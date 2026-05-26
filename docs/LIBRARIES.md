# Wyspr — Library Stack

Every dependency is a piece of trust surface. This document records what we
use, why we picked it, and what we deliberately rejected.

## Selection Rules

1. **License compatibility.** Prefer Apache-2.0, MIT, BSD. GPL/AGPL only
   when there is no comparable alternative AND the licensing impact on
   the rest of the codebase is understood.
2. **Audit history.** Prefer libraries that have been through at least
   one published third-party security audit, or are widely deployed in
   adversarial environments (Briar, Signal, Tor).
3. **Reproducible builds.** Prefer libraries that publish source +
   checksums to Maven Central; avoid AAR-only black-box drops.
4. **Native code = scrutinize twice.** Native (JNI / fork) dependencies
   have a larger attack surface than pure-JVM ones. Justify every native
   lib.
5. **No analytics. No telemetry. No crash reporters that phone home.**
   If a library includes one, fork it or pick a different one.

## Core Stack

### Cryptography

| Concern | Library | Version | License | Notes |
|---------|---------|---------|---------|-------|
| Noise protocol | `com.github.rweather:noise-java` | commit `49377b6` (JitPack) | MIT | Reference Java impl of the Noise framework. We use `Noise_XX_25519_ChaChaPoly_BLAKE2s`. No tagged release exists on JitPack; pinning the SHA keeps the build reproducible. Re-evaluate if JitPack ever refuses the commit — fallback is a hand-rolled XX over libsodium primitives plus a ported BLAKE2s (libsodium exposes only BLAKE2b). |
| BLAKE2s digest | `noise-java`'s `Blake2sMessageDigest` | (transitive) | MIT | Already on the classpath; used by `ServiceUuid` to derive the BLE service UUID from `community_id`. RFC 7693 vectors tested. |
| AEAD, HKDF, Ed25519, X25519 | `com.goterl:lazysodium-android` + `net.java.dev.jna:jna` | `5.1.0@aar` + `5.13.0@aar` | MPL-2.0 / Apache-2.0 | libsodium binding. ARM64 + x86_64 binaries shipped. Preferred over Tink for stable API and small surface. Hard requirement: Ed25519 → X25519 derivation via `crypto_sign_ed25519_sk_to_curve25519`. |
| HSv3 key derivation | `org.bouncycastle:bcprov-jdk18on` | `1.78.1` | MIT-style | Needed for SHA3-256 + Ed25519 `scalarMultBase` on minSdk 26, where the platform JCE doesn't expose them. Declared explicitly on `:app` because `:feature:onboarding` pulls it transitively as `implementation`, hiding its classes from `:app` at compile time. |
| Hardware keystore | `androidx.security:security-crypto` | `1.1.0-alpha06` | Apache-2.0 | Only for `EncryptedSharedPreferences`; primary keystore use is direct via `java.security.KeyStore` (StrongBox preferred, TEE floor; refuses software-only). |
| Biometric prompt | `androidx.biometric:biometric` + `androidx.fragment:fragment-ktx` | `1.1.0` + `1.6.2` | Apache-2.0 | App-level lock on launch, gating keystore-derived key access. |

**Rejected**: BouncyCastle as a general-purpose crypto layer (too large,
pure-software, encourages weaker fallbacks — we keep the surface
minimal); Google Tink (fine but heavier and harder to pin to specific
primitives).

### Transport

| Concern | Library | Version | License | Notes |
|---------|---------|---------|---------|-------|
| BLE GATT | Platform `android.bluetooth.*` | n/a | n/a | Direct platform APIs — the Jetpack `androidx.bluetooth` lib was unstable when we evaluated and ships less surface than we need (no GATT server). |
| WiFi Direct | Platform `android.net.wifi.p2p.WifiP2pManager` | n/a | n/a | Fully implemented: DNS-SD discovery, P2P group formation, TCP connect on port 9094, u32 length-prefixed framing. Wired into `TransportSelector` and races alongside BLE + Tor in `MessageSyncService`. Hardware verification pending. |
| Tor (embedded) | `io.matthewnelson.kmp-tor:runtime` + `:resource-exec-tor` + `:resource-noexec-tor` | `2.0.0` + `408.13.2` × 2 | Apache-2.0 / BSD-3 (tor) | Bundles a real `tor` binary, extracted to `nativeLibraryDir` on install and `fork()`ed at runtime; the `-noexec` companion is a shim picked on devices that block fork. Pinned to 2.0.0 because everything later is built against Kotlin 2.1+ whose stdlib metadata our 1.9.22 compiler cannot read. Re-evaluate the moment Wyspr upgrades past Kotlin 2.0. JNI extraction requires `packaging { jniLibs { useLegacyPackaging = true } }`. |
| Reticulum / LoRa | (none — placeholder) | — | — | `:core:transport:reticulum` is a stub. No maintained Kotlin/JVM Reticulum client exists; the wiring will most likely come via JNI to the C port or a localhost bridge to a paired Reticulum daemon on an ironmesh node. |
| QR rendering | `com.google.zxing:core` | `3.5.2` | Apache-2.0 | Used by `core:ui/QrRenderer`. Lives in `:core:ui` because we render fingerprints + share URLs in multiple features. |
| QR scanning | `androidx.camera:camera-core` / `-camera2` / `-lifecycle` / `-view` + `com.google.zxing:core` | CameraX `1.3.1` | Apache-2.0 | CameraX lives only in `:feature:onboarding` (`ScanPeerQrScreen`, peer-share-mini-site URL pickup). Scanning is hardened against phone-screen reading conditions: ZXing TRY_HARDER hints, multi-frame retry, glare/low-light tolerance. |

**Rejected**:

- **libp2p-jvm** (`tech.libp2p:jvm-libp2p`) — production-grade and tempting,
  but the Android story is thin, transitive deps are large, and we don't
  need DHT / pubsub. Wyspr is friend-to-friend, not open-world.
- **Google Nearby Connections** — depends on Google Play Services, which
  means depending on Google. Disqualified by Pillar 1.
- **Bridgefy SDK** — proprietary, phoned home in past versions, audit
  history of catastrophic bugs. Hard reject.
- **Tor-Android (Guardian Project)** — superseded by `kmp-tor`, which
  ships a more current `tor` binary and a Kotlin/coroutines API.

### Persistence

| Concern | Library | Version | License | Notes |
|---------|---------|---------|---------|-------|
| Relational store | `androidx.room:room-runtime` / `-ktx` / `-compiler` | `2.6.1` | Apache-2.0 | Standard Room. Migrations are additive — see PROTOCOLS.md §3.4 for the live schema versions. |
| Encrypted SQLite | `net.zetetic:sqlcipher-android` | `4.6.x` | BSD-3 | Backs Room via `SupportOpenHelperFactory`. Key derived from `WYSPR/v1/db` HKDF subkey of the hardware-keystore identity. |
| Preferences | `androidx.datastore:datastore-preferences` | `1.0.0` | Apache-2.0 | UI prefs only — never identity or trust state. |

### UI / DI / Lifecycle

| Concern | Library | Version | License | Notes |
|---------|---------|---------|---------|-------|
| Compose | `androidx.compose:compose-bom` | `2024.02.00` | Apache-2.0 | Pinned for Pi 5 build stability. Material3 + extended icons. |
| Navigation | `androidx.navigation:navigation-compose` | `2.7.7` | Apache-2.0 | Single-activity, Compose nav. |
| Activity | `androidx.activity:activity-compose` | `1.8.2` | Apache-2.0 | |
| DI | `com.google.dagger:hilt-android` | `2.50` (KSP) | Apache-2.0 | Hilt at the DI root; manual factories below it. KSP `1.9.22-1.0.18`. |
| Lifecycle | `androidx.lifecycle:lifecycle-runtime-ktx` / `-runtime-compose` / `-viewmodel-compose` | `2.7.0` | Apache-2.0 | |
| Splash | `androidx.core:core-splashscreen` | `1.0.1` | Apache-2.0 | |

### Cryptocurrency (planned, v0.7.0b)

| Concern | Library | Version | License | Notes |
|---------|---------|---------|---------|-------|
| Monero JNI binding | Monerujo `monero-android` (m2049r/xmrwallet) | TBD (vendored AAR or fork+JitPack) | GPL-3.0 | The only battle-tested Android binding for Monero. Bundled in `:feature:monero-wallet` only — but the resulting combined APK ships under GPLv3. F-Droid distributes GPL-licensed apps cleanly. Maintainer note: vendor + reproducible-build evaluation pending. |
| Monero RPC transport | None — hand-rolled HTTP/1.1 over the existing `Socks5` dialer | — | — | OkHttp over `Proxy.SOCKS` resolves hostnames JVM-side, which would leak the remote node's `.onion`/host to the device DNS resolver. The minimal in-tree dialer uses SOCKS5 DOMAINNAME (ATYP=0x03) so Tor handles resolution. |

**Rejected for the wallet:** Bitcoin / Lightning client libraries
(default-transparent ledger violates the project ethos);
`mymonero-core-cpp` (less actively maintained than Monerujo's
fork); pure-Kotlin RPC-only clients (cannot scan blocks or sign
transactions locally — they require trusting a wallet-RPC server
with the view + spend keys).

### Build

| Tool | Version | Notes |
|------|---------|-------|
| AGP | `8.2.0` | Pinned for build stability across CI + Pi 5. |
| Kotlin | `1.9.22` | KSP `1.9.22-1.0.18`. **Do not bump past 2.0 without coordinating with the kmp-tor pin.** |
| Compose compiler ext | `1.5.10` | Tied to the Kotlin version. |
| JDK target | `17` | OpenJDK 17 already installed across dev machines. |
| Gradle heap | `1536m` | Bump to `2048m` if KSP/Hilt OOMs. |

## Forbidden Dependencies (project-wide deny list)

Anything from this list MUST NOT be added without explicit security review.

- Google Play Services (any module)
- Firebase (any module)
- Crashlytics, Sentry-with-default-DSN, Bugsnag — anything that phones home
- Facebook SDK, Twitter Kit, any social SDK
- Any analytics library, including "self-hosted" ones we haven't audited
- AppsFlyer, Branch, Adjust, or any attribution SDK
- Stripe / Braintree client SDKs (Wyspr doesn't take payments; if it
  ever does, the integration is server-side via a user-controlled VPS)

CI grep that fails the build on any banned identifier is still
outstanding — see NEXT-STEPS.md §7.

## Update Policy

- **Crypto libraries**: update on every security advisory, within 7 days.
- **Transport libraries**: update on minor releases; major releases get
  a fresh review of the changelog before merge. The kmp-tor pin is held
  on the Kotlin 1.9 boundary — see the inline build-file comment for
  the resignation criteria.
- **Everything else**: quarterly batch update with a fresh dependency
  scan.

Per-module versions are inlined in each `build.gradle.kts` for now.
A consolidated `gradle/libs.versions.toml` is on the roadmap once the
dependency surface stops moving.
