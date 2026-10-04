package com.tabletplayer;

import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import org.videolan.libvlc.interfaces.IMedia;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class PlayerActivity extends AppCompatActivity {
    private enum SourceMode {
        LOCAL_FILE,
        DIRECT_REMOTE,
        LOCAL_CACHE
    }

    private enum PlaybackPhase {
        IDLE,
        PREPARING,
        STARTING_LOCAL,
        PLAYING_LOCAL,
        SWITCHING_TO_DIRECT,
        PLAYING_DIRECT,
        STOPPING,
        DESTROYED
    }

    private static final int JUMP_MS = 10000;
    private static final int AUTO_HIDE_MS = 3500;
    private static final long SWIPE_FULL_WIDTH_MS = 120000;
    private static final int NET_CACHING = 4000;
    private static final long POS_SAVE_INTERVAL_MS = 7000;
    private static final float TOP_GESTURE_DEAD_ZONE = 0.20f;
    private static final int DECODER_AUTO = 0;
    private static final int DECODER_HARDWARE = 1;
    private static final int DECODER_SOFTWARE = 2;

    private String base, path, name, folder, serverName, serverId;
    private String historyKey = "", mediaVersion = "";
    private boolean preparingIdentity, askSavedPosition;
    private long requestedResumeMs;
    private boolean local = false;

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private TextureView smoothView;
    private SmoothVideoRenderer smoothRenderer;
    private Surface smoothInput;
    private boolean smoothDisabledForSource, smoothSetting, activityActive = true;
    private String smoothStatus = "", smoothConfiguration = "";
    private double sourceFps;
    private int smoothTrackId = -1;
    private String pendingSmoothFallback = "";
    private Uri smoothMediaUri;
    private Thread fpsProbe;
    private boolean fpsProbeStarted;
    private static final java.util.concurrent.atomic.AtomicBoolean FPS_PROBE_BUSY = new java.util.concurrent.atomic.AtomicBoolean();
    private int fpsProbeEpoch;
    private long smoothAudioDelayUs, appliedSmoothAudioDelayUs = Long.MIN_VALUE;
    private boolean pauseAfterSmoothRestart;
    private final Runnable smoothStartupTimeout = this::checkSmoothStartup;
    private final Runnable fpsProbeTimeout = () -> {
        if (smoothRenderer != null && !FrameTiming.validFps(sourceFps)) fallbackSmooth("Анализ частоты не завершён");
    };
    private View controls, gestureOverlay, buffering;
    private View playerHeader;
    private TextView batteryBadge;
    private HeadsetBatteryMonitor batteryMonitor;
    private TextView time, gestureInfo, bufferingText, titleBar, cacheBadge, technicalCard;
    private android.widget.Button retryBtn;
    private SeekBar seek;
    private ImageButton prev, rew, playPause, fwd, next, aspect, fullscreen;
    private android.widget.Button speed, fwd90;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long duration = 0, currentMs = 0, pendingResumeMs = 0, seekPreview = 0;
    private boolean started = false, controlsVisible = true, dragging = false, immersive = true;
    private int mode = 0; // 0 none, 1 seek, 2 volume
    private long dragStartTime = 0;
    private int dragStartVol = 100, volume = 100, reconnectAttempts = 0;
    private boolean reconnecting = false;
    private boolean ignoreGestureSequence = false;
    private boolean videoViewsAttached = false;
    private boolean wasPlayingBeforeStop = false;
    private int lastVideoWidth = -1, lastVideoHeight = -1;
    private int decoderMode = DECODER_AUTO;
    private int networkCachingMs = Store.LIBVLC_DEFAULT_CACHING_MS;
    private int fileCachingMs = Store.LIBVLC_DEFAULT_CACHING_MS;
    private int localCachingMs = Store.LIBVLC_DEFAULT_CACHING_MS;
    private int contentLoadMode = Store.CONTENT_LOAD_AUTO;
    private boolean libVlcCatchUpFrames = true;
    private boolean libVlcAvcodecFast = false;
    private String libVlcSkipLoopFilter = "off";
    private boolean localCacheStartupWaiting = false;
    private long localCacheStartupBaseMs = 0;
    private int lastBufferingBucket = -1;
    private long resumeMs = 0;
    private long lastPosSaveAt = 0;
    private long lastSavedPosition = -1;
    private long lastTechnicalCardUpdateMs = 0;
    private long lastMediaButtonAt = 0;
    private int lastMediaButtonKey = 0;
    private int currentVoutCount = 0;
    private boolean completedCurrent = false;
    private SourceMode sourceMode = SourceMode.DIRECT_REMOTE;
    private PlaybackPhase playbackPhase = PlaybackPhase.IDLE;
    private int playbackGeneration = 0;
    private boolean terminalHandled = false;
    private boolean destroyed = false;
    private PlaybackCacheTask cacheTask;
    private PlaybackCacheManager.Entry activeCacheEntry;
    private PlaybackProxyServer cacheProxy;
    private String cacheProxyUrl;
    private String currentMediaOverrideUri;
    private long cacheSeekWaitMs = 0;
    private long cacheSeekStartedAtMs = 0;
    private long cacheSeekTargetEndBytes = 0;
    private boolean directFallbackInProgress = false;
    private final Set<String> directOnlyThisSession = new HashSet<>();
    private Thread prefetchThread;
    private volatile int prefetchGeneration = 0;
    private volatile PlaybackCacheTask prefetchTask;
    private final Object prefetchLock = new Object();

    private final float[] speeds = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f};
    private int speedIdx = 0;
    private final String[] aspectNames = {"По размеру", "16:9", "4:3", "Растянуть", "Оригинал"};
    private final String[] decoderNames = {"Автоматически", "Аппаратный", "Программный"};
    private int aspectIdx = 0;

    private final List<String> episodePaths = new ArrayList<>();
    private final List<String> episodeNames = new ArrayList<>();
    private int episodeIndex = -1;
    private boolean hasQueue = false;

    private MediaSessionCompat session;
    private AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener focusListener = focus -> {
        if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (player != null && player.isPlaying()) setPlaying(false);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateTime();
            ui.postDelayed(this, 500);
        }
    };
    private final Runnable hideRunnable = this::hideControls;
    private final Runnable reconnectAgain = new Runnable() {
        @Override
        public void run() {
            reconnectStep();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        installFullscreenGuards();
        scheduleImmersiveReapply();

        base = getIntent().getStringExtra("base");
        path = getIntent().getStringExtra("path");
        name = getIntent().getStringExtra("name");
        folder = getIntent().getStringExtra("folder");
        serverName = getIntent().getStringExtra("server_name");
        serverId = getIntent().getStringExtra("server_id");
        local = getIntent().getBooleanExtra("local", false);
        if (folder == null) folder = "";
        if (serverName == null) serverName = "";
        if (serverId == null) serverId = "";

        String[] queuePathsExtra = getIntent().getStringArrayExtra("queue_paths");
        String[] queueNamesExtra = getIntent().getStringArrayExtra("queue_names");
        if (queuePathsExtra != null && queueNamesExtra != null && queuePathsExtra.length > 0
                && queuePathsExtra.length == queueNamesExtra.length) {
            hasQueue = true;
            for (int qi = 0; qi < queuePathsExtra.length; qi++) {
                episodePaths.add(queuePathsExtra[qi]);
                episodeNames.add(queueNamesExtra[qi]);
            }
        }

        volume = Store.getVolume(this, 100);
        aspectIdx = Store.getAspect(this, 0);
        decoderMode = Store.getDecoderMode(this, DECODER_AUTO);
        loadLibVlcSettings();
        videoLayout = findViewById(R.id.video_layout);
        smoothView = findViewById(R.id.smooth_video_view);
        smoothSetting = Store.getSmoothVideo(this);
        smoothView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
                if (smoothRenderer != null) smoothRenderer.setOutput(texture, w, h);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
                if (smoothRenderer != null) smoothRenderer.resizeOutput(w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                if (smoothRenderer != null) smoothRenderer.setOutput(null, 0, 0);
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        controls = findViewById(R.id.controls);
        gestureOverlay = findViewById(R.id.gesture_overlay);
        buffering = findViewById(R.id.buffering);
        bufferingText = findViewById(R.id.buffering_text);
        cacheBadge = findViewById(R.id.cache_badge);
        playerHeader = findViewById(R.id.player_header);
        playerHeader.addOnLayoutChangeListener((v,l,t,r,bottom,ol,ot,or,ob) -> {
            if (technicalCard != null && bottom > t) {
                android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) technicalCard.getLayoutParams();
                int margin = bottom - t + Math.round(8 * getResources().getDisplayMetrics().density);
                if (params.topMargin != margin) { params.topMargin = margin; technicalCard.setLayoutParams(params); }
            }
        });
        batteryBadge = findViewById(R.id.battery_badge);
        batteryMonitor = new HeadsetBatteryMonitor(this, (connected, deviceName, percentage) -> {
            batteryBadge.setVisibility(connected ? View.VISIBLE : View.GONE);
            batteryBadge.setText(percentage >= 0 ? percentage + "%" : "—%");
            batteryBadge.setTextColor(percentage >= 0 && percentage <= 20 ? 0xFFFFCE7A : 0xFFFFFFFF);
            batteryBadge.setContentDescription("Заряд наушников " + (deviceName == null ? "" : deviceName)
                    + ": " + (percentage >= 0 ? percentage + "%" : "недоступен"));
        });
        technicalCard = findViewById(R.id.technical_card);
        retryBtn = findViewById(R.id.buffer_retry);
        titleBar = findViewById(R.id.title_bar);
        gestureInfo = findViewById(R.id.gesture_info);
        time = findViewById(R.id.time);
        seek = findViewById(R.id.seek);
        prev = findViewById(R.id.prev);
        rew = findViewById(R.id.rew);
        playPause = findViewById(R.id.play_pause);
        fwd = findViewById(R.id.fwd);
        fwd90 = findViewById(R.id.fwd90);
        next = findViewById(R.id.next);
        speed = findViewById(R.id.speed);
        aspect = findViewById(R.id.aspect);
        fullscreen = findViewById(R.id.fullscreen);

        videoLayout.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int w = right - left;
            int h = bottom - top;
            if (w > 0 && h > 0 && (w != lastVideoWidth || h != lastVideoHeight)) {
                lastVideoWidth = w;
                lastVideoHeight = h;
                ui.postDelayed(() -> {
                    attachVideoViewsIfNeeded();
                    applyAspect();
                    PlayerDiagnostics.log(this, "layout", "video " + lastVideoWidth + "x" + lastVideoHeight + " aspect=" + aspectIdx);
                }, 120L);
            }
        });

        retryBtn.setOnClickListener(v -> {
            retryBtn.setVisibility(View.GONE);
            if (preparingIdentity) {
                preparePlayback(path, name, requestedResumeMs, askSavedPosition);
                return;
            }
            reconnectAttempts = 0;
            reconnectStep();
        });

        prev.setOnClickListener(v -> { playPrev(); showControls(); });
        rew.setOnClickListener(v -> { seekRelative(-JUMP_MS); showControls(); });
        playPause.setOnClickListener(v -> { togglePlay(); showControls(); });
        fwd.setOnClickListener(v -> { seekRelative(JUMP_MS); showControls(); });
        fwd90.setOnClickListener(v -> { seekRelative(Store.getLongJumpSeconds(this) * 1000); showControls(); });
        updateLongJumpButton();
        next.setOnClickListener(v -> { playNext(); showControls(); });
        speed.setOnClickListener(v -> { cycleSpeed(); showControls(); });
        aspect.setOnClickListener(v -> { cycleAspect(); showControls(); });
        fullscreen.setOnClickListener(v -> { toggleImmersive(); showControls(); });
        playPause.setOnLongClickListener(v -> {
            showDecoderDialog();
            return true;
        });
        aspect.setOnLongClickListener(v -> {
            showDecoderDialog();
            return true;
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (duration > 0 && player != null) requestSeek(sb.getProgress() * duration / 1000);
                showControls();
            }
        });

        setupGestures();
        initPlayer();
        setupSession();
        askResume();
        if (!local) {
            if (hasQueue) updateEpisodeIndex();
            else fetchEpisodes();
        }
    }

    private void loadLibVlcSettings() {
        networkCachingMs = Store.getLibVlcNetworkCaching(this);
        fileCachingMs = Store.getLibVlcFileCaching(this);
        localCachingMs = Store.getLibVlcLocalCaching(this);
        contentLoadMode = Store.getContentLoadMode(this);
        libVlcCatchUpFrames = Store.getLibVlcCatchUpFrames(this);
        libVlcAvcodecFast = Store.getLibVlcAvcodecFast(this);
        libVlcSkipLoopFilter = Store.getLibVlcSkipLoopFilter(this);
    }

    private void initPlayer() {
        loadLibVlcSettings();
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=" + networkCachingMs);
        options.add("--file-caching=" + fileCachingMs);
        if (libVlcCatchUpFrames) {
            options.add("--no-drop-late-frames");
            options.add("--no-skip-frames");
        }
        if (libVlcAvcodecFast) {
            options.add("--avcodec-fast");
        }
        if (!"off".equals(libVlcSkipLoopFilter)) {
            options.add("--avcodec-skiploopfilter=" + libVlcSkipLoopFilter);
        }
        libVLC = new LibVLC(this, options);
        setPlaybackPhase(PlaybackPhase.IDLE);
        PlayerDiagnostics.log(this, "libvlc", "init networkCaching=" + networkCachingMs
                + " fileCaching=" + fileCachingMs
                + " localCaching=" + localCachingMs
                + " catchUpFrames=" + libVlcCatchUpFrames
                + " fast=" + libVlcAvcodecFast
                + " skipLoop=" + libVlcSkipLoopFilter);
    }

    private void beginVideoOutput(final MediaPlayer mp, final int generation) {
        if (!smoothSetting || smoothDisabledForSource) {
            mp.attachViews(videoLayout, null, false, false);
            videoViewsAttached = true;
            if (activityActive) mp.play();
            mp.setRate(speeds[speedIdx]);
            return;
        }
        smoothStatus = "Сглаживание: анализ видео…";
        smoothView.setVisibility(View.VISIBLE);
        videoLayout.setVisibility(View.GONE);
        smoothRenderer = new SmoothVideoRenderer(new SmoothVideoRenderer.Listener() {
            @Override public void onReady(SmoothVideoRenderer owner, Surface input) {
                ui.post(() -> {
                    if (!isCurrentGeneration(generation) || player != mp || smoothRenderer != owner) return;
                    try {
                        smoothInput = input;
                        if (!activityActive) return;
                        mp.getVLCVout().setVideoSurface(input, null);
                        mp.getVLCVout().attachViews();
                        videoViewsAttached = true;
                        mp.getVLCVout().setWindowSize(640, 360);
                        if (activityActive) {
                            mp.play(); mp.setRate(speeds[speedIdx]);
                            ui.postDelayed(smoothStartupTimeout, 10000);
                        }
                    } catch (Throwable error) { fallbackSmooth("Вывод GPU недоступен"); }
                });
            }
            @Override public void onFallback(SmoothVideoRenderer owner, String reason) {
                ui.post(() -> {
                    if (isCurrentGeneration(generation) && smoothRenderer == owner) fallbackSmooth(reason);
                });
            }
            @Override public void onAudioDelay(SmoothVideoRenderer owner, long microseconds) {
                ui.post(() -> {
                    if (isCurrentGeneration(generation) && smoothRenderer == owner && player == mp) {
                        smoothAudioDelayUs = microseconds;
                        applySmoothAudioDelay();
                    }
                });
            }
        });
        smoothRenderer.start();
        if (smoothView.isAvailable()) smoothRenderer.setOutput(smoothView.getSurfaceTexture(),
                smoothView.getWidth(), smoothView.getHeight());
    }

    private IMedia.VideoTrack selectedVideoTrack() {
        if (player == null) return null;
        IMedia media = player.getMedia();
        if (media == null) return null;
        try {
            int selected = player.getVideoTrack();
            for (int i = 0; i < media.getTrackCount(); i++) {
                IMedia.Track track = media.getTrack(i);
                if (track instanceof IMedia.VideoTrack && track.id == selected) return (IMedia.VideoTrack) track;
            }
            return null;
        } finally { media.release(); }
    }

    private void checkSmoothStartup() {
        if (smoothRenderer != null && activityActive && (!videoViewsAttached || !smoothRenderer.hasPresentedFrame()))
            fallbackSmooth("Не удалось запустить вывод GPU");
    }

    private void applySmoothAudioDelay() {
        if (player == null || smoothRenderer == null || !started || player.getAudioTracksCount() == 0) return;
        if (appliedSmoothAudioDelayUs != smoothAudioDelayUs) {
            if (player.setAudioDelay(smoothAudioDelayUs)) appliedSmoothAudioDelayUs = smoothAudioDelayUs;
            else fallbackSmooth("Не удалось синхронизировать звук");
        }
    }

    private void updateSmoothMetadata() {
        if (smoothRenderer == null || player == null || !activityActive) return;
        try {
            IMedia.VideoTrack track = selectedVideoTrack();
            if (track == null) return;
            if (smoothTrackId != track.id) {
                smoothTrackId = track.id;
                sourceFps = 0; smoothConfiguration = "";
                fpsProbeEpoch++;
                if (fpsProbe != null) fpsProbe.interrupt();
                fpsProbeStarted = false;
            }
            if (track.frameRateDen > 0 && track.frameRateNum > 0)
                sourceFps = (double) track.frameRateNum / track.frameRateDen;
            if (track.projection != IMedia.VideoTrack.Projection.Rectangular) {
                fallbackSmooth("Объёмное видео: обычный вывод"); return;
            }
            if (!FrameTiming.validFps(sourceFps)) {
                probeSmoothFps();
                return;
            }
            if (!FrameTiming.shouldSmooth(sourceFps)) {
                fallbackSmooth(String.format(java.util.Locale.US, "%.2f FPS: обычный вывод", sourceFps)); return;
            }
            ui.removeCallbacks(fpsProbeTimeout);
            float ratio = track.height > 0 ? (float) track.width / track.height : 16f / 9f;
            if (track.sarDen > 0 && track.sarNum > 0) ratio *= (float) track.sarNum / track.sarDen;
            if (track.orientation >= IMedia.VideoTrack.Orientation.LeftTop) ratio = 1f / ratio;
            if (!(ratio > 0.05f && ratio < 20f)) ratio = 16f / 9f;
            int width = Math.min(1280, Math.max(640, Math.max(smoothView.getWidth(), smoothView.getHeight())));
            int height = Math.max(2, Math.round(width / ratio));
            if (height > 1280) { height = 1280; width = Math.max(2, Math.round(height * ratio)); }
            String key = sourceFps + ":" + width + ":" + height + ":" + ratio + ":" + speeds[speedIdx] + ":" + aspectIdx;
            if (!key.equals(smoothConfiguration)) {
                smoothConfiguration = key;
                player.getVLCVout().setWindowSize(width, height);
                int naturalHeight = Math.max(2, track.orientation >= IMedia.VideoTrack.Orientation.LeftTop ? track.width : track.height);
                int naturalWidth = Math.max(2, Math.round(naturalHeight * ratio));
                smoothRenderer.configure(sourceFps, width, height, naturalWidth, naturalHeight, ratio, speeds[speedIdx], aspectIdx);
                smoothStatus = String.format(java.util.Locale.US, speeds[speedIdx] * sourceFps < 50
                        ? "Сглаживание: %.2f → 60 FPS" : "%.2f FPS · без генерации на этой скорости", sourceFps);
                PlayerDiagnostics.log(this, "smoothing", smoothStatus);
            }
        } catch (Throwable error) { fallbackSmooth("Не удалось прочитать параметры видео"); }
    }

    private void probeSmoothFps() {
        if (fpsProbeStarted || smoothMediaUri == null) return;
        fpsProbeStarted = true;
        if (!FPS_PROBE_BUSY.compareAndSet(false, true)) { fallbackSmooth("Анализатор частоты занят"); return; }
        final int generation = playbackGeneration;
        final int epoch = ++fpsProbeEpoch;
        final SmoothVideoRenderer owner = smoothRenderer;
        final Uri uri = sourceMode == SourceMode.DIRECT_REMOTE ? Uri.parse(streamUrl(path)) : smoothMediaUri;
        fpsProbe = new Thread(() -> {
            double result = 0;
            try (TransferCoordinator.Lease lease = TransferCoordinator.get().acquire(
                    TransferCoordinator.Priority.PLAYBACK_METADATA, "video FPS")) {
                result = VideoFrameRateProbe.read(uri);
            } catch (Exception ignored) {} finally { FPS_PROBE_BUSY.set(false); }
            final double detected = result;
            ui.post(() -> {
                if (!isCurrentGeneration(generation) || smoothRenderer != owner || epoch != fpsProbeEpoch) return;
                if (FrameTiming.validFps(sourceFps)) return;
                if (!FrameTiming.validFps(detected)) { fallbackSmooth("Частота видео неизвестна"); return; }
                sourceFps = detected;
                updateSmoothMetadata();
            });
        }, "VideoFrameRateProbe");
        fpsProbe.setDaemon(true);
        fpsProbe.start();
        ui.postDelayed(fpsProbeTimeout, 8000);
    }

    private void fallbackSmooth(String reason) {
        if (destroyed || player == null || smoothRenderer == null) return;
        if (!activityActive) { pendingSmoothFallback = reason; return; }
        smoothDisabledForSource = true;
        smoothStatus = "Сглаживание отключено: " + reason;
        PlayerDiagnostics.log(this, "smoothing-fallback", reason);
        long position = Math.max(pendingResumeMs, Math.max(0, player.getTime()));
        boolean paused = started && !player.isPlaying();
        String override = currentMediaOverrideUri;
        SourceMode source = sourceMode;
        startSource(path, name, position, source, true, override);
        pauseAfterSmoothRestart = paused;
    }

    private void releaseSmoothRenderer() {
        ui.removeCallbacks(smoothStartupTimeout);
        ui.removeCallbacks(fpsProbeTimeout);
        fpsProbeEpoch++;
        if (fpsProbe != null) { fpsProbe.interrupt(); fpsProbe = null; }
        fpsProbeStarted = false;
        SmoothVideoRenderer renderer = smoothRenderer;
        smoothRenderer = null; smoothInput = null;
        if (renderer != null) renderer.close();
        sourceFps = 0; smoothConfiguration = ""; smoothMediaUri = null;
        smoothTrackId = -1; pendingSmoothFallback = "";
        smoothAudioDelayUs = 0; appliedSmoothAudioDelayUs = Long.MIN_VALUE;
        if (smoothView != null) smoothView.setVisibility(View.GONE);
        if (videoLayout != null) videoLayout.setVisibility(View.VISIBLE);
    }

    private MediaPlayer createMediaPlayer(final int generation) {
        MediaPlayer mp = new MediaPlayer(libVLC);
        PlayerDiagnostics.log(this, "player", "create gen=" + generation);
        mp.setEventListener(event -> {
            final int type = event.type;
            final float pct = (type == MediaPlayer.Event.Buffering) ? event.getBuffering() : 0f;
            final int vout = (type == MediaPlayer.Event.Vout) ? event.getVoutCount() : -1;
            ui.post(() -> handlePlayerEvent(generation, type, pct, vout));
        });
        return mp;
    }

    private void releaseCurrentPlayer() {
        MediaPlayer old = player;
        player = null;
        if (old == null) { releaseSmoothRenderer(); return; }
        try { old.setEventListener(null); } catch (Throwable ignored) {}
        try { old.stop(); } catch (Throwable ignored) {}
        try { old.detachViews(); } catch (Throwable ignored) {}
        try { old.getVLCVout().detachViews(); } catch (Throwable ignored) {}
        videoViewsAttached = false;
        try { old.release(); } catch (Throwable ignored) {}
        releaseSmoothRenderer();
        PlayerDiagnostics.log(this, "player", "release");
    }

    private void setPlaybackPhase(PlaybackPhase phase) {
        if (playbackPhase != phase) {
            PlayerDiagnostics.log(this, "phase", playbackPhase + " -> " + phase + " source=" + sourceMode + " gen=" + playbackGeneration);
        }
        playbackPhase = phase;
    }

    private boolean isCurrentGeneration(int generation) {
        return !destroyed && generation == playbackGeneration && playbackPhase != PlaybackPhase.DESTROYED;
    }

    private boolean beginTerminalHandling(int generation) {
        if (!isCurrentGeneration(generation)) return false;
        if (terminalHandled) return false;
        terminalHandled = true;
        return true;
    }

    private void startSource(String p, String nm, long resume, SourceMode mode, boolean reconnectStart) {
        startSource(p, nm, resume, mode, reconnectStart, null);
    }

    private void startSource(String p, String nm, long resume, SourceMode mode, boolean reconnectStart, String mediaOverrideUri) {
        if (destroyed || libVLC == null) return;
        if (mode != SourceMode.LOCAL_CACHE) {
            stopPlaybackCache(false);
        }
        int generation = ++playbackGeneration;
        pauseAfterSmoothRestart = false;
        ui.removeCallbacks(reconnectAgain);
        path = p;
        name = nm;
        if (!local && base != null && p != null) {
            UiStore.setLastPlayed(this, serverId, base, p);
            UiStore.addRecentVideo(this, serverId, base, p, nm);
        }
        sourceMode = mode;
        currentMediaOverrideUri = mediaOverrideUri;
        PlayerDiagnostics.log(this, "start", "gen=" + generation + " mode=" + mode + " resume=" + resume + " path=" + p + " override=" + (mediaOverrideUri != null));
        pendingResumeMs = resume;
        currentMs = 0;
        duration = 0;
        seekPreview = 0;
        cacheSeekWaitMs = 0;
        if (seek != null) {
            seek.setProgress(0);
            seek.setSecondaryProgress(0);
        }
        started = false;
        localCacheStartupWaiting = false;
        localCacheStartupBaseMs = 0;
        terminalHandled = false;
        completedCurrent = false;
        lastBufferingBucket = -1;
        currentVoutCount = 0;
        directFallbackInProgress = false;
        lastPosSaveAt = 0;
        lastSavedPosition = -1;
        if (!reconnectStart) {
            reconnecting = false;
            reconnectAttempts = 0;
        }
        setPlaybackPhase(mode == SourceMode.DIRECT_REMOTE
                ? (reconnectStart ? PlaybackPhase.SWITCHING_TO_DIRECT : PlaybackPhase.PREPARING)
                : PlaybackPhase.STARTING_LOCAL);
        if (!local) updateEpisodeIndex();
        setTitle(nm);
        titleBar.setText(nm);
        if (!reconnectStart && mode != SourceMode.LOCAL_CACHE) showBuffering("Подготовка…");

        releaseCurrentPlayer();
        MediaPlayer mp = createMediaPlayer(generation);
        Media media = buildMedia(p, mode, mediaOverrideUri);
        player = mp;
        mp.setMedia(media);
        smoothMediaUri = media.getUri();
        media.release();
        beginVideoOutput(mp, generation);
        showControls();
    }

    private void handlePlayerEvent(int generation, int type, float pct, int vout) {
        if (!isCurrentGeneration(generation) || player == null) return;
        if (type == MediaPlayer.Event.Buffering) {
            int bucket = ((int) pct) / 10;
            if (bucket != lastBufferingBucket) {
                lastBufferingBucket = bucket;
                PlayerDiagnostics.log(this, "event", "gen=" + generation + " Buffering " + ((int) pct) + "% source=" + sourceMode);
            }
        } else {
            PlayerDiagnostics.log(this, "event", "gen=" + generation + " type=" + type + " source=" + sourceMode + " t=" + currentMs + "/" + duration);
        }
        switch (type) {
            case MediaPlayer.Event.Vout:
                currentVoutCount = Math.max(0, vout);
                if (vout > 0 && smoothRenderer != null && smoothRenderer.hasPresentedFrame())
                    ui.removeCallbacks(smoothStartupTimeout);
                updateSmoothMetadata();
                if (!isCurrentGeneration(generation)) return;
                updateTechnicalCard(true);
                break;
            case MediaPlayer.Event.Playing:
                if (smoothRenderer != null) smoothRenderer.setPlaying(true);
                updateSmoothMetadata();
                if (!isCurrentGeneration(generation)) return;
                Store.markWatched(this, historyKey);
                reconnectAttempts = 0;
                reconnecting = false;
                started = true;
                applySmoothAudioDelay();
                if (!isCurrentGeneration(generation)) return;
                terminalHandled = false;
                setPlaybackPhase(sourceMode == SourceMode.DIRECT_REMOTE ? PlaybackPhase.PLAYING_DIRECT : PlaybackPhase.PLAYING_LOCAL);
                if (sourceMode == SourceMode.LOCAL_CACHE) {
                    localCacheStartupWaiting = true;
                    localCacheStartupBaseMs = Math.max(0L, pendingResumeMs);
                    showLocalCacheStartupProgress();
                } else {
                    hideBuffering();
                }
                if (pendingResumeMs > 0) {
                    if (smoothRenderer != null) smoothRenderer.reset();
                    player.setTime(pendingResumeMs);
                    pendingResumeMs = 0;
                }
                player.setVolume(volume);
                applyAspect();
                if (!isCurrentGeneration(generation)) return;
                updatePlayIcon();
                updatePlaybackState();
                if (sourceMode == SourceMode.LOCAL_CACHE) startPrefetchWindow();
                if (pauseAfterSmoothRestart) {
                    pauseAfterSmoothRestart = false;
                    setPlaying(false);
                }
                break;
            case MediaPlayer.Event.Buffering:
                if (!started) {
                    if (!reconnecting) {
                        if (sourceMode == SourceMode.LOCAL_CACHE) {
                            showBuffering("Подготовка локального кэша…");
                        } else {
                            showBuffering("Подготовка… " + (int) pct + "%");
                        }
                    }
                } else if (pct >= 100f && !localCacheStartupWaiting) {
                    hideBuffering();
                }
                break;
            case MediaPlayer.Event.Paused:
                if (smoothRenderer != null) smoothRenderer.setPlaying(false);
                updatePlayIcon();
                updatePlaybackState();
                break;
            case MediaPlayer.Event.EndReached:
                if (smoothRenderer != null) smoothRenderer.setPlaying(false);
                handleEnd(generation);
                break;
            case MediaPlayer.Event.EncounteredError:
                if (smoothRenderer != null) {
                    fallbackSmooth("Декодер не поддерживает вывод GPU");
                    break;
                }
                handleDrop(generation);
                break;
            case MediaPlayer.Event.ESSelected:
                updateSmoothMetadata();
                break;
        }
    }

    private void setupSession() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        try {
        session = new MediaSessionCompat(this, "TabletPlayer");
        session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                setPlaying(true);
            }

            @Override
            public void onPause() {
                setPlaying(false);
            }

            @Override
            public void onSkipToNext() {
                // Bluetooth double/triple clicks are intentionally ignored.
                updatePlaybackState();
            }

            @Override
            public void onSkipToPrevious() {
                // Bluetooth double/triple clicks are intentionally ignored.
                updatePlaybackState();
            }

            @Override
            public void onStop() {
                // Hardware Stop means pause for this player, not closing Activity.
                setPlaying(false);
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                return handleMediaButton(mediaButtonEvent);
            }
        });
        session.setActive(true);
        } catch (Throwable e) {
            session = null;
        }
    }

    private void updatePlaybackState() {
        if (session == null) return;
        long pos = player != null ? player.getTime() : 0;
        int state = (player != null && player.isPlaying())
                ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        PlaybackStateCompat s = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(state, pos, 1f)
                .build();
        session.setPlaybackState(s);
    }

    private boolean handleMediaButton(Intent mediaButtonEvent) {
        if (mediaButtonEvent == null) return false;
        KeyEvent ev = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        return handleMediaKeyEvent(ev, "session");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleMediaKeyEvent(event, "dispatch")) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean handleMediaKeyEvent(KeyEvent ev, String source) {
        if (ev == null) return false;
        int code = ev.getKeyCode();
        if (!isMediaKey(code)) return false;

        // Many Bluetooth headsets reliably send ACTION_DOWN, while ACTION_UP may be delayed
        // or not routed through MediaSession on old Android builds. Act once on DOWN and
        // consume UP so a single press cannot toggle twice.
        if (ev.getAction() == KeyEvent.ACTION_UP) return true;
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (ev.getRepeatCount() > 0) return true;

        long now = System.currentTimeMillis();
        if (code == lastMediaButtonKey && now - lastMediaButtonAt < 650L) {
            PlayerDiagnostics.log(this, "media-button", "ignored double source=" + source + " key=" + code);
            return true;
        }
        lastMediaButtonAt = now;
        lastMediaButtonKey = code;

        PlayerDiagnostics.log(this, "media-button", "source=" + source + " key=" + code
                + " playing=" + (player != null && player.isPlaying()));
        switch (code) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                setPlaying(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                setPlaying(false);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                // Do not map double/triple headset clicks to gestures or episode switching.
                updatePlaybackState();
                return true;
            default:
                return true;
        }
    }

    private boolean isMediaKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return true;
            default:
                return false;
        }
    }

    private String streamUrl(String p) {
        return base + "/download?path=" + Util.enc(p)
                + "&version=" + Util.enc(mediaVersion)
                + App.authQuery(this, "/download", p, "");
    }

    private void askResume() {
        preparePlayback(path, name, 0, true);
    }

    private Media buildMedia(String p, SourceMode mode, String mediaOverrideUri) {
        Uri uri;
        if (mediaOverrideUri != null && mediaOverrideUri.length() > 0) {
            uri = Uri.parse(mediaOverrideUri);
        } else if (mode == SourceMode.LOCAL_FILE) {
            uri = Uri.fromFile(new File(p));
        } else if (mode == SourceMode.LOCAL_CACHE && cacheProxyUrl != null) {
            uri = Uri.parse(cacheProxyUrl);
        } else {
            uri = Uri.parse(streamUrl(p));
        }
        Media media = new Media(libVLC, uri);
        applyDecoderMode(media);
        if (mode == SourceMode.DIRECT_REMOTE) {
            media.addOption(":network-caching=" + networkCachingMs);
        } else if (mode == SourceMode.LOCAL_CACHE) {
            media.addOption(":file-caching=" + localCachingMs);
        } else {
            media.addOption(":file-caching=" + fileCachingMs);
        }
        return media;
    }

    private void playPath(String p, String nm, long resume) {
        preparePlayback(p, nm, resume, false);
    }

    /** Invalidate old callbacks before closing its player/cache or selecting the next episode. */
    private void preparePlayback(final String p, final String nm, final long resume, final boolean askSaved) {
        if (destroyed) return;
        final int generation = ++playbackGeneration;
        ui.removeCallbacks(reconnectAgain);
        stopPrefetchWindow();
        releaseCurrentPlayer();
        smoothDisabledForSource = false;
        smoothStatus = "";
        stopPlaybackCache(false);
        path = p; name = nm;
        currentMs = 0; duration = 0; pendingResumeMs = resume;
        historyKey = ""; mediaVersion = "";
        started = false; completedCurrent = false; terminalHandled = false;
        reconnecting = false; directFallbackInProgress = false;
        localCacheStartupWaiting = false;
        sourceMode = local ? SourceMode.LOCAL_FILE : SourceMode.DIRECT_REMOTE;
        preparingIdentity = true; askSavedPosition = askSaved; requestedResumeMs = resume;
        setPlaybackPhase(PlaybackPhase.PREPARING);
        titleBar.setText(nm);
        if (!local) updateEpisodeIndex();
        showBuffering("Проверка серии…");
        final String requestBase = base, expectedServer = serverId;
        new Thread(() -> {
            try {
                final FileIdentity.Info info = local ? null : FileIdentity.fetch(this, requestBase, p, expectedServer);
                final String key = local ? FileIdentity.localKey(new File(p)) : info.key;
                ui.post(() -> {
                    if (!isCurrentGeneration(generation)) return;
                    historyKey = key;
                    if (info != null) { mediaVersion = info.version; serverId = info.serverId; }
                    preparingIdentity = false;
                    long saved = askSaved ? Store.getPos(this, key) : 0;
                    if (saved > 5000) {
                        hideBuffering();
                        new AlertDialog.Builder(this).setTitle(nm)
                                .setMessage("Продолжить с " + Util.fmtTime(saved) + "?")
                                .setPositiveButton("Продолжить", (d, w) -> {
                                    if (isCurrentGeneration(generation)) playPreparedPath(p, nm, saved);
                                })
                                .setNegativeButton("Сначала", (d, w) -> {
                                    if (isCurrentGeneration(generation)) {
                                        Store.clearPos(this, key); playPreparedPath(p, nm, 0);
                                    }
                                }).setCancelable(false).show();
                    } else { playPreparedPath(p, nm, resume); }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (isCurrentGeneration(generation)) {
                        showBuffering("Не удалось проверить серию: " + e.getMessage());
                        retryBtn.setVisibility(View.VISIBLE);
                    }
                });
            }
        }, "PlaybackIdentity").start();
    }

    private void playPreparedPath(String p, String nm, long resume) {
        if (local) {
            startSource(p, nm, resume, SourceMode.LOCAL_FILE, false);
            return;
        }
        PlaybackCacheManager.Entry ready = PlaybackCacheManager.get().entryFor(this, base, p, nm, mediaVersion);
        if (ready.finalFile.exists() && ready.finalFile.length() > 0) {
            startSource(p, nm, resume, SourceMode.LOCAL_FILE, false, Uri.fromFile(ready.finalFile).toString());
            startPrefetchWindow();
            return;
        }
        if (contentLoadMode == Store.CONTENT_LOAD_DIRECT) {
            startDirectPlayback(p, nm, resume, false);
        } else if (contentLoadMode == Store.CONTENT_LOAD_LOCAL_CACHE) {
            startCachePlayback(p, nm, resume);
        } else if (shouldUseDirect(resume, p)) {
            startDirectPlayback(p, nm, resume, false);
        } else {
            startCachePlayback(p, nm, resume);
        }
    }

    private boolean shouldUseDirect(long resume, String remotePath) {
        if (remotePath == null) return true;
        if (directOnlyThisSession.contains(remotePath)) return true;
        return resume >= 10L * 60L * 1000L;
    }

    private void startDirectPlayback(String p, String nm, long resume, boolean fromFallback) {
        if (!fromFallback) {
            stopPrefetchWindow();
            stopPlaybackCache(false);
        }
        startSource(p, nm, resume, SourceMode.DIRECT_REMOTE, fromFallback);
    }

    private void startCachePlayback(final String p, final String nm, final long resume) {
        stopPrefetchWindow();
        stopPlaybackCache(false);
        activeCacheEntry = PlaybackCacheManager.get().entryFor(this, base, p, nm, mediaVersion);
        cacheTask = new PlaybackCacheTask(this, activeCacheEntry, false, new PlaybackCacheTask.Listener() {
            @Override
            public void onCacheProgress(PlaybackCacheTask task) {
                ui.post(() -> showPrepareProgress(task));
            }

            @Override
            public void onCacheReady(PlaybackCacheTask task, boolean early) {
                ui.post(() -> startFromLocalCache(task, resume));
            }

            @Override
            public void onCacheComplete(PlaybackCacheTask task) {
                ui.post(() -> {
                    if (task == cacheTask) {
                        updateCacheProgressUi();
                        startPrefetchWindow();
                    }
                });
            }

            @Override
            public void onCacheFallback(PlaybackCacheTask task, String reason) {
                ui.post(() -> { if (!destroyed && task == cacheTask) fallbackToDirect(reason); });
            }

            @Override
            public void onCacheError(PlaybackCacheTask task, Exception error) {
                ui.post(() -> { if (!destroyed && task == cacheTask) fallbackToDirect("ошибка загрузки кэша"); });
            }
        });
        showBuffering("Подготовка серии…");
        setPlaybackPhase(PlaybackPhase.PREPARING);
        cacheTask.start();
    }

    private void showPrepareProgress(PlaybackCacheTask task) {
        if (destroyed || task == null || task != cacheTask || playbackPhase != PlaybackPhase.PREPARING) return;
        showBuffering("Подготовка кэша: " + cacheProgressLine(task, true));
        updateTechnicalCard(false);
    }

    private String cacheProgressLine(PlaybackCacheTask task, boolean usePrepareTarget) {
        if (task == null) return "0 Б / 0 Б";
        long got = Math.max(0L, task.cachedBytes());
        long limit = usePrepareTarget ? task.prepareTargetBytes() : task.totalBytes();
        if (limit <= 0) limit = task.totalBytes();
        StringBuilder text = new StringBuilder();
        text.append(Util.humanSize(got));
        if (limit > 0) text.append(" / ").append(Util.humanSize(limit));
        long sp = task.recentSpeedBytesPerSec();
        if (sp > 0) text.append(" · ").append(Util.humanSize(sp)).append("/с");
        return text.toString();
    }

    private String cacheSeekWaitMessage() {
        String line = cacheTask != null ? cacheProgressLine(cacheTask, false) : "0 Б / 0 Б";
        StringBuilder sb = new StringBuilder();
        sb.append("Ожидание кэша: ").append(line);
        if (cacheSeekWaitMs > 0) sb.append(" · переход ").append(Util.fmtTime(cacheSeekWaitMs));
        long eta = estimateCacheSeekEtaMs();
        if (eta > 0 && eta < 10L * 60L * 1000L) sb.append(" · ~").append(Math.max(1L, eta / 1000L)).append(" с");
        return sb.toString();
    }

    private long estimateCacheSeekEtaMs() {
        if (cacheTask == null || cacheSeekTargetEndBytes <= 0) return -1L;
        long available = Math.max(0L, cacheTask.downloadedBytes());
        long remaining = Math.max(0L, cacheSeekTargetEndBytes - available);
        if (remaining <= 0) return 0L;
        long speed = cacheTask.recentSpeedBytesPerSec();
        if (speed <= 0) return -1L;
        return remaining * 1000L / Math.max(1L, speed);
    }

    private boolean shouldFallbackSeekWaitToDirect() {
        if (contentLoadMode == Store.CONTENT_LOAD_LOCAL_CACHE) return false;
        if (cacheSeekWaitMs <= 0 || cacheSeekStartedAtMs <= 0 || cacheTask == null) return false;
        long elapsed = System.currentTimeMillis() - cacheSeekStartedAtMs;
        if (elapsed < 5000L) return false;
        long speed = cacheTask.recentSpeedBytesPerSec();
        long eta = estimateCacheSeekEtaMs();
        if (elapsed >= 12000L && speed < 384L * 1024L) return true;
        return eta > 30000L;
    }

    private void startFromLocalCache(PlaybackCacheTask task, long resume) {
        if (destroyed || task == null || task != cacheTask || cacheProxy != null) return;
        try {
            if (cacheProxy != null) cacheProxy.close();
            cacheProxy = new PlaybackProxyServer(task);
            cacheProxyUrl = cacheProxy.start();
            showBuffering("Запуск из локального кэша…");
            startSource(task.entry().path, task.entry().name, resume, SourceMode.LOCAL_CACHE, false, cacheProxyUrl);
        } catch (Exception e) {
            fallbackToDirect("локальный прокси не запустился");
        }
    }

    private void fallbackToDirect(String reason) {
        long pos = cacheSeekWaitMs > 0 ? cacheSeekWaitMs : -1L;
        fallbackToDirect(reason, pos);
    }

    private void fallbackToDirect(String reason, long forcedPositionMs) {
        if (destroyed || directFallbackInProgress) return;
        directFallbackInProgress = true;
        String p = path;
        String nm = name;
        long pos = forcedPositionMs >= 0 ? forcedPositionMs : (currentMs > 0 ? currentMs : pendingResumeMs);
        PlayerDiagnostics.log(this, "fallback", reason + " pos=" + pos + " path=" + path);
        if (p != null) directOnlyThisSession.add(p);
        cacheSeekWaitMs = 0;
        cacheSeekStartedAtMs = 0;
        cacheSeekTargetEndBytes = 0;
        showBuffering("Запуск прямого воспроизведения…");
        stopPrefetchWindow();
        stopPlaybackCache(false);
        startSource(p, nm, pos, SourceMode.DIRECT_REMOTE, true);
    }

    private void stopPlaybackCache(boolean keepEntry) {
        if (cacheProxy != null) {
            try { cacheProxy.close(); } catch (Throwable ignored) {}
            cacheProxy = null;
            cacheProxyUrl = null;
        }
        if (cacheTask != null) {
            try { cacheTask.close(); } catch (Throwable ignored) {}
            cacheTask = null;
        }
        if (activeCacheEntry != null) {
            if (keepEntry) {
                PlaybackCacheManager.get().release(activeCacheEntry);
            } else {
                PlaybackCacheManager.get().deleteEntry(activeCacheEntry);
            }
        }
        activeCacheEntry = null;
        cacheSeekWaitMs = 0;
        cacheSeekStartedAtMs = 0;
        cacheSeekTargetEndBytes = 0;
        if (cacheBadge != null) cacheBadge.setVisibility(View.GONE);
    }

    private void requestSeek(long targetMs) {
        if (player == null) return;
        PlayerDiagnostics.log(this, "seek", "target=" + targetMs + " duration=" + duration + " source=" + sourceMode);
        if (targetMs < 0) targetMs = 0;
        if (duration > 0 && targetMs > duration) targetMs = duration;
        if (smoothRenderer != null) smoothRenderer.reset();
        if (sourceMode == SourceMode.LOCAL_CACHE && cacheTask != null && duration > 0) {
            long extra = 32L * 1024L * 1024L;
            if (!cacheTask.hasBytesForTime(targetMs, duration, extra)) {
                cacheSeekWaitMs = targetMs;
                cacheSeekStartedAtMs = System.currentTimeMillis();
                long startByte = cacheTask.bytesForTime(targetMs, duration);
                cacheSeekTargetEndBytes = Math.min(Math.max(1L, cacheTask.totalBytes()), startByte + extra);
                // Не создаём срочное окно от новой позиции: на слабых устройствах это даёт подвисания.
                // Ждём обычную догрузку, но в режиме Auto через 5–12 секунд считаем ETA.
                setPlaying(false);
                showBuffering(cacheSeekWaitMessage());
                return;
            }
        }
        cacheSeekWaitMs = 0;
        cacheSeekStartedAtMs = 0;
        cacheSeekTargetEndBytes = 0;
        player.setTime(targetMs);
    }

    private void updateCacheProgressUi() {
        if (seek == null) return;
        if (sourceMode != SourceMode.LOCAL_CACHE || cacheTask == null) {
            seek.setSecondaryProgress(0);
            if (cacheBadge != null) cacheBadge.setVisibility(View.GONE);
            return;
        }
        long total = cacheTask.totalBytes();
        long got = cacheTask.downloadedBytes();
        if (total > 0) {
            int progress = (int) Math.max(0, Math.min(1000, got * 1000L / total));
            seek.setSecondaryProgress(progress);
        }
        if (cacheSeekWaitMs > 0) {
            long seekExtra = 32L * 1024L * 1024L;
            boolean ready = cacheTask.hasBytesForTime(cacheSeekWaitMs, duration, seekExtra);
            if (ready) {
                long target = cacheSeekWaitMs;
                cacheSeekWaitMs = 0;
                hideBuffering();
                if (player != null) {
                    player.setTime(target);
                    if (smoothRenderer != null) smoothRenderer.reset();
                    setPlaying(true);
                }
            } else {
                if (shouldFallbackSeekWaitToDirect()) {
                    fallbackToDirect("кэш не успевает к перемотке", cacheSeekWaitMs);
                    return;
                }
                showBuffering(cacheSeekWaitMessage());
            }
        }
        if (cacheBadge != null) {
            if (cacheTask.complete()) {
                cacheBadge.setVisibility(View.GONE);
            } else {
                cacheBadge.setText("↓ " + cacheTask.percent() + "%");
                cacheBadge.setVisibility(View.VISIBLE);
            }
        }
    }

    private void stopPrefetchWindow() {
        PlaybackCacheTask active;
        synchronized (prefetchLock) {
            prefetchGeneration++;
            active = prefetchTask;
            prefetchTask = null;
        }
        if (active != null && !active.complete()) {
            active.close(); PlaybackCacheManager.get().deleteEntry(active.entry());
        }
        Thread t = prefetchThread;
        prefetchThread = null;
        if (t != null) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }
    }

    private void startPrefetchWindow() {
        if (local || episodePaths.isEmpty() || episodeIndex < 0 || prefetchThread != null && prefetchThread.isAlive()) return;
        if (cacheTask != null && !cacheTask.complete()) return;
        final int generation = ++prefetchGeneration;
        final int start = episodeIndex + 1;
        final int end = Math.min(episodePaths.size(), start + 3);
        if (start >= end) return;
        final List<String> paths = new ArrayList<>(episodePaths);
        final List<String> names = new ArrayList<>(episodeNames);
        final String targetBase = base, targetServer = serverId;
        prefetchThread = new Thread(() -> {
            for (int i = start; i < end && generation == prefetchGeneration; i++) {
                PlaybackCacheTask task = null;
                try {
                    String pp = paths.get(i), nn = names.get(i);
                    FileIdentity.Info info = FileIdentity.fetch(this, targetBase, pp, targetServer);
                    if (generation != prefetchGeneration) break;
                    synchronized (prefetchLock) {
                        if (generation != prefetchGeneration) break;
                        PlaybackCacheManager.Entry e = PlaybackCacheManager.get().entryFor(this, targetBase, pp, nn, info.version);
                        if (e.finalFile.exists() && e.finalFile.length() > 0) continue;
                        task = new PlaybackCacheTask(this, e, true, null);
                        prefetchTask = task;
                        task.start();
                    }
                    while (generation == prefetchGeneration && !task.complete() && !task.failed() && !task.finished()) {
                        Thread.sleep(500L);
                    }
                } catch (Exception ignored) {
                    if (Thread.currentThread().isInterrupted()) break;
                } finally {
                    if (task != null && !task.complete()) {
                        task.close(); PlaybackCacheManager.get().deleteEntry(task.entry());
                    }
                    synchronized (prefetchLock) { if (prefetchTask == task) prefetchTask = null; }
                }
            }
        }, "QueuePrefetch");
        prefetchThread.setDaemon(true);
        prefetchThread.setPriority(Thread.MIN_PRIORITY);
        prefetchThread.start();
    }

    private void playEpisode(int index) {
        if (index < 0 || index >= episodePaths.size()) return;
        savePosition(true);
        stopPrefetchWindow();
        episodeIndex = index;
        playPath(episodePaths.get(index), episodeNames.get(index), 0);
    }

    private void playNext() {
        if (episodePaths.isEmpty()) {
            Toast.makeText(this, "Список серий ещё загружается", Toast.LENGTH_SHORT).show();
            return;
        }
        if (episodeIndex >= 0 && episodeIndex + 1 < episodePaths.size()) playEpisode(episodeIndex + 1);
        else Toast.makeText(this, "Это последняя серия", Toast.LENGTH_SHORT).show();
    }

    private void playPrev() {
        if (episodeIndex > 0) playEpisode(episodeIndex - 1);
        else Toast.makeText(this, "Это первая серия", Toast.LENGTH_SHORT).show();
    }

    private void updateEpisodeIndex() {
        episodeIndex = episodePaths.indexOf(path);
    }

    private void finishFade() {
        finish();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private void handleEnd(int generation) {
        if (!beginTerminalHandling(generation)) return;
        PlayerDiagnostics.log(this, "end", "gen=" + generation + " source=" + sourceMode + " t=" + currentMs + "/" + duration);
        if (sourceMode == SourceMode.LOCAL_FILE && local) {
            completedCurrent = true;
            Store.clearPos(this, historyKey);
            setPlaybackPhase(PlaybackPhase.STOPPING);
            finishFade();
            return;
        }
        // Настоящий конец — только если досмотрели почти до конца.
        // Иначе это обрыв сети — libVLC часто шлёт EndReached вместо ошибки.
        if (sourceMode == SourceMode.LOCAL_FILE || (duration > 0 && currentMs > 0 && currentMs >= duration - 8000)) {
            completedCurrent = true;
            Store.clearPos(this, historyKey);
            setPlaybackPhase(PlaybackPhase.STOPPING);
            showNextDialog();
        } else {
            terminalHandled = false;
            handleDrop(generation);
        }
    }

    private void handleDrop(int generation) {
        if (!beginTerminalHandling(generation)) return;
        PlayerDiagnostics.log(this, "drop", "gen=" + generation + " source=" + sourceMode + " t=" + currentMs + "/" + duration);
        if (sourceMode == SourceMode.LOCAL_FILE) {
            Toast.makeText(this, "Ошибка воспроизведения файла", Toast.LENGTH_LONG).show();
            return;
        }
        if (sourceMode == SourceMode.LOCAL_CACHE) {
            if (cacheSeekWaitMs > 0 && cacheTask != null) {
                terminalHandled = false;
                setPlaying(false);
                showBuffering("Ожидание кэша: " + cacheProgressLine(cacheTask, false) + " · переход " + Util.fmtTime(cacheSeekWaitMs));
                return;
            }
            fallbackToDirect("ошибка локального кэша");
            return;
        }
        reconnectStep(generation);
    }

    /** Статус «поиск сервера»: повтор на текущем IP, затем переобнаружение (IP мог смениться). */
    private void reconnectStep() {
        reconnectStep(playbackGeneration);
    }

    private void reconnectStep(final int generation) {
        if (!isCurrentGeneration(generation)) return;
        if (!reconnecting) {
            reconnecting = true;
            reconnectAttempts = 0;
            resumeMs = currentMs > 0 ? currentMs : pendingResumeMs;
        }
        reconnectAttempts++;
        if (reconnectAttempts > 10) {
            showRetry("Сервер не найден");
            return;
        }
        if (reconnectAttempts <= 3) {
            showBuffering("Соединение потеряно. Переподключение… (" + reconnectAttempts + ")");
            ui.postDelayed(() -> {
                if (isCurrentGeneration(generation)) reloadStream(generation, resumeMs);
            }, 1500);
        } else {
            showBuffering("Поиск сервера в сети…");
            rediscover(generation, resumeMs);
        }
    }

    private void reloadStream(int generation, long ms) {
        if (!isCurrentGeneration(generation)) return;
        startSource(path, name, ms, SourceMode.DIRECT_REMOTE, true);
    }

    private void rediscover(final int generation, final long ms) {
        final int port = portFromBase(base);
        new Thread(() -> {
            final List<Discovery.Server> servers = Discovery.find(this, port, 2500);
            final String nb = pickServer(servers);
            ui.post(() -> {
                if (!isCurrentGeneration(generation)) return;
                if (nb != null) {
                    base = nb;
                    reloadStream(generation, ms);
                } else {
                    ui.postDelayed(() -> reconnectStep(generation), 1500);
                }
            });
        }, "PlayerRediscover").start();
    }

    private String pickServer(List<Discovery.Server> servers) {
        // Для уже разрешённого сервера постоянный server_id важнее IP и имени.
        if (serverId != null && !serverId.isEmpty()) {
            for (Discovery.Server s : servers) {
                if (s.trusted && serverId.equals(s.id)) {
                    String cand = "http://" + s.host + ":" + s.port;
                    if (verifyPath(cand, path)) {
                        UiStore.saveServer(this, serverId, s.name, s.host, s.port);
                        return cand;
                    }
                }
            }
            return null;
        }
        // Legacy pairing: имя + проверка того же файла.
        if (serverName != null && !serverName.isEmpty()) {
            for (Discovery.Server s : servers) {
                if (serverName.equals(s.name)) {
                    String cand = "http://" + s.host + ":" + s.port;
                    if (verifyPath(cand, path)) return cand;
                }
            }
        }
        for (Discovery.Server s : servers) {
            String cand = "http://" + s.host + ":" + s.port;
            if (verifyPath(cand, path)) return cand;
        }
        return null;
    }

    private boolean verifyPath(String cand, String p) {
        HttpURLConnection c = null;
        TransferCoordinator.Lease lease = null;
        try {
            lease = TransferCoordinator.get().tryAcquire(TransferCoordinator.Priority.PLAYBACK_METADATA, "verify", 2500);
            if (lease == null) return false;
            c = (HttpURLConnection) new URL(cand + "/download?path=" + Util.enc(p)).openConnection();
            App.auth(c, this);
            c.setRequestProperty("Range", "bytes=0-0");
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            int code = c.getResponseCode();
            return code == 200 || code == 206;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
            if (lease != null) lease.close();
        }
    }

    private int portFromBase(String b) {
        try {
            int i = b.lastIndexOf(':');
            return Integer.parseInt(b.substring(i + 1));
        } catch (Exception e) {
            return 10930;
        }
    }

    private void showRetry(String msg) {
        reconnecting = true;
        showBuffering(msg + ". Нажмите «Повторить».");
        retryBtn.setVisibility(View.VISIBLE);
    }

    private void showNextDialog() {
        if (episodePaths.isEmpty() || episodeIndex < 0 || episodeIndex + 1 >= episodePaths.size()) {
            finishFade();
            return;
        }
        final List<Integer> idxs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (int i = episodeIndex + 1; i < episodePaths.size(); i++) {
            idxs.add(i);
            labels.add(episodeNames.get(i));
        }
        final int[] chosen = {0};
        final int[] left = {8};
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Следующая серия через " + left[0] + "…")
                .setSingleChoiceItems(labels.toArray(new String[0]), 0, (d, w) -> chosen[0] = w)
                .setPositiveButton("Смотреть", (d, w) -> playEpisode(idxs.get(chosen[0])))
                .setNegativeButton("Выйти", (d, w) -> finishFade())
                .setCancelable(true)
                .create();
        dlg.show();
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!dlg.isShowing()) return;
            left[0]--;
            if (left[0] <= 0) {
                dlg.dismiss();
                playEpisode(idxs.get(chosen[0]));
            } else {
                dlg.setTitle("Следующая серия через " + left[0] + "…");
                ui.postDelayed(tick[0], 1000);
            }
        };
        ui.postDelayed(tick[0], 1000);
    }

    private void fetchEpisodes() {
        final String f = folder;
        new Thread(() -> {
            try {
                String body = httpGet(base + "/list?path=" + Util.enc(f));
                JSONObject o = new JSONObject(body);
                JSONArray arr = o.getJSONArray("entries");
                final List<String[]> vids = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.getJSONObject(i);
                    if (e.getBoolean("is_dir")) continue;
                    String nm = e.getString("name");
                    if (!Util.isVideo(nm)) continue;
                    String fp = f.isEmpty() ? nm : f + "/" + nm;
                    vids.add(new String[]{nm, fp});
                }
                Collections.sort(vids, new Comparator<String[]>() {
                    @Override
                    public int compare(String[] a, String[] b) {
                        return Util.naturalCompare(a[0], b[0]);
                    }
                });
                ui.post(() -> {
                    episodeNames.clear();
                    episodePaths.clear();
                    for (String[] v : vids) {
                        episodeNames.add(v[0]);
                        episodePaths.add(v[1]);
                    }
                    updateEpisodeIndex();
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private String httpGet(String u) throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        TransferCoordinator.Lease lease = null;
        try {
            lease = TransferCoordinator.get().acquire(TransferCoordinator.Priority.PLAYBACK_METADATA, "httpGet");
            c = (HttpURLConnection) new URL(u).openConnection();
            App.auth(c, this);
            c.setConnectTimeout(8000);
            c.setReadTimeout(40000);
            if (c.getResponseCode() != 200) {
                throw new RuntimeException("HTTP " + c.getResponseCode());
            }
            in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
            return bo.toString("UTF-8");
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
            if (lease != null) lease.close();
        }
    }

    private void setPlaying(boolean play) {
        if (destroyed || player == null) return;
        if (smoothRenderer != null) smoothRenderer.setPlaying(play);
        if (play && !player.isPlaying()) player.play();
        else if (!play && player.isPlaying()) player.pause();
        updatePlayIcon();
        updatePlaybackState();
    }

    private void togglePlay() {
        if (player != null) setPlaying(!player.isPlaying());
    }

    private void updatePlayIcon() {
        playPause.setImageResource(player != null && player.isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void seekRelative(int delta) {
        if (player == null) return;
        long t = player.getTime() + delta;
        if (t < 0) t = 0;
        if (duration > 0 && t > duration) t = duration;
        requestSeek(t);
        flashInfo((delta > 0 ? "+" : "") + (delta / 1000) + " сек");
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.length;
        if (player != null) player.setRate(speeds[speedIdx]);
        smoothConfiguration = "";
        updateSmoothMetadata();
        speed.setText(speeds[speedIdx] + "x");
    }

    private void cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspectNames.length;
        applyAspect();
        Store.setAspect(this, aspectIdx);
        flashInfo(aspectNames[aspectIdx]);
    }

    private void applyAspect() {
        if (player == null) return;
        attachVideoViewsIfNeeded();
        if (smoothRenderer != null) {
            player.setAspectRatio(null);
            player.setScale(0);
            updateSmoothMetadata();
            return;
        }
        switch (aspectIdx) {
            case 0:
                player.setAspectRatio(null);
                player.setScale(0);
                break;
            case 1:
                player.setAspectRatio("16:9");
                player.setScale(0);
                break;
            case 2:
                player.setAspectRatio("4:3");
                player.setScale(0);
                break;
            case 3:
                int w = videoLayout.getWidth(), h = videoLayout.getHeight();
                if (w > 0 && h > 0) player.setAspectRatio(w + ":" + h);
                player.setScale(0);
                break;
            case 4:
                player.setAspectRatio(null);
                player.setScale(1);
                break;
        }
    }

    private void applyDecoderMode(Media media) {
        if (media == null) return;
        switch (decoderMode) {
            case DECODER_HARDWARE:
                media.setHWDecoderEnabled(true, true);
                break;
            case DECODER_SOFTWARE:
                media.setHWDecoderEnabled(false, false);
                break;
            case DECODER_AUTO:
            default:
                media.setHWDecoderEnabled(true, false);
                break;
        }
        PlayerDiagnostics.log(this, "decoder", decoderNames[Math.max(0, Math.min(decoderMode, decoderNames.length - 1))]);
    }

    private void showDecoderDialog() {
        int checked = Math.max(0, Math.min(decoderMode, decoderNames.length - 1));
        new AlertDialog.Builder(this)
                .setTitle("Режим декодера")
                .setSingleChoiceItems(decoderNames, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == decoderMode) return;
                    decoderMode = which;
                    Store.setDecoderMode(this, decoderMode);
                    flashInfo("Декодер: " + decoderNames[decoderMode]);
                    restartCurrentSourceForDecoder();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void restartCurrentSourceForDecoder() {
        if (destroyed || player == null || path == null) return;
        long pos = currentMs > 0 ? currentMs : player.getTime();
        SourceMode mode = sourceMode;
        String override = currentMediaOverrideUri;
        PlayerDiagnostics.log(this, "decoder", "restart mode=" + mode + " pos=" + pos);
        startSource(path, name, pos, mode, false, override);
    }

    private void attachVideoViewsIfNeeded() {
        if (player == null || videoViewsAttached || videoLayout == null || destroyed) return;
        try {
            if (smoothRenderer != null) {
                if (smoothInput == null) return;
                player.getVLCVout().setVideoSurface(smoothInput, null);
                player.getVLCVout().attachViews();
            } else player.attachViews(videoLayout, null, false, false);
            videoViewsAttached = true;
            PlayerDiagnostics.log(this, "surface", "attach");
        } catch (Throwable e) {
            PlayerDiagnostics.log(this, "surface-attach-error", e);
        }
    }

    private void detachVideoViewsIfNeeded() {
        if (player == null || !videoViewsAttached) return;
        try {
            player.detachViews();
            player.getVLCVout().detachViews();
            PlayerDiagnostics.log(this, "surface", "detach");
        } catch (Throwable e) {
            PlayerDiagnostics.log(this, "surface-detach-error", e);
        } finally {
            videoViewsAttached = false;
        }
    }

    private void setupGestures() {
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isTopDeadZone(e)) {
                    if (controlsVisible) {
                        hideControls();
                        return true;
                    }
                    scheduleImmersiveReapply();
                    return false;
                }
                if (controlsVisible) hideControls();
                else showControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isTopDeadZone(e)) return false;
                int w = gestureOverlay.getWidth();
                float x = e.getX();
                if (x < w / 3f) seekRelative(-JUMP_MS);
                else if (x > 2f * w / 3f) seekRelative(JUMP_MS);
                else togglePlay();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (isTopDeadZone(e1)) return false;
                int w = gestureOverlay.getWidth(), h = gestureOverlay.getHeight();
                float totalX = e2.getX() - e1.getX();
                float totalY = e2.getY() - e1.getY();
                if (!dragging || mode == 0) {
                    if (Math.abs(totalX) > Math.abs(totalY) && Math.abs(totalX) > 40) {
                        mode = 1;
                        dragging = true;
                        dragStartTime = player != null ? player.getTime() : 0;
                    } else if (Math.abs(totalY) > 40 && e1.getX() > w / 2f) {
                        mode = 2;
                        dragging = true;
                        dragStartVol = volume;
                    } else {
                        return false;
                    }
                }
                if (mode == 1) {
                    long target = dragStartTime + (long) (totalX / w * SWIPE_FULL_WIDTH_MS);
                    if (target < 0) target = 0;
                    if (duration > 0 && target > duration) target = duration;
                    seekPreview = target;
                    flashInfoSticky(Util.fmtTime(target) + " / " + Util.fmtTime(duration));
                } else if (mode == 2) {
                    int nv = dragStartVol - (int) (totalY / h * 200);
                    if (nv < 0) nv = 0;
                    if (nv > 200) nv = 200;
                    volume = nv;
                    if (player != null) player.setVolume(nv);
                    Store.setVolume(PlayerActivity.this, nv);
                    flashInfoSticky("🔊 " + nv + "%");
                }
                return true;
            }
        });

        gestureOverlay.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                ignoreGestureSequence = isTopDeadZone(ev);
                if (ignoreGestureSequence) {
                    PlayerDiagnostics.log(this, "gesture", "ignored top zone y=" + ev.getY());
                    scheduleImmersiveReapply();
                    return true;
                }
            }
            if (!ignoreGestureSequence) gd.onTouchEvent(ev);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (ignoreGestureSequence && controlsVisible && action == MotionEvent.ACTION_UP) {
                    hideControls();
                }
                if (dragging && mode == 1 && player != null) requestSeek(seekPreview);
                if (dragging) gestureInfo.setVisibility(View.GONE);
                dragging = false;
                mode = 0;
                ignoreGestureSequence = false;
                scheduleImmersiveReapply();
            }
            return true;
        });
    }

    private boolean isTopDeadZone(MotionEvent e) {
        if (e == null || gestureOverlay == null) return false;
        int h = gestureOverlay.getHeight();
        return h > 0 && e.getY() < h * TOP_GESTURE_DEAD_ZONE;
    }

    private void flashInfo(String text) {
        gestureInfo.setText(text);
        gestureInfo.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 900);
    }

    private void flashInfoSticky(String text) {
        gestureInfo.setText(text);
        gestureInfo.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideInfo);
    }

    private final Runnable hideInfo = () -> gestureInfo.setVisibility(View.GONE);

    private void showBuffering(String text) {
        if (text == null) text = "";
        CharSequence old = bufferingText.getText();
        if (old == null || !text.contentEquals(old)) bufferingText.setText(text);
        retryBtn.setVisibility(View.GONE);
        if (buffering.getVisibility() != View.VISIBLE) buffering.setVisibility(View.VISIBLE);
    }

    private void hideBuffering() {
        buffering.setVisibility(View.GONE);
        retryBtn.setVisibility(View.GONE);
    }

    private void updateTechnicalCard(boolean force) {
        if (technicalCard == null || !controlsVisible) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastTechnicalCardUpdateMs < 1000L) return;
        lastTechnicalCardUpdateMs = now;

        StringBuilder text = new StringBuilder();
        switch (sourceMode) {
            case LOCAL_FILE:
                text.append("Локальный файл");
                break;
            case LOCAL_CACHE:
                text.append(cacheTask != null && cacheTask.complete() ? "Локальный файл" : "Локальный кэш");
                break;
            case DIRECT_REMOTE:
            default:
                text.append("Прямой поток");
                break;
        }

        if (cacheTask != null && cacheTask.totalBytes() > 0) {
            long got = Math.max(0L, cacheTask.cachedBytes());
            long available = Math.max(0L, cacheTask.downloadedBytes());
            long total = Math.max(1L, cacheTask.totalBytes());
            long percent = Math.min(100L, got * 100L / total);
            text.append("\n").append(Util.humanSize(got)).append(" / ").append(Util.humanSize(total))
                    .append(" · ").append(percent).append('%');
            if (available < got && !cacheTask.complete()) {
                text.append(" · доступно ").append(Util.humanSize(available));
            }
            long sp = cacheTask.recentSpeedBytesPerSec();
            if (sp > 0 && !cacheTask.complete()) {
                text.append(" · ").append(Util.humanSize(sp)).append("/с");
            }
            if (duration > 0) {
                long downloadedTime = duration * Math.min(got, total) / total;
                long ahead = Math.max(0L, downloadedTime - currentMs);
                if (ahead > 0 && !cacheTask.complete()) {
                    float rate = Math.max(0.25f, speeds[speedIdx]);
                    long realAhead = (long) (ahead / rate);
                    text.append("\nзапас ").append(Util.fmtTime(realAhead));
                } else if (cacheTask.complete()) {
                    text.append("\nфайл загружен полностью");
                }
            }
        } else if (sourceMode == SourceMode.DIRECT_REMOTE) {
            text.append("\nбуфер libVLC · ").append(networkCachingMs / 1000).append(" с");
        }

        text.append("\n").append(decoderNames[Math.max(0, Math.min(decoderMode, decoderNames.length - 1))])
                .append(" · ").append(speeds[speedIdx]).append('x')
                .append(" · Vout ").append(currentVoutCount);
        if (smoothSetting) text.append("\n").append(smoothStatus);
        text.append("\nсоединения приложения: ").append(TransferCoordinator.get().activeRemoteTransfers())
                .append('/').append(TransferCoordinator.get().maxRemoteTransfers());
        technicalCard.setText(text.toString());
        technicalCard.setVisibility(View.VISIBLE);
    }

    private void showControls() {
        setControlsVisible(true);
    }

    private void hideControls() {
        setControlsVisible(false);
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        if (playerHeader != null) playerHeader.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (controls != null) controls.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (titleBar != null) titleBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (technicalCard != null) {
            if (visible) updateTechnicalCard(true);
            else technicalCard.setVisibility(View.GONE);
        }

        ui.removeCallbacks(hideRunnable);
        if (visible) {
            ui.postDelayed(hideRunnable, AUTO_HIDE_MS);
        }
        scheduleImmersiveReapply();
    }

    private void toggleImmersive() {
        immersive = !immersive;
        applyImmersive();
        scheduleImmersiveReapply();
        fullscreen.setImageResource(immersive ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
    }

    private void installFullscreenGuards() {
        final View decor = getWindow().getDecorView();
        decor.setOnSystemUiVisibilityChangeListener(visibility -> {
            if (!immersive || destroyed) return;
            // Android can reveal status/nav bars after taps, swipes, dialogs, or focus changes.
            // Return to sticky immersive shortly after the system finishes its own animation.
            scheduleImmersiveReapply();
        });
    }

    private void scheduleImmersiveReapply() {
        if (!immersive || destroyed) return;
        ui.removeCallbacks(immersiveReapplyImmediate);
        ui.removeCallbacks(immersiveReapplyShort);
        ui.removeCallbacks(immersiveReapplyLong);
        ui.post(immersiveReapplyImmediate);
        ui.postDelayed(immersiveReapplyShort, 80L);
        ui.postDelayed(immersiveReapplyLong, 350L);
    }

    private final Runnable immersiveReapplyImmediate = this::applyImmersive;
    private final Runnable immersiveReapplyShort = this::applyImmersive;
    private final Runnable immersiveReapplyLong = this::applyImmersive;

    private void applyImmersive() {
        if (destroyed) return;
        View d = getWindow().getDecorView();
        if (immersive) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            d.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void showLocalCacheStartupProgress() {
        if (!localCacheStartupWaiting || sourceMode != SourceMode.LOCAL_CACHE) return;
        showBuffering("Запуск из кэша: " + cacheProgressLine(cacheTask, false));
    }

    private void updateLocalCacheStartupUi() {
        if (!localCacheStartupWaiting) return;
        if (sourceMode != SourceMode.LOCAL_CACHE || player == null) {
            localCacheStartupWaiting = false;
            return;
        }
        long movedFromStart = Math.max(0L, currentMs - localCacheStartupBaseMs);
        if (movedFromStart >= 1000L) {
            localCacheStartupWaiting = false;
            hideBuffering();
            return;
        }
        showLocalCacheStartupProgress();
    }

    private void updateTime() {
        if (player == null) return;
        int generation = playbackGeneration;
        duration = player.getLength();
        currentMs = player.getTime();
        updateSmoothMetadata();
        if (!isCurrentGeneration(generation) || player == null) return;
        time.setText(Util.fmtTime(currentMs) + " / " + Util.fmtTime(duration));
        if (duration > 0 && !dragging) seek.setProgress((int) (currentMs * 1000 / duration));
        updateCacheProgressUi();
        updateLocalCacheStartupUi();
        if (player.isPlaying()) savePosition(false);
        updateTechnicalCard(false);
        updatePlaybackState();
    }

    private void savePosition(boolean force) {
        if (completedCurrent || path == null || player == null) return;
        long pos = currentMs > 0 ? currentMs : player.getTime();
        if (pos <= 0) return;
        if (duration > 0 && pos >= duration - 5000) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastPosSaveAt < POS_SAVE_INTERVAL_MS
                && Math.abs(pos - lastSavedPosition) < POS_SAVE_INTERVAL_MS) {
            return;
        }
        Store.setPos(this, historyKey, pos);
        lastPosSaveAt = now;
        lastSavedPosition = pos;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) scheduleImmersiveReapply();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityActive = true;
        if (!pendingSmoothFallback.isEmpty()) fallbackSmooth(pendingSmoothFallback);
        startBatteryMonitoring();
        if (session != null) {
            try { session.setActive(true); updatePlaybackState(); } catch (Throwable ignored) {}
        }
        ui.post(ticker);
        scheduleImmersiveReapply();
        attachVideoViewsIfNeeded();
        if (player != null && videoViewsAttached && !started) {
            player.play(); player.setRate(speeds[speedIdx]);
        }
        ui.postDelayed(() -> { attachVideoViewsIfNeeded(); applyAspect(); }, 180L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean setting = Store.getSmoothVideo(this);
        if (setting != smoothSetting) {
            smoothSetting = setting;
            smoothDisabledForSource = false;
            if (player != null) restartCurrentSourceForDecoder();
        }
        updateLongJumpButton();
        if (session != null) {
            try { session.setActive(true); updatePlaybackState(); } catch (Throwable ignored) {}
        }
        scheduleImmersiveReapply();
        attachVideoViewsIfNeeded();
        ui.postDelayed(() -> { attachVideoViewsIfNeeded(); applyAspect(); }, 220L);
        PlayerDiagnostics.log(this, "lifecycle", "resume wasPlaying=" + wasPlayingBeforeStop);
    }

    private void startBatteryMonitoring() {
        if (batteryMonitor == null) return;
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!App.prefs(this).getBoolean("bluetooth_permission_asked", false)) {
                App.prefs(this).edit().putBoolean("bluetooth_permission_asked", true).apply();
                requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, 61);
            }
            return;
        }
        batteryMonitor.start();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 61 && activityActive && results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startBatteryMonitoring();
    }

    private void updateLongJumpButton() {
        if (fwd90 != null) {
            String interval = Util.fmtTime(Store.getLongJumpSeconds(this) * 1000L);
            fwd90.setText("+" + interval);
            fwd90.setContentDescription("Вперёд " + interval);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        scheduleImmersiveReapply();
        ui.postDelayed(() -> { attachVideoViewsIfNeeded(); applyAspect(); scheduleImmersiveReapply(); }, 180L);
        PlayerDiagnostics.log(this, "config", "orientation=" + newConfig.orientation + " aspect=" + aspectIdx);
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityActive = false;
        if (batteryMonitor != null) batteryMonitor.stop();
        ui.removeCallbacks(smoothStartupTimeout);
        ui.removeCallbacks(ticker);
        savePosition(true);
        wasPlayingBeforeStop = player != null && player.isPlaying();
        if (wasPlayingBeforeStop) setPlaying(false);
        detachVideoViewsIfNeeded();
        PlayerDiagnostics.log(this, "lifecycle", "stop wasPlaying=" + wasPlayingBeforeStop + " pos=" + currentMs);
    }

    @Override
    public void onBackPressed() {
        PlayerDiagnostics.log(this, "lifecycle", "back pos=" + currentMs + " source=" + sourceMode);
        playbackGeneration++;
        setPlaybackPhase(PlaybackPhase.STOPPING);
        savePosition(true);
        releaseCurrentPlayer();
        stopPrefetchWindow();
        stopPlaybackCache(false);
        super.onBackPressed();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        PlayerDiagnostics.log(this, "lifecycle", "destroy pos=" + currentMs + " source=" + sourceMode);
        destroyed = true;
        if (batteryMonitor != null) batteryMonitor.stop();
        playbackGeneration++;
        setPlaybackPhase(PlaybackPhase.DESTROYED);
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (session != null) {
            try { session.release(); } catch (Throwable ignored) {}
            session = null;
        }
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        stopPrefetchWindow();
        stopPlaybackCache(false);
        releaseCurrentPlayer();
        PlaybackCacheManager.get().clearAll(this);
        if (libVLC != null) {
            try { libVLC.release(); } catch (Throwable ignored) {}
            libVLC = null;
        }
    }
}
