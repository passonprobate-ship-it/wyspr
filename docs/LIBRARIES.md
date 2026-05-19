# Keystone — Library Stack

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
4. **Native code = scrutinize twice.** Native (JNI) dependencies have
   a larger attack surface than pure-JVM ones. Justify every native lib.
5. **No analytics. No telemetry. No crash reporters that phone home.**
   If a library includes one, fork it or pick a different one.

## Core Stack

### Cryptography

| Concern | Library | Version | License | Notes |
|---------|---------|---------|---------|-------|
| Noise protocol | `com.github.rweather:noise-java` | `0.1.x` | MIT | Reference Java implementation. Audited and used by several projects. We use Noise_XX_25519_ChaChaPoly_BLAKE2s. |
| AEAD, HKDF, Ed25519 | `com.goterl:lazysodium-android` | `5.1.x` | MPL-2.0 | libsodium binding. ARM64 + x86_64 binaries shipped. Preferred over Tink for stable API and small surface. |
| Hardware keystore | `androidx.security:security-crypto` | `1.1.0-alpha06` | Apache-2.0 | Only for `EncryptedSharedPreferences`; primary keystore use is direct via `java.security.KeyStore`. |

**Rejected**: BouncyCastle pure-JVM crypto (too large, pure-software,
encourages weaker fallbacks); Google Tink (fine but heavier and harder
to pin to specific primitives).

### P2P / Transport

| Concern | Library | Version | License | Notes |
|---------|---------|---------|---------|-------|
| BLE GATT | `androidx.bluetooth:bluetooth` | `1.0.0-alpha02` (track stable) | Apache-2.0 | New Jetpack BLE library; cleaner than `android.bluetooth.le.*` directly. Falls back to platform APIs if unstable. |
| WiFi Direct | Platform `android.net.wifi.p2p.WifiP2pManager` | n/a | n/a | No third-party wrapper; the platform API is the right abstraction level. |
| Long-range (deferred) | `info.guardianproject:tor-android` | `0.4.x` | BSD-3 | For optional Tor hidden-service transport. Scoped out of v0. |
| QR codes | `com.google.zxing:core` + `com.journeyapps:zxing-android-embedded` | `3.5.x` + `4.3.x` | Apache-2.0 | Read + generate. Used only for handshake; no other QR surface. |

**Rejected**:

- **libp2p-jvm** (`tech.libp2p:jvm-libp2p`) — production-grade and tempting,
  but the Android story is thin, transitive deps are large, and we don't
  need DHT / pubsub. Keystone is friend-to-friend, not open-world.
- **Google Nearby Connections** — depends on Google Play Services, which
  means depending on Google. Disqualified by Pillar 1.
- **Bridgefy SDK** — proprietary, phoned home in past versions, audit
  history of catastrophic bugs. Hard reject.

### Persistence

| Concern | Library | Version | License | Notes |
|---------|---------|---------|---------|-------|
| Relational store | `androidx.room:room-runtime` / `-ktx` / `-compiler` | `2.6.1` | Apache-2.0 | Standard Room. |
| Encrypted SQLite | `net.zetetic:sqlcipher-android` | `4.6.x` | BSD-3 | Backs Room via `SupportFactory`. Key derived from hardware-keystore identity. |
| Preferences | `androidx.datastore:datastore-preferences` | `1.0.0` | Apache-2.0 | UI prefs only — never identity or trust state. |

### UI / DI

| Concern | Library | Version | License | Notes |
|---------|---------|---------|---------|-------|
| Compose | `androidx.compose:compose-bom` | `2024.02.00` | Apache-2.0 | Same BOM as xpat for consistency on Pi 5 build. |
| Navigation | `androidx.navigation:navigation-compose` | `2.7.7` | Apache-2.0 | Single-activity, Compose nav. |
| DI | `com.google.dagger:hilt-android` | `2.50` | Apache-2.0 | Same as xpat / rovo. |
| Work | `androidx.work:work-runtime-ktx` | `2.9.0` | Apache-2.0 | For periodic sync attempts when transports come up. |

### Build

| Tool | Version | Notes |
|------|---------|-------|
| AGP | `8.2.0` | Pinned to xpat's version for Pi 5 build stability |
| Kotlin | `1.9.22` | KSP `1.9.22-1.0.18` |
| Compose compiler ext | `1.5.10` | |
| JDK target | `17` | OpenJDK 17.0.18 already installed |

## Forbidden Dependencies (project-wide deny list)

Anything from this list MUST NOT be added without explicit security review.

- Google Play Services (any module)
- Firebase (any module)
- Crashlytics, Sentry-with-default-DSN, Bugsnag — anything that phones home
- Facebook SDK, Twitter Kit, any social SDK
- Any analytics library, including "self-hosted" ones we haven't audited
- AppsFlyer, Branch, Adjust, or any attribution SDK
- Stripe / Braintree client SDKs (Keystone doesn't take payments; if it ever does, the integration is server-side via a user-controlled VPS)

The CI pipeline will fail any PR introducing one of these. (CI not yet wired;
see `CLAUDE.md`.)

## Update Policy

- **Crypto libraries**: update on every security advisory, within 7 days.
- **Transport libraries**: update on minor releases; major releases get
  a fresh review of the changelog before merge.
- **Everything else**: quarterly batch update with a fresh dependency scan.

`gradle/libs.versions.toml` is the single source of truth for versions
(once introduced; first sprint inlines them in `app/build.gradle.kts`
for simplicity).
