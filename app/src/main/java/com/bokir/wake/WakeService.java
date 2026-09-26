package com.bokir.wake;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.os.IBinder;
import android.speech.*;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Locale;

public class WakeService extends Service {
    private static final String CHANNEL_ID = "bokir_wake_channel";
    private SpeechRecognizer recognizer;
    private boolean launching = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bokir Wake aktif")
                .setContentText("Menunggu: Halo Bokir")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();

        startForeground(1, notification);
        sendStatus("Service aktif. Menyiapkan pendengar...");
        new android.os.Handler(getMainLooper()).postDelayed(this::safeStartListening, 400);
    }

    private void sendStatus(String msg) {
        Intent i = new Intent("com.bokir.wake.STATUS");
        i.setPackage(getPackageName());
        i.putExtra("msg", msg);
        sendBroadcast(i);
    }

    private void safeStartListening() {
        try {
            startListening();
        } catch (Throwable t) {
            sendStatus("Error mulai dengar: " + t.getClass().getSimpleName());
            scheduleRestart(1500);
        }
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendStatus("SpeechRecognizer tidak tersedia");
            scheduleRestart(2000);
            return;
        }

        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle params) {
                sendStatus("Mendengarkan... ucapkan: Halo Bokir");
            }
            @Override public void onBeginningOfSpeech() {
                sendStatus("Suara terdeteksi...");
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                sendStatus("Memproses ucapan...");
            }
            @Override public void onError(int error) {
                sendStatus("Error suara #" + error + " — mencoba lagi");
                scheduleRestart(1000);
            }

            @Override
            public void onResults(android.os.Bundle results) {
                checkResults(results, false);
                scheduleRestart(700);
            }

            @Override public void onPartialResults(android.os.Bundle partialResults) {
                checkResults(partialResults, true);
            }

            @Override public void onEvent(int eventType, android.os.Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizer.startListening(intent);
    }

    private void checkResults(android.os.Bundle bundle, boolean partial) {
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;

        String heard = list.get(0);
        sendStatus((partial ? "Terdengar: " : "Hasil: ") + heard);

        if (launching) return;

        for (String s : list) {
            String t = s.toLowerCase(Locale.ROOT).trim();
            if (t.contains("bokir") || t.contains("bogir") || t.contains("bukir")) {
                launching = true;
                sendStatus("Bokir terdeteksi — membuka ChatGPT...");
                launchAssistant();
                new android.os.Handler(getMainLooper()).postDelayed(() -> launching = false, 3000);
                break;
            }
        }
    }

    private void scheduleRestart(long delayMs) {
        new android.os.Handler(getMainLooper()).postDelayed(this::safeStartListening, delayMs);
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
                    started = true;
                }
            } catch (Throwable ignored) {}
        }

        if (!started) sendStatus("ChatGPT tidak berhasil dibuka");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Bokir Wake",
                            NotificationManager.IMPORTANCE_LOW
                    );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (recognizer != null) recognizer.destroy();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
