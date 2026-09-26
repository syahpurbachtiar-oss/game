package com.bokir.wake;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

public class WakeService extends Service {
    private static final int SAMPLE_RATE = 16000;
    private static final String CHANNEL_ID = "bokir_local";
    private volatile boolean running = false;
    private AudioRecord recorder;
    private float[] template;
    private NotificationManager nm;
    private WindowManager windowManager;
    private View overlayView;

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
                    showOverlayAndOpenChatGPT();
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

    private void showOverlayAndOpenChatGPT() {
        new android.os.Handler(getMainLooper()).post(() -> {
            try {
                if (!Settings.canDrawOverlays(this)) {
                    updateNotification("Izin tampil di atas aplikasi lain belum aktif");
                    return;
                }

                if (windowManager == null) {
                    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                }

                if (overlayView != null) {
                    try { windowManager.removeView(overlayView); } catch (Throwable ignored) {}
                    overlayView = null;
                }

                TextView bubble = new TextView(this);
                bubble.setText("Bokir");
                bubble.setTextSize(14f);
                bubble.setGravity(Gravity.CENTER);
                bubble.setPadding(18, 8, 18, 8);
                bubble.setBackgroundColor(0xCC000000);
                bubble.setTextColor(0xFFFFFFFF);
                overlayView = bubble;

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                );
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.x = 24;
                lp.y = 120;

                windowManager.addView(overlayView, lp);

                new android.os.Handler(getMainLooper()).postDelayed(() -> {
                    openChatGPT();
                    new android.os.Handler(getMainLooper()).postDelayed(this::removeOverlay, 1200);
                }, 250);

            } catch (Throwable t) {
                updateNotification("Overlay gagal: " + t.getClass().getSimpleName());
            }
        });
    }

    private void openChatGPT() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            }
        } catch (Throwable t) {
            updateNotification("Gagal membuka ChatGPT");
        }
    }

    private void removeOverlay() {
        try {
            if (windowManager != null && overlayView != null) {
                windowManager.removeView(overlayView);
            }
        } catch (Throwable ignored) {}
        overlayView = null;
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
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
