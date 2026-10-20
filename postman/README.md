# Mengirim SMS lewat Postman

OpenSMS Next menerima perintah kirim SMS melalui API. Aplikasi Android tidak menyediakan form untuk menulis SMS secara manual; API memasukkan pesan ke antrean, lalu gateway memprosesnya menggunakan SIM di Infinix.

## Siapkan Infinix

1. Hubungkan Infinix dan komputer/Postman ke Wi-Fi yang sama.
2. Buka **Pengaturan → Kunci API**, buat client dengan scope **Kirim pesan**, **Baca pesan**, dan **Baca status dan SIM**. Salin API key saat ditampilkan; kunci hanya terlihat satu kali.
3. Di bagian **Akses LAN tepercaya**, simpan subnet Wi-Fi yang dipercaya, misalnya `192.168.1.0/24` jika sesuai dengan jaringan Anda.
4. Kembali ke **Beranda**, pilih **LAN**, lalu mulai gateway. Biarkan aplikasi tetap terbuka di layar.
5. Catat alamat yang ditampilkan, misalnya `http://192.168.1.25:9292`. Gunakan nilai itu sebagai `baseUrl` dengan `/v1` di akhir: `http://192.168.1.25:9292/v1`.

## Impor dan atur Postman

1. Di Postman pilih **Import** dan impor `OpenSMS-Next.postman_collection.json`.
2. Buka collection variables, ubah `baseUrl` menjadi alamat Infinix tadi, lalu isi `apiKey` dengan kunci client. Simpan nilainya secara lokal/rahasia dan jangan bagikan collection yang berisi API key.
3. Jalankan **1. Health check**. Hasil `200` dengan `{"ok":true}` berarti listener dapat dijangkau.
4. Jalankan **2. Lihat status gateway** dan pastikan `server` bernilai `RUNNING`, `sms_ready` bernilai `true`.
5. Jalankan **3. Lihat SIM yang tersedia** untuk memastikan SIM aktif. Secara default, request kirim memakai SIM default Android.
6. Isi variable `recipient` dengan nomor milik Anda dalam format E.164, misalnya awalan `+62` tanpa spasi, lalu buka **4. Kirim SMS** dan sesuaikan teksnya. Tekan **Send**.
7. Respons `202 Accepted` berarti pesan berhasil dimasukkan ke antrean, bukan bukti pesan sudah terkirim. Collection menyimpan `messageId`; jalankan **5. Periksa status pesan terakhir** atau **6. Daftar pesan milik client ini** untuk memantau hasilnya.

`Idempotency-Key` dibuat otomatis dan dipertahankan untuk request kirim yang sama. Jika Postman harus mengirim pesan uji baru setelah respons sukses, hapus nilai collection variable `idempotencyKey` dahulu. Jika tidak, pengulangan request dengan key dan isi yang sama akan direplay aman dan tidak mengirim SMS duplikat.

## Catatan

- Mengirim SMS nyata dapat dikenai biaya operator. Pastikan `recipient` adalah nomor uji yang Anda kendalikan sebelum menekan **Send**.
- Gunakan hanya jaringan Wi-Fi tepercaya. Koneksi HTTP LAN tidak terenkripsi; jangan teruskan port gateway ke internet.
- Jika health check gagal, pastikan kedua perangkat berada di Wi-Fi yang sama, IP `baseUrl` benar, subnet allowlist cocok, dan gateway masih aktif di Infinix.
- Scope API key harus mencakup operasi yang dipanggil: `messages:write` untuk kirim, `messages:read` untuk riwayat/status, `system:read` untuk status gateway dan daftar SIM.
