package com.tabletplayer;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * Видеоплеер на базе libVLC. Играет файл напрямую по HTTP-адресу сервера
 * (с поддержкой перемотки через HTTP Range). Контролы: пауза/воспроизведение,
 * перемотка ±10с, ползунок прогресса, переключение скорости.
 */
public class PlayerActivity extends Activity {
    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
    private SeekBar seek;
    private TextView time;
    private Button playPause;
    private boolean dragging = false;

    private final float[] speeds = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f};
    private int speedIdx = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        videoLayout = (VLCVideoLayout) findViewById(R.id.video_layout);
        seek = (SeekBar) findViewById(R.id.seek);
        time = (TextView) findViewById(R.id.time);
        playPause = (Button) findViewById(R.id.play_pause);

        ArrayList<String> options = new ArrayList<String>();
        options.add("--network-caching=1500");
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        libVLC = new LibVLC(this, options);
        player = new MediaPlayer(libVLC);
        player.attachViews(videoLayout, null, false, false);

        String url = getIntent().getStringExtra("url");
        String title = getIntent().getStringExtra("title");
        if (title != null) setTitle(title);

        Media media = new Media(libVLC, Uri.parse(url));
        player.setMedia(media);
        media.release();

        player.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.TimeChanged:
                        if (!dragging) updateProgress();
                        break;
                    case MediaPlayer.Event.LengthChanged:
                        seek.setMax((int) player.getLength());
                        break;
                    case MediaPlayer.Event.EndReached:
                        playPause.setText("▶");
                        break;
                    default:
                        break;
                }
            }
        });

        playPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player.isPlaying()) {
                    player.pause();
                    playPause.setText("▶");
                } else {
                    player.play();
                    playPause.setText("⏸");
                }
            }
        });

        findViewById(R.id.rew).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.setTime(Math.max(0, player.getTime() - 10000));
            }
        });

        findViewById(R.id.fwd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                player.setTime(player.getTime() + 10000);
            }
        });

        final Button speedBtn = (Button) findViewById(R.id.speed);
        speedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speedIdx = (speedIdx + 1) % speeds.length;
                player.setRate(speeds[speedIdx]);
                speedBtn.setText(speeds[speedIdx] + "x");
                Toast.makeText(PlayerActivity.this, "Скорость: " + speeds[speedIdx] + "x", Toast.LENGTH_SHORT).show();
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) time.setText(fmt(progress) + " / " + fmt(player.getLength()));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                player.setTime(s.getProgress());
                dragging = false;
            }
        });

        player.play();
        playPause.setText("⏸");
    }

    private void updateProgress() {
        long t = player.getTime();
        long len = player.getLength();
        if (len > 0) seek.setMax((int) len);
        seek.setProgress((int) t);
        time.setText(fmt(t) + " / " + fmt(len));
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        s %= 60;
        m %= 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) {
            player.pause();
            playPause.setText("▶");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
