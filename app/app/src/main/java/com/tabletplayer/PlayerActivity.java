package com.tabletplayer;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Locale;

public class PlayerActivity extends Activity {

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;

    private View controls;
    private TextView timeLabel;
    private SeekBar seekBar;
    private TextView gestureInfo;
    private Button playPause;
    private Button speedBtn;
    private Button aspectBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean seeking = false;
    private boolean controlsVisible = true;

    private static final long SWIPE_FULL_WIDTH_MS = 120000L;
    private static final long JUMP_MS = 10000L;
    private static final long JUMP90_MS = 90000L;
    private static final long AUTO_HIDE_MS = 3500L;

    private final float[] speeds = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f};
    private int speedIdx = 0;

    private final String[] aspectNames = {"По размеру", "16:9", "4:3", "Растянуть", "Оригинал"};
    private int aspectIdx = 0;

    private final Runnable hideControls = new Runnable() {
        @Override public void run() { setControls(false); }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateProgress();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        controls = findViewById(R.id.controls);
        timeLabel = (TextView) findViewById(R.id.time);
        seekBar = (SeekBar) findViewById(R.id.seek);
        gestureInfo = (TextView) findViewById(R.id.gesture_info);
        playPause = (Button) findViewById(R.id.play_pause);
        speedBtn = (Button) findViewById(R.id.speed);
        aspectBtn = (Button) findViewById(R.id.aspect);
        Button rew = (Button) findViewById(R.id.rew);
        Button fwd = (Button) findViewById(R.id.fwd);
        Button fwd90 = (Button) findViewById(R.id.fwd90);

        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        setTitle(title == null ? "" : title);

        ArrayList<String> options = new ArrayList<String>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--network-caching=1500");
        libVLC = new LibVLC(this, options);
        player = new MediaPlayer(libVLC);

        videoLayout = new VLCVideoLayout(this);
        FrameLayout container = (FrameLayout) findViewById(R.id.video_layout);
        container.addView(videoLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        player.attachViews(videoLayout, null, false, false);

        Media media = new Media(libVLC, Uri.parse(url));
        player.setMedia(media);
        media.release();

        setupControls(rew, fwd, fwd90);
        setupGestures();

        speedBtn.setText(formatRate(speeds[speedIdx]));
        player.play();
        setControls(true);
        ui.post(ticker);
    }

    private void setupControls(Button rew, Button fwd, Button fwd90) {
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        rew.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jump(-JUMP_MS); }
        });
        fwd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jump(JUMP_MS); }
        });
        fwd90.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jump(JUMP90_MS); }
        });
        speedBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                speedIdx = (speedIdx + 1) % speeds.length;
                player.setRate(speeds[speedIdx]);
                speedBtn.setText(formatRate(speeds[speedIdx]));
                showControlsTemporarily();
            }
        });
        aspectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                aspectIdx = (aspectIdx + 1) % aspectNames.length;
                applyAspect(aspectNames[aspectIdx]);
                showControlsTemporarily();
            }
        });
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    long len = player.getLength();
                    if (len > 0) player.setTime(len * progress / 1000);
                    showControlsTemporarily();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { seeking = false; }
        });
    }

    private void applyAspect(String name) {
        aspectBtn.setText(shortAspect(name));
        if ("По размеру".equals(name)) {
            player.setAspectRatio(null);
            player.setScale(0f);
        } else if ("16:9".equals(name)) {
            player.setScale(0f);
            player.setAspectRatio("16:9");
        } else if ("4:3".equals(name)) {
            player.setScale(0f);
            player.setAspectRatio("4:3");
        } else if ("Растянуть".equals(name)) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            player.setAspectRatio(w + ":" + h);
            player.setScale(0f);
        } else {
            player.setAspectRatio(null);
            player.setScale(1f);
        }
    }

    private static String shortAspect(String name) {
        if ("По размеру".equals(name)) return "⬚";
        if ("Растянуть".equals(name)) return "⬌";
        if ("Оригинал".equals(name)) return "1:1";
        return name;
    }

    private void setupGestures() {
        final View overlay = findViewById(R.id.gesture_overlay);
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        overlay.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            long baseTime;
            boolean dragging;
            long target;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        baseTime = player.getTime();
                        dragging = false;
                        target = baseTime;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                            dragging = true;
                        }
                        if (dragging) {
                            int width = v.getWidth() > 0 ? v.getWidth() : 1;
                            long len = player.getLength();
                            long delta = (long) (dx / width * SWIPE_FULL_WIDTH_MS);
                            target = baseTime + delta;
                            if (target < 0) target = 0;
                            if (len > 0 && target > len) target = len;
                            String sign = delta >= 0 ? "+" : "−";
                            long absSec = Math.abs(delta) / 1000;
                            gestureInfo.setVisibility(View.VISIBLE);
                            gestureInfo.setText(formatTime(target) + "   " + sign + formatClock(absSec));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            player.setTime(target);
                            gestureInfo.setVisibility(View.GONE);
                            showControlsTemporarily();
                        } else {
                            toggleControls();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void togglePlay() {
        if (player.isPlaying()) {
            player.pause();
            playPause.setText("▶");
        } else {
            player.play();
            playPause.setText("⏸");
        }
        showControlsTemporarily();
    }

    private void jump(long deltaMs) {
        long t = player.getTime() + deltaMs;
        if (t < 0) t = 0;
        long len = player.getLength();
        if (len > 0 && t > len) t = len;
        player.setTime(t);
        showControlsTemporarily();
    }

    private void updateProgress() {
        if (player == null) return;
        long len = player.getLength();
        long time = player.getTime();
        if (!seeking && len > 0) {
            seekBar.setProgress((int) (time * 1000 / len));
        }
        timeLabel.setText(formatTime(time) + " / " + formatTime(len));
        playPause.setText(player.isPlaying() ? "⏸" : "▶");
    }

    private void toggleControls() { setControls(!controlsVisible); }

    private void setControls(boolean show) {
        controlsVisible = show;
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(hideControls);
        if (show) ui.postDelayed(hideControls, AUTO_HIDE_MS);
    }

    private void showControlsTemporarily() { setControls(true); }

    private static String formatRate(float r) {
        String s = String.format(Locale.US, "%.2f", r);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s + "x";
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        return formatClock(ms / 1000);
    }

    private static String formatClock(long totalSec) {
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            player.stop();
            player.detachViews();
            player.release();
            player = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
    }
}
