# Wyspr — Private Groups

Multi-party end-to-end encrypted threads on top of the already-shipped
1:1 messaging stack. Same trust graph, same Noise transport, same
sync engine — extended.

This is a design document. Implementation order is at the bottom.

## Design goals (and non-goals)

1. **No new trust assumption.** A group's members are a subset of one
   peer's existing trust graph. You can only add someone to a group
   if you have a trust edge with them.
2. **No new key-management layer.** First version uses
   **sender-encrypted-to-each-member** — every group message is N
   ciphertexts, one per member, encrypted with the same Noise
   session machinery 1:1 messaging already uses. No shared group
   key, no rekey-on-revocation, no MLS-style tree. Slow for large
   groups, simple and verifiably secure for small ones.
3. **Membership is signed.** Every member is in the group by virtue
   of a `GroupMembership` cert signed by the group's creator (v1) or
   by any current member with add-permission (v2+). Mirrors the
   `InvitationCertificate` design.
4. **No third-party storage.** Same as 1:1 — group messages flow
   peer-to-peer through the existing sync engine. No central group
   server, no "group hosted on a peer" pattern.

Non-goals for v1:
- MLS or any tree-based group keying.
- Anonymous senders (every message is signed by `fromPub`).
- Member removal that revokes past-message access (you can't unsend).
- Groups larger than ~20 members. The sender-encrypted-to-each
  fan-out makes this expensive past that.

## Concepts

### GroupId

Opaque 32-byte identifier. Derived deterministically from creation
material so two devices that observe the same creation event compute
the same id:

```
groupId = BLAKE2s-256(
    "WYSPR/v1/group-id"
    || creatorPub      // 32 bytes, Ed25519
    || nameBytes       // canonical UTF-8 of the user-chosen name
    || createdAt       // u64 big-endian seconds
)
```

The name is in the id derivation so renaming the group changes its
identity (we don't support rename in v1). Members keep their own
local nickname for the group separately if they want.

### GroupMembership cert

Signed assertion that `memberPub` is a member of `groupId`. CBOR
canonical encoding, signed by the issuer:

```cddl
GroupMembership = {
    "g":   bstr .size 32,    ; groupId
    "m":   bstr .size 32,    ; memberPub (Ed25519)
    "n":   tstr,             ; group name (denormalised — convenience for receivers)
    "c":   uint,             ; createdAt (group), u64 seconds
    "i":   bstr .size 32,    ; issuerPub (creator in v1; any add-capable member in v2+)
    "t":   uint,             ; issuedAt (cert), u64 seconds
    "x":   bstr .size 64,    ; Ed25519 signature over the above fields,
                             ;   canonical CBOR, omitting "x"
}
```

Verification:
1. Re-canonicalise the cert without `x`, verify the signature.
2. Check that `i == creatorPub` (v1). v2+ will allow any current
   member with add-permission, traced through a cert chain.
3. Check `t >= c` and `t <= now + 5 min` (clock skew tolerance
   already used by `InvitationCertificate`).

A device gets a `GroupMembership` cert one of two ways:
- The creator hands it directly via a 1:1 sync push (the membership
  cert IS the invitation).
- The creator hands a fresh `GroupMembership` for each member;
  receivers verify before storing.

### Local membership view

Each device computes its view of group membership from the certs it
has stored locally. There's no global membership oracle — if peer B
has a cert proving peer C is a member but peer A hasn't received
that cert yet, A doesn't send to C. The sync round (already shipped)
gets membership certs to A on the next round between A and B.

In v1, the group creator is authoritative. A non-creator who has
membership certs for {A, B, C, self} sends every group message to
A, B, C. If a new member is added by the creator, the creator's
next sync round with the existing members hands them the new cert,
and from then on those members include the new one in their
send-fan-out.

## Wire format extensions

### MessageEnvelope (existing)

```cddl
MessageEnvelope = {
    "id":   bstr .size 32,    ; UUIDv4
    "f":    bstr .size 32,    ; fromPub (Ed25519)
    "t":    bstr .size 32,    ; toPub (Ed25519) — direct recipient
    "c":    uint,             ; createdAt seconds
    "b":    tstr,             ; body
    "x":    bstr .size 64,    ; Ed25519 signature
}
```

### GroupMessageEnvelope (new)

```cddl
GroupMessageEnvelope = {
    "id":   bstr .size 32,    ; UUIDv4 — same across all recipients of this group post
    "f":    bstr .size 32,    ; fromPub (Ed25519)
    "g":    bstr .size 32,    ; groupId
    "c":    uint,             ; createdAt seconds
    "b":    tstr,             ; body
    "x":    bstr .size 64,    ; Ed25519 signature over the above fields (canonical CBOR, no "x")
}
```

Key differences:
- No `toPub`. Membership is implied by `groupId`; the SENDER fans
  out one transmission to each member of the group as they currently
  understand membership. The receiver doesn't see `toPub` because
  the transmission was 1:1 to them (the recipient is `ownPub`).
- `id` is the same on every fan-out copy so receivers can dedupe.
- The signature is over the GroupMessageEnvelope without `x`. Same
  canonical-CBOR scheme as `MessageEnvelope`.

### MessageSyncFrame.Push

Already carries `envelopes: [MessageEnvelope]`. Extend to also carry
`groupEnvelopes: [GroupMessageEnvelope]`. The codec is backwards
compatible — old peers ignore the new field, new peers read both.

```cddl
Push = {
    "k":   0,                 ; tag
    "e":   [MessageEnvelope],
    "ge":  [GroupMessageEnvelope] / null,  ; new in v0.8
    "gc":  [GroupMembership]    / null,    ; new — share membership certs
}
```

Sync rounds now also propagate `GroupMembership` certs the local
device holds that the peer doesn't. Similar to revocation propagation
in v0.6.6 — piggybacked on the existing Push frame.

## Schema (v7)

Two new tables. Additive — existing tables untouched.

### `group_entity`

```sql
CREATE TABLE group_entity (
    groupId        BLOB PRIMARY KEY,
    name           TEXT NOT NULL,
    creatorPub     BLOB NOT NULL,
    createdAt      INTEGER NOT NULL,    -- seconds
    -- Local-only nickname the user set for the group; null = use `name`.
    localNickname  TEXT
);
```

### `group_member`

```sql
CREATE TABLE group_member (
    groupId        BLOB NOT NULL,
    memberPub      BLOB NOT NULL,
    -- The signed cert that proves this member belongs. Persisted verbatim
    -- so we can re-send to other peers without re-signing.
    certBytes      BLOB NOT NULL,
    addedAt        INTEGER NOT NULL,    -- cert's t field
    status         TEXT NOT NULL,        -- "active" | "removed"
    PRIMARY KEY (groupId, memberPub),
    FOREIGN KEY (groupId) REFERENCES group_entity(groupId) ON DELETE CASCADE
);

CREATE INDEX idx_group_member_pub ON group_member(memberPub);
```

### `group_message`

Group messages share the existing `message` table layout but include a
`groupId` column. Cleanest is a separate `group_message` table with the
same columns plus `groupId`. The conversation list UNIONs both for the
"all threads" view.

```sql
CREATE TABLE group_message (
    id          BLOB PRIMARY KEY,
    groupId     BLOB NOT NULL,
    fromPub     BLOB NOT NULL,
    body        TEXT NOT NULL,
    createdAt   INTEGER NOT NULL,
    receivedAt  INTEGER,                 -- null for outbound until ack
    status      TEXT NOT NULL,           -- pending|sent|delivered|read
    signature   BLOB NOT NULL,
    FOREIGN KEY (groupId) REFERENCES group_entity(groupId) ON DELETE CASCADE
);

CREATE INDEX idx_group_message_group_time ON group_message(groupId, createdAt);
```

`status` semantics on a group message are per-sender: "delivered" once
the message has been ack'd by AT LEAST ONE recipient; "read" requires
read receipts from every recipient. v1 just shows "sent" for outbound
group messages — full per-recipient receipts are a v2 feature.

## Migration MIGRATION_6_7

```kotlin
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `group_entity` ( ... )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `group_member` ( ... )")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_member_pub ON group_member(memberPub)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `group_message` ( ... )")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_message_group_time ON group_message(groupId, createdAt)")
    }
}
```

Non-destructive. Existing 1:1 messages, trust edges, and contacts are
untouched.

## UI

### Conversation list

The existing list shows 1:1 threads. Add group threads alongside,
sorted by most-recent activity. Each group row:

- Leading: a `FingerprintChip`-style square with the first 2 chars
  of the group's name (or "ɢ" if empty), distinguishable from
  fingerprint chips (different background — `tertiaryContainer`?).
- Title: group name (or local nickname if set).
- Subtitle: `{lastSenderName}: {preview}` — sender name disambiguates
  in a multi-party thread.
- Trailing: last-message time.

### Create-group screen

Reached via a FAB or a "+" action on the conversation list TopAppBar.

1. Name field.
2. Member list — every contact from the trust graph, each with a
   checkbox. Toggle to include.
3. "Create" button — signs the membership certs, persists, sends
   the certs to each member on the next sync round.

### Group conversation screen

Reuses the `ConversationScreen` layout. Differences:
- Title is the group name; subtitle shows `{N} members` (tap to open
  member list).
- Inbound bubbles show the sender's friendly name (fingerprint chip
  as a small leading element on the left margin) since multiple
  people post.
- Composer is identical.

### Member list dialog

Tap "N members" subtitle → modal dialog listing each member with
their fingerprint chip + name. v1 is read-only; v2 adds remove.

## Implementation order

Each step is independently committable.

1. **Schema migration + entities + DAO.** `MIGRATION_6_7`,
   `GroupEntity`, `GroupMemberEntity`, `GroupMessageEntity`,
   `GroupDao`, `GroupMemberDao`, `GroupMessageDao`. Compile + run on
   device; verify existing 1:1 still works (smoke test).

2. **Wire format + codec.** `GroupMessageEnvelope` + `GroupMembership`
   CBOR codecs (alongside the existing `MessageEnvelope` codec).
   Round-trip unit tests.

3. **Sync engine extension.** `Push` frame carries `groupEnvelopes`
   and `gc`. `awaitPushAndAck` ingests group messages and group-
   membership certs. `pushPending` includes group messages addressed
   to the peer (i.e. messages this device sent to a group where the
   peer is a member, and the peer hasn't yet acked).

4. **Create-group flow.** UI + `GroupCreationService` that:
   - generates the `groupId`,
   - signs N `GroupMembership` certs (one per member, including self),
   - persists everything,
   - enqueues the membership certs for the next sync round to each member.

5. **Group conversation screen.** New composable, mirrors
   `ConversationScreen`, sender-aware bubbles.

6. **Conversation list integration.** UNION group threads with
   1:1 threads.

7. **Two-device test.** Create a group of 2 between the S23 and A02s,
   send a message, verify it lands on the other side and renders in
   the new screen.

## Future (v2+)

- Add/remove members by any current member (cert chain).
- Per-recipient delivery + read receipts.
- Group-key model (MLS or epoch-based shared key) for larger groups.
- Group avatars / emoji.
- Group settings (mute, rename, leave).
- Group-bound `.onion` (HSv3 service per group) for offline-async
  delivery via Tor.

## Open questions

1. **Is `creatorPub` reachable forever?** If the creator's device is
   destroyed without revocation, every membership cert chain stays
   verifiable because the certs are signed before the loss. New
   members can't be added by anyone else in v1.
2. **What happens when a non-creator wants to add a member?** v1
   answer: they can't — they ask the creator. v2 introduces
   delegation.
3. **Should we sync group messages BETWEEN non-self members?**
   E.g. C sends to A and B. A and B sync. Does A then forward C's
   message to B if B somehow missed it? v1 no — each member only
   receives messages addressed to them by the sender. v2 could do
   gossip-style anti-entropy on missed messages.
