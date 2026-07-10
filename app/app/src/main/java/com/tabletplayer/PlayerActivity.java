package com.tabletplayer;

import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.GestureDetector;
import android.view.MotionEvent;
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

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PlayerActivity extends AppCompatActivity {
    private static final int JUMP_MS = 10000;
    private static final int JUMP90_MS = 90000;
    private static final int AUTO_HIDE_MS = 3500;
    private static final long SWIPE_FULL_WIDTH_MS = 120000;

    private String base, path, name, folder;

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private View controls, gestureOverlay, buffering;
    private TextView time, gestureInfo, bufferingText;
    private SeekBar seek;
    private ImageButton prev, rew, playPause, fwd, fwd90, next, aspect, fullscreen;
    private android.widget.Button speed;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long duration = 0, currentMs = 0, pendingResumeMs = 0, seekPreview = 0;
    private boolean started = false, controlsVisible = true, dragging = false, immersive = true;
    private int mode = 0; // 0 none, 1 seek, 2 volume
    private long dragStartTime = 0;
    private int dragStartVol = 100, volume = 100, reconnectAttempts = 0;

    private final float[] speeds = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f};
    private int speedIdx = 0;
    private final String[] aspectNames = {"По размеру", "16:9", "4:3", "Растянуть", "Оригинал"};
    private int aspectIdx = 0;

    private final List<String> episodePaths = new ArrayList<>();
    private final List<String> episodeNames = new ArrayList<>();
    private int episodeIndex = -1;

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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        base = getIntent().getStringExtra("base");
        path = getIntent().getStringExtra("path");
        name = getIntent().getStringExtra("name");
        folder = getIntent().getStringExtra("folder");
        if (folder == null) folder = "";

        videoLayout = findViewById(R.id.video_layout);
        controls = findViewById(R.id.controls);
        gestureOverlay = findViewById(R.id.gesture_overlay);
        buffering = findViewById(R.id.buffering);
        bufferingText = (TextView) ((android.view.ViewGroup) buffering).getChildAt(1);
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

        prev.setOnClickListener(v -> { playPrev(); showControls(); });
        rew.setOnClickListener(v -> { seekRelative(-JUMP_MS); showControls(); });
        playPause.setOnClickListener(v -> { togglePlay(); showControls(); });
        fwd.setOnClickListener(v -> { seekRelative(JUMP_MS); showControls(); });
        fwd90.setOnClickListener(v -> { seekRelative(JUMP90_MS); showControls(); });
        next.setOnClickListener(v -> { playNext(); showControls(); });
        speed.setOnClickListener(v -> { cycleSpeed(); showControls(); });
        aspect.setOnClickListener(v -> { cycleAspect(); showControls(); });
        fullscreen.setOnClickListener(v -> { toggleImmersive(); showControls(); });

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
                if (duration > 0) player.setTime(sb.getProgress() * duration / 1000);
                showControls();
            }
        });

        setupGestures();
        initPlayer();
        setupSession();
        askResume();
        fetchEpisodes();
    }

    private void initPlayer() {
        ArrayList<String> options = new ArrayList<>();
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--network-caching=1500");
        libVLC = new LibVLC(this, options);
        player = new MediaPlayer(libVLC);
        player.attachViews(videoLayout, null, false, false);
        player.setEventListener(event -> {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    reconnectAttempts = 0;
                    started = true;
                    hideBuffering();
                    if (pendingResumeMs > 0) {
                        player.setTime(pendingResumeMs);
                        pendingResumeMs = 0;
                    }
                    player.setVolume(volume);
                    updatePlayIcon();
                    updatePlaybackState();
                    break;
                case MediaPlayer.Event.Buffering:
                    float pct = event.getBuffering();
                    if (pct >= 100f) hideBuffering();
                    else if (!started) showBuffering("Подготовка видео…");
                    break;
                case MediaPlayer.Event.Paused:
                    updatePlayIcon();
                    updatePlaybackState();
                    break;
                case MediaPlayer.Event.EndReached:
                    onEnd();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    onError();
                    break;
            }
        });
    }

    private void setupSession() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        session = new MediaSessionCompat(this, "TabletPlayer");
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
                playNext();
            }

            @Override
            public void onSkipToPrevious() {
                playPrev();
            }

            @Override
            public void onStop() {
                finish();
            }
        });
        session.setActive(true);
    }

    private void updatePlaybackState() {
        if (session == null) return;
        long pos = player != null ? player.getTime() : 0;
        int state = (player != null && player.isPlaying())
                ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        PlaybackStateCompat s = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(state, pos, 1f)
                .build();
        session.setPlaybackState(s);
    }

    private String streamUrl(String p) {
        return base + "/download?path=" + Util.enc(p)
                + "&dev=" + Util.enc(App.deviceId(this))
                + "&dn=" + Util.enc(App.deviceName());
    }

    private void askResume() {
        long saved = Store.getPos(this, path);
        if (saved > 5000) {
            new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage("Продолжить с " + Util.fmtTime(saved) + "?")
                    .setPositiveButton("Продолжить", (d, w) -> playPath(path, name, saved))
                    .setNegativeButton("Сначала", (d, w) -> playPath(path, name, 0))
                    .setCancelable(false)
                    .show();
        } else {
            playPath(path, name, 0);
        }
    }

    private void playPath(String p, String nm, long resumeMs) {
        path = p;
        name = nm;
        pendingResumeMs = resumeMs;
        started = false;
        updateEpisodeIndex();
        setTitle(nm);
        showBuffering("Подготовка видео…");
        Media media = new Media(libVLC, Uri.parse(streamUrl(p)));
        media.setHWDecoderEnabled(true, false);
        media.addOption(":network-caching=1500");
        player.setMedia(media);
        media.release();
        player.play();
        player.setRate(speeds[speedIdx]);
        showControls();
    }

    private void playEpisode(int index) {
        if (index < 0 || index >= episodePaths.size()) return;
        if (player != null && player.getTime() > 0) Store.setPos(this, path, player.getTime());
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

    private void onEnd() {
        Store.clearPos(this, path);
        Store.markWatched(this, path);
        showNextDialog();
    }

    private void onError() {
        reconnectAttempts++;
        if (reconnectAttempts <= 5) {
            final long resume = currentMs;
            showBuffering("Переподключение… (" + reconnectAttempts + ")");
            ui.postDelayed(() -> playPath(path, name, resume), 2000);
        } else {
            Toast.makeText(this, "Ошибка воспроизведения", Toast.LENGTH_LONG).show();
        }
    }

    private void showNextDialog() {
        if (episodePaths.isEmpty() || episodeIndex < 0 || episodeIndex + 1 >= episodePaths.size()) {
            finish();
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
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
                .setNegativeButton("Выйти", (d, w) -> {
                    finish();
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                })
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
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        App.auth(c, this);
        c.setConnectTimeout(8000);
        c.setReadTimeout(40000);
        if (c.getResponseCode() != 200) {
            c.disconnect();
            throw new RuntimeException("HTTP " + c.getResponseCode());
        }
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        c.disconnect();
        return bo.toString("UTF-8");
    }

    private void setPlaying(boolean play) {
        if (player == null) return;
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
        player.setTime(t);
        flashInfo((delta > 0 ? "+" : "") + (delta / 1000) + " сек");
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.length;
        player.setRate(speeds[speedIdx]);
        speed.setText(speeds[speedIdx] + "x");
    }

    private void cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspectNames.length;
        applyAspect();
        flashInfo(aspectNames[aspectIdx]);
    }

    private void applyAspect() {
        if (player == null) return;
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

    private void setupGestures() {
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (controlsVisible) hideControls();
                else showControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int w = gestureOverlay.getWidth();
                float x = e.getX();
                if (x < w / 3f) seekRelative(-JUMP_MS);
                else if (x > 2f * w / 3f) seekRelative(JUMP_MS);
                else togglePlay();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                int w = gestureOverlay.getWidth(), h = gestureOverlay.getHeight();
                float totalX = e2.getX() - e1.getX();
                float totalY = e2.getY() - e1.getY();
                if (!dragging || mode == 0) {
                    if (Math.abs(totalX) > Math.abs(totalY) && Math.abs(totalX) > 40) {
                        mode = 1;
                        dragging = true;
                        dragStartTime = player.getTime();
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
                    flashInfoSticky("🔊 " + nv + "%");
                }
                return true;
            }
        });

        gestureOverlay.setOnTouchListener((v, ev) -> {
            gd.onTouchEvent(ev);
            if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                if (dragging && mode == 1 && player != null) player.setTime(seekPreview);
                if (dragging) gestureInfo.setVisibility(View.GONE);
                dragging = false;
                mode = 0;
            }
            return true;
        });
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
        bufferingText.setText(text);
        buffering.setVisibility(View.VISIBLE);
    }

    private void hideBuffering() {
        buffering.setVisibility(View.GONE);
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        controlsVisible = true;
        ui.removeCallbacks(hideRunnable);
        ui.postDelayed(hideRunnable, AUTO_HIDE_MS);
    }

    private void hideControls() {
        controls.setVisibility(View.GONE);
        controlsVisible = false;
    }

    private void toggleImmersive() {
        immersive = !immersive;
        applyImmersive();
        fullscreen.setImageResource(immersive ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
    }

    private void applyImmersive() {
        View d = getWindow().getDecorView();
        if (immersive) {
            d.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void updateTime() {
        if (player == null) return;
        duration = player.getLength();
        currentMs = player.getTime();
        time.setText(Util.fmtTime(currentMs) + " / " + Util.fmtTime(duration));
        if (duration > 0 && !dragging) seek.setProgress((int) (currentMs * 1000 / duration));
        if (player.isPlaying() && currentMs > 0) Store.setPos(this, path, currentMs);
        updatePlaybackState();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) applyImmersive();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.post(ticker);
        applyImmersive();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(ticker);
        if (player != null && currentMs > 0) Store.setPos(this, path, currentMs);
        if (player != null && player.isPlaying()) setPlaying(false);
    }

    @Override
    public void onBackPressed() {
        if (player != null && currentMs > 0) Store.setPos(this, path, currentMs);
        super.onBackPressed();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (session != null) session.release();
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
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
