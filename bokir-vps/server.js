require("dotenv").config();

const express = require("express");
const multer = require("multer");
const crypto = require("crypto");

const app = express();
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: Number(process.env.MAX_AUDIO_MB || 8) * 1024 * 1024 }
});

app.use(express.json({ limit: "1mb" }));

const PORT = Number(process.env.PORT || 8787);
const OPENROUTER_API_KEY = process.env.OPENROUTER_API_KEY || "";
const BOKIR_TOKEN = process.env.BOKIR_TOKEN || "";
const CHAT_MODEL = process.env.CHAT_MODEL || "openrouter/free";
const STT_MODEL = process.env.STT_MODEL || "openai/whisper-large-v3-turbo";
const MAX_HISTORY_TURNS = Number(process.env.MAX_HISTORY_TURNS || 6);
const SESSION_TTL_MS = Number(process.env.SESSION_TTL_MINUTES || 15) * 60 * 1000;

const sessions = new Map();

function nowMs() { return Date.now(); }

function cleanOldSessions() {
  const cutoff = nowMs() - SESSION_TTL_MS;
  for (const [id, s] of sessions.entries()) {
    if (s.updatedAt < cutoff) sessions.delete(id);
  }
}
setInterval(cleanOldSessions, 60_000).unref();

function auth(req, res, next) {
  if (!BOKIR_TOKEN) return next();
  const header = req.headers.authorization || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (token !== BOKIR_TOKEN) return res.status(401).json({ ok: false, error: "unauthorized" });
  next();
}

function getSessionId(req) {
  return String(req.body?.session_id || req.headers["x-bokir-session"] || crypto.randomUUID()).slice(0, 128);
}

function getHistory(sessionId) {
  return sessions.get(sessionId)?.messages || [];
}

function saveTurn(sessionId, userText, assistantText) {
  const messages = getHistory(sessionId);
  messages.push({ role: "user", content: userText });
  messages.push({ role: "assistant", content: assistantText });
  sessions.set(sessionId, {
    messages: messages.slice(-(MAX_HISTORY_TURNS * 2)),
    updatedAt: nowMs()
  });
}

function detectFormat(file) {
  const name = (file.originalname || "").toLowerCase();
  const mime = (file.mimetype || "").toLowerCase();
  if (name.endsWith(".wav") || mime.includes("wav")) return "wav";
  if (name.endsWith(".mp3") || mime.includes("mpeg")) return "mp3";
  if (name.endsWith(".m4a") || mime.includes("mp4")) return "m4a";
  if (name.endsWith(".ogg") || mime.includes("ogg")) return "ogg";
  if (name.endsWith(".webm") || mime.includes("webm")) return "webm";
  if (name.endsWith(".aac") || mime.includes("aac")) return "aac";
  if (name.endsWith(".flac") || mime.includes("flac")) return "flac";
  return "wav";
}

async function openRouter(path, body, timeoutMs = 30000) {
  if (!OPENROUTER_API_KEY) throw new Error("OPENROUTER_API_KEY belum diisi");
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const r = await fetch(`https://openrouter.ai/api/v1${path}`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${OPENROUTER_API_KEY}`,
        "Content-Type": "application/json",
        "HTTP-Referer": process.env.APP_URL || "https://bokir.local",
        "X-Title": "Bokir Voice Assistant"
      },
      body: JSON.stringify(body),
      signal: controller.signal
    });
    const text = await r.text();
    let data;
    try { data = JSON.parse(text); }
    catch { throw new Error(`OpenRouter response bukan JSON: ${text.slice(0, 300)}`); }

    if (!r.ok) throw new Error(data?.error?.message || data?.message || `OpenRouter HTTP ${r.status}`);
    return data;
  } finally {
    clearTimeout(timer);
  }
}

async function transcribeAudio(file) {
  const data = await openRouter("/audio/transcriptions", {
    model: STT_MODEL,
    input_audio: {
      data: file.buffer.toString("base64"),
      format: detectFormat(file)
    },
    language: "id",
    temperature: 0
  }, 45000);
  return String(data?.text || "").trim();
}

async function askAI(sessionId, userText) {
  const systemPrompt = [
    "Kamu adalah Bokir, asisten suara pribadi berbahasa Indonesia.",
    "Jawab dengan natural, cepat, jelas, dan enak didengar melalui speaker.",
    "Utamakan jawaban singkat 1-3 kalimat kecuali pengguna meminta detail.",
    "Jangan gunakan markdown, bullet, tabel, emoji berlebihan, atau pembukaan panjang.",
    "Jika pertanyaan sederhana, langsung jawab.",
    "Jangan mengarang fakta yang tidak diketahui."
  ].join(" ");

  const data = await openRouter("/chat/completions", {
    model: CHAT_MODEL,
    messages: [
      { role: "system", content: systemPrompt },
      ...getHistory(sessionId),
      { role: "user", content: userText }
    ],
    temperature: 0.4,
    max_tokens: 220
  }, 30000);

  const reply = String(data?.choices?.[0]?.message?.content || "").trim();
  if (!reply) throw new Error("AI tidak mengembalikan jawaban");
  saveTurn(sessionId, userText, reply);
  return reply;
}

app.get("/health", (req, res) => {
  res.json({ ok: true, service: "bokir-vps", chat_model: CHAT_MODEL, stt_model: STT_MODEL, time: new Date().toISOString() });
});

app.post("/ask", auth, async (req, res) => {
  const started = nowMs();
  try {
    const text = String(req.body?.text || "").trim();
    if (!text) return res.status(400).json({ ok: false, error: "text kosong" });
    const sessionId = getSessionId(req);
    const llmStart = nowMs();
    const reply = await askAI(sessionId, text);
    res.json({ ok: true, session_id: sessionId, transcript: text, reply, timing_ms: { llm: nowMs() - llmStart, total: nowMs() - started } });
  } catch (err) {
    console.error("[ASK]", err);
    res.status(500).json({ ok: false, error: err.message || "server_error" });
  }
});

app.post("/voice", auth, upload.single("audio"), async (req, res) => {
  const started = nowMs();
  try {
    if (!req.file?.buffer?.length) return res.status(400).json({ ok: false, error: "file audio tidak ada" });
    const sessionId = getSessionId(req);

    const sttStart = nowMs();
    const transcript = await transcribeAudio(req.file);
    const sttMs = nowMs() - sttStart;

    if (!transcript) {
      return res.json({ ok: true, session_id: sessionId, transcript: "", reply: "", no_speech: true, timing_ms: { stt: sttMs, total: nowMs() - started } });
    }

    const llmStart = nowMs();
    const reply = await askAI(sessionId, transcript);
    const llmMs = nowMs() - llmStart;

    res.json({ ok: true, session_id: sessionId, transcript, reply, timing_ms: { stt: sttMs, llm: llmMs, total: nowMs() - started } });
  } catch (err) {
    console.error("[VOICE]", err);
    res.status(500).json({ ok: false, error: err.message || "server_error" });
  }
});

app.post("/reset", auth, (req, res) => {
  const sessionId = getSessionId(req);
  sessions.delete(sessionId);
  res.json({ ok: true, session_id: sessionId });
});

app.use((err, req, res, next) => {
  if (err?.code === "LIMIT_FILE_SIZE") return res.status(413).json({ ok: false, error: "audio terlalu besar" });
  console.error("[UNHANDLED]", err);
  res.status(500).json({ ok: false, error: "server_error" });
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`Bokir VPS aktif di port ${PORT}`);
  console.log(`Chat model: ${CHAT_MODEL}`);
  console.log(`STT model: ${STT_MODEL}`);
});
