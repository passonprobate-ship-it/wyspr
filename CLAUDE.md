# Wyspr — Project Notes

## Project Info
- **Framework**: Android (Kotlin, Jetpack Compose, Material3)
- **Package**: `com.wyspr`
- **Min SDK**: 26 (Android 8.0 — Keystore + StrongBox availability cutoff)
- **Target SDK**: 34
- **Port**: 5034 (daemon registration only — Wyspr has no server component)
- **Status**: 2026-06-14 — **v0.9.9 (build 30).** Coordination
  feature (events/scheduling) — **hardware-verified.** New
  `feature:coordination` module:
  any Full-trust community member creates events (title, description,
  location, start/end time); others RSVP (going/maybe/declined).
  `EventEnvelope` (12-field signed CBOR, creator-only LWW) and
  `RsvpEnvelope` (7-field signed CBOR, composite key) sync via
  anti-entropy (tags `0x40`–`0x45`) on a `CoordinationSyncRound`
  that piggybacks existing Noise sessions in `MessageSyncService`,
  after the key-rotation round. DB schema v24 (`coordination_event`
  + `coordination_rsvp`). UI under Settings → Coordination → Events.
  **HARDWARE-VERIFIED 2026-06-14** (S23 + A02s, over Tor): events and
  RSVPs (going/maybe/declined) sync both directions and render in the
  UI; `CoordSync` diagnostic logging confirmed the full HaveSet/Want/
  Push exchange + ingest. Two fixes shipped during verification
  (v0.9.9 / build 30, branch `android`):
  (1) **Root-cause fix — Events screen now drives sync.** Coordination
  rode on `MessageSyncService.runOnce()`, but that only runs from chat
  ViewModels, so an event never synced while a user sat on the Events
  screen. Added `SyncTrigger` (core:transport:api) ←
  `MessageSyncTrigger` (feature:messaging, in-flight-guarded wrapper of
  `runOnce`) ← injected into `EventListViewModel`'s 8s loop. See
  [[keystone_sync_only_on_messaging_screens]].
  (2) **Event-received notifications + deep-link.** `CoordinationNotifier`
  (core:transport:api) ← `AndroidCoordinationNotifier` (:app), channel
  `events_v2` @ IMPORTANCE_HIGH (heads-up + sound; legacy `events`
  channel deleted), fires only on `isNew` ingest, deep-links via
  `ACTION_OPEN_EVENT` → `MainActivity` → `WysprNavHost`
  `events?eventId=<hex>` → `CoordinationRoot` event detail. Chat
  notifications already existed (`AndroidMessagingNotifier`).
  NOTE: v0.9.8 was committed (`1fb604b`) without a version bump; the
  28→29 / 0.9.7→0.9.8 stamp was applied 2026-06-14. v0.9.9 (build 30)
  stamps the hardware verification + the two fixes above.

  Earlier (2026-05-25): **v0.9.7 (build 28).** WiFi Direct
  transport fully wired into the sync stack. `WifiDirectTransport`
  (DNS-SD discovery, P2P group formation, TCP port 9094, u32
  framing) now races alongside BLE and Tor in `MessageSyncService`.
  DI via Hilt, `SyncTransportFacade` interface extended. Hardware
  verification pending.

  Earlier (2026-05-25): **v0.9.6 (build 27).** Monero wallet
  **hardware-verified** — Molly SDK native library loads, Tor
  bootstraps in ~2s, wallet opens from encrypted disk, RPC calls
  flow through Tor SOCKS to Rino community node. XMR/USD price
  from CoinGecko displayed on wallet balance panel. Node connection
  status LED (gray/orange/green/red) with node label on wallet
  screen. Only untested: actual send/receive XMR transaction.

  Earlier (2026-05-24): **v0.9.5 (build 26).** Payment notifications
  ("Received 0.1 XMR from Bob") — `PaymentNotifier` interface +
  `AndroidPaymentNotifier` with dedicated "Payments" channel.
  `MoneroWalletService` diffs tx hashes across ledger emissions;
  reverse-lookups peer via `PeerSubAddressMintDao.forSubAddress`.
  "Buy the developer a beer" XMR donation row in Settings.
  13-finding bug sweep: `applyRotation` self-edge double-delete +
  `certSigner` rekey, `ConversationViewModel` flow leak, group
  message REPLACE→IGNORE, `FileBubble` path traversal,
  `ProfileFetcher` OOM cap, audio temp file cleanup, schema v22
  (key_rotation.newPub index). Auto-scroll always on send/receive.

  Earlier (2026-05-24): **v0.9.4 (build 25).** Auto key rotation
  (90-day interval) with cert chaining. `KeyRotationSettings` stores
  the last rotation timestamp in SharedPreferences; `MainActivity`
  checks on every cold start and silently rotates if due.
  `KeyRotationSyncRepository` refactored: `storeIfValid()` persists
  all signature-verified certs, `resolveAndIngestBatch()` applies them
  in topological order with backward chain walking (up to depth 10)
  for peers who missed intermediate rotations. `KeyRotationDao.byNewPub`
  enables the chain walk. Chat composer unified: `+` drawer for
  Photo/File/Location, mic + send always visible. Location sharing
  fixed in 1-on-1 chats (callback existed but had no button). Voice
  notes and file sharing added to group chats.

  Earlier (2026-05-24): **v0.9.3 (build 24).** Key rotation envelope.
  `KeyRotationCertificate` (signed by old key, 7-field CBOR) with
  anti-entropy propagation (tags `0x34`/`0x35`/`0x36`), trust-edge +
  contact + message rekeying on ingestion, issuer-side "Rotate identity"
  UI in Settings, `plantSeed()` on KeystoreManager for pre-generating the
  new identity. DB schema v21 (`key_rotation` table). The old key is
  retired (added to revoked set) so it cannot issue further operations.
  Closes the longest-standing open question in SECURITY-MODEL.md.

  Earlier (2026-05-23): **v0.9.2 (build 23).** Full codebase
  security audit → 68 bug fixes across crypto, transport, messaging,
  database, trust, UI, and build config. Tor bootstrap watchdog
  landed — auto-restarts the daemon if bootstrap stalls >120s.
  **Tor-only messaging hardware-verified** between S23 + A02s with
  BLE off — the longest-standing roadmap item is closed. Noise
  session reuse confirmed working (sub-second cached rounds over
  Tor). Revocation propagation shipped — users can now revoke a
  peer from the conversation details sheet; the revocation cert
  propagates to all peers via the existing anti-entropy sync
  protocol. UI polish: relative timestamps on chat list, colored
  initial avatars, scroll-to-bottom FAB, "Add a contact" copy,
  faster message rendering (reactions load async). F-Droid metadata
  updated (Keystone → Wyspr, v0.9.1 build entry, fastlane
  descriptions rewritten).

  Earlier (2026-05-22): Sprints 1+2 of TOR-ACROSS-WEB landed
  (Noise session reuse, multi-host mailbox, battery-opt prompt).

  Earlier (2026-05-21): v0.8.3 WhatsApp-shaped UX overhaul.
  Three-tab bottom nav, reply quoting, contact notes, onion
  profile page glow-up. v0.8.0 shipped async mailbox delivery.

  Earlier (2026-05-20): end-to-end encrypted messaging confirmed
  working on real hardware for the first time (commit `5afb67e`).
  22-build session. See [[wyspr-messaging-works]] in auto-memory.

## Philosophy (read this before changing anything)

Wyspr is private, censorship-resistant messaging and payments. Every
architectural decision should be evaluated against three questions:

1. **Is it private by default?** No opt-in privacy modes. No "we promise
   not to look." The wire format must hide content AND metadata, and the
   protocol must work without revealing IP, identity, or social graph to
   any third party.
2. **Is it censorship-resistant?** No single chokepoint that can disable
   the network — no central server, no app-store dependency, no fixed
   set of relays. Pairing happens device-to-device; transports route
   through Tor or local radio; the app updates peer-to-peer.
3. **Does it expand the trust surface?** New libraries, new transports,
   new permissions all enlarge attack surface. Justify every addition.

Privacy and censorship-resistance beat every other concern, including
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
cd /var/www/scws/projects/wyspr
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2
```

Release builds work too — `:app:assembleRelease` produces a ~27 MB signed
APK with R8 + resource shrinking. Signing material is read from
`local.properties` (gitignored) or `-P` flags; placeholders live in
`gradle.properties` to document the schema.

Gradle heap is `1536m`. If KSP/Hilt OOMs, bump `org.gradle.jvmargs` to
`-Xmx2048m`. Latest green build: 2026-05-23 against `a44495e`.

### Notes on deps
- `noise-java` (rweather) pinned to commit `49377b6` via JitPack — no
  tagged release exists. Fallback if JitPack ever refuses: hand-rolled
  Noise XX over libsodium plus a ported BLAKE2s (libsodium exposes
  only BLAKE2b).
- `kmp-tor` is pinned at `runtime:2.0.0` + `resource-exec/noexec-tor:408.13.2`.
  The Kotlin version constraint (was 1.9, now 2.1.20) is resolved but
  the pin is kept intentional — bump deliberately and test.
- `bcprov-jdk18on:1.78.1` is declared on `:app` explicitly because
  `:feature:onboarding` pulls it transitively as `implementation`,
  hiding its classes from `:app` at compile time.
- `:core:transport:api` exposes `CommunityId` in its public surface, so
  its dep on `:core:identity` is `api(...)`, not `implementation(...)`.
  Do not downgrade.
- CameraX (`androidx.camera:*:1.3.1`) lives only in `:feature:onboarding`.
- ZXing (`com.google.zxing:core:3.5.3`) is exposed as `api` from
  `:core:ui`. Feature modules get it transitively — do not re-declare.

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
- `core/trust/.../KeyRotationCertificate.kt` — signed cert linking
  old pub → new pub; `KeyRotationSyncRound.kt` propagates it
- `app/.../KeyRotationService.kt` — issuer-side orchestrator:
  sign cert, reset, plant new seed, re-open DB; auto-rotation via
  `KeyRotationSettings`
- `core/ui/.../settings/KeyRotationSettings.kt` — SharedPreferences
  store for 90-day auto-rotation timer
- `core/database/.../TrustEdgeEntity.kt` — includes `peerOnion`
  column (schema v5)
- `core/transport/api/.../Transport.kt` — the only abstraction every
  transport implements; framing is non-negotiable
- `core/transport/api/.../TorBackend.kt` — facade for the embedded Tor
  daemon (state, onion address, SOCKS port, target port)
- `core/transport/api/.../ServiceUuid.kt` — BLAKE2s(community ||
  "WYSPR-SVC") derivation
- `core/transport/bluetooth/.../BleTransport.kt` — GATT server + client,
  MTU negotiation, chunked framing
- `feature/messaging/.../MessageEnvelope.kt` + `.../sync/MessageSyncWire.kt` —
  signed envelope shape and Push/Ack/Read/End protocol
- `feature/onboarding/.../share/ApkShareServer.kt` — on-device HTTPS
  share server (self-signed cert per session)
- `feature/onboarding/.../share/PeerUpdateViewModel.kt` — Layer-1 peer
  update with SSRF gate (RFC 1918 + CGNAT only)
- `app/.../transport/EmbeddedTorBackend.kt` — kmp-tor wiring, HSv3 key
  derivation from `WYSPR/v1/tor-hs` subkey
- `app/.../transport/TorHsKey.kt` — Tor v3 ed25519 expanded-key
  derivation (RFC for HSv3 onion service)
- `app/.../transport/TorHiddenServiceTransport.kt` — loopback listener
  on `TorBackend.DEFAULT_HS_TARGET_PORT` + SOCKS5 dial of peer .onion;
  yields `TorLink` (u32 length-prefixed framing, MAX_FRAME_BYTES=262144)
- `app/.../transport/TorLink.kt` — TCP-backed `Link` impl, u32 framing
  matches BleLink so a Noise session is wire-format-portable
- `app/.../transport/Socks5.kt` — minimal RFC 1928 DOMAINNAME dialer
  used for `.onion` resolution through the local Tor proxy
- `app/.../transport/TransportSelector.kt` — façade over BLE+WiFi
  Direct+Tor; implements `core.transport.SyncTransportFacade`;
  provides `bleDiscoverAndConnect` + `wifiDirectDiscoverAndConnect` +
  `dialFirstKnownOnion(ownPub)` + merged `acceptedLinks` for the
  messaging sync race
- `core/transport/api/.../SyncTransportFacade.kt` — bridge interface
  so `feature:messaging` can inject the selector without depending
  on `:app`
- `core/database/.../TrustEdgeDao.kt` — `peerOnionForEndpoints(a, b)`
  resolves an edge's `.onion` regardless of who issued the cert
- `app/.../transport/TransportForegroundService.kt` — reference-counted
  foreground service keeping BLE / share / Tor radio work alive across
  backgrounding
- `core/ui/.../WysprTheme.kt` — design system; new screens must use it

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
  See [[wyspr-ble-flow-control]] in auto-memory.
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
- `WysprDatabaseImpl` — Room + SQLCipher keyed off
  `WYSPR/v1/db` subkey.
- `HandshakeProtocolImpl` — full Noise XX state machine for both
  Inviter and Invitee; channel binding via QR nonces; signed cert
  exchange; quarantine on every abort reason.
- `NoiseSessionImpl` — `Noise_XX_25519_ChaChaPoly_BLAKE2s` via
  noise-java; prologue mixes `"WYSPR/v1" || community_id ||
  nonce_inviter || nonce_invitee || eph_pubs`.
- `HandshakeQrCodec`, `InvitationCertificate`, `RevocationCertificate`
  — canonical CBOR over the shared `Cbor` codec.
- `BleTransport` — GATT server + client, MTU 247 request with
  fallback, length-prefixed framing chunked at MTU − 3.
- `SyncEngine` — HaveSet → Want → Push round; CBOR codec; per-community
  filtering (used today by the currency module).
- Design system — `WysprTheme` + `core:ui/components/{TrustBadge,
  WysprPanel, StepIndicator, PulsingDot}`.
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
- App icon (wyspr-arch adaptive: foreground + background +
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
  `/version.json`, streams `/wyspr.apk` with on-the-fly SHA-256
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
  `WYSPR/v1/tor-hs` keystore subkey — the `.onion` survives
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

### 2026-05-23 — v0.9.2: full audit, Tor-only verified, revocation propagation

**68-fix codebase audit.** 6-agent parallel sweep across 279 Kotlin
files. 9 CRITICAL, 15 HIGH, 22 MEDIUM, 15 LOW findings fixed:

Key CRITICAL fixes:
- Noise protocol chunking for payloads >65KB (noise-java enforces
  the 65535 spec limit; large Push frames were crashing)
- Group messages now reach ALL members (per-recipient delivery
  tracking via `group_message_delivery` table, schema v20)
- Disappearing messages WAL checkpoint (deleted rows were
  recoverable from SQLite write-ahead log)
- MessageDao upsert REPLACE → IGNORE (concurrent ingest race)
- HandshakeState destroyed after Noise XX split (key material
  was persisting in heap for session lifetime)

**Tor bootstrap watchdog.** `EmbeddedTorBackend` now monitors
bootstrap progress. If `isBootstrapped` stays false for 120s after
daemon start, the watchdog stops and restarts the daemon. Proved
itself on the S23 during the hardware test session — stuck at 5%,
watchdog fired, restarted, hit 100% in <2s. Also removed the
`RuntimeEvent.READY` observer that raced with the STATE observer.

**Tor-only messaging hardware-verified.** BLE off on both phones
(S23 + A02s). Messages flow through Tor hidden service circuits
with Noise session reuse (cached rounds complete in ~6s). This
closes the longest-standing roadmap item.

**Revocation propagation.** `RevocationCertificate.issue()` signs
a revocation cert using KeystoreManager. `TrustGraphService.revokePeer()`
orchestrates creation + trust-level check + DB persist. UI: "Revoke
this peer" button in PeerDetailsSheet with confirmation dialog.
Revocations propagate to all peers via the existing
`RevocationSyncRound` anti-entropy protocol (HaveSet/Want/Push).

**UI polish:**
- Relative timestamps on conversation list ("2m", "3h", "2d")
- Colored initial circle avatars on conversation rows
- Scroll-to-bottom FAB when scrolled up in conversation
- "Add a contact" replaces "Pair a peer" throughout
- Faster message rendering (reactions load async, removing
  flatMapLatest chain that blocked first paint ~10s)
- Paired contacts visible on Chats tab immediately after pairing
- Sync timeouts increased (12s→30s link, 20s→45s session)

**Build & infra:**
- Room schema export enabled (exportSchema=true + KSP schemaLocation)
- ProGuard rules fixed (com.keystone → com.wyspr)
- F-Droid metadata updated (Keystone → Wyspr, v0.9.1 build entry)
- @TorTransport Hilt qualifier for clean DI
- ZXing version deduplicated (3.5.3 from core:ui only)
- Periodic GC of seen_cert_nonce (7d) and handshake_quarantine

### 2026-05-22 — Tor stuck-state diagnosed and FIXED

The stuck bootstrap problem (kmp-tor starting with `--DisableNetwork 1`
and never flipping it off) is now handled by the watchdog added in
v0.9.2. The `RuntimeEvent.READY` observer that prematurely set state
to Ready has been removed. See the 2026-05-23 entry above.

### 2026-05-22 — Sprint 2 (partial) of TOR-ACROSS-WEB: multi-host mailbox + battery-opt prompt

Two pieces of Sprint 2 landed, both code-complete and build-clean,
neither hardware-verified yet:

**1. Multi-host mailbox bindings (schema v14).** `MailboxBindingEntity`
now uses a composite primary key `(owner_pub, mailbox_pub)`. An owner
can publish N bindings (one per host they delegate to). Migration
`MIGRATION_13_14` rebuilds the table preserving existing rows.

- `MailboxBindingService.forOwner` returns `List<MailboxBinding>`;
  `myBindings`/`myBindingsFlow` replace the singular variants (which
  remain as `myBinding`/`myBindingFlow` adapters for UI back-compat).
- New `addOwn` (composite-PK upsert), `removeOwnHost` (per-host
  delete), `clearOwn` (delete-all-for-owner) on the service.
- `setOwn` kept as `@Deprecated` alias for `addOwn` — semantics
  changed (insert one row, doesn't evict siblings).
- `MessageSyncEngine.sealForMailboxPeer` and `mailboxPullPhase`
  iterate over the recipient's full binding list. Sender seals if
  the sync partner is ANY of the recipient's hosts; receiver pulls
  if the sync partner is ANY of own hosts.
- `MessageSyncEngine.pushPending` broadcasts ALL of own bindings
  every round (was: just the one). Wire-compatible with v0.8.x
  peers — the `mailboxBindings` list already supported N entries.
- UI not yet updated for "add another host" — `MailboxClientViewModel`
  exposes `myBindings: StateFlow<List<...>>` so the polish pass can
  enumerate, but the existing single-mailbox UI keeps working via
  the `myBinding` first-of-list adapter.

**2. Battery-opt exemption banner.** `MailboxScreen` shows a tertiary-
container card whenever host mode is on AND the app isn't already
exempt from Doze. "Open battery settings" fires the
`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent for
this app's package, with the global-list intent as a fallback. The
permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared in the
manifest. The card auto-dismisses once `PowerManager.isIgnoringBatteryOptimizations`
flips true.

**Deferred from Sprint 2 (intentionally):**
- BOOT_COMPLETED receiver — the API-26/31/34 restrictions on starting
  a foreground service from a boot broadcast deserve a dedicated
  session with hardware to test. Without it, a phone reboot leaves
  the mailbox offline until the user opens the app once. Document
  this as a known limitation; reach for it after Sprint 1+2 hardware
  verification.
- OEM-killer deep-links (Samsung/Xiaomi/OPPO/Vivo/Huawei via
  `dontkillmyapp.com`) — field-test-and-iterate work, not buildable
  blind.
- Multi-host UI (add another, list, remove one) — the data layer
  supports it; the screen surface remains single-host.

Files touched:
- `core/database/.../entities/MailboxBindingEntity.kt`
- `core/database/.../dao/MailboxBindingDao.kt`
- `core/database/.../WysprRoomDatabase.kt` (v14 + MIGRATION_13_14)
- `core/database/.../WysprDatabaseImpl.kt` (register migration)
- `feature/messaging/.../mailbox/MailboxBindingService.kt`
- `feature/messaging/.../mailbox/MailboxClientViewModel.kt`
- `feature/messaging/.../sync/MessageSyncEngine.kt`
- `feature/messaging/.../mailbox/screens/MailboxScreen.kt`
- `app/src/main/AndroidManifest.xml`

### 2026-05-22 — Sprint 1 of TOR-ACROSS-WEB: Noise-session reuse

Code-complete, **NOT YET HARDWARE-VERIFIED**. Builds clean against
the v0.8.3 tree; messaging unit tests pass.

`MessageSyncService` now keeps the `(Link, NoiseSession)` pair alive
across rounds when the underlying link is a Tor circuit. The next
round on the same peer skips Tor descriptor lookup, SOCKS5 dial,
and the 6-message Noise XX handshake — expected per-round cost
drops from ~30s (current cold-circuit) to sub-second.

- New private `cachedSessions: MutableMap<PeerKey, CachedSession>`
  field on `MessageSyncService`, guarded by the existing round
  Mutex.
- `runOnce` checks the cache before `transports.startAll`. Hit →
  `tryCachedRound` runs the engine directly on the cached session.
  Miss → existing race/handshake path, then `maybeCacheTorSession`
  stages the result for next round (Tor links only — BLE links
  continue to close as before).
- `openSessionAndSync` now returns the `NoiseSession` to the
  caller on success (so it can be cached) rather than closing it
  in `finally`. A `callerOwnsNoise` flag preserves close-on-
  failure semantics.
- Eviction is on-failure only. No keepalive: the 8s auto-sync
  cycle is itself the implicit keepalive, and Tor circuits use
  TCP keepalive for longer idle periods.
- BLE-backed sessions are explicitly not cached (checked via
  `link.endpoint.kind == Transport.Kind.TorHiddenService`); BLE
  rounds remain identical to v0.8.3.

Files touched: `feature/messaging/.../MessageSyncService.kt` only.
No interface changes; no `NoiseSession`/`NoiseSessionImpl` changes;
no `TransportSelector` changes.

Hardware proof still owed: two-device test with BLE off, observing
round 1 cost (~30s cold) followed by round 2 sub-second. Apply the
[[wyspr-feedback-verify-baseline]] rule — code that builds is not
the same as code that works.

### 2026-05-24 — v0.9.3: Key rotation envelope

**KeyRotationCertificate** — signed by the old key, proves the holder
authorized the transition to a new identity. 7-field canonical CBOR:
`[version, oldPub, newPub, communityId, issuedAt, newOnion|null,
signature]`.

**Anti-entropy propagation** — tags `0x34`/`0x35`/`0x36` mirror the
revocation sync round pattern. Runs after `runRevocationSyncRound` on
the same Noise link at both sync sites in `MessageSyncService`.

**Ingestion** — `KeyRotationSyncRepository.ingest` verifies community,
signature, time bounds, and issuer trust level. Rejects quarantined
keys, rejects duplicate rotations to a different newPub. On accept:
rekeys trust edges (`fromPub`/`toPub`), contacts (`peerPub`), and
message threads (`thread_pub`, `from_pub`, `to_pub`). Atomically
updates `peerOnion` from the cert. Retires the old key in the trust
graph (added to revoked set).

**Issuer-side flow** — "Rotate identity" in Settings. `KeyRotationService`
generates a new seed, derives the new Ed25519 pubkey and `.onion`,
signs the rotation cert with the old key, persists it to a pending file,
resets the keystore, plants the new seed via `KeystoreManager.plantSeed()`,
re-opens the DB, persists the cert, and re-creates the community
membership. The pending file guards against data loss if the process
dies mid-rotation.

**Database** — schema v21: `key_rotation` table (composite PK
`(oldPub, newPub)`). `KeyRotationDao` with upsert, all, byOldPub.
`MessageDao` gains `rekeyThread`/`rekeyFromPub`/`rekeyToPub`.

**KeystoreManager** — `plantSeed(seed)` added to the interface and
`AndroidKeystoreManager`. Wraps the pre-generated seed with the
wrapping key and writes it to the seed file.

New files: `KeyRotationCertificate.kt`, `KeyRotationSyncMessage.kt`,
`KeyRotationSyncRepository.kt`, `KeyRotationSyncRound.kt`,
`KeyRotationEntity.kt`, `KeyRotationDao.kt`, `KeyRotationService.kt`.

Hardware verification owed — two-device test where one side rotates
and the other picks up the cert on next sync.

### 2026-05-24 — v0.9.4: Auto key rotation + cert chaining + composer cleanup

**90-day auto-rotation.** `KeyRotationSettings` (SharedPreferences)
stores `lastRotationEpochSeconds`. `MainActivity` checks on cold start
inside the biometric-gated `LaunchedEffect` — if 90+ days have passed,
`KeyRotationService.rotate()` runs silently. `seedIfNeeded()` initializes
the clock on first launch. Both manual and auto rotation reset the timer.

**Cert chaining.** `KeyRotationSyncRepository` refactored into
`storeIfValid()` (sig + community check, persist to DB) and
`applyIfTrusted()` (trust-level gate, rekey edges/contacts/messages).
New `resolveAndIngestBatch()` applies certs in topological order: first
pass applies certs whose `oldPub` is already trusted, then
`buildChainToTrusted()` walks backward through the DB (via new
`KeyRotationDao.byNewPub`) to find a chain of stored certs leading to
a trusted root, and applies the chain oldest-first. Max chain depth 10
(~2.5 years of missed syncs). Cycle detection prevents infinite loops.

**Chat composer unified.** Both 1-on-1 and group composers now share
the same layout: `+` button expands an `AnimatedVisibility` tray with
Photo / File / Location chips; mic button and send button remain
top-level. Location sharing fixed in 1-on-1 chats (callback existed
but no button rendered it). Voice notes and file sharing added to
group chats (`GroupConversationViewModel.sendVoiceNote`,
`sendFile`).

New files: `KeyRotationSettings.kt`.

### 2026-05-24 — v0.9.5: Payment notifications + bug sweep + donate

**Payment notifications.** `PaymentNotifier` interface in
`core:transport:api` + `AndroidPaymentNotifier` in `:app` with a
dedicated "Payments" notification channel. `MoneroWalletService`
tracks seen tx hashes; first ledger emission seeds the set (no
notifications for historical txs), subsequent emissions fire
notifications for new inbound transactions. Peer reverse-lookup via
`PeerSubAddressMintDao.forSubAddress` resolves which contact paid —
notification shows "Received 0.5 XMR — From Bob" or fingerprint.

**13-finding bug sweep.** CRITICAL: `applyRotation` self-edge
double-delete fixed (single-pass rekey), `certSigner` now rekeyed,
`ConversationViewModel` reaction flow leak fixed (nested `.collect`
→ `flatMapLatest`), `GroupConversationViewModel` CancellationException
rethrow. SECURITY: `GroupMessageDao` REPLACE→IGNORE, `FileBubble`
path traversal sanitization, `ProfileFetcher` 1MB body cap. HIGH:
audio temp file cleanup, schema v22 (`key_rotation.newPub` index),
`GroupConversationScreen` stable LazyColumn keys, `MainActivity`
rotation dispatched to IO, pending rotation recovery on startup,
cross-community haveSet filtering.

**"Buy the developer a beer"** — expandable XMR donation row in
Settings with copy-to-clipboard.

New files: `PaymentNotifier.kt`, `AndroidPaymentNotifier.kt`.

### 2026-05-25 — v0.9.8: Coordination feature (events/scheduling)

**New `feature:coordination` module.** Any Full-trust community
member can create events; other members RSVP. Events and RSVPs are
signed CBOR envelopes synced via anti-entropy, piggybacking the
existing Noise sessions in `MessageSyncService`.

- `EventEnvelope` — 12-field signed CBOR, creator-only
  last-writer-wins.
- `RsvpEnvelope` — 7-field signed CBOR, composite key
  `(eventId, responderPub)`, statuses going/maybe/declined.
- `CoordinationSyncRound` — HaveSet/Want/Push on tags `0x40`–`0x45`;
  runs after `runKeyRotationSyncRound` on every link, both sync
  sites in `MessageSyncService`.
- `CoordinationSyncRepository` — verifies community, signature, and
  creator trust level on ingest.
- DB schema v24: `coordination_event` + `coordination_rsvp` tables
  (`CoordinationEventDao`, `CoordinationRsvpDao`).
- `Cbor.Reader.uintOrNull()` added for nullable unsigned ints.
- UI: `EventListScreen` (upcoming/past tabs), `EventDetailScreen`
  (RSVPs + actions), `CreateEventScreen`; reachable via
  Settings → Coordination → Events.

New module: `feature/coordination/`. New files: `EventEnvelope.kt`,
`RsvpEnvelope.kt`, `CoordinationSyncMessage.kt`,
`CoordinationSyncRepository.kt`, `CoordinationSyncRound.kt`,
`CoordinationEventEntity.kt`, `CoordinationRsvpEntity.kt`,
`CoordinationEventDao.kt`, `CoordinationRsvpDao.kt`, `CoordinationRoot.kt`,
`EventListViewModel.kt`, `EventDetailViewModel.kt`,
`CreateEventViewModel.kt`, plus the three screens.

Committed as `1fb604b` without a version bump; the build was stamped
28→29 / 0.9.7→0.9.8 and verified clean on 2026-06-14 (coordination
classes confirmed present in the APK DEX). Hardware verification
owed — create an event on one device, confirm the event and a
returning RSVP sync to the other on the next round.

### 2026-05-25 — v0.9.7: WiFi Direct transport wired into sync stack

**WiFi Direct fully integrated.** The `WifiDirectTransport` module
(DNS-SD service discovery, WifiP2pManager P2P group formation, TCP
connect on port 9094, `WifiDirectLink` with u32 length-prefixed
framing matching BLE/Tor) was already code-complete but never wired
into the transport selector or sync service.

**Changes:**
- `TransportModule` — new `provideWifiDirectTransport()` Hilt singleton
- `TransportSelectorModule` — passes `WifiDirectTransport` to selector
- `TransportSelector` — WiFi Direct wired into `startAll()`, `stopAll()`,
  `discoveredPeers()`, `acceptedLinks()`; new
  `wifiDirectDiscoverAndConnect()` (parks via `awaitCancellation()` when
  hardware unavailable, same pattern as BLE)
- `SyncTransportFacade` — `wifiDirectDiscoverAndConnect()` added to
  the interface
- `MessageSyncService` — WiFi Direct added as third racing leg in both
  `dialOnly()` and `raceBoth()`, alongside BLE and Tor

The transport race is now BLE vs WiFi Direct vs Tor — whichever
connects first wins, losers are cancelled. Permissions already
merged from the `:core:transport:wifidirect` manifest; app manifest
already overrides `wifi.direct` hardware feature to `required="false"`.

Hardware verification pending — needs two-device test with WiFi Direct
enabled, BLE off, confirming P2P group forms and messages sync.

### 2026-05-25 — v0.9.6: Monero wallet hardware-verified + wallet UI polish

**Monero wallet hardware-verified.** First real-device confirmation that
the full Monero stack works: Molly SDK's `libmonero_wallet.so` loads on
ARM64, `InProcessWalletService` connects, wallet opens from AES-GCM
encrypted disk (410KB wallet file), and RPC calls flow through the Tor
SOCKS proxy to the Rino community node (`node.community.rino.io:18081`).
Tor bootstraps to 100% in ~2 seconds. The wallet enters `Ready` state
and syncs blocks. Only untested: an actual XMR send/receive transaction.

**XMR/USD price on wallet screen.** The CoinGecko price feed (already
fetched by `MoneroWalletService.startPriceCollector`) is now surfaced
in the UI: "1 XMR = $XXX.XX USD" appears below the balance in the
Balance panel. `xmrUsdRate: StateFlow<Double?>` threaded through
ViewModel → Root → Screen. Hidden when rate hasn't loaded yet.

**Node connection status LED.** Color-coded dot on the wallet screen:
gray (Idle), orange (Connecting), green (Connected), red (Disconnected),
with the node label ("Rino community node") next to it. Composable
`NodeStatusRow` derives color + label from `WalletState`. Node label
exposed via `MoneroWalletService.nodeLabel`.

## What's NOT Built Yet

- **Monero send/receive test** — wallet connects and syncs but no
  real XMR transaction has been sent or received on-device yet.
- **Node picker UI** — users cannot switch nodes or add a custom one;
  hardcoded to first in `MoneroNodeRegistry.defaults`.
- **Vault** feature (encrypted personal-record store) — empty module.
- **Marketplace over BLE** — wallet exists but doesn't yet sync over
  a real Link.
- **WiFi Direct hardware verification** — transport is fully wired
  (discovery, connect, accept, TCP framing) and races alongside BLE
  and Tor in the sync service, but not yet tested on real devices.
- ~~**Coordination hardware verification**~~ — DONE 2026-06-14.
  Events + RSVPs sync both directions on S23 + A02s over Tor; the
  Events-screen-drives-sync fix + heads-up notifications landed during
  the test (uncommitted on branch `android`). See the v0.9.8 status entry.
- **Reticulum/LoRa** — `:core:transport:reticulum` is a stub; no
  Kotlin binding exists yet.
- **CI** — no pipeline yet. Reproducible-build flags + a banned-deps
  grep are the smallest viable CI.
- **Directory** feature — not started.
- **Layer-2 / Layer-3 peer-update** — automatic discovery via BLE
  trust channel (L2) and K-quorum APK verification (L3).
- **F-Droid submission** — metadata is ready (`metadata/com.wyspr.yml`,
  fastlane descriptions). Needs a tagged release + PR to fdroiddata.

## Memory / Plan State

v0.9.8 is the current build (build 29, stamped 2026-06-14).
Coordination feature (events/scheduling) added in `feature:coordination`
— signed event/RSVP CBOR envelopes synced via anti-entropy
(`CoordinationSyncRound`) on existing Noise sessions; DB schema v24;
UI under Settings → Coordination. Build-verified clean; hardware
verification owed. WiFi Direct transport fully wired (v0.9.7) but
also not yet device-tested. Monero wallet hardware-verified (connect
+ sync; no real send/receive yet). Payment notifications wired. Key
rotation hardware-verified. Both BLE and Tor-only messaging
hardware-verified. Next priorities: hardware-verify WiFi Direct and
coordination, test a real XMR send/receive transaction, then CI,
then F-Droid submission.

Auto-memory references:
- [[wyspr-messaging-works]] — 22-build journey, all fixes
- [[wyspr-ble-flow-control]] — the BLE pattern that hid the rest
- [[wyspr-first-pairing-milestone]] — earlier same-day milestone
- [[wyspr-sign-padding-bug]] — 5-year-old root cause
- [[wyspr-feedback-verify-baseline]] — apply same skepticism to
  remaining unverified layers (vault, marketplace).
