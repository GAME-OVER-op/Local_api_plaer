package com.tabletplayer;

import android.content.res.Configuration;
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
import android.widget.ProgressBar;
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

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PlayerActivity extends AppCompatActivity {
    private static final int JUMP_MS = 10000;
    private static final int JUMP90_MS = 90000;
    private static final int AUTO_HIDE_MS = 3500;
    private static final long SWIPE_FULL_WIDTH_MS = 120000;
    private static final int NET_CACHING = 4000;
    private static final long POSITION_SAVE_INTERVAL_MS = 10000;
    private static final long CACHE_PREPARE_TIMEOUT_MS = 30000;
    private static final long CACHE_EARLY_ESTIMATE_MS = 8000;
    private static final long CACHE_LOCAL_START_TIMEOUT_MS = 10000;
    private static final long DIRECT_RESUME_LIMIT_MS = 10L * 60L * 1000L;
    private static final float TOP_GESTURE_DEAD_ZONE = 0.20f;

    private static final int SOURCE_DIRECT = 0;
    private static final int SOURCE_CACHE_PREPARING = 1;
    private static final int SOURCE_CACHE_LOCAL = 2;

    private String base, path, name, folder, serverName;
    private boolean local = false;

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private View controls, gestureOverlay, buffering;
    private TextView time, gestureInfo, bufferingText, titleBar, cacheStatus;
    private ProgressBar bufferSpinner, bufferProgress;
    private android.widget.Button retryBtn;
    private SeekBar seek;
    private ImageButton prev, rew, playPause, fwd, fwd90, next, aspect, fullscreen;
    private android.widget.Button speed;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long duration = 0, currentMs = 0, pendingResumeMs = 0, seekPreview = 0;
    private boolean started = false, controlsVisible = true, dragging = false, immersive = true;
    private int mode = 0; // 0 none, 1 seek, 2 volume
    private long dragStartTime = 0;
    private int dragStartVol = 100, volume = 100, reconnectAttempts = 0;
    private boolean reconnecting = false;
    private boolean currentCompleted = false;
    private long resumeMs = 0;
    private long lastPositionSaveAt = 0;
    private long lastSavedPosition = -1;
    private long lastKnownDuration = 0;
    private PlaybackCache playbackCache;
    private int sourceMode = SOURCE_DIRECT;
    private long cacheRequestedResumeMs = 0;
    private long waitingSeekMs = 0;
    private boolean ignoreGestureSequence = false;
    private boolean viewsDetached = false;
    private boolean destroyed = false;
    private boolean suppressTerminalEvents = false;
    private boolean localPlaybackEstablished = false;
    private final Set<String> directOnlyThisSession = new HashSet<>();

    private final float[] speeds = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f};
    private int speedIdx = 0;
    private final String[] aspectNames = {"По размеру", "16:9", "4:3", "Растянуть", "Оригинал"};
    private int aspectIdx = 0;

    private final List<String> episodePaths = new ArrayList<>();
    private final List<String> episodeNames = new ArrayList<>();
    private final Map<String, Long> knownFileSizes = new HashMap<>();
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
    private final Runnable cacheDecision = new Runnable() {
        @Override
        public void run() {
            evaluateCachePreparation();
        }
    };
    private final Runnable localStartTimeout = new Runnable() {
        @Override
        public void run() {
            if (sourceMode == SOURCE_CACHE_LOCAL && !localPlaybackEstablished) {
                fallbackToDirect("Локальный кэш не смог начать воспроизведение");
            }
        }
    };
    private final Runnable clearTerminalSuppression = new Runnable() {
        @Override
        public void run() {
            suppressTerminalEvents = false;
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        base = getIntent().getStringExtra("base");
        path = getIntent().getStringExtra("path");
        name = getIntent().getStringExtra("name");
        folder = getIntent().getStringExtra("folder");
        serverName = getIntent().getStringExtra("server_name");
        local = getIntent().getBooleanExtra("local", false);
        long initialSize = getIntent().getLongExtra("size", 0L);
        if (!local && path != null && initialSize > 0) knownFileSizes.put(path, initialSize);
        if (folder == null) folder = "";
        if (serverName == null) serverName = "";

        String[] queuePathsExtra = getIntent().getStringArrayExtra("queue_paths");
        String[] queueNamesExtra = getIntent().getStringArrayExtra("queue_names");
        long[] queueSizesExtra = getIntent().getLongArrayExtra("queue_sizes");
        if (queuePathsExtra != null && queueNamesExtra != null && queuePathsExtra.length > 0
                && queuePathsExtra.length == queueNamesExtra.length) {
            hasQueue = true;
            for (int qi = 0; qi < queuePathsExtra.length; qi++) {
                episodePaths.add(queuePathsExtra[qi]);
                episodeNames.add(queueNamesExtra[qi]);
                if (queueSizesExtra != null && qi < queueSizesExtra.length && queueSizesExtra[qi] > 0) {
                    knownFileSizes.put(queuePathsExtra[qi], queueSizesExtra[qi]);
                }
            }
        }

        volume = Store.getVolume(this, 100);
        aspectIdx = Store.getAspect(this, 0);
        videoLayout = findViewById(R.id.video_layout);
        controls = findViewById(R.id.controls);
        gestureOverlay = findViewById(R.id.gesture_overlay);
        buffering = findViewById(R.id.buffering);
        bufferingText = findViewById(R.id.buffering_text);
        bufferSpinner = findViewById(R.id.buffer_spinner);
        bufferProgress = findViewById(R.id.buffer_progress);
        cacheStatus = findViewById(R.id.cache_status);
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

        retryBtn.setOnClickListener(v -> {
            retryBtn.setVisibility(View.GONE);
            reconnectAttempts = 0;
            reconnectStep();
        });

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
                if (duration > 0 && player != null) requestSeek(sb.getProgress() * duration / 1000);
                showControls();
            }
        });

        videoLayout.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                ui.postDelayed(this::applyAspect, 120);
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

    private void initPlayer() {
        ArrayList<String> options = new ArrayList<>();
        options.add("--network-caching=" + NET_CACHING);
        options.add("--file-caching=" + NET_CACHING);
        libVLC = new LibVLC(this, options);
        player = new MediaPlayer(libVLC);
        player.attachViews(videoLayout, null, false, false);
        player.setEventListener(event -> {
            final int type = event.type;
            final float pct = (type == MediaPlayer.Event.Buffering) ? event.getBuffering() : 0f;
            ui.post(() -> handlePlayerEvent(type, pct));
        });
    }

    private void handlePlayerEvent(int type, float pct) {
        if (destroyed || player == null || sourceMode == SOURCE_CACHE_PREPARING) return;
        if (suppressTerminalEvents
                && (type == MediaPlayer.Event.EndReached || type == MediaPlayer.Event.EncounteredError)) {
            return;
        }
        switch (type) {
            case MediaPlayer.Event.Playing:
                reconnectAttempts = 0;
                reconnecting = false;
                started = true;
                if (sourceMode != SOURCE_CACHE_LOCAL) ui.removeCallbacks(localStartTimeout);
                if (pendingResumeMs > 0) {
                    long target = pendingResumeMs;
                    pendingResumeMs = 0;
                    requestSeek(target);
                }
                if (waitingSeekMs <= 0) hideBuffering();
                player.setVolume(volume);
                applyAspect();
                updateCacheUi();
                updatePlayIcon();
                updatePlaybackState();
                break;
            case MediaPlayer.Event.Buffering:
                if (sourceMode == SOURCE_CACHE_LOCAL) {
                    if (!started) {
                        showBuffering("Запуск из локального кэша…");
                    } else if (playbackCache != null && (playbackCache.isWaitingForData() || waitingSeekMs > 0)) {
                        showCacheWaiting();
                    } else if (pct >= 100f) {
                        waitingSeekMs = 0;
                        hideBuffering();
                    }
                    updateCacheUi();
                } else if (!started) {
                    if (!reconnecting) showBuffering("Подготовка… " + (int) pct + "%");
                } else if (pct >= 100f) {
                    hideBuffering();
                }
                break;
            case MediaPlayer.Event.Paused:
                saveCurrentPosition(true);
                updatePlayIcon();
                updatePlaybackState();
                break;
            case MediaPlayer.Event.EndReached:
                handleEnd();
                break;
            case MediaPlayer.Event.EncounteredError:
                if (sourceMode == SOURCE_CACHE_LOCAL) fallbackToDirect("Формат не поддержал локальную дозагрузку");
                else handleDrop();
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
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(state, pos, 1f)
                .build();
        session.setPlaybackState(s);
    }

    private String streamUrl(String p) {
        return App.signedUrl(this, base + "/download?path=" + Util.enc(p));
    }

    private void askResume() {
        long saved = Store.getPos(this, path);
        if (saved > 5000) {
            new AlertDialog.Builder(this)
                    .setTitle(name)
                    .setMessage("Продолжить с " + Util.fmtTime(saved) + "?")
                    .setPositiveButton("Продолжить", (d, w) -> playPath(path, name, saved))
                    .setNegativeButton("Сначала", (d, w) -> {
                        Store.clearPos(this, path);
                        playPath(path, name, 0);
                    })
                    .setCancelable(false)
                    .show();
        } else {
            playPath(path, name, 0);
        }
    }

    private Media buildMedia(Uri uri, boolean network) {
        Media media = new Media(libVLC, uri);
        media.setHWDecoderEnabled(true, false);
        media.addOption((network ? ":network-caching=" : ":file-caching=") + NET_CACHING);
        return media;
    }

    private void playPath(String p, String nm, long resume) {
        ui.removeCallbacks(cacheDecision);
        ui.removeCallbacks(localStartTimeout);
        if (player != null) {
            stopPlayerForTransition();
            updatePlayIcon();
        }
        stopPlaybackCache();
        path = p;
        name = nm;
        pendingResumeMs = resume;
        currentMs = resume;
        duration = 0;
        currentCompleted = false;
        lastPositionSaveAt = 0;
        lastSavedPosition = resume;
        lastKnownDuration = Store.getDuration(this, p);
        started = false;
        localPlaybackEstablished = false;
        waitingSeekMs = 0;
        if (!local) updateEpisodeIndex();
        setTitle(nm);
        titleBar.setText(nm);
        cacheStatus.setVisibility(View.GONE);
        showControls();

        if (local) {
            sourceMode = SOURCE_DIRECT;
            startMedia(Uri.fromFile(new File(p)), resume, false, "Подготовка…");
        } else if (isFarResume(resume, lastKnownDuration) || directOnlyThisSession.contains(p)) {
            startDirectPlayback(resume, isFarResume(resume, lastKnownDuration)
                    ? "Быстрое продолжение с сервера…" : "Прямое воспроизведение…");
        } else {
            startCachePreparation(resume);
        }
    }

    private boolean isFarResume(long resume, long knownDuration) {
        return resume > DIRECT_RESUME_LIMIT_MS
                || (knownDuration > 0 && resume > 0 && resume * 10L >= knownDuration);
    }

    private void startMedia(Uri uri, long resume, boolean network, String message) {
        if (player == null) return;
        pendingResumeMs = resume;
        currentMs = resume;
        started = false;
        if (message != null && !message.isEmpty()) showBuffering(message);
        Media media = buildMedia(uri, network);
        player.setMedia(media);
        media.release();
        player.play();
        player.setRate(speeds[speedIdx]);
    }

    private void startDirectPlayback(long resume, String message) {
        ui.removeCallbacks(cacheDecision);
        ui.removeCallbacks(localStartTimeout);
        stopPlaybackCache();
        sourceMode = SOURCE_DIRECT;
        cacheStatus.setVisibility(View.GONE);
        startMedia(Uri.parse(streamUrl(path)), resume, true, message);
    }

    private void startCachePreparation(long resume) {
        sourceMode = SOURCE_CACHE_PREPARING;
        cacheRequestedResumeMs = resume;
        showCachePreparation();
        try {
            playbackCache = new PlaybackCache(this, () -> {
                if (!destroyed) ui.post(this::updateCacheUi);
            });
            Long knownSize = knownFileSizes.get(path);
            playbackCache.start(base, path, knownSize == null ? -1L : knownSize);
            ui.post(cacheDecision);
        } catch (Throwable e) {
            fallbackToDirect("Локальный кэш недоступен");
        }
    }

    private void evaluateCachePreparation() {
        if (sourceMode != SOURCE_CACHE_PREPARING || playbackCache == null) return;
        updateCacheUi();
        if (playbackCache.isFailed()) {
            fallbackToDirect(playbackCache.getError());
            return;
        }
        if (playbackCache.isReadyToPlay()) {
            startLocalCachePlayback();
            return;
        }
        long elapsed = System.currentTimeMillis() - playbackCache.getStartedAtMs();
        if (elapsed >= CACHE_PREPARE_TIMEOUT_MS) {
            fallbackToDirect("Подготовка заняла больше 30 секунд");
            return;
        }
        long downloaded = playbackCache.getDownloadedBytes();
        long target = playbackCache.getPrepareTargetBytes();
        if (elapsed >= CACHE_EARLY_ESTIMATE_MS && downloaded >= 4L * 1024L * 1024L && target > downloaded) {
            long projected = elapsed * target / Math.max(1L, downloaded);
            if (projected > CACHE_PREPARE_TIMEOUT_MS + 15000L) {
                fallbackToDirect("Скорости недостаточно для быстрой подготовки");
                return;
            }
        }
        ui.postDelayed(cacheDecision, 250L);
    }

    private void startLocalCachePlayback() {
        if (playbackCache == null || playbackCache.localUrl() == null) {
            fallbackToDirect("Локальный кэш недоступен");
            return;
        }
        sourceMode = SOURCE_CACHE_LOCAL;
        localPlaybackEstablished = false;
        showBuffering("Запуск из локального кэша…");
        updateCacheUi();
        startMedia(Uri.parse(playbackCache.localUrl()), cacheRequestedResumeMs, true,
                "Запуск из локального кэша…");
        ui.postDelayed(localStartTimeout, CACHE_LOCAL_START_TIMEOUT_MS);
    }

    private void fallbackToDirect(String reason) {
        if (local || path == null || player == null) return;
        long resume = waitingSeekMs > 0 ? waitingSeekMs
                : (player.getTime() > 0 ? player.getTime()
                : (pendingResumeMs > 0 ? pendingResumeMs : cacheRequestedResumeMs));
        directOnlyThisSession.add(path);
        waitingSeekMs = 0;
        if (reason != null && !reason.isEmpty()) {
            Toast.makeText(this, reason + ". Переключение на сервер.", Toast.LENGTH_SHORT).show();
        }
        stopPlayerForTransition();
        startDirectPlayback(resume, "Запуск прямого воспроизведения…");
    }

    private void stopPlayerForTransition() {
        if (player == null) return;
        suppressTerminalEvents = true;
        ui.removeCallbacks(clearTerminalSuppression);
        try { player.stop(); } catch (Throwable ignored) {}
        ui.postDelayed(clearTerminalSuppression, 750L);
    }

    private void stopPlaybackCache() {
        PlaybackCache cache = playbackCache;
        playbackCache = null;
        if (cache != null) cache.cancelAndDelete();
        cacheStatus.setVisibility(View.GONE);
    }

    private void playEpisode(int index) {
        if (index < 0 || index >= episodePaths.size()) return;
        saveCurrentPosition(true);
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

    private void handleEnd() {
        if (local) {
            currentCompleted = true;
            Store.clearPos(this, path);
            finishFade();
            return;
        }

        // При EndReached libVLC может уже сбросить getTime()/getLength() в ноль.
        // Поэтому используем последние устойчивые значения, сохранённую длительность
        // и состояние последовательного кэша.
        long eventPosition = currentMs;
        long eventDuration = Math.max(duration, lastKnownDuration);
        try {
            long reportedPosition = player != null ? player.getTime() : 0L;
            long reportedDuration = player != null ? player.getLength() : 0L;
            if (reportedPosition > eventPosition) eventPosition = reportedPosition;
            if (reportedDuration > eventDuration) eventDuration = reportedDuration;
        } catch (Throwable ignored) {
        }

        PlaybackCache cache = playbackCache;
        boolean cacheHasWholeFile = sourceMode == SOURCE_CACHE_LOCAL
                && cache != null
                && cache.getTotalBytes() > 0
                && cache.getDownloadedBytes() >= cache.getTotalBytes();
        boolean reachedTimelineEnd = eventDuration > 0
                && eventPosition > 0
                && eventPosition >= eventDuration - 8000L;

        // Полностью отданный локальный файл не может закончиться из-за потери
        // соединения с основным сервером. Для прямого потока сохраняем прежнюю
        // защиту от ложного EndReached при сетевом обрыве.
        if (cacheHasWholeFile || reachedTimelineEnd) {
            currentCompleted = true;
            currentMs = eventDuration > 0 ? eventDuration : eventPosition;
            Store.clearPos(this, path);
            Store.markWatched(this, path);
            reconnecting = false;
            reconnectAttempts = 0;
            ui.removeCallbacks(reconnectAgain);
            hideBuffering();
            showNextDialog();
        } else {
            handleDrop();
        }
    }

    private void handleDrop() {
        if (local) {
            Toast.makeText(this, "Ошибка воспроизведения файла", Toast.LENGTH_LONG).show();
            return;
        }
        if (sourceMode == SOURCE_CACHE_LOCAL || sourceMode == SOURCE_CACHE_PREPARING) {
            fallbackToDirect("Локальная дозагрузка прервалась");
        } else {
            reconnectStep();
        }
    }

    /** Статус «поиск сервера»: повтор на текущем IP, затем переобнаружение (IP мог смениться). */
    private void reconnectStep() {
        if (player == null) return;
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
            ui.postDelayed(() -> reloadStream(resumeMs), 1500);
        } else {
            showBuffering("Поиск сервера в сети…");
            rediscover(resumeMs);
        }
    }

    private void reloadStream(long ms) {
        if (player == null) return;
        sourceMode = SOURCE_DIRECT;
        startMedia(Uri.parse(streamUrl(path)), ms, true, null);
    }

    private void rediscover(final long ms) {
        final int port = portFromBase(base);
        new Thread(() -> {
            final List<Discovery.Server> servers = Discovery.find(this, port, 2500);
            final String nb = pickServer(servers);
            ui.post(() -> {
                if (player == null) return;
                if (nb != null) {
                    base = nb;
                    reloadStream(ms);
                } else {
                    ui.postDelayed(reconnectAgain, 1500);
                }
            });
        }).start();
    }

    private String pickServer(List<Discovery.Server> servers) {
        // Сначала — сервер с тем же именем и наличием файла по тому же пути.
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
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(cand + "/download?path=" + Util.enc(p)).openConnection();
                App.auth(c, this);
                c.setRequestProperty("Range", "bytes=0-0");
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                int code = c.getResponseCode();
                if (code == 403 && attempt == 0 && App.retryPairingAfterForbidden(this, c)) continue;
                if (code == 200 || code == 206) {
                    App.markPaired(this, c);
                    return true;
                }
                return false;
            } catch (Exception e) {
                return false;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
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
                final List<Object[]> vids = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.getJSONObject(i);
                    if (e.getBoolean("is_dir")) continue;
                    String nm = e.getString("name");
                    if (!Util.isVideo(nm)) continue;
                    String fp = f.isEmpty() ? nm : f + "/" + nm;
                    vids.add(new Object[]{nm, fp, e.optLong("size", 0L)});
                }
                Collections.sort(vids, new Comparator<Object[]>() {
                    @Override
                    public int compare(Object[] a, Object[] b) {
                        return Util.naturalCompare((String) a[0], (String) b[0]);
                    }
                });
                ui.post(() -> {
                    episodeNames.clear();
                    episodePaths.clear();
                    for (Object[] v : vids) {
                        String episodeName = (String) v[0];
                        String episodePath = (String) v[1];
                        long episodeSize = (Long) v[2];
                        episodeNames.add(episodeName);
                        episodePaths.add(episodePath);
                        if (episodeSize > 0) knownFileSizes.put(episodePath, episodeSize);
                    }
                    updateEpisodeIndex();
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    private String httpGet(String u) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                App.auth(c, this);
                c.setConnectTimeout(8000);
                c.setReadTimeout(40000);
                int code = c.getResponseCode();
                if (code == 403 && attempt == 0 && App.retryPairingAfterForbidden(this, c)) continue;
                if (code != 200) throw new RuntimeException("HTTP " + code);
                App.markPaired(this, c);
                in = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
                return bo.toString("UTF-8");
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (c != null) c.disconnect();
            }
        }
        throw new RuntimeException("HTTP 403");
    }

    private void setPlaying(boolean play) {
        if (player == null) return;
        if (play && !player.isPlaying()) player.play();
        else if (!play && player.isPlaying()) player.pause();
        updatePlayIcon();
        updatePlaybackState();
    }

    private void togglePlay() {
        if (sourceMode == SOURCE_CACHE_PREPARING) {
            flashInfo("Подготовка серии…");
            return;
        }
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

    private void requestSeek(long target) {
        if (player == null) return;
        if (target < 0) target = 0;
        if (duration > 0 && target > duration) target = duration;
        waitingSeekMs = 0;
        currentMs = target;
        if (sourceMode == SOURCE_CACHE_LOCAL && playbackCache != null
                && duration > 0 && playbackCache.getTotalBytes() > 0) {
            long wantedByte = playbackCache.getTotalBytes() * target / duration;
            if (wantedByte > playbackCache.getDownloadedBytes()) {
                waitingSeekMs = target;
                showCacheWaiting();
            }
        }
        player.setTime(target);
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.length;
        player.setRate(speeds[speedIdx]);
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
                if (e.getY() < gestureOverlay.getHeight() * TOP_GESTURE_DEAD_ZONE) return false;
                if (controlsVisible) hideControls();
                else showControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (e.getY() < gestureOverlay.getHeight() * TOP_GESTURE_DEAD_ZONE) return false;
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
                if (e1 == null || e1.getY() < h * TOP_GESTURE_DEAD_ZONE) return false;
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
                    Store.setVolume(PlayerActivity.this, nv);
                    flashInfoSticky("🔊 " + nv + "%");
                }
                return true;
            }
        });

        gestureOverlay.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                ignoreGestureSequence = ev.getY() < v.getHeight() * TOP_GESTURE_DEAD_ZONE;
                if (ignoreGestureSequence) {
                    dragging = false;
                    mode = 0;
                    return false;
                }
            }
            if (ignoreGestureSequence) return false;
            gd.onTouchEvent(ev);
            if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                if (dragging && mode == 1 && player != null) requestSeek(seekPreview);
                if (dragging) gestureInfo.setVisibility(View.GONE);
                dragging = false;
                mode = 0;
                ignoreGestureSequence = false;
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
        bufferSpinner.setVisibility(View.VISIBLE);
        bufferProgress.setVisibility(View.GONE);
        retryBtn.setVisibility(View.GONE);
        buffering.setVisibility(View.VISIBLE);
    }

    private void showCachePreparation() {
        bufferingText.setText("Подготовка серии…");
        bufferSpinner.setVisibility(View.GONE);
        bufferProgress.setVisibility(View.VISIBLE);
        bufferProgress.setProgress(0);
        retryBtn.setVisibility(View.GONE);
        buffering.setVisibility(View.VISIBLE);
    }

    private void showCacheWaiting() {
        String target = waitingSeekMs > 0 ? "\nПереход к " + Util.fmtTime(waitingSeekMs) : "";
        showBuffering("Ожидание загрузки…" + target);
    }

    private void updateCacheUi() {
        PlaybackCache cache = playbackCache;
        if (cache == null) {
            cacheStatus.setVisibility(View.GONE);
            return;
        }
        if (cache.isFailed() && sourceMode == SOURCE_CACHE_LOCAL) {
            fallbackToDirect(cache.getError());
            return;
        }
        long downloaded = cache.getDownloadedBytes();
        long total = cache.getTotalBytes();
        if (sourceMode == SOURCE_CACHE_PREPARING) {
            long target = cache.getPrepareTargetBytes();
            int progress = target > 0 ? (int) Math.min(1000L, downloaded * 1000L / target) : 0;
            bufferSpinner.setVisibility(View.GONE);
            bufferProgress.setVisibility(View.VISIBLE);
            bufferProgress.setProgress(progress);
            bufferingText.setText("Подготовка серии\n" + Util.humanSize(downloaded)
                    + " из " + Util.humanSize(target));
            buffering.setVisibility(View.VISIBLE);
            return;
        }
        if (sourceMode == SOURCE_CACHE_LOCAL && !cache.isComplete() && total > 0) {
            int percent = (int) Math.min(100L, downloaded * 100L / total);
            cacheStatus.setText("↓ " + percent + "%" + (cache.isWaitingForData() ? " · ожидание" : ""));
            cacheStatus.setVisibility(View.VISIBLE);
        } else {
            cacheStatus.setVisibility(View.GONE);
        }
        if (sourceMode == SOURCE_CACHE_LOCAL && cache.isWaitingForData() && started) {
            showCacheWaiting();
        }
    }

    private void hideBuffering() {
        buffering.setVisibility(View.GONE);
        bufferProgress.setVisibility(View.GONE);
        retryBtn.setVisibility(View.GONE);
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        titleBar.setVisibility(View.VISIBLE);
        controlsVisible = true;
        ui.removeCallbacks(hideRunnable);
        ui.postDelayed(hideRunnable, AUTO_HIDE_MS);
    }

    private void hideControls() {
        controls.setVisibility(View.GONE);
        titleBar.setVisibility(View.GONE);
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
        long reportedDuration = player.getLength();
        if (reportedDuration > 0) duration = reportedDuration;
        if (duration > 0 && Math.abs(duration - lastKnownDuration) >= 1000L) {
            lastKnownDuration = duration;
            Store.setDuration(this, path, duration);
        }
        long reportedTime = player.getTime();
        // В момент естественного окончания libVLC иногда сначала возвращает 0,
        // а затем присылает EndReached. Не затираем последнюю реальную позицию.
        if (reportedTime > 0 || currentMs <= 0 || !started) currentMs = Math.max(0L, reportedTime);
        if (sourceMode == SOURCE_CACHE_LOCAL && !localPlaybackEstablished
                && reportedTime >= 1000L) {
            localPlaybackEstablished = true;
            PlaybackCache cache = playbackCache;
            if (cache != null) cache.markPlaybackEstablished();
            ui.removeCallbacks(localStartTimeout);
        }
        time.setText(Util.fmtTime(currentMs) + " / " + Util.fmtTime(duration));
        if (duration > 0 && !dragging) seek.setProgress((int) (currentMs * 1000 / duration));
        if (player.isPlaying()) saveCurrentPosition(false);
        if (sourceMode == SOURCE_CACHE_LOCAL && playbackCache != null) {
            updateCacheUi();
            if (!playbackCache.isWaitingForData() && player.isPlaying()) {
                waitingSeekMs = 0;
                hideBuffering();
            }
        }
        updatePlaybackState();
    }

    private void saveCurrentPosition(boolean force) {
        if (player == null || path == null || path.isEmpty() || currentCompleted) return;
        long pos = player.getTime();
        if (pos <= 0) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastPositionSaveAt < POSITION_SAVE_INTERVAL_MS) return;
        if (!force && lastSavedPosition >= 0 && Math.abs(pos - lastSavedPosition) < 2000) return;
        // Не возвращаем почти конечную позицию, даже если событие EndReached задержалось.
        long len = player.getLength();
        if (len > 0 && pos >= len - 8000) {
            Store.clearPos(this, path);
            return;
        }
        Store.setPos(this, path, pos);
        lastPositionSaveAt = now;
        lastSavedPosition = pos;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) applyImmersive();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && viewsDetached) {
            try {
                player.attachViews(videoLayout, null, false, false);
                viewsDetached = false;
                ui.postDelayed(this::applyAspect, 180L);
            } catch (Throwable ignored) {
            }
        }
        ui.post(ticker);
        applyImmersive();
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(ticker);
        saveCurrentPosition(true);
        if (player != null) {
            if (player.isPlaying()) setPlaying(false);
            try {
                player.detachViews();
                viewsDetached = true;
            } catch (Throwable ignored) {
            }
        }
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ui.postDelayed(this::applyAspect, 180L);
    }

    @Override
    public void onBackPressed() {
        saveCurrentPosition(true);
        if (player != null) stopPlayerForTransition();
        stopPlaybackCache();
        super.onBackPressed();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        suppressTerminalEvents = true;
        ui.removeCallbacksAndMessages(null);
        if (session != null) session.release();
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            if (!viewsDetached) {
                try { player.detachViews(); } catch (Throwable ignored) {}
            }
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        // Сначала libVLC окончательно закрывает локальный HTTP-поток, только после
        // этого останавливаем кэш и удаляем его уникальный временный файл.
        stopPlaybackCache();
        if (libVLC != null) {
            try { libVLC.release(); } catch (Throwable ignored) {}
            libVLC = null;
        }
        super.onDestroy();
    }
}
