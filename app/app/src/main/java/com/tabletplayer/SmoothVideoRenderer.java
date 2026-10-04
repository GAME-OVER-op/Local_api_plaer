package com.tabletplayer;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GLES2 temporal interpolation. VLC renders to inputSurface; this worker alone owns all GL
 * objects and presents the two most recent pictures to a TextureView on display vsync.
 * No CPU pixel readback, video encoder, GLES3 dependency, or continuous paused rendering.
 */
final class SmoothVideoRenderer implements Choreographer.FrameCallback {
    interface Listener {
        void onReady(SmoothVideoRenderer owner, Surface input);
        void onFallback(SmoothVideoRenderer owner, String reason);
        void onAudioDelay(SmoothVideoRenderer owner, long microseconds);
    }

    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("VideoInterpolation");
    private Handler worker;
    private volatile boolean closed;
    private volatile boolean presented;
    private Choreographer clock;
    private boolean scheduled, playing, pending, enabled, failed;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE, window = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private SurfaceTexture inputTexture;
    private Surface inputSurface, outputSurface;
    private int oes, copyProgram, blendProgram, fbo;
    private int[] history = new int[2];
    private int current, pictures, captureWidth = 640, captureHeight = 360;
    private int allocatedWidth, allocatedHeight, outputWidth, outputHeight, aspectMode;
    private int naturalWidth = 640, naturalHeight = 360;
    private double fps;
    private float rate = 1f, videoAspect = 16f / 9f;
    private long intervalNs, previousNs, currentNs, lastArrivalNs, previousStamp;
    private long lastVsync, budgetStart;
    private long lastPresentedNs;
    private int budgetFrames, budgetLate;
    private final float[] matrix = new float[16];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    private static final String VERTEX =
            "attribute vec2 aPosition; attribute vec2 aUv; varying vec2 vUv;" +
            "void main(){ gl_Position=vec4(aPosition,0.0,1.0); vUv=aUv; }";
    private static final String COPY =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying vec2 vUv; uniform samplerExternalOES image;" +
            "uniform mat4 transform; void main(){" +
            "gl_FragColor=texture2D(image,(transform*vec4(vUv,0.0,1.0)).xy);}";
    private static final String BLEND =
            "precision mediump float; varying vec2 vUv; uniform sampler2D previousImage;" +
            "uniform sampler2D currentImage; uniform float position; void main(){" +
            "vec4 p=texture2D(previousImage,vUv); vec4 c=texture2D(currentImage,vUv);" +
            "vec3 d=abs(c.rgb-p.rgb); float change=max(d.r,max(d.g,d.b));" +
            // Protect hard changes. Exact endpoints remain the original pictures.
            "float reactive=smoothstep(0.20,0.70,change)*0.85;" +
            "float t=mix(position,step(0.5,position),reactive);" +
            "gl_FragColor=mix(p,c,t);}";

    SmoothVideoRenderer(Listener listener) {
        this.listener = listener;
        quad.put(new float[]{-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1}).position(0);
    }

    void start() {
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            if (closed) return;
            try {
                initGl();
                clock = Choreographer.getInstance();
                // Construct on this Looper: API19's single-argument listener stays off the UI thread.
                inputTexture = new SurfaceTexture(oes);
                inputTexture.setDefaultBufferSize(captureWidth, captureHeight);
                inputTexture.setOnFrameAvailableListener(texture -> {
                    if (closed) return;
                    if (failed) {
                        // Keep the producer unblocked until VLC detaches for fallback.
                        try { makeCurrent(pbuffer); texture.updateTexImage(); } catch (Throwable ignored) {}
                    } else { pending = true; schedule(); }
                });
                inputSurface = new Surface(inputTexture);
                listener.onReady(this, inputSurface);
            } catch (Throwable error) { fail("GPU: " + error.getClass().getSimpleName()); }
        });
    }

    void setOutput(SurfaceTexture texture, int width, int height) {
        post(() -> {
            releaseWindow();
            if (texture == null) return;
            try {
                outputSurface = new Surface(texture);
                window = EGL14.eglCreateWindowSurface(display, config, outputSurface,
                        new int[]{EGL14.EGL_NONE}, 0);
                require(window != EGL14.EGL_NO_SURFACE, "window surface");
                outputWidth = width; outputHeight = height;
                schedule();
            } catch (Throwable error) { fail("Поверхность GPU недоступна"); }
        });
    }

    void resizeOutput(int width, int height) {
        post(() -> { outputWidth = width; outputHeight = height; schedule(); });
    }

    void configure(double sourceFps, int width, int height, int originalWidth, int originalHeight,
                   float displayAspect, float speed, int aspect) {
        post(() -> {
            boolean changed = fps != sourceFps || rate != speed || captureWidth != width || captureHeight != height;
            fps = sourceFps; rate = speed; videoAspect = displayAspect; aspectMode = aspect;
            naturalWidth = Math.max(2, originalWidth); naturalHeight = Math.max(2, originalHeight);
            enabled = FrameTiming.shouldSmooth(fps) && fps * rate < 50;
            intervalNs = FrameTiming.validFps(fps) ? (long) (1_000_000_000.0 / (fps * rate)) : 0;
            captureWidth = Math.max(2, width); captureHeight = Math.max(2, height);
            try {
                if (inputTexture != null) inputTexture.setDefaultBufferSize(captureWidth, captureHeight);
                if (changed) resetHistory();
                listener.onAudioDelay(this, enabled ? intervalNs / 1000 : 0);
                schedule();
            } catch (Throwable error) { fail("Размер кадра недоступен"); }
        });
    }

    void setPlaying(boolean value) {
        post(() -> {
            if (playing != value) {
                playing = value;
                resetHistory();
                if (!value && scheduled) { clock.removeFrameCallback(this); scheduled = false; }
                if (value) schedule();
            }
        });
    }

    void reset() { post(this::resetHistory); }

    boolean hasPresentedFrame() { return presented; }

    private void resetHistory() {
        pictures = 0; previousNs = currentNs = lastArrivalNs = previousStamp = 0;
        lastVsync = budgetStart = 0; budgetFrames = budgetLate = 0;
    }

    private void post(Runnable action) {
        if (worker != null && !closed) worker.post(() -> { if (!closed && !failed) action.run(); });
    }

    private void schedule() {
        if (clock != null && !scheduled && !closed && !failed) {
            scheduled = true; clock.postFrameCallback(this);
        }
    }

    @Override public void doFrame(long vsyncNs) {
        scheduled = false;
        if (closed || failed) return;
        try {
            makeCurrent(pbuffer);
            if (pending) {
                pending = false;
                inputTexture.updateTexImage();
                inputTexture.getTransformMatrix(matrix);
                long arrival = System.nanoTime(), stamp = inputTexture.getTimestamp();
                if (allocatedWidth != captureWidth || allocatedHeight != captureHeight) allocateHistory();
                // The producer may supply media PTS or presentation-clock timestamps. Use deltas
                // only while they agree with arrival pacing; never blend across a seek or a stall.
                long gap = stamp - previousStamp;
                long arrivalGap = arrival - lastArrivalNs;
                if (pictures > 0 && (arrivalGap > Math.max(250_000_000L, intervalNs * 3)
                        || gap < 0 || gap > Math.max(250_000_000L, intervalNs * 3))) resetHistory();
                previousNs = currentNs;
                long delta = gap > 0 && Math.abs(gap - arrivalGap) < Math.max(5_000_000L, intervalNs / 2)
                        ? gap : arrivalGap;
                if (gap > 0 && rate != 1f) {
                    long scaled = (long) (gap / rate);
                    if (Math.abs(scaled - arrivalGap) < Math.abs(delta - arrivalGap)) delta = scaled;
                }
                currentNs = pictures == 0 ? arrival : previousNs + delta;
                if (Math.abs(currentNs - arrival) > Math.max(50_000_000L, intervalNs)) currentNs = arrival;
                lastArrivalNs = arrival; previousStamp = stamp;
                current = 1 - current;
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, history[current], 0);
                require(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE, "FBO");
                GLES20.glViewport(0, 0, captureWidth, captureHeight);
                useQuad(copyProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "image"), 0);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProgram, "transform"), 1, false, matrix, 0);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                pictures = Math.min(2, pictures + 1);
            }
            if (window != EGL14.EGL_NO_SURFACE && pictures > 0 && outputWidth > 0 && outputHeight > 0
                    && (!enabled || lastPresentedNs == 0
                    || vsyncNs - lastPresentedNs >= FrameTiming.OUTPUT_INTERVAL_NS * 9 / 10)) {
                makeCurrent(window);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glViewport(0, 0, outputWidth, outputHeight);
                GLES20.glClearColor(0, 0, 0, 1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                setViewport();
                useQuad(blendProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, history[pictures == 2 ? 1-current : current]);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "previousImage"), 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, history[current]);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "currentImage"), 1);
                float t = enabled && pictures == 2
                        ? FrameTiming.alpha(vsyncNs - intervalNs, previousNs, currentNs) : 1;
                GLES20.glUniform1f(GLES20.glGetUniformLocation(blendProgram, "position"), t);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "GL draw");
                require(EGL14.eglSwapBuffers(display, window), "swap");
                lastPresentedNs = vsyncNs; presented = true;
                measureBudget(vsyncNs);
            }
            // Continue only while there are frames to interpolate. A starved source wakes on
            // its next frame; a paused or hidden player has no 60-Hz worker loop.
            if (playing && enabled && pictures > 0 && window != EGL14.EGL_NO_SURFACE
                    && vsyncNs - lastArrivalNs < Math.max(250_000_000L, intervalNs * 2)) schedule();
        } catch (Throwable error) { fail("Ошибка обработки GPU: " + error.getClass().getSimpleName()); }
    }

    private void measureBudget(long now) {
        if (!playing || !enabled || pictures < 2) return;
        if (budgetStart == 0) { budgetStart = now; lastVsync = now; return; }
        // Ignore the initial warm-up, configuration changes, and buffering stalls.
        if (now - budgetStart > 1_000_000_000L && now - lastArrivalNs < intervalNs * 2) {
            budgetFrames++;
            if (now - lastVsync > FrameTiming.OUTPUT_INTERVAL_NS * 3 / 2) budgetLate++;
        }
        lastVsync = now;
        if (now - budgetStart >= 3_000_000_000L) {
            if (budgetFrames >= 30 && budgetLate * 5 > budgetFrames) fail("GPU не успевает выводить 60 FPS");
            budgetStart = now; budgetFrames = budgetLate = 0;
        }
    }

    private void setViewport() {
        float ratio = videoAspect;
        if (aspectMode == 1) ratio = 16f/9f;
        if (aspectMode == 2) ratio = 4f/3f;
        if (aspectMode == 3) return;
        int width = outputWidth, height = Math.round(width / ratio);
        if (height > outputHeight) { height = outputHeight; width = Math.round(height * ratio); }
        if (aspectMode == 4) { width = naturalWidth; height = naturalHeight; }
        GLES20.glViewport((outputWidth-width)/2, (outputHeight-height)/2, width, height);
    }

    private void allocateHistory() {
        if (history[0] != 0) GLES20.glDeleteTextures(2, history, 0);
        GLES20.glGenTextures(2, history, 0);
        for (int texture : history) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            textureParameters(GLES20.GL_TEXTURE_2D);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, captureWidth, captureHeight,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        }
        allocatedWidth = captureWidth; allocatedHeight = captureHeight; resetHistory();
    }

    private void initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        require(EGL14.eglInitialize(display, version, 0, version, 1), "EGL init");
        EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0) && count[0] > 0, "EGL config");
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        require(context != EGL14.EGL_NO_CONTEXT, "GLES2 context");
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
        require(pbuffer != EGL14.EGL_NO_SURFACE, "pbuffer"); makeCurrent(pbuffer);
        int[] ids = new int[1]; GLES20.glGenTextures(1, ids, 0); oes = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes);
        textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        copyProgram = program(VERTEX, COPY); blendProgram = program(VERTEX, BLEND);
        GLES20.glGenFramebuffers(1, ids, 0); fbo = ids[0];
    }

    private void makeCurrent(EGLSurface surface) {
        require(EGL14.eglMakeCurrent(display, surface, surface, context), "make current");
    }

    private void useQuad(int program) {
        GLES20.glUseProgram(program);
        int position = GLES20.glGetAttribLocation(program,"aPosition"), uv = GLES20.glGetAttribLocation(program,"aUv");
        quad.position(0); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,quad);
        GLES20.glEnableVertexAttribArray(position);
        quad.position(2); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,quad);
        GLES20.glEnableVertexAttribArray(uv);
    }

    private static void textureParameters(int target) {
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader);
        int[] ok = new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);
        if (ok[0] == 0) { GLES20.glDeleteShader(shader); throw new IllegalStateException("shader"); }
        return shader;
    }

    private static int program(String vertex, String fragment) {
        int v = shader(GLES20.GL_VERTEX_SHADER,vertex), f = shader(GLES20.GL_FRAGMENT_SHADER,fragment);
        int p = GLES20.glCreateProgram(); GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f);
        int[] ok = new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        require(ok[0] != 0,"link"); return p;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private void fail(String reason) {
        if (failed || closed) return;
        failed = true;
        if (clock != null && scheduled) clock.removeFrameCallback(this);
        scheduled = false;
        listener.onFallback(this,reason);
        // Do not release the decoder surface until VLC has detached on the UI thread.
    }

    private void releaseWindow() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) makeCurrent(pbuffer);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
        }
        window = EGL14.EGL_NO_SURFACE;
        if (outputSurface != null) { outputSurface.release(); outputSurface = null; }
    }

    void close() {
        if (closed) return;
        closed = true;
        if (worker == null) return;
        worker.post(() -> {
            try {
                if (clock != null) clock.removeFrameCallback(this);
                try { releaseWindow(); } catch (Throwable ignored) {}
                if (inputSurface != null) inputSurface.release();
                if (inputTexture != null) inputTexture.release();
                if (context != EGL14.EGL_NO_CONTEXT) {
                    GLES20.glDeleteTextures(2,history,0);
                    GLES20.glDeleteTextures(1,new int[]{oes},0);
                    GLES20.glDeleteFramebuffers(1,new int[]{fbo},0);
                    GLES20.glDeleteProgram(copyProgram); GLES20.glDeleteProgram(blendProgram);
                    EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(display,pbuffer); EGL14.eglDestroyContext(display,context);
                }
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display);
                EGL14.eglReleaseThread();
            } finally { thread.quitSafely(); }
        });
    }
}
