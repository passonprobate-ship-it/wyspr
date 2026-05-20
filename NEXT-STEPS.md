# Keystone — Next Steps

Refreshed 2026-05-19 against `android` branch (v0.6.7).

The v0.1 happy path landed on 2026-05-18. Since then we've shipped through
**v0.6.7**: Trust-graph quorum is wired into the handshake; the
foreground service keeps BLE alive across backgrounding; peer-to-peer APK
distribution works; messaging with read receipts works; an embedded Tor
backend boots, derives a keystore-pinned `.onion`, publishes a hidden
service, and `TorHiddenServiceTransport` carries Noise frames end-to-end
via a loopback listener + SOCKS5 dial. `MessageSyncService` races
BLE-discover, Tor-dial-any-known-onion, and BLE/Tor accepts in a single
sync round. Fastlane metadata + a draft F-Droid manifest now sit in the
repo so the first listing can be cut.

What remains is the two-device hardware proof of the Tor leg, revocation
propagation, the Monero wallet utility module, the still-scaffold Utility
Modules, and the from-source rebuild work for F-Droid reproducible builds.

This file is grouped by readiness, not by sprint. Each section answers:
what works today, what's the smallest next step, and what catches fire
if we skip it.

---

## 1. Tor end-to-end — two-device hardware proof (v0.6.5)

**Status.** `EmbeddedTorBackend` boots, bootstraps, publishes the HSv3
and surfaces the SOCKS port. `TorHiddenServiceTransport` binds a
loopback listener on `TorBackend.DEFAULT_HS_TARGET_PORT`, accepts
incoming streams, and SOCKS5-CONNECTs to peer `.onion:9091` for
outbound. `TorLink` carries length-prefixed frames identical to
`BleLink`'s wire format. `TransportSelector` (a `SyncTransportFacade`)
races BLE + Tor inside `MessageSyncService`; the Tor branch returns
null and parks when Tor isn't ready or no `peerOnion` is known.

**Smallest next step.**
- Install latest debug APK on two Android-26+ devices.
- Pair over BLE (Inviter+Invitee).
- Verify both devices' `trust_edge` rows carry a `peerOnion`.
- Wait for both `EmbeddedTorBackend.state` to reach `Ready`.
- Disable BLE on both devices (Settings → Bluetooth off).
- Send a message from one device; tap "Sync now" on the other.
  Expect the message to arrive; capture `logcat -s "Keystone"
  "TorHsTransport" "EmbeddedTorBackend"` for the round.
- Verify in logs that the winning branch was `connect: dialing
  *.onion:9091 via SOCKS :…` (initiator) or that the accept loop
  yielded an inbound TorLink (responder).

**Known soft spots to watch for.**
- The Tor dial is sequential across `knownPeerOnions(ownPub)` —
  first failure adds 30s of SOCKS timeout before the next try. In
  a future sprint, run dials in parallel and take the first success.
- Tor circuit build can take 5–60s on a cold daemon; the sync
  round budget is 30s. If the proof shows we routinely lose to
  the timeout, bump `DEFAULT_TIMEOUT_MS` or pre-warm circuits.
- `TorLink.send` writes through a `sendLock`; a hung peer that
  TCP-buffers without reading can block the next `send`. For the
  hardware proof we ignore — for production we want a write
  timeout on the socket.

**If skipped.** We have wired Tor into the sync engine in code but
have no evidence it actually carries a real exchange between two
phones. Until the proof lands, the "you can be in another country"
property is theoretical.

---

## 2. Revocation propagation (v0.6.6 — wired, awaits hardware proof)

**Status.** Wire format, anti-entropy round, trust-graph gate, and DB
persistence all landed in v0.6.6:
- `RevocationSyncMessage` (tags 0x31/0x32/0x33) — CBOR wire codec for
  HaveSet/Want/Push.
- `RevocationSyncRepository` — diff, fetch, ingest. Ingest path:
  signature verify → trust-graph issuer check (silent drop if
  Unknown/Quarantined) → DB upsert → in-memory graph update.
- `runRevocationSyncRound` — symmetric anti-entropy round, piggybacks
  on the same Noise transport as the messaging round.
- `MessageSyncService` — runs `runRevocationSyncRound` immediately
  after the messaging round completes. Failure non-fatal.
  `Result.revocationsReceived` carries the count to the UI.
- `TrustGraphService.buildGraph` — now hydrates revocations from the
  DB into the in-memory graph, so an already-Quarantined attacker
  can't sneak through the issuer check on a fresh session.

**Smallest remaining step (v0.6.6b).** An instrumented test that
exercises the Alice→Bob→Carol scenario end-to-end on a real Android
runtime (libsodium + SQLCipher + Room don't load in JVM unit tests).
For now the security rule reduces to `TrustGraphImpl.trustLevel(unknown)
== Unknown` (already covered by `TrustGraphImplTest`) followed by a
simple `if` in `RevocationSyncRepository.ingest` — the bridge has no
branches worth testing in isolation.

**If skipped.** Until v0.6.6b lands, the propagation is wired but
unproven on a real device. Same risk shape as the Tor leg in §1 — code
is correct as written, just not battle-tested.

---

## 3. Pairing-flow hardware proof on the v0.6 stack

**Why still on the list.** The original v0.1 hardware proof on 2026-05-18
covered Noise XX over BLE. The v0.2–v0.5 work has changed the pairing
path enough — the trust-graph authorization gate, the foreground
service, the new `peerOnion` field on the QR/edge, the kmp-tor daemon
spinning up alongside the BLE stack — that a fresh two-device pass on
the pairing happy path is overdue. (The Tor-leg portion lives in §1
above; this section is the BLE/handshake portion only.)

**Smallest next step.**
- Install latest debug APK on two Android-26+ devices.
- Inviter+Invitee handshake; capture
  `logcat -s "Keystone" "BluetoothGatt" "Noise" "EmbeddedTorBackend"`.
- Verify the `trust_edge` row carries `peerOnion` on both sides.
- Verify Tor bootstrap reaches `Ready` within ~60s on each device
  (battery, no captive portal).
- Verify the foreground notification stays up while the screen sleeps.

---

## 4. Per-feature Utility Modules

Each is its own milestone — they don't block each other.

### 4.1 Vault (`feature:vault`)
The encrypted personal-record store. Files + structured records
(passwords, recovery phrases, will instructions, family contacts)
sealed with XChaCha20-Poly1305 keyed off a HKDF-derived subkey.

- Module exists but is empty.
- Single-owner write/read first; per-record sharing via a per-record
  recipient set lands later.
- UI: list, detail, add, delete, lock. No search until indexing
  works on encrypted records.

### 4.2 Marketplace over BLE
Wallet exists (`core:currency`, `WalletService`); the sync engine
exists. The remaining work is wiring `SyncEngine.runSession()` to a
real BLE Link after handshake, plus the send-to-peer UI flow.

### 4.3 Coordination (`feature:coordination`)
Event/voting/announcement primitives over the same sync pipeline.
The data model is small (CBOR envelopes — `coord_envelope`); the
UI is the bulk of the work.

### 4.4 Directory (`feature:directory`)
Member list view over what the local TrustGraph already knows.
First slice: read-only with one filter ("members I have a Full
vouch edge to"). Avatars are deferred — they leak biometrics if
not handled carefully.

---

## 5. Transport & sync hardening

### 5.1 WiFi Direct Link
`WifiDirectTransport.connect()` is still a `TODO`. The work is:
- `WifiP2pManager.connect`, wait for `GROUP_OWNER_INFO`.
- Open a TCP socket on the group owner's IP at a fixed port,
  wrap it as a `Link` with the existing length-prefixed framing.
- Negotiate "switch transport" mid-session — start handshake on BLE,
  upgrade to WiFi Direct for bulk sync.

### 5.2 BLE robustness
- 8 BLE/handshake bugs were squashed pre-v0.2; the surface is
  reasonably stable. Remaining items: bonding (we only do
  unbonded BLE today; OEM bonding would speed reconnect), and
  a UI badge when MTU negotiation falls back to 23 bytes.

### 5.3 Sync robustness
- Pagination — a single round currently caps at 16,384-byte frames.
  For peers offline for weeks, we need multi-frame `Push` chunks
  with explicit termination.
- Backpressure — the SharedFlow buffers in the transports are 64
  entries; under load this `DROP_OLDESTs` discovery events and
  suspends accepted links. Add a TrustBadge to the dashboard
  counting drops in the last hour.

---

## 6. Trust graph completeness

### 6.1 K-paths quorum — DONE (v0.2 wave 2)
`TrustGraphImpl` ships the K-independent-paths quorum with 21 tests.
The vertex-and-edge-disjoint shortest-path algorithm is documented
in SECURITY-MODEL.md §3.8. Wired into
`HandshakeProtocolImpl.canIssueInvitations` so unauthorised inviters
abort before sending a cert.

### 6.2 Revocation propagation — see §2 above.

### 6.3 Reset & re-onboarding
- `AndroidKeystoreManager.reset()` is solid; surfaces a fresh identity
  on next launch.
- Missing: a "leave community" flow that signs a `Voluntary_Exit`
  revocation against your own pub before resetting, so peers age you
  out cleanly instead of carrying you as `Quarantined: peer offline`.

---

## 7. Cryptography hardening

### 7.1 Service UUID → BLAKE2s — DONE
`ServiceUuid.forCommunity` now uses `BLAKE2s-256(community_id ||
"KEYSTONE-SVC")[0..16]` via noise-java's `Blake2sMessageDigest`.
RFC 7693 vectors tested.

### 7.2 Forward secrecy on identity reset
Today, resetting the identity invalidates the SQLCipher key, which
makes any stored ciphertext unrecoverable — that's correct. The
matching peer doesn't know we reset; their sync attempts hit
`SignatureInvalid` because our pub changed. We need a
`KeyRotation` envelope so peers learn about a legitimate rotation
without confusing it with a compromise. Tracked in
SECURITY-MODEL.md §8.

### 7.3 Side-channel padding
`KeystoreManager.sign` re-derives the secret on every call — that's
intentional (no in-memory cache of the seed). Ed25519 sign with
libsodium is constant-time with respect to the key, not the
message length. For invitation certs this doesn't matter (fixed
length), but for arbitrary sync envelopes we should pad to the
nearest 32-byte boundary before signing.

---

## 8. UI polish

The v0.1 design system is in. What's still un-themed:
- `core:ui/QrRenderer` — currently renders pure black-on-white. Add
  the same monospace label rendering below the QR.
- The `marketplace` Wallet screens were built before the new theme.
  Apply `KeystonePanel` + `TrustBadge` throughout.
- Onboarding screens at small widths (320dp) — manual pass on a
  Pixel 4a-class device for error/empty states.
- A11y — nothing has been checked. Fingerprint groups should each
  be individually announceable; `TrustBadge` should have a content
  description.

---

## 9. Build & release hygiene

- **Release variant — DONE.** R8 minification + resource shrinking
  + signing config + BouncyCastle/JNA keep rules ship a working
  ~27 MB signed release APK from `:app:assembleRelease`. Reproducible-
  build flags still pending.
- **CI.** No CI today. Minimum: `assembleDebug` + the unit-test
  modules on every push, plus a grep for the forbidden-deps list.
- **F-Droid manifest — DRAFTED (v0.6.7).** `metadata/com.keystone.yml`
  + `fastlane/metadata/android/en-US/` are in the repo. Submission
  to `f-droid/fdroiddata` happens after a clean release-APK
  smoke-test on real hardware. The reproducible-build deviations
  (pre-built lazysodium AAR + pre-built kmp-tor binaries) are
  documented in `docs/FDROID.md`; the from-source rebuild work to
  earn the reproducible-build badge is deferred. See FDROID.md §2
  for the close-the-gap plan.
- **Layer-2 / Layer-3 peer update.** Layer-1 (manual URL pull,
  SSRF-gated) ships today. Layer-2 (auto-discovery over the BLE
  trust channel) and Layer-3 (K-quorum verification of the APK over
  the trust graph) are deferred until the Tor + revocation work
  settles.

---

## 10. Monero wallet (Utility Module — new, v0.7.0)

**Why.** Keystone already ships a community-internal scrip ledger
(`:core:currency` + `:feature:marketplace`) for in-circle value
transfer. A real cryptocurrency wallet is a different concern: cross-
community value, sovereign storage of savings, payments to anyone in
the world. Monero is the only mainstream cryptocurrency aligned with
the project's privacy posture — every transaction is sender/receiver/
amount blind by default. BTC is rejected (default-transparent); ARRR
is rejected (smaller ecosystem, Komodo-platform risk, sapling adoption
gaps).

**Architecture.** Remote-node-over-Tor only. No on-device chain — a
running daemon and 200 GB of blocks would shred phone battery and
storage. The remote node sees nothing meaningful (Monero blinds
everything), and mandatory routing through Keystone's embedded Tor
SOCKS proxy hides the user's IP from the node operator. Multi-node
round-robin so a single hostile node can't lie about the chain tip.

**Library choice.** Open question — see "GPL decision" below.
- **monerujo monero-android JNI binding** (m2049r/xmrwallet) is the
  battle-tested option. GPLv3.
- **Pure-Kotlin RPC-only client.** View-only wallets only (no spend).
  Apache-2.0 clean.

**GPL decision.** monerujo brings real send/receive but the resulting
APK is GPLv3 — the rest of Keystone is Apache-2.0 and only the
distributed *combined binary* shifts to GPL. F-Droid is fine with
this. Needs a top-level decision before adding the dep.

**Module shape.**
```
feature/monero-wallet/
  src/main/java/com/keystone/feature/monero/
    MoneroWalletService.kt      # façade, Hilt-injected
    MoneroNode.kt               # remote-node round-robin
    MoneroKeyStore.kt           # KEYSTONE/v1/monero subkey wrap
    rpc/                        # JSON-RPC over Tor SOCKS
    ui/                         # Compose screens
```
The module *does not* touch `:core:currency` or `:feature:marketplace`.

**Threat model adds (will land in SECURITY-MODEL.md §11).**
- Node operator can correlate connection IP with view-key
  registrations → mandatory embedded-Tor SOCKS for every RPC call.
- Spend key on disk → encrypted with a `KEYSTONE/v1/monero` keystore
  subkey; wallet file stored in SQLCipher-backed Room.
- Hostile remote node lies about chain tip → multi-node round-robin
  with majority-rules tip-check; tx submission to ≥2 nodes.
- View-key leak reveals all incoming payments → per-community sub-
  wallets so a compromised view key only exposes one community.

**Sprint v0.7.0a — Module scaffold (in progress).**
- GPL gate confirmed 2026-05-19 — combined APK shifts to GPLv3 once
  the JNI binding lands.
- `:feature:monero-wallet` module created, registered in
  `settings.gradle.kts` and `:app` dependencies.
- `MoneroNode` + `MoneroNodeRegistry` curate a Tor-reachable default
  pool; round-robin starting index randomised per session.
- `MoneroRpcClient` speaks JSON-RPC over a Tor SOCKS-DOMAINNAME
  socket (no DNS leak, no OkHttp dependency). First wired method:
  daemon's `/get_info`.
- `MoneroWalletService` exposes a `ConnectionStatus` flow the UI
  consumes; `awaitTorThenRefresh` drives the first connection
  attempt after Tor finishes bootstrapping.
- `MoneroCryptoEngine` interface defined; `NotImplementedMoneroCryptoEngine`
  bound by Hilt until v0.7.0b lands the JNI engine.
- `MoneroKeyStore` derives the `KEYSTONE/v1/monero` subkey for
  wallet-file encryption — seam established now, used later.
- Compose UI shows Tor + remote-node status + a clear
  "wallet engine not bundled" panel. No misleading "0 XMR" rows.
- `Socks5.kt` moved from `:app` to `:core:transport:api` so both
  `TorHiddenServiceTransport` and `MoneroRpcClient` can use it.

**Sprint v0.7.0b — JNI crypto engine.**
- Integrate Monerujo's `monero-android` JNI binding (or equivalent).
- Bind `MoneroJniCryptoEngine` in place of `NotImplementedMoneroCryptoEngine`.
- Wire view-key block scan; surface real balance + incoming-tx
  history rows on the wallet screen.
- License: combined APK ships under GPLv3 from this point on.

**Later sprints (v0.8.x).** Send flow + dust handling + per-community
subwallet + payment-id-on-receipt flow + UR-format airgap export.

---

## 11. Docs & ops

- All four docs were refreshed against the v0.6 codebase on 2026-05-19.
- Next doc pass: capture actual wire bytes from §3's two-device proof
  and append to PROTOCOLS.md as ground truth for future ports.
- Once revocation propagation lands, extend SECURITY-MODEL.md §3.6
  with the receive-side rules.

---

## Suggested sprint cadence

| Sprint | Status | Theme | Concrete output |
|--------|--------|-------|-----------------|
| v0.6.5 | open | Tor end-to-end (hardware) | Two-device handshake re-established over `.onion` after the BLE pair |
| **v0.6.6** | **landed** | **Revocation sync** | **Wire codec + anti-entropy round + trust-graph gate piggybacked on messaging sync** |
| v0.6.6b | open | Revocation sync — hardware proof | Alice→Bob→Carol scenario verified on real devices (instrumented test) |
| **v0.6.7** | **landed** | **F-Droid release plumbing** | **Manifest + fastlane metadata in repo; ready to submit to fdroiddata** |
| **v0.7.0a** | **landed** | **Monero wallet — scaffold** | **`:feature:monero-wallet` module wired, RPC over Tor SOCKS, "engine not bundled" UI** |
| v0.7.0b | next | Monero wallet — JNI engine | Bind monerujo's `monero-android`; real view-only balance + history rendering |
| v0.7.1 | open | Vault | First non-crypto Utility Module: encrypted personal records |
| v0.7.2 | open | Marketplace over BLE | Community-scrip wallet send works peer-to-peer |
| v0.7.3 | open | WiFi Direct Link | Bulk sync works over the high-bandwidth transport |
| v0.7.4 | open | Onion mirror remote-invite | Any peer can publish the signed APK on their `.onion` for joiners |
| v0.8.0 | open | Coordination + Directory | Read-only directory + voting |
| v0.8.1 | open | F-Droid reproducible builds + CI | From-source rebuild of lazysodium + tor; reproducible-build badge |
| v0.8.2 | open | Monero wallet — send | Spend flow, tx submission to ≥2 nodes, signing UI |
| v0.9.0 | open | KeyRotation | Legitimate identity reset propagates without quarantine |

Each sprint should leave behind a green build, a paragraph in
SECURITY-MODEL.md describing what changed in the threat model, and
a one-line bump in the `versionName` in `app/build.gradle.kts`.
