# Keystone — Security Model

## 1. Threat Model

We design against three classes of adversary, in order of expected frequency:

| Adversary | Capabilities | Mitigation locus |
|-----------|--------------|------------------|
| **Network observer** | Passive wire capture on Bluetooth, WiFi, and any upstream relay | Noise XX framing — every byte after the first 32 is encrypted; transport headers carry no identity |
| **Active impersonator** | Can forge messages, replay frames, run a hostile peer claiming to be a member | Long-term Ed25519 identities; every record signed; replay-protected via Noise nonces and per-edge counters |
| **Infiltrator** | Has obtained a working build, attempts to join via a careless inviter | Web of Trust quorum; physical handshake requirement; revocation propagation |

We **do not** defend against:

- A nation-state with on-device implant after physical seizure. (Hardware
  keystore raises cost; it does not stop a determined attacker with the device.)
- A user who voluntarily discloses content. (Out of scope.)
- Long-term traffic-analysis correlation against an opponent that controls
  every hop. (We reduce metadata; we do not eliminate it.)

## 2. Identity

Each Keystone install generates **one long-term identity key pair** on first
launch:

- **Algorithm**: Ed25519 (signing) + X25519 (Noise key agreement).
- **Storage**: Android `KeyStore`, backed by `StrongBox` when the device
  exposes it, falling back to `TEE`. Software-only storage is rejected at
  install time; the app refuses to run.
- **Fingerprint**: First 16 bytes of `SHA-256(pub || domain_separator)`,
  rendered as five space-separated groups of base32 (e.g.
  `K5T2N AB3FR HJ8MQ XYZ4P 7VN2K`). This is the only identifier a human ever
  reads or types.
- **Rotation**: Identity keys do not rotate. Devices reset their identity by
  full reinstall. Membership transfer between devices is an explicit,
  vouched operation.

There is **no display name** stored on the wire. Local nicknames are a
per-device UI preference and never sync.

## 3. Web of Trust — Invitation System

### 3.1 Genesis

The first node is a **Root**. Its identity is self-signed. There is no
mechanism to designate roots remotely; root status is established by being
the first to instantiate a community (or by being imported as a root via
an out-of-band community-bootstrap blob signed by an existing community's
quorum).

A community may have multiple roots. Roots have no special runtime privilege
beyond the seed of the trust graph.

### 3.2 Invitation Certificate

An invitation is a signed certificate produced by an **Inviter** for an
**Invitee**. Wire form (CBOR, then signed):

```
InvitationCertificate {
  version:        u8 = 1
  inviter_pub:    bytes[32]      // Ed25519 public key
  invitee_pub:    bytes[32]      // captured during handshake
  community_id:   bytes[32]      // genesis-block hash
  issued_at:      u64            // unix seconds
  expires_at:     u64            // issued_at + 24h typical
  vouch_level:    enum { PROVISIONAL, FULL }
  nonce:          bytes[16]
  signature:      bytes[64]      // Ed25519 over the above
}
```

Certificates are **single-use** — the `nonce` is recorded by both parties and
any subsequent presentation is rejected.

### 3.3 Handshake — Establishing a Root of Trust

The handshake is **physical and bidirectional**. Both parties must be
co-located. The protocol is:

1. **Display.** Each device renders a QR code containing
   `{identity_pub, ephemeral_pub, community_id, nonce}`. The QR is
   never transmitted electronically; it is shown on-screen and read with
   the camera.

2. **Scan.** Each device scans the other's QR. After scan, each device
   shows its **fingerprint** and the peer's fingerprint side-by-side. The
   users compare them verbally. The flow cannot proceed without an
   explicit "match" tap from both sides.

3. **Noise XX over short-range.** Both devices initiate a Noise_XX_25519_
   ChaChaPoly_BLAKE2s session over BLE GATT. Channel binding incorporates
   the QR nonces. If the channel-binding hash does not match what each
   device captured in step 1, the session aborts.

4. **Vouch exchange.** Inside the encrypted channel, the Inviter sends a
   signed `InvitationCertificate`. The Invitee responds with its own
   self-signed `IdentityClaim`. Both are persisted as a new **trust edge**
   in the local Trust Graph.

The handshake CANNOT be completed remotely. There is no fallback path
for "I'll scan you later." This is intentional.

### 3.4 Trust Levels

A node's trust score relative to the local viewer is computed from the
local Trust Graph:

| Level | Requirement |
|-------|-------------|
| **Root** | Self-signed; appears as a root in the local graph |
| **Full** | At least `K = 2` independent paths from a Root, each of length ≤ `D = 4`, with no `PROVISIONAL` edges |
| **Provisional** | A `FULL`-vouched node has admitted them, but only one path exists |
| **Quarantined** | A revocation has been observed; see §3.6 |
| **Unknown** | Any node not appearing in the graph |

Tuning constants (`K`, `D`) live in `core:trust` and are community-scoped.
Defaults assume a small community (< 200) and should be raised, not
lowered, for larger ones.

### 3.5 Privileges by Level

| Action | Required level |
|--------|----------------|
| Receive any frame on a transport | `Provisional` or above |
| Sync from the local device | `Full` |
| Issue new invitations | `Full` AND signed by a `Root` within ≤ 2 hops |
| Issue revocations | `Full` |
| Participate in a Vault group | Explicit per-group membership; trust level is necessary, not sufficient |

`Unknown` peers receive a transport-layer drop with no error response.
The node behaves indistinguishably from a node that is simply offline.

### 3.6 Revocation

Any `Full` member may issue a signed `RevocationCertificate` against
another member. Revocation is **not** majoritarian — a single revocation
moves the target to `Quarantined` for all peers who have ingested it.

```
RevocationCertificate {
  version:       u8 = 1
  issuer_pub:    bytes[32]
  target_pub:    bytes[32]
  community_id:  bytes[32]
  issued_at:     u64
  reason_code:   u8        // structured, not free-text
  signature:     bytes[64]
}
```

Reason codes are an enumeration (`COMPROMISED`, `INFILTRATOR`, `INACTIVE`,
`VOLUNTARY_EXIT`, `OTHER`) — free-text reasons would leak via metadata.

Revocations propagate through normal sync. There is **no un-revoke**;
a quarantined identity must re-handshake from scratch with a clean key pair.

### 3.7 Why Not a Blockchain?

A consensus ledger is overkill for a graph this small and works against
us: every member would need every record. The trust graph is intentionally
**partial and local** — node A may not know that B was revoked by C until
A and C sync, and that is the desired behavior. Communities partition,
heal, and split organically. A global ledger would force uniformity we
don't want.

## 4. Data at Rest

- **Database**: SQLCipher with a 256-bit key derived from the hardware-
  keystore identity key via HKDF. The database cannot be opened on
  another device, even with the file copied off.
- **Vault payloads**: Sealed with XChaCha20-Poly1305; the symmetric key
  is wrapped per-recipient with X25519 ECDH.
- **Backups**: Disabled. `android:allowBackup="false"`,
  `android:fullBackupContent="false"`, no `dataExtractionRules.xml`
  permitting any export. Users who want a backup re-handshake on the
  new device.

## 5. Data in Transit

All payload bytes between any two Keystone nodes traverse a Noise_XX_25519_
ChaChaPoly_BLAKE2s session. Transport-layer headers are minimal and
carry no identity:

```
[ frame_len: u16 ][ noise_ciphertext: bytes ]
```

The MAC layer (BLE advertising, WiFi Direct beacons) advertises an
opaque service UUID shared by the entire community — there is no
per-device identifier visible at L2. A passive observer cannot
distinguish two Keystone nodes from one another without joining.

## 6. Failure Modes That Must Stay Failure Modes

The following are **bugs to avoid**, not features to add:

- **Silent fallback to weaker crypto.** If StrongBox is unavailable, fall
  back to TEE; if TEE is unavailable, **refuse to run**. Never fall back
  to software keys.
- **Auto-accept invitations.** Every handshake requires a tap on both
  sides AFTER fingerprint comparison.
- **Cross-community discovery.** A device that belongs to two communities
  must not leak one community's existence to the other. Each community
  has its own service UUID and its own database.
- **Server-side recovery.** There is no recovery account. Lost device =
  lost identity. This is a feature.

## 7. Open Questions for Review

These decisions are not final and should be revisited before v1.0:

1. **Quorum `K`** — is 2 paths enough, or should `Full` require 3?
2. **Provisional TTL** — should `PROVISIONAL` edges expire if not upgraded
   within N days?
3. **Long-range transport** — Tor hidden service vs. friend-to-friend
   relay vs. both. Currently scoped out of v0.
4. **Group key rotation** — Vault groups have no rotation story yet;
   when a group member is revoked, do we re-encrypt or accept that
   their old reads were already cached?
