package com.tabletplayer;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {
    private static final String[] CONTENT_LOAD_LABELS = {"Авто", "Локальный кэш", "Прямой поток"};
    private static final String[] DECODER_LABELS = {"Автоматически", "Аппаратный", "Программный"};
    private static final String[] SKIP_LOOP_LABELS = {"Выкл", "nonref", "bidir", "all"};
    private static final String[] SKIP_LOOP_VALUES = {"off", "nonref", "bidir", "all"};

    private EditText networkCaching;
    private EditText fileCaching;
    private EditText localCaching;
    private EditText playbackCacheThreads;
    private EditText playbackPrefetchThreads;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.settings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        View root = findViewById(R.id.settings_root);
        if (root != null) root.requestFocus();

        SwitchCompat themeSwitch = findViewById(R.id.theme_switch);
        themeSwitch.setChecked(App.isDark(this));
        themeSwitch.setOnCheckedChangeListener((btn, checked) -> {
            saveCachingFields();
            App.setDark(this, checked);
            recreate();
        });

        Spinner contentLoadSpinner = findViewById(R.id.content_load_mode_spinner);
        ArrayAdapter<String> contentLoadAdapter = labelAdapter(CONTENT_LOAD_LABELS);
        contentLoadSpinner.setAdapter(contentLoadAdapter);
        contentLoadSpinner.setSelection(Store.getContentLoadMode(this));
        contentLoadSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> Store.setContentLoadMode(this, position)));

        Spinner decoderSpinner = findViewById(R.id.decoder_spinner);
        ArrayAdapter<String> decoderAdapter = labelAdapter(DECODER_LABELS);
        decoderSpinner.setAdapter(decoderAdapter);
        decoderSpinner.setSelection(Math.max(0, Math.min(2, Store.getDecoderMode(this, 0))));
        decoderSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> Store.setDecoderMode(this, Math.max(0, Math.min(2, position)))));

        SwitchCompat catchUpSwitch = findViewById(R.id.catch_up_switch);
        catchUpSwitch.setChecked(Store.getLibVlcCatchUpFrames(this));
        catchUpSwitch.setOnCheckedChangeListener((btn, checked) -> Store.setLibVlcCatchUpFrames(this, checked));

        SwitchCompat fastSwitch = findViewById(R.id.avcodec_fast_switch);
        fastSwitch.setChecked(Store.getLibVlcAvcodecFast(this));
        fastSwitch.setOnCheckedChangeListener((btn, checked) -> Store.setLibVlcAvcodecFast(this, checked));

        Spinner skipLoopSpinner = findViewById(R.id.skip_loop_spinner);
        ArrayAdapter<String> skipAdapter = labelAdapter(SKIP_LOOP_LABELS);
        skipLoopSpinner.setAdapter(skipAdapter);
        skipLoopSpinner.setSelection(skipIndex(Store.getLibVlcSkipLoopFilter(this)));
        skipLoopSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            int idx = Math.max(0, Math.min(SKIP_LOOP_VALUES.length - 1, position));
            Store.setLibVlcSkipLoopFilter(this, SKIP_LOOP_VALUES[idx]);
        }));

        networkCaching = findViewById(R.id.network_caching);
        fileCaching = findViewById(R.id.file_caching);
        localCaching = findViewById(R.id.local_caching);
        playbackCacheThreads = findViewById(R.id.playback_cache_threads);
        playbackPrefetchThreads = findViewById(R.id.playback_prefetch_threads);
        setupCachingField(networkCaching, Store.getLibVlcNetworkCaching(this));
        setupCachingField(fileCaching, Store.getLibVlcFileCaching(this));
        setupCachingField(localCaching, Store.getLibVlcLocalCaching(this));
        setupNumberField(playbackCacheThreads, Store.getPlaybackCacheThreads(this));
        setupNumberField(playbackPrefetchThreads, Store.getPlaybackPrefetchThreads(this));

        Button reset = findViewById(R.id.reset_libvlc_btn);
        reset.setOnClickListener(v -> {
            Store.resetLibVlcSettings(this);
            contentLoadSpinner.setSelection(Store.CONTENT_LOAD_AUTO);
            decoderSpinner.setSelection(0);
            catchUpSwitch.setChecked(true);
            fastSwitch.setChecked(false);
            skipLoopSpinner.setSelection(0);
            setCachingText(networkCaching, Store.LIBVLC_DEFAULT_CACHING_MS);
            setCachingText(fileCaching, Store.LIBVLC_DEFAULT_CACHING_MS);
            setCachingText(localCaching, Store.LIBVLC_DEFAULT_CACHING_MS);
            setNumberText(playbackCacheThreads, Store.PLAYBACK_CACHE_DEFAULT_THREADS);
            setNumberText(playbackPrefetchThreads, Store.PLAYBACK_PREFETCH_DEFAULT_THREADS);
            if (root != null) root.requestFocus();
        });

        if (root != null) root.requestFocus();
    }


    private ArrayAdapter<String> labelAdapter(String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(getResources().getColor(R.color.text_primary));
                }
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void setupCachingField(EditText field, int value) {
        setupNumberField(field, value);
    }

    private void setupNumberField(EditText field, int value) {
        if (field == null) return;
        setNumberText(field, value);
        field.setSelectAllOnFocus(true);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                saveCachingFields();
                return false;
            }
            return false;
        });
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveCachingFields();
        });
    }

    private void setCachingText(EditText field, int value) {
        if (field != null) field.setText(String.valueOf(Store.clampCaching(value)));
    }

    private void setNumberText(EditText field, int value) {
        if (field != null) field.setText(String.valueOf(value));
    }

    private void saveCachingFields() {
        if (networkCaching == null || fileCaching == null || localCaching == null) return;
        int network = readCaching(networkCaching, Store.getLibVlcNetworkCaching(this));
        int file = readCaching(fileCaching, Store.getLibVlcFileCaching(this));
        int local = readCaching(localCaching, Store.getLibVlcLocalCaching(this));
        Store.setLibVlcNetworkCaching(this, network);
        Store.setLibVlcFileCaching(this, file);
        Store.setLibVlcLocalCaching(this, local);
        if (playbackCacheThreads != null) {
            int workers = readPlainNumber(playbackCacheThreads, Store.getPlaybackCacheThreads(this));
            Store.setPlaybackCacheThreads(this, workers);
        }
        if (playbackPrefetchThreads != null) {
            int workers = readPlainNumber(playbackPrefetchThreads, Store.getPlaybackPrefetchThreads(this));
            Store.setPlaybackPrefetchThreads(this, workers);
        }
        setCachingText(networkCaching, Store.getLibVlcNetworkCaching(this));
        setCachingText(fileCaching, Store.getLibVlcFileCaching(this));
        setCachingText(localCaching, Store.getLibVlcLocalCaching(this));
        setNumberText(playbackCacheThreads, Store.getPlaybackCacheThreads(this));
        setNumberText(playbackPrefetchThreads, Store.getPlaybackPrefetchThreads(this));
    }

    private int readCaching(EditText field, int fallback) {
        return Store.clampCaching(readPlainNumber(field, fallback));
    }

    private int readPlainNumber(EditText field, int fallback) {
        try {
            String s = field.getText() == null ? "" : field.getText().toString().trim();
            if (s.length() == 0) return fallback;
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private int skipIndex(String value) {
        for (int i = 0; i < SKIP_LOOP_VALUES.length; i++) {
            if (SKIP_LOOP_VALUES[i].equals(value)) return i;
        }
        return 0;
    }

    @Override
    protected void onPause() {
        saveCachingFields();
        super.onPause();
    }

    @Override
    public boolean onSupportNavigateUp() {
        saveCachingFields();
        finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        return true;
    }

    private interface SelectionHandler {
        void onSelected(int position);
    }

    private static final class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final SelectionHandler handler;

        SimpleItemSelectedListener(SelectionHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            if (handler != null) handler.onSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {
        }
    }
}
