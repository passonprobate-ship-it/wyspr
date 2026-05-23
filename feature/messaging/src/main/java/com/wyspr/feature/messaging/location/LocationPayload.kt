package com.wyspr.feature.messaging.location

/**
 * Encoding for "this is a location, not text" payloads inside the
 * existing `MessageEnvelope.body` and `GroupMessageEnvelope.body`
 * string field.
 *
 * Wire shape (in the body string):
 *
 *     wyspr:loc:{lat},{lng},{accuracy_meters}
 *
 * Where lat/lng are decimal degrees and accuracy is meters (float).
 * Chosen because it survives every CBOR/Noise/BLE codepath we
 * already exercise — body is `bstr` over UTF-8 in the signed-form
 * spec, so plain ASCII characters round-trip cleanly.
 *
 * The `wyspr:` prefix doubles as a magic marker: receivers
 * detect it and render specially instead of as plaintext. Users
 * can't accidentally type this prefix at the start of a message
 * without us catching it on send (defence-in-depth in the
 * composer).
 *
 * Future tagged-body kinds (image, file, contact) follow the same
 * scheme: `wyspr:{kind}:{...}`. When we eventually promote this
 * to a proper wire-format field we'll keep the body-side encoding
 * for backwards compat with already-stored messages.
 */
object LocationPayload {
    private const val PREFIX = "wyspr:loc:"

    fun encode(lat: Double, lng: Double, accuracyMeters: Float): String =
        "$PREFIX$lat,$lng,$accuracyMeters"

    fun decode(body: String): Parsed? {
        if (!body.startsWith(PREFIX)) return null
        val rest = body.removePrefix(PREFIX)
        val parts = rest.split(",")
        if (parts.size != 3) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        val accuracy = parts[2].toFloatOrNull() ?: return null
        if (lat !in -90.0..90.0) return null
        if (lng !in -180.0..180.0) return null
        return Parsed(lat, lng, accuracy)
    }

    fun isLocation(body: String): Boolean = body.startsWith(PREFIX)

    data class Parsed(
        val lat: Double,
        val lng: Double,
        val accuracyMeters: Float,
    )
}
