package com.keystone.feature.monero

/**
 * A Monero daemon-rpc endpoint Keystone is willing to talk to. Every
 * field is part of the identifier: same host, different port → a
 * different node entry (the daemon may move).
 *
 * Three properties are non-negotiable for inclusion in the default
 * registry:
 *  - Reachable over Tor. Either an `.onion` host (preferred — no exit
 *    relay needed) or a clearnet host on the public internet that
 *    accepts inbound traffic from Tor exit relays.
 *  - Open RPC. The node operator is willing to serve untrusted
 *    callers (most public nodes); we never need authenticated RPC
 *    because we never reveal a wallet identity to the node.
 *  - No tx-pool censoring. Some "public" nodes drop transactions
 *    before relay. Verified empirically before adding here.
 *
 * The combined IP-on-the-wire risk is mitigated by mandatory
 * routing through `TorBackend.socksPort` — the node operator sees
 * a Tor exit relay or, for `.onion` nodes, only the inner end of a
 * Tor circuit.
 */
data class MoneroNode(
    val host: String,
    val port: Int,
    val label: String,
) {
    init {
        require(host.isNotBlank()) { "empty host" }
        require(port in 1..0xFFFF) { "port $port out of range" }
        // Hostname is interpolated into an HTTP Host header inside
        // [com.keystone.feature.monero.rpc.MoneroRpcClient]. Reject
        // anything outside the RFC 952/1123 + RFC 1035 character set
        // so a user-supplied or trust-pushed node can't smuggle
        // additional headers via CR/LF or whitespace.
        require(host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) {
            "host contains invalid characters: $host"
        }
        require(host.length <= 253) { "host too long: ${host.length}" }
    }

    /** True iff [host] is a v3 onion address (56 chars + .onion). */
    val isOnion: Boolean
        get() = host.endsWith(".onion") && host.length == 56 + ".onion".length

    override fun toString(): String = "$label ($host:$port)"
}
