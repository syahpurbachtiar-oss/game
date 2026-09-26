package com.bokir.wake;

import android.app.*;
import android.content.*;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class WakeService extends Service {
    private static final int SAMPLE_RATE = 16000;
    private static final String CHANNEL_ID = "bokir_local";
    private volatile boolean running = false;
    private AudioRecord recorder;
    private float[] template;
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        nm = getSystemService(NotificationManager.class);
        startForeground(10, buildNotification("Menunggu: Halo Bokir"));

        template = FeatureExtractor.decode(
                getSharedPreferences("bokir", MODE_PRIVATE).getString("template", null)
        );
        if (template == null) {
            stopSelf();
            return;
        }

        running = true;
        new Thread(this::listenLoop).start();
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bokir Wake aktif")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            if (nm != null) nm.notify(10, buildNotification(text));
        } catch (Throwable ignored) {}
    }

    private void listenLoop() {
        int min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, SAMPLE_RATE * 2)
            );

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                updateNotification("Mikrofon gagal dibuka");
                stopSelf();
                return;
            }

            recorder.startRecording();

            final int maxSamples = SAMPLE_RATE * 3;
            short[] ring = new short[maxSamples];
            int filled = 0;
            short[] frame = new short[320];
            int frames = 0;
            long cooldownUntil = 0;
            float lastShown = -1f;

            while (running) {
                int n = recorder.read(frame, 0, frame.length);
                if (n <= 0) continue;

                if (filled < maxSamples) {
                    int copy = Math.min(n, maxSamples - filled);
                    System.arraycopy(frame, 0, ring, filled, copy);
                    filled += copy;
                    if (copy < n) {
                        int remain = n - copy;
                        System.arraycopy(ring, remain, ring, 0, maxSamples - remain);
                        System.arraycopy(frame, copy, ring, maxSamples - remain, remain);
                        filled = maxSamples;
                    }
                } else {
                    System.arraycopy(ring, n, ring, 0, maxSamples - n);
                    System.arraycopy(frame, 0, ring, maxSamples - n, n);
                }

                frames++;
                if (frames % 8 != 0 || filled < SAMPLE_RATE) continue;

                float best = -1f;
                int[] windows = new int[] {
                        SAMPLE_RATE,
                        SAMPLE_RATE * 3 / 2,
                        SAMPLE_RATE * 2,
                        SAMPLE_RATE * 5 / 2,
                        SAMPLE_RATE * 3
                };

                for (int w : windows) {
                    if (filled < w) continue;
                    short[] segment = new short[w];
                    System.arraycopy(ring, filled - w, segment, 0, w);
                    float[] f = FeatureExtractor.extract(segment, w);
                    float score = FeatureExtractor.cosine(template, f);
                    if (score > best) best = score;
                }

                if (best > 0.55f && Math.abs(best - lastShown) > 0.03f) {
                    updateNotification("Mendeteksi suara (" + Math.round(best * 100) + "%)");
                    lastShown = best;
                }

                if (best >= 0.72f && System.currentTimeMillis() > cooldownUntil) {
                    cooldownUntil = System.currentTimeMillis() + 6000;
                    updateNotification("Halo Bokir terdeteksi");
                    openChatGPT();
                }
            }
        } catch (SecurityException e) {
            updateNotification("Izin mikrofon ditolak");
            stopSelf();
        } catch (Throwable t) {
            updateNotification("Listener berhenti: " + t.getClass().getSimpleName());
            stopSelf();
        } finally {
            try {
                if (recorder != null) {
                    recorder.stop();
                    recorder.release();
                }
            } catch (Throwable ignored) {}
        }
    }

    private void openChatGPT() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
            }
        } catch (Throwable ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID,
                    "Bokir Wake",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
