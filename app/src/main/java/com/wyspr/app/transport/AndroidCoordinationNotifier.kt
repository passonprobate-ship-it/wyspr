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
import com.wyspr.core.transport.CoordinationNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [CoordinationNotifier]. Posts a
 * notification when a new coordination event syncs in from a peer,
 * deep-linking into the Events area at that specific event.
 *
 * Notification ids are derived from the event id so two distinct
 * events don't collapse into one banner. Mirrors
 * [AndroidPaymentNotifier] / [AndroidMessagingNotifier].
 */
@Singleton
class AndroidCoordinationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : CoordinationNotifier {

    private val nm = NotificationManagerCompat.from(context)
    private val systemNm = context.getSystemService(NotificationManager::class.java)

    init { ensureChannel() }

    override fun notifyReceivedEvent(eventId: ByteArray, title: String) {
        if (!nm.areNotificationsEnabled()) return

        val hex = eventId.joinToString("") { "%02x".format(it) }
        val notifId = eventId.contentHashCode() and 0x7FFFFFFF

        val openIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ACTION_OPEN_EVENT
                putExtra(EXTRA_EVENT_ID_HEX, hex)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("New event")
            .setContentText(if (title.isNotBlank()) title else "A peer shared an event")
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        runCatching { nm.notify(notifId, notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = systemNm ?: return
        // Drop the original DEFAULT-importance channel: its settings are
        // locked once created, so a fresh id is the only way to ship the
        // HIGH-importance (heads-up + sound) behaviour.
        runCatching { mgr.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Events",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Shown when a community member shares an event with you."
                setShowBadge(true)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "events_v2"
        private const val LEGACY_CHANNEL_ID = "events"
        const val ACTION_OPEN_EVENT = "com.wyspr.app.OPEN_EVENT"
        const val EXTRA_EVENT_ID_HEX = "event_id_hex"
    }
}
