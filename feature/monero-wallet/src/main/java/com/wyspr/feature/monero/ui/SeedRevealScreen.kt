package com.wyspr.feature.monero.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.wyspr.core.ui.components.WysprPanel
import kotlinx.coroutines.launch

/**
 * Reveal-the-seed screen. Behind a biometric prompt that fires
 * before the words ever hit the screen. Renders 25 numbered
 * Electrum-style words plus an "I've backed these up" confirm
 * that flips the [com.wyspr.feature.monero.persistence.WalletPrefs]
 * flag so the banner on the wallet home disappears.
 *
 * Threat model:
 *  - Words are only held in Compose state for the lifetime of the
 *    screen. Navigating away drops the array reference; GC reclaims
 *    it. We can't zero a Kotlin `List<String>` deterministically,
 *    but we never copy them into anything that outlives the
 *    composition.
 *  - Copy-to-clipboard is opt-in only — there's no auto-copy.
 *  - Shows a hard-warning banner BEFORE revealing so a shoulder-
 *    surfer can't sneak a screenshot if the user opened the page
 *    by accident.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SeedRevealScreen(
    onBack: () -> Unit,
    biometricPrompt: suspend () -> Boolean,
) {
    val viewModel: SeedRevealViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var revealed by remember { mutableStateOf(false) }
    var authFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.bootstrap() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Back up your seed") },
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
            WarningPanel()
            if (!revealed) {
                Button(
                    onClick = {
                        scope.launch {
                            val ok = try { biometricPrompt() } catch (_: Throwable) { false }
                            if (ok) {
                                viewModel.reveal()
                                revealed = true
                            } else {
                                authFailed = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reveal my 25 words")
                }
                if (authFailed) {
                    Text(
                        text = "Authentication failed — try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                val words = state.words
                if (words.isEmpty()) {
                    Text(
                        text = "No seed available — the wallet hasn't been bootstrapped yet. " +
                            "Open the Wallet tab once and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    SeedWordsPanel(words = words)
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(words.joinToString(" ")))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Copy to clipboard")
                    }
                    Button(
                        onClick = {
                            viewModel.acknowledge()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("I've written these down safely")
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningPanel() {
    WysprPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.error,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Anyone who sees these 25 words controls your wallet.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Write them on paper, in order. Store them somewhere only you can " +
                    "access. Never type them into another app, never store them in a " +
                    "cloud note, never photograph them. Wyspr will not ask for these " +
                    "again unless you tap Reveal here.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedWordsPanel(words: List<String>) {
    WysprPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Your 25-word seed",
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                words.forEachIndexed { idx, w ->
                    AssistChip(
                        onClick = {},
                        label = { Text("${idx + 1}. $w") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
    }
}

