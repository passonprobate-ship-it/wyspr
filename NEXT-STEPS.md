# Wyspr — Next Steps

Refreshed 2026-05-24 against `android` branch (v0.9.3, build 24).

## Completed since last refresh

| Item | Version | Status |
|------|---------|--------|
| Key rotation envelope | v0.9.3 | **Done** — cert + propagation + ingestion + issuer UI |
| Tor end-to-end hardware proof | v0.9.1 | **Done** — BLE off, both directions, S23+A02s |
| Revocation propagation | v0.9.2 | **Done** — issue + UI + peer-to-peer anti-entropy sync |
| Tor bootstrap watchdog | v0.9.1 | **Done** — auto-restart on stall >120s |
| Noise session reuse (Tor) | v0.9.1 | **Done** — hardware-verified, sub-second cached rounds |
| 68-fix codebase audit | v0.9.1 | **Done** — crypto, transport, messaging, DB, UI, build |
| F-Droid metadata | v0.9.1 | **Done** — Keystone→Wyspr, ready to tag+submit |
| Killer-app sprints 1-7 | v0.9.0 | **Done** — images, reactions, voice, search, disappearing, files, link previews |

---

## 1. Hardware-verify key rotation

**Status.** Code-complete, NOT hardware-verified. Two-device test
needed: one side rotates, the other picks up the cert on next sync
and rekeys trust edges, contacts, and message history automatically.

---

## 2. CI pipeline

**Status.** No CI. The codebase has 280+ Kotlin files, 15 migrations,
and Room schema export enabled — all untested in automation.

**Smallest viable CI.**
- GitHub Actions: `assembleDebug` + `compileDebugKotlin` on every push
- Banned-deps grep (no `com.google.firebase`, no analytics SDKs)
- Room migration test via `MigrationTestHelper` + exported schemas

---

## 3. F-Droid submission

**Status.** Metadata is ready (`metadata/com.wyspr.yml`, fastlane
descriptions + changelogs). Needs:
- Tag `v0.9.3` on the repo
- Fork `f-droid/fdroiddata`, submit PR with `metadata/com.wyspr.yml`
- Accept that reproducible-build check will flag pre-built native
  deps (lazysodium, kmp-tor) — documented in `docs/FDROID.md`

---

## 4. Vault (`feature:vault`)

**Status.** Empty module. Encrypted personal-record store (passwords,
recovery phrases, family contacts) sealed with XChaCha20-Poly1305
keyed off a HKDF-derived `WYSPR/v1/vault` subkey.

---

## 5. Marketplace over BLE

**Status.** Wallet exists (`core:currency`, `WalletService`). The
remaining work is wiring `SyncEngine.runSession()` to a real BLE
Link, plus the send-to-peer UI flow.

---

## 6. WiFi Direct connect()

**Status.** Discovery only. `connect()` is a TODO. Work:
`WifiP2pManager.connect`, wait for `GROUP_OWNER_INFO`, open TCP
socket on group owner's IP, wrap as `Link`.

---

## 7. Reticulum/LoRa

**Status.** `:core:transport:reticulum` is a stub. No Kotlin binding
exists for the Reticulum network stack.

---

## 8. Monero wallet — JNI engine

**Status.** v0.7.0a scaffold is live (RPC over Tor SOCKS, "engine
not bundled" UI). Next: integrate monerujo's `monero-android` JNI
binding for real view-only balance + history.

---

## 9. Layer-2 / Layer-3 peer update

**Status.** Layer-1 (manual URL pull, SSRF-gated) works. Layer-2
(auto-discovery over BLE trust channel) and Layer-3 (K-quorum APK
verification) are deferred.

---

## 10. Coordination + Directory

**Status.** Not started. Event/voting/announcement primitives over
the sync pipeline.

---

## Suggested sprint order

| Priority | Theme | Rationale |
|----------|-------|-----------|
| 1 | Hardware-verify key rotation | Security — code-complete but unproven on real devices |
| 2 | CI | Build hygiene — 15 migrations untested in automation |
| 3 | F-Droid submission | Distribution — metadata ready, just needs tag + PR |
| 4 | Monero JNI engine | Feature — scaffold is wired, binding is the last mile |
| 5 | Vault | Feature — empty module, clear spec |
| 6 | WiFi Direct | Transport — higher bandwidth for bulk sync |
| 7 | Coordination | Feature — new surface area |
