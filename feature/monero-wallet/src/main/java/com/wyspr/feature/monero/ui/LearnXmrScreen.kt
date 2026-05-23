package com.wyspr.feature.monero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyspr.core.ui.components.WysprPanel

/**
 * Why-Monero-and-where-to-get-it explainer. Pure static content
 * — no ViewModel, no Hilt. Reachable from:
 *   - the Wallet tab when the balance is zero (empty-state nudge)
 *   - a "Learn about XMR" link in the receive address panel
 *
 * Threat-model note: we deliberately do NOT link out to live URLs
 * here. Acquisition services come and go (LocalMonero shut down
 * in 2024; new ones appear), and clearnet links would tempt a
 * user to open them outside Tor. We list service names + a one-
 * line description so the user can search in Tor Browser
 * themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnXmrScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("About Monero") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WhyMoneroPanel()
            WhatYouGetPanel()
            TradeOffsPanel()
            AcquisitionPanel()
            CaveatPanel()
        }
    }
}

@Composable
private fun WhyMoneroPanel() {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Why we chose Monero",
                style = MaterialTheme.typography.titleMedium,
            )
            Bullet(
                "Privacy is the default, not an option. " +
                    "Every Monero transaction hides the sender, recipient, and amount. " +
                    "Other privacy coins (Zcash, for example) let you opt into shielded " +
                    "transactions, but most of their actual volume runs through transparent " +
                    "addresses that anyone can analyse. Monero has no such footgun.",
            )
            Bullet(
                "Mature, audited cryptography. " +
                    "Ring signatures hide the sender, stealth addresses hide the recipient, " +
                    "RingCT + Bulletproofs hide the amount. The combination has been " +
                    "deployed since 2017 and has held up.",
            )
            Bullet(
                "Light on mobile. " +
                    "Wyspr uses mollyim's monero-wallet-sdk — it talks to a remote node " +
                    "over Tor and only downloads the blocks containing YOUR outputs. A fresh " +
                    "wallet syncs in seconds, not gigabytes.",
            )
            Bullet(
                "No smart contracts, no attack surface. " +
                    "Monero is a payments-only protocol. There's no DeFi, no NFTs, no on-chain " +
                    "applications that can be compromised to drain your wallet — just send " +
                    "and receive.",
            )
        }
    }
}

@Composable
private fun WhatYouGetPanel() {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "What's hidden",
                style = MaterialTheme.typography.titleMedium,
            )
            Row("Sender", "Mixed with 15 decoy inputs (ring signatures). An observer can't " +
                "tell which one actually spent.")
            Row("Recipient", "Every payment goes to a one-time stealth address derived from " +
                "the recipient's public address. Reusing the same address never shows up " +
                "on-chain twice.")
            Row("Amount", "Hidden via RingCT. The chain proves the transaction balances " +
                "without revealing how much was moved.")
            Row("Your IP", "Wyspr routes every byte of wallet traffic through its " +
                "embedded Tor proxy. The remote node sees a Tor exit, never your address.")
        }
    }
}

@Composable
private fun TradeOffsPanel() {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Trade-offs to know",
                style = MaterialTheme.typography.titleMedium,
            )
            Bullet(
                "Lower market cap than Bitcoin. Liquidity is fine for personal payments but " +
                    "moving large amounts in or out of XMR takes more steps than BTC.",
            )
            Bullet(
                "Delisted from many KYC exchanges. This is actually a feature — exchanges " +
                    "delisted XMR because they couldn't surveil it. The trade-off is fewer " +
                    "convenient on-ramps.",
            )
            Bullet(
                "Transactions take ~20 minutes to fully unlock (10 confirmations). Fine for " +
                    "between-people payments; not as instant as Lightning.",
            )
            Bullet(
                "No public block explorer view of your funds. Useful for privacy, but means " +
                    "you have to trust your wallet's accounting — there's no chain-side " +
                    "second opinion.",
            )
        }
    }
}

@Composable
private fun AcquisitionPanel() {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Where to acquire XMR",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Listed roughly from most-private to most-convenient. Search for each " +
                    "in Tor Browser to find the current URL — services rotate, and we don't " +
                    "ship clearnet links from here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceRow(
                name = "Haveno",
                description = "Decentralised peer-to-peer exchange. No-KYC, runs over Tor by " +
                    "default. Trade fiat (bank transfer, cash, gift cards) for XMR with " +
                    "another human. Highest privacy, slowest UX.",
            )
            SourceRow(
                name = "Atomic swaps (Eigenwallet, COMIT)",
                description = "Trustless BTC ↔ XMR swap on-chain. If you already have BTC, " +
                    "this converts to XMR without going through any exchange or counterparty " +
                    "who can identify you.",
            )
            SourceRow(
                name = "Trocador / Majestic Bank / Exch",
                description = "No-KYC instant swap aggregators. Send any common crypto, " +
                    "receive XMR at your address minutes later. Trust the operator briefly " +
                    "during the swap window.",
            )
            SourceRow(
                name = "Cake Wallet built-in exchange",
                description = "Mobile wallet that wraps swap providers behind a friendly UI. " +
                    "Convenient on-ramp but you're trusting one more layer.",
            )
            SourceRow(
                name = "Mining",
                description = "Get paid in XMR for contributing CPU work. Realistic on " +
                    "desktop, marginal on phones. Slow but identity-free.",
            )
            SourceRow(
                name = "Get paid in XMR",
                description = "The most private acquisition path is to accept payment for " +
                    "your own work directly in XMR. Share your receive address; skip the " +
                    "fiat detour entirely.",
            )
            SourceRow(
                name = "KYC exchanges (Kraken, KuCoin)",
                description = "Last resort. Identity-linked, defeats much of the point. " +
                    "Useful only if no other option is available where you live.",
            )
        }
    }
}

@Composable
private fun CaveatPanel() {
    WysprPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.tertiary,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Operational privacy still matters",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = "Strong cryptography doesn't help if you announce \"I just acquired " +
                    "1 XMR\" on a public timestamp. Open the wallet in Tor Browser when " +
                    "researching, don't reuse addresses across services, and never share " +
                    "your seed words. Wyspr preserves what it can; the rest is on you.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Row(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceRow(name: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = name, style = MaterialTheme.typography.titleSmall)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
