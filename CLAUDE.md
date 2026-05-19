# Keystone — Project Notes

## Project Info
- **Framework**: Android (Kotlin, Jetpack Compose, Material3)
- **Package**: `com.keystone`
- **Min SDK**: 26 (Android 8.0 — Keystore + StrongBox availability cutoff)
- **Target SDK**: 34
- **Port**: 5034 (daemon registration only — Keystone has no server component)
- **Status**: v0.1 happy path COMPLETE. End-to-end handshake builds + tests green.

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
                    core:sync  -> core:transport:api -> {bluetooth, wifidirect}
                    core:database -> core:crypto (SQLCipher key derivation)
```

Modules MUST NOT reach sideways across the graph. `feature:marketplace`
never imports `feature:vault`; they communicate through `core:sync` events.

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

Latest green build: 2026-05-18 (post v0.1 handshake landing). Gradle 8.5,
~2min warm / ~3min cold. `local.properties` is gitignored — generate from
`local.properties.example`.

Gradle heap is set to 1536MB. If KSP/Hilt OOMs, bump
`org.gradle.jvmargs` to `-Xmx2048m`.

### Notes on deps
- `noise-java` (rweather) pinned to commit `49377b6` via JitPack — no
  tagged release exists. If JitPack refuses on a future build, switch
  to a hand-rolled Noise XX impl using libsodium primitives (BLAKE2s
  needs to be ported separately; libsodium only exposes BLAKE2b).
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
- `core/crypto/.../NoiseSession.kt` + `NoiseSessionImpl.kt` — the
  `Noise_XX_25519_ChaChaPoly_BLAKE2s` wrapper; one-shot lifecycle
- `core/trust/.../HandshakeProtocolImpl.kt` — Noise XX + cert exchange
  state machine; persists the TrustEdge on success
- `core/trust/.../InvitationCertificate.kt` — canonical CBOR
  encode/decode/sign/verify
- `core/trust/.../TrustGraph.kt` — vouch math and revocation propagation;
  change with review only
- `core/transport/api/.../Transport.kt` — the only abstraction every
  transport implements; framing is non-negotiable
- `core/transport/bluetooth/.../BleTransport.kt` — GATT server + client,
  MTU negotiation, chunked framing
- `core/ui/.../KeystoneTheme.kt` — design system; new screens must use it

## What Is Built (v0.1 — 2026-05-18)

Full happy path end-to-end. The whole `Welcome → RolePicker →
KeyGeneration → DisplayQr → ScanPeerQr → CompareFingerprints →
RunHandshake → Result` flow drives a real Noise XX handshake over
real BLE GATT and persists the trust edge in the encrypted DB.

- **`AndroidKeystoreManager`** — libsodium Ed25519 with seed wrapped
  by an AndroidKeyStore AES-256-GCM key. StrongBox preferred, TEE
  floor; refuses to run on software-only. HKDF-SHA256 subkey
  derivation. Real hardware-backed signing. Also derives the
  matching X25519 static keypair for Noise.
- **`IdentityIssuer.issue()`** — wraps the keystore into an
  `Identity` with the human-readable `Fingerprint`.
- **`KeystoneDatabaseImpl`** — Room + SQLCipher
  (`SupportOpenHelperFactory`) keyed off
  `keystore.deriveSubkey("KEYSTONE/v1/db")`. Trust-edge,
  revocation, community-membership entities + DAOs live in the
  encrypted DB on first open.
- **`HandshakeProtocolImpl`** — full Noise XX state machine for
  both Inviter (initiator) and Invitee (responder); channel-binding
  check against the QR's identity key; signed InvitationCertificate
  exchange; TrustEdge write on commit; quarantine on every abort
  reason.
- **`NoiseSessionImpl`** — `Noise_XX_25519_ChaChaPoly_BLAKE2s` via
  rweather/noise-java; prologue mixes `"KEYSTONE/v1" ||
  community_id || nonce_inviter || nonce_invitee || eph_pubs`.
- **`HandshakeQrCodec`** — canonical CBOR (RFC 8949 §4.2.1) over
  RFC 4648 base32. Decode rejects non-shortest integer encodings,
  indefinite-length items, trailing bytes.
- **`InvitationCertificate`** — canonical CBOR encode + sign via
  keystore + libsodium verify. `RevocationCertificate` symmetric.
- **`BleTransport`** — peripheral GATT server + GATT client. MTU
  request 247, per-community service + characteristic UUIDs,
  length-prefixed framing chunked at MTU - 3, accepted-links flow.
- **`WifiDirectTransport`** — DNS-SD discovery half. connect() is
  v0.2 — BLE remains the primary handshake transport.
- **`SyncEngine`** — HaveSet/Want/Push anti-entropy round; CBOR
  message codec; per-community filtering.
- **CameraX `ScanPeerQrScreen`** — live preview, scan reticle, perm
  flow, base32+CBOR decode.
- **`CompareFingerprintsScreen`** — monospace side-by-side compare,
  abort-quarantines path.
- **`RunHandshakeScreen` + `ResultScreen`** — animated step
  indicator, TrustBadge with pulse, full set of AbortReason
  messages.
- **Design system** — `KeystoneTheme` (dark-first, hardened-
  terminal palette, monospace typography for identity strings) +
  `core:ui/components/{TrustBadge, KeystonePanel, StepIndicator,
  PulsingDot}`.
- **Hilt** — `CryptoModule` + `TransportModule` provide every
  singleton: keystore, sodium, database, community service,
  handshake protocol, BLE transport, wallet, biometric settings.

## What's NOT Built Yet (see NEXT-STEPS.md for the full sprint plan)

- **Two-device hardware proof.** The build is green; nothing has
  yet been verified on physical hardware.
- **Vault** feature (encrypted personal-record store).
- **Marketplace over BLE** — wallet exists but doesn't yet sync
  over a real Link.
- **TrustGraph** quorum (K-independent-paths to upgrade
  Provisional → Full).
- **Revocation propagation** between peers via sync.
- **WiFi Direct `connect()`** — discovery only today.
- **BLAKE2s for `ServiceUuid`** — currently uses SHA-256 as a
  placeholder.
- Release build wiring, signing, F-Droid manifest, CI.
- Coordination, Directory features.
- Tor hidden-service transport.

## Memory / Plan State

Active plan saved to spawn-mcp: key `active-task-keystone`. After
v0.1 landing, the live work item is "two-device hardware proof"
(see NEXT-STEPS.md §1).

Update via `spawn_remember` after each milestone.
