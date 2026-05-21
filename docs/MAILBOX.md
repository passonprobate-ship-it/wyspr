# Keystone — Mailboxes

A mailbox is a Keystone device that volunteers to **hold encrypted
messages for community members until they come online**. Solves the
"both peers must be online at the same time" failure mode without
introducing any central server.

## What the user sees

Two new home tiles. That's the entire surface.

**Use a mailbox**

> Friends can drop messages here when you're offline.
>
> [ Scan a mailbox QR ]
>
> Connected to: Bob's mailbox  ✓
>   23 messages waiting → pull now

**Be a mailbox**

> Hold encrypted messages for your community. You can't read them.
>
> [ ⃝ Running ]
>
> Holding 1.2 MB for 4 peers.
>
> [ Show QR for peers to scan ]
> [ Stop ]

There are **no other settings**. No retention knob. No storage cap
knob. No allowed-senders knob. No per-peer drill-down. No "advanced".
Defaults are baked in:

- 50 MB hard cap per host. Old messages drop oldest-first when full.
- 14-day retention. Older messages auto-expire.
- Community-only senders (trust-graph members of the host).

Power users who need different numbers can change them in a future
release; v1 is opinionated.

## Crypto contract

The mailbox host CAN'T READ stored messages.

```
sender                             mailbox host                    recipient
  |                                     |                                |
  | seal(MessageEnvelope, toX25519) ──► |                                |
  |     (MailboxEnvelope)               |                                |
  |                                     | hold encrypted blob            |
  |                                     |                                |
  |                                     | ◄──── Pull (over Noise) ────── |
  |                                     | ──── encrypted blobs ────────► |
  |                                                                       |
  |                                                            unseal + verify
  |                                                            ingest normally
```

- Outer wrap: `crypto_box_seal(MessageEnvelope, recipient_X25519_pub)`.
  Sender doesn't need to share a session with the recipient; sealed-box
  is anonymous-sender X25519.
- Inner: the existing `MessageEnvelope` (signed CBOR). On the recipient
  side this verifies+ingests exactly like a direct-delivered message.
- Mailbox host stores the sealed blob keyed by `recipientPub`. They
  see metadata (who→who, when, sizes) but never plaintext.

That's the only crypto rule for v1.

## Wire (over the existing Noise transport)

Four frames. Mailbox protocol piggybacks on the BLE / Tor / future-LAN
transport already in place — no new networking work.

```
MailboxEnvelope  (CBOR, signed by fromPub)
  { id, toPub, fromPub, ciphertext, createdAt, signature }

Push  [tag=10, MailboxEnvelope]      sender → host
Pull  [tag=11, sinceCursor?]         recipient → host
Resp  [tag=12, [MailboxEnvelope], maxCursor]   host → recipient
Ack   [tag=13, [envelopeId...]]      recipient → host  (delete-ok)
```

Authentication: the Noise XX session already binds the peer to a known
pubkey. The host serves Pull results for *the Noise peer's pub*, never
for an attacker-supplied target. The host accepts Push from any
trust-graph peer (signature check + trust-edge lookup).

## Setup flow

The whole flow is one scan per role.

**You want to use someone else's mailbox** (recipient/sender side):

1. Tap **Use a mailbox**.
2. Scan the host's QR.
3. Done. Your device:
   - Adds a `MailboxBinding` cert to its local DB.
   - From now on, sends a sealed copy of every outbound message to the
     mailbox in parallel with direct delivery.
   - On every app launch, pulls from the mailbox.
   - Auto-broadcasts the binding cert to your trust-graph peers via
     the existing sync engine — so they know to push messages destined
     for YOU to your mailbox too.

**You want to be a mailbox** (host side):

1. Tap **Be a mailbox**.
2. Read the one-paragraph warning. Toggle Running on.
3. Show the QR. Peers scan to add you.
4. The screen now shows current storage usage. That's all.

## Implementation order

Each phase = one commit.

**Phase 1 — schema + sealed-box codec**

- Schema v9: `mailbox_binding`, `mailbox_stored`, `mailbox_stats`.
- `MailboxEnvelope` CBOR codec + sealed-box wrap/unwrap.
- Unit tests for round-trip.

**Phase 2 — host service**

- `MailboxHost` runs alongside `MessageSyncService` when the toggle
  is on. Push/Pull/Ack handlers. Hourly retention sweep + cap
  enforcement.
- New `MailboxWire` codec.

**Phase 3 — client outbound + auto-advertisement**

- After a successful direct delivery OR after N seconds of direct
  failure, sender wraps the envelope as sealed-box and pushes it to
  the recipient's known mailbox(es).
- `MailboxBinding` certs sync between peers like trust certs — so
  Alice's mailbox is known automatically to Bob without him scanning
  it.

**Phase 4 — client inbound**

- On app launch + every N minutes while open, pull from MY mailbox
  and ingest.

**Phase 5 — UI**

- Two home tiles.
- "Be a mailbox" screen with the storage number + QR + Stop.
- "Use a mailbox" screen with the mailbox name + pull-now button.

## Open questions for later

- Multiple mailboxes per user for redundancy.
- Mailbox reputation / trust-graph signals about misbehaving hosts.
- Group-message support via mailboxes (currently direct-only).
- Foreground-service / battery-saver story for phone-mode hosts.
