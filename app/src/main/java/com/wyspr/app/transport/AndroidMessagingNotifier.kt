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
import com.wyspr.core.transport.MessagingNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [MessagingNotifier]. One notification
 * per peer; the system updates in place when the same id is
 * re-posted, so a flurry of messages from one sender collapses
 * into a single "Wyspr: 3 new messages" banner rather than
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

    /**
     * Peer the user is currently viewing in [com.wyspr.feature.messaging.ConversationViewModel].
     * Set on bind / cleared on screen leave so [notifyInbound] can
     * suppress noise for the active conversation — the user is
     * already looking at the thread.
     *
     * Volatile, not synchronized: a torn read here just means one
     * spurious notification, never a missed message (messages still
     * land in the DB regardless).
     */
    @Volatile private var activePeer: ByteArray? = null

    init { ensureChannel() }

    override fun setActivePeer(peerPub: ByteArray?) {
        activePeer = peerPub
        // Also clear any stale notification for the peer the user is
        // now looking at — they've seen it.
        peerPub?.let { nm.cancel(notificationId(it)) }
    }

    override fun notifyInbound(
        peerPub: ByteArray,
        senderFingerprint: String,
        count: Int,
        preview: String,
    ) {
        if (!nm.areNotificationsEnabled()) return
        // Suppress if the user is currently viewing this peer's
        // thread — an OS notification on top of the open chat is
        // just noise. The message itself lives in the DB and is
        // already on screen for them.
        val active = activePeer
        if (active != null && active.contentEquals(peerPub)) return
        val title = if (count > 1) "$count new messages" else "New message"
        // Deep-link payload: hex-encode the peer pubkey into the
        // intent extras. MainActivity reads these on launch and
        // hands them to the NavController so tapping the
        // notification opens that peer's chat directly instead of
        // dumping the user on the welcome screen.
        val openIntent = PendingIntent.getActivity(
            context,
            notificationId(peerPub),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ACTION_OPEN_CHAT
                putExtra(EXTRA_PEER_HEX, peerPub.toHex())
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val CHANNEL_ID = "messages"
        /** Intent action MainActivity inspects to deep-link into a chat. */
        const val ACTION_OPEN_CHAT = "com.wyspr.app.OPEN_CHAT"
        /** Hex-encoded peer pubkey extra carried on [ACTION_OPEN_CHAT]. */
        const val EXTRA_PEER_HEX = "peer_pub_hex"
    }
}
