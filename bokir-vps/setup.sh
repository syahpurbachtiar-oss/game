#!/usr/bin/env bash
set -e

echo "=== Bokir VPS Setup ==="

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js belum ada. Install Node.js 20+ terlebih dahulu."
  exit 1
fi

echo "Node: $(node -v)"
echo "NPM : $(npm -v)"

npm install

if [ ! -f .env ]; then
  cp .env.example .env
  echo "File .env dibuat. Isi OPENROUTER_API_KEY + BOKIR_TOKEN."
fi

echo "Selesai."
echo "Tes manual: npm start"
echo "24 jam: npm install -g pm2 && pm2 start ecosystem.config.cjs && pm2 save"
