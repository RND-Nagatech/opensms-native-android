# AGENTS.md — OpenSMS Next (Kotlin Native)

## Mission

Implement **OpenSMS Next** as a robust native Kotlin Android SMS Gateway based on `PRD_OpenSMS_Next_Kotlin.md`. The old Python/Kivy OpenSMS is the behavior reference, **not** a drop-in porting target.

## Binding specifications

1. `PRD_OpenSMS_Next_Kotlin.md` — product scope, requirements, UX, architecture, acceptance, platform constraints.
2. `openapi.yaml` — REST API canonical contract.
3. `CODEX_MASTER_PROMPT.md` — milestone order and reporting format.

If a spec contradicts actual Android APIs, safety, official platform policy, or test evidence, document it as an ADR/change proposal. Do not silently implement noncompliant behavior.

## Working agreements

- **100% Kotlin** for new Android app. Jetpack Compose/Material3, Coroutines/Flow, Hilt, Room, DataStore; avoid Python/Kivy/Flutter/React Native for new app.
- Begin with repository inspection. Place code in `android-native/` if the upstream tree already contains a Python app. Preserve old app and license unless requested otherwise.
- Build clean, testable, layered code. Use interfaces for `SmsSender`, `GatewayServer`, `Clock`, `RateLimiter`, `TokenVerifier`, `SubscriptionProvider`.
- Enable Gradle Wrapper, Kotlin lint/static analysis where feasible, pinned dependencies and deterministic CI.
- Do not hardcode tokens, signing keys, SMS destinations, network IPs, or secrets.
- Never log SMS text, raw API tokens, IMSI/ICCID, full destination number, or device unique identifiers.
- Treat each HTTP 202 as DB commit success; canonical transactional idempotency is mandatory.
- Never equate invocation of `sendTextMessage()` with successful delivery.
- Never auto-resend SMS after ambiguous dispatch/callback loss. Conclude `UNKNOWN` with audit event.
- API is OFF on fresh install, user must actively start, LAN binding behind explicit trust/allowlist policy.
- Do not expose a public unauthenticated control endpoint. Do not enable bulk spam/OTP interception.
- Do not mislabel foreground service type to get 24/7 operation; official eligibility must be validated.
- At Android 17 target 37, account for `ACCESS_LOCAL_NETWORK` requirement and test in physical device. Respect Android 12–17 FGS background restrictions.
- Avoid `fallbackToDestructiveMigration` and database resets on upgrade.
- Favor small PRs: scaffold -> domain/Room -> radio adapter -> API/auth -> lifecycle/UI -> hardening.
- Do not commit directly to main, push, publish, or generate release keystore unless the user explicitly asks.

## Mandatory workflow, each phase

1. Read PRD and source repo; list actionable tasks and blockers.
2. State exact files to modify, tests to add and acceptance IDs to satisfy.
3. Implement minimum compiling slice; include tests.
4. Run test/lint/build commands available in environment; report outputs honestly.
5. Mark physical Android-only tests `NOT RUN` if device is unavailable.
6. Update `docs/IMPLEMENTATION_STATUS.md` with Done/In Progress/Blocked and tests.
7. Record background-service eligibility decisions in ADR, not unchecked assumptions.
8. Present summary, changed files, commands/results, unresolved risks and recommended next phase.

## Acceptance criteria that cannot be skipped

- Safe key lifecycle: generated strong secret, shown once, verifier only stored, revoke.
- Queue backed by Room transactions with idempotency replay/conflict behavior.
- Correct `SmsManager` subscription selection, sent/delivery callbacks per multipart part.
- State machine `QUEUED -> DISPATCHING -> SENT -> DELIVERED`; failure/unknown handling.
- Per-client authorization/ownership of read endpoints, rate and capacity limits.
- HTTP server lifecycle and trusted network policy.
- Permission denial and process recovery handling.
- Unit/integration/physical tests with transparent result reporting.

## Test/build baseline

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
# when emulator/device available:
./gradlew connectedDebugAndroidTest
```

Do not report a test passed if the environment could not execute it. Resolve Gradle/SDK constraints explicitly.

## References

See `PRD_OpenSMS_Next_Kotlin.md` section 16 for upstream links and official Android guidance. Upstream is Apache-2.0 licensed; retain required notices if copying source.
