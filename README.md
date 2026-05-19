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
   (Tor hidden services, friend-to-friend relays) — never a Keystone-operated
   backend.

3. **Web of Trust.** Membership is granted by existing members via a physical,
   in-person QR handshake. There is no signup form. Trust is transitive,
   bounded, and revocable.

4. **Utility over Vanity.** No likes. No followers. No discoverability. The
   feature set is strictly operational: encrypted vault, local resource
   marketplace, group coordination, vetted directory.

## Architecture

A modular Gradle project. Each `core/*` module is a horizontal capability;
each `feature/*` module is a self-contained Utility Module that can be enabled,
disabled, or replaced without touching the rest of the system.

```
keystone/
├── app/                            Application shell, DI graph root
├── core/
│   ├── crypto/                     Noise protocol, libsodium, hardware keystore
│   ├── identity/                   Ed25519 identities, fingerprints, key rotation
│   ├── trust/                      Web of Trust graph, invites, handshake
│   ├── transport/
│   │   ├── api/                    Transport interface (sealed)
│   │   ├── bluetooth/              BLE GATT mesh transport
│   │   └── wifidirect/             WiFi Direct transport
│   ├── sync/                       CRDT sync engine between trusted peers
│   ├── database/                   SQLCipher + Room layer
│   └── ui/                         Shared Compose theme, components
└── feature/
    ├── onboarding/                 Invite + dual-QR handshake flow
    ├── vault/                      Encrypted personal store
    ├── marketplace/                Local resource sharing (offers / needs)
    ├── coordination/               Tasks, events, signal flares
    └── directory/                  Vetted member directory
```

## Documentation

- [docs/SECURITY-MODEL.md](docs/SECURITY-MODEL.md) — threat model and Web of Trust spec
- [docs/PROTOCOLS.md](docs/PROTOCOLS.md) — handshake, sync, transport framing
- [docs/LIBRARIES.md](docs/LIBRARIES.md) — library stack and rationale

## Status

Scaffold only. Build pipeline not yet wired up; see `CLAUDE.md` for the
next-sprint checklist.
