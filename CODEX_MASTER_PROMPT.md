# MASTER PROMPT UNTUK CODEX — OpenSMS Next

Copy seluruh prompt berikut ke Codex setelah repo dan file handoff tersedia. Pada fase pertama, minta Codex mengerjakan **Phase 0 saja** untuk menghindari implementation drift.

---

Saya ingin membangun **OpenSMS Next**, rewrite total aplikasi OpenSMS dari Python/Kivy menjadi **native Android Kotlin**, berbasis repository referensi: https://github.com/daslearning-org/OpenSMS

Dokumen yang WAJIB dibaca sebelum mulai:
- `PRD_OpenSMS_Next_Kotlin.md` (source of truth requirements, milestones, acceptance criteria)
- `openapi.yaml` (kontrak API REST)
- `AGENTS.md` (engineering rules)

**TUJUAN:** menghasilkan Android SMS Gateway internal yang aman, stabil, mendukung REST API, Bearer auth, durable queue, idempotency, multi-SIM, multipart SMS, sent/delivery callbacks, status/history, dan lifecycle sesuai batasan Android 12–17.

**RULES NON-NEGOTIABLE**

1. Implementasi aplikasi baru 100% Kotlin + Jetpack Compose Material3 + Room + Coroutines + Hilt + DataStore. Embedded HTTP server dengan Ktor bila teruji kompatibel pada Android; jika gagal, dokumentasikan spike dan gunakan alternatif Kotlin/Java teruji tanpa mengubah kontrak domain/API.
2. Jangan menghapus aplikasi upstream. Buat `android-native/` dengan module Gradle baru. Pertahankan license/atribusi Apache-2.0 bila kode digunakan.
3. Jangan membuat dummy implementation yang mengaku SMS terkirim. Pisahkan `QUEUED`, `SENT`, `DELIVERED`, `FAILED`, `UNKNOWN`, `CANCELED`.
4. Setiap `202 Accepted` hanya setelah transaksi Room sukses. Enforce `Idempotency-Key` dan satu dispatch per SIM secara terkontrol.
5. Gunakan `SmsManager` yang mengacu pada subscription aktif, `divideMessage` untuk multipart, explicit unique PendingIntent untuk callback tiap part. Jangan diam-diam retry status `UNKNOWN`.
6. Keamanan default OFF: tidak ada listener sejak install. LAN server hanya dimulai oleh pengguna pada jaringan terpercaya; validasi permission API 37 (`ACCESS_LOCAL_NETWORK`), key scopes, rate/queue limits, dan response error standar.
7. Jangan berusaha mengakali Android foreground-service types atau Google Play SMS permission policy. Dokumentasikan eligibility `remoteMessaging`/opsi lain, service timeout/restriction, dan risiko operasi nonstop. Jika tidak sah/layak, gunakan session-based behavior dan sebutkan keterbatasan. Tidak ada janji 24/7.
8. Pengujian unit, integrasi, dan lint wajib. Physical-device SMS/Android 17 tests harus ditandai belum diuji bila perangkat tidak ada. Jangan klaim sudah berfungsi di hardware hanya karena build sukses.
9. Tulis dokumentasi dengan jelas dalam bahasa Indonesia (kode identifier dan API tetap English). Hindari scope creep ke P1/P2.
10. Jangan push, merge ke main, membuat release public, atau commit signing keystore tanpa instruksi eksplisit.

**RUTE IMPLEMENTASI (HARUS BERTAHAP)**

- PHASE 0 — Audit existing repository, mapping fungsi Python ke Kotlin; buat `docs/REPO_AUDIT.md`, `docs/RISKS.md`, dan ADR tentang Android foreground service + kebijakan SMS. Setup minimal app `android-native/` dan lakukan spike embedded HTTP server (GET health) dengan bukti build/test. STOP setelah Phase 0 dan laporkan hasil + blocker sebelum Phase 1.
- PHASE 1 — Compose app skeleton + onboarding/UI state + Room/DataStore + Hilt + tes dasar.
- PHASE 2 — SMS domain, queue, idempotency, status, subscription selection, multipart per-part callback, recovery; tests dengan fake sender dan physical-device test plan.
- PHASE 3 — Ktor API v1 + Bearer auth + scopes + key rotation + rate limit + pagination + OpenAPI contract tests.
- PHASE 4 — Service lifecycle compliant, network trust policy/API37 permission, complete dashboard/log/diagnostics/history, recovery and acceptance tests.
- PHASE 5 — hardening, CI, docs, README/cURL, security, Android device matrix, APK debug/release signing guidance.

**PERMINTAAN EKSEKUSI SEKARANG:** Kerjakan **PHASE 0 SAJA**. Sebelum menulis kode, tampilkan temuan source reference, implementation plan terinci dan ADR candidates. Kemudian buat skeleton minimal yang **buildable**. Run test/build jika toolchain tersedia. Tulis `docs/IMPLEMENTATION_STATUS.md` dengan tabel AC done/not run/blocked. Pastikan gradle project tetap bisa dibuka di Android Studio. Setelah selesai, berikan ringkasan files changed, perintah & output test, batasan yang belum teruji, dan proposal langkah Phase 1. JANGAN meneruskan Phase 1 otomatis.

---

## Prompt follow-up fase berikutnya (gunakan setelah menerima hasil fase sebelumnya)

> Lanjutkan **Phase N** sesuai `PRD_OpenSMS_Next_Kotlin.md` dan `AGENTS.md`. Baca implementasi Phase N-1, pastikan build/test baseline, implement hanya fitur Phase N, update OpenAPI/ADR bila diperlukan, jalankan test, dan tulis laporan file yang berubah, acceptance criteria, hasil testing, risiko, dan item blocked. Jangan melanjutkan fase lain tanpa instruksi.

## Checklist sebelum menyerahkan ke Codex

- Clone repo OpenSMS ke workspace kerja Anda atau fork yang punya izin akses.
- Salin `PRD_OpenSMS_Next_Kotlin.md`, `openapi.yaml`, `AGENTS.md`, `CODEX_MASTER_PROMPT.md` ke root repo kerja.
- Minta Codex membaca semua dokumen dan mengerjakan **Phase 0 saja**.
- Nilai ADR tentang foreground service, izin Google Play, Ktor, dan Android 17 **sebelum** meminta 24x7 gateway.
