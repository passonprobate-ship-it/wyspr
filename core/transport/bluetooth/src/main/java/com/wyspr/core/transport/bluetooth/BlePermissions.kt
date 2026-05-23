package com.wyspr.core.transport.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Which runtime permissions BLE advertising + scanning need on this
 * device, expressed as Android permission strings.
 *
 *   - API 31 (S) and up: BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE,
 *     BLUETOOTH_CONNECT. Location is no longer implied (we set
 *     `usesPermissionFlags="neverForLocation"` in the manifest).
 *   - API 26..30: BLUETOOTH and BLUETOOTH_ADMIN are normal permissions
 *     declared in the manifest, but scanning still requires
 *     ACCESS_FINE_LOCATION as a dangerous runtime permission — without
 *     it the OS silently drops scan results.
 *
 * Caller responsibility: request the missing permissions before calling
 * [BleTransport.start]; the transport itself does not request, it only
 * checks. Failures from the OS during scan/advertise are surfaced via
 * the relevant callbacks.
 */
object BlePermissions {

    /** Runtime permissions to request for [BleTransport.start] to succeed. */
    val required: List<String> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun allGranted(context: Context): Boolean =
        required.all { p ->
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }

    fun missing(context: Context): List<String> =
        required.filter { p ->
            ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED
        }
}
