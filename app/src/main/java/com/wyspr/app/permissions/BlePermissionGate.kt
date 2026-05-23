package com.wyspr.app.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.wyspr.core.transport.bluetooth.BlePermissions
import kotlinx.coroutines.CompletableDeferred

/**
 * Returns a suspending function that requests every BLE runtime
 * permission this device's API level needs, and resolves true iff
 * the user grants them all. Wired into the onboarding flow so the
 * handshake never starts before BLE has the permissions it needs —
 * the alternative is a hard crash inside `BleTransport.start()`.
 *
 * The returned function is stable across recomposition because the
 * underlying launcher is remembered; calling it again from a fresh
 * coroutine just shows the system permission UI again.
 */
@Composable
fun rememberBlePermissionGate(): suspend () -> Boolean {
    val context = LocalContext.current
    val pending = remember { ArrayDeque<CompletableDeferred<Boolean>>() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = BlePermissions.required.all { results[it] == true }
        pending.removeFirstOrNull()?.complete(allGranted)
    }
    return remember(launcher) {
        gate@{
            if (BlePermissions.allGranted(context)) return@gate true
            val deferred = CompletableDeferred<Boolean>()
            pending.addLast(deferred)
            launcher.launch(BlePermissions.required.toTypedArray())
            deferred.await()
        }
    }
}
