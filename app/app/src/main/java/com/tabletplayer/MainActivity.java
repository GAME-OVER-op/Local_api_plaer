package com.tabletplayer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Стартовый экран: спрашивает IP и порт устройства, где запущен media-server.
 * Значения сохраняются в SharedPreferences и подставляются при следующем запуске.
 */
public class MainActivity extends Activity {
    public static final String PREFS = "tabletplayer";
    public static final String KEY_IP = "ip";
    public static final String KEY_PORT = "port";

    private EditText ipEdit;
    private EditText portEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipEdit = (EditText) findViewById(R.id.ip);
        portEdit = (EditText) findViewById(R.id.port);
        Button connect = (Button) findViewById(R.id.connect);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ipEdit.setText(prefs.getString(KEY_IP, ""));
        portEdit.setText(prefs.getString(KEY_PORT, "10930"));

        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ip = ipEdit.getText().toString().trim();
                String port = portEdit.getText().toString().trim();
                if (TextUtils.isEmpty(ip)) {
                    Toast.makeText(MainActivity.this, "Введите IP устройства", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (TextUtils.isEmpty(port)) {
                    port = "10930";
                }

                SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                e.putString(KEY_IP, ip);
                e.putString(KEY_PORT, port);
                e.apply();

                Intent i = new Intent(MainActivity.this, BrowseActivity.class);
                i.putExtra("base", "http://" + ip + ":" + port);
                i.putExtra("path", "");
                startActivity(i);
            }
        });
    }
}
