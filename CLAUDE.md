# Wyspr — Project Notes

## Project Info
- **Framework**: Android (Kotlin, Jetpack Compose, Material3)
- **Package**: `com.wyspr`
- **Min SDK**: 26 (Android 8.0 — Keystore + StrongBox availability cutoff)
- **Target SDK**: 34
- **Port**: 5034 (daemon registration only — Wyspr has no server component)
- **Status**: 2026-05-23 — **v0.9.2 (build 23).** Full codebase
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

## What's NOT Built Yet

- **KeyRotation envelope** — legitimate identity reset currently
  looks identical to a compromise. Needs a signed "I rotated my
  key" cert that peers can verify.
- **Vault** feature (encrypted personal-record store) — empty module.
- **Marketplace over BLE** — wallet exists but doesn't yet sync over
  a real Link.
- **WiFi Direct `connect()`** — discovery only today.
- **Reticulum/LoRa** — `:core:transport:reticulum` is a stub; no
  Kotlin binding exists yet.
- **CI** — no pipeline yet. Reproducible-build flags + a banned-deps
  grep are the smallest viable CI.
- **Coordination, Directory** features — not started.
- **Layer-2 / Layer-3 peer-update** — automatic discovery via BLE
  trust channel (L2) and K-quorum APK verification (L3).
- **F-Droid submission** — metadata is ready (`metadata/com.wyspr.yml`,
  fastlane descriptions). Needs a tagged release + PR to fdroiddata.

## Memory / Plan State

v0.9.2 is the current build (2026-05-23). Both BLE and Tor-only
messaging are hardware-verified. Revocation propagation is live.
68 audit fixes landed. Next priorities: KeyRotation envelope,
then Vault or CI depending on direction.

Auto-memory references:
- [[wyspr-messaging-works]] — 22-build journey, all fixes
- [[wyspr-ble-flow-control]] — the BLE pattern that hid the rest
- [[wyspr-first-pairing-milestone]] — earlier same-day milestone
- [[wyspr-sign-padding-bug]] — 5-year-old root cause
- [[wyspr-feedback-verify-baseline]] — apply same skepticism to
  remaining unverified layers (Monero, vault).
