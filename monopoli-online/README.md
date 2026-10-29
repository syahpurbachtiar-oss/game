# Kota Kita — Bisnis Properti

Game papan properti orisinal yang berjalan di browser dan responsif untuk HP maupun PC.

**Live path:** `https://syahpurbachtiar-oss.github.io/game/monopoli-online/`

## Fitur

- Mode lokal 2–4 pemain, termasuk AI komputer.
- Mode online hingga 4 pemain manusia dengan kode room enam karakter.
- Papan 40 petak, dadu, gaji, pembelian properti, sewa, rumah/hotel, gadai, kartu, pajak, penjara, dan kondisi menang.
- Progress game lokal disimpan di browser.
- UI responsif, tanpa proses build atau API berbayar.
- Firebase Realtime Database untuk sinkronisasi online antarperangkat.

## Cara main lokal

Buka live link, atur 2–4 pemain, pilih manusia atau AI komputer, lalu klik **Mulai Bermain**. Progres lokal tersimpan di browser yang sama.

## Aktifkan online room (perlu konfigurasi Firebase satu kali)

GitHub Pages hanya meng-host file statis. Online room memakai Firebase Realtime Database. Untuk mengaktifkannya:

1. Buka [Firebase Console](https://console.firebase.google.com/) dan buat project.
2. Tambahkan aplikasi web di Project settings → General, lalu salin Web App config.
3. Di Authentication → Sign-in method, aktifkan **Anonymous**.
4. Buat **Realtime Database** pada Build → Realtime Database dan salin Database URL.
5. Pada Realtime Database → Rules, salin isi file `database.rules.json` dari repository ini dan tekan **Publish**.
6. Buka game, perluas **Pengaturan Firebase**, isi API Key, Auth Domain, Database URL, Project ID, dan App ID; tekan **Simpan & Hubungkan**.
7. Lakukan langkah 6 juga pada perangkat teman menggunakan konfigurasi Firebase yang sama.
8. Host klik **Buat Room** dan bagikan kode/link; teman memasukkan kode lalu klik **Gabung**. Host dapat memulai permainan setelah minimal dua pemain bergabung.

Firebase Web API key bukan kredensial admin. Tetap gunakan rules di atas dan jangan menyimpan service-account key di aplikasi web. Rules ini cocok untuk bermain kasual bersama teman; pemain yang sudah masuk room dapat memodifikasi state game, jadi ini bukan sistem anti-cheat atau game berhadiah uang asli.

## Files

- `index.html` — UI dan logika game.
- `database.rules.json` — contoh rules Firebase Realtime Database.

## Batasan

- Mode online membutuhkan project Firebase milik operator game; tanpa konfigurasi tersebut, mode lokal tetap bisa dimainkan.
- Permainan online saat ini mengandalkan klien browser untuk logika game, sehingga tidak cocok untuk kompetisi berhadiah atau data sensitif.
- Tidak memerlukan akun permanen; autentikasi Firebase anonymous dibuat otomatis.
