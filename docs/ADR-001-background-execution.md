# ADR-001: Background execution dan lifecycle gateway

- **Status:** Diamendemen 2026-10-20; FGS `specialUse` untuk sesi gateway yang dimulai pengguna, dengan uptime best-effort. Kelayakan distribusi Play dan verifikasi fisik tetap terbuka.
- **Tanggal:** 2026-10-20
- **Konteks:** Produk memerlukan HTTP listener yang dapat menerima pesan dari LAN, sementara Android membatasi start dan durasi foreground service. Target produk adalah Android 12–17, dengan target SDK 37. Produk tidak boleh menjanjikan 24/7 atau mengakali tipe FGS.

## Keputusan

1. Jangan deklarasikan atau mulai foreground service pada scaffold Phase 0.
2. Buka socket hanya atas aksi eksplisit pengguna; listener tidak aktif pada fresh install dan tidak otomatis pulih setelah pengguna menekan Stop.
3. Spike awal Ktor menggunakan loopback. Implementasi MVP memakai Ktor CIO untuk API v1 dan memulai listener LAN hanya pada sesi yang dimulai pengguna setelah kebijakan jaringan/permission lolos. Ini belum membuktikan kompatibilitas seluruh perangkat fisik.
4. Keputusan awal menggunakan bound service tanpa FGS kini digantikan oleh amendment di bawah. Layanan gateway berjalan sebagai started + bound foreground service setelah aksi Mulai yang eksplisit.
5. Jangan gunakan `dataSync` untuk listener jangka panjang. `remoteMessaging` didefinisikan untuk transfer pesan teks antarperangkat guna kesinambungan tugas messaging; OpenSMS menerima request dari klien LAN lalu mengirim melalui SIM, sehingga kecocokan tipe itu belum dapat dibuktikan.
6. Target SDK 37 meminta `ACCESS_LOCAL_NETWORK` sebelum binding LAN. Binding hanya ke alamat IPv4 Wi-Fi aktif dan akses HTTP LAN dibatasi subnet privat IPv4 yang dimasukkan pengguna. Nilai awal allowlist kosong.
7. Jangan deklarasikan atau memulai FGS sampai pemilik produk dan review policy membuktikan eligibility, prasyarat, notifikasi, dan distribusinya. Build MVP ini tidak membuat klaim gateway 24/7.
8. Google Play saat ini membatasi `SEND_SMS` untuk aplikasi yang terdaftar sebagai default SMS/Assistant handler; OpenSMS tidak mengimplementasikan role handler. Distribusi melalui Play adalah blocker terpisah. APK internal/sideload tidak menyiratkan kesiapan Play.

## Alasan dan bukti

- Android membatasi start FGS dari background untuk target Android 12+ dan memeriksa tipe serta permission terkait pada Android 14+.
- Android mendefinisikan `remoteMessaging` secara sempit sebagai transfer pesan teks antarperangkat untuk kesinambungan tugas messaging, bukan izin umum agar server SMS gateway selalu hidup.
- Android 17 memblokir akses jaringan lokal secara default untuk target SDK 37+ sampai izin runtime diberi.
- Kebijakan Play yang diperiksa saat Phase 4 menyatakan grup permission SMS termasuk `SEND_SMS` perlu aplikasi terdaftar sebagai default SMS/Assistant handler.

## Konsekuensi

- Tidak ada janji SLA atau uptime absolut pada perangkat Android konsumen. Doze dapat menangguhkan akses jaringan, OEM dapat membatasi aplikasi, dan pengguna/system dapat menghentikan proses.
- Ktor API integration test pada AVD Android 15 membuktikan route/auth/queue, tetapi tidak membuktikan LAN, izin API 37, radio SMS, FGS, atau uptime fisik.
- Google Play release diblokir sampai strategi role/permission SMS yang sesuai policy disepakati dan diuji.
- Jika bisnis memerlukan SLA ketat, pemilik produk perlu memilih perangkat terkelola/MDM atau arsitektur gateway eksternal melalui perubahan desain.

## Amendment — FGS untuk gateway latar belakang

Atas permintaan pemilik produk, implementasi sekarang memakai foreground service bertipe `specialUse` untuk sesi gateway:

1. Pengguna memulai layanan dari Activity yang terlihat. Service segera memanggil `startForeground()` dengan notifikasi ongoing dan aksi **Hentikan**; Stop dari UI maupun notifikasi mengakhiri sesi dan menghapus pilihan pemulihan.
2. `specialUse` dipilih karena socket HTTP gateway yang menerima perintah SMS melalui LAN tidak sesuai dengan tipe khusus lain. `remoteMessaging` pada dokumentasi Android berarti transfer pesan antardevice untuk kesinambungan tugas messaging, bukan listener gateway LAN. Manifest menjelaskan subtype yang dipakai.
3. Mode sesi disimpan lokal untuk memulihkan layanan setelah process death (`START_REDELIVER_INTENT`, dengan fallback membaca sesi tersimpan bila intent kosong) dan boot selesai, tetapi hanya jika pengguna sebelumnya menyalakan gateway. Manifest menyetel `stopWithTask=false`; ini tidak dapat mencegah kebijakan OEM yang membunuh proses. Fresh install tetap OFF dan Stop mematikan pemulihan otomatis.
4. Android menyatakan kasus `specialUse` dan subtype manifest ditinjau untuk distribusi Google Play. Implementasi ini tidak mengklaim telah lolos review Play; distribusi sideload/internal dan izin `SEND_SMS` tetap memiliki batas yang berbeda.
5. Tidak meminta pengecualian battery optimization secara otomatis. Pengguna dapat mengatur baterai/auto-start secara manual pada perangkat, tetapi Doze masih dapat menangguhkan jaringan. Force-stop, kehabisan daya, pergantian Wi-Fi, kebijakan OEM, pencabutan notifikasi/izin, dan reboot yang gagal dipulihkan dapat memutus layanan.
6. Uptime background, pemulihan boot, akses LAN API 37, notifikasi, dan radio SMS wajib diuji pada Infinix fisik dalam soak test. Sampai bukti itu ada, gunakan istilah **berjalan di background dengan pemulihan best-effort**, bukan jaminan 24/7.
7. Reproduksi pada Infinix X6887 (Android 16) menunjukkan launcher XOS mencatat `remove task app with kill!` dan ActivityManager membunuh PID aplikasi dengan alasan `Swipe-up clean`. Perbaikan menggunakan `START_REDELIVER_INTENT` agar mode START terakhir dikirim ulang ketika Android me-restart service; pemulihan tetap perlu validasi fisik karena tindakan OEM dapat menunda atau menekan restart.
8. Sesi LAN memegang `PARTIAL_WAKE_LOCK`, lalu melepasnya saat Stop, startup gagal, atau service dihancurkan. Android 10+ tidak menyediakan `WifiLock` mode yang berguna untuk mempertahankan Wi-Fi ketika layar mati: `WIFI_MODE_FULL` tidak berfungsi sejak API 29 dan mode low-latency dibatasi saat layar menyala. Karena itu `WifiLock` hanya dipakai pada API 28. Partial CPU lock hanya menjaga CPU jika sistem mengizinkannya; Doze dapat mengabaikan wake lock dan menangguhkan jaringan, sementara OEM dapat membekukan aplikasi secara terpisah. Untuk XOS, pengguna perlu mengizinkan aktivitas latar belakang/autostart dan memilih baterai tanpa batas. Ini tetap tidak memberi SLA.

Rujukan utama untuk amendment:

- <https://developer.android.com/develop/background-work/services/fgs/service-types>
- <https://developer.android.com/develop/background-work/services/fgs/launch>
- <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- <https://developer.android.com/training/monitoring-device-state/doze-standby>

## Sumber resmi

- <https://developer.android.com/develop/background-work/services/fgs/service-types>
- <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- <https://developer.android.com/develop/background-work/services/fgs/changes>
- <https://developer.android.com/develop/background-work/services/fgs/service-types>
- <https://developer.android.com/privacy-and-security/local-network-permission>
- <https://developer.android.com/about/versions/17/behavior-changes-17>
- <https://support.google.com/googleplay/android-developer/answer/16558241>
