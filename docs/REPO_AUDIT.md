# Audit Repository — Phase 0

Tanggal audit: 2026-10-20  
Status: audit terbatas pada paket handoff; belum ada checkout kode upstream.

## Temuan repository kerja

Sebelum Phase 0, root hanya berisi `AGENTS.md`, `PRD_OpenSMS_Next_Kotlin.md`, `CODEX_MASTER_PROMPT.md`, `README_HANDOFF.md`, dan `openapi.yaml`. Phase 0 menambahkan `docs/` dan `android-native/`. Direktori ini bukan Git checkout (`git status` mengembalikan `fatal: not a git repository`). Tidak ditemukan implementasi Python/Kivy, package license, Gradle project, atau riwayat commit di workspace awal. Karena itu, audit ini tidak mengklaim sebagai inventaris lengkap repository upstream dan tidak menyalin kode upstream.

## Pemetaan sumber upstream yang diperiksa

Rujukan upstream pada PRD: `https://github.com/daslearning-org/OpenSMS` (branch `main`, diperiksa 2026-10-20).

| Sumber | Perilaku yang terlihat | Implikasi untuk rewrite |
|---|---|---|
| `app/services/postApi.py` | FastAPI mengikat default ke `0.0.0.0:9292`; `POST /sms/` memakai Bearer token; key dibuat memakai `secrets.token_urlsafe(32)` dan SHA-256-nya disimpan; handler langsung memanggil fungsi kirim SMS dan mengembalikan `{"stat": bool}`; `/control/` tidak memiliki dependency autentikasi. | Jangan pertahankan bind terbuka/default maupun endpoint kontrol. Gunakan kontrak v1, auth dan policy terpisah, enqueue persisten, dan response yang tidak menyebut SMS terkirim. |
| `app/services/smsApi.py` | Android memakai `SmsManager.getDefault()` dan `sendTextMessage(..., null, null)`; `true` hanya berarti pemanggilan tidak melempar exception. Cabang non-Android juga mengembalikan `true`. | Gunakan subscription aktif eksplisit dan sent/delivery callback per part. Invocation bukan bukti terkirim; jangan mempertahankan stub sukses lintas platform. |
| `app/services/appSvc.py` | Server dikelola lewat `request.json`/`resp.json`; thread tak berujung membaca file tiap 0,2 detik dan menyalakan/mematikan server. | Ganti polling dengan lifecycle eksplisit yang dimiliki app/service; jangan tiru loop polling. |
| `LICENSE` | File upstream yang dirujuk mengidentifikasi Apache License 2.0 dan copyright `daslearning.in` (2026). Tidak ada file `LICENSE` di paket handoff. | Phase 0 tidak menyalin kode. Jika source upstream digunakan atau didistribusikan, sertakan license dan attribution yang diwajibkan serta notices relevan. |

Pemetaan ini hanya mencakup tiga file yang disebut PRD. Belum dilakukan audit semua routes, UI, packaging, test, dan lisensi penuh upstream.

## Kontrak dan target implementasi

- `openapi.yaml` adalah kontrak REST; PRD adalah kontrak produk dan keamanan.
- Target scaffold: Kotlin Android, `minSdk 28`, `compileSdk/targetSdk 37`; listener belum dimulai otomatis.
- `applicationId` scaffold `org.daslearning.opensms.next` masih provisional; branding dan package ID adalah OPEN-1 di PRD dan belum dianggap keputusan produk.
- Phase 0 membuktikan apakah Ktor CIO dapat dibangun dan melayani `GET /health` di loopback. Itu belum membuktikan LAN permission, lifecycle foreground service, kinerja idle, SMS radio, ataupun kesiapan produksi.
- Untuk API 37, direct inbound/outbound LAN traffic memerlukan `ACCESS_LOCAL_NETWORK` runtime. Scaffold belum meminta izin ini karena probe hanya loopback; fitur LAN harus menambah flow izin dan uji grant/deny/revoke.

## Environment build yang ditemukan

- Android SDK platform `android-37.0`, build-tools `36.0.0`, dan Android Studio JBR Java `25.0.3` terpasang.
- `java` global tidak terkonfigurasi; JDK Studio JBR 25.0.3 dipakai melalui `JAVA_HOME`.
- Gradle global tidak tersedia saat audit dimulai. Gradle Wrapper sudah dibuat, menggunakan distribusi langsung dengan SHA-256 terverifikasi.
- `adb devices -l` tidak menunjukkan emulator/perangkat aktif.
- Kombinasi build dipin ke AGP `9.4.1` dan Gradle `9.8.2`; AGP 9.4 mendukung API 37, sedangkan Gradle 9.8.2 kompatibel dengan JDK 25 yang tersedia. JDK 17 adalah baseline minimum AGP.

## Gap Phase 0

1. Tidak ada clone upstream di workspace, sehingga audit kode lengkap masih terbatas pada tiga file yang dirujuk PRD.
2. Gradle Wrapper dibuat dan dijalankan sukses dengan Gradle 9.8.2; checksum distribusi dipin di `gradle-wrapper.properties`.
3. Ktor loopback probe lulus di emulator Android 15/API 35. Akses LAN API 37, Android 17, SMS radio, dan device matrix masih belum diuji.
4. Eligibility service type untuk listener kontinu belum dipastikan; lihat ADR-001.

## Referensi primer

- Upstream API: <https://raw.githubusercontent.com/daslearning-org/OpenSMS/main/app/services/postApi.py>
- Upstream SMS: <https://raw.githubusercontent.com/daslearning-org/OpenSMS/main/app/services/smsApi.py>
- Upstream lifecycle: <https://raw.githubusercontent.com/daslearning-org/OpenSMS/main/app/services/appSvc.py>
- Android local network permission: <https://developer.android.com/privacy-and-security/local-network-permission>
- Android 17 behavior: <https://developer.android.com/about/versions/17/behavior-changes-17>
- AGP 9.4 compatibility: <https://developer.android.com/build/releases/agp-9-4-0-release-notes>
- Google Play SMS permissions policy: <https://support.google.com/googleplay/android-developer/answer/10208820>
- Android foreground services: <https://developer.android.com/develop/background-work/services/fgs/service-types>
- Ktor release history: <https://ktor.io/docs/releases.html>
