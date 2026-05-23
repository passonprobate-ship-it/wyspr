# Wyspr — Security Model

## 1. Threat Model

We design against three classes of adversary, in order of expected frequency:

| Adversary | Capabilities | Mitigation locus |
|-----------|--------------|------------------|
| **Network observer** | Passive wire capture on Bluetooth, WiFi, and any upstream relay | Noise XX framing — every byte after the first 32 is encrypted; transport headers carry no identity |
| **Active impersonator** | Can forge messages, replay frames, run a hostile peer claiming to be a member | Long-term Ed25519 identities; every record signed; replay-protected via Noise nonces and per-edge counters |
| **Infiltrator** | Has obtained a working build, attempts to join via a careless inviter | Web of Trust K-paths quorum; physical handshake requirement; revocation propagation |

We **do not** defend against:

- A nation-state with on-device implant after physical seizure. (Hardware
  keystore raises cost; it does not stop a determined attacker with the device.)
- A user who voluntarily discloses content. (Out of scope.)
- Long-term traffic-analysis correlation against an opponent that controls
  every hop. (We reduce metadata; we do not eliminate it. Tor narrows the
  surface but cannot close it against a global passive adversary.)

## 2. Identity

Each Wyspr install generates **one long-term identity key pair** on first
launch:

- **Algorithm**: Ed25519 (signing) + X25519 (Noise key agreement), with the
  X25519 keypair derived from the Ed25519 seed via libsodium's
  `crypto_sign_ed25519_sk_to_curve25519`.
- **Storage**: Android `KeyStore`, backed by `StrongBox` when the device
  exposes it, falling back to `TEE`. Software-only storage is rejected at
  install time; the app refuses to run.
- **Subkey derivation**: HKDF-SHA256 over a keystore-wrapped seed, with
  the info string `"WYSPR/v1/<purpose>"` as the only domain separator.
  Live consumers: `WYSPR/v1/db` (SQLCipher key) and `WYSPR/v1/tor-hs`
  (HSv3 hidden-service Ed25519 seed). Never reuse an info string.
- **Fingerprint**: First 16 bytes of `SHA-256(pub || domain_separator)`,
  rendered as five space-separated groups of base32 (e.g.
  `K5T2N AB3FR HJ8MQ XYZ4P 7VN2K`). This is the only identifier a human ever
  reads or types.
- **Rotation**: Identity keys do not rotate. Devices reset their identity by
  full reinstall. Membership transfer between devices is an explicit,
  vouched operation. (Per-peer `KeyRotation` propagation remains open — see §7.)

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
**Invitee**. Wire form (canonical CBOR, then signed):

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
   `{identity_pub, ephemeral_pub, community_id, nonce, minted_at, onion?}`.
   The QR is never transmitted electronically; it is shown on-screen and
   read with the camera. The optional `onion` field carries the device's
   long-range HSv3 address so peers can reconnect when out of BLE range
   (see §3.7).

2. **Scan.** Each device scans the other's QR. After scan, each device
   shows its **fingerprint** and the peer's fingerprint side-by-side. The
   users compare them verbally. The flow cannot proceed without an
   explicit "match" tap from both sides.

3. **Noise XX over short-range.** Both devices initiate a Noise_XX_25519_
   ChaChaPoly_BLAKE2s session over BLE GATT. Channel binding incorporates
   the QR nonces. If the channel-binding hash does not match what each
   device captured in step 1, the session aborts.

4. **Authorization check.** Before transmitting an InvitationCertificate,
   the Inviter consults its local `TrustGraph.canIssueInvitations(self)`.
   A node that does not meet the issuing privilege (see §3.5) aborts here
   without leaking which check failed.

5. **Vouch exchange.** Inside the encrypted channel, the Inviter sends a
   signed `InvitationCertificate`. The Invitee responds with its own
   self-signed `IdentityClaim`. Both are persisted as a new **trust edge**
   in the local Trust Graph, with the peer's `.onion` (if present in
   the QR) captured on the edge.

The handshake CANNOT be completed remotely. There is no fallback path
for "I'll scan you later." This is intentional.

### 3.4 Trust Levels

A node's trust score relative to the local viewer is computed from the
local Trust Graph:

| Level | Requirement |
|-------|-------------|
| **Root** | Self-signed; appears as a root in the local graph |
| **Full** | At least `K = 2` vertex-disjoint paths from a Root, each of length ≤ `D = 4` edges, using only `FULL` edges |
| **Provisional** | At least one path exists from a Root (any vouch level, length ≤ `D`) but the Full quorum is not met |
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
| Issue new invitations | `Full` (queried via `TrustGraph.canIssueInvitations`) |
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

Local revocation is fully implemented: the cert is persisted, the
target is added to `TrustGraphImpl.revoked`, and path search blocks
the revoked vertex thereafter so quarantined members cannot launder
trust for anyone downstream.

**Cross-peer propagation (v0.6.6).** Revocations now flow between
trusted peers via an anti-entropy sub-protocol that piggybacks on
the same Noise transport as the messaging round. After messaging
sync finishes, both peers exchange `RevocationSync` frames:

```
A                    B
|── HaveSet ────────►|     (issuer, target) pairs we hold
|◄── HaveSet ────────|
|── Want ───────────►|     pairs we don't yet have
|◄── Want ───────────|
|── Push ───────────►|     wire-encoded certs the peer asked for
|◄── Push ───────────|
```

CBOR tags 0x31 (HaveSet), 0x32 (Want), 0x33 (Push) — see
PROTOCOLS.md §4 for the wire format.

On receipt of each cert, [`RevocationSyncRepository.ingest`] runs
four checks in order:

1. **CBOR decode.** Malformed input rejected silently.
2. **Signature verify.** Ed25519 `verify(signedBytes, signature,
   issuer_pub)` must succeed. Forgeries dropped.
3. **Issuer trust check.** If `trustGraph.trustLevel(issuer) ∈
   {Unknown, Quarantined}`, drop silently. This is the security-
   critical gate: an attacker who learns *any* TrustEdge cannot
   impersonate its revoker because their pub key is not in any
   honest peer's trust graph. A previously-trusted member who is
   themselves later revoked cannot retroactively revoke others
   because their own subsequent revocations will be rejected.
4. **Persist + graph update.** The cert is upserted into the
   `revocation` table and `TrustGraph.ingestRevocation` updates
   the in-memory state, so the next cert in the same batch sees
   the freshly Quarantined issuer.

A failure of the revocation round after the messaging round
already succeeded is non-fatal — the message exchange is
preserved and the revocation propagation is retried the next time
the two peers sync.

There is **no un-revoke**; a quarantined identity must re-handshake
from scratch with a clean key pair.

### 3.7 Long-Range Connectivity (Tor Hidden Services)

After the in-person QR handshake, two peers may need to communicate
when out of BLE range. Wyspr uses **embedded Tor hidden services**
so neither device exposes a public IP or a Wyspr-operated rendezvous.

- Each install runs its own Tor daemon (kmp-tor `-exec` resource —
  the binary is extracted to `nativeLibraryDir` on install and
  `fork()`s as a subprocess).
- The HSv3 Ed25519 service key is derived deterministically from
  the keystore identity (`WYSPR/v1/tor-hs` HKDF subkey), so the
  `.onion` is stable across reinstalls as long as the keystore
  identity survives. Wiping the identity changes the `.onion`.
- The address is shared **only** through the QR handshake — never
  broadcast, never published to any directory. Possession of a
  `.onion` from a Wyspr QR therefore implies the holder has
  already passed the physical-trust check.
- Tor's own threat model still applies: a global passive adversary
  who sees both ends of a circuit can correlate timing. Wyspr
  treats `.onion`-routed traffic as confidentiality-preserving but
  not unlinkable against that adversary class.

### 3.8 K-Independent-Paths Quorum

The Full-trust quorum is enforced by `TrustGraphImpl.countIndependentPaths`.
The algorithm is iterative shortest-path with vertex- and edge-blocking:

```
fn countIndependentPaths(target, K, fullOnly) -> Int:
    blockedVertices := { all revoked pubkeys }
    blockedEdges    := ∅
    found := 0
    while found < K:
        path := shortestPath(target, maxLen=D,
                             blockedVertices, blockedEdges,
                             fullOnly)
        if path is null: break
        for each intermediate vertex v on path:   # not source or target
            blockedVertices.add(v)
        for each edge (u,v) on path:
            blockedEdges.add((u,v))
        found += 1
    return found
```

Properties this gives us:

- **Vertex disjointness.** Two paths cannot share a non-root
  intermediate. A single compromised member cannot single-handedly
  elevate a stranger to Full.
- **Edge disjointness on shared roots.** Multiple roots may anchor
  multiple paths to the same target, but no two paths use the same
  first hop from a shared root. This blocks the trivial "one root,
  two parallel claims through the same neighbour" attack.
- **Revocation cascade.** Any revoked vertex is blocked from the
  outset, so a once-trusted member who is later revoked cannot
  appear on any future path computation.
- **Bounded cost.** With `D = 4` and graph sizes well under 10⁴
  vertices, each BFS is microseconds and we run at most K of them
  per query. No caching is applied — the invalidation surface
  outweighs the saving.

The trust level is computed lazily on each query; reads take a
read lock and never block writes.

### 3.9 Why Not a Blockchain?

A consensus ledger is overkill for a graph this small and works against
us: every member would need every record. The trust graph is intentionally
**partial and local** — node A may not know that B was revoked by C until
A and C sync, and that is the desired behavior. Communities partition,
heal, and split organically. A global ledger would force uniformity we
don't want.

## 4. Data at Rest

- **Database**: SQLCipher with a 256-bit key derived from the hardware-
  keystore identity key via HKDF (`WYSPR/v1/db` subkey). The database
  cannot be opened on another device, even with the file copied off.
- **Tor hidden-service key**: HSv3 Ed25519 seed derived from the
  `WYSPR/v1/tor-hs` subkey; written to Tor's `HiddenServiceDir`
  with POSIX 0700 permissions; never persisted in plaintext outside
  that directory.
- **Vault payloads**: Sealed with XChaCha20-Poly1305; the symmetric key
  is wrapped per-recipient with X25519 ECDH. (Vault feature module is
  scaffold-only as of v0.6.)
- **Messaging**: Outbound and inbound message rows live in the same
  SQLCipher-encrypted Room database. Bodies are stored decrypted at
  rest under the SQLCipher key — confidentiality on disk relies on
  the hardware keystore, not on a separate per-message wrap.
- **Backups**: Disabled. `android:allowBackup="false"`,
  `android:fullBackupContent="false"`, no `dataExtractionRules.xml`
  permitting any export. Users who want a backup re-handshake on the
  new device.

## 5. Data in Transit

All payload bytes between any two Wyspr nodes traverse a Noise_XX_25519_
ChaChaPoly_BLAKE2s session. Transport-layer headers are minimal and
carry no identity:

```
[ frame_len: u16 ][ noise_ciphertext: bytes ]
```

The MAC layer (BLE advertising, WiFi Direct beacons) advertises an
opaque service UUID derived as `BLAKE2s(community_id || "WYSPR-SVC")`
truncated to 16 bytes — see PROTOCOLS.md §6. A passive observer cannot
distinguish two Wyspr nodes from one another without joining.

For long-range traffic, the same Noise frames flow inside a Tor
circuit terminating at the peer's hidden service. The transport layer
SOCKS5-connects through the local Tor daemon's auto-assigned listener.

## 6. Software Distribution

Wyspr never depends on an app store. Two delivery paths exist; both
keep distribution peer-to-peer.

- **Peer share (initial install).** A user opens "Share Wyspr" and the
  device stands up an on-device HTTPS server bound to its private-network
  IP, using a per-session self-signed certificate. A QR encodes the URL.
  The recipient scans, reviews a one-page mini-site, and downloads the
  APK directly over the local network.
- **Layer-1 peer update.** A user pastes or scans a peer's share URL;
  the client fetches `/version.json`, compares to the local versionCode,
  and (if newer) streams the APK with on-the-fly SHA-256 verification
  before handing it to the system `PackageInstaller`. The SSRF gate
  resolves the host and admits only RFC 1918 (`10/8`, `172.16/12`,
  `192.168/16`) and CGNAT (`100.64/10` — for Tailscale) addresses.
  Public IPs, loopback, link-local, multicast, and IPv6 are rejected.
- **APK signing.** Release builds are signed with a single keystore
  (see `app/build.gradle.kts`); recipients can verify the signature
  themselves before install. The system `PackageInstaller` refuses to
  upgrade an existing install whose signer differs.

Layer-2 (automatic discovery via BLE trust channel) and Layer-3
(K-quorum verification of the APK over the trust graph) are open work
— see §7.

## 7. Failure Modes That Must Stay Failure Modes

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
- **Outbound to arbitrary hosts.** Peer-pull updates MUST traverse the
  SSRF gate. Loosening the allowlist to public IPs would convert
  Wyspr into an attacker-controlled fetcher.

## 8. Open Questions

These decisions are not final and should be revisited before v1.0:

1. **Quorum `K`** — is 2 paths enough, or should `Full` require 3 for
   larger communities? `core:trust` already supports raising it per
   community.
2. **Provisional TTL** — should `PROVISIONAL` edges expire if not upgraded
   within N days? Currently they persist indefinitely.
3. **Revocation propagation — RESOLVED (v0.6.6).** Receive-side rules
   landed in §3.6 above. The remaining work is the two-device
   hardware proof (the Alice→Bob→Carol scenario) — code is wired
   but unproven on physical devices. Tracked as v0.6.6b in
   NEXT-STEPS.md.
4. **Group key rotation** — Vault groups have no rotation story yet;
   when a group member is revoked, do we re-encrypt or accept that
   their old reads were already cached?
5. **KeyRotation envelope** — a legitimate identity reset currently
   looks identical to a compromise. A signed `KeyRotation` linking
   old pub → new pub would let peers age in the new identity without
   losing the trust graph.
6. **Tor / clearnet correlation** — when both BLE and Tor are
   simultaneously available to the same peer pair, do we pick one or
   multiplex? Multiplexing helps reliability; it also widens the
   timing-correlation surface.

## 9. Software Distribution Tiers

Anti-platform distribution in three tiers, in order of who is
trusted to host the bytes:

| Tier | Channel | Operator trust |
|---|---|---|
| 1 | F-Droid + community mirrors | Single signing key (Wyspr maintainer) |
| 2 | Peer share (`feature:onboarding/share/`) | The trusted peer in front of you |
| 3 | Onion mirror (future, v0.7.4) | Any peer with a known `.onion` |

F-Droid is the canonical channel. Its mirror format is signed flat
files over HTTPS, so any HTTPS endpoint can rehost the same APK
under the same signing key — useful when fdroid.org itself is
blocked. The peer-share path uses an in-flight self-signed cert
verified against a QR-delivered fingerprint, so the recipient
verifies the bytes against the same person they just trust-paired
with. The onion-mirror path (deferred) lets joiners fetch the APK
through the embedded Tor we already ship, without any uplift in
trust beyond their existing trust graph.

We will never ship to Google Play — see docs/FDROID.md §4.

## 10. Cryptocurrency Wallet (Monero)

Wyspr bundles a Monero wallet as a utility module
(`:feature:monero-wallet`, v0.7.0a scaffold landed; JNI crypto
engine scheduled for v0.7.0b).

Selection rationale: Monero is the only mainstream cryptocurrency
whose default-private semantics align with the project's anti-
metadata posture. BTC is rejected (default-transparent). ARRR has
strong cryptography but a smaller ecosystem and platform risk on
Komodo.

### 10.1 Threat additions

| Threat | Mitigation |
|---|---|
| Remote-node operator correlates the user's IP with view-key registration | Mandatory routing through the embedded Tor SOCKS proxy. Node never sees device IP. |
| Hostile remote node returns false chain tip / withholds inbound transactions | Round-robin across a curated node pool; majority-rules tip check; tx submission to ≥2 nodes (v0.8.2). |
| Spend key on disk → wallet drain on device compromise | Encrypted under the `WYSPR/v1/monero` HKDF subkey of the hardware-keystore identity. Wallet file unrecoverable after a keystore reset. |
| View-key leak reveals every incoming payment | Per-community sub-wallets so a compromised view key exposes only one community's payment history. |
| DNS leak of remote-node hostname | RPC client uses SOCKS5 DOMAINNAME (ATYP=0x03) — Tor resolves the hostname; the device's stub resolver never sees it. |
| Hostile node streams gigabytes to OOM the wallet UI | Response body capped at 256 KB per request. |
| Header smuggling via hostile node hostname | Hostnames validated to `[A-Za-z0-9.-]` at construction; no CR/LF can reach the HTTP layer. |

### 10.2 Out of scope (deferred)

- **Decoy quality.** Remote-node decoy selection is what the node
  ships; we trust the daemon's `get_outs` to follow the standard
  selection algorithm. A future hardening would have us choose
  decoys client-side.
- **Tx fingerprinting via signing time / hop count.** Not yet
  measured; tracked separately.

### 10.3 Licence note

The combined APK shifts to GPLv3 once the JNI crypto binding is
bundled in v0.7.0b. Wyspr's other modules remain Apache-2.0;
GPL contagion is contained to the distributed binary.
