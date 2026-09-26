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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_AUDIO = 1001;
    private static final int SAMPLE_RATE = 16000;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        Button enrollButton = findViewById(R.id.enrollButton);
        Button startButton = findViewById(R.id.startButton);
        Button testButton = findViewById(R.id.testButton);
        Button stopButton = findViewById(R.id.stopButton);

        enrollButton.setOnClickListener(v -> {
            if (ensureMicPermission()) recordTemplate();
        });

        startButton.setOnClickListener(v -> {
            if (!hasTemplate()) {
                statusText.setText("Rekam 'Halo Bokir' dulu.");
                return;
            }
            if (ensureMicPermission()) {
                Intent i = new Intent(this, WakeService.class);
                ContextCompat.startForegroundService(this, i);
                statusText.setText("Bokir aktif. Tekan Home lalu ucapkan Halo Bokir.");
            }
        });

        testButton.setOnClickListener(v -> openChatGPT());

        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, WakeService.class));
            statusText.setText("Bokir berhenti.");
        });

        if (hasTemplate()) {
            statusText.setText("Pemicu tersimpan. Aktifkan Bokir.");
        } else {
            statusText.setText("Rekam ucapan 'Halo Bokir' sekali.");
        }
    }

    private void openChatGPT() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                statusText.setText("ChatGPT dibuka.");
            } else {
                statusText.setText("Aplikasi ChatGPT tidak ditemukan.");
            }
        } catch (Throwable t) {
            statusText.setText("Gagal membuka ChatGPT.");
        }
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
        statusText.setText("Ucapkan 'Halo Bokir' sekarang...");
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
                int pos = 0;
                rec.startRecording();
                long until = System.currentTimeMillis() + 3000;
                short[] buf = new short[1024];

                while (System.currentTimeMillis() < until && pos < data.length) {
                    int n = rec.read(buf, 0, Math.min(buf.length, data.length - pos));
                    if (n > 0) {
                        System.arraycopy(buf, 0, data, pos, n);
                        pos += n;
                    }
                }

                float[] f = FeatureExtractor.extract(data, pos);
                if (f == null) {
                    post("Suara belum terbaca. Rekam lagi lebih jelas.");
                    return;
                }

                SharedPreferences sp = getSharedPreferences("bokir", MODE_PRIVATE);
                sp.edit().putString("template", FeatureExtractor.encode(f)).apply();
                post("Tersimpan. Tekan AKTIFKAN BOKIR.");
            } catch (SecurityException e) {
                post("Izin mikrofon belum aktif.");
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

    private void post(String s) {
        runOnUiThread(() -> statusText.setText(s));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            statusText.setText("Izin mikrofon aktif. Rekam pemicu.");
        } else {
            statusText.setText("Izin mikrofon ditolak.");
        }
    }
}
