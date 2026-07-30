package com.tabletplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.GestureDetector;
import android.view.KeyEvent;
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
    private static final int LOCAL_CACHING = 15000;
    private static final long POSITION_SAVE_INTERVAL_MS = 10000;
    private static final long CACHE_PREPARE_TIMEOUT_MS = 30000;
    private static final long CACHE_EARLY_ESTIMATE_MS = 8000;
    private static final long CACHE_LOCAL_START_CHECK_MS = 10000L;
    private static final long CACHE_LOCAL_START_HARD_TIMEOUT_MS = 45000L;
    private static final long DIRECT_RESUME_LIMIT_MS = 10L * 60L * 1000L;
    private static final float TOP_GESTURE_DEAD_ZONE = 0.20f;

    private static final int SOURCE_DIRECT = 0;
    private static final int SOURCE_CACHE_PREPARING = 1;
    private static final int SOURCE_CACHE_LOCAL = 2;

    private static final int STATE_IDLE = 0;
    private static final int STATE_PREPARING = 1;
    private static final int STATE_STARTING = 2;
    private static final int STATE_PLAYING = 3;
    private static final int STATE_SWITCHING = 4;
    private static final int STATE_STOPPING = 5;
    private static final int STATE_DESTROYED = 6;

    private String base, path, name, folder, serverName;
    private boolean local = false;

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private View controls, gestureOverlay, buffering;
    private TextView time, gestureInfo, bufferingText, titleBar, cacheStatus, technicalCard;
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
    private boolean localPlaybackEstablished = false;
    private boolean localStartObserved = false;
    private long localStartWatchStartedMs = 0L;
    private long localStartWatchBytes = 0L;
    private boolean playingFromCompletedFile = false;
    private boolean switchedToCompletedFile = false;
    private boolean fallbackInProgress = false;
    private int playbackState = STATE_IDLE;
    private int mediaGeneration = 0;
    private int currentVoutCount = 0;
    private boolean forceSoftwareDecoder = false;
    private boolean softwareRetryUsed = false;
    private Uri currentMediaUri;
    private boolean currentMediaNetwork = false;
    private boolean currentMediaLocalSource = false;
    private long zeroVoutSinceMs = 0L;
    private long lastVoutProgressMs = 0L;
    private boolean surfaceRecoveryPending = false;
    private QueuePrefetcher queuePrefetcher;
    private boolean prefetchStartedForCurrent = false;
    private boolean cacheSessionReleased = false;
    private final Set<String> directOnlyThisSession = new HashSet<>();
    private boolean audioRouteReceiverRegistered = false;
    private boolean wiredHeadsetConnected = false;
    private long lastTechnicalCardUpdateMs = 0L;

    /**
     * ACTION_AUDIO_BECOMING_NOISY отправляется системой перед переводом звука
     * с Bluetooth/проводных наушников на динамик. Это основной и наиболее
     * совместимый с Android 4.4 сигнал, не требующий Bluetooth-разрешений.
     */
    private final BroadcastReceiver audioRouteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action)) {
                pauseBecauseHeadphonesDisconnected("Наушники отключены");
                return;
            }
            if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
                int state = intent.getIntExtra("state", -1);
                if (state == 1) {
                    wiredHeadsetConnected = true;
                } else if (state == 0) {
                    boolean wasConnected = wiredHeadsetConnected;
                    wiredHeadsetConnected = false;
                    if (wasConnected) pauseBecauseHeadphonesDisconnected("Проводные наушники отключены");
                }
            }
        }
    };

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
    // Команда пользователя хранится отдельно от player.isPlaying(): libVLC меняет
    // фактическое состояние асинхронно, из-за чего MediaSession раньше оставалась
    // в STATE_PLAYING после паузы и второе нажатие гарнитуры снова посылало Pause.
    private boolean playbackRequested = false;
    private int lastSessionState = Integer.MIN_VALUE;
    private long lastSessionPositionSecond = Long.MIN_VALUE;
    private int lastSessionSpeedBits = Integer.MIN_VALUE;
    private long lastDisplayedTimeSecond = Long.MIN_VALUE;
    private long lastDisplayedDurationSecond = Long.MIN_VALUE;
    private final AudioManager.OnAudioFocusChangeListener focusListener = focus -> {
        if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (playbackRequested || (player != null && player.isPlaying())) setPlaying(false);
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
            if (sourceMode != SOURCE_CACHE_LOCAL || localPlaybackEstablished
                    || destroyed || fallbackInProgress) return;
            PlaybackCache cache = playbackCache;
            long now = System.currentTimeMillis();
            long downloaded = cache == null ? 0L : cache.getDownloadedBytes();
            boolean downloadProgressed = downloaded > localStartWatchBytes;
            boolean sourceActive = localStartObserved || currentVoutCount > 0
                    || (cache != null && cache.isWaitingForData());
            localStartWatchBytes = downloaded;

            // Тяжёлые контейнеры и старые аппаратные декодеры могут открываться
            // значительно дольше 10 секунд. Пока источник или загрузка реально
            // продвигаются, не освобождаем нативный MediaPlayer посреди запуска.
            if (cache != null && !cache.isFailed()
                    && now - localStartWatchStartedMs < CACHE_LOCAL_START_HARD_TIMEOUT_MS
                    && (downloadProgressed || sourceActive)) {
                ui.postDelayed(this, CACHE_LOCAL_START_CHECK_MS);
                return;
            }
            fallbackToDirect("Локальный кэш не смог начать воспроизведение");
        }
    };
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        CacheFiles.acquireSession(this);

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
            queuePrefetcher = new QueuePrefetcher(this, (prefetchPath, downloaded, total, complete) -> {
                if (!destroyed) ui.post(this::updateCacheUi);
            });
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
        aspect.setOnLongClickListener(v -> {
            if (currentMediaUri == null || player == null || sourceMode == SOURCE_CACHE_PREPARING) return false;
            forceSoftwareDecoder = !forceSoftwareDecoder;
            softwareRetryUsed = forceSoftwareDecoder;
            restartCurrentDecoder(forceSoftwareDecoder
                    ? "Программный декодер" : "Аппаратный декодер");
            return true;
        });
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
        options.add("--file-caching=" + LOCAL_CACHING);

        // Не выбрасываем опоздавшие видеокадры. Эти два параметра
        // поддерживаются libVLC 3.x и не требуют модульных значений.
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");

        try {
            libVLC = new LibVLC(this, options);
            PlaybackDiagnostics.log(this, "libvlc init frame-drop disabled");
        } catch (RuntimeException | LinkageError error) {
            // Старые/урезанные Android-сборки libVLC могут отказаться от
            // необязательных параметров. Не роняем PlayerActivity: повторяем
            // инициализацию только с базовыми настройками кэша.
            PlaybackDiagnostics.log(this, "libvlc option fallback: " + error);
            ArrayList<String> fallback = new ArrayList<>();
            fallback.add("--network-caching=" + NET_CACHING);
            fallback.add("--file-caching=" + LOCAL_CACHING);
            libVLC = new LibVLC(this, fallback);
        }
    }

    private MediaPlayer createPlayer(final int generation) {
        MediaPlayer created = new MediaPlayer(libVLC);
        created.attachViews(videoLayout, null, false, false);
        viewsDetached = false;
        created.setEventListener(event -> {
            final int type = event.type;
            final float pct = type == MediaPlayer.Event.Buffering ? event.getBuffering() : 0f;
            final int vout = type == MediaPlayer.Event.Vout ? event.getVoutCount() : -1;
            ui.post(() -> handlePlayerEvent(generation, type, pct, vout));
        });
        return created;
    }

    private void handlePlayerEvent(int generation, int type, float pct, int vout) {
        if (destroyed || generation != mediaGeneration || player == null
                || sourceMode == SOURCE_CACHE_PREPARING) return;
        if (type == MediaPlayer.Event.Vout) {
            currentVoutCount = Math.max(0, vout);
            if (currentVoutCount > 0) {
                if (sourceMode == SOURCE_CACHE_LOCAL) localStartObserved = true;
                surfaceRecoveryPending = false;
                zeroVoutSinceMs = 0L;
                ui.postDelayed(this::applyAspect, 100L);
            }
            return;
        }
        switch (type) {
            case MediaPlayer.Event.Playing:
                // play() запускается асинхронно. Если пользователь успел нажать
                // Bluetooth-паузу во время подготовки, останавливаем только что
                // стартовавший источник вместо самопроизвольного продолжения.
                if (!playbackRequested) {
                    try { player.pause(); } catch (Throwable ignored) {}
                    updatePlayIcon();
                    updatePlaybackState();
                    break;
                }
                if (sourceMode == SOURCE_CACHE_LOCAL) localStartObserved = true;
                reconnectAttempts = 0;
                reconnecting = false;
                started = true;
                playbackState = STATE_PLAYING;
                fallbackInProgress = false;
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
                    if (pct > 0f) localStartObserved = true;
                    if (!started) {
                        showBuffering(playingFromCompletedFile
                                ? "Запуск локального файла…" : "Запуск из локального кэша…");
                    } else if (playbackCache != null
                            && (playbackCache.isWaitingForData() || waitingSeekMs > 0)) {
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
                PlaybackDiagnostics.log(this, "error gen=" + generation + " mode=" + sourceMode
                        + " file=" + playingFromCompletedFile + " vout=" + currentVoutCount);
                if ((playingFromCompletedFile || local) && !softwareRetryUsed) {
                    forceSoftwareDecoder = true;
                    softwareRetryUsed = true;
                    restartCurrentDecoder("Повтор с программным декодером");
                } else if (sourceMode == SOURCE_CACHE_LOCAL && !playingFromCompletedFile) {
                    fallbackToDirect("Формат не поддержал локальную дозагрузку");
                } else {
                    handleDrop();
                }
                break;
        }
    }

    private void setupSession() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        requestAudioFocusForPlayback();
        try {
            session = new MediaSessionCompat(this, "TabletPlayer");
            session.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSessionCompat.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    if (mediaButtonIntent == null) return false;
                    KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event == null) return false;

                    // Поглощаем и DOWN, и UP, но действие выполняем только один раз.
                    if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
                        return true;
                    }
                    switch (event.getKeyCode()) {
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            togglePlay();
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                            setPlaying(true);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                            setPlaying(false);
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_NEXT:
                            playNext();
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            playPrev();
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_STOP:
                            finish();
                            return true;
                        default:
                            return super.onMediaButtonEvent(mediaButtonIntent);
                    }
                }

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
            }, ui);
            session.setActive(true);
            updatePlaybackState();
        } catch (Throwable e) {
            session = null;
        }
    }

    private void requestAudioFocusForPlayback() {
        if (audioManager != null) {
            try {
                audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            } catch (Throwable ignored) {
            }
        }
    }

    private void registerAudioRouteReceiver() {
        if (audioRouteReceiverRegistered) return;
        try {
            if (audioManager != null) wiredHeadsetConnected = audioManager.isWiredHeadsetOn();
            IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            filter.addAction(Intent.ACTION_HEADSET_PLUG);
            registerReceiver(audioRouteReceiver, filter);
            audioRouteReceiverRegistered = true;
            PlaybackDiagnostics.log(this, "audio route receiver registered wired="
                    + wiredHeadsetConnected);
        } catch (Throwable e) {
            PlaybackDiagnostics.log(this, "audio route receiver register failed: " + e);
        }
    }

    private void unregisterAudioRouteReceiver() {
        if (!audioRouteReceiverRegistered) return;
        try {
            unregisterReceiver(audioRouteReceiver);
        } catch (Throwable ignored) {
        } finally {
            audioRouteReceiverRegistered = false;
        }
    }

    private void pauseBecauseHeadphonesDisconnected(String reason) {
        boolean actuallyPlaying = playbackRequested;
        if (!actuallyPlaying && player != null) {
            try { actuallyPlaying = player.isPlaying(); } catch (Throwable ignored) {}
        }
        if (destroyed || !actuallyPlaying) return;
        PlaybackDiagnostics.log(this, "audio route lost: " + reason);
        setPlaying(false);
        saveCurrentPosition(true);
        flashInfo(reason + "\nВоспроизведение приостановлено");
        showControls();
    }

    private void updatePlaybackState() {
        if (session == null) return;
        long pos = player != null ? Math.max(0L, player.getTime()) : Math.max(0L, currentMs);
        int state;
        float stateSpeed;
        if (sourceMode == SOURCE_CACHE_PREPARING || playbackState == STATE_PREPARING
                || playbackState == STATE_STARTING) {
            state = PlaybackStateCompat.STATE_BUFFERING;
            stateSpeed = 0f;
        } else if (playbackRequested) {
            state = PlaybackStateCompat.STATE_PLAYING;
            stateSpeed = speeds[speedIdx];
        } else {
            state = PlaybackStateCompat.STATE_PAUSED;
            stateSpeed = 0f;
        }
        long positionSecond = pos / 1000L;
        int speedBits = Float.floatToIntBits(stateSpeed);
        if (state == lastSessionState && positionSecond == lastSessionPositionSecond
                && speedBits == lastSessionSpeedBits) return;
        lastSessionState = state;
        lastSessionPositionSecond = positionSecond;
        lastSessionSpeedBits = speedBits;
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(state, pos, stateSpeed)
                .build();
        session.setPlaybackState(playbackState);
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

    private Media buildMedia(Uri uri, boolean network, boolean localSource) {
        Media media = new Media(libVLC, uri);
        media.setHWDecoderEnabled(!forceSoftwareDecoder, false);
        int caching = localSource ? LOCAL_CACHING : NET_CACHING;
        media.addOption((network ? ":network-caching=" : ":file-caching=") + caching);
        media.addOption(":live-caching=" + caching);
        return media;
    }

    private void playPath(String p, String nm, long resume) {
        ui.removeCallbacks(cacheDecision);
        ui.removeCallbacks(localStartTimeout);
        fallbackInProgress = false;
        playbackState = STATE_SWITCHING;
        String previousPath = path;
        if (queuePrefetcher != null) queuePrefetcher.pauseFor(p);
        releasePlayerForTransition();
        stopPlaybackCache(!hasQueue);
        if (hasQueue && previousPath != null && !previousPath.equals(p)) {
            CacheFiles.delete(this, base, previousPath);
        }
        path = p;
        name = nm;
        PlaybackDiagnostics.log(this, "play path=" + p + " resume=" + resume + " queue=" + hasQueue);
        pendingResumeMs = resume;
        currentMs = resume;
        duration = 0;
        currentCompleted = false;
        lastPositionSaveAt = 0;
        lastSavedPosition = resume;
        lastKnownDuration = Store.getDuration(this, p);
        started = false;
        localPlaybackEstablished = false;
        playingFromCompletedFile = false;
        switchedToCompletedFile = false;
        prefetchStartedForCurrent = false;
        forceSoftwareDecoder = false;
        softwareRetryUsed = false;
        currentVoutCount = 0;
        zeroVoutSinceMs = 0L;
        lastVoutProgressMs = resume;
        waitingSeekMs = 0;
        if (!local) updateEpisodeIndex();
        setTitle(nm);
        titleBar.setText(nm);
        cacheStatus.setVisibility(View.GONE);
        seek.setSecondaryProgress(0);
        showControls();

        if (local) {
            sourceMode = SOURCE_DIRECT;
            startMedia(Uri.fromFile(new File(p)), resume, false, false, "Подготовка…");
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

    private void startMedia(Uri uri, long resume, boolean network, boolean localSource, String message) {
        if (destroyed || libVLC == null) return;
        releasePlayerForTransition();
        final int generation = ++mediaGeneration;
        player = createPlayer(generation);
        currentMediaUri = uri;
        currentMediaNetwork = network;
        currentMediaLocalSource = localSource;
        if (sourceMode == SOURCE_CACHE_LOCAL) {
            localStartObserved = false;
            localStartWatchStartedMs = System.currentTimeMillis();
            PlaybackCache cache = playbackCache;
            localStartWatchBytes = cache == null ? 0L : cache.getDownloadedBytes();
        }
        pendingResumeMs = resume;
        currentMs = resume;
        lastDisplayedTimeSecond = Long.MIN_VALUE;
        lastDisplayedDurationSecond = Long.MIN_VALUE;
        lastSessionState = Integer.MIN_VALUE;
        lastSessionPositionSecond = Long.MIN_VALUE;
        lastSessionSpeedBits = Integer.MIN_VALUE;
        started = false;
        playbackState = STATE_STARTING;
        if (message != null && !message.isEmpty()) showBuffering(message);
        Media media = buildMedia(uri, network, localSource);
        player.setMedia(media);
        media.release();
        playbackRequested = true;
        requestAudioFocusForPlayback();
        player.play();
        player.setRate(speeds[speedIdx]);
        updatePlayIcon();
        updatePlaybackState();
        PlaybackDiagnostics.log(this, "start gen=" + generation + " mode=" + sourceMode
                + " sw=" + forceSoftwareDecoder + " source="
                + (localSource ? "cache" : (network ? "network" : "file")));
    }

    private void startDirectPlayback(long resume, String message) {
        ui.removeCallbacks(cacheDecision);
        ui.removeCallbacks(localStartTimeout);
        stopPlaybackCache(!hasQueue);
        sourceMode = SOURCE_DIRECT;
        playingFromCompletedFile = false;
        localStartObserved = false;
        cacheStatus.setVisibility(View.GONE);
        seek.setSecondaryProgress(0);
        startMedia(Uri.parse(streamUrl(path)), resume, true, false, message);
    }

    private void startCachePreparation(long resume) {
        sourceMode = SOURCE_CACHE_PREPARING;
        playbackState = STATE_PREPARING;
        cacheRequestedResumeMs = resume;
        showCachePreparation();
        try {
            playbackCache = new PlaybackCache(this, () -> {
                if (!destroyed) ui.post(this::updateCacheUi);
            });
            Long knownSize = knownFileSizes.get(path);
            playbackCache.start(base, path, knownSize == null ? -1L : knownSize, lastKnownDuration);
            ui.post(cacheDecision);
        } catch (Throwable e) {
            fallbackToDirect("Локальный кэш недоступен");
        }
    }

    private void evaluateCachePreparation() {
        if (sourceMode != SOURCE_CACHE_PREPARING || playbackCache == null || fallbackInProgress) return;
        updateCacheUi();
        if (playbackCache.isFailed()) {
            fallbackToDirect(playbackCache.getError());
            return;
        }
        long elapsed = System.currentTimeMillis() - playbackCache.getStartedAtMs();
        boolean timedOut = elapsed >= CACHE_PREPARE_TIMEOUT_MS;
        if (playbackCache.isReadyToPlay(lastKnownDuration, speeds[speedIdx], timedOut)) {
            startLocalCachePlayback();
            return;
        }
        if (timedOut) {
            fallbackToDirect("Скорости недостаточно для устойчивого просмотра");
            return;
        }
        if (elapsed >= CACHE_EARLY_ESTIMATE_MS
                && playbackCache.shouldFallbackEarly(lastKnownDuration, speeds[speedIdx])) {
            fallbackToDirect("Скорости недостаточно для быстрой подготовки");
            return;
        }
        ui.postDelayed(cacheDecision, 300L);
    }

    private void startLocalCachePlayback() {
        PlaybackCache cache = playbackCache;
        if (cache == null) {
            fallbackToDirect("Локальный кэш недоступен");
            return;
        }
        sourceMode = SOURCE_CACHE_LOCAL;
        localPlaybackEstablished = false;
        playingFromCompletedFile = cache.isComplete();
        localStartObserved = false;
        localStartWatchStartedMs = System.currentTimeMillis();
        localStartWatchBytes = cache.getDownloadedBytes();
        playbackState = STATE_STARTING;
        updateCacheUi();
        if (playingFromCompletedFile && cache.getCacheFile() != null) {
            startMedia(Uri.fromFile(cache.getCacheFile()), cacheRequestedResumeMs, false, true,
                    "Запуск локального файла…");
        } else {
            String localUrl = cache.localUrl();
            if (localUrl == null) {
                fallbackToDirect("Локальный кэш недоступен");
                return;
            }
            startMedia(Uri.parse(localUrl), cacheRequestedResumeMs, true, true,
                    "Запуск из локального кэша…");
        }
        ui.postDelayed(localStartTimeout, CACHE_LOCAL_START_CHECK_MS);
    }

    private void fallbackToDirect(String reason) {
        if (local || path == null || destroyed || fallbackInProgress
                || sourceMode == SOURCE_DIRECT || playbackState == STATE_DESTROYED) return;
        fallbackInProgress = true;
        playbackState = STATE_SWITCHING;
        long resume = waitingSeekMs > 0 ? waitingSeekMs
                : (player != null && player.getTime() > 0 ? player.getTime()
                : (pendingResumeMs > 0 ? pendingResumeMs : cacheRequestedResumeMs));
        directOnlyThisSession.add(path);
        waitingSeekMs = 0;
        PlaybackDiagnostics.log(this, "fallback path=" + path + " reason=" + reason + " at=" + resume);
        if (reason != null && !reason.isEmpty()) {
            Toast.makeText(this, reason + ". Переключение на сервер.", Toast.LENGTH_SHORT).show();
        }
        releasePlayerForTransition();
        startDirectPlayback(resume, "Запуск прямого воспроизведения…");
    }

    private void releasePlayerForTransition() {
        MediaPlayer old = player;
        player = null;
        mediaGeneration++;
        currentVoutCount = 0;
        if (old == null) return;
        try { old.setEventListener(null); } catch (Throwable ignored) {}
        try { old.stop(); } catch (Throwable ignored) {}
        try { old.detachViews(); } catch (Throwable ignored) {}
        try { old.release(); } catch (Throwable ignored) {}
        viewsDetached = false;
    }

    private void stopPlaybackCache(boolean deleteFile) {
        PlaybackCache cache = playbackCache;
        playbackCache = null;
        if (cache != null) {
            if (deleteFile) cache.cancelAndDelete();
            else cache.cancelKeepFile();
        }
        cacheStatus.setVisibility(View.GONE);
    }

    private void maybeStartQueuePrefetch() {
        if (!hasQueue || queuePrefetcher == null || prefetchStartedForCurrent
                || episodeIndex < 0 || episodePaths.isEmpty()) return;
        PlaybackCache cache = playbackCache;
        if (cache == null || !cache.isComplete()) return;
        prefetchStartedForCurrent = true;
        queuePrefetcher.updateWindow(base, episodePaths, knownFileSizes, episodeIndex);
    }

    private void switchToCompletedFileIfUseful() {
        PlaybackCache cache = playbackCache;
        if (cache == null || !cache.isComplete() || playingFromCompletedFile
                || switchedToCompletedFile || sourceMode != SOURCE_CACHE_LOCAL
                || !started || fallbackInProgress) return;
        if (duration > 0 && duration - currentMs < 30000L) return;
        File file = cache.getCacheFile();
        if (file == null || !file.exists()) return;
        switchedToCompletedFile = true;
        long resume = Math.max(currentMs, player != null ? player.getTime() : 0L);
        cache.stopServingKeepFile();
        playingFromCompletedFile = true;
        localPlaybackEstablished = false;
        localStartObserved = false;
        localStartWatchStartedMs = System.currentTimeMillis();
        localStartWatchBytes = cache.getDownloadedBytes();
        startMedia(Uri.fromFile(file), resume, false, true, "Переход на локальный файл…");
        ui.postDelayed(localStartTimeout, CACHE_LOCAL_START_CHECK_MS);
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
        playbackRequested = false;
        updatePlayIcon();
        updatePlaybackState();
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
        startMedia(Uri.parse(streamUrl(path)), ms, true, false, null);
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
        if (destroyed) return;
        playbackRequested = play;
        PlaybackDiagnostics.log(this, "transport requested=" + (play ? "play" : "pause")
                + " state=" + playbackState + " mode=" + sourceMode);
        MediaPlayer current = player;
        if (current != null) {
            try {
                if (play) {
                    requestAudioFocusForPlayback();
                    // play() безопасно возобновляет paused MediaPlayer. Не проверяем
                    // isPlaying(), так как после Bluetooth-паузы это значение может
                    // ещё несколько мгновений оставаться устаревшим.
                    current.play();
                    current.setRate(speeds[speedIdx]);
                } else if (current.isPlaying()) {
                    current.pause();
                }
            } catch (Throwable e) {
                PlaybackDiagnostics.log(this, "transport " + (play ? "play" : "pause")
                        + " failed: " + e);
            }
        }
        updatePlayIcon();
        updatePlaybackState();
    }

    private void togglePlay() {
        if (sourceMode == SOURCE_CACHE_PREPARING) {
            flashInfo("Подготовка серии…");
            return;
        }
        if (player != null) setPlaying(!playbackRequested);
    }

    private void updatePlayIcon() {
        playPause.setImageResource(player != null && playbackRequested
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
                && duration > 0 && playbackCache.getTotalBytes() > 0
                && !playingFromCompletedFile) {
            long wantedByte = playbackCache.getTotalBytes() * target / duration;
            playbackCache.requestUserSeekByte(wantedByte);
            if (wantedByte > playbackCache.getDownloadedBytes()) {
                waitingSeekMs = target;
                showCacheWaiting();
            }
        }
        player.setTime(target);
    }

    private void restartCurrentDecoder(String message) {
        if (currentMediaUri == null || destroyed) return;
        long resume = currentMs;
        try {
            if (player != null && player.getTime() > 0) resume = player.getTime();
        } catch (Throwable ignored) {}
        PlaybackDiagnostics.log(this, "decoder restart sw=" + forceSoftwareDecoder + " at=" + resume);
        flashInfo(message);
        startMedia(currentMediaUri, resume, currentMediaNetwork, currentMediaLocalSource, message + "…");
        if (sourceMode == SOURCE_CACHE_LOCAL) ui.postDelayed(localStartTimeout, CACHE_LOCAL_START_CHECK_MS);
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.length;
        if (player != null) player.setRate(speeds[speedIdx]);
        if (playbackCache != null) playbackCache.updatePlaybackInfo(duration, speeds[speedIdx], currentMs);
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
                        if (player == null || sourceMode == SOURCE_CACHE_PREPARING) return false;
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
            if (sourceMode != SOURCE_CACHE_LOCAL) seek.setSecondaryProgress(0);
            return;
        }
        if (cache.isFailed() && sourceMode == SOURCE_CACHE_LOCAL && !playingFromCompletedFile) {
            fallbackToDirect(cache.getError());
            return;
        }
        long downloaded = cache.getDownloadedBytes();
        long total = cache.getTotalBytes();
        if (total > 0) {
            seek.setSecondaryProgress((int) Math.min(1000L, downloaded * 1000L / total));
        }
        if (sourceMode == SOURCE_CACHE_PREPARING) {
            long target = cache.getPrepareTargetBytes();
            int progress = target > 0 ? (int) Math.min(1000L, downloaded * 1000L / target) : 0;
            bufferSpinner.setVisibility(View.GONE);
            bufferProgress.setVisibility(View.VISIBLE);
            bufferProgress.setProgress(progress);
            String speedText = cache.getBytesPerSecond() > 0
                    ? " · " + Util.humanSize(cache.getBytesPerSecond()) + "/с" : "";
            bufferingText.setText("Подготовка серии\n" + Util.humanSize(downloaded)
                    + " из " + Util.humanSize(target) + speedText);
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
        if (cache.isComplete()) {
            // Не перезапускаем MediaPlayer посреди серии. Локальный HTTP-источник
            // после 100% читает уже готовый файл без сети, а смена URI на лету
            // могла оставить звук активным при зависшем/потерянном видеовыходе.
            maybeStartQueuePrefetch();
        }
    }

    private void hideBuffering() {
        buffering.setVisibility(View.GONE);
        bufferProgress.setVisibility(View.GONE);
        retryBtn.setVisibility(View.GONE);
    }

    private void updateTechnicalCard(boolean force) {
        if (technicalCard == null || !controlsVisible) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastTechnicalCardUpdateMs < 1000L) return;
        lastTechnicalCardUpdateMs = now;

        StringBuilder text = new StringBuilder();
        PlaybackCache cache = playbackCache;
        if (sourceMode == SOURCE_CACHE_PREPARING) {
            text.append("Подготовка кэша");
        } else if (sourceMode == SOURCE_CACHE_LOCAL) {
            text.append(cache != null && cache.isComplete() ? "Локальный файл" : "Локальный кэш");
        } else if (local || !currentMediaNetwork) {
            text.append("Локальный файл");
        } else {
            text.append("Прямой поток");
        }

        if (cache != null && cache.getTotalBytes() > 0) {
            long downloaded = Math.max(0L, cache.getDownloadedBytes());
            long total = cache.getTotalBytes();
            long percent = Math.min(100L, downloaded * 100L / Math.max(1L, total));
            text.append("\n").append(Util.humanSize(downloaded)).append(" / ")
                    .append(Util.humanSize(total)).append(" · ").append(percent).append('%');
            long bps = cache.getBytesPerSecond();
            if (bps > 0 && !cache.isComplete()) {
                text.append("\n").append(Util.humanSize(bps)).append("/с");
            }
            if (duration > 0 && total > 0) {
                long downloadedTime = duration * Math.min(downloaded, total) / total;
                long ahead = Math.max(0L, downloadedTime - currentMs);
                if (ahead > 0 && !cache.isComplete()) {
                    float rate = Math.max(0.25f, speeds[speedIdx]);
                    long realAhead = (long) (ahead / rate);
                    text.append(" · запас ").append(Util.fmtCompactDuration(realAhead));
                } else if (cache.isComplete()) {
                    text.append("\nФайл загружен полностью");
                }
            }
        } else if (sourceMode == SOURCE_DIRECT && currentMediaNetwork) {
            text.append("\nБуфер libVLC · ").append(NET_CACHING / 1000).append(" с");
        }

        text.append("\n").append(forceSoftwareDecoder ? "SW" : "HW")
                .append(" · ").append(speeds[speedIdx]).append('x')
                .append(" · Vout ").append(currentVoutCount);
        text.append("\nСоединения приложения: ").append(TransferCoordinator.activeCount());
        technicalCard.setText(text.toString());
        technicalCard.setVisibility(View.VISIBLE);
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        titleBar.setVisibility(View.VISIBLE);
        controlsVisible = true;
        updateTechnicalCard(true);
        ui.removeCallbacks(hideRunnable);
        ui.postDelayed(hideRunnable, AUTO_HIDE_MS);
    }

    private void hideControls() {
        controls.setVisibility(View.GONE);
        titleBar.setVisibility(View.GONE);
        if (technicalCard != null) technicalCard.setVisibility(View.GONE);
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
        PlaybackCache activeCache = playbackCache;
        if (activeCache != null) activeCache.updatePlaybackInfo(duration, speeds[speedIdx], currentMs);
        if (sourceMode == SOURCE_CACHE_LOCAL && !localPlaybackEstablished
                && reportedTime >= 1000L) {
            localPlaybackEstablished = true;
            if (activeCache != null) activeCache.markPlaybackEstablished();
            ui.removeCallbacks(localStartTimeout);
        }
        boolean playingNow;
        try { playingNow = player.isPlaying(); } catch (Throwable ignored) { playingNow = false; }
        if (surfaceRecoveryPending && playingNow && reportedTime > lastVoutProgressMs + 500L) {
            long now = System.currentTimeMillis();
            if (currentVoutCount <= 0) {
                if (zeroVoutSinceMs == 0L) zeroVoutSinceMs = now;
                if (now - zeroVoutSinceMs >= 6000L && !softwareRetryUsed) {
                    forceSoftwareDecoder = true;
                    softwareRetryUsed = true;
                    surfaceRecoveryPending = false;
                    restartCurrentDecoder("Восстановление изображения");
                    return;
                }
            } else {
                surfaceRecoveryPending = false;
                zeroVoutSinceMs = 0L;
            }
            lastVoutProgressMs = reportedTime;
        }
        long timeSecond = currentMs / 1000L;
        long durationSecond = duration / 1000L;
        if (timeSecond != lastDisplayedTimeSecond || durationSecond != lastDisplayedDurationSecond) {
            lastDisplayedTimeSecond = timeSecond;
            lastDisplayedDurationSecond = durationSecond;
            time.setText(Util.fmtTime(currentMs) + " / " + Util.fmtTime(duration));
        }
        if (duration > 0 && !dragging) seek.setProgress((int) (currentMs * 1000 / duration));
        if (playingNow) saveCurrentPosition(false);
        if (sourceMode == SOURCE_CACHE_LOCAL && playbackCache != null) {
            updateCacheUi();
            if (!playbackCache.isWaitingForData() && playingNow) {
                waitingSeekMs = 0;
                hideBuffering();
            }
        }
        updateTechnicalCard(false);
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
        registerAudioRouteReceiver();
        if (player != null && viewsDetached) {
            try {
                player.attachViews(videoLayout, null, false, false);
                viewsDetached = false;
                surfaceRecoveryPending = true;
                zeroVoutSinceMs = 0L;
                lastVoutProgressMs = currentMs;
                ui.postDelayed(this::applyAspect, 180L);
            } catch (Throwable ignored) {
            }
        }
        ui.post(ticker);
        applyImmersive();
    }

    @Override
    protected void onStop() {
        unregisterAudioRouteReceiver();
        ui.removeCallbacks(ticker);
        saveCurrentPosition(true);
        if (player != null) {
            if (playbackRequested || player.isPlaying()) setPlaying(false);
            try {
                player.detachViews();
                viewsDetached = true;
                currentVoutCount = 0;
                zeroVoutSinceMs = 0L;
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
        playbackRequested = false;
        updatePlaybackState();
        playbackState = STATE_STOPPING;
        releasePlayerForTransition();
        stopPlaybackCache(true);
        if (queuePrefetcher != null) {
            queuePrefetcher.closeAndClear();
            queuePrefetcher = null;
        }
        super.onBackPressed();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        unregisterAudioRouteReceiver();
        destroyed = true;
        playbackState = STATE_DESTROYED;
        ui.removeCallbacksAndMessages(null);
        if (session != null) session.release();
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        playbackRequested = false;
        releasePlayerForTransition();
        stopPlaybackCache(true);
        if (queuePrefetcher != null) {
            queuePrefetcher.closeAndClear();
            queuePrefetcher = null;
        }
        if (libVLC != null) {
            try { libVLC.release(); } catch (Throwable ignored) {}
            libVLC = null;
        }
        if (!cacheSessionReleased) {
            cacheSessionReleased = true;
            CacheFiles.releaseSession(this);
        }
        super.onDestroy();
    }
}
