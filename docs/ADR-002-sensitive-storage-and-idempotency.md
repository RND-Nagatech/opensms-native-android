# ADR-002: Sensitive storage, API keys, and idempotency fingerprints

- **Status:** Diterima untuk MVP P0.
- **Tanggal:** 2026-10-20

## Keputusan

1. Destination dan body SMS dienkripsi terpisah memakai AES-256-GCM. Kunci AES dibuat dan disimpan non-exportable di Android Keystore.
2. Token API berisi 32 byte dari `SecureRandom`, hanya ditampilkan saat pembuatan/rotasi, dan tidak disimpan dalam plaintext. Room menyimpan HMAC-SHA256 verifier menggunakan kunci HMAC Android Keystore.
3. Payload fingerprint idempotency memakai HMAC-SHA256 dengan label domain terpisah dari API token. Ini menghindari penyimpanan hash polos yang dapat diuji dengan tebakan offline terhadap body SMS.
4. Idempotency record, encrypted message, per-part rows, dan audit enqueue ditulis dalam satu transaksi Room sebelum API mengirim HTTP 202. Konflik key/payload tidak membuat row baru.
5. Database memakai Room schema export dan tidak menggunakan destructive migration. Database saat ini versi 1; semua perubahan skema sesudah MVP harus menambah migrasi teruji.
6. Android backup dinonaktifkan untuk menghindari pemulihan ciphertext tanpa kunci Keystore terkait. Kehilangan kunci Keystore dapat membuat field lama tak dapat dibuka; record tersebut tidak boleh dikirim ulang otomatis.

## Konsekuensi dan sisa risiko

- Enkripsi field tidak mengenkripsi metadata seperti timestamp, status, SIM ID, client ID, dan ukuran segment.
- Penghapusan SQLite/flash tidak menjamin secure erase fisik; retensi dan prosedur hapus data tetap dibutuhkan.
- Pemulihan dari invalidasi kunci AES belum memiliki UI administrasi. Dispatcher menandai persiapan gagal dan tidak memanggil radio saat dekripsi gagal.
- Tes instrumented membuktikan field terenkripsi dan replay idempotency pada Room in-memory; ketahanan lifecycle Keystore pada perangkat fisik masih `NOT RUN`.
