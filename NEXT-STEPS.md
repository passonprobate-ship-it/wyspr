# Keystone — Next Steps

Generated 2026-05-18 after the v0.1 happy-path landing.

The v0.1 slice now compiles and exercises the full handshake pipeline:
`Welcome → RolePicker → KeyGeneration → DisplayQr → ScanPeerQr →
CompareFingerprints → RunHandshake (Noise XX over BLE) → Result`,
with the resulting `TrustEdge` written to the encrypted DB. What
remains is everything that lives *after* the trust edge exists — the
Utility Modules — plus the hardening work that turns a working build
into something we'd ship to people who actually need it.

The list is grouped by readiness, not by sprint. Each section answers
the same three questions: what works today, what's the smallest next
step, and what catches fire if we skip it.

---

## 1. Two-device hardware proof

**Why first.** Everything below is wishful until the v0.1 happy path
has been verified end-to-end on two physical Android devices, not just
"the build is green." A green build does not mean BLE GATT
characteristics fire in the right order, nor that the MTU negotiation
lands at 247, nor that the Noise channel-binding check fires when the
QRs are swapped.

**Smallest next step.**
- Install `app-debug.apk` on two Android-26+ devices.
- Run the Inviter+Invitee flow; capture `logcat -s "Keystone" "BluetoothGatt" "Noise"`.
- Verify `trust_edge` row appears in both DBs after success.
- Verify `AbortReason.ChannelBindingMismatch` fires when one QR is
  scanned from a stale screenshot mid-handshake.

**If skipped.** We're shipping a paper architecture. The BLE callback
shapes and Noise message framing are exactly where reality bites
hardest.

---

## 2. Per-feature Utility Modules

Each is its own milestone — they don't block each other, and the
order below is the order in which the missing functionality starts to
hurt first.

### 2.1 Vault (`feature:vault`)
The encrypted personal-record store. Files + structured records
(passwords, recovery phrases, will instructions, family contacts)
sealed with XChaCha20-Poly1305 keyed off a HKDF-derived subkey.

- Vault entries are CBOR envelopes in `currency_envelope`'s sister
  table `vault_envelope` — sync filter restricts them to the owner
  only.
- Per-record sharing via a "share key" wrap is a v0.3 problem; v0.2
  is single-owner only.
- UI: list, detail, add, delete, lock. No "search" until indexing
  works on encrypted records — out of scope for v0.2.

### 2.2 Marketplace (`feature:marketplace`)
Already largely scaffolded with `WalletService` + `Send/Receive/
History/Audit` screens. The next milestone is making it actually
work over the BLE link:
- Wire the existing `SyncEngine.runSession()` to the BLE Link the
  handshake established.
- Add a "send to peer" flow that picks a verified TrustEdge target.
- Audit screen pulls from real envelopes once we've sync'd at least
  once.

### 2.3 Coordination (`feature:coordination`)
Event/voting/announcement primitives over the same sync pipeline.
The data model is small (CBOR envelopes again — `coord_envelope`),
the UI is the bulk of the work.

### 2.4 Directory (`feature:directory`)
Member list view over what the local TrustGraph already knows. v0.2
ships read-only with one filter: "members I have a Full vouch edge
to." Avatars are deferred — they leak biometrics if not handled
carefully.

---

## 3. Transport & sync hardening

### 3.1 WiFi Direct Link
`WifiDirectTransport.connect()` is still a `TODO`. The work is:
- WifiP2pManager.connect, wait for GROUP_OWNER_INFO,
- open a TCP socket on the group owner's IP at a fixed port,
- wrap it as a `Link` with the existing length-prefixed framing.
- Negotiate "switch transport" mid-session — start handshake on BLE,
  upgrade to WiFi Direct for bulk sync.

### 3.2 BLE robustness
- MTU negotiation can fail silently on some OEMs; fall back to 23-byte
  chunks gracefully and surface a warning badge.
- Long-running sessions need a foreground service with a low-priority
  notification so Android doesn't suspend the BLE callback.
- Bonding: detect repeated handshakes with the same MAC and offer to
  bond at the OS level for faster reconnect (currently we only do
  unbonded BLE).

### 3.3 Sync robustness
- Pagination — current sync caps a single round at 16,384-byte
  frames. For peers that have been offline for weeks, we need
  multi-frame `Push` chunks with explicit termination.
- Backpressure — the SharedFlow buffers in the transports are 64
  entries; under load this DROP_OLDESTs discovery events and
  SUSPENDs accepted links. Both are correct, but neither has
  telemetry yet. Add a TrustBadge to the dashboard counting
  drops in the last hour.

---

## 4. Trust graph completeness

### 4.1 K-paths quorum
`TrustGraph` is interface-only. The next sprint implements:
- BFS up to depth D=4 from `self` to a target peer,
- count node-disjoint paths,
- if `≥ K=2` paths exist, the target's effective TrustLevel rises
  from Provisional to Full.

Until this lands, every successful handshake produces a `Provisional`
edge, which is correct per spec but means no one ever reaches `Full`
trust except via direct invitation.

### 4.2 Revocation propagation
- Local revocation works (entity + DAO + cert codec done).
- Cross-peer propagation needs the sync engine to recognize
  `RevocationEnvelope` and apply it immediately on receipt — with
  the rule that an unknown revoker is silently ignored. Otherwise an
  attacker who learns *any* TrustEdge can claim to be its revoker.

### 4.3 Reset & re-onboarding
- The `AndroidKeystoreManager.reset()` path is solid; surfaces a
  fresh identity on next launch.
- Missing: a "leave community" flow that signs a
  `Voluntary_Exit` revocation against your own pub before resetting,
  so peers age you out cleanly instead of carrying you as
  `Quarantined: peer offline`.

---

## 5. Cryptography hardening

### 5.1 Service UUID → BLAKE2s
`ServiceUuid.forCommunity` currently uses SHA-256 as a placeholder.
PROTOCOLS.md §6 specifies BLAKE2s. This is purely a one-line crypto
swap — but doing it correctly requires libsodium's `crypto_generichash`
exposed through `lazysodium`. Before swapping, write a fixed-test-
vector test so we never break it.

### 5.2 Forward secrecy on identity reset
Today, resetting the identity invalidates the SQLCipher key, which
makes any stored ciphertext unrecoverable — that's correct. The
matching peer doesn't know we reset; their sync attempts hit
`SignatureInvalid` because our pub changed. We need a
`KeyRotation` envelope so peers learn about a legitimate rotation
without confusing it with a compromise.

### 5.3 Side-channel
`KeystoreManager.sign` re-derives the secret on every call — this is
intentional (no in-memory cache of the seed). But Ed25519 sign with
libsodium is constant-time only with respect to the key, not the
message length. For invitation certs this doesn't matter (fixed
length), but for arbitrary sync envelopes we should pad to the
nearest 32-byte boundary before signing.

---

## 6. UI polish — what's left

The v0.1 design system is in. What's not yet themed to match:
- `core:ui/QrRenderer` — currently renders pure black-on-white. Add
  the same monospace label rendering below the QR.
- The `marketplace` Wallet screens were built before the new theme;
  they work but they look like the rest-of-Android, not Keystone.
  Apply `KeystonePanel` + `TrustBadge` throughout.
- Onboarding screens have no error/empty states tested at small
  screen widths (320dp). Need a manual pass on a Pixel 4a-class
  device.
- A11y: nothing has been checked. Fingerprint groups should each be
  individually announceable; `TrustBadge` should have a content
  description.

---

## 7. Build & release hygiene

- **Release variant.** Today only `assembleDebug` is wired. Release
  needs:
  - A real signing config (placeholders are in `app/build.gradle.kts`).
  - R8/ProGuard pass (rules are present but not exercised on a
    release build).
  - Reproducible-build flags — Keystone's threat model includes
    "compare two APKs at two devices" so the user can verify they
    have the same artifact.
- **CI.** No CI today. Minimum: `assembleDebug` + the three
  `testDebugUnitTest` modules on every push.
- **F-Droid manifest.** Listed for v0.3 — F-Droid is the only Android
  distribution channel consistent with Keystone's anti-platform
  posture. Their reproducibility requirements bite hardest on
  native deps (lazysodium ships pre-built AARs); plan to either swap
  to a from-source build or document the deviation.
- **Tor hidden service transport.** Listed in the Transport enum as
  `TorHiddenService`. Implementation is a v0.4 problem — needs a
  bundled Tor process (orbot-style) and a v3 onion address per
  community.

---

## 8. Docs & ops

- `docs/SECURITY-MODEL.md` — still authoritative. Needs a §3.8 on
  the K-paths algorithm once §4.1 above lands.
- `docs/PROTOCOLS.md` — add an appendix with the actual wire bytes
  for one minimal handshake (captured from §1's hardware proof) so
  future ports have ground truth.
- `docs/LIBRARIES.md` — add `noise-java` pinned commit `49377b6` and
  a note on why we haven't moved to a tagged release yet (there
  isn't one).
- No analytics ever. The forbidden-deps list in
  `docs/LIBRARIES.md` already prevents this; add a CI grep that
  fails the build if any module's dependency tree includes the
  banned identifiers.

---

## Suggested sprint cadence

| Sprint | Theme | Concrete output |
|--------|-------|-----------------|
| v0.1.1 | Hardware proof | Two-device handshake demo + screen recording |
| v0.2.0 | Vault | Vault feature shipped + theme parity |
| v0.2.1 | Marketplace over BLE | Wallet send works peer-to-peer |
| v0.2.2 | Trust graph quorum | K=2 paths → Full trust |
| v0.3.0 | WiFi Direct + foreground service | Bulk sync works |
| v0.3.1 | Release pipeline + F-Droid | Reproducible build, signed |
| v0.4.0 | Coordination + Directory | Read-only directory + voting |
| v0.4.1 | Tor transport | Optional, off by default |

Each sprint should leave behind a green build, a paragraph in
`SECURITY-MODEL.md` describing what changed in the threat model, and
a one-line line in `VERSION` so devices show the right tag.
