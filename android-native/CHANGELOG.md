# Changelog

## 0.2.0 — MVP/P0 implementation work

- Added Compose onboarding, dashboard, message history, diagnostics, and API key/network settings.
- Added Room-backed encrypted message queue, transactional idempotency, persistent rate buckets, client scopes, and ownership filtering.
- Added Android Keystore AES-GCM field encryption and HMAC API token/payload verifiers.
- Added Ktor REST v1 routes, trusted IPv4 subnet policy, and explicit session-only Start/Stop lifecycle.
- Added per-part SmsManager callbacks, serial queue dispatch, stale-dispatch reconciliation to `UNKNOWN`, and no-auto-retry behavior.
- Added unit, database, queue, API contract, and Android UI instrumentation coverage.
- Public release remains blocked by physical Android 17/SMS device validation and Google Play `SEND_SMS` policy eligibility.
