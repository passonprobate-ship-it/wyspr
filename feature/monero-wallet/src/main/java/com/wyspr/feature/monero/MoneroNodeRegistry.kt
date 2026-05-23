package com.wyspr.feature.monero

/**
 * Default list of Monero remote nodes Wyspr will round-robin
 * across. Curated for the v0.7.0 scaffold; users will be able to
 * add their own nodes (or run a private node on a Wyspr Pi) in
 * a later sprint.
 *
 * **Inclusion criteria** (see [MoneroNode] kdoc for the formal list):
 *
 *  - Reachable through Tor. `.onion` is strongly preferred — it
 *    avoids the exit-relay attack surface entirely.
 *  - Open RPC, no auth.
 *  - Operator does not censor the tx pool.
 *  - Run by an organisation Monero users would name unprompted
 *    (community foundations, prominent contributors). Random
 *    "free public node" lists are deliberately excluded.
 *
 * **Operational notes.**
 *  - Order is stable across builds but the round-robin starting
 *    index is randomised per session so a single hostile node can
 *    only ever be picked first 1/N of the time.
 *  - Public node lists rot. The maintainer is responsible for
 *    re-checking each entry before tagging a release — see
 *    `docs/FDROID.md §3`. Long-term plan is to fetch a signed
 *    node list over the trust graph (sync-engine envelope), so
 *    the canonical list itself can rotate without a release.
 *
 * **v0.7.0a status.** The default entries below are placeholders
 * sourced from public Monero community lists. Their reachability
 * from the embedded Tor exit pool is *not* verified in CI and has
 * not been verified on real hardware yet. The first hardware proof
 * of this module will surface dead entries; replace them before
 * tagging a release that publicises the wallet feature.
 *
 * All inclusion checks done manually — Wyspr never auto-imports
 * an external node list at runtime.
 */
internal object MoneroNodeRegistry {

    /**
     * Default node pool for fresh installs. These three are real
     * hostnames documented across the Monero community (Rino,
     * moneroworld, p2pool sidechain mirror) but **none have been
     * end-to-end probed from Wyspr yet** — see kdoc above.
     */
    val defaults: List<MoneroNode> = listOf(
        MoneroNode(
            host = "node.community.rino.io",
            port = 18081,
            label = "Rino community node",
        ),
        MoneroNode(
            host = "node.moneroworld.com",
            port = 18089,
            label = "Moneroworld",
        ),
        MoneroNode(
            host = "p2pmd.xmrvsbeast.com",
            port = 18081,
            label = "p2pool sidechain mirror",
        ),
    )
}
