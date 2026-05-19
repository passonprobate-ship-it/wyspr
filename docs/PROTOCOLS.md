# Keystone — Protocols

This document specifies the on-the-wire and on-disk formats. Implementations
in any module MUST match this spec; if they diverge, the spec is wrong, fix
it here first.

All multi-byte integers are big-endian. All structured payloads are CBOR
(RFC 8949) with deterministic encoding (RFC 8949 §4.2.1).

## 1. Frame Layer

Every transport (BLE, WiFi Direct, future Tor) carries the same frame:

```
[ frame_len: u16 ][ payload: bytes ]
```

- `frame_len` is the byte length of `payload`, max 16384.
- `payload` is always a Noise transport-mode ciphertext **after** session
  establishment, or a Noise handshake message **during** establishment.
- There are no plaintext frames at the application layer. Ever.

Transports MAY add their own framing below this (BLE has L2CAP MTU; WiFi
Direct uses TCP-over-P2P) but MUST NOT inspect or modify the payload.

## 2. Noise Session

Pattern: `Noise_XX_25519_ChaChaPoly_BLAKE2s`.

Why XX:
- Mutual authentication.
- Both parties' static keys are exchanged during the handshake — neither
  needs prior knowledge of the other's identity key.
- Forward secrecy via ephemeral keys.

Channel binding:
- The handshake hash after the final XX message is included in the first
  application message. The receiver re-derives it and compares. If it
  differs, the session terminates.
- For the handshake initiated from a QR scan, both QR nonces are mixed
  into the prologue:
  `prologue = "KEYSTONE/v1" || community_id || nonce_a || nonce_b`
  where `nonce_a` and `nonce_b` come from the two QRs. This binds the
  cryptographic session to the physical-world act of scanning.

Rekey policy:
- Rekey after `2^20` frames or 24 hours, whichever comes first.

## 3. Handshake (Onboarding)

### 3.1 QR Payload

CBOR map, base32 (no padding) encoded for QR density:

```
HandshakeQR {
  v:    u8 = 1                  // schema version
  cid:  bytes[32]               // community_id
  ipub: bytes[32]               // identity public key
  epub: bytes[32]               // ephemeral X25519 public key for this handshake
  n:    bytes[16]               // nonce; bound into Noise prologue
  ts:   u64                     // unix seconds; QR is rejected if > 5 min old
}
```

QR is regenerated every 5 minutes while the screen is open. Stale QRs are
rejected on scan.

### 3.2 Sequence

```
Device A (Inviter)                           Device B (Invitee)
─────────────────                            ──────────────────
display QR_A                                 display QR_B
scan QR_B  ─────────────────────────────► (camera reads QR_B)
                              (user compares fingerprints, taps confirm)
                                             scan QR_A  ◄────────── (camera)
                              (user compares fingerprints, taps confirm)

           ── BLE GATT connect (service UUID = derive(community_id)) ──►

           ◄── Noise XX message 1 (e) ─────────────────────────────────
           ── Noise XX message 2 (e, ee, s, es) ───────────────────────►
           ◄── Noise XX message 3 (s, se) ─────────────────────────────

           [secure transport mode begins]

           ── InvitationCertificate (CBOR, encrypted) ────────────────►
           ◄── IdentityClaim       (CBOR, encrypted) ──────────────────
           ── ACK ────────────────────────────────────────────────────►

           [persist trust edge on both sides, drop connection]
```

If any verification step fails, both sides emit a single `ABORT` frame,
drop the connection, and quarantine the peer's identity public key for
24 hours (no retry inside the window).

### 3.3 Records persisted

Both devices write a `TrustEdge` row to `core:database`:

```
TrustEdge {
  self_pub:      bytes[32]
  peer_pub:      bytes[32]
  community_id:  bytes[32]
  vouch_level:   u8              // PROVISIONAL | FULL
  established:  u64
  cert_blob:    bytes            // signed InvitationCertificate (peer-side)
  cert_signer:  bytes[32]        // == peer_pub for the Inviter row
}
```

## 4. Sync

Sync is the only protocol that moves user payloads between nodes after
the handshake. It runs whenever two trust-graph-connected nodes are
within transport range.

### 4.1 Message envelope

```
SyncEnvelope {
  v:        u8 = 1
  type:     u8                  // see below
  origin:   bytes[32]           // originating identity public key
  seq:      u64                 // origin's monotonic per-feed counter
  ts:       u64                 // origin's unix seconds (advisory; not trusted)
  payload:  bytes               // CBOR, type-specific
  sig:      bytes[64]           // Ed25519(origin) over (v||type||origin||seq||ts||payload)
}
```

Types (initial):

| Code | Name | Body |
|------|------|------|
| `0x01` | `VAULT_RECORD` | per-recipient sealed payload |
| `0x02` | `MARKETPLACE_OFFER` | local resource offer |
| `0x03` | `COORDINATION_EVENT` | task/event update |
| `0x04` | `DIRECTORY_ENTRY` | self-published role/skills |
| `0x10` | `INVITATION_CERT` | new invitation cert (for graph propagation) |
| `0x11` | `REVOCATION_CERT` | revocation (high priority) |

`seq` is per (`origin`, `type`) and strictly monotonic. Receivers reject
any envelope whose `seq` is not strictly greater than the highest seen
for that pair.

### 4.2 Anti-entropy

When two peers connect and complete Noise, they exchange a `HaveSet`:

```
HaveSet {
  pairs: [
    { origin: bytes[32], type: u8, max_seq: u64 }, ...
  ]
}
```

Each side computes the delta and streams the missing envelopes. There is
no global ordering — the system is eventually consistent per (origin, type)
feed, and that is sufficient because every envelope is independently
signed and verified.

### 4.3 Trust filtering

Before persisting any received envelope, the receiver checks:

1. `origin` is in the receiver's trust graph at level ≥ `Provisional`.
2. The Ed25519 signature verifies under `origin`.
3. `seq` is strictly greater than the local high-water mark for
   `(origin, type)`.
4. Type-specific constraints (e.g. `INVITATION_CERT` originator must be
   `Full` and have a Root-anchored path).

Failing any check: drop the envelope, increment a per-peer suspicion
counter. Three failures within an hour and the peer is `Quarantined`
locally pending a full re-handshake.

## 5. Vault Sealing (Type-Specific)

`VAULT_RECORD` payloads are per-recipient. Body:

```
VaultRecord {
  recipients: [
    { peer_pub: bytes[32], wrapped_key: bytes[32+16] /* X25519 || mac */ },
    ...
  ]
  nonce:      bytes[24]
  ciphertext: bytes           // XChaCha20-Poly1305 over the plaintext record
}
```

A vault record visible to N members has N wrapped keys. Non-recipients
can still relay the envelope (relays trust-filter on `origin`, not on
recipient list) but cannot decrypt.

## 6. Service UUIDs

The BLE service UUID and WiFi Direct service-info hash are both derived
deterministically from `community_id`:

```
service_uuid = UUID(BLAKE2s(community_id || "KEYSTONE-SVC"))
```

This means a passive scanner with no `community_id` cannot identify
Keystone nodes by advertisement alone. They appear as devices serving
an arbitrary 128-bit UUID. (A scanner who has joined any community can
of course detect *their* community's UUID; this is the intended outcome.)

## 7. Versioning

The leading `v:` byte in every CBOR structure is the protocol version.
Receiving a higher version than known: drop the message, do not error.
Receiving a lower version than the floor we still accept: drop the
message, do not error. Version negotiation is per-message-type and one-
way; there is no handshake-time version exchange because the handshake
itself is versioned by `HandshakeQR.v`.
