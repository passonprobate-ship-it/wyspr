# Monero wallet — research + plan

**Status:** drafted 2026-05-22 after Sprint 1–3 of TOR-ACROSS-WEB shipped. **Sprint W1 blocked on toolchain upgrade — see "Blocked: Kotlin 2.1 toolchain bump" below.**
**Problem being solved:** Wyspr's `core/currency` module ("Gem")
implements a working signed-ledger account-balance system, but Gem
has no real-world value and every transfer is plaintext on the
community sync wire — sender, recipient, and amount visible to any
community member. Users have repeatedly asked for two things:

1. **Real-value private payments** so Wyspr is useful as money,
   not just a community-internal scoring mechanic.
2. **"Auto-send to a paired peer"** so the user never sees a
   payment address — the trust-graph friendly name is the only
   identifier they ever interact with.

This doc explains why Monero (XMR) is the right backing chain, what
the integration looks like, and the sprint plan to ship a usable
in-app wallet.

---

## TL;DR

**Integrate Monero via [`mollyim/monero-wallet-sdk`][mollyim-sdk] and
bind each paired peer's subaddress onto the trust edge.** The user
sees "Send XMR to Alice" — never an address string. Subaddress
rotation is per-relationship so Alice can revoke one relationship's
payment trail without affecting others. Gem stays in the codebase for
community-internal payments (tipping, splitting bills) but Monero
becomes the headline cross-internet value transfer.

Three sprints:

1. **Sprint W1 — Wallet bring-up**: drop `mollyim/monero-wallet-sdk` in,
   wire a `MoneroWalletService` singleton, add a basic Wallet screen
   showing balance + receive-address. Stub send flow.
2. **Sprint W2 — Peer binding + auto-send**: schema bump for
   `peer_monero_address`, handshake QR v3 with optional XMR address,
   bind/rotate APIs, "Send XMR" button on the conversation peer-detail
   sheet.
3. **Sprint W3 — Polish**: foreground-service sync, biometric send
   confirm, restore-from-seed flow, fee tier picker, transaction
   history.

Total cost estimate: 1.5–2 weeks of focused work.

---

## Why Monero and not ARRR

Researched both. Two finalists, one clear winner for our use case.

### Monero ([`mollyim/monero-wallet-sdk`][mollyim-sdk])

- **Modern Kotlin SDK**, ~6.5 MB AAR, sandboxed native code in a
  zero-privilege isolated Android process. Suspend / Flow API.
  Jetpack Compose demo wallet maintained by the SDK author.
- **Subaddresses**: per-relationship one-time-receive addresses are
  a first-class Monero primitive. Maps cleanly to "give Bob a unique
  address only he uses" without on-chain linkability between Bob's
  address and Carol's.
- **Liquidity**: ~$3–5B market cap. Plenty of exchanges still list
  it; users who want to acquire some XMR can.
- **Privacy**: RingCT + stealth + ring signatures — the gold standard
  for fungible private money in production for years.
- **Concern**: ~6.5 MB native dependency, increases APK size and
  attack surface (mitigated by SDK's sandboxed-process design).

### Pirate Chain ([`PirateNetwork/pirate-android-wallet-sdk`][arrr-sdk])

- **Stronger default privacy**: 100% shielded zk-SNARKs, no
  transparent path. Stricter than Monero.
- **Liquidity**: ~$10M market cap, smaller exchange footprint.
  Practical concern: users may struggle to acquire ARRR.
- **SDK maturity**: less polished than Monero's. Built on the Zcash
  Android SDK which is fine but has fewer reference apps.
- **Trusted setup**: inherits Zcash's original ceremony concern.
  Small but exists.

### Decision

**Monero wins on the practical axis.** "Out of the box useable"
means users can actually obtain and spend the coin. ARRR's
stronger-by-default privacy is admirable but loses on ecosystem
maturity. Gem (our community-internal coin) stays for
community-internal stuff where mainstream privacy isn't the goal.

A future sprint can add ARRR alongside if a community asks for it —
the binding architecture below is chain-agnostic.

---

## Architecture

```
┌─────────────────────────────────────┐
│ feature/wallet (NEW)                │
│  ├─ WalletScreen (balance + tabs)   │
│  ├─ WalletViewModel                 │
│  ├─ SendXmrSheet                    │
│  └─ ReceiveAddressCard              │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│ core:wallet (NEW)                   │
│  ├─ MoneroWalletService             │
│  │   wraps mollyim/monero-wallet-sdk│
│  ├─ PaymentAddressService           │
│  │   manages peer_payment_address   │
│  └─ NetworkConfig                   │
│      mainnet/stagenet, default nodes│
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│ core:database                       │
│  + peer_payment_address (schema v15)│
│  ALSO trust_edge unchanged          │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ core/trust HandshakeQr v3           │
│  + optional moneroSubaddress field  │
└─────────────────────────────────────┘
```

### Why a new module instead of extending `core:currency`

`core:currency` is account-balance with our ed25519 identities as
the keys; Monero is UTXO-based with separate spend/view keys. Forcing
both into one module would couple unrelated abstractions. Two modules
side-by-side, one UI surface, is cleaner.

`core:currency` stays. Future use cases: community tipping,
chore-credits, splitting a dinner among 5 paired peers without going
on-chain.

---

## Sprint W1 — Wallet bring-up

**Goal:** real Monero wallet running inside Wyspr, syncing with
the network, balance visible in the UI. No send flow yet, no peer
binding. Just "the app holds a wallet, it knows the balance, it
shows a receive address."

### Changes

- New module `core/wallet/` (Kotlin, Hilt-aware) wrapping
  [mollyim/monero-wallet-sdk][mollyim-sdk].
- `MoneroWalletService @Singleton`:
    - `create(seedWords: List<String>?)` — restore-from-seed or
      generate-new. Stores wallet bytes encrypted on-disk via the
      same keystore subkey pattern as `WysprDatabaseImpl`.
    - `state: StateFlow<WalletState>` — Locked / Synced(height,
      balance, unlockedBalance) / Syncing(percent) / Failed(msg).
    - `primaryAddress: StateFlow<String?>` — the main wallet address
      (subaddress 0/0). Used as the universal "receive XMR" address
      when no peer-specific one exists.
    - `recentTransactions: StateFlow<List<Transaction>>`.
- New `feature/wallet/` module:
    - `WalletScreen` — balance, primary receive address (QR + text),
      sync state, recent txs list. Routed under a new bottom-nav
      tab.
    - `WalletViewModel` injects `MoneroWalletService`.
- Bottom nav update: 4 tabs — Chats / Community / **Wallet** /
  Settings.
- Foreground service hook: `TransportForegroundService` gains a
  ref-count slot for "wallet syncing" so the daemon survives
  backgrounding during the initial sync (which can take minutes
  on first run).
- `network` config defaults to mainnet with mollyim's default light-
  wallet servers. Optional override in Settings.

### Schema

No DB changes in W1. The wallet's own keystore + cache file are
managed inside `core/wallet/` private storage.

### Cost

**Small to medium.** ~800–1200 LOC. Most of the work is wiring
mollyim/monero-wallet-sdk's lifecycle correctly into Hilt + the
foreground service, plus building the basic Wallet screen.

### Acceptance

- App boots, opens Wallet tab, prompts to "Create" or "Restore."
- Generate flow: wallet seed phrase displayed once with strong
  warning; user confirms by re-entering N selected words.
- After creation, the balance shows 0.00 XMR.
- Sync progress visible in real time. Once synced, balance reflects
  any test funds sent to the receive address.
- App restart resumes the wallet at last-known height without
  forcing re-sync from genesis.

---

## Sprint W2 — Peer binding + auto-send

**Goal:** the user never sees a Monero address string. They tap
"Send XMR" on a peer's conversation screen, enter an amount, confirm.
The wallet auto-resolves the destination from the peer's bound
subaddress.

### Changes

- **Schema v15**: new `peer_payment_address` table.
    ```sql
    CREATE TABLE peer_payment_address (
        peer_pub      BLOB NOT NULL,
        chain         TEXT NOT NULL,  -- "monero" for now; "arrr" / "btc-lightning" forward-compat
        address       TEXT NOT NULL,  -- chain-specific encoded address
        created_at    INTEGER NOT NULL,
        revoked_at    INTEGER,        -- null = active
        notes         TEXT,           -- optional, local-only
        PRIMARY KEY (peer_pub, chain, address)
    );
    ```
  Composite key allows multiple addresses per peer per chain
  (rotation history). A peer's "current" address is the newest
  non-revoked row.

- **HandshakeQr v3**: extend [`HandshakeQrCodec`][hsqrcodec] with an
  optional `paymentAddresses: Map<String, String>` field (chain →
  address). Backwards-compatible with v1 + v2 QRs.

- **Handshake protocol**: at the same point we capture `peerOnion`
  in v2, also capture `peer_payment_address` for `chain = "monero"`
  if the QR carries it. Wire it via `TrustEdgeDao` partner table
  insert during handshake completion.

- **PaymentAddressService** (in `core:wallet`):
    - `currentForPeer(peerPub, chain): String?` — most recent
      non-revoked address.
    - `addForPeer(peerPub, chain, address)` — explicit bind (UI
      paste path or scan-extra-QR).
    - `revoke(peerPub, chain, address)` — soft delete.
    - Reactive `Flow` variant for UI subscription.

- **MoneroWalletService extensions**:
    - `mintSubaddress(peerPub: PublicKey): String` — creates a fresh
      Monero subaddress (account 0, next subaddress index), labels
      it locally with the peer's fingerprint for diagnostics, returns
      the encoded address. Called from the handshake completion
      handler so each new pairing gets a fresh subaddress.
    - `sendTo(peerPub, amountAtomicUnits, priority)` — calls
      `PaymentAddressService.currentForPeer(peerPub, "monero")`,
      builds and submits the transaction, returns a `TxId` once it
      enters the mempool.

- **UI: Conversation peer-detail sheet** gets a "Send XMR" entry:
    - Amount entry (XMR with atomic-unit precision under the hood).
    - Fee tier selector (slow / normal / fast).
    - Biometric confirm (uses existing `BiometricGate`).
    - Reports tx hash + first-confirmation time.

- **Settings**: "Pair-time payment address" toggle. On by default —
  meaning new pairings automatically exchange subaddresses. Users
  who want manual control can turn it off and bind addresses later
  via the peer-detail sheet.

### Cost

**Medium.** ~1500–2000 LOC. The handshake protocol bump (v3 QR
codec + migration of in-flight v2 handshakes) is the trickiest
part; everything else is straightforward.

### Acceptance

- Two paired peers exchange Monero subaddresses automatically during
  a fresh handshake.
- The conversation peer-detail sheet shows a "Send XMR" button when
  a payment address is bound.
- Tapping "Send 0.1 XMR" with biometric confirm submits a real tx
  to the Monero network and the recipient sees it in their Wallet
  tab within ~2 minutes.
- The recipient never copy-pastes an address.
- A rotated subaddress on Alice's side is picked up by Bob's side
  on the next sync round; subsequent sends use the new address
  without user intervention.

---

## Sprint W3 — Polish

**Goal:** make it production-grade. Restore flow, fee picker,
transaction history with status, foreground service lifecycle,
seed-phrase backup reminders.

### Changes

- **Restore from seed**: 25-word Monero mnemonic entry screen,
  validated client-side, resumes the wallet from chain genesis or a
  user-supplied restore height.
- **Seed backup reminder**: persistent badge on the Wallet tab if
  the user hasn't confirmed they wrote the seed down. Tap → display
  seed phrase behind biometric gate.
- **Fee tier picker**: estimate slow/normal/fast at send time; show
  resulting confirmation-time estimate.
- **Transaction history**: full list with timestamps, amounts, peer
  (resolved from `peer_payment_address` reverse-lookup where
  possible), confirmation count, status (pending / confirmed /
  failed).
- **Foreground service lifecycle**: wallet sync continues for a
  bounded budget (~5 min) after the app is backgrounded; resumes on
  resume. Long-tail sync from a long-offline state is gated behind
  a user-initiated "Sync now" so we don't burn battery
  involuntarily.
- **Address book**: aggregate view of `(peer_payment_address)`
  rows for non-paired payment recipients (someone sends you their
  address via an out-of-band channel).
- **Light-wallet server picker**: Settings entry to override the
  default node. Pre-populated with a few well-known options.

### Cost

**Small-to-medium.** ~800–1200 LOC. Mostly UI + UX polish.

### Acceptance

- Restore-from-seed produces a wallet that finds existing balance.
- Transaction history is accurate against an external explorer.
- Backgrounding the app while a tx is in-flight doesn't lose it.
- A user can pay an arbitrary external XMR address (not a paired
  peer) via the address book + send sheet.

---

## Open questions

- **Light wallet server choice.** mollyim's SDK uses a remote light-
  wallet server by default. That server sees view-only data
  (subaddress reuse patterns, sync timings). Acceptable for the
  default UX; advanced users get the node override in Sprint W3.
  Long-term we could run our own light-wallet server, but that's
  meaningful infra. Defer.
- **Fee budget on first-pair auto-send.** If two peers can
  auto-resolve and auto-send, does the app prompt before every send
  or just biometric-confirm? Recommend biometric-confirm always —
  spending money should never be a single-tap action even with a
  paired peer.
- **Recovery if the device is lost.** Same as any non-custodial
  wallet: seed phrase. Backup reminder UX is the only meaningful
  mitigation.
- **Regulatory surface.** Monero is delisted from several major
  exchanges. Users acquire it via Kraken (some regions), KuCoin,
  DEXes, atomic swaps. Document but don't editorialize — the user's
  choice of money is their business.

---

## Recommended order of operations

1. **Sprint W1** as a single focused build session. End state: app
   has a Wallet tab with real Monero balance + receive address.
2. **Sprint W2** as a second focused session. End state: paired
   peers can send each other XMR without ever seeing an address
   string.
3. **Hardware-verify** with two phones + small real XMR amounts.
4. **Sprint W3** as polish.

If only one sprint lands: W1 + W2 together delivers the headline
feature ("auto-send to a paired peer"). W3 is polish on top.

---

## Blocked: Kotlin 2.1 toolchain bump

**Discovered 2026-05-22 during W1 bring-up.** `im.molly:monero-wallet-sdk:1.0.0`
declares `org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.1.0}` as a
hard dependency. Wyspr is pinned at Kotlin 1.9.22. The 1.9
compiler cannot read 2.1-built artifact metadata — compile fails as
soon as we add the dep.

The pre-existing scaffolding in `feature/monero-wallet` was
deferring crypto-engine binding to a future Monerujo JNI integration
(v0.7.0b in the kdoc). That route avoids the Kotlin version issue
but requires vendoring monerujo's native build outputs from
[`m2049r/xmrwallet`][monerujo] (~2-3 days of native build setup +
JNI wrapping). Realistic option but much heavier than mollyim.

Realistic options to unblock:

### Option A: Kotlin 2.1 toolchain bump (~half-day to one-day sprint)

Bump in `gradle/libs.versions.toml`:
- `kotlin = "1.9.22"` → `"2.1.0"`
- `ksp = "1.9.22-1.0.18"` → matching 2.1.x release
- `compose-compiler = "1.5.10"` → drop entirely; replace with the
  new `org.jetbrains.kotlin.plugin.compose` Gradle plugin (Kotlin
  2.0+ folded Compose compiler into a first-party plugin)
- `kotlin-test = "1.9.22"` → `"2.1.0"`

Risks:
- **`allWarningsAsErrors = true`** is set on most modules. Kotlin
  2.1 may surface new warnings (deprecations of `kotlin.Any?` smart-
  casts, etc.). Expect to chase a handful of warning-fixes.
- **kmp-tor 2.0.0**: CLAUDE.md notes "every kmp-tor release after
  2.0.0 is built against Kotlin 2.1+ whose stdlib metadata the 1.9
  compiler cannot read." Forward compat (2.1 compiler reading 2.0.0
  metadata) should be fine — but verify on first build.
- **Compose compiler plugin**: the move from per-module
  `composeOptions { kotlinCompilerExtensionVersion = "..." }` to a
  Gradle plugin is mechanical but touches every Compose-enabled
  module.

After the bump, the W1–W3 plan above lands cleanly. mollyim's SDK
slots into `feature/monero-wallet` replacing
`NotImplementedMoneroCryptoEngine`; the rest of the plan stays.

### Option B: Vendor Monerujo JNI (~2-3 days)

Original v0.7.0a plan. Vendor monerujo's prebuilt native libraries
([`m2049r/xmrwallet`][monerujo] under `external/`) for arm64-v8a +
armeabi-v7a + x86_64. Write the Kotlin JNI wrapper that implements
`MoneroCryptoEngine`. No Kotlin upgrade needed.

Heavier, but stays inside the toolchain envelope. The downside is
we then own the monerujo native-binding maintenance ourselves —
every Monero release that requires a libwallet2 rebuild becomes
work for us.

### Option C: Defer the on-device wallet, ship remote-RPC connectivity now

The existing v0.7.0a scaffolding (`MoneroWalletService.refreshNodeInfo`)
works and shows chain tip + node connectivity over Tor. Ship the UI
with "Wallet — connecting to node, balance coming soon" and start
the schema/handshake work (Sprint W2 below) without the actual
wallet engine.

Lets the auto-send-to-paired-peer UX work be done in parallel with
the engine choice. When either A or B lands, the engine slots in
under the already-wired surface.

### Recommendation

**Option A**, on the first session that has time to validate the
toolchain bump across the full module graph. The current state of
the project — Kotlin 1.9 — is going to need to be bumped eventually
anyway (kmp-tor 2.0.0 is increasingly behind, Compose libs all
target Kotlin 2.0+ now, Hilt 2.50 is showing its age). Adopting
mollyim is the forcing function that makes us do the bump now
rather than 6 months from now when something else forces it.

[mollyim-sdk]: https://github.com/mollyim/monero-wallet-sdk
[arrr-sdk]: https://github.com/piratenetwork/pirate-android-wallet-sdk/
[hsqrcodec]: ../core/trust/src/main/java/com/wyspr/core/trust/HandshakeQrCodec.kt
[monerujo]: https://github.com/m2049r/xmrwallet
