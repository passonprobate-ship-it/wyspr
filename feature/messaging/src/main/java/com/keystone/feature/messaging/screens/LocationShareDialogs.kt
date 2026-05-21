package com.keystone.feature.messaging.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.keystone.feature.messaging.location.LocationProvider
import kotlinx.coroutines.launch

/**
 * Shared state-holder + dialog set for the "share my location"
 * composer action. Returns a trigger function the composer can hook
 * up to its location icon; renders its own permission/capture/
 * confirm/error dialogs.
 *
 * Used by both [ConversationScreen] and [GroupConversationScreen]
 * with the only difference being [onConfirmed], which sends the
 * location through the appropriate ViewModel.
 */
@Composable
fun rememberLocationShareController(
    onConfirmed: (lat: Double, lng: Double, accuracyMeters: Float) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val provider = remember { LocationProvider(context) }
    val scope = rememberCoroutineScope()

    var capturing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<LocationProvider.Result.Ok?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun capture() {
        scope.launch {
            capturing = true
            val r = provider.capture()
            capturing = false
            when (r) {
                is LocationProvider.Result.Ok -> pending = r
                LocationProvider.Result.PermissionDenied ->
                    error = "Location permission was denied."
                LocationProvider.Result.Unavailable ->
                    error = "Location is turned off on this device."
                LocationProvider.Result.NoFix ->
                    error = "Couldn't get a location fix — try again outdoors."
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) capture()
        else error = "Location permission denied. Share location needs it."
    }

    // Modal: spinner while capturing.
    if (capturing) {
        AlertDialog(
            onDismissRequest = { /* not dismissible mid-capture */ },
            title = { Text("Getting your location…") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = {},
        )
    }

    // Confirmation: user reviews coords before sending.
    val p = pending
    if (p != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Share your location?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "%.5f, %.5f".format(p.lat, p.lng),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val acc = p.accuracyMeters.toInt()
                    if (acc > 0) {
                        Text(
                            "± $acc m accuracy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Sent encrypted to this thread only. The recipient " +
                            "can open it in their maps app.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onConfirmed(p.lat, p.lng, p.accuracyMeters)
                    pending = null
                }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            },
        )
    }

    // Error toast (rendered as a dismissible AlertDialog so the user
    // sees the actual problem instead of a silent no-op).
    val errMsg = error
    if (errMsg != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Couldn't share location") },
            text = { Text(errMsg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }

    // The trigger lambda exposed to the composer.
    return {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) capture()
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
