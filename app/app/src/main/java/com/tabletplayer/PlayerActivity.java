package com.tabletplayer;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.GestureDetector;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Locale;

public class PlayerActivity extends AppCompatActivity {

    private static final long SWIPE_FULL_WIDTH_MS = 120000L;
    private static final long JUMP_MS = 10000L;
    private static final long JUMP90_MS = 90000L;
    private static final long AUTO_HIDE_MS = 3500L;

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;

    private View overlay;
    private View controls;
    private TextView gestureInfo;
    private TextView timeText;
    private SeekBar seekBar;
    private ImageButton playPause;
    private ImageButton fullscreenBtn;
    private Button speedBtn;
    private ImageButton aspectBtn;

    private GestureDetector gestureDetector;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean controlsVisible = true;
    private boolean immersive = false;
    private boolean userSeeking = false;
    private boolean dragging = false;
    private float downX = 0f;
    private long downTime = 0L;
    private long seekTarget = 0L;

    private final double[] speeds = {1.0, 1.25, 1.5, 2.0, 0.5, 0.75};
    private int speedIdx = 0;

    private final String[] aspectNames = {"По размеру", "16:9", "4:3", "Растянуть", "Оригинал"};
    private int aspectIdx = 0;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            main.postDelayed(this, 500);
        }
    };

    private final Runnable hideControls = new Runnable() {
        @Override public void run() { setControlsVisible(false); }
    };

    private final Runnable hideInfo = new Runnable() {
        @Override public void run() { gestureInfo.setVisibility(View.GONE); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        videoLayout = (VLCVideoLayout) findViewById(R.id.video_layout);
        overlay = findViewById(R.id.gesture_overlay);
        controls = findViewById(R.id.controls);
        gestureInfo = (TextView) findViewById(R.id.gesture_info);
        timeText = (TextView) findViewById(R.id.time);
        seekBar = (SeekBar) findViewById(R.id.seek);
        playPause = (ImageButton) findViewById(R.id.play_pause);
        fullscreenBtn = (ImageButton) findViewById(R.id.fullscreen);
        speedBtn = (Button) findViewById(R.id.speed);
        aspectBtn = (ImageButton) findViewById(R.id.aspect);
        ImageButton rew = (ImageButton) findViewById(R.id.rew);
        ImageButton fwd = (ImageButton) findViewById(R.id.fwd);
        ImageButton fwd90 = (ImageButton) findViewById(R.id.fwd90);

        ArrayList<String> options = new ArrayList<String>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--network-caching=1500");
        libVLC = new LibVLC(this, options);
        player = new MediaPlayer(libVLC);
        player.attachViews(videoLayout, null, false, false);

        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        if (title != null) {
            Toast.makeText(this, title, Toast.LENGTH_SHORT).show();
        }

        Media media = new Media(libVLC, Uri.parse(url));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":network-caching=1500");
        player.setMedia(media);
        media.release();

        player.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                    case MediaPlayer.Event.Paused:
                    case MediaPlayer.Event.Stopped:
                        updatePlayIcon();
                        break;
                    case MediaPlayer.Event.EndReached:
                        finish();
                        break;
                    default:
                        break;
                }
            }
        });

        setupGestures();

        playPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        rew.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jumpBy(-JUMP_MS); flash("−10 сек"); }
        });
        fwd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jumpBy(JUMP_MS); flash("+10 сек"); }
        });
        fwd90.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jumpBy(JUMP90_MS); flash("+1:30"); }
        });
        speedBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleSpeed(); }
        });
        aspectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleAspect(); }
        });
        fullscreenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleImmersive(); }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    long len = player.getLength();
                    long t = (long) (progress / 1000.0 * len);
                    timeText.setText(formatTime(t) + " / " + formatTime(len));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { userSeeking = true; }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                long len = player.getLength();
                player.setTime((long) (sb.getProgress() / 1000.0 * len));
                userSeeking = false;
            }
        });

        player.play();
        updatePlayIcon();
        setControlsVisible(true);
        main.post(ticker);
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float x = e.getX();
                int w = overlay.getWidth();
                if (x < w / 3f) {
                    jumpBy(-JUMP_MS);
                    flash("−10 сек");
                } else if (x > 2f * w / 3f) {
                    jumpBy(JUMP_MS);
                    flash("+10 сек");
                } else {
                    togglePlay();
                }
                return true;
            }
        });

        overlay.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                gestureDetector.onTouchEvent(ev);
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = ev.getX();
                        downTime = player.getTime();
                        dragging = false;
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getX() - downX;
                        if (Math.abs(dx) > 30) {
                            dragging = true;
                            long len = player.getLength();
                            if (len <= 0) break;
                            float frac = dx / Math.max(1, v.getWidth());
                            long target = downTime + (long) (frac * SWIPE_FULL_WIDTH_MS);
                            if (target < 0) target = 0;
                            if (target > len) target = len;
                            seekTarget = target;
                            long diff = (target - downTime) / 1000L;
                            String sign = diff >= 0 ? "+" : "−";
                            gestureInfo.setText(formatTime(target) + " / " + formatTime(len)
                                    + "  (" + sign + Math.abs(diff) + " сек)");
                            gestureInfo.setVisibility(View.VISIBLE);
                            setControlsVisible(true);
                        }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            player.setTime(seekTarget);
                            main.removeCallbacks(hideInfo);
                            main.postDelayed(hideInfo, 700);
                            dragging = false;
                            scheduleAutoHide();
                        }
                        break;
                }
                return true;
            }
        });
    }

    private void togglePlay() {
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
        updatePlayIcon();
        setControlsVisible(true);
    }

    private void updatePlayIcon() {
        main.post(new Runnable() {
            @Override
            public void run() {
                playPause.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            }
        });
    }

    private void jumpBy(long deltaMs) {
        long len = player.getLength();
        long t = player.getTime() + deltaMs;
        if (t < 0) t = 0;
        if (len > 0 && t > len) t = len;
        player.setTime(t);
        setControlsVisible(true);
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.length;
        float rate = (float) speeds[speedIdx];
        player.setRate(rate);
        speedBtn.setText(String.format(Locale.US, "%.2gx", rate).replace(",", "."));
        speedBtn.setText(trimSpeed(rate));
        flash("Скорость " + trimSpeed(rate));
        setControlsVisible(true);
    }

    private static String trimSpeed(float rate) {
        if (rate == (long) rate) {
            return String.format(Locale.US, "%.1fx", rate);
        }
        return String.format(Locale.US, "%sx", ("" + rate));
    }

    private void cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspectNames.length;
        applyAspect(aspectIdx);
        flash(aspectNames[aspectIdx]);
        setControlsVisible(true);
    }

    private void applyAspect(int idx) {
        switch (idx) {
            case 0:
                player.setAspectRatio(null);
                player.setScale(0f);
                break;
            case 1:
                player.setAspectRatio("16:9");
                player.setScale(0f);
                break;
            case 2:
                player.setAspectRatio("4:3");
                player.setScale(0f);
                break;
            case 3:
                int w = videoLayout.getWidth();
                int h = videoLayout.getHeight();
                if (w > 0 && h > 0) {
                    player.setAspectRatio(w + ":" + h);
                }
                player.setScale(0f);
                break;
            case 4:
                player.setAspectRatio(null);
                player.setScale(1f);
                break;
            default:
                break;
        }
    }

    private void toggleImmersive() {
        immersive = !immersive;
        applyImmersive(immersive);
        fullscreenBtn.setImageResource(immersive ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        flash(immersive ? "Весь экран" : "Оконный режим");
    }

    private void applyImmersive(boolean on) {
        View decor = getWindow().getDecorView();
        if (on) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) {
            applyImmersive(true);
        }
    }

    private void toggleControls() {
        setControlsVisible(!controlsVisible);
    }

    private void setControlsVisible(boolean visible) {
        controlsVisible = visible;
        main.removeCallbacks(hideControls);
        if (visible) {
            if (controls.getVisibility() != View.VISIBLE) {
                controls.setVisibility(View.VISIBLE);
                controls.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_in));
            }
            scheduleAutoHide();
        } else {
            if (controls.getVisibility() == View.VISIBLE) {
                controls.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_out));
                controls.setVisibility(View.GONE);
            }
        }
    }

    private void scheduleAutoHide() {
        main.removeCallbacks(hideControls);
        if (player.isPlaying()) {
            main.postDelayed(hideControls, AUTO_HIDE_MS);
        }
    }

    private void flash(String text) {
        gestureInfo.setText(text);
        gestureInfo.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideInfo);
        main.postDelayed(hideInfo, 700);
    }

    private void updateProgress() {
        if (player == null) return;
        long len = player.getLength();
        long t = player.getTime();
        if (!userSeeking && !dragging) {
            timeText.setText(formatTime(t) + " / " + formatTime(len));
            if (len > 0) {
                seekBar.setProgress((int) (t * 1000L / len));
            }
        }
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null && player.isPlaying()) {
            player.pause();
            updatePlayIcon();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
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

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }
}
