package com.bokir.wake;

import android.app.*;
import android.content.*;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WakeService extends Service {
    private static final int SAMPLE_RATE = 16000;
    private static final String CHANNEL_ID = "bokir_local";
    private volatile boolean running = false;
    private AudioRecord recorder;
    private float[] template;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bokir Wake aktif")
                .setContentText("Menunggu ucapan pemicu")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        startForeground(10, notification);

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
                stopSelf();
                return;
            }

            recorder.startRecording();

            short[] frame = new short[320];
            short[] speech = new short[SAMPLE_RATE * 3];
            int speechPos = 0;
            boolean inSpeech = false;
            int silentFrames = 0;
            float noise = 300f;
            long cooldownUntil = 0;

            while (running) {
                int n = recorder.read(frame, 0, frame.length);
                if (n <= 0) continue;

                double sum = 0;
                for (int i = 0; i < n; i++) sum += (double)frame[i] * frame[i];
                float rms = (float)Math.sqrt(sum / n);

                if (!inSpeech) {
                    noise = noise * 0.98f + rms * 0.02f;
                    float gate = Math.max(700f, noise * 2.2f);
                    if (rms > gate) {
                        inSpeech = true;
                        speechPos = 0;
                        silentFrames = 0;
                    }
                }

                if (inSpeech) {
                    int copy = Math.min(n, speech.length - speechPos);
                    System.arraycopy(frame, 0, speech, speechPos, copy);
                    speechPos += copy;

                    float gate = Math.max(500f, noise * 1.5f);
                    if (rms < gate) silentFrames++;
                    else silentFrames = 0;

                    boolean finished = silentFrames >= 18 || speechPos >= speech.length;
                    if (finished) {
                        if (speechPos > SAMPLE_RATE / 3 && System.currentTimeMillis() > cooldownUntil) {
                            float[] f = FeatureExtractor.extract(speech, speechPos);
                            float score = FeatureExtractor.cosine(template, f);
                            if (score >= 0.90f) {
                                cooldownUntil = System.currentTimeMillis() + 5000;
                                launchAssistant();
                            }
                        }
                        inSpeech = false;
                        speechPos = 0;
                        silentFrames = 0;
                    }
                }
            }
        } catch (SecurityException e) {
            stopSelf();
        } catch (Throwable t) {
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

    private void launchAssistant() {
        boolean started = false;
        try {
            Intent assistant = new Intent(Intent.ACTION_ASSIST);
            assistant.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(assistant);
            started = true;
        } catch (Throwable ignored) {}

        if (!started) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            } catch (Throwable ignored) {}
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID,
                    "Bokir Wake",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
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
