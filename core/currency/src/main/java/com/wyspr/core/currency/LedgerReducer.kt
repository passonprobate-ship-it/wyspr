package com.wyspr.core.currency

/**
 * Pure reducer over signed currency envelopes. CURRENCY.md §3 / §13.3.
 *
 * Given a stream of [Event]s, computes the local view of:
 *
 *   - per-account balances
 *   - per-account max-applied sequence number
 *   - the slashed set
 *
 * Every method is a pure function — no I/O, no side effects, no implicit
 * time source. Callers (WalletService, SyncEngine) own ordering, signature
 * verification, trust-graph gating, and persistence; this module owns the
 * arithmetic and the rejection rules.
 *
 * Scope boundaries:
 *
 *  - Signatures are assumed pre-verified. Pass only [CurrencySigning]-
 *    accepted envelopes; this reducer trusts the bytes it sees.
 *  - Trust-graph membership (Provisional+) is the caller's check. The
 *    reducer will happily credit a transfer from a stranger if the
 *    stranger somehow has a balance — that's the gatekeeper's job, not
 *    arithmetic.
 *  - Order-of-arrival matters. A gap in a sender's seq is rejected
 *    here; higher layers buffer and replay. The reducer never silently
 *    holds events.
 *  - Slashing is irreversible by design. A slashed account stays
 *    slashed for the life of this State; future transfers from it are
 *    rejected. CURRENCY.md §6.2.
 */
object LedgerReducer {

    /**
     * Immutable snapshot. [apply] returns a new State; the previous one
     * is unchanged. Cheap to keep around (maps share no mutable parts).
     */
    data class State(
        val balances: Map<AccountId, Long>,
        val seqByAccount: Map<AccountId, Long>,
        val slashed: Set<AccountId>,
        val genesisApplied: Boolean,
        val rejections: List<Rejection>,
    ) {
        fun balanceOf(account: AccountId): Long = balances[account] ?: 0L
        fun seqOf(account: AccountId): Long = seqByAccount[account] ?: 0L
        fun isSlashed(account: AccountId): Boolean = account in slashed

        companion object {
            val EMPTY = State(
                balances = emptyMap(),
                seqByAccount = emptyMap(),
                slashed = emptySet(),
                genesisApplied = false,
                rejections = emptyList(),
            )
        }
    }

    sealed interface Event
    data class GenesisEvent(val cert: GenesisIssuance) : Event
    data class TransferEvent(val transfer: Transfer) : Event
    /** A slash that has already been validated by the caller. */
    data class SlashEvent(val target: AccountId) : Event

    data class Rejection(val event: Event, val reason: Reason)

    enum class Reason {
        DUPLICATE_GENESIS,
        SENDER_SLASHED,
        RECIPIENT_SLASHED,
        UNKNOWN_SENDER,
        SEQ_GAP,
        SEQ_REGRESSION,
        OVERSPEND,
        SELF_TRANSFER,
    }

    /** Apply a single event. Returns a new state — never mutates. */
    fun apply(state: State, event: Event): State = when (event) {
        is GenesisEvent -> applyGenesis(state, event)
        is TransferEvent -> applyTransfer(state, event)
        is SlashEvent -> applySlash(state, event)
    }

    /** Apply a sequence of events in iteration order. */
    fun fold(events: Iterable<Event>): State =
        events.fold(State.EMPTY) { acc, e -> apply(acc, e) }

    // --------------------------------------------------------------------
    // Per-event handlers
    // --------------------------------------------------------------------

    private fun applyGenesis(state: State, event: GenesisEvent): State {
        if (state.genesisApplied) {
            return state.copy(rejections = state.rejections + Rejection(event, Reason.DUPLICATE_GENESIS))
        }
        // Credit each recipient. The issuer is not credited unless they
        // also appear in the recipient list — same rule as anyone else.
        val newBalances = state.balances.toMutableMap()
        for (r in event.cert.recipients) {
            val id = AccountId(r.account)
            newBalances[id] = (newBalances[id] ?: 0L) + r.amount
        }
        return state.copy(
            balances = newBalances,
            genesisApplied = true,
        )
    }

    private fun applyTransfer(state: State, event: TransferEvent): State {
        val t = event.transfer
        val sender = AccountId(t.sender)
        val recipient = AccountId(t.recipient)

        // Order matters: slash checks first (cheapest, most decisive),
        // then self-transfer, then known-sender, then seq, then balance.
        if (state.isSlashed(sender)) return reject(state, event, Reason.SENDER_SLASHED)
        if (state.isSlashed(recipient)) return reject(state, event, Reason.RECIPIENT_SLASHED)
        if (sender == recipient) return reject(state, event, Reason.SELF_TRANSFER)

        // First-sight sender: must have come from a genesis or earlier
        // transfer. If we don't know them, we have no way to validate
        // their balance — refuse and let higher layers buffer/wait.
        val senderKnown = sender in state.balances || sender in state.seqByAccount
        if (!senderKnown) return reject(state, event, Reason.UNKNOWN_SENDER)

        val lastSeq = state.seqOf(sender)
        when {
            t.seq <= lastSeq -> return reject(state, event, Reason.SEQ_REGRESSION)
            t.seq != lastSeq + 1 -> return reject(state, event, Reason.SEQ_GAP)
        }

        val senderBalance = state.balanceOf(sender)
        if (t.amount > senderBalance) return reject(state, event, Reason.OVERSPEND)

        val newBalances = state.balances.toMutableMap()
        newBalances[sender] = senderBalance - t.amount
        newBalances[recipient] = (newBalances[recipient] ?: 0L) + t.amount

        val newSeqs = state.seqByAccount.toMutableMap()
        newSeqs[sender] = t.seq

        return state.copy(balances = newBalances, seqByAccount = newSeqs)
    }

    private fun applySlash(state: State, event: SlashEvent): State {
        // Slashing the same account twice is a no-op, not a rejection —
        // slash envelopes can legitimately arrive via multiple paths and
        // the second occurrence carries no new information.
        if (state.isSlashed(event.target)) return state
        val newBalances = state.balances.toMutableMap()
        newBalances[event.target] = 0L
        return state.copy(
            balances = newBalances,
            slashed = state.slashed + event.target,
        )
    }

    private fun reject(state: State, event: Event, reason: Reason): State =
        state.copy(rejections = state.rejections + Rejection(event, reason))
}
