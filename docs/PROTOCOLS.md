# Keystone — Protocols

This document specifies the on-the-wire and on-disk formats. Implementations
in any module MUST match this spec; if they diverge, the spec is wrong, fix
it here first.

All multi-byte integers are big-endian. All structured payloads are CBOR
(RFC 8949) with deterministic encoding (RFC 8949 §4.2.1) via the shared
`core:crypto/Cbor` codec, which rejects non-shortest integer encodings,
indefinite-length items, and trailing bytes on decode.

## 1. Frame Layer

Every transport (BLE, WiFi Direct, Tor, future Reticulum) carries the
same frame:

```
[ frame_len: u16 ][ payload: bytes ]
```

- `frame_len` is the byte length of `payload`, max 16384.
- `payload` is always a Noise transport-mode ciphertext **after** session
  establishment, or a Noise handshake message **during** establishment.
- There are no plaintext frames at the application layer. Ever.

Transports MAY add their own framing below this (BLE chunks at `MTU − 3`
bytes with a length-prefixed reassembler; Tor wraps in TCP-over-circuit)
but MUST NOT inspect or modify the payload.

## 2. Noise Session

Pattern: `Noise_XX_25519_ChaChaPoly_BLAKE2s`, via the `noise-java` impl
pinned at commit `49377b6`.

Why XX:
- Mutual authentication.
- Both parties' static keys are exchanged during the handshake — neither
  needs prior knowledge of the other's identity key.
- Forward secrecy via ephemeral keys.

Channel binding:
- The handshake hash after the final XX message is included in the first
  application message. The receiver re-derives it and compares. If it
  differs, the session terminates.
- For the handshake initiated from a QR scan, both QR nonces and the
  community id are mixed into the prologue:
  `prologue = "KEYSTONE/v1" || community_id || nonce_inviter || nonce_invitee || eph_pub_inviter || eph_pub_invitee`
  This binds the cryptographic session to the physical-world act of
  scanning.

Rekey policy:
- Rekey after `2^20` frames or 24 hours, whichever comes first.

## 3. Handshake (Onboarding)

### 3.1 QR Payload

Canonical CBOR array, base32 (no padding) encoded for QR density.
**Wire version 2** (current — Sprint 3 onwards) carries seven fields:

```
HandshakeQR v2 [
  ver:        u8 = 2,
  community:  bstr(32),     // community_id
  identity:   bstr(32),     // identity public key
  ephemeral:  bstr(32),     // ephemeral X25519 public key for this handshake
  nonce:      bstr(16),     // bound into Noise prologue
  mintedAt:   u64,          // unix seconds; QR is rejected if > 5 min old
  onion:      bstr(56) | null,  // optional HSv3 .onion (ASCII)
]
```

**Wire version 1** (legacy, decode-only) is identical without the trailing
`onion`. Encoding always emits v2 — the QR is regenerated every 5 minutes
while the screen is open, so there are no v1 emitters to keep working.
Stale QRs are rejected on scan.

The decoder pins the field count to the declared version: a v1 array
masquerading as `ver=2` (or vice versa) is rejected even though both
versions are otherwise accepted.

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

           [Inviter consults local TrustGraph.canIssueInvitations(self);
            aborts here without leaking which check failed if it returns
            false]

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
  fromPub:        bytes[32]     // self
  toPub:          bytes[32]     // peer
  vouchLevel:     str           // "PROVISIONAL" | "FULL"
  establishedAt:  u64
  certBlob:       bytes         // signed InvitationCertificate (peer-side)
  certSigner:     bytes[32]     // == peer pub for the Inviter row
  peerOnion:      str | null    // peer's HSv3 .onion captured from QR
}
```

The primary key is `(fromPub, toPub)`; a second handshake between the
same pair replaces the first. `peerOnion` is captured at handshake time
and used by the Tor transport to dial the peer when out of BLE range.

### 3.4 Schema versions

`core:database` migrates additively:

| Version | Change |
|---------|--------|
| 1 | Initial schema: trust_edge, revocation, community_membership |
| 2 | Marketplace/wallet (currency_envelope) tables |
| 3 | Messaging tables (`message_outbound`, `message_inbound`, conversation index) |
| 4 | Read-receipts column on outbound messages |
| 5 | `trust_edge.peerOnion` nullable text — captures peer's HSv3 address from the QR |

Older devices that haven't migrated to v5 simply lack the column and
fall back to BLE-only reconnect; the handshake still completes.

## 4. Currency / Marketplace Sync

The `core:sync` engine moves community-currency envelopes between peers
once Noise is up. The protocol is a single round of HaveSet → Want → Push.

### 4.1 Envelope shape

Envelopes are stored in `currency_envelope` keyed by
`(community, typeTag, primaryKey)`. The wire form is the canonical CBOR
of the underlying payload — sync moves bytes; only the receiver decodes.

Type tags (stable, never reuse or reorder — see CURRENCY.md §7):

| Tag | Name | Body |
|------|------|------|
| `0x10` | `GENESIS_ISSUANCE` | community-bootstrap mint |
| `0x11` | `SERVICE_ISSUANCE` | service-time issuance |
| `0x12` | `TRANSFER` | peer-to-peer transfer |
| `0x13` | `TRANSFER_MEMO` | optional memo attached to a transfer |
| `0x14` | `SLASH` | revocation of a previously issued unit |

### 4.2 Anti-entropy round

```
SyncMessage = HaveSet | Want | Push

HaveSet {
  community: bytes[32]
  keys: [ { typeTag: u32, primaryKey: bytes } ... ]
}

Want {
  community: bytes[32]
  keys: [ { typeTag: u32, primaryKey: bytes } ... ]
}

Push {
  rows: [ EnvelopeRow ... ]
}

EnvelopeRow {
  community:  bytes[32]
  typeTag:    u32
  primaryKey: bytes
  body:       bytes          // canonical CBOR of the underlying envelope
  observedAt: u64
}
```

Round shape:

1. Both peers send `HaveSet`.
2. Both peers send `Want` (peer's keys minus ours).
3. Both peers send `Push` with the rows the peer wanted.

There is no global ordering — the system is eventually consistent
per `(community, typeTag, primaryKey)`, and that is sufficient because
every payload is independently signed and verified before being persisted.

### 4.3 Trust filtering

Before persisting any received envelope, the receiver checks:

1. The originator (recovered from the body's signature) is in the
   receiver's trust graph at level ≥ `Provisional`.
2. The Ed25519 signature on the body verifies.
3. Type-specific constraints (e.g. issuance signed by an account
   authorised under the community's currency rules; transfer balances
   reconcile).

Failing any check: drop the envelope, increment a per-peer suspicion
counter. Three failures within an hour and the peer is `Quarantined`
locally pending a full re-handshake.

## 5. Messaging Sync

Messaging runs in a separate sync pass over the same Noise session.
The protocol is small enough that it has its own framing rather than
sharing the currency engine's HaveSet/Want/Push.

### 5.1 Envelope

```
MessageEnvelope [           // signed bytes are the first 5 fields
  id:        bstr(16),      // random per-message
  fromPub:   bstr(32),      // sender identity
  toPub:     bstr(32),      // recipient identity
  createdAt: u64,           // sender's unix seconds
  body:      bstr,          // UTF-8, ≤ 16384 bytes
  signature: bstr(64),      // Ed25519 over the 5 fields above
]
```

The signed form (5 fields) and wire form (6 fields with the trailing
signature) share the same canonical encoder. The hardware keystore
produces the signature; the recipient verifies via libsodium before
persisting.

### 5.2 Round protocol

After Noise is up, either side may initiate a messaging round. Each
frame is a 1- or 2-element CBOR array `[tag, payload?]`:

| Tag | Name | Payload |
|-----|------|---------|
| `0` | `Push` | `[ MessageEnvelope.wireBytes ... ]` (≤ 1000 per frame) |
| `1` | `Ack`  | `[ bstr(16) ... ]` — ids the receiver now holds |
| `3` | `Read` | `[ bstr(16) ... ]` — ids the receiver has read; flips read-receipt state on the outbound side |
| `2` | `End`  | empty — closes the round |

```
Initiator                       Responder
---------                       ---------
PUSH(env...)        -->
                    <--         ACK(id...)
                    <--         PUSH(env...)   (if responder has pending)
ACK(id...)          -->
READ(id...)         -->         (optional)
                    <--         READ(id...)    (optional)
END                 -->
                    <--         END
```

Each frame goes through `NoiseSession.encrypt` on the sender and
`decrypt` on the receiver. The 1000-envelope cap defends against a
hostile peer claiming a huge array; arrays beyond that abort the round.

## 6. Service UUIDs

The BLE service UUID and WiFi Direct service-info hash are both derived
deterministically from `community_id`:

```
service_uuid = UUID(BLAKE2s-256(community_id || "KEYSTONE-SVC")[0..16])
```

BLAKE2s is provided by noise-java's `Blake2sMessageDigest` (already on
the classpath via Noise). RFC 7693 vectors are exercised by
`ServiceUuidTest`.

This means a passive scanner with no `community_id` cannot identify
Keystone nodes by advertisement alone. They appear as devices serving
an arbitrary 128-bit UUID. (A scanner who has joined any community can
of course detect *their* community's UUID; this is the intended outcome.)

## 7. Long-Range Transport (Tor Hidden Services)

Two peers who completed an in-person QR handshake can reconnect over
the public internet without exposing IPs or relying on any Keystone
infrastructure.

### 7.1 Hidden service key derivation

Each install runs an embedded Tor daemon (kmp-tor `-exec`). The HSv3
Ed25519 key seed is derived from the device's keystore identity:

```
hs_seed = HKDF-SHA256(
    ikm  = keystoreManager.deriveSubkey("KEYSTONE/v1/tor-hs"),
    info = "..."
)
```

The resulting v3 onion address is **stable across reinstalls** as long
as the user's keystore identity survives. Wiping the identity (factory
reset, app reinstall on a wiped device) regenerates the keystore
identity and therefore the `.onion`.

### 7.2 Address exchange

Each device publishes its own `.onion` only inside the handshake QR
(see §3.1). After the handshake the peer's `.onion` is persisted on
the `TrustEdge` row (`peerOnion`). There is no directory, no
beacon, no third-party rendezvous.

### 7.3 Transport mechanics

- Tor's SOCKS5 listener is picked automatically; the chosen port is
  surfaced via `RuntimeEvent.LISTENERS`.
- The hidden service forwards `<our>.onion:9091` →
  `localhost:9091` (`TorBackend.DEFAULT_HS_TARGET_PORT`), where the
  `TorHiddenServiceTransport` binds its Noise listener.
- Outbound connects SOCKS5-CONNECT through the local Tor listener to
  `<peer>.onion:9091`. Frames on the resulting TCP socket follow §1.
- Bootstrap progress is surfaced via `TorBackend.state` so the UI
  can render `Bootstrapping(percent)` → `Ready`.

## 8. Software Distribution

### 8.1 Peer APK share

When a user opens "Share Keystone", the device:

1. Starts the foreground transport service (so the share survives
   backgrounding).
2. Generates a per-session self-signed X.509 certificate (RSA-2048,
   `CN=<random-token>`).
3. Binds an HTTPS listener on the first private-network IPv4
   discovered by `LocalIp` (RFC 1918, then any other non-loopback v4).
4. Encodes the URL into a QR shown on screen, with a "tap to copy"
   affordance for cameras that can't read it.

The recipient's browser displays a TLS warning (self-signed); the
mini-site explains the warning and offers the APK directly.

### 8.2 Layer-1 peer update

A user pastes or scans a peer's share URL. The client:

1. Resolves the host through the **SSRF gate** — admits only
   `10/8`, `172.16/12`, `192.168/16`, and `100.64/10` (CGNAT /
   Tailscale). Rejects loopback, link-local, multicast, IPv6, and any
   public IPv4.
2. Fetches `/version.json`. Compares `versionCode` to the local
   build.
3. If newer, streams `/keystone.apk` with on-the-fly SHA-256
   computation against the digest in `/version.json`.
4. On match, hands the APK to the system `PackageInstaller`. The
   system layer will reject the install if the signer differs from
   the installed Keystone.
5. If the user has not granted `REQUEST_INSTALL_PACKAGES`, surface
   a dedicated state prompting them to enable "Install unknown apps"
   for Keystone.

Layer-2 (automatic discovery through the BLE trust channel) and
Layer-3 (K-quorum verification of the APK over the trust graph)
remain open work.

## 9. Versioning

The leading `ver:` element in every CBOR structure is the protocol
version. Receiving a higher version than known: drop the message, do
not error. Receiving a lower version than the floor we still accept:
drop the message, do not error. Version negotiation is per-message-type
and one-way; there is no handshake-time version exchange because the
handshake itself is versioned by `HandshakeQR.ver`.
