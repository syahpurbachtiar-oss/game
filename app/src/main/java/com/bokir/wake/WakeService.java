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
        startListening();
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf();
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(android.os.Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) { restart(); }

            @Override
            public void onResults(android.os.Bundle results) {
                checkResults(results);
                restart();
            }

            @Override public void onPartialResults(android.os.Bundle partialResults) {
                checkResults(partialResults);
            }

            @Override public void onEvent(int eventType, android.os.Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizer.startListening(intent);
    }

    private void checkResults(android.os.Bundle bundle) {
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || launching) return;
        for (String s : list) {
            String t = s.toLowerCase(Locale.ROOT).trim();
            if (t.contains("halo bokir") || t.contains("hallo bokir") ||
                    t.contains("halo bogir") || t.contains("halo bukir")) {
                launching = true;
                launchAssistant();
                new android.os.Handler(getMainLooper()).postDelayed(() -> launching = false, 3000);
                break;
            }
        }
    }

    private void restart() {
        try {
            if (recognizer != null) {
                recognizer.destroy();
                recognizer = null;
            }
        } catch (Exception ignored) {}
        new android.os.Handler(getMainLooper()).postDelayed(this::startListening, 500);
    }

    private void launchAssistant() {
        try {
            Intent assistant = new Intent(Intent.ACTION_ASSIST);
            assistant.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(assistant);
        } catch (Exception e) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            } catch (Exception ignored) {}
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(CHANNEL_ID, "Bokir Wake",
                            NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (recognizer != null) recognizer.destroy();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
