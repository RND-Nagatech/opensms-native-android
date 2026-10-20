# PRODUCT REQUIREMENTS DOCUMENT (PRD)

# OpenSMS Next — Native Android SMS Gateway (Kotlin)

| Atribut | Keputusan |
|---|---|
| Versi PRD | 1.0 |
| Tanggal | 20 Oktober 2026 |
| Status | Baseline implementasi; keputusan terbuka ditandai `OPEN` |
| Repo referensi | https://github.com/daslearning-org/OpenSMS |
| Produk baru | **OpenSMS Next** (working name) |
| Platform | Android native Kotlin |
| Distribusi pertama | APK private / internal / perangkat milik organisasi |
| Bahasa antarmuka | Indonesia, English-ready |
| Target MVP | SMS outbound REST API aman pada LAN tepercaya, queue persisten, status SMS akurat, multi-SIM |
| Di luar scope MVP | SMS marketing masif, bulk broadcast, OTP interception, dashboard cloud, bypass pembatasan OS |

> **Instruksi untuk coding agent:** dokumen ini adalah product contract. Implementasi harus menyelesaikan semua P0 serta acceptance criteria sebelum menambah P1/P2. Jangan mengasumsikan feature permission tersedia/diizinkan di semua device. Catat blockers platform secara eksplisit.

## 1. Ringkasan Eksekutif

OpenSMS Next adalah penulisan ulang native Kotlin dari aplikasi OpenSMS berbasis Python/Kivy. Android bertindak sebagai gateway SMS menggunakan SIM fisik/eSIM aktif dan mengekspos REST API kepada aplikasi tepercaya pada jaringan lokal. Fokus produk: keamanan, status pengiriman yang benar, keandalan antrean dan recovery, operasi multi-SIM, dan diagnosa operasional.

Pelanggan sasaran adalah administrator sistem internal/SMB/developer yang **memiliki izin untuk mengirim SMS ke penerima**. Produk bukan alat spam/OTP interception. Penerapan harus mematuhi aturan operator, kebijakan platform, regulasi, dan consent penerima.

### 1.1 Masalah pada proyek asal

Hasil pemeriksaan statis terhadap source referensi pada 20 Oktober 2026:

- `app/services/postApi.py`: FastAPI `POST /sms/`, Bearer key, default bind `0.0.0.0:9292`; response `{ "stat": bool }`.
- `app/services/smsApi.py`: `SmsManager.getDefault().sendTextMessage(..., null, null)`; keberhasilan hanya berarti pemanggilan tidak melempar exception, **bukan bukti delivery**.
- `app/services/appSvc.py`: server dikontrol via polling file JSON; loop setiap 0,2 detik.
- Tidak terlihat persistent SMS job queue, idempotency, multi-part results, pilihan subscription, audit trail terstruktur, atau kontrak API versioned.

**Konsekuensi:** Rewrite native, bukan penerjemahan Python satu-banding-satu. Tetap pertahankan konsep gateway lokal serta kompatibilitas API lama sebagai opsi nanti.

### 1.2 Tujuan dan metrik keberhasilan (target desain; wajib diverifikasi di perangkat)

1. **Reliability:** 100% request yang mendapat HTTP `202` telah disimpan persisten ke SQLite sebelum respons dikirim.
2. **Idempotency:** request ulang dengan key dan payload sama menghasilkan **satu** pekerjaan SMS selama masa retensi idempotency; konflik payload mendapat `409`.
3. **Visibility:** setiap SMS memiliki ID dan state yang dapat di-query; jangan samakan `SENT` dengan `DELIVERED`.
4. **Privacy/security:** server LAN **OFF secara default**; tidak ada SMS API tanpa autentikasi; API key tidak dicatat sebagai plaintext.
5. **Operability:** UI menunjukkan server state, permission, SIM terpilih, antrean, riwayat, hasil pengiriman, dan alasan error.
6. **Quality:** test unit untuk state machine/idempotency/rate-limit; automated integration untuk HTTP + Room; instrumented test di Android dan manual SMS live.
7. **Performance budget (target, bukan fakta):** respons `POST /v1/messages` p95 < 500 ms pada perangkat target ketika SQLite sehat; server idle tidak melakukan busy-poll; metrik baterai dan RAM diukur pada uji soak.
8. **Uptime:** **tidak menjanjikan always-on/24x7** pada Android konsumen; app memperlihatkan status saat service dihentikan OS dan menyediakan recovery yang kompatibel dengan OS.

## 2. Asumsi, pengguna, dan persona

- Administrator/gateway owner: memasang APK, memberi izin, mengonfigurasi API key dan allowlist, memilih SIM, melihat health/log, menghentikan gateway.
- Integrator backend: mengirim request melalui HTTP dari LAN tepercaya, menyimpan message ID, polling status dan menangani error.
- Operator/support: memeriksa pesan gagal, SIM tidak tersedia, permission dicabut, proses dibunuh, perubahan Wi-Fi.
- Penerima SMS: hanya menerima pesan yang sah/berizin dan tidak pernah harus memasang aplikasi ini.

**Asumsi awal:** satu Android = satu node gateway; satu instance server HTTP; perangkat dengan fitur telephony messaging, SIM aktif, SMS plan yang mengizinkan penggunaan; LAN pribadi. Jumlah pengiriman kecil–menengah dengan kontrol rate untuk mencegah penyalahgunaan dan pemblokiran oleh operator.

**OPEN-1:** nama aplikasi, package ID, branding.
**OPEN-2:** requirement operasional benar-benar 24/7 atau manual-session best effort.
**OPEN-3:** target perangkat/merek, versi Android, 1 atau 2 SIM, apakah MDM/device owner tersedia.
**OPEN-4:** apakah harus mempertahankan `POST /sms/` lama secara default atau hanya melalui compatibility toggle.

## 3. Scope dan Prioritas

### 3.1 P0 — Wajib untuk MVP

| ID | Requirement | Acceptance summary |
|---|---|---|
| P0-01 | Android app 100% Kotlin, Compose Material 3 | UI responsif, dark/light, onboarding selesai |
| P0-02 | Setup & permission | Permission `SEND_SMS`, notifikasi, LAN API 37, penjelasan risiko; graceful denial |
| P0-03 | Start/Stop LAN gateway | OFF default; bind hanya setelah user menekan Start dan syarat terpenuhi |
| P0-04 | REST API v1 dengan bearer token | Unauthorized ditolak; endpoint dibatasi scope |
| P0-05 | Key generation/rotation/revoke | Key dibuat via secure RNG, hanya ditampilkan sekali; simpan verifier terenkripsi/aman |
| P0-06 | SMS send async, queued | HTTP 202 hanya setelah commit Room; pesan diproses serial per SIM |
| P0-07 | SMS status lifecycle | QUEUED, DISPATCHING, SENT, DELIVERED, FAILED, UNKNOWN |
| P0-08 | SMS result callbacks | `sentIntent`/`deliveryIntent` per part, hasil dicatat durable |
| P0-09 | Multi-part SMS | `divideMessage` + `sendMultipartTextMessage`; hitung jumlah part untuk pengguna |
| P0-10 | SIM selection | Default SIM hanya jika valid; pilih subscription ID aktif secara eksplisit |
| P0-11 | Idempotency dan status query | `Idempotency-Key`, `GET /v1/messages/{id}` dengan semantics stabil |
| P0-12 | Rate limit/anti-abuse | Configurable per client + global cap; antrean bounded; tidak ada open relay |
| P0-13 | Audit log & history | Room; redaksi secrets dan body default, opsi retensi |
| P0-14 | Diagnostik & error handling | Screen health/permission/SIM/queue; reason code jelas |
| P0-15 | Crash/restart reconciliation | status in-flight yang ambigu menjadi UNKNOWN; **jangan auto-resend** tanpa kepastian |
| P0-16 | Build, test, docs, CI | `./gradlew ...` test/lint/build, README, API docs, no secrets |

### 3.2 P1 — Stable Gateway (setelah MVP)

- Batch submission kecil yang dibatasi kuota; progress, validasi per item, dan chunking; default **nonaktif** hingga diputuskan kuota/consent.
- Callback/webhook outbound yang ditandatangani (HMAC-SHA256), retry terbatas untuk webhook (bukan SMS).
- Scheduling pengiriman lokal pada waktu tertentu hanya bila batasan background OS memungkinkan; tampilan `DEFERRED` bila tidak dapat menjamin tepat waktu.
- IP/subnet allowlist lengkap, opsi pairing QR yang tidak mengekspos full token pada layar publik.
- Ekspor log CSV/JSON yang diberi redaksi; backup konfigurasi tanpa secrets secara default.
- Dashboard ringkas per hari, per SIM, status breakdown, health indicators.
- Mode LAN secure dengan TLS via reverse proxy/tunnel yang ditentukan admin; jangan menghasilkan sertifikat self-signed otomatis tanpa model trust.
- Pilihan compatibility endpoint `/sms/` dengan toggle admin, response tidak mengklaim delivery.
- Manual retry untuk failure yang dikonfirmasi tidak dikirim; tidak untuk UNKNOWN tanpa peringatan risiko duplikasi.

### 3.3 P2 — Advanced / Enterprise (desain masa depan, bukan MVP)

- Mode **outbound relay** yang aman (device membuat koneksi ke server tepercaya) untuk mengurangi kebutuhan inbound LAN, tanpa janji push reliability dan tanpa menghindari pembatasan OS.
- Fleet management/multiple devices, dashboard backend opsional, policy by client.
- Inbound SMS dan webhook inbound **hanya setelah review izin/kebijakan/consent**; by default jangan baca, simpan, atau forward OTP/2FA. Android 17 memperlambat akses banyak SMS OTP bagi aplikasi non-exempt.
- HA/failover di backend (tidak ada exactly-once lintas operator tanpa mekanisme eksternal).
- Per-SIM quotas, rules, delivery analytics dari operator jika callback tersedia.
- Managed device/enterprise deployment (MDM) bila requirement availability ketat.

### 3.4 Non-goals

Tidak membuat aplikasi default SMS di MVP; tidak membaca inbox; tidak meneruskan SMS OTP; tidak menjanjikan delivery; tidak meretas kebijakan Play/foreground service; tidak meluncurkan API ke internet publik; tidak auto-kirim campaign massal; tidak meminta permission yang tidak digunakan.

## 4. Perilaku Produk dan UX

### 4.1 Navigasi

1. **Dashboard:** chip Server OFF/STARTING/RUNNING/STOPPING/ERROR; tombol Start/Stop; IP+port dan copy URL (hanya ketika RUNNING), QR dokumentasi/pairing opsional, SIM aktif, queue size, success/failure summary, warning keamanan HTTP.
2. **Messages:** paginated history, filter status/SIM/rentang tanggal, detail message ID, destination masked, preview disembunyikan default, part counters, time events, error reason.
3. **SIM:** daftar subscription aktif dengan label disanitasi dan status; pilih default; refresh; unsupported/permission denied states.
4. **API Access:** daftar API clients/key fingerprint, scopes, created/last used/revoked, generate/revoke/rotate, IP allowlist settings, rate limits.
5. **Logs & Diagnostics:** status permission, Android version, local network, battery restrictions info, server bindings, status service, test SMS dengan confirm manual, export sanitized log.
6. **Settings:** port (default 9292), session mode, notification, data retention, queue limits, security posture, UI language.
7. **About:** versi, license Apache-2.0 upstream, attributions, technical troubleshooting.

### 4.2 First-run onboarding

- Screen 1: product purpose, managed gateway risk, SIM & carrier charges; explicit user acknowledgment.
- Screen 2: check hardware `FEATURE_TELEPHONY_MESSAGING` dan current subscriptions; show unsupported state.
- Screen 3: request `SEND_SMS` only when enabling SMS feature. If denied, status `PERMISSION_REQUIRED` and CTA Settings; no dangerous bypass advice.
- Screen 4: request `POST_NOTIFICATIONS` API 33+ with explanation (FGS notification handling is separate), optionally `READ_PHONE_STATE` only if needed for SIM metadata; use least privilege.
- Screen 5: Android 17 target 37+: `ACCESS_LOCAL_NETWORK` where applicable before LAN networking; handle revoke. Do not declare/request it if targetSdk < 37.
- Screen 6: generate client key after admin confirmation; show once, copy + warning; no pre-generated token in repo.
- Screen 7: safety configuration (network trusted, bind address, port, allowlist, rate limit, chosen SIM). Default LAN gateway OFF; require explicit Start.

### 4.3 UX states

- `STOPPED`: no active listening socket.
- `STARTING`: permission/network/port check, notification & service startup.
- `RUNNING`: socket bound; local health and auth available.
- `DEGRADED`: HTTP up but SEND_SMS denied/no SIM; sending returns clear service-unavailable error.
- `STOPPING`: stop accepting new traffic, release socket; queued messages retained.
- `ERROR`: binding failed, socket lost, permission denied, OS restriction.

On app restart show **actual runtime state** instead of persisted optimistic status. If user chooses Stop, do not self-restart in background without new opt-in.

## 5. Architecture

### 5.1 Diagram logical

```text
Admin / Backend (trusted LAN)
   │ HTTP POST with Bearer token + Idempotency-Key
   ▼
[Embedded Ktor Server] ── [Auth, Input Validation, Rate Limiter, Policy]
   │                              │
   │                       [API Access Audit]
   ▼
[Message Use Cases] ──── [Room DB / Transaction / State Machine]
   │                              ▲
   ▼                              │
[Dispatch Coordinator] ───────────┘
   │ (serial queue per SIM; bounded, cancelable)
   ▼
[Android SmsManager per subscription]
   │
   ├── sentIntents ──> [Explicit BroadcastReceiver] ──> Room status
   └── deliveryIntents ─> [Explicit BroadcastReceiver] ─> Room status

[Compose UI] -> [ViewModels] -> [Use Cases] -> [Repository]
[GatewayService] owns Ktor lifecycle & foreground notification when policy permits
[WorkManager] used only for deferrable housekeeping / applicable retry work
```

### 5.2 Modules (modularize only where justified)

Suggested initial single Gradle `:app` with strict packages to avoid excessive scaffolding:

```text
app/src/main/java/<package>/
  App.kt
  MainActivity.kt
  core/common/           Result types, Clock, Logger, validation
  core/security/         TokenVerifier, KeyStore, AccessPolicy, RateLimiter
  core/database/         Room entities, DAOs, migrations
  core/network/          KtorServer, Routes, DTO, ErrorMapper
  domain/model/          Message, SmsPart, Sim, GatewayState
  domain/usecase/        EnqueueMessage, DispatchSms, ReconcileInFlight, ...
  data/repository/       MessageRepository, SettingsRepository
  platform/sms/          AndroidSmsSender, SmsResultReceiver, SimManager
  platform/service/      GatewayService, GatewayLifecycleController
  feature/onboarding/
  feature/dashboard/
  feature/messages/
  feature/sim/
  feature/clients/
  feature/settings/
  feature/logs/
```

If complexity warrants, split `:core`, `:gateway`, `:feature` in P1, not necessary in P0.

### 5.3 Stack and choices

| Layer | Preferred | Notes |
|---|---|---|
| Kotlin | Latest stable, pinned to tested AGP | Avoid floating dependencies |
| Android SDK | `minSdk=28`, `compileSdk/targetSdk=37` baseline | Validate actual toolchain; if 37 unavailable, document temporary deviation; never silently lower security expectations |
| UI | Jetpack Compose / Material3 / Navigation Compose | No XML primary UI |
| Async | Coroutines / Flow | Structured concurrency; never block main thread |
| DI | Hilt | Mockable bindings |
| Storage | Room SQLite | DB schema migrations, DB transactions |
| Preferences | DataStore | Non-secret settings |
| Crypto | Android Keystore + standard cryptographic APIs | Prefer non-exportable key; do not invent cryptography |
| HTTP embedded | Ktor Server (CIO or Netty; choose working Android-compatible option) | Prove Android runtime compatibility in spike |
| HTTP DTO | kotlinx.serialization | Strict validation and controlled error envelope |
| Jobs | WorkManager for deferrable work | Not for continuously listening API server |
| Testing | JUnit, Turbine, MockK or fake, AndroidX test, Compose tests | Integration and physical-device validation |

**Technical spike gate:** Before committing to a server engine, demonstrate Ktor starts, binds, stops, accepts authorized request on Android 14–17 physical test device; measure idle memory; handle lifecycle and Android 17 local network permissions. If Ktor engine incompatible, replace *server adapter only* with a proven Kotlin/Java Android-compatible embedded HTTP library and record ADR. Do not force a broken stack.

## 6. Android Platform, Lifecycle, and Distribution Constraints

### 6.1 SMS permissions/Play policy (release blocker)

- `SEND_SMS` is sensitive and restricted for Google Play publication. Default SMS handler and allowed exception rules apply, subject to review; private/enterprise distribution is product default.
- Do not advise end users to disable Play Protect or bypass security enforcement.
- No SMS read/receive permissions for P0 (so fewer permission surfaces).
- No automated SMS for unknown/unsolicited recipients: administrator manages consent and legal use; throttle and destination allowlist options.
- Ask for permission only on user-facing action with rationale; gracefully degrade on revocation.

### 6.2 Background work

- Android 12+: background FGS launches are restricted. Android 14+: FGS type and corresponding permissions required.
- Android 15+: `dataSync` FGS has 6 hours/24 hours total when in background for target 35+ and cannot be initiated from `BOOT_COMPLETED` in affected cases. **Never mislabel indefinite gateway as dataSync to bypass restrictions.**
- Evaluate `remoteMessaging` only if actual use meets documented message-continuity semantics and relevant policy; do not claim it automatically fits an SMS Gateway. `specialUse` is *not* a loophole. For uncertain type/eligibility, stop at a documented platform-policy spike; do not ship noncompliant FGS.
- Starting server is user-initiated; show ongoing notification with STOP action when FGS is legitimate; handle `ForegroundServiceStartNotAllowedException`, `SecurityException`, process death, service timeout and notification requirements.
- 24x7 availability is not guaranteed on consumer Android. If strict SLA is required, new architectural/product decision required (managed device or external gateway).
- `BOOT_COMPLETED`: permission + platform-policy check only; do not silently start restricted service; user-visible prompt to resume when appropriate.

### 6.3 Android 17 local network and OTP

- On API 37 target, handle `ACCESS_LOCAL_NETWORK` for direct LAN access; test both incoming HTTP listener and relevant outbound calls on Android 17 physical device with granted/denied/revoked permission.
- Server binds to loopback by default for developer mode; LAN bind requires explicit network selection + security checks.
- For future inbound SMS, Android 17 delays programmatic access to certain OTP messages for most apps; never promise OTP forwarding; security sensitive feature requires standalone design review.

### 6.4 Runtime posture

- No implicit internet exposure/port-forwarding; trusted LAN only; recommend VPN/tunnel for remote access through explicitly designed P1 component.
- Wifi change -> reassess bind/allowlist/trusted network; stop or mark degraded if policy violates.
- Foreground service optional subject to platform compliance. Always distinguish process RUNNING vs socket reachable.
- Avoid WakeLock by default; no indefinite polling loops; measure and optimize power consumption.

## 7. SMS Domain and State Machine

### 7.1 States and semantics

| State | Meaning | Terminal? |
|---|---|---|
| `QUEUED` | Request validated + persisted and awaiting dispatch | No |
| `DISPATCHING` | Dispatch lease acquired and SmsManager invocation started | No |
| `SENT` | All SMS parts confirmed sent by OS/modem callbacks (not delivered to recipient) | No (delivery may be unavailable) |
| `DELIVERED` | All required delivery reports received, if supported | Yes |
| `FAILED` | Confirmed failure before/at transmission, with `errorCode` | Yes |
| `UNKNOWN` | Callback missing/restart/timeout; cannot reliably infer send/delivery | Yes (manual review) |
| `CANCELED` | Canceled before any dispatch invocation | Yes |

**Transition rules:** `QUEUED -> DISPATCHING -> SENT -> DELIVERED`; `QUEUED/DISPATCHING -> FAILED` when conclusive; uncertain dispatch due to missing sent-callback/restart -> `UNKNOWN`; `QUEUED -> CANCELED`. **Jangan mengubah `SENT` menjadi `UNKNOWN` hanya karena delivery report tidak masuk:** SMS tetap diketahui sudah dikirim oleh modem, sedangkan `deliveryStatus` menjadi `UNKNOWN` setelah timeout, atau `UNAVAILABLE` bila laporan delivery tidak didukung. Late callback yang valid dapat merekonsiliasi `UNKNOWN` (sebelumnya dispatch tidak pasti) ke `SENT`/`DELIVERED` hanya dengan audit trail dan precedence deterministik.

Statuses at SMS part level: `PENDING`, `SENT`, `DELIVERED`, `FAILED`, `UNKNOWN`. Aggregation to message status requires **all** relevant parts. Persist each callback result and timestamp.

### 7.2 Sending policy

- `SubscriptionManager` selects valid active `subscriptionId`; device without active SMS SIM -> `SIM_UNAVAILABLE`.
- Per subscription, use correct `SmsManager` instance via available API (`createForSubscriptionId()` as supported); do not rely solely on `SmsManager.getDefault()`.
- Use `divideMessage()` and `sendMultipartTextMessage()` for multipart. Single part may use `sendTextMessage()`; attach unique explicit `PendingIntent`s for sent and delivery per part; use appropriate flags for SDK 28–37 and unique identity to prevent collisions.
- Bound concurrency **1 in-flight dispatch per SIM** by default; configurable rate limits and cooling period per operator. No parallel burst sending for MVP.
- Validate E.164 format (allow strictly configured domestic normalization with explicit default country setting if necessary), strip non-digit separators in controlled way, avoid unintentionally altering country codes.
- Length bound (e.g. configurable ≤ 10 SMS parts per message) using `SmsManager.divideMessage()` for segment counting; reject extremely long/blank body.
- Required prior consent for recipient; optional allowlist can be enforced by client; no upload contacts.
- SMS cost warning when multipart or cross-carrier if estimable; never claim authoritative charges.

### 7.3 Retry and idempotency

- `Idempotency-Key` REQUIRED on `POST /v1/messages`, length 16–128, ASCII restricted safe charset. Scope `(clientId, key)`.
- Persist `(clientId, key, canonicalPayloadHash, messageId)` in one Room transaction. Request with same key+same payload => same `messageId`, HTTP 200; with same key+different payload => HTTP 409 `IDEMPOTENCY_CONFLICT`.
- Persist key 24 hours minimum (configurable, default 7 days; do not prune early while message exists); ensure DB UNIQUE index.
- **NO AUTOMATIC SMS RETRY** after `SmsManager` invocation if status is unknown. This avoids duplicate SMS on radio callback loss.
- Optional automatic retry *only* for proven pre-dispatch failures and explicit policy; disable by default in P0.
- Retry webhook delivery (P1) independently of SMS.

### 7.4 Reconciliation

- Atomic dispatch lease and dispatch attempt ID; mark `DISPATCHING` before invoking platform API.
- On process restart, `DISPATCHING` older than timeout -> `UNKNOWN`, reason `INTERRUPTED_IN_FLIGHT`. Do not send again automatically.
- Store `SENT` with `deliveryStatus=UNAVAILABLE/PENDING/CONFIRMED`; if reports not supported, no false DELIVERED.
- UI renders historical events and manual resolution action (P1) with admin-only privileges.

## 8. API Contract v1

Base URL: `http://<trusted-android-lan-ip>:9292/v1`. **HTTPS strongly recommended through explicitly configured proxy/tunnel outside trusted LAN**. Body `application/json`; timestamps ISO-8601 UTC; IDs UUID or ULID; properties snake_case consistently in public API. Do not silently change API casing.

**Authentication:** `Authorization: Bearer <api_key>` for all endpoints except sanitized `GET /v1/health` (no host identifier/queue details); API key scopes `messages:write`, `messages:read`, `system:read`, `admin:keys` (admin key management only in local UI in P0); per-key active/revoked and last used.

### 8.1 POST `/v1/messages` (P0)

Headers `Authorization`, `Idempotency-Key` (required). JSON:

```json
{
  "to": "+628123456789",
  "text": "Notifikasi internal: tiket Anda sudah dibuat.",
  "sim_id": 1,
  "request_delivery_report": true,
  "client_reference": "ticket-4831"
}
```

Rules: `sim_id` is Android active subscription ID for current device, optional = explicitly configured preferred active SIM; never silently choose invalid default. Response `202 Accepted`:

```json
{
  "message_id": "019aefb4-4d40-7c36-b72f-e934865740a1",
  "status": "QUEUED",
  "delivery_status": "PENDING",
  "created_at": "2026-10-20T05:00:00Z",
  "parts_total": 1,
  "links": {"self": "/v1/messages/019aefb4-4d40-7c36-b72f-e934865740a1"}
}
```

Repeated identical request with same key returns HTTP `200 OK` with current resource summary and header `Idempotent-Replay: true`, without new SMS job. If different payload returns 409.

### 8.2 GET `/v1/messages/{message_id}` (P0)

`messages:read`. Response 200:

```json
{
  "message_id": "019aefb4-4d40-7c36-b72f-e934865740a1",
  "to_masked": "+62812******89",
  "status": "SENT",
  "delivery_status": "PENDING",
  "sim_id": 1,
  "parts_total": 1,
  "parts_sent": 1,
  "parts_delivered": 0,
  "client_reference": "ticket-4831",
  "created_at": "2026-10-20T05:00:00Z",
  "sent_at": "2026-10-20T05:00:04Z",
  "delivered_at": null,
  "last_error": null
}
```

Never return full body by default; separate `include_body` local UI-only, not remote P0. `404` indistinguishable from unauthorized resource access. API key can read only its own messages.

### 8.3 GET `/v1/messages` (P0)

Query: `limit` (1..100, default 20), `cursor` opaque, `status`, `sim_id`, `created_from`, `created_to`. Scoped by client. Pagination is cursor-based; no full table scan; response `{ "items": [ ... ], "next_cursor": "..." }`; no SMS text fields by default.

### 8.4 GET `/v1/sims` (P0)

`system:read`, returns available active SIM metadata: `{ "items": [{"sim_id": 1, "slot_index": 0, "display_name": "SIM 1", "is_default": true, "is_available": true}] }`. Avoid phone number/IMSI/ICCID and sensitive identifiers; indicate unavailable if permission denied.

### 8.5 GET `/v1/status` (P0)

`system:read`, returns `{ "server": "RUNNING", "sms_ready": true, "pending_count": 2, "network": "LAN", "version": "1.0.0" }`. No secrets/keys/device unique identifiers.

### 8.6 GET `/v1/health` (P0)

Unauthenticated optional readiness with **minimal** body `{ "ok": true }`; safe only on trusted LAN. If server disabled, no network response. Rate-limit even public health.

### 8.7 Error contract

HTTP 400 invalid JSON; 401 missing/invalid key; 403 insufficient scope or recipient policy; 404 unknown resource; 409 idempotency conflict; 413 message too large; 422 validation; 429 rate limit; 503 SIM/permission/server unavailable; 500 unexpected. All errors:

```json
{
  "error": {
    "code": "SIM_UNAVAILABLE",
    "message": "Selected SIM is not available",
    "request_id": "req_01jxyz",
    "details": {}
  }
}
```

Response headers `X-Request-Id`, `Cache-Control: no-store`; `Retry-After` for 429/503 when meaningful. All client-visible errors safe and do not leak secrets.

### 8.8 Legacy API (P1, OFF default)

Optional `POST /sms/` with `{number,text}` and old Bearer key mapping; return clear compatibility response with **queued** semantic (`{ "stat": true, "message_id": "...", "note": "queued_not_delivered" }`) only after commit. Do not claim old client expectations unchanged; document behavior migration.

### 8.9 Webhook future (P1)

`POST` to admin allowlisted HTTPS endpoint with body `{event_id,message_id,event_type,occurred_at,...}`; signature header `X-OpenSMS-Signature: sha256=<hex HMAC>` over raw body; replay protection with timestamp; exponential retry bounded, at-least-once, dedupe event ID. Failures do not cause SMS resend.

## 9. Persistence Design (Room)

Required tables/entities:

| Table | Fields (core) | Constraints |
|---|---|---|
| `api_clients` | id, name, token_hash, salt_or_key_version, scopes, created_at, revoked_at, last_used_at, active | unique fingerprint/hash; never plaintext token |
| `messages` | id, client_id, destination_enc_or_masked, body_enc, sim_id, status, delivery_status, parts_total, timestamps, error_code, client_reference, payload_hash | indexed `(client_id,created_at)`, `(status,created_at)` |
| `message_parts` | id, message_id, part_index, sent_status, delivery_status, result_code, timestamps | UNIQUE `(message_id,part_index)` |
| `dispatch_attempts` | id, message_id, started_at, completed_at, lease_token, invocation_state, error_code | auditable one/more attempts |
| `idempotency_keys` | client_id, key_digest, payload_hash, message_id, created_at, expires_at | UNIQUE `(client_id,key_digest)` |
| `audit_events` | id, timestamp, actor, action, entity_id, result_code, redacted_metadata | retention policy; no API secrets/SMS plaintext |
| `gateway_config` | port, binding_mode, trusted_ssid_or_network, quotas, retention_days, preferred_sim_id | DataStore for simple config rather than Room if appropriate |

**Privacy at rest:** SMS text/destination are sensitive. Prefer application-level AES-GCM encrypt with Keystore-managed key where feasible; fields needed for searching/indexing should be masked/one-way derived. Explicitly document compatibility and key-loss behavior; DB full encryption can be evaluated as alternative with license/maintenance implications. No SMS body in Logcat, crash logs, network logs, screenshots of secrets. Store minimum viable data, default retention 30 days; separate idempotency retention. Secure deletion limitations of SQLite/flash should be disclosed.

**Migration:** Set up Room schema export and deterministic migrations from version 1 onward; no `fallbackToDestructiveMigration()` in production.

## 10. Security Requirements and Threat Model

### 10.1 Threats

- Leaked bearer token -> unauthorized SMS, financial/operational misuse.
- Exposed open HTTP bind -> LAN neighbor access, interception on untrusted Wi-Fi.
- API abuse -> floods and carrier throttling/suspension.
- Data-at-rest compromise -> reveal recipients/text.
- Replay -> duplicate SMS.
- App/process restart -> accidentally resend ambiguous in-flight message.
- SIM swap/change -> send using wrong subscription.
- Malicious webhook endpoint (P1) -> SSRF / information exfiltration.

### 10.2 Controls (P0)

1. Gateway OFF at install; default bind loopback; LAN mode only after explicit admin action.
2. Bind to selected interface/address, never intentionally expose to WAN; allowlist inbound IP/CIDR, warn about DHCP and dynamic IP. If Android cannot reliably bind per interface, validate and reject requests not from allowed local networks and document residual risk.
3. Keys generated with SecureRandom (≥256 bits entropy); displayed only once; store SHA-256 verifier of uniformly random token or HMAC digest with Keystore protected pepper. Never raw token in DB/telemetry.
4. Compare tokens safely and avoid information leaks; auth scoped by client; rotate/revoke key, runtime invalidation. UI admin operation only P0.
5. Rate limit: initial **default** 5 SMS/minute/client, 20/hour/client, 100/day/device, admin configurable to local operator limits; one dispatch at a time/SIM; enforce DB-backed quotas to survive restarts. These are safety defaults, **not** throughput commitments.
6. Queue capacity default 100; reject with `429` or `503` before enqueue when full; request body max 16 KiB; body segment limit 10; all configurable within hard safety ceiling.
7. No permissive CORS; no unauthenticated remote control (`/control/` not carried forward); no nonlocal network exposure in default configuration.
8. Enforce only HTTPS for outbound webhook if implemented; block loopback/link-local/private targets unless explicitly enterprise allowlisted and risk-reviewed; avoid URL redirects to blocked ranges.
9. Strict request validation and sanitized exception handling; `Cache-Control: no-store`; no API docs exposure outside trusted LAN unless admin permits.
10. Security audit for key create/revoke, server start/stop, policy changes; log client ID and masked IP only; retention and export controls.
11. Biometric/device authentication for opening API key management UI if supported and desired (P1); device screen lock recommended for P0.
12. No API-side execution of shell commands, file paths, or arbitrary network requests.

**Transport warning:** HTTP over LAN allows sniffing on compromised LAN, even with token auth. For hostile/shared LAN production deployment, require trusted tunnel/reverse proxy with TLS and ensure raw device port isn't reachable broadly; never label HTTP+Bearer alone as end-to-end secure.

## 11. Detailed Acceptance Criteria / Test Scenarios

`AC-01` Fresh install: no listener, no SMS sent, dashboard OFF; user must opt in.

`AC-02` Permission denied: Start blocked or DEGRADED with clear error; no crash.

`AC-03` No active SMS capability/SIM: reject request with `503 SIM_UNAVAILABLE`; do not accept 202 that cannot safely be queued indefinitely without policy.

`AC-04` One valid authorized request: 202 only after Room commit and job appears in history with stable ID.

`AC-05` Same Idempotency-Key + same payload: response replayed, no duplicate DB message/dispatch.

`AC-06` Same key + different payload: 409; no new send.

`AC-07` Token missing/revoked/invalid: 401; no queued SMS.

`AC-08` Client with read scope only attempts send: 403.

`AC-09` Queue full/rate limit exceeded: 429/503 with clear error and no SMS.

`AC-10` Single SMS sent callback success: status becomes SENT; not DELIVERED merely because OS accepted it.

`AC-11` Delivery callback success for all parts: DELIVERED; partial delivery remains not DELIVERED.

`AC-12` Failure callback includes per-part error code, aggregate FAILED when confirmed, with visible reason.

`AC-13` Long Unicode multipart text uses correct segmenting; all callbacks mapped by unique part IDs.

`AC-14` Dual SIM device: selected active sub-ID used; remove SIM then sending stops with correct error, no silent SIM switch.

`AC-15` Restart/force-kill while DISPATCHING: reconcile stale work as UNKNOWN; no unintended automatic resend.

`AC-16` Service STOP: socket closed, UI OFF, no new requests; committed queued items remain and require deliberate resume.

`AC-17` Port already bound: Start fails gracefully with actionable message.

`AC-18` Wi-Fi to cellular/untrusted Wi-Fi: LAN policy recalculated; server stops/degrades, no accidental WAN exposure.

`AC-19` Android 17 permission denied/revoked: LAN mode unavailable/degraded without crash; normal local UI usable.

`AC-20` Background OS restriction: app handles denied start/timeouts gracefully, no hidden FGS workaround; status on next UI visit truthful.

`AC-21` Exported logs contain no plaintext bearer token/body; recipient masked.

`AC-22` All Room migrations pass and no destructive migration in production.

`AC-23` API contract tests verify expected HTTP status, casing, validation, and JSON error envelope.

`AC-24` Rate limiting persists across process restart and is not bypassable by changing key names for same client.

`AC-25` Test on physical devices with different vendors/Android versions; emulator-only proof insufficient for SMS radio path.

`AC-26` No runtime notification when server OFF except necessary app notifications; when server active and FGS justified, user can stop from visible notification.

`AC-27` `GET` by client A never discloses messages sent by client B.

`AC-28` Invalid recipient, blank message, oversized multipart, invalid SIM id rejected prior to queue commit.

### 11.1 Test matrix

| Layer | Tests | Target |
|---|---|---|
| Unit | Validators, state machine, aggregate parts, token verifier, limiter, policy, idempotency | ≥80% line coverage of domain/core (target) |
| Integration | Ktor endpoint tests, Room transactions/indexes, fake SmsSender, migration test | Pass in CI |
| Android instrumented | Permission flows, Compose screen state, process lifecycle, receiver and PendingIntent mapping | Pass supported API variants |
| Physical SMS | 1 SIM, dual SIM, single/multipart Unicode, success/error, sent/delivery callback | Android 9/12/14/15/17 where devices available |
| Stability | 1h/6h/24h soak, idle battery/RAM, Wi-Fi changes, restart, battery saver | Record measurements; no universal SLA claims |
| Security | Token misuse, replay, burst requests, LAN hostile client, log inspection, exported components | No critical findings |

If device matrix unavailable, explicitly mark untested cells; do not claim release validation complete.

## 12. Nonfunctional Requirements

- UI: Material3 accessible; sufficient contrast, text scaling, screenreader labels; dark/light.
- Concurrency: structured coroutines, cancellation, bounded channels, no endless loops; hardware calls not on main thread.
- Data integrity: transactionally persisted queue; deterministic state transitions; indexes; safe crash recovery.
- Security: SAST, dependency checks, minimized exported Android components; explicit `android:exported` policies.
- Localizations: strings resources ID/EN-ready, no hardcoded secrets/URLs.
- Battery: idle CPU near zero in stopped state, no 200ms polling like reference project; measure when running.
- Packaging: reproducible Gradle Wrapper, dependency version catalog and lock strategy; no firebase/cloud dependency in MVP.
- Docs: setup, permission FAQ, API reference, troubleshooting, limitations, threat model, license notices.
- Observability: redacted structured logs with correlation IDs and bounded retention; no external telemetry by default.

## 13. Delivery Roadmap / Work Packages for Codex

### Phase 0 — Discovery, baseline, policy spike (deliver first)

- Create `docs/REPO_AUDIT.md` source mapping and gap list; identify Apache 2.0 compliance / attribution.
- Research and document Android 17 API, LAN permission, SMS policy, proper FGS type. Produce `docs/ADR-001-background-execution.md` as gate with legal eligible service type/no service fallback.
- Create minimal Kotlin Android scaffold with compile/target 37 where available and test embedded Ktor bind on physical/emulated Android; record results.
- Deliver architecture diagram and risk ledger (`docs/RISKS.md`), not code for all features.
- Gate: no production service until platform/policy assumptions resolved.

### Phase 1 — Native app skeleton

- Compose navigation, theme, dashboard mock state, onboarding, DataStore, DI, Room setup & migrations, logger, safe settings.
- Gate: app builds with no dangerous manifest permissions except justified ones, unit tests pass.

### Phase 2 — SMS core and durable queue

- Domain states, Room entities, idempotency transactions, queue coordinator, SMS sender abstraction, SubscriptionManager, result receivers, multipart callbacks; unit & instrumented tests.
- Gate: fake SMS sender tests pass, real test SMS from UI and correct per-part state on device.

### Phase 3 — Embedded API and policy

- Ktor lifecycle, auth/scopes/key rotation, REST routes, validation, limiter, allowlist, API contract tests; integrate enqueue flow.
- Gate: all AC-04..AC-09 and AC-23..AC-24 green.

### Phase 4 — Gateway lifecycle + UX diagnostics

- Start/Stop, notification and FGS policy-compliant design, network permissions, service state, SIM UI, history/status/logs, recovery; Android 17 tests.
- Gate: Android platform test matrix and AC-01..AC-28 checklist completed/marked blocked explicitly.

### Phase 5 — Hardening/release

- CI, unit/integration/instrumentation, soak tests, accessibility, security review, license attribution, signed APK via owner-managed signing credentials (never commit keystore), `README`, examples, changelog.
- Gate: release checklist and evidence attached; unresolved policy blockers prevent public launch.

### Phase 6 — P1/P2 improvements (separate PRD amendments)

- Signed webhook, constrained batch, scheduling, advanced export, optional outbound relay, fleet; each gated on privacy/permission review.

**Delivery rule:** Each phase must produce compiling code and tests, not placeholder functions/stubs falsely marked done. Do not begin next phase until quality gate passes or blockers clearly logged.

## 14. Definition of Done

1. Android app builds from clean checkout with documented JDK/Android SDK and Gradle Wrapper.
2. All P0 functional requirements and relevant ACs implemented; screenshot/test evidence from supported devices.
3. API responses match checked-in `openapi.yaml` (or documented compatible amendment).
4. No secrets in repository/logs; key is shown once and revocable.
5. No more than one dispatched SMS per unique idempotency key under concurrency/restarts (not a guarantee of network exactly-once delivery).
6. Sent status is not misreported as delivered; unknown radio outcomes are never silently retried.
7. Service and network permissions respect Android 12–17 restrictions; no false 24/7 availability promise.
8. `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug` pass. Instrumented test results separately documented.
9. README includes configuration + cURL + FAQ + sample errors + limitations + offline operation.
10. APK can be installed on real test device and demonstrate a consented test SMS end-to-end.
11. Design/policy decisions recorded in ADR; Apache-2.0 source use handled appropriately.

## 15. Release Readiness / Blockers

**Hard blockers:** Google Play restricted SMS policy if public distribution requested; FGS service-type eligibility for long-lived listening; Android 17 LAN permission on target API 37; compatibility of Ktor server with Android; physical-device SMS callback reliability across OEMs; no external TLS in shared/untrusted network.

**Mitigations:** private distribution, honest session-based mode, tech spike, instrumentation, explicit disable for untrusted networks, optional P1 managed-device/external relay design, no promise of uptime SLA.

**Security / legal review:** operator fair-use and rate constraints; opt-in recipient/compliance; data retention and local regulations (including applicable Indonesian personal data obligations), consent and support process.

## 16. Sources & API Reference Links

Repository (accessed 2026-10-20):
- https://github.com/daslearning-org/OpenSMS
- https://raw.githubusercontent.com/daslearning-org/OpenSMS/main/app/services/postApi.py
- https://raw.githubusercontent.com/daslearning-org/OpenSMS/main/app/services/smsApi.py
- https://raw.githubusercontent.com/daslearning-org/OpenSMS/main/app/services/appSvc.py

Official Android/Google guidance:
- https://developer.android.com/reference/android/telephony/SmsManager
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/about/versions/15/behavior-changes-15
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/privacy-and-security/local-network-permission
- https://support.google.com/googleplay/android-developer/answer/10208820

## 17. Change Control & Questions Before Enterprise Release

- Architecture/security/policy changes require ADR and PRD revision.
- External API breaking changes require version bump (`/v2`), schema tests, migration guide.
- No feature may automatically introduce SMS read permissions, WAN exposure, auto-send retries, or FGS policy changes without review.
- Record `OPEN-1..OPEN-4` answers when product owner supplies them. Engineering may use defaults above to implement the MVP without blocking unrelated work.

---

**Acuan implementasi:** `AGENTS.md` berisi aturan coding agent; `CODEX_MASTER_PROMPT.md` berisi prompt eksekusi bertahap; `openapi.yaml` berisi kontrak yang bisa diuji.
