# Panduan API OpenSMS Next untuk Mengirim OTP

Dokumen ini menjelaskan cara menghubungkan aplikasi/backend Anda ke OpenSMS Next melalui REST API lokal. Contoh di bawah memakai alamat dan OTP contoh; ganti dengan data gateway dan nomor uji Anda sendiri.

> `202 Accepted` hanya berarti SMS tersimpan di antrean gateway. Itu bukan konfirmasi bahwa SMS sudah terkirim atau diterima.

## 1. Persiapan

1. Pasang dan buka OpenSMS Next di ponsel Android yang memiliki SIM aktif.
2. Buka halaman **Kunci API**, buat API client khusus untuk aplikasi pengirim OTP, dan aktifkan scope yang diperlukan:
   - `messages:write` untuk mengirim SMS.
   - `messages:read` untuk membaca status pesan.
   - `system:read` untuk memeriksa gateway dan daftar SIM.
3. Simpan token API yang ditampilkan ketika dibuat. Perlakukan token seperti password; jangan masukkan ke source control, log, tiket, atau collection Postman yang dibagikan.
4. Nyalakan gateway dari halaman Beranda. Untuk akses dari komputer/server di Wi-Fi yang sama, gunakan alamat LAN dan port yang ditampilkan aplikasi, misalnya `http://192.168.1.25:9292`.
5. Di **Kunci API**, atur jaringan tepercaya/allowlist sesuai jaringan yang dipakai. Gateway LAN memakai HTTP tanpa enkripsi, jadi gunakan hanya pada jaringan tepercaya atau melalui tunnel TLS yang diamankan.

Atur `BASE_URL` menjadi alamat yang ditampilkan gateway ditambah `/v1`, misalnya:

```text
http://192.168.1.25:9292/v1
```

Jangan memakai IP contoh tersebut secara literal. IP perangkat dapat berubah. Endpoint harus dapat dijangkau dari mesin pemanggil dan port gateway tidak boleh dibuka ke internet publik.

## 2. Ringkasan route

Semua endpoint di bawah berawalan `BASE_URL`. Semua route kecuali `/health` membutuhkan `Authorization: Bearer <API_KEY>` dan scope yang sesuai.

| Method | Path | Scope | Kegunaan |
| --- | --- | --- | --- |
| `GET` | `/health` | Tidak perlu key | Memastikan listener HTTP aktif; tidak memastikan SIM siap |
| `GET` | `/status` | `system:read` | Status gateway, kesiapan SMS, jumlah antrean, jaringan, versi |
| `GET` | `/sims` | `system:read` | Melihat SIM aktif dan `sim_id` yang dapat dipakai |
| `POST` | `/messages` | `messages:write` | Menambahkan satu SMS ke antrean |
| `GET` | `/messages/{message_id}` | `messages:read` | Memeriksa status satu pesan milik API client ini |
| `GET` | `/messages` | `messages:read` | Melihat daftar pesan milik API client ini |

Sebuah API client hanya dapat membaca pesan yang dibuat oleh client tersebut. Membagikan key yang sama ke beberapa integrasi akan membuat semuanya berbagi identitas/akses yang sama; buat client/key terpisah bila perlu membatasi atau mencabut akses per aplikasi.

## 3. Periksa gateway dan SIM

### Health check

```bash
curl -i "${BASE_URL}/health"
```

Contoh respons:

```json
{"ok":true}
```

Health check hanya memeriksa listener. Sebelum mengirim, periksa juga status terautentikasi:

```bash
curl -sS "${BASE_URL}/status" \
  -H "Authorization: Bearer ${OPENSMS_API_KEY}"
```

Lanjutkan saat `server` bernilai `RUNNING` dan `sms_ready` bernilai `true`. Untuk melihat pilihan SIM:

```bash
curl -sS "${BASE_URL}/sims" \
  -H "Authorization: Bearer ${OPENSMS_API_KEY}"
```

Pilih nilai `sim_id` dari respons bila ingin memilih SIM secara eksplisit. Jika `sim_id` tidak dikirim saat membuat pesan, gateway memakai SIM default. `sim_id` adalah ID subscription Android pada perangkat tersebut, bukan nomor slot universal; nilainya dapat berubah.

## 4. Kirim OTP

Buat OTP di backend/aplikasi Anda, terapkan masa berlaku dan batas percobaan di sana, lalu masukkan kode ke teks SMS. Gateway hanya mengirim teks yang diberikan; gateway bukan pembuat maupun validator OTP.

### Contoh cURL

Contoh berikut menggunakan UUID acak sebagai `Idempotency-Key`. Ganti `+628123456789` dengan nomor uji dalam format E.164 milik penerima yang berwenang. Nomor E.164 dimulai `+` dan hanya berisi digit setelahnya.

```bash
BASE_URL="http://192.168.1.25:9292/v1"
OPENSMS_API_KEY="<API_KEY_RAHASIA>"
RECIPIENT="+628123456789"
OTP="<KODE_OTP_DARI_BACKEND>"
IDEMPOTENCY_KEY="$(uuidgen | tr '[:upper:]' '[:lower:]')"

curl -i -X POST "${BASE_URL}/messages" \
  -H "Authorization: Bearer ${OPENSMS_API_KEY}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  --data "{\"to\":\"${RECIPIENT}\",\"text\":\"Kode OTP Anda: ${OTP}. Jangan bagikan kode ini.\",\"request_delivery_report\":true,\"client_reference\":\"otp-login-<ID_TRANSAKSI>\"}"
```

`Idempotency-Key` wajib diisi dengan 16–128 karakter ASCII dari `A-Z`, `a-z`, angka, titik, garis bawah, titik dua, atau tanda hubung. `client_reference` opsional, maksimal 100 karakter; gunakan ID transaksi internal yang tidak mengandung OTP atau data pribadi.

Contoh respons saat pesan baru diterima (`202`):

```json
{
  "message_id": "019aefb4-4d40-7c36-b72f-e934865740a1",
  "status": "QUEUED",
  "delivery_status": "PENDING",
  "created_at": "2026-10-20T10:00:00Z",
  "parts_total": 1,
  "links": {
    "self": "/v1/messages/019aefb4-4d40-7c36-b72f-e934865740a1"
  }
}
```

Simpan `message_id` untuk melacak hasil. Jangan menganggap pesan terkirim hanya karena memperoleh `202`.

## 5. Idempotency dan percobaan ulang

`Idempotency-Key` mencegah satu permintaan logis dibuat dua kali ketika pemanggil mengalami timeout atau perlu mengulang permintaan:

- Buat key acak baru untuk setiap **pengiriman SMS yang memang baru**. UUID v4 cocok.
- Bila permintaan yang sama timeout atau responsnya tidak diketahui, ulangi dengan key yang sama dan payload yang sama persis. Gateway mengembalikan pesan yang sama (`200`, `Idempotent-Replay: true`) jika request sebelumnya sudah tercatat.
- Mengirim key yang sama dengan payload berbeda menghasilkan `409 IDEMPOTENCY_CONFLICT`.
- Jangan membuat key baru untuk mengulang request yang hasil pengiriman SMS-nya `UNKNOWN`. Gateway tidak mengirim ulang otomatis setelah hasil dispatch tidak pasti. Periksa status pesan dan riwayat di aplikasi terlebih dahulu.
- Jika pengguna meminta OTP baru, proses itu harus menjadi penerbitan OTP baru yang disengaja: buat OTP dan key baru, terapkan cooldown/rate limit, dan invalidasi OTP sebelumnya di backend Anda sesuai aturan aplikasi.

Key idempotency disimpan setidaknya 24 jam menurut kontrak API. Jangan mengandalkan key lama untuk deduplikasi setelah masa retensi tersebut.

## 6. Periksa status pengiriman

```bash
MESSAGE_ID="019aefb4-4d40-7c36-b72f-e934865740a1"

curl -sS "${BASE_URL}/messages/${MESSAGE_ID}" \
  -H "Authorization: Bearer ${OPENSMS_API_KEY}"
```

Respons detail menyertakan `status`, `delivery_status`, `parts_total`, `parts_sent`, `parts_delivered`, waktu, dan `last_error`. Nomor tujuan dikembalikan dalam bentuk tersamarkan. Endpoint ini tidak mengembalikan teks SMS.

Arti status pesan:

| Status | Arti |
| --- | --- |
| `QUEUED` | Tersimpan dan menunggu diproses |
| `DISPATCHING` | Gateway sedang meminta Android mengirim SMS |
| `SENT` | Callback pengiriman dari Android mengonfirmasi SMS diserahkan ke jaringan/operator; belum menjadi bukti diterima penerima |
| `DELIVERED` | Laporan delivery mengonfirmasi pesan diterima perangkat tujuan |
| `FAILED` | Pengiriman gagal menurut callback/error yang tersedia |
| `UNKNOWN` | Hasil tidak dapat dipastikan, misalnya callback hilang; jangan kirim ulang otomatis |
| `CANCELED` | Pesan antrean dibatalkan sebelum dikirim |

`delivery_status` dapat berupa `PENDING`, `CONFIRMED`, `UNAVAILABLE`, `FAILED`, atau `UNKNOWN`. Laporan delivery bergantung pada dukungan operator/perangkat. `SENT` berbeda dari `DELIVERED`; bila delivery report tidak tersedia, jangan menyimpulkan SMS gagal hanya dari laporan delivery yang tidak ada. Untuk OTP, backend sebaiknya menganggap OTP hanya bisa diverifikasi selama masa berlakunya, bukan berdasarkan asumsi bahwa SMS telah dibaca.

## 7. Daftar pesan

```bash
curl -sS "${BASE_URL}/messages?limit=20" \
  -H "Authorization: Bearer ${OPENSMS_API_KEY}"
```

Filter yang tersedia: `limit` (1–100, default 20), `cursor`, `status`, `sim_id`, `created_from`, dan `created_to` (waktu ISO-8601). Respons membawa `items` dan `next_cursor`; teruskan cursor berikutnya untuk halaman selanjutnya. Nilai `next_cursor: null` berarti tidak ada halaman berikutnya. Daftar hanya memuat metadata tersamarkan milik API client pemanggil.

## 8. Bentuk request `POST /messages`

| Field | Wajib | Aturan |
| --- | --- | --- |
| `to` | Ya | Nomor E.164, contoh `+628123456789` |
| `text` | Ya | 1–1600 karakter, tidak boleh kosong; maksimal 10 bagian SMS, teks Unicode dapat mencapai batas bagian lebih cepat |
| `sim_id` | Tidak | ID subscription aktif dari `/sims`; bila tidak diberikan, SIM default digunakan |
| `request_delivery_report` | Tidak | Boolean; default `true` |
| `client_reference` | Tidak | String maksimal 100 karakter; referensi aplikasi tanpa OTP atau data sensitif |

Body JSON tidak menerima field tambahan yang tidak dikenal.

## 9. Kode respons dan penanganan error

| HTTP | Contoh kode error | Tindakan |
| --- | --- | --- |
| `200` | Replay idempotent | Request sebelumnya sudah tercatat; pakai `message_id` yang sama |
| `202` | Pesan baru diterima | Simpan `message_id`, lalu cek status |
| `400` | `INVALID_JSON`, `INVALID_IDEMPOTENCY_KEY`, `INVALID_LIMIT`, `INVALID_CURSOR` | Perbaiki format request sebelum mengulang |
| `401` | `UNAUTHORIZED` | Periksa header/token; buat atau gunakan key aktif |
| `403` | `FORBIDDEN`, `NETWORK_NOT_TRUSTED` | Periksa scope key dan allowlist jaringan |
| `404` | `NOT_FOUND` | ID tidak dikenal atau bukan milik client ini |
| `409` | `IDEMPOTENCY_CONFLICT` | Jangan ubah payload dengan key lama; evaluasi request yang sudah tercatat |
| `413` | `PAYLOAD_TOO_LARGE` | Kurangi ukuran request |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Gunakan `Content-Type: application/json` |
| `422` | `INVALID_RECIPIENT`, `INVALID_SIM_ID`, `MESSAGE_TOO_LONG`, dan sejenisnya | Perbaiki field yang ditolak |
| `429` | `RATE_LIMITED` | Hormati `Retry-After` bila tersedia; pertahankan key dan payload untuk retry request yang sama |
| `503` | `SIM_UNAVAILABLE`, `SIM_PERMISSION_REQUIRED`, `QUEUE_FULL`, `QUEUE_UNAVAILABLE` | Periksa status/SIM/izin/kapasitas; jangan mengganti key jika request yang sama mungkin sudah tercatat |

Respons error berbentuk `{ "error": { "code", "message", "request_id", "details" } }`. Gunakan `request_id` untuk mengidentifikasi error tanpa mencatat token, OTP, isi SMS, atau nomor tujuan lengkap. Jangan melakukan retry tanpa batas; gunakan backoff, batas percobaan, dan idempotency.

## 10. Keamanan OTP

- Gunakan key dengan scope minimum. Untuk alur kirim dan cek status, gunakan `messages:write` dan `messages:read`; tambahkan `system:read` hanya bila integrasi perlu membaca status gateway/SIM.
- Simpan API key di secret manager atau konfigurasi rahasia server, bukan di aplikasi browser/mobile klien.
- Jangan log OTP, isi SMS, API key, atau nomor tujuan lengkap. Hindari menyimpan body request di APM/proxy.
- Batasi rate pengiriman dan percobaan verifikasi di backend; satu key idempotency bukan pengganti rate limit bisnis per akun/nomor/IP.
- SMS OTP memiliki risiko SIM swap, pengalihan nomor, dan keterlambatan operator. Gunakan OTP singkat masa berlakunya dan jangan jadikan SMS satu-satunya faktor untuk aksi berisiko tinggi.
- API gateway ini berjalan pada perangkat Android dan operator dapat mengenakan biaya SMS. Pantau penggunaan SIM dan ketersediaan perangkat.

## 11. Postman

Repository menyertakan collection di [`postman/OpenSMS-Next.postman_collection.json`](../postman/OpenSMS-Next.postman_collection.json). Import ke Postman, ubah `baseUrl` ke alamat gateway aktual, isi `apiKey` secara lokal, dan periksa recipient sebelum menjalankan request **Kirim SMS**. Collection tersebut adalah contoh pengujian manual dan mengirim SMS nyata; jangan membagikan environment/collection yang telah berisi API key atau OTP.

## Sumber kontrak

Panduan ini mengikuti route dan schema di [`openapi.yaml`](../openapi.yaml) serta implementasi Ktor di `android-native/app/src/main/java/org/daslearning/opensms/next/api/OpenSmsApiServer.kt`. Jika kontrak berubah, perbarui panduan ini bersama OpenAPI.
