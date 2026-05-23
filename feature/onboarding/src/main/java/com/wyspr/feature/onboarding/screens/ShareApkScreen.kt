package com.wyspr.feature.onboarding.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wyspr.core.ui.QrRenderer
import com.wyspr.feature.onboarding.share.ApkShareIntent
import com.wyspr.feature.onboarding.share.ApkSharingViewModel
import kotlinx.coroutines.launch

/**
 * Shows a QR encoding the URL of the on-device APK share server. The
 * Invitee scans this QR with their stock phone camera; their browser
 * opens the mini-site served from [ApkShareServer], they tap the Agree
 * button, the APK downloads peer-to-peer over the local network.
 *
 * Lifecycle: starting the server is asynchronous (we have to find a
 * LAN IP first), so the screen renders a "Starting…" state until the
 * VM reports [ApkSharingViewModel.State.Ready]. On exit the
 * DisposableEffect explicitly calls [ApkSharingViewModel.stop] so
 * the socket releases immediately rather than waiting for the VM to
 * be cleared by the navigation stack.
 */
@Composable
fun ShareApkScreen(
    onDone: () -> Unit,
    onUpdateFromPeer: (() -> Unit)? = null,
    viewModel: ApkSharingViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Share Wyspr",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Send the APK via any app, or have your peer scan the QR " +
                "for a direct download over WiFi — no internet needed.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        ShareViaAppButton()

        when (val s = state) {
            is ApkSharingViewModel.State.Starting -> StartingPlaceholder()
            is ApkSharingViewModel.State.Failed -> FailedPanel(message = s.message)
            is ApkSharingViewModel.State.Ready -> ReadyPanel(s)
        }

        // The two-way switch: this screen ships the APK out; the
        // peer side pulls from a peer. Only show the link when the
        // host supplied a callback (PairScreen still calls
        // [ShareApkScreen] without it, since the inverse side-trip
        // is already reachable from there).
        if (onUpdateFromPeer != null) {
            androidx.compose.material3.TextButton(
                onClick = onUpdateFromPeer,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Receive an update from a peer") }
        }

        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done") }
    }
}

@Composable
private fun StartingPlaceholder() {
    Surface(
        color = Color.Black,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Starting share server…",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FailedPanel(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ReadyPanel(state: ApkSharingViewModel.State.Ready) {
    val qrBitmap = remember(state.url) { QrRenderer.render(state.url, sizePx = 768) }
    val sha256Pretty = remember(state.apkSha256) {
        state.apkSha256.chunked(8).joinToString(" ")
    }
    val sizeMb = remember(state.apkSizeBytes) {
        "%.1f".format(state.apkSizeBytes / (1024.0 * 1024.0))
    }

    Surface(
        color = Color.White,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Share QR — local server URL",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // Manual fallback: not every Android camera highlights URLs in
    // the viewfinder (Samsung Galaxy A-series, One UI Core, anything
    // running Android Go). Show the URL big, monospace, tap to copy
    // — the inviter can paste it into the recipient's browser bar
    // directly, or send via Quick Share / SMS / anything.
    UrlCopyRow(url = state.url)

    Text(
        "Wyspr ${state.versionName} • $sizeMb MB • SHA-256:",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        sha256Pretty,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun ShareViaAppButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    Button(
        onClick = {
            if (working) return@Button
            working = true
            scope.launch {
                val result = runCatching { ApkShareIntent.create(context) }
                working = false
                result
                    .onSuccess { intent ->
                        try {
                            context.startActivity(
                                Intent.createChooser(intent, "Send Wyspr APK"),
                            )
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                "No app available to share with",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    .onFailure {
                        Toast.makeText(
                            context,
                            "Couldn't prepare the APK: ${it.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        },
        enabled = !working,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (working) "Preparing…" else "Share APK via app")
    }
}

@Composable
private fun UrlCopyRow(url: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(url))
                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    url,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Tap to copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
