package com.wyspr.app.profile

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wyspr.core.identity.PublicKey

/**
 * In-app viewer for a paired peer's onion-hosted profile page.
 * The HTML is fetched by [ProfileFetcher] over Tor (SOCKS5 dial
 * → plain HTTP GET) and rendered in a sandboxed WebView with
 * JavaScript and network access disabled. The WebView NEVER hits
 * the network directly — we hand it the bytes via loadDataWithBaseURL
 * with a `about:blank` base so any relative URLs resolve nowhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewProfileScreen(
    peer: PublicKey,
    onBack: () -> Unit,
    viewModel: ViewProfileViewModel = hiltViewModel(),
) {
    LaunchedEffect(peer.bytes.toList()) { viewModel.start(peer) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Peer page",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.retry(peer) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                ViewProfileViewModel.UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Fetching over Tor…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is ViewProfileViewModel.UiState.NoOnion -> ErrorPanel(message = s.message, onion = null, onRetry = { viewModel.retry(peer) })
                is ViewProfileViewModel.UiState.Failed -> ErrorPanel(message = s.message, onion = s.onion, onRetry = { viewModel.retry(peer) })
                is ViewProfileViewModel.UiState.Ok -> {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // Lock the WebView down hard. We render
                                // peer-supplied HTML — assume it's
                                // hostile until further notice.
                                settings.javaScriptEnabled = false
                                settings.allowContentAccess = false
                                settings.allowFileAccess = false
                                settings.blockNetworkLoads = true
                                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                                settings.setSupportMultipleWindows(false)
                            }
                        },
                        update = { web ->
                            web.loadDataWithBaseURL(
                                "about:blank",
                                s.html,
                                "text/html",
                                "utf-8",
                                null,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onion: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "—◇—",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            "Couldn't load this page",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onion != null) {
            Text(
                "$onion.onion",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onRetry) { Text("Try again") }
    }
}
