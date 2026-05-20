# Keystone — Project Notes

## Project Info
- **Framework**: Android (Kotlin, Jetpack Compose, Material3)
- **Package**: `com.keystone`
- **Min SDK**: 26 (Android 8.0 — Keystore + StrongBox availability cutoff)
- **Target SDK**: 34
- **Port**: 5034 (daemon registration only — Keystone has no server component)
- **Status**: 2026-05-20 — **end-to-end encrypted messaging confirmed
  working on real hardware for the first time** (commit `5afb67e` on
  `android`). 22-build session walked every layer of the BLE + Noise
  + sync stack; landed pairing, then messaging, with hardware proof at
  each step. Two paired phones now exchange Push / Ack / Read / End
  frames over Noise XX over BLE GATT, with friendly contact names and
  skip-onboarding UX. See [[keystone-messaging-works]] in auto-memory
  for the full bug list and [[keystone-ble-flow-control]] for the BLE
  pattern that hid the rest.
  
  Earlier shape from 2026-05-19 (v0.6.7): K-paths quorum + peer APK
  share + embedded Tor with keystore-pinned `.onion` + messaging sync
  racing BLE/Tor + F-Droid release plumbing. **Next sprint (v0.7.0)**:
  Monero wallet scaffold — remote-node-over-Tor only, GPL approval
  gate before adding the monerujo JNI binding. See `docs/FDROID.md`
  for the release process and `NEXT-STEPS.md` §10 for the Monero plan.

## Philosophy (read this before changing anything)

Keystone is designed for groups that distrust the platform layer. Every
architectural decision should be evaluated against three questions:

1. **Does this require trusting a third party?** If yes, reject it or
   abstract it behind a swappable interface.
2. **Does this leak metadata?** Plaintext over the wire, even non-payload
   metadata, is a liability. Default to authenticated encryption everywhere.
3. **Does this expand the trust surface?** New libraries, new transports,
   new permissions all enlarge attack surface. Justify every addition.

The integrity of the private network beats every other concern, including
developer convenience and feature velocity.

## Module Graph

```
app -> feature:* -> core:ui, core:trust, core:sync, core:database
                    core:trust -> core:identity -> core:crypto
                                    \
                                     -> core:database (trust_edge writes)
                                     -> core:transport:api (Link in HandshakeProtocol.open)
                    core:sync  -> core:transport:api -> {bluetooth, wifidirect, reticulum}
                    core:database -> core:crypto (SQLCipher key derivation)
                    app -> core:transport:api (TorBackend, TorHiddenServiceTransport)
```

Modules MUST NOT reach sideways across the graph. `feature:marketplace`
never imports `feature:vault`; they communicate through `core:sync` events.
The Tor backend lives in `:app` (not a transport module) because it
depends on `KeystoreManager` for HSv3 key derivation and on the Android
`Context` for kmp-tor's resource extraction.

## Code Standards

- **Kotlin only.** No Java. Explicit nullability everywhere.
- **No reflection in hot paths.** Hilt at the DI root; manual factories below.
- **No suspend in crypto APIs.** Crypto is synchronous and CPU-bound;
  callers dispatch to `Dispatchers.Default`.
- **No `String` for keys, nonces, or ciphertext.** Always `ByteArray`,
  always zeroed on disposal where the JVM permits.
- **No logging of payloads.** `Log.d` for protocol events only, never content.
  Production builds strip logs via R8 rules.
- **Fail closed.** When verification fails, the connection drops and the
  peer is quarantined. No "try again" path.

## Build Pipeline

```bash
cd /var/www/scws/projects/keystone
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2
```

Release builds work too — `:app:assembleRelease` produces a ~27 MB signed
APK with R8 + resource shrinking. Signing material is read from
`local.properties` (gitignored) or `-P` flags; placeholders live in
`gradle.properties` to document the schema.

Gradle heap is `1536m`. If KSP/Hilt OOMs, bump `org.gradle.jvmargs` to
`-Xmx2048m`. Latest green build: 2026-05-19 against `4a31bdb`.

### Notes on deps
- `noise-java` (rweather) pinned to commit `49377b6` via JitPack — no
  tagged release exists. Fallback if JitPack ever refuses: hand-rolled
  Noise XX over libsodium plus a ported BLAKE2s (libsodium exposes
  only BLAKE2b).
- `kmp-tor` is pinned at `runtime:2.0.0` + `resource-exec/noexec-tor:408.13.2`.
  **Do not bump past Kotlin 2.0** without updating this — every kmp-tor
  release after 2.0.0 is built against Kotlin 2.1+ whose stdlib metadata
  the 1.9 compiler cannot read.
- `bcprov-jdk18on:1.78.1` is declared on `:app` explicitly because
  `:feature:onboarding` pulls it transitively as `implementation`,
  hiding its classes from `:app` at compile time.
- `:core:transport:api` exposes `CommunityId` in its public surface, so
  its dep on `:core:identity` is `api(...)`, not `implementation(...)`.
  Do not downgrade.
- CameraX (`androidx.camera:*:1.3.1`) lives only in `:feature:onboarding`.
- ZXing (`com.google.zxing:core:3.5.2`) is used by `:core:ui/QrRenderer`
  for rendering and `:feature:onboarding/ScanPeerQrScreen` for decoding.

## Critical Files

- `docs/SECURITY-MODEL.md` — Web of Trust spec; treat as authoritative
- `docs/PROTOCOLS.md` — wire format spec
- `docs/LIBRARIES.md` — selection rationale + forbidden-deps deny list
- `core/crypto/.../KeystoreManager.kt` — hardware-keystore wrapper; do NOT
  replace with software keys
- `core/crypto/.../NoiseSessionImpl.kt` — the
  `Noise_XX_25519_ChaChaPoly_BLAKE2s` wrapper; one-shot lifecycle
- `core/crypto/.../Cbor.kt` — canonical CBOR codec shared by every
  signed-form structure (handshake QR, invitation cert, message envelope,
  sync envelopes)
- `core/trust/.../HandshakeProtocolImpl.kt` — Noise XX + cert exchange
  state machine; consults `TrustGraph.canIssueInvitations` before
  issuing; persists the TrustEdge (including `peerOnion`) on success
- `core/trust/.../HandshakeQrCodec.kt` — v1/v2 CBOR codec for the QR,
  carries the optional HSv3 `.onion`
- `core/trust/.../TrustGraphImpl.kt` — K-independent-paths quorum;
  vertex- and edge-disjoint shortest-path. **Algorithm spec in
  SECURITY-MODEL.md §3.8 — keep them in sync.**
- `core/trust/.../InvitationCertificate.kt` — canonical CBOR
  encode/decode/sign/verify. `RevocationCertificate` symmetric.
- `core/database/.../TrustEdgeEntity.kt` — includes `peerOnion`
  column (schema v5)
- `core/transport/api/.../Transport.kt` — the only abstraction every
  transport implements; framing is non-negotiable
- `core/transport/api/.../TorBackend.kt` — facade for the embedded Tor
  daemon (state, onion address, SOCKS port, target port)
- `core/transport/api/.../ServiceUuid.kt` — BLAKE2s(community ||
  "KEYSTONE-SVC") derivation
- `core/transport/bluetooth/.../BleTransport.kt` — GATT server + client,
  MTU negotiation, chunked framing
- `feature/messaging/.../MessageEnvelope.kt` + `.../sync/MessageSyncWire.kt` —
  signed envelope shape and Push/Ack/Read/End protocol
- `feature/onboarding/.../share/ApkShareServer.kt` — on-device HTTPS
  share server (self-signed cert per session)
- `feature/onboarding/.../share/PeerUpdateViewModel.kt` — Layer-1 peer
  update with SSRF gate (RFC 1918 + CGNAT only)
- `app/.../transport/EmbeddedTorBackend.kt` — kmp-tor wiring, HSv3 key
  derivation from `KEYSTONE/v1/tor-hs` subkey
- `app/.../transport/TorHsKey.kt` — Tor v3 ed25519 expanded-key
  derivation (RFC for HSv3 onion service)
- `app/.../transport/TorHiddenServiceTransport.kt` — loopback listener
  on `TorBackend.DEFAULT_HS_TARGET_PORT` + SOCKS5 dial of peer .onion;
  yields `TorLink` (length-prefixed framing, MAX_FRAME_BYTES=16384)
- `app/.../transport/TorLink.kt` — TCP-backed `Link` impl, framing
  matches BleLink so a Noise session is wire-format-portable
- `app/.../transport/Socks5.kt` — minimal RFC 1928 DOMAINNAME dialer
  used for `.onion` resolution through the local Tor proxy
- `app/.../transport/TransportSelector.kt` — façade over BLE+Tor;
  implements `core.transport.SyncTransportFacade`; provides
  `bleDiscoverAndConnect` + `dialFirstKnownOnion(ownPub)` +
  merged `acceptedLinks` for the messaging sync race
- `core/transport/api/.../SyncTransportFacade.kt` — bridge interface
  so `feature:messaging` can inject the selector without depending
  on `:app`
- `core/database/.../TrustEdgeDao.kt` — `peerOnionForEndpoints(a, b)`
  resolves an edge's `.onion` regardless of who issued the cert
- `app/.../transport/TransportForegroundService.kt` — reference-counted
  foreground service keeping BLE / share / Tor radio work alive across
  backgrounding
- `core/ui/.../KeystoneTheme.kt` — design system; new screens must use it

## What Is Built

### 2026-05-20 — end-to-end messaging on real hardware (commit `5afb67e`)

This is the first time the full stack — pairing AND messaging — was
demonstrably shown to work between two physical phones (Samsung
Galaxy A02s + Galaxy S23+). Until this date, every layer's claim of
"verified" was based on code shape, not observed behaviour. The 22
builds that landed this fix all attacked specific bugs the previous
runs surfaced; nothing in the sync stack was speculative.

Headline fixes (all in this commit):
- `KeystoreManager.sign()` no longer pads the message — the padding
  silently broke every signature in the project for years.
- `BleTransport` emits the accept Link on GATT CONNECTED, not on the
  first characteristic write. Without this, both peers became
  Initiator and Noise XX deadlocked.
- `MessageSyncService` uses a **hard pubkey-derived role bias** — the
  peer with the smaller pubkey runs dial-only, the larger runs
  accept-only. No race, no symmetric-role deadlock.
- `MessageSyncService` runs the race in a class-level `raceScope`
  detached from the caller's job — earlier the SupervisorJob was a
  child of `withTimeoutOrNull`, and structured concurrency stalled
  the body until losing deferreds completed (a Tor SOCKS dial can
  block 15s, well past the 12s outer timeout).
- `MessageSyncService.SESSION_TIMEOUT_MS` releases the round mutex
  when Noise XX wedges on a half-dead Link, so subsequent rounds
  can retry instead of sitting silent forever.
- `TransportSelector.drainStaleAccepted()` flushes buffered Tor
  inbounds at the start of every accept-only round so a stale
  circuit can't impersonate a fresh one.
- `TorHiddenServiceTransport` uses `receiveAsFlow` (not
  `consumeAsFlow`) so the Tor accept channel survives the first
  round's collector.
- **BLE write flow control** — `writeAck` on the client and
  per-device `notifyAcks` on the server. Both drainers wait for the
  local stack's ack between chunks. Android's BLE only queues 1-3
  outstanding NO_RESPONSE writes per peer; a 13KB Push frame
  chunking to ~27 writes silently dropped the tail without this.
  See [[keystone-ble-flow-control]] in auto-memory.
- `BleOutboundChunker` payload clamped to 512 (`GATT_MAX_ATTR_LEN`).
- `MessageSyncEngine.exchangeReadReceipts` split by role; the
  symmetric send-first form deadlocked.
- Mutual-scan UX (`PairScreen` + `OnboardingViewModel`) keeps the QR
  visible until the peer has scanned it.
- Skip-onboarding UX (`OnboardingRoot` + `OnboardingViewModel`)
  routes returning users past Welcome.
- Friendly contact names (`contact` table, schema v6, MIGRATION_5_6,
  `ContactDao`, `ContactEntity`, `ConversationViewModel.renameContact`,
  tap-to-rename header in `ConversationScreen`).

Diagnostic logging stays in across the entire stack — Noise XX step
traces, engine phase traces, `sendFrame`/`receiveFrame` byte counts.
Every one of them paid for itself across the 22 builds.

### v0.1 — handshake happy path (2026-05-18)
The whole `Welcome → RolePicker → KeyGeneration → DisplayQr → ScanPeerQr
→ CompareFingerprints → RunHandshake → Result` flow drives a real Noise
XX handshake over real BLE GATT and persists the trust edge in the
encrypted DB.

- `AndroidKeystoreManager` — StrongBox-preferred, TEE-floor, software-
  refused. HKDF-SHA256 subkey derivation. Derives X25519 from Ed25519
  for Noise.
- `KeystoneDatabaseImpl` — Room + SQLCipher keyed off
  `KEYSTONE/v1/db` subkey.
- `HandshakeProtocolImpl` — full Noise XX state machine for both
  Inviter and Invitee; channel binding via QR nonces; signed cert
  exchange; quarantine on every abort reason.
- `NoiseSessionImpl` — `Noise_XX_25519_ChaChaPoly_BLAKE2s` via
  noise-java; prologue mixes `"KEYSTONE/v1" || community_id ||
  nonce_inviter || nonce_invitee || eph_pubs`.
- `HandshakeQrCodec`, `InvitationCertificate`, `RevocationCertificate`
  — canonical CBOR over the shared `Cbor` codec.
- `BleTransport` — GATT server + client, MTU 247 request with
  fallback, length-prefixed framing chunked at MTU − 3.
- `SyncEngine` — HaveSet → Want → Push round; CBOR codec; per-community
  filtering (used today by the currency module).
- Design system — `KeystoneTheme` + `core:ui/components/{TrustBadge,
  KeystonePanel, StepIndicator, PulsingDot}`.
- Hilt — `CryptoModule`, `TransportModule`, `TorBackendModule` provide
  every singleton.

### v0.2 — trust-graph authorization + transport hardening
- `TrustGraphImpl` — K-independent-paths quorum (21 tests). Vertex- and
  edge-disjoint, revocation-aware. **Wired into**
  `HandshakeProtocolImpl.canIssueInvitations`: unauthorised inviters
  abort before sending a cert (wave 2).
- `TransportForegroundService` — reference-counted, single ongoing
  low-importance notification, `connectedDevice` foreground-service
  type. Keeps the BLE GATT server alive across backgrounding (wave 3).
- 8 critical BLE/handshake bug fixes — `BleLink.consumeAsFlow →
  receiveAsFlow`, single-drainer reassembly, eager client-side `BleLink`
  allocation, MTU fallback, characteristic `PROPERTY_WRITE_NO_RESPONSE`,
  disconnect-before-resume on `openGattClient`, `computeIfAbsent` link
  creation, clock-skew tolerance on cert verify.
- App icon (keystone-arch adaptive: foreground + background +
  monochrome themed variant).

### v0.3 — My Community + peer software distribution
- `MyCommunityScreen` — local trust-graph viewer; renders the device's
  own edges and fingerprints.
- **Peer APK share** (`feature:onboarding/share/`) — `ApkShareServer`
  + per-session self-signed TLS cert + QR with tap-to-copy URL +
  in-Compose mini-site. Bound to RFC 1918 IP picked by `LocalIp`.
- **Layer-1 peer update** — `PeerUpdateViewModel` resolves a peer's
  share URL through an SSRF gate (RFC 1918 + CGNAT 100.64/10; rejects
  loopback / link-local / multicast / IPv6 / public v4), fetches
  `/version.json`, streams `/keystone.apk` with on-the-fly SHA-256
  verification, hands off to system `PackageInstaller`. Dedicated
  consent-prompt UI for `canRequestPackageInstalls()` denial.
- Notification permission prompt on first launch.

### v0.4 — encrypted messaging
- `MessageEnvelope` — signed CBOR (`id, fromPub, toPub, createdAt, body`
  with Ed25519 signature over those 5 fields). Body cap 16 KB.
- `MessageStore` — `message_outbound` + `message_inbound` tables in
  the encrypted Room DB; conversation index over latest message per
  peer pub.
- `MessageSyncWire` — Push / Ack / End frame protocol over the
  existing Noise transport.
- `MessageSyncEngine` + `MessageSyncService` — runs a sync round
  whenever a BLE link is up between two trusted peers.
- `ConversationListScreen` + `ConversationScreen` — Compose UI for
  composing and reading messages.

### v0.5 — messaging polish
- Read receipts: outbound messages carry `read_at` state; a new
  `Read` tag (3) on the sync wire flips it when the recipient opens
  the conversation.
- Inbound notifications via `MessagingNotifier`; deep-link from
  notification → `ConversationScreen`.
- Trust graph visualization in MyCommunity.

### v0.6 — Tor backbone (sprints 1–5)
- **Sprint 1 — Tor foundation.** `TorBackend` interface in
  `core:transport:api` (state, onion, SOCKS port, target port).
  `TorBackend.Stub` reports `Unavailable`. UI status surface for Tor
  bootstrap progress.
- **Sprint 2 — embedded Tor.** `EmbeddedTorBackend` wires kmp-tor.
  Boots a real `tor` subprocess from the `-exec` resource pack.
  Picks a SOCKS port automatically. Publishes an HSv3 service whose
  Ed25519 key is derived deterministically from the
  `KEYSTONE/v1/tor-hs` keystore subkey — the `.onion` survives
  reinstalls as long as the keystore identity does.
- **Sprint 3 — address exchange.** `HandshakeQr` schema v2 adds an
  optional `onion: bstr(56)` field. `HandshakeProtocolImpl` samples
  the current `.onion` at QR-mint time and persists the peer's
  `.onion` onto the `TrustEdge` (`peerOnion`, schema v5) on
  successful handshake. Both v1 (no-onion) and v2 (with-onion) QRs
  decode for the short upgrade window.
- **Sprint 4 — `TorHiddenServiceTransport`.** Loopback ServerSocket
  bound on `TorBackend.DEFAULT_HS_TARGET_PORT`; accept loop yields
  `TorLink` instances. Outbound `connect()` SOCKS5-CONNECTs through
  the local Tor proxy to a peer `.onion:9091`. `TorLink` carries
  Noise frames with the same length-prefix framing as `BleLink`.
- **Sprint 5 — sync over Tor.** `TransportSelector` (implements
  `SyncTransportFacade`) owns both transports + the trust-edge DB.
  `MessageSyncService` injects the facade and races three paths
  per round: BLE-discover+connect (Initiator), Tor-dial-any-known-
  onion (Initiator), merged accept flow (Responder). First Link
  wins; losers cancelled, dangling losers' Links closed if they
  resolved in the same tick. `TrustEdgeDao.peerOnionForEndpoints`
  resolves the address regardless of which side issued the cert.
- **Onboarding collapse to 6 screens** + QR scanner fixes for
  phone-screen reading conditions (multi-frame retry, TRY_HARDER
  hints).

## What's NOT Built Yet (see NEXT-STEPS.md for the full plan)

- **Two-device hardware proof of the Tor leg over Tor only** — BLE +
  messaging now demonstrably works. Tor is wired but every successful
  round in the 2026-05-20 session used the BLE branch (in-room test).
  The Tor branch needs its own two-device proof with BLE turned off.
- **Revocation propagation** — local-only today; peer-to-peer
  propagation via sync is the next-highest priority after the Tor
  hardware proof.
- **Vault** feature (encrypted personal-record store) — empty module.
- **Marketplace over BLE** — wallet exists but doesn't yet sync over
  a real Link.
- **WiFi Direct `connect()`** — discovery only today.
- **Reticulum/LoRa** — `:core:transport:reticulum` is a stub; no
  Kotlin binding exists yet.
- **F-Droid manifest, CI** — reproducible-build flags + a banned-deps
  grep are the smallest viable CI.
- **Coordination, Directory** features.
- **Layer-2 / Layer-3 peer-update** — automatic discovery via BLE
  trust channel (L2) and K-quorum APK verification (L3).
- **KeyRotation envelope** — legitimate identity reset currently
  looks identical to a compromise.

## Memory / Plan State

End-to-end messaging works on hardware as of 2026-05-20 (commit
`5afb67e`). Live work item is now the two-device hardware proof of
the Tor leg WITH BLE OFF, followed by revocation propagation
(NEXT-STEPS.md §1, §2).

Auto-memory references for this work:
- [[keystone-messaging-works]] — 22-build journey, all fixes
- [[keystone-ble-flow-control]] — the BLE pattern that hid the rest
- [[keystone-first-pairing-milestone]] — earlier same-day milestone
- [[keystone-sign-padding-bug]] — 5-year-old root cause
- [[keystone-feedback-verify-baseline]] — apply same skepticism to
  remaining unverified layers (Tor, revocation, Monero, vault).
