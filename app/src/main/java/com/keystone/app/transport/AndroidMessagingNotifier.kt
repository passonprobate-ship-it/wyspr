package com.keystone.app.transport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.keystone.app.MainActivity
import com.keystone.app.R
import com.keystone.core.transport.MessagingNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [MessagingNotifier]. One notification
 * per peer; the system updates in place when the same id is
 * re-posted, so a flurry of messages from one sender collapses
 * into a single "Keystone: 3 new messages" banner rather than
 * filling the user's shade.
 *
 * Notification ids are derived deterministically from the peer's
 * Ed25519 pubkey via `contentHashCode` mod a positive 31-bit
 * range — collision risk between two paired peers in the same
 * community is ~2^-31, low enough to ignore.
 */
@Singleton
class AndroidMessagingNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : MessagingNotifier {

    private val nm = NotificationManagerCompat.from(context)
    private val systemNm = context.getSystemService(NotificationManager::class.java)

    init { ensureChannel() }

    override fun notifyInbound(
        peerPub: ByteArray,
        senderFingerprint: String,
        count: Int,
        preview: String,
    ) {
        if (!nm.areNotificationsEnabled()) return
        val title = if (count > 1) "$count new messages" else "New message"
        val openIntent = PendingIntent.getActivity(
            context,
            // Each peer gets its own request code so the PendingIntent
            // doesn't get mutated by the system when more than one
            // notification is on-screen.
            notificationId(peerPub),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(senderFingerprint)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        runCatching { nm.notify(notificationId(peerPub), notification) }
    }

    override fun clearForPeer(peerPub: ByteArray) {
        nm.cancel(notificationId(peerPub))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = systemNm ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "New messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Shown when a paired peer's device delivers a new message."
                setShowBadge(true)
            },
        )
    }

    private fun notificationId(peerPub: ByteArray): Int {
        // Positive 31-bit deterministic id keyed on pubkey content.
        return (peerPub.contentHashCode() and 0x7FFFFFFF) or 0x10000
    }

    private companion object {
        const val CHANNEL_ID = "messages"
    }
}
