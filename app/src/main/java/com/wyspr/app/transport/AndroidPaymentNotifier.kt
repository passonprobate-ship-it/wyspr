package com.wyspr.app.transport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wyspr.app.MainActivity
import com.wyspr.app.R
import com.wyspr.core.transport.PaymentNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidPaymentNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : PaymentNotifier {

    private val nm = NotificationManagerCompat.from(context)
    private val systemNm = context.getSystemService(NotificationManager::class.java)

    @Volatile private var walletScreenActive: Boolean = false

    init { ensureChannel() }

    override fun setActiveWalletScreen(active: Boolean) {
        walletScreenActive = active
        // Cancel any outstanding payment notifications when the wallet
        // screen is foregrounded. Can't cancel by tag easily without
        // tracking IDs, so use the channel-level group summary if needed.
        // For now this is best-effort since IDs are now per-tx.
    }

    override fun notifyInboundPayment(
        peerPub: ByteArray?,
        peerName: String?,
        amountAtomicUnits: Long,
        txHash: String,
    ) {
        if (!nm.areNotificationsEnabled()) return
        if (walletScreenActive) return

        val xmr = formatXmr(amountAtomicUnits)
        val title = "Received $xmr XMR"
        val body = when {
            peerName != null -> "From $peerName"
            peerPub != null -> "From ${shortFingerprint(peerPub)}"
            else -> "New incoming payment"
        }

        // Derive a unique notification ID from the txHash so multiple
        // payment notifications don't replace each other.
        val notifId = txHash.hashCode() and 0x7FFFFFFF

        val openIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ACTION_OPEN_WALLET
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        runCatching { nm.notify(notifId, notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = systemNm ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Payments",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Shown when you receive a Monero payment."
                setShowBadge(true)
            },
        )
    }

    private fun formatXmr(atomicUnits: Long): String =
        java.math.BigDecimal(atomicUnits)
            .divide(java.math.BigDecimal(1_000_000_000_000L))
            .stripTrailingZeros()
            .toPlainString()

    private fun shortFingerprint(pub: ByteArray): String {
        val hex = pub.joinToString("") { "%02x".format(it) }
        return hex.take(8) + "…"
    }

    companion object {
        private const val CHANNEL_ID = "payments"
        const val ACTION_OPEN_WALLET = "com.wyspr.app.OPEN_WALLET"
    }
}
