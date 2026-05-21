package com.keystone.feature.messaging.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads an image from a content URI, downscales + WebP-compresses it
 * until the result fits the [TARGET_MAX_BYTES] budget, and returns
 * the encoded bytes. Returns null if no combination of scale +
 * quality gets the encoded size under budget (rare — even crowded
 * photos fit at 360 px / quality 50).
 *
 * Budget math: [com.keystone.feature.messaging.MessageEnvelope.MAX_BODY_BYTES]
 * is 16,384 and this matches the hard
 * [com.keystone.core.transport.bluetooth.BleLink.MAX_FRAME_BYTES] cap
 * — bumping the envelope past that breaks single-frame BLE
 * delivery. [ImagePayload.PREFIX] eats 13 bytes; base64 inflates
 * the remaining budget by 4/3. Target ≤ 11,500 raw bytes so the
 * encoded body lands at ≤ ~15,350.
 *
 * **Why WebP instead of JPEG (v0.8.2):** WebP-lossy is roughly 25–35%
 * more efficient than JPEG at similar visual quality, which lets the
 * same 11.5 KB budget carry either a higher-resolution image or a
 * higher quality factor. Memes (640 px, text-heavy) used to come out
 * crunchy at JPEG q≤45; the equivalent WebP at q≈65 looks
 * substantially cleaner for the same byte count.
 *
 * The loop walks a small ladder of (maxDim, quality) pairs from
 * "best quality" to "ugly-but-tiny" and takes the first that fits.
 * Optimised for "always deliverable" over "best looking."
 */
object ImageCompressor {

    /** Raw encoded-image byte budget — keeps the body under 16 KB after base64. */
    const val TARGET_MAX_BYTES = 11_500

    /**
     * (maxDim, WebP quality). WebP quality scale isn't 1:1 with
     * JPEG — q=75 WebP roughly matches q=85 JPEG visually but at
     * ~70% of the bytes. Pinning higher resolutions + mid-high
     * quality at the top of the ladder gives memes legible text.
     */
    private val LADDER: List<Pair<Int, Int>> = listOf(
        720 to 75,
        720 to 65,
        640 to 75,
        640 to 65,
        640 to 55,
        480 to 70,
        480 to 60,
        480 to 50,
        360 to 60,
        360 to 45,
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
                val bytes = encodeWebp(scaled, quality)
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

    /**
     * Encode [bmp] as WebP. On API 30+ uses the explicit
     * `WEBP_LOSSY` format; older devices use the legacy `WEBP`
     * constant which has historically been lossy. Result is
     * `null` on encode failure (very rare — typically only OOM).
     */
    @Suppress("DEPRECATION")
    private fun encodeWebp(bmp: Bitmap, quality: Int): ByteArray? {
        val out = ByteArrayOutputStream(16_384)
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        val ok = bmp.compress(format, quality, out)
        return if (ok) out.toByteArray() else null
    }

    private const val TAG = "ImageCompressor"
}
