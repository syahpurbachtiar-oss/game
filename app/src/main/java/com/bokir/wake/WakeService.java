package com.bokir.wake;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;

public class WakeService extends Service {
    private static final int SAMPLE_RATE = 16000;
    private static final String CHANNEL_ID = "bokir_voice";

    private volatile boolean running = false;
    private volatile boolean busy = false;
    private AudioRecord recorder;
    private float[] template;
    private NotificationManager nm;
    private PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;

    private enum Mode { WAKE, QUESTION_WAIT, FOLLOWUP_WAIT }
    private volatile Mode mode = Mode.WAKE;
    private volatile long followupDeadline = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        nm = getSystemService(NotificationManager.class);

        startForeground(10, buildNotification("Menunggu: Halo Bokir"));

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bokir::VoiceWake");
            wakeLock.acquire();
        }

        template = FeatureExtractor.decode(
                getSharedPreferences("bokir", MODE_PRIVATE).getString("template", null)
        );

        if (template == null) {
            updateNotification("Wake word belum direkam");
            stopSelf();
            return;
        }

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("id", "ID"));
                tts.setSpeechRate(1.05f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}

                    @Override public void onDone(String utteranceId) {
                        busy = false;
                        mode = Mode.FOLLOWUP_WAIT;
                        followupDeadline = System.currentTimeMillis() + 5000;
                        updateNotification("Lanjut bicara, atau diam 5 detik");
                    }

                    @Override public void onError(String utteranceId) {
                        busy = false;
                        mode = Mode.WAKE;
                        updateNotification("Menunggu: Halo Bokir");
                    }
                });
            }
        });

        running = true;
        new Thread(this::listenLoop).start();
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bokir aktif")
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
                    Math.max(min * 4, SAMPLE_RATE * 2)
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

            while (running) {
                int n = recorder.read(frame, 0, frame.length);
                if (n <= 0) continue;

                if (busy) continue;

                if (mode == Mode.FOLLOWUP_WAIT && System.currentTimeMillis() > followupDeadline) {
                    mode = Mode.WAKE;
                    filled = 0;
                    updateNotification("Menunggu: Halo Bokir");
                }

                float rms = rms(frame, n);

                if (mode == Mode.QUESTION_WAIT || mode == Mode.FOLLOWUP_WAIT) {
                    if (rms > 550f) {
                        short[] question = captureUtterance(frame, n);
                        if (question != null && question.length > SAMPLE_RATE / 4) {
                            busy = true;
                            processQuestion(question);
                        } else if (mode == Mode.QUESTION_WAIT) {
                            mode = Mode.WAKE;
                            updateNotification("Tidak ada pertanyaan. Menunggu Halo Bokir");
                        }
                    }
                    continue;
                }

                if (filled < maxSamples) {
                    int copy = Math.min(n, maxSamples - filled);
                    System.arraycopy(frame, 0, ring, filled, copy);
                    filled += copy;
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

                if (best >= 0.72f && System.currentTimeMillis() > cooldownUntil) {
                    cooldownUntil = System.currentTimeMillis() + 3500;
                    mode = Mode.QUESTION_WAIT;
                    filled = 0;
                    updateNotification("Halo Bokir terdeteksi — silakan tanya sekarang");
                }
            }

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

    private short[] captureUtterance(short[] first, int firstN) {
        short[] data = new short[SAMPLE_RATE * 15];
        int pos = 0;
        System.arraycopy(first, 0, data, 0, firstN);
        pos += firstN;

        short[] buf = new short[320];
        int silentFrames = 0;
        boolean heardSpeech = true;
        long deadline = System.currentTimeMillis() + 15000;

        while (running && System.currentTimeMillis() < deadline && pos < data.length) {
            int n = recorder.read(buf, 0, Math.min(buf.length, data.length - pos));
            if (n <= 0) continue;

            System.arraycopy(buf, 0, data, pos, n);
            pos += n;

            float r = rms(buf, n);
            if (r > 450f) {
                heardSpeech = true;
                silentFrames = 0;
            } else if (heardSpeech) {
                silentFrames++;
            }

            if (heardSpeech && silentFrames >= 35) break;
        }

        if (!heardSpeech || pos < SAMPLE_RATE / 4) return null;

        short[] out = new short[pos];
        System.arraycopy(data, 0, out, 0, pos);
        return out;
    }

    private float rms(short[] data, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (double) data[i] * data[i];
        }
        return (float) Math.sqrt(sum / Math.max(1, n));
    }

    private void processQuestion(short[] pcm) {
        updateNotification("Memproses pertanyaan...");
        new Thread(() -> {
            try {
                String base = getSharedPreferences("bokir", MODE_PRIVATE)
                        .getString("server_url", "")
                        .replaceAll("/+$", "");
                String token = getSharedPreferences("bokir", MODE_PRIVATE)
                        .getString("token", "");

                if (base.isEmpty()) throw new IOException("URL VPS kosong");

                byte[] wav = pcmToWav(pcm);
                String boundary = "----Bokir" + UUID.randomUUID();

                HttpURLConnection c = (HttpURLConnection) new URL(base + "/voice").openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(45000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                DataOutputStream out = new DataOutputStream(c.getOutputStream());

                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"session_id\"\r\n\r\n");
                out.writeBytes("redmi-bokir\r\n");

                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"question.wav\"\r\n");
                out.writeBytes("Content-Type: audio/wav\r\n\r\n");
                out.write(wav);
                out.writeBytes("\r\n--" + boundary + "--\r\n");
                out.flush();
                out.close();

                int code = c.getResponseCode();
                InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                c.disconnect();

                JSONObject obj = new JSONObject(sb.toString());
                if (!obj.optBoolean("ok", false)) {
                    throw new IOException(obj.optString("error", "VPS error"));
                }

                String reply = obj.optString("reply", "").trim();
                if (reply.isEmpty()) {
                    busy = false;
                    mode = Mode.WAKE;
                    updateNotification("Jawaban kosong. Menunggu Halo Bokir");
                    return;
                }

                updateNotification("Bokir menjawab...");
                speak(reply);

            } catch (Throwable t) {
                busy = false;
                mode = Mode.WAKE;
                updateNotification("Gagal: " + t.getMessage());
            }
        }).start();
    }

    private void speak(String text) {
        if (tts == null) {
            busy = false;
            mode = Mode.WAKE;
            return;
        }
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "bokir-answer");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "bokir-answer");
    }

    private byte[] pcmToWav(short[] pcm) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);

        int dataSize = pcm.length * 2;
        writeAscii(out, "RIFF");
        writeLEInt(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLEInt(out, 16);
        writeLEShort(out, (short) 1);
        writeLEShort(out, (short) 1);
        writeLEInt(out, SAMPLE_RATE);
        writeLEInt(out, SAMPLE_RATE * 2);
        writeLEShort(out, (short) 2);
        writeLEShort(out, (short) 16);
        writeAscii(out, "data");
        writeLEInt(out, dataSize);

        ByteBuffer bb = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) bb.putShort(s);
        out.write(bb.array());
        out.flush();

        return bos.toByteArray();
    }

    private void writeAscii(DataOutputStream out, String s) throws IOException {
        out.writeBytes(s);
    }

    private void writeLEInt(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xff);
        out.writeByte((v >> 8) & 0xff);
        out.writeByte((v >> 16) & 0xff);
        out.writeByte((v >> 24) & 0xff);
    }

    private void writeLEShort(DataOutputStream out, short v) throws IOException {
        out.writeByte(v & 0xff);
        out.writeByte((v >> 8) & 0xff);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID,
                    "Bokir Voice",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(c);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            boolean auto = getSharedPreferences("bokir", MODE_PRIVATE)
                    .getBoolean("auto_start", false);
            if (auto) {
                Intent restart = new Intent(getApplicationContext(), WakeService.class);
                PendingIntent pi = PendingIntent.getService(
                        getApplicationContext(),
                        77,
                        restart,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                                : PendingIntent.FLAG_UPDATE_CURRENT
                );
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                if (am != null) {
                    am.set(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + 1500,
                            pi
                    );
                }
            }
        } catch (Throwable ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Throwable ignored) {}
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
