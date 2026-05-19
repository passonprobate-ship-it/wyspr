# Keystone — Gem (in-community currency)

This document is the spec for **Gem**, Keystone's in-community currency.
Implementations in `core:currency` (primitives), `feature:marketplace`
(wallet UX), and `core:sync` (envelope propagation) MUST match this
document; if they diverge, fix the doc first.

---

## 1. Goals and Non-Goals

### Goals

- Members can hold and transfer a unit of value entirely offline, propagated
  by the same mesh sync that carries chat and coordination.
- Issuance is bounded and auditable: every coin in existence can be traced
  back to a signed issuance certificate.
- Service providers (relay nodes, mirrors, anyone running a useful daemon
  for the group) can be paid in-protocol without manual bookkeeping.
- Double-spend is detected and punished, without requiring global consensus.
- The whole system works inside one Keystone community, with no cross-
  community settlement and no bridge to external chains.

### Non-Goals

- We are NOT building Bitcoin. There is no chain, no proof-of-work, no
  mining, no global ledger, no public addresses, no anonymity guarantees
  beyond what the trust graph already provides.
- We are NOT building a stablecoin. Gem has no peg; its value is
  whatever the community decides it is worth.
- We are NOT building an exchange. Transfers are member-to-member only;
  any cross-community swap is out-of-band.
- We are NOT shielding amounts. Members within a community can audit each
  other's balances after sync (this is a feature — accountability is the
  point of mutual credit). If shielded amounts are needed later, that's
  v2 work.

---

## 2. Model in One Paragraph

Gem is a community-scoped, account-based ledger replicated via CRDT
sync. Each account is identified by a member's Ed25519 identity key.
Every entry on the ledger is a signed envelope: an `Issuance` minted by
the community founder or a service operator, or a `Transfer` from one
identity to another. Each sender maintains a strictly monotonic
per-account sequence number; the local balance is a deterministic
fold over the signed envelopes the device has seen. Double-spend (two
different transfers with the same sequence number) is detectable on
merge and triggers a `Slash` — the offending identity is zeroed and
revoked. There is no global "block height" and no consensus round;
finality is local, and conflicts surface during sync.

---

## 3. Account Model

| Field | Source | Notes |
|---|---|---|
| `account` | An Ed25519 identity public key | Same key as the Web of Trust identity — there is no separate "wallet key" |
| `balance` | Computed locally from signed envelopes | Never stored as a primary value; cached for UX only |
| `seq` | Monotonic counter, per account | Incremented by one for every outbound `Transfer` the account signs |
| `slashed` | Boolean, set by an accepted `Slash` | Once true, the account's balance is zero and it cannot issue or receive |

Balance is computed as:

```
balance(A) = Σ inbound transfers to A
           + Σ issuances naming A as recipient
           - Σ outbound transfers from A
           - (if slashed(A)) infinity
```

Computed only over envelopes whose signatures verify, whose issuers/senders
are not slashed, and whose `seq` chain is monotonic and gap-free.

---

## 4. Issuance

There is exactly one rule: **coins enter the system only through a signed
`IssuanceCertificate`.** Two kinds exist.

### 4.1 Genesis Pre-Mine

Issued exactly once per community, signed by the community founder's
identity key. Mints the initial supply to one or more recipient accounts.

```
IssuanceCertificate (genesis):
  version       : u8 = 1
  kind          : enum = GENESIS
  community     : CommunityId (32 bytes)
  issuer        : PublicKey (32 bytes)   # must equal community founder
  recipients    : [(PublicKey, u64)]     # account → amount in base units
  total_supply  : u64                    # informational; MUST equal Σ amounts
  issued_at     : i64                    # unix seconds
  nonce         : bytes (16)
  signature     : bytes (64)             # Ed25519 over canonical CBOR of all above
```

A community has at most one accepted `GENESIS` certificate. Subsequent
genesis certs (forks) are rejected on arrival.

### 4.2 Service Reward

Issued by a member running a community-valued service. The service kinds
are enumerated; free-text reasons would leak metadata and create gaming
surface.

```
IssuanceCertificate (service):
  version       : u8 = 1
  kind          : enum = SERVICE
  community     : CommunityId
  issuer        : PublicKey                # member running the service
  recipients    : [(PublicKey, u64)]
  service_kind  : enum { RELAY, MIRROR, BACKUP, GATEWAY, OTHER }
  service_proof : bytes (≤256)             # opaque, service-kind-specific
  period_start  : i64
  period_end    : i64
  nonce         : bytes (16)
  signature     : bytes (64)
```

Issuance rate caps are community-scoped parameters set at genesis (see §10).
A `SERVICE` certificate that exceeds the per-period cap for its issuer is
treated as a `Slash` event against the issuer.

`service_proof` is service-kind specific: for `RELAY`, it's a count of
sync envelopes forwarded during the period, signed by recipients that
witnessed the relaying. For `MIRROR`, it's a hash of the data mirrored
plus a recipient signature attesting download. The spec for each is
deferred until the corresponding service exists.

---

## 5. Transfer

```
Transfer:
  version       : u8 = 1
  community     : CommunityId
  sender        : PublicKey
  recipient     : PublicKey
  amount        : u64
  seq           : u64                      # sender's monotonic counter
  memo_hash     : bytes (32) | null        # hash of optional memo (memo body
                                           # is a separate envelope, not here)
  issued_at     : i64
  nonce         : bytes (16)
  signature     : bytes (64)               # Ed25519 over canonical CBOR
```

Validation, in order — failing any step drops the envelope:

1. Signature verifies under `sender`.
2. `community` matches the local community.
3. `sender` is in the trust graph at `Provisional` or higher and is not
   `slashed`.
4. `recipient` is in the trust graph at `Provisional` or higher and is not
   `slashed`.
5. `seq` is exactly `last_seen_seq(sender) + 1`. (Gap → buffer until
   ancestors arrive; collision → §6 double-spend handling.)
6. `amount` ≤ computed `balance(sender)` excluding this transfer.

Memos are never inline. If a transfer has a memo, the sender publishes a
separate `TransferMemo` envelope keyed by `(sender, seq)`, encrypted to
the recipient's X25519 key. Receivers without the memo see only the hash.

---

## 6. Double-Spend Handling

This is the core design choice. We **detect and slash**, we do not prevent.

### 6.1 Detection

Two distinct `Transfer` envelopes with the same `(sender, seq)` and
different signatures (i.e. different content but both signed by the same
account) is a **conflict**. The CRDT merge layer treats `(sender, seq)`
as the primary key and surfaces any conflict to the currency module.

A conflict is a cryptographically self-contained proof of misbehavior:
both transfers are signed by the offender, both signatures verify, and
they cannot both be intended honestly. No tribunal is needed — anyone
who sees both can independently verify and act.

### 6.2 Slash Envelope

```
Slash:
  version       : u8 = 1
  community     : CommunityId
  target        : PublicKey            # the offender
  evidence      : (Transfer, Transfer) # the conflicting pair, by ref or inline
  issued_at     : i64
  witness       : PublicKey            # the member who observed the conflict
  signature     : bytes (64)           # witness signs the slash envelope
```

When the currency module observes a conflict, it produces and emits a
`Slash`. Slash envelopes propagate via the standard sync layer.

On accepting a `Slash`:

- The target's `slashed` flag is set locally.
- The target's outstanding balance and any pending outbound transfers
  from the target are zeroed.
- A `RevocationCertificate` is also issued against the target (the same
  revocation type the trust graph already uses, §3.6 of SECURITY-MODEL.md).
- Both halves of the conflicting transfer are recorded; recipients of
  either half MAY treat their pending credit as unrecoverable.

### 6.3 Recipient Risk

Until a transfer's `seq` has been independently witnessed by other peers
(i.e. you've synced the sender's `(seq+1)` from someone else, or a witness
chain has formed), the recipient bears the risk of a double-spend. The
wallet UI exposes this:

- **Pending** — single witness (just you). Don't rely on it for material
  transactions.
- **Confirmed** — at least one other member has acknowledged seeing the
  same `seq`. Default threshold for the UI is 2 witnesses. Community-tunable.

This is the v0 design. v1 may add optional witness co-signing (§10) for
faster confirmation at the cost of an extra round trip.

---

## 7. Wire Format

All envelopes are CBOR with deterministic encoding (RFC 8949 §4.2.1).
Field order is fixed and MUST match the order in this document; deterministic
encoding alone is not sufficient because `signedBytes()` covers a
specifically-ordered tuple.

Envelopes sit inside the existing `SyncEnvelope` wrapper (`core:sync`)
with the following new types:

| Type tag | Envelope |
|---|---|
| `0x10` | `IssuanceCertificate` (kind=GENESIS) |
| `0x11` | `IssuanceCertificate` (kind=SERVICE) |
| `0x12` | `Transfer` |
| `0x13` | `TransferMemo` |
| `0x14` | `Slash` |

---

## 8. Storage

Two new Room tables in `core:database`:

```
account
  pub             BLOB PRIMARY KEY  -- 32-byte identity public key
  seq             INTEGER NOT NULL  -- max seq observed
  balance_cached  INTEGER NOT NULL  -- UX-only; recomputable from envelopes
  slashed         INTEGER NOT NULL  -- 0/1

currency_envelope
  community       BLOB NOT NULL
  type_tag        INTEGER NOT NULL  -- 0x10..0x14
  primary_key     BLOB NOT NULL     -- (sender, seq) for transfers,
                                    -- (issuer, period_start) for service
                                    -- issuances, etc.
  body            BLOB NOT NULL     -- canonical CBOR of the envelope
  observed_at     INTEGER NOT NULL
  PRIMARY KEY (community, type_tag, primary_key)
```

The cached balance exists so the wallet UI doesn't recompute from scratch
on every open. A `Recompute` debug command is exposed for verification.

---

## 9. Wallet UX

The minimum viable wallet, surfaced as `feature:marketplace`:

- **Home**: current balance, pending balance, last 10 envelopes.
- **Send**: pick a recipient from the trust graph (cannot send to
  Unknown / Quarantined / non-members); enter amount; optional encrypted
  memo. The send button signs and queues the `Transfer`; the wallet UI
  shows it as **Pending** until witnessed.
- **Receive**: shows your fingerprint and identity key as a deep-link
  payload; peer wallet sends to that. No new QR; reuse identity.
- **History**: filterable by counterparty, amount, time window. Memos
  shown only for transfers where the local device has the matching
  `TransferMemo` envelope decrypted.
- **Audit**: opt-in screen that recomputes balance from raw envelopes
  and flags any drift from the cached value. Useful for support; not
  on the home screen.

There is no "address book" beyond the trust graph. The trust graph
*is* the address book.

---

## 10. Community Parameters

Set at genesis, signed into the `GENESIS` certificate, immutable thereafter:

| Parameter | Default | Notes |
|---|---|---|
| `base_units_per_coin` | 1_000_000 | Smallest indivisible unit — like satoshi |
| `initial_supply` | community-set | Total minted in genesis |
| `service_reward_cap_per_period` | community-set | Per-issuer cap |
| `service_period_seconds` | 86_400 | Daily reward window |
| `confirmation_witness_threshold` | 2 | Witnesses needed before "Confirmed" |
| `max_pending_seq_gap` | 32 | How many out-of-order envelopes to buffer |

Changing these requires a new community, by design. If the community wants
to amend the rules, the path is: fork to a new community, migrate balances
via signed `Migration` envelopes (out of scope here, v2 work).

---

## 11. Failure Modes

| Scenario | Outcome |
|---|---|
| Sender's device goes offline mid-spend | Transfer envelope is queued in `core:sync` outbox; flushes on next contact. Recipient sees Pending → Confirmed when peers sync |
| Sender double-spends accidentally (device clock skew, multi-device) | Slashed. We do not distinguish accident from malice. This is the cost of holding the keys yourself |
| Sender's device is stolen | Existing identity revocation flow applies. Coins are lost — there is no recovery key, by design |
| Service reward issuer is over-cap | Slashed. Cap is per issuer per period, not global |
| Two devices, same identity, both spend | Slashed — `(sender, seq)` conflict. Multi-device support is a v2 feature requiring co-signed transfers |
| Genesis cert is forged | Rejected — the founder identity must match the community's stored root, set during community creation |
| Receiver's device gets a `Slash` for a sender mid-spend | Pending transfers from that sender are voided; receiver's cached balance drops by the affected amount |
| The community partitions for a long time | Each side advances normally; on reunion, conflicts are resolved per §6. A long partition with high transaction volume amplifies the slash blast radius — this is the price of strong-eventual-consistency without consensus |

---

## 12. Open Questions

1. **Witness co-signing as a tier above mutual credit.** For high-value
   transfers, allow opt-in N-of-M co-signing by other community members
   before the transfer is final. Trade-off: latency vs. trust.
2. **Memo encryption when recipient has no recent X25519 ephemeral seen.**
   Fall back to identity-key encryption (libsodium `crypto_box_seal`)? Or
   defer the memo until a fresh handshake? Open.
3. **Service-reward gaming.** A relay can over-report. The `service_proof`
   must include recipient signatures, but the design of those is per
   service-kind and not yet specified. This is the single biggest design
   gap before currency ships in production.
4. **Cross-community settlement.** Out of scope for v1. If multiple
   Keystone communities want to trade, the right primitive is probably a
   bilateral channel anchored in both communities' trust graphs — but
   that's a separate doc.
5. **Recovery from total loss.** Currently impossible by design. Whether
   to support a community-witnessed recovery (e.g. N trusted members can
   co-sign a balance transfer from a dead key to a fresh key) is an open
   community-policy question, not a protocol question.

---

## 13. v0 Implementation Order

Each step is independently testable.

1. CBOR canonical encoder/decoder for `IssuanceCertificate (GENESIS)` and
   `Transfer`. Unit tests for round-trip and signature verification.
2. `core:database` schema additions (`account`, `currency_envelope`).
3. `LedgerState` reducer: deterministic fold over a stream of envelopes
   → balances + seq map. Pure function; trivially testable.
4. `WalletService` in `feature:marketplace`: signs/emits transfers,
   queues into `core:sync` outbox, reads cached balance.
5. Wallet Compose screens (Home, Send, Receive, History).
6. `SlashDetector`: watches incoming envelopes for `(sender, seq)`
   collisions, emits `Slash`.
7. `SERVICE` issuance: deferred until the first community service exists.

Service reward `service_proof` formats are deferred to when the relevant
services are built. Until then, only `GENESIS` issuances and member-to-
member transfers exist.
