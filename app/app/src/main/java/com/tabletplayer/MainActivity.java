package com.tabletplayer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "tablet_player";
    private static final String KEY_IP = "ip";
    private static final String KEY_PORT = "port";
    private static final String KEY_HISTORY = "history";
    private static final int HISTORY_MAX = 8;

    private EditText ipField;
    private EditText portField;
    private ListView historyList;
    private TextView historyEmpty;
    private ArrayAdapter<String> historyAdapter;
    private final ArrayList<String> history = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipField = (EditText) findViewById(R.id.ip);
        portField = (EditText) findViewById(R.id.port);
        historyList = (ListView) findViewById(R.id.history);
        historyEmpty = (TextView) findViewById(R.id.history_empty);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String lastIp = prefs.getString(KEY_IP, "");
        String lastPort = prefs.getString(KEY_PORT, "10930");
        if (!TextUtils.isEmpty(lastIp)) ipField.setText(lastIp);
        portField.setText(lastPort);

        historyAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, history);
        historyList.setAdapter(historyAdapter);
        historyList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String entry = history.get(position);
                int idx = entry.lastIndexOf(':');
                if (idx > 0) {
                    ipField.setText(entry.substring(0, idx));
                    portField.setText(entry.substring(idx + 1));
                    connect();
                }
            }
        });

        Button connect = (Button) findViewById(R.id.connect);
        connect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { connect(); }
        });

        loadHistory();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void connect() {
        String ip = ipField.getText().toString().trim();
        String port = portField.getText().toString().trim();
        if (TextUtils.isEmpty(ip)) {
            Toast.makeText(this, "Введите IP-адрес", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(port)) port = "10930";

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString(KEY_IP, ip).putString(KEY_PORT, port).apply();
        addHistory(ip + ":" + port);

        String base = "http://" + ip + ":" + port;
        Intent intent = new Intent(this, BrowseActivity.class);
        intent.putExtra("base", base);
        intent.putExtra("path", "");
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void loadHistory() {
        history.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                history.add(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
        historyAdapter.notifyDataSetChanged();
        updateHistoryEmpty();
    }

    private void addHistory(String entry) {
        history.remove(entry);
        history.add(0, entry);
        while (history.size() > HISTORY_MAX) {
            history.remove(history.size() - 1);
        }
        JSONArray arr = new JSONArray();
        for (String s : history) arr.put(s);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, arr.toString())
                .apply();
        historyAdapter.notifyDataSetChanged();
        updateHistoryEmpty();
    }

    private void updateHistoryEmpty() {
        historyEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
