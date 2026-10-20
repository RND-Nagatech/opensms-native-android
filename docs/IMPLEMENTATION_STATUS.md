# Implementation Status

Tanggal pembaruan: 2026-10-20

## Status fase

| Fase | Status | Hasil dan batas |
|---|---|---|
| Phase 0 — discovery dan spike | Done | Audit handoff, risk ledger, ADR awal, Gradle/Ktor loopback health check. Handoff ini bukan checkout source upstream. |
| Phase 1 — aplikasi native | Done | Compose/Material3, navigation, onboarding, dashboard, diagnostics, settings, Hilt, Room, DataStore, key lifecycle, fresh install OFF. |
| Phase 2 — SMS core dan durable queue | Implemented; device verification pending | Domain/state machine, Room transaction/idempotency, SIM-specific adapter, per-part callbacks, multipart, recovery UNKNOWN, tanpa retry otomatis setelah dispatch ambigu. Fake-backed instrumented coverage tersedia. |
| Phase 3 — API dan policy | Implemented; contract verification pending | Ktor v1, Bearer token verifier, scopes, ownership, key rotation/revocation, limiter, capacity, filters/cursor, errors, validation. Sebagian route diuji pada emulator. |
| Phase 4 — lifecycle dan diagnostics | Implemented; physical background verification pending | User-started `specialUse` foreground service + ongoing notification/Stop action, mode tersimpan untuk sticky/boot restore, trust/allowlist beberapa CIDR privat dipisahkan koma, Android 17 permission flow, dashboard/history/diagnostics. Beranda menunjukkan alamat LAN saat aktif. Soak/Doze/OEM uptime belum dibuktikan. |
| Phase 5 — hardening/release | Partial | CI, docs, ADR, dependency locking/verification metadata, instrumented tests, dan redacted CSV audit export disiapkan. Physical device matrix, Android 17 LAN, live SMS callback, dan Google Play eligibility masih menjadi release gates. |
| Phase 6 — P1/P2 | Out of scope | Tidak dikerjakan sesuai PRD. |

Fase 1–5 telah memiliki implementasi lokal yang dapat direview. Ini bukan klaim siap rilis: gerbang perangkat fisik dan distribusi masih terbuka. Hasil verifikasi final dari sesi ini dicatat di bawah setelah perintah selesai.

## Acceptance criteria

| AC | Status | Bukti / batas |
|---|---|---|
| AC-01 fresh install OFF | Implemented | Tidak ada listener autostart; UI memerlukan aksi Mulai. Instrumented launch test memeriksa UI awal. |
| AC-02 permission denial | Partial | Runtime permission diminta pada Start dan error disampaikan; denial/revoke matrix OS fisik belum diuji. |
| AC-03 SIM unavailable | Partial | Queue dispatcher memvalidasi subscription dan API memetakan unavailable; belum dites pada radio/SIM fisik tanpa capability. |
| AC-04 Room commit sebelum 202 | Implemented | Penerimaan menunggu repository/Room transaction; API contract + repository tests. |
| AC-05 idempotency replay | Implemented | Key dan fingerprint tersimpan transaksional; contract test. |
| AC-06 idempotency conflict | Implemented | Payload berbeda untuk key sama ditolak; contract test. |
| AC-07 auth reject | Implemented | Missing/invalid/revoked bearer ditolak; API contract test. |
| AC-08 scope enforcement | Implemented | Endpoint memeriksa scope; API contract test. |
| AC-09 queue/rate cap | Implemented | Batas antrean dan limiter persisten; limiter instrumented test. |
| AC-10 sent callback | Partial | Callback per part memutakhirkan state; fake sender test. SMS operator/OEM nyata belum diuji. |
| AC-11 delivery callback | Partial | Delivery callback per part dan agregasi tersedia; radio/OEM nyata belum diuji. |
| AC-12 per-part failure | Partial | Error per-part disimpan/agregasi; coverage fake; belum diuji radio fisik. |
| AC-13 multipart Unicode | Partial | Menggunakan `SmsManager.divideMessage` per subscription dan callback ID tiap bagian; belum ada pembuktian modem fisik. |
| AC-14 dual SIM | NOT RUN | Adapter menggunakan subscription ID pilihan, tetapi perlu perangkat dual SIM untuk validasi. |
| AC-15 recovery ke UNKNOWN | Partial | Attempt DISPATCHING durable dan recovery ambigu ke UNKNOWN tanpa resend; emulator test tersedia, force-kill matrix belum diuji. |
| AC-16 Stop menutup socket | Partial | Sesi bound berhenti dan server ditutup; belum ada test UI/service lifecycle khusus untuk semua race. |
| AC-17 port conflict | NOT RUN | Belum ada instrumented test bind conflict/actionable UI. |
| AC-18 perubahan jaringan | Partial | Validasi Wi-Fi aktif, IPv4 private CIDR dan peer; perubahan jaringan fisik belum diuji. |
| AC-19 Android 17 local network permission | NOT RUN | Flow `ACCESS_LOCAL_NETWORK` disediakan; tidak ada emulator/perangkat API 37 dalam matrix sesi. |
| AC-20 batasan background | Partial | FGS `specialUse` dimulai eksplisit, notifikasi ongoing, restore process/boot bersyarat pada sesi yang pernah diaktifkan. Doze, force-stop, OEM policy, reboot restore, dan uptime fisik belum diverifikasi. |
| AC-21 redacted log export | Implemented | SAF CSV export dibatasi 200 audit event dan hanya menulis timestamp, actor category, action, result. Exporter allowlists kode dan menghilangkan entity ID serta free-form metadata; unit test memeriksa body/token/destination bait tidak bocor. |
| AC-22 Room migrations | Partial | Tidak memakai destructive migration; schema saat ini v1. Upgrade migration antar versi belum dapat diuji karena belum ada versi sebelumnya. |
| AC-23 API contract | Partial | Contract tests untuk alur inti tersedia; belum terbukti meliputi seluruh openapi.yaml, casing, validation dan error matrix. |
| AC-24 limiter bertahan restart | Partial | Rate bucket disimpan di Room dan instrumented test tersedia; restart process sesungguhnya perlu device run. |
| AC-25 physical device matrix | NOT RUN | AVD API 35 hanya mendukung sebagian uji aplikasi; tidak membuktikan radio SMS/API 37/OEM. |
| AC-26 notifikasi | Partial | Sesi aktif memakai notifikasi foreground persisten dengan aksi Stop; fresh install tetap OFF. Permission dan tampilan notifikasi perlu diverifikasi di Infinix/API 37. |
| AC-27 ownership/read privacy | Implemented | Read query dibatasi client owner; contract/repository test. |
| AC-28 validasi sebelum enqueue | Partial | Parsing/validasi request dan ukuran body ada; matriks eksplisit invalid recipient, pesan kosong, multipart oversized, SIM invalid perlu dilengkapi. |

## Policy dan blocker rilis

- Default install OFF, token tidak disimpan plaintext, key baru ditampilkan sekali, dan enkripsi field memakai Android Keystore.
- Room menjadi batas commit sebelum response `202`; state `UNKNOWN` tidak otomatis dikirim ulang.
- Listener hanya dalam sesi yang dimulai pengguna. FGS `specialUse` menahan sesi saat UI tidak terlihat dan mencoba memulihkan setelah process death/reboot. Ini best-effort; Doze/OEM/force-stop dapat menghentikannya dan Play eligibility belum disetujui. Lihat `docs/ADR-001-background-execution.md`.
- LAN memerlukan kebijakan CIDR privat eksplisit dan permission local network pada API 37. Ini masih perlu validasi perangkat API 37.
- Aplikasi belum mengimplementasikan role default SMS. Distribusi Google Play yang memerlukan `SEND_SMS` adalah blocker kebijakan dan harus ditinjau sebelum rilis publik.
- Belum ada TLS. Jangan paparkan API pada jaringan tidak terpercaya atau WAN.
- AC-14/17/19/25 device evidence dan pengujian SMS operator masih terbuka.

## Perintah verifikasi

Untuk bug layar terkunci di Infinix X6887 (2026-10-20), gateway LAN diuji saat baterai disimulasikan tidak dicas. Setelah layar off sekitar 65 detik, log XOS menunjukkan UID aplikasi masuk `FrozenState` dan OEM memaksa melepas `PARTIAL_WAKE_LOCK`. Port TCP masih menyelesaikan handshake, tetapi GET `/health` timeout 5 detik tanpa respons. Jadi foreground service dan wake lock saja belum menjaga request tetap diproses pada setelan XOS perangkat ini. Implementasi tidak lagi meminta `WifiLock` pada API 29+ karena mode yang tersedia tidak berfungsi saat layar mati; UI memberi langkah untuk mengizinkan background/autostart dan set baterai ke Tanpa batas. `assembleDebug lintDebug` sukses dan APK hasil perubahan dipasang pada Infinix. Validasi ulang setelah perubahan setelan OEM belum dilakukan; unit/instrumented suite tidak dijalankan pada perubahan ini. Doze/Low Power Standby tetap dapat menangguhkan jaringan.

Untuk status `SMS_CALLBACK_FAILURE` di Infinix X6887 (2026-10-20), pemeriksaan lokal pada metadata bagian pesan menemukan callback terkirim bernilai `0` untuk pesan yang ditampilkan sebagai terkirim oleh aplikasi Messages. Kode lama hanya menerima `Activity.RESULT_OK` (`-1`), sehingga salah mengubah callback XOS tersebut menjadi FAILED; kode lama juga membiarkan callback laporan delivery gagal menurunkan status pesan utama. Callback terkirim bernilai `0` kini diterima sebagai sukses pada adapter, sementara callback delivery bernilai `0` ditandai UNKNOWN agar tidak mengklaim penerimaan tanpa status report. Migrasi Room 2→3 memperbaiki rekaman lama secara kondisional hanya bila seluruh bagian memiliki callback terkirim 0 dan status lama SMS_CALLBACK_FAILURE. APK baru terpasang; layar riwayat fisik menunjukkan rekaman terbaru SENT dengan delivery UNKNOWN, dan rekaman sebelumnya SENT dengan delivery PENDING. Tidak mengirim SMS uji baru; unit/instrumented suite tidak dijalankan.

Untuk bug swipe task di Infinix (2026-10-20), log fisik mengonfirmasi XOS membunuh PID aplikasi (`Swipe-up clean`). Service meminta Android mengirim ulang intent START beserta mode (`START_REDELIVER_INTENT`) dan manifest menyatakan `stopWithTask=false`. Uji fisik menunjukkan XOS menjadwalkan restart sekitar 29 detik setelah swipe, lalu listener port 9292 kembali menerima koneksi. Pemulihan berhasil tetapi ada jeda; mengunci aplikasi di Recent adalah workaround agar XOS tidak menjalankan swipe-up clean. Ini tetap best-effort terhadap kebijakan OEM.

Jalankan dari `android-native/` dengan Android Studio JBR dan Android SDK terpasang:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

Hasil akhir sesi ini: `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest` sukses. Unit test: 4 test, 0 gagal. Instrumented: 14 test, 0 gagal pada AVD `Medium_Phone_API_35` (Android 15/API 35). Lint: `No issues found`. Debug APK dibuat di `android-native/app/build/outputs/apk/debug/app-debug.apk` (40 MB). Gradle mengeluarkan peringatan deprecation untuk kompatibilitas Gradle 10 dan JVM dependency protobuf mengeluarkan warning `Unsafe`; keduanya tidak menggagalkan build. Jangan menafsirkan compile atau emulator sebagai bukti SMS radio/perangkat API 37.

Untuk pembaruan UI kunci/API LAN (2026-10-20), build dan lint sukses. Unit/instrumented suite lulus pada emulator API 35. Pada Infinix X6887 Android 16, 13/14 instrumented tests lulus; `AppLaunchTest` gagal karena Compose test runner tidak menemukan hierarchy, sedangkan API/Room/Ktor tests lulus. APK debug terbaru dipasang pada perangkat dan `MainActivity` berhasil dibawa ke foreground lewat ADB. Kegagalan test harness fisik tetap perlu diselidiki; ini bukan verifikasi SMS radio/LAN end-to-end.

Untuk perubahan FGS background (2026-10-20), `assembleDebug` dan `lintDebug` berhasil. Unit/instrumentation tests dan soak test fisik untuk lock screen, Doze, process death, reboot, notifikasi, dan akses LAN belum dijalankan. FGS memakai `specialUse`, mulai hanya setelah aksi pengguna, dan mencoba pulih dari process death/boot jika sesi sebelumnya masih tersimpan. Play Console eligibility belum diputuskan.

Untuk allowlist LAN multi-subnet, halaman Kunci API kini menerima beberapa CIDR IPv4 privat yang dipisahkan koma, memvalidasi semua entri sebelum menyimpan, dan mengizinkan koneksi bila peer cocok dengan salah satu subnet. `lintDebug` dan `assembleDebug` berhasil; unit/instrumented tests tidak dijalankan pada perubahan ini.
