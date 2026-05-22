# Tor across the open web — research + plan

**Status:** plan, drafted 2026-05-21 from three parallel research streams.
**Problem being solved:** the Tor leg between two paired Keystone peers who
are not on the same WiFi takes 30–60s on cold start and ~30s on first
peer-dial, and the auto-sync loop wastes most of that work by rebuilding
the circuit + redoing Noise XX every 8 seconds.

This doc is the bedtime read. Three research streams ran in parallel:

1. Architecture survey of comparable apps (Briar, SimpleX, Session, Cwtch)
2. Tor-on-Android tuning options
3. Non-Tor alternative transports (WebRTC, Reticulum, Yggdrasil, Nym,
   queue relays, mailbox-as-relay, WebSocket-over-Tor)

The conclusions converged. This is the most surprising — and the most
useful — outcome.

---

## TL;DR

**Don't abandon Tor. Stop misusing it, then turn the existing on-phone
mailbox into the cross-internet default by allowing multiple hosts per
community and hardening on-phone reliability, then upgrade to a
long-lived WebSocket subscription on top.** None of these require new
crypto, new wire protocols, new infrastructure, or any server outside
the trust graph. All are increments on code already in
`feature/messaging/mailbox/` and `app/transport/`.

In one diagram (asterisks mark what already exists):

```
peers same WiFi               → BLE *
peers across internet, paired → Tor direct dial *  (current: 30s/round)
                                         ↓
                              Tor direct dial *  (sprint 1: amortize Noise across rounds → ~1s/round)
                                         ↓
                              Tor → community mailbox at .onion  (sprint 2: works when receiver offline)
                                         ↓
                              WebSocket-over-Tor → mailbox (sprint 3: sub-second messaging)
                                         ↓
                              Queue-rotated mailbox (sprint 4: closes social-graph leak)
```

The single most important architectural insight from the survey:
**every comparable app keeps the cryptographic tunnel alive for the
contact-lifetime, not the round-lifetime.** Keystone's
`MessageSyncService.runOnce` currently does the opposite. Fixing that
one thing is the largest single-PR win available.

---

## What other projects converged on

Four data points (Briar, SimpleX, Session, Cwtch) with very different
threat models nevertheless landed on a near-identical structural shape
for cross-internet messaging:

1. **Long-lived sessions, never per-round.** Briar's BTP, Session's
   path persistence, Cwtch's HSv3 introductions — none of them rebuild
   the cryptographic tunnel per message. Keystone is the outlier here,
   and the cost is exactly the latency the user is complaining about.

2. **"Messages live at the recipient,"** in some form: Session's
   storage-node swarm, SimpleX's SMP queue relay, Cwtch's untrusted
   group server, Briar's optional self-hosted mailbox. The
   architectural answer to "what about when the peer is offline" is
   always "park the ciphertext somewhere the recipient can fetch
   later." Keystone already has this primitive (`MailboxEnvelope` +
   `MailboxHost`), it's just not yet the default cross-internet path.

3. **Decouple identity from address.** SimpleX rotates queue IDs;
   Cwtch lets you rotate HSv3 keys. Keystone pins `peerOnion` per
   `TrustEdge` at handshake and never rotates. A medium-term hardening
   target (see Sprint 4 below).

4. **Polling beats poking on mobile.** Every project accepts that
   battery + radio + NAT make "push from peer" impractical. Schedule
   pulls with a cadence that tightens when the app is in focus.
   Keystone's current 8-second auto-sync is roughly the right
   foreground cadence but the wrong background one.

These four patterns map directly onto Keystone code paths and dictate
the sprint plan that follows.

---

## What NOT to do (and why)

The non-Tor research stream evaluated nine alternatives and ruled out
six. Documenting the rule-outs because they're tempting:

- **WebRTC + STUN/TURN** — TURN sees both endpoints' real IPs. ~5 MB of
  native deps. Bandwidth-hungry. **Violates the operator-metadata
  requirement directly.** Bottom of the list despite the latency
  appeal.
- **Plain HTTPS to a federated relay (Nostr-shaped)** — the relay
  sees the full social graph mapped to long-term Keystone pubkeys.
  This is the worst metadata profile in the option set; only
  acceptable if accessed over Tor, at which point it collapses into
  the mailbox-at-onion design.
- **Yggdrasil / CJDNS** — IPv6 overlay; intermediate routers see
  source + destination cleartext. Downgrade vs. `.onion`. Plus the
  VPN-slot exclusivity (collides with users who already run a system
  VPN) is a UX dealbreaker.
- **Nym mixnet** — genuinely stronger anonymity than Tor, but the
  fixed-rate cover-traffic that makes Nym what it is is incompatible
  with "messaging app, phone in pocket, doze mode active." Battery
  non-starter on mobile today.
- **Reticulum over the internet** — Reticulum's design centre is
  low-bandwidth radio; running it over TCP looks like Tor without the
  obfuscation. Keep the stub for the LoRa future, don't build it now.
- **I2P / Matrix / Status (Waku) / libp2p** — variously: too slow on
  mobile, federated-but-metadata-leaky, project flux, or just
  protocol toolkits we'd have to reimplement Keystone on top of.

The three options that survived (mailbox-at-onion, WebSocket-over-Tor,
queue rotation) are all evolutions of code already in the tree.

---

## Sprint 1: amortize Noise XX across rounds (the highest-leverage fix)

**Goal:** collapse per-round Tor latency from ~30s to ~1s by keeping
the cryptographic tunnel alive between rounds. No new infrastructure,
no new dependencies, no new wire protocol.

### The actual bug

`MessageSyncService.runOnce` (line 271) currently runs

```kotlin
try {
    val link = transports.dial(...)             // ~30s cold, ~3s warm
    val noise = NoiseSession(...)
    val engine = MessageSyncEngine(noise, link, ...)
    engine.run()                                 // ~200ms of useful work
} finally {
    link.close()                                 // tears down the TCP socket
}
```

Then 8 seconds later, the auto-sync loop runs it again. The Tor
circuit may still be warm (Tor caches them for 10 min) but the
end-to-end TCP socket and the Noise session are gone. We pay the
descriptor fetch + rendezvous + Noise XX (3 messages each direction
= 6 round trips) every round.

### The fix

A `peerOnion → (TorLink, NoiseSession)` cache at `TransportSelector`
level. On `runOnce`:

1. Look up an existing session for `peerPub`.
2. If found and the link is still alive: send a frame, get a frame,
   ack, done. **Sub-second on a warm circuit.**
3. If not found: build a circuit + Noise XX + cache. Same cost as
   today but only paid once per ~10 minutes.
4. Send a keepalive frame every 60s on the Noise session so a NAT'd
   peer doesn't silently drop the stream.

The Noise XX session is already symmetric/stateful in transport state
— this is exactly what it's for. We've just been throwing it away
between rounds.

### Files affected

| File | Change |
|---|---|
| `app/transport/TransportSelector.kt` | Add `liveLinks: Map<PeerKey, LiveSession>` cache. Race "pool first, then dial" instead of "dial always." |
| `feature/messaging/sync/MessageSyncService.kt` | Don't `link.close()` in `finally` on success. Return to the pool. Distinct teardown path for failure. |
| `feature/messaging/sync/MessageSyncEngine.kt` | Accept a pre-built `NoiseSession` in transport state. Today it builds + handshakes; refactor to optional. |
| `core/crypto/.../NoiseSessionImpl.kt` | Currently one-shot; add a `keepAlive(payload: ByteArray = ZERO_BYTES)` method that exchanges a no-op frame. |

### Cost

**Small.** Probably 300–500 LOC across four files. No schema changes.
No protocol bump (the keepalive frame can be a 0-length plaintext —
receiver ignores). Backwards-compatible with a peer that hasn't
deployed this change yet: that peer just continues to see fresh
Noise XX handshakes from our side, which works today.

### Risk

The reusable-session shape needs careful liveness detection. A peer
that dies mid-session leaves us with a stale TCP socket. Mitigation:
the keepalive doubles as a liveness probe — if it doesn't ack within
~5s, evict the session and rebuild. Worst case: one stalled round
before we notice; same behaviour as today.

### Expected outcome

- Cold-start first round: ~30s (unchanged)
- Subsequent rounds within ~10min: ~200ms–1s (currently ~30s)
- Battery: less Tor + Noise work per round → better

---

## Sprint 2: on-phone mailbox as the cross-internet default

**Goal:** make the cross-internet default "push to mailbox" rather
than "dial peer directly." Solves the "both have to be online at the
same moment" problem that no amount of Tor optimization fixes.

**No new server. No headless build. No infrastructure outside the
trust graph.** The existing on-phone `MailboxHost` already does the
work — we just need to (a) allow multiple hosts per community for
redundancy and (b) harden on-phone reachability so a dedicated
spare phone is a credible always-up mailbox.

### Why on-phone is enough

The original framing assumed "phone in a pocket" was the only
deployment shape, and concluded we needed a server. Wrong shape.
The deployment shape that works is **a spare Android phone on a
charger, on home WiFi, with battery-optimization disabled and
Doze whitelisted**. This is exactly the [Briar Mailbox companion
app](https://briarproject.org/manual/#briar-mailbox) model — they
recommend a dedicated old phone and it works.

Reachability isn't binary, it's probabilistic. One spare phone at
~95% reachable is fine for asynchronous messaging. Five community
members each at 60% reachable approaches 99% — and we can ride
that with multi-host bindings.

### The shape

v0.8.0 mailbox already exists. The pieces:

- `MailboxEnvelope` (sealed-box ciphertext + addressing)
- `MailboxBinding` (cert: "I, Alice, use H as my mailbox")
- `MailboxHost` (the server side; serves Push/Pull/Ack frames over
  Noise on the existing transport)
- `MailboxSettings` (the per-device "I am a mailbox" toggle)

What we add:

1. **Reliability hardening on the host phone** so a phone-as-server
   is a credible always-up endpoint.
2. **Multiple bindings per owner** so a recipient lists 2–N mailbox
   hosts; senders try them all, recipient pulls from whichever is
   reachable.
3. **Senders push to all known hosts** (best-effort replication) so
   that any reachable host has a copy when the recipient pulls.

### Why this works

- **Same crypto.** Sealed-box envelope unchanged. Ed25519 signatures
  unchanged. Noise XX over Tor to the host unchanged. Wire protocol
  in `MailboxWire.kt` unchanged.
- **Same trust model.** Host sees `(fromPub, toPub, ciphertext)` —
  exactly what `MailboxHost.handlePush` already exposes. No new
  metadata surface beyond what v0.8.0 already accepted.
- **Solves the offline-peer problem.** Sender pushes to host even
  when receiver is offline. Receiver pulls when next online.
- **Solves the host-flake problem** (multi-host). One mailbox phone
  reboots, runs out of battery, or has its Tor circuit drop — the
  other hosts still have the ciphertext.
- **Zero new infrastructure.** No VPS, no RPi, no Linux build, no
  JVM Tor port. Every device in the system is a phone running the
  same APK.

### On-phone reliability hardening

The work that turns "a phone" into "a credible mailbox host":

| Concern | Fix |
|---|---|
| Battery optimization kills the foreground service | First-launch prompt for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` when `MailboxSettings.isHost = true`; reprompt if revoked. |
| Phone reboots → mailbox offline until user opens app | `BOOT_COMPLETED` broadcast receiver that starts `TransportForegroundService` in mailbox-host mode. |
| OEM background killers (Samsung, Xiaomi, OPPO) | Detect manufacturer; on first launch in host mode, deep-link to the OEM's "don't kill this app" settings page with copy explaining why. [`dontkillmyapp.com`](https://dontkillmyapp.com) has the per-OEM links. |
| Tor daemon dies | Restart on bootstrap failure with exponential backoff; surface state in the host's mailbox UI. |
| Storage fills up | Cap already exists in v0.8.0; expose used / available in host UI. |
| User doesn't realize their phone is acting as a mailbox | Persistent notification when in host mode, clear UI surface in Settings. |

### Multi-host bindings

Today: `MailboxBinding` is one cert per owner ("I, Alice, use H").
Change to: an owner can publish N bindings, one per host. Senders
treat them as a set and push to all reachable. Recipient pulls from
whichever they can reach.

Two viable shapes — pick at coding time:

- **Multiple bindings per owner.** Schema and cert format unchanged;
  the index just stops being unique-on-owner. Sender iterates.
  Simplest.
- **Single binding with a list of hosts.** Cert format gains a
  `hosts: [{pub, onion}]` field. One cert to sign, one cert to
  ingest. Cleaner UX but is a protocol bump.

Recommend the first — no protocol bump, no schema migration, gracefully
backwards-compatible with v0.8.x peers (they see one of the bindings,
which is degraded but correct behaviour).

### Files affected

| File | Change |
|---|---|
| `feature/messaging/mailbox/MailboxSettings.kt` | Add `isHost` flow + first-host-launch prompts for battery + OEM killer settings. |
| New: `app/.../mailbox/MailboxBootReceiver.kt` | `BOOT_COMPLETED` receiver that re-starts the foreground service in host mode if `MailboxSettings.isHost`. |
| New: `feature/messaging/mailbox/HostReliability.kt` | OEM detection + deep-link helpers (Samsung, Xiaomi, OPPO, Huawei, Vivo). |
| `feature/messaging/mailbox/MailboxBindingDao.kt` | Drop unique-on-`ownerPub` constraint; allow N bindings per owner. Migration. |
| `feature/messaging/mailbox/MailboxClient.kt` | When pushing for a recipient, iterate all known bindings; push to each reachable host. When pulling, iterate own bindings; pull from whichever responds. |
| `app/.../ui/mailbox/HostStatusScreen.kt` | Storage used, Tor state, restart-on-failure counter, "kill me" warnings if optimizations not exempt. |

### What's NEW vs. what's reused

| Component | Status |
|---|---|
| Sealed envelope codec | ✅ exists |
| Wire format | ✅ exists |
| Storage cap + eviction | ✅ exists |
| Pull cursor (replay defence) | ✅ exists (v0.8.3) |
| Binding cert ingest with owner check | ✅ exists (v0.8.3 critical fix) |
| On-phone foreground service for mailbox | ✅ exists (extend `TransportForegroundService`) |
| On-phone embedded Tor | ✅ exists (v0.6 work) |
| Battery-opt exemption prompt | ⚠ new |
| Boot-time auto-start receiver | ⚠ new |
| OEM-killer deep-links | ⚠ new |
| Multi-host bindings | ⚠ new (drop unique constraint + client iteration) |
| Host status UI | ⚠ new |

### Cost

**Small.** ~300–500 LOC of on-phone reliability + multi-host client
iteration. No new Gradle subproject, no kmp-tor JVM port, no Linux
build. Documentation is a "set up your spare phone as a mailbox"
guide — a screen of text.

### The trust question

A single hostile mailbox host builds a social graph of every binding
to it (`{ownerPub, mailboxPub}`) and sees every Push (`fromPub`,
`toPub` on the envelope). Multi-host *worsens* this slightly — N
hosts each see a subset of your traffic, and a colluding majority
sees most of it. This is the residual metadata exposure mailboxes
accept. **Sprint 4 (below) addresses it** via per-pair queue rotation.

For now, the assumption is: a mailbox host is a trusted community
member's phone, and the trust is at the community-membership level,
not the per-pair level. This is unchanged from v0.8.0.

### Expected outcome

- "Wife paired with husband; wife's phone in pocket all day" works:
  husband's messages land in the community mailbox; wife pulls them
  when she next opens the app, regardless of whether husband is
  still online.
- A spare Android phone on a charger at the community-builder's house
  is the default mailbox shape. Multiple such phones gives redundancy
  with zero per-host config — just install the APK, toggle "Act as
  mailbox," scan the trust QR.
- Tor cold-start cost amortizes across many cross-peer interactions:
  one dial to a mailbox lets you push to *every* paired peer who
  uses that mailbox.
- Sender doesn't care if recipient is online: the protocol already
  handles "host accepts even if recipient is offline."

### Out-of-scope but parking-lot

A headless Linux/RPi/VPS build of `MailboxHost` remains *possible*
for communities that have such infrastructure and want the
operational simplicity. It is no longer on the critical path. If
ever built, it would extract the Android-Context dependencies in
`MailboxHost.kt` behind interfaces and add a JVM `main()` with
embedded Tor — but only if and when a real community asks for it.

---

## Sprint 3: long-lived WebSocket subscription to the mailbox

**Goal:** sub-second cross-internet messaging once a circuit is warm,
without giving up Tor's metadata properties.

### The shape

Today: receiver pulls from mailbox on the existing 8-second auto-sync
cadence. Even with Sprint 1's session reuse, that's an 8-second
latency floor.

Sprint 3: the mailbox host exposes a WebSocket endpoint at its
`.onion`. Receiver subscribes once per `MailboxBinding`. Mailbox host
pushes "you have N new messages" as they arrive. Receiver issues a
pull only when notified.

### Why this works on mobile despite Doze

Foreground service is already a pattern in Keystone
(`TransportForegroundService`). When the user has the app open (or
something in the trust graph is actively messaging), the WS stays up.
When the user backgrounds the app long enough for the OS to kill the
WS, the existing `WorkManager` periodic pull catches up on next wake.

### Files affected

| File | Change |
|---|---|
| `tools/keystone-mailbox/` (Sprint 2's host) | Add Ktor / OkHttp-server WS endpoint. Per-subscriber identity = ownerPub on the binding. |
| `feature/messaging/mailbox/MailboxClient.kt` (new) | Long-lived WS client over a SOCKS5 dial of the mailbox's `.onion`. |
| `feature/messaging/sync/MessageSyncService.kt` | When a binding exists, prefer the WS subscription over polling. Polling stays as a fallback for after a Doze kill. |
| `TransportForegroundService` | Add a "mailbox subscription active" ref-count slot alongside BLE + Tor radios. |

### Cost

**Small-to-medium.** ~500–800 LOC on top of Sprint 2. OkHttp's
WebSocket client handles the heavy lifting on the Android side.

### Expected outcome

- New-message latency drops from "auto-sync cadence" (8s+) to
  "circuit RTT" (sub-second).
- Battery cost is lower than current 8s polling because the WS is
  idle most of the time vs. polling that spins up the Noise session
  per cadence tick.
- Doze still happens; fallback polling still runs at WorkManager
  cadence (15+ min) when WS is killed.

---

## Sprint 4 (later): per-pair queue rotation

**Goal:** close the "hostile mailbox host builds a social graph" leak.

### The leak today

`MailboxHost.handlePush` and `handlePull` index by `toPub`. A hostile
mailbox host watches `{fromPub, toPub}` on every envelope and builds
a `(sender, recipient, timestamp)` log. Even sealed-box content is
opaque, but the social graph leaks.

### The fix (borrowed from SimpleX)

- Each paired peer pair maintains TWO opaque queue IDs (one per
  direction), gossipped at handshake time and rotatable on demand.
- Mailbox host indexes by queue ID, not by `toPub`.
- Sender knows which queue to push to (the recipient's inbound
  queue for this conversation). Recipient pulls by queue ID.
- Periodic rotation: receivers issue a new queue ID periodically,
  push the new ID to active senders via the existing message flow.

### What this changes in Keystone

- `MailboxEnvelope` gains a `queueId: bytes(16)` field. Replaces or
  augments `toPub` for routing (a host still needs `fromPub` for the
  signature check, but doesn't need `toPub` for routing).
- `MailboxBinding` becomes per-pair instead of per-owner. Or stays
  per-owner but each binding includes a set of pair-specific queue
  IDs.
- Schema bump.
- Wire-protocol bump (host needs the new field; old hosts gracefully
  refuse).

### Cost

**Medium-to-large.** Schema + protocol bump + key rotation logic +
gossip-the-new-queue-id-to-active-peers. Maybe 1500 LOC plus a
careful migration. The hardest part is the rotation rendezvous:
what happens when sender knows queue ID v1 but receiver has rotated
to v2? Solution: receiver listens on a window of queue IDs (current
+ N-back) for a grace period after rotation.

### Why this is sprint 4, not sprint 2

Sprint 4 only matters if the mailbox host is *not fully trusted*.
The community-mailbox host is, by definition, more trusted than a
random SMP relay. Doing this right requires nailing down the
trust assumptions first. And the latency wins from Sprints 1–3 are
larger end-user impact than the queue-rotation hardening.

---

## Parking lot: HSv3 client authorization

Independent of the sprint plan, there's one Tor-side hardening worth
queuing: **HSv3 client authorization**. The HS publishes a list of
authorized x25519 client pubs; only those clients can fetch the
descriptor. An attacker who learns your `.onion` can't even confirm
it's online, let alone connect.

Implementation cost: one new field on `HandshakeQr` v3 (the client-auth
x25519 pub), plus a `ONION_CLIENT_AUTH_ADD` control-port call at
trust-edge commit time. Touches the pairing protocol so it's a QR v3
bump; schedule alongside the next QR migration.

**Strict metadata-reduction win, independent of which transport
sprint we land first.** Not on the critical path but worth listing.

---

## Things I'm uncertain about (worth verifying)

- **Briar's mailbox companion app** — recalled from memory; details
  may have evolved. Confirm with their docs before citing the design
  directly.
- **SimpleX's queue-rotation cadence + recovery story** — described
  in their protocol docs; we'd want to read those carefully before
  designing Sprint 4.
- **kmp-tor's lifecycle behavior on Android process death** — agent
  B was confident the daemon survives, but a real two-device test
  with the phone backgrounded for hours is the only verification
  that matters.
- **WebSocket-over-Tor longevity on mobile** — the BLE foreground-
  service pattern *should* extend, but the first build will reveal
  the truth.
- **OEM background-killer landscape** — Samsung, Xiaomi, OPPO, Vivo,
  Huawei all kill foreground services aggressively despite the
  Android API contract. The on-phone mailbox needs to survive these.
  `dontkillmyapp.com` is the canonical list; whether deep-linking
  into each OEM's settings page is enough, or whether we need a
  per-OEM keepalive trick, is something only field-testing on those
  devices will reveal.

---

## Recommended order of operations

If you've got a few sessions to spend on this:

1. **Tomorrow, half a day:** Sprint 1 (Noise-session reuse). Highest
   leverage, lowest risk, smallest diff. Tests against your existing
   two-device setup once the A02s is awake.
2. **Next session:** Sprint 2 (on-phone mailbox hardening + multi-host
   bindings). The wife-husband case becomes "works whenever either of
   you opens the app," not "works when you both happen to be online
   at the same time." No new infrastructure — a spare phone on a
   charger is the deployment unit.
3. **Sprint 2.5:** Hardware-verify the two-device Tor leg with
   sprints 1+2 applied — and verify a spare phone configured as
   mailbox host actually stays reachable across Doze + reboot +
   OEM-killer attempts on the device families you care about.
   Until this works, Sprint 3 is premature.
4. **Then:** Sprint 3 (WebSocket-over-Tor). Sub-second cross-internet
   messaging.
5. **Later:** Sprint 4 (queue rotation), HSv3 client auth.

If you only do one thing: **Sprint 1.** It's the change that turns
"Tor is too slow" from accurate into "Tor is fine once it's warm,"
without any new infrastructure or protocol surgery.

---

## Open questions for the human

- **How many mailbox hosts per community is the recommended floor?**
  One spare phone on a charger is workable. Two gives genuine
  redundancy. Five+ pushes effective availability past 99% even with
  flaky individual hosts. Recommend the docs/UX nudge toward "any
  community member with a spare Android phone can run one — and the
  more the merrier."
- **Default cross-internet path = direct dial, or mailbox?** I've
  proposed mailbox-first above, with direct dial as fallback. The
  alternative — direct dial first, mailbox as failure fallback — is
  closer to the current code shape but loses the "works when peer
  offline" property. With multi-host bindings, mailbox-first is
  cheap enough that it should be the default.
- **What happens when no mailbox is reachable?** Buffer outbound
  locally and retry later? Surface "peer is offline and no mailbox
  reachable" to the user? The current behaviour (silent retry) is
  probably wrong once mailbox is the default path.

Sleep on it.
