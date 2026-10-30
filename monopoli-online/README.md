# Kota Kita — Bisnis Properti

Game papan bisnis properti orisinal, responsif untuk HP dan PC.

## Fitur

- Mode lokal 2–4 pemain (pemain manusia + AI komputer).
- Mode online hingga 4 pemain lewat kode room.
- Papan 40 petak, dadu, gaji, pembelian properti, sewa, rumah/hotel, gadai, kartu, pajak, penjara, dan kebangkrutan.
- Simpan progres permainan lokal di browser.
- UI responsif. Tidak perlu build tool atau API server sendiri.

## Online tanpa konfigurasi untuk pemain

Koneksi online memakai konfigurasi Firebase Firestore yang sudah ditanam langsung di `index.html`, dengan pola yang sama seperti game HAY Gaple milik pemilik repository. Pemain tidak perlu membuat proyek Firebase, memasukkan API key, login, atau mengatur database.

- Host klik **Buat Room**, lalu bagikan kode enam karakter.
- Pemain lain memasukkan kode tersebut dan klik **Gabung**.
- Host klik **Mulai Permainan** setelah minimal dua orang bergabung.
- Maksimal empat pemain manusia per room.

Data room Monopoli menggunakan dokumen dengan prefix `MONO_` di koleksi Firestore `rooms`, sehingga tidak bertabrakan dengan dokumen room Gaple yang menggunakan ID biasa.

## File

- `index.html` — seluruh UI dan logika permainan.
- `database.rules.json` — contoh rules untuk instalasi Realtime Database lama; tidak digunakan oleh versi online Firestore saat ini.

## Catatan teknis

Mode online menggunakan database Firebase yang sudah dipakai aplikasi Gaple. Karena koneksi ditanam langsung di HTML, siapa pun yang membuka game dapat menggunakan konfigurasi Firebase yang sama. Aturan akses aktual mengikuti Firestore Rules pada proyek tersebut. Permainan dirancang untuk main kasual bersama teman, bukan kompetisi berhadiah atau transaksi uang asli.

Game ini tidak berafiliasi dengan merek board game lain.
