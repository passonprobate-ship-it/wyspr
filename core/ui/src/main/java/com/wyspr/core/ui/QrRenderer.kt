package com.wyspr.core.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Render an arbitrary string payload to a square QR [Bitmap].
 *
 *  - High error correction (H) — survives partial occlusion from camera
 *    glare or fingers, important for handshake reliability.
 *  - Margin 1 module — the caller draws its own padding in the layout.
 *
 * The Bitmap is plain ARGB_8888; consumers wrap it in a Compose Image.
 */
object QrRenderer {

    fun render(payload: String, sizePx: Int): Bitmap {
        require(sizePx > 0)
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        return bmp
    }
}
