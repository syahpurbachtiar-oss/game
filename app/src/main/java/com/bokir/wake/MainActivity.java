package com.bokir.wake;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_AUDIO = 1001;
    private TextView statusText;
    private SpeechRecognizer recognizer;
    private boolean launching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        Button startButton = findViewById(R.id.startButton);
        Button stopButton = findViewById(R.id.stopButton);

        startButton.setOnClickListener(v -> ensureStarted());
        stopButton.setOnClickListener(v -> stopListening());

        ensureStarted();
    }

    private void ensureStarted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO
            );
        } else {
            safeStartListening();
        }
    }

    private void safeStartListening() {
        try {
            startListening();
        } catch (Throwable t) {
            statusText.setText("Error mulai dengar: " + t.getClass().getSimpleName());
        }
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.setText("SpeechRecognizer tidak tersedia");
            return;
        }

        stopRecognizerOnly();

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                statusText.setText("Mendengarkan... ucapkan: Halo Bokir");
            }
            @Override public void onBeginningOfSpeech() {
                statusText.setText("Suara terdeteksi...");
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                statusText.setText("Memproses ucapan...");
            }
            @Override public void onError(int error) {
                statusText.setText("Error suara #" + error + " — mencoba lagi");
                statusText.postDelayed(() -> safeStartListening(), 800);
            }
            @Override public void onResults(Bundle results) {
                checkResults(results);
                statusText.postDelayed(() -> safeStartListening(), 500);
            }
            @Override public void onPartialResults(Bundle partialResults) {
                checkResults(partialResults);
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognizer.startListening(intent);
    }

    private void checkResults(Bundle bundle) {
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return;

        String heard = list.get(0);
        statusText.setText("Terdengar: " + heard);

        if (launching) return;
        for (String s : list) {
            String t = s.toLowerCase(Locale.ROOT).trim();
            if (t.contains("bokir") || t.contains("bogir") || t.contains("bukir")) {
                launching = true;
                statusText.setText("Bokir terdeteksi — membuka ChatGPT...");
                launchAssistant();
                statusText.postDelayed(() -> launching = false, 3000);
                break;
            }
        }
    }

    private void launchAssistant() {
        try {
            Intent assistant = new Intent(Intent.ACTION_ASSIST);
            assistant.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(assistant);
            return;
        } catch (Throwable ignored) {}

        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                statusText.setText("ChatGPT tidak ditemukan");
            }
        } catch (Throwable t) {
            statusText.setText("Gagal membuka ChatGPT");
        }
    }

    private void stopRecognizerOnly() {
        try {
            if (recognizer != null) {
                recognizer.cancel();
                recognizer.destroy();
                recognizer = null;
            }
        } catch (Throwable ignored) {}
    }

    private void stopListening() {
        stopRecognizerOnly();
        statusText.setText("Berhenti");
    }

    @Override
    protected void onDestroy() {
        stopRecognizerOnly();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            safeStartListening();
        } else {
            statusText.setText("Izin mikrofon ditolak");
        }
    }
}
