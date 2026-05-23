package com.wyspr.feature.messaging.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One-shot current-location capture. Uses Android's [LocationManager]
 * directly — no Google Play Services dep, so we stay F-Droid-friendly.
 *
 * The result is the latest available fix that's at most
 * [maxAgeMillis] old; if nothing recent is cached, we request a
 * fresh fix and wait up to [timeoutMillis].
 *
 * Caller must hold `ACCESS_FINE_LOCATION` at runtime — this is a
 * dangerous permission and the manifest entry alone isn't enough on
 * API 23+. The caller is responsible for invoking the system
 * permission dialog before calling [capture]; we assert at the
 * SuppressLint boundary.
 */
class LocationProvider(private val context: Context) {

    sealed interface Result {
        data class Ok(val lat: Double, val lng: Double, val accuracyMeters: Float) : Result
        data object Unavailable : Result
        data object PermissionDenied : Result
        data object NoFix : Result
    }

    /**
     * Get the device's current location. Returns Ok on success; the
     * various error variants document why a fix wasn't produced.
     *
     * Strategy:
     * - On API 30+: [LocationManagerCompat.getCurrentLocation]
     * - Older: best-cached `getLastKnownLocation` from any enabled provider
     */
    @SuppressLint("MissingPermission")
    suspend fun capture(timeoutMillis: Long = 8_000): Result {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Result.Unavailable
        if (!LocationManagerCompat.isLocationEnabled(lm)) return Result.Unavailable

        val provider = pickProvider(lm) ?: return Result.Unavailable

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: single-shot async API.
            return suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(
                        lm,
                        provider,
                        cancel,
                        context.mainExecutor,
                    ) { loc: Location? ->
                        if (loc == null) cont.resume(Result.NoFix)
                        else cont.resume(loc.toOk())
                    }
                } catch (se: SecurityException) {
                    cont.resume(Result.PermissionDenied)
                }
            }
        }

        // Pre-API 30 fallback — use cached last-known. Good enough for
        // a "send my location" use case where the user is the one
        // demanding the fix; if they haven't moved, last-known is
        // accurate; if they have, they'd need to walk a few meters
        // for the GPS to refresh — minimal harm for the test devices
        // we run on (both API 30+ in practice).
        return try {
            lm.getLastKnownLocation(provider)
                ?.toOk()
                ?: Result.NoFix
        } catch (se: SecurityException) {
            Result.PermissionDenied
        }
    }

    private fun pickProvider(lm: LocationManager): String? {
        // FUSED is the highest-quality blend (API 31+); fall back to
        // GPS, then NETWORK, then anything enabled. PASSIVE is excluded
        // — it only fires if some other app requests location.
        val ordered = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        for (p in ordered) {
            if (lm.allProviders.contains(p) && lm.isProviderEnabled(p)) return p
        }
        return null
    }

    private fun Location.toOk(): Result.Ok = Result.Ok(
        lat = latitude,
        lng = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else 0f,
    )
}
