# OpenSMS Next — Android app

OpenSMS Next is a Kotlin Android SMS gateway for trusted local networks. It stores messages durably before returning `202`; `202` means queued, not sent or delivered.

## Build

Open `android-native/` in Android Studio, or use the Gradle Wrapper:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

The build targets Android SDK 37, `minSdk 28`, AGP 9.4.1, Gradle 9.8.2, and JDK 21 or newer supported by Gradle/AGP. Install SDK Platform 37 and Build Tools 36.0.0. Android Studio can create the ignored local `local.properties`; do not commit it.

## First run and gateway session

1. Review the onboarding notes and complete setup.
2. Create an API client under **Pengaturan → Kunci API**. Choose only the scopes it needs. Tap **Salin kunci** while the generated key is shown; the app stores an HMAC verifier and will not show the token again. Keys can be rotated or revoked there.
3. For LAN use, set a private IPv4 subnet under **Akses LAN tepercaya** (for example `192.168.1.0/24`). The app binds only to the active Wi-Fi IPv4 address and rejects peers outside that subnet.
4. Choose **LAN**, press **Mulai gateway**, and grant the requested SMS/SIM permissions. LAN mode on target API 37 also requests `ACCESS_LOCAL_NETWORK`. Once running, Beranda shows the active local address and port, for example `http://192.168.1.24:9292` and the `/v1` API base. The address is shown only while the LAN listener is running.
5. The gateway runs as a foreground service while the app is in the background and shows a persistent notification with a **Hentikan** action. Android 13+ requires notification permission for the normal notification experience. The selected mode is saved to restore after process death and, if the session was active, after reboot. Fresh install remains OFF; pressing Stop clears the saved session.

The API listens on port `9292` only after an explicit Start action. Fresh install is stopped. Loopback mode uses `127.0.0.1`; LAN mode needs the runtime permission and a non-empty private subnet allowlist. There is no unauthenticated remote control endpoint. Background availability is best-effort, not a 24/7 SLA: Doze may suspend network access, and OEM battery controls, force-stop, power loss, Wi-Fi changes, permission changes, and failed boot recovery can interrupt it. On Infinix, review the app's battery/background and auto-start settings manually; the app does not request a battery-optimization exemption automatically.

## API examples

Set `API_KEY` to a key just created in the app. Use an unpredictable idempotency key for each logical SMS request.

```bash
curl http://127.0.0.1:9292/v1/health

curl -X POST http://127.0.0.1:9292/v1/messages \
  -H "Authorization: Bearer $API_KEY" \
  -H "Idempotency-Key: order-2026-10-20-00000001" \
  -H "Content-Type: application/json" \
  -d '{"to":"+628123456789","text":"Test message","request_delivery_report":true}'

curl http://127.0.0.1:9292/v1/messages \
  -H "Authorization: Bearer $API_KEY"
```

`GET /v1/health` is unauthenticated and contains only `{ "ok": true }`. Other endpoints require a bearer token with the appropriate scope. Message reads belong to the authenticated API client; destinations are masked and SMS body text is never returned.

### Common errors

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `INVALID_IDEMPOTENCY_KEY` | Missing or malformed idempotency key |
| 401 | `UNAUTHORIZED` | Token is missing, invalid, or revoked |
| 403 | `FORBIDDEN` / `NETWORK_NOT_TRUSTED` | Scope or configured network policy rejected the request |
| 404 | `NOT_FOUND` | Message does not exist for this client |
| 409 | `IDEMPOTENCY_CONFLICT` | Key was used with a different request payload |
| 413 | `PAYLOAD_TOO_LARGE` | Request body exceeds the configured limit |
| 422 | `INVALID_RECIPIENT` / `INVALID_SIM_ID` | Request failed validation |
| 429 | `RATE_LIMITED` | Persistent per-client or global rate limit reached |
| 503 | `SIM_UNAVAILABLE` / `QUEUE_FULL` | No selected active SIM or bounded queue is full |

All errors use the `openapi.yaml` error envelope and include a request ID. Do not send SMS body, API keys, or full phone numbers to logs or support tickets.

## Privacy, transport, and distribution

- SMS destination/body fields use AES-GCM with a key held by Android Keystore. API tokens are shown once; Room stores only an HMAC verifier. Android backup is disabled. Clearing app data or losing the Keystore key can make stored ciphertext unrecoverable.
- The API is plain HTTP. Use it only on a trusted Wi-Fi network and restrict the subnet allowlist. Do not port-forward it to the public internet. Use a separately managed VPN/TLS tunnel for remote access.
- The app does not implement the Android default SMS handler role. Google Play currently restricts `SEND_SMS` to apps actively registered as the default SMS or Assistant handler, so Play distribution is blocked pending a policy-compliant product decision. An internally distributed/sideloaded APK is not a Play approval.
- The gateway uses the `specialUse` FGS type with a declared subtype because the LAN listener is not a data-sync or cross-device messaging transfer. Android and Google Play require appropriate FGS declarations; Play reviews `specialUse` use cases. This APK has not passed Play review, and the separate `SEND_SMS` policy issue remains.
- `SENT` means the platform sent callback succeeded; it is not carrier delivery. Only all successful delivery callbacks produce `DELIVERED`. Ambiguous callback loss becomes `UNKNOWN`, with no automatic SMS retry.

## Validation status

Automated unit and Android instrumentation tests cover domain validation, redacted audit export, Room encryption/idempotency/state transitions, API auth/scopes/replay/conflict, Compose fresh-install state, and Ktor lifecycle on an Android 15/API 35 emulator. A run on the connected Infinix X6887 (Android 16) passed 13 of 14 instrumented tests; the app-launch UI test could not find a Compose hierarchy under the device's test runner. Diagnostics can export up to 200 audit rows as CSV via the system file picker; it exports only timestamp, actor category, action, and result code. It omits entity IDs, recipients, free-form metadata, message bodies, and bearer tokens. These tests do not prove Android 17 LAN permission behavior, a physical SMS modem, dual-SIM OEM behavior, background policy, or soak/idle performance. See [implementation status](../docs/IMPLEMENTATION_STATUS.md) and [ADR-001](../docs/ADR-001-background-execution.md).

`applicationId` `org.daslearning.opensms.next` is provisional pending the product owner’s package-ID decision. No release signing key is included.
