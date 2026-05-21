package com.keystone.feature.messaging.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads an image from a content URI, downscales + JPEG-compresses it
 * until the result fits the [TARGET_MAX_BYTES] budget, and returns
 * the JPEG bytes. Returns null if no combination of scale + quality
 * gets the encoded size under budget (rare — even crowded photos
 * fit at 240px / quality 25).
 *
 * Budget math: [com.keystone.feature.messaging.MessageEnvelope.MAX_BODY_BYTES]
 * is 16,384. The [ImagePayload.PREFIX] eats 13 bytes; base64 inflates
 * the remaining budget by 4/3. Target ≤ 11,000 raw bytes so the
 * encoded body lands at ≤ ~14,680 — comfortably under cap with room
 * to spare in case Cbor varies overhead by a few bytes between
 * envelope versions.
 *
 * The loop is intentionally simple: walk a small ladder of
 * (maxDim, quality) pairs from "good-looking" to "ugly-but-tiny" and
 * take the first one that fits. We're optimising for "always
 * deliverable" over "best looking" — the photo can be re-sent at a
 * different fidelity if the user wants a higher-quality copy.
 */
object ImageCompressor {

    /** Raw JPEG byte budget — keeps the encoded body under 16 KB after base64. */
    const val TARGET_MAX_BYTES = 11_000

    /** Output ladder, walked top-down. (maxDim, quality). */
    private val LADDER: List<Pair<Int, Int>> = listOf(
        640 to 60,
        640 to 45,
        480 to 60,
        480 to 45,
        480 to 30,
        360 to 45,
        360 to 30,
        240 to 40,
        240 to 25,
    )

    /**
     * Decode + compress + return JPEG bytes ≤ [TARGET_MAX_BYTES], or
     * null if even the smallest rung overshoots (an unlikely
     * pathological input).
     *
     * Runs synchronously — caller is responsible for dispatching to
     * Dispatchers.IO. Doesn't throw on malformed input; returns null.
     */
    fun compress(resolver: ContentResolver, uri: Uri): ByteArray? {
        val original = loadBitmap(resolver, uri) ?: return null
        try {
            for ((maxDim, quality) in LADDER) {
                val scaled = scaleIfNeeded(original, maxDim)
                val bytes = encodeJpeg(scaled, quality)
                if (scaled !== original) scaled.recycle()
                if (bytes != null && bytes.size <= TARGET_MAX_BYTES) {
                    Log.d(TAG, "compress: dim=$maxDim q=$quality bytes=${bytes.size}")
                    return bytes
                }
            }
            Log.w(TAG, "compress: no ladder rung fit under $TARGET_MAX_BYTES bytes")
            return null
        } finally {
            original.recycle()
        }
    }

    private fun loadBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        // Two-pass decode: first inspect dimensions without allocating
        // pixel data, then load with a coarse inSampleSize so we don't
        // OOM on a 12-megapixel phone photo just to throw most of it
        // away. The fine-grained downscale happens in [scaleIfNeeded].
        val (srcW, srcH) = readDimensions(resolver, uri) ?: return null
        val maxBootstrapDim = (LADDER.maxOfOrNull { it.first } ?: 640) * 2
        val sample = computeInSampleSize(srcW, srcH, maxBootstrapDim)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrNull()
    }

    private fun readDimensions(resolver: ContentResolver, uri: Uri): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return opts.outWidth to opts.outHeight
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, targetMaxDim: Int): Int {
        var sample = 1
        var halfW = srcW
        var halfH = srcH
        while (halfW / 2 >= targetMaxDim || halfH / 2 >= targetMaxDim) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        return sample
    }

    private fun scaleIfNeeded(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longSide = max(w, h)
        if (longSide <= maxDim) return src
        val ratio = maxDim.toFloat() / longSide
        val newW = (w * ratio).roundToInt().coerceAtLeast(1)
        val newH = (h * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, /* filter = */ true)
    }

    private fun encodeJpeg(bmp: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream(16_384)
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return if (ok) out.toByteArray() else null
    }

    private const val TAG = "ImageCompressor"
}
