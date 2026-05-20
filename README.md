# Keystone

> An operational toolkit for resilient, high-trust communities.

**Keystone** is a privacy-first, decentralized Android application. It is designed
for groups that cannot rely on commercial messaging platforms — communities where
exclusion of unvetted actors, censorship-resistance, and offline operation are
non-negotiable.

There are no central servers. There is no public profile. There is no algorithmic
feed. Every node is sovereign, every link between nodes is cryptographically
authenticated, and every byte of payload data is end-to-end encrypted.

## Design Pillars

1. **Hostility-First Design.** The threat model assumes active adversaries on
   the wire, on the device boundary, and inside the user pool. The default
   posture is exclusion: an entity that cannot prove membership cannot reach
   the network.

2. **True Decentralization.** No cloud. No CDN. No identity provider. Peers
   discover one another via local mesh (Bluetooth LE, WiFi Direct) and exchange
   data directly. Long-range sync rides over user-controlled transports
   (embedded Tor hidden services, friend-to-friend relays) — never a
   Keystone-operated backend. Software is distributed peer-to-peer: an
   on-device HTTPS share server hands an APK to a new recruit; updates can
   be pulled from any peer's `/version.json`.

3. **Web of Trust.** Membership is granted by existing members via a physical,
   in-person QR handshake. There is no signup form. Trust is transitive,
   bounded by a K-independent-paths quorum from a Root, and revocable.

4. **Utility over Vanity.** No likes. No followers. No discoverability. The
   feature set is strictly operational: encrypted messaging, local resource
   marketplace, group coordination, vetted directory, encrypted personal vault.

## Architecture

A modular Gradle project. Each `core/*` module is a horizontal capability;
each `feature/*` module is a self-contained Utility Module that can be enabled,
disabled, or replaced without touching the rest of the system.

```
keystone/
├── app/                            Application shell, DI graph root,
│                                    embedded Tor backend, transport
│                                    foreground service
├── core/
│   ├── crypto/                     Noise XX wrapper, libsodium, hardware
│   │                                keystore, canonical CBOR codec
│   ├── identity/                   Ed25519 identities, fingerprints,
│   │                                CommunityId
│   ├── trust/                      Web of Trust graph (K-paths quorum),
│   │                                handshake protocol, invitation +
│   │                                revocation certs
│   ├── transport/
│   │   ├── api/                    Transport interface, BLAKE2s ServiceUuid,
│   │   │                            TorBackend facade, MessagingNotifier
│   │   ├── bluetooth/              BLE GATT mesh transport
│   │   ├── wifidirect/             WiFi Direct transport (discovery only)
│   │   └── reticulum/              LoRa backbone transport (scaffold)
│   ├── sync/                       Currency/marketplace HaveSet → Want →
│   │                                Push anti-entropy
│   ├── database/                   SQLCipher + Room (encrypted at rest)
│   ├── currency/                   Local-resource wallet (issuance, transfer,
│   │                                slash)
│   └── ui/                         Hardened-terminal Compose theme, trust
│                                    badges, fingerprint rendering
└── feature/
    ├── onboarding/                 Invite QR handshake; peer-to-peer APK
    │                                share + Layer-1 update pull
    ├── messaging/                  End-to-end encrypted DMs over Noise+BLE,
    │                                with read receipts
    ├── vault/                      Encrypted personal store (scaffold)
    ├── marketplace/                Local resource sharing (offers / needs)
    ├── coordination/               Tasks, events, signal flares (scaffold)
    └── directory/                  Vetted member directory (scaffold)
```

## Documentation

- [docs/SECURITY-MODEL.md](docs/SECURITY-MODEL.md) — threat model, Web of Trust spec, K-paths quorum
- [docs/PROTOCOLS.md](docs/PROTOCOLS.md) — wire format: handshake, sync, messaging, address exchange
- [docs/LIBRARIES.md](docs/LIBRARIES.md) — dependency stack, rationale, forbidden-deps deny list
- [docs/CURRENCY.md](docs/CURRENCY.md) — community-scoped local currency spec
- [NEXT-STEPS.md](NEXT-STEPS.md) — current sprint state and roadmap
- [CLAUDE.md](CLAUDE.md) — operational notes for autonomous coding agents

## Status — v0.6.7 (2026-05-19)

End-to-end handshake landed in v0.1 and has expanded since:

- **Handshake (v0.1)** — Noise XX over BLE with hardware-keystore Ed25519
  identities, channel-binding via QR nonces, signed invitation certificates,
  encrypted TrustEdge persistence in SQLCipher.
- **Trust authorization (v0.2)** — `TrustGraphImpl` with K-independent-paths
  quorum wired into `HandshakeProtocolImpl`. Inviters must satisfy the quorum
  before issuing a new edge. Foreground service keeps the BLE radio alive
  across backgrounding.
- **My Community (v0.3)** — local trust-graph view; SSRF gate on peer-pull
  updates restricting outbound HTTPS to RFC 1918 / 100.64/10 ranges.
- **Messaging (v0.4–v0.5)** — peer-to-peer DMs sync over the existing Noise
  session via a dedicated Push/Ack/Read protocol. Inbound notifications,
  read receipts, deep-link from notification.
- **Tor (v0.6)** — embedded Tor daemon via kmp-tor; HSv3 hidden service
  keyed off the device's keystore identity so the `.onion` survives
  reinstalls. The address is published inside the handshake QR and
  persisted in the TrustEdge for long-range reconnect. `MessageSyncService`
  races BLE-discover, Tor-dial-any-known-onion, and BLE/Tor-accept in a
  single sync round — once two devices have paired and exchanged
  `.onion`s, sync works across the internet without BLE in range.
- **Peer-to-peer APK distribution** — on-device HTTPS share server with a
  per-session self-signed cert; recipient scans a QR and downloads directly.
  Layer-1 update pull: `/version.json` + SHA-256-verified streaming
  download → system PackageInstaller.
- **F-Droid release plumbing (v0.6.7)** — `metadata/com.keystone.yml`
  draft manifest + `fastlane/metadata/android/en-US/` description and
  changelog in the repo, ready to submit to `f-droid/fdroiddata`. See
  `docs/FDROID.md` for the maintainer release process and the
  reproducible-build deviation list.

The project produces a signed release APK (~27 MB, R8-minified). Next
sprint (v0.7.0) is the Monero wallet scaffold over embedded Tor —
see [NEXT-STEPS.md](NEXT-STEPS.md) §10.
