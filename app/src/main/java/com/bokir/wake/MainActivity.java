package com.bokir.wake;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_AUDIO = 1001;
    private static final int SAMPLE_RATE = 16000;

    private TextView statusText;
    private EditText serverUrl;
    private EditText tokenInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        serverUrl = findViewById(R.id.serverUrl);
        tokenInput = findViewById(R.id.tokenInput);

        Button saveButton = findViewById(R.id.saveButton);
        Button testServerButton = findViewById(R.id.testServerButton);
        Button enrollButton = findViewById(R.id.enrollButton);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);

        SharedPreferences sp = getSharedPreferences("bokir", MODE_PRIVATE);
        serverUrl.setText(sp.getString("server_url", ""));
        tokenInput.setText(sp.getString("token", ""));

        saveButton.setOnClickListener(v -> saveConfig());
        testServerButton.setOnClickListener(v -> testServer());

        enrollButton.setOnClickListener(v -> {
            if (ensureMicPermission()) recordTemplate();
        });

        startButton.setOnClickListener(v -> {
            saveConfig();
            if (!hasTemplate()) {
                statusText.setText("Rekam Halo Bokir dulu.");
                return;
            }
            if (serverUrl.getText().toString().trim().isEmpty()) {
                statusText.setText("Isi URL VPS dulu.");
                return;
            }
            if (ensureMicPermission()) {
                Intent i = new Intent(this, WakeService.class);
                ContextCompat.startForegroundService(this, i);
                sp.edit().putBoolean("auto_start", true).apply();
                statusText.setText("Bokir aktif. Sekarang boleh tekan Home / matikan layar.");
            }
        });

        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, WakeService.class));
            sp.edit().putBoolean("auto_start", false).apply();
            statusText.setText("Bokir berhenti.");
        });

        statusText.setText(hasTemplate()
                ? "Wake word tersimpan. Isi VPS lalu aktifkan Bokir."
                : "Rekam Halo Bokir sekali.");
    }

    private void saveConfig() {
        String url = serverUrl.getText().toString().trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        getSharedPreferences("bokir", MODE_PRIVATE).edit()
                .putString("server_url", url)
                .putString("token", tokenInput.getText().toString().trim())
                .apply();
        statusText.setText("Konfigurasi VPS tersimpan.");
    }

    private void testServer() {
        saveConfig();
        final String base = serverUrl.getText().toString().trim().replaceAll("/+$", "");
        if (base.isEmpty()) {
            statusText.setText("Isi URL VPS dulu.");
            return;
        }

        statusText.setText("Menguji VPS...");
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + "/health").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream()
                ));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                JSONObject obj = new JSONObject(sb.toString());
                post(obj.optBoolean("ok", false)
                        ? "VPS CONNECT ✅"
                        : "VPS menjawab tapi tidak OK.");
            } catch (Throwable t) {
                post("VPS belum bisa diakses: " + t.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private boolean hasTemplate() {
        return getSharedPreferences("bokir", MODE_PRIVATE).contains("template");
    }

    private boolean ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) return true;

        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQ_AUDIO
        );
        return false;
    }

    private void recordTemplate() {
        statusText.setText("Ucapkan Halo Bokir sekarang...");
        new Thread(() -> {
            int min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            AudioRecord rec = null;
            try {
                rec = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min * 2, SAMPLE_RATE * 2)
                );

                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    post("Mikrofon gagal dibuka.");
                    return;
                }

                short[] data = new short[SAMPLE_RATE * 3];
                short[] buf = new short[1024];
                int pos = 0;

                rec.startRecording();
                long until = System.currentTimeMillis() + 3000;

                while (System.currentTimeMillis() < until && pos < data.length) {
                    int n = rec.read(buf, 0, Math.min(buf.length, data.length - pos));
                    if (n > 0) {
                        System.arraycopy(buf, 0, data, pos, n);
                        pos += n;
                    }
                }

                float[] f = FeatureExtractor.extract(data, pos);
                if (f == null) {
                    post("Suara belum terbaca. Rekam ulang lebih jelas.");
                    return;
                }

                getSharedPreferences("bokir", MODE_PRIVATE).edit()
                        .putString("template", FeatureExtractor.encode(f))
                        .apply();

                post("Halo Bokir tersimpan ✅");
            } catch (Throwable t) {
                post("Gagal rekam: " + t.getClass().getSimpleName());
            } finally {
                try {
                    if (rec != null) {
                        rec.stop();
                        rec.release();
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private void post(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            statusText.setText("Izin mikrofon aktif.");
        } else {
            statusText.setText("Izin mikrofon ditolak.");
        }
    }
}
