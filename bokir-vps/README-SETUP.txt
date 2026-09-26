BOKIR VPS - SETUP

Flow:
APK -> /voice -> OpenRouter STT -> OpenRouter AI -> JSON reply -> TTS Android.

Satu OpenRouter API key dipakai untuk STT dan AI.
STT Whisper dapat berbiaya berdasarkan durasi audio.
CHAT_MODEL=openrouter/free dipakai untuk jawaban AI gratis jika tersedia.

INSTALL:
cd bokir-vps
chmod +x setup.sh
./setup.sh
nano .env

Isi:
OPENROUTER_API_KEY=API_KAMU
BOKIR_TOKEN=TOKEN_RAHASIA

Tes:
npm start
curl http://127.0.0.1:8787/health

Tes AI:
curl -X POST http://127.0.0.1:8787/ask -H "Authorization: Bearer TOKEN" -H "Content-Type: application/json" -d '{"session_id":"hp-redmi","text":"Halo Bokir, berapa dua kali lima?"}'

24 jam:
npm install -g pm2
pm2 start ecosystem.config.cjs
pm2 save
pm2 startup

Port default: 8787
