package com.keystone.app.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.keystone.app.MainActivity
import com.keystone.app.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that keeps Keystone's transport stack alive
 * while the user is in a flow that needs the radio (BLE handshake,
 * peer-to-peer APK share). Without this, Android 14's tightened
 * background limits would tear down the GATT server / HTTPS listener
 * within ~30s of the user backgrounding the app — a recipient
 * mid-handshake or mid-download would silently get cut off.
 *
 * ## Reference counting
 *
 * Multiple features can each call [start] for their own reason
 * (handshake AND share are orthogonal, and a user could in
 * principle have both open). The service maintains an
 * [AtomicInteger] reference count; the foreground notification
 * persists as long as the count is positive, and the service
 * stops itself when the count drops back to zero. Reasons that
 * fail to balance are logged via the notification "Stop" action,
 * which forcibly resets the count.
 *
 * ## Foreground service type
 *
 * Declared `connectedDevice` in the manifest. On Android 14+
 * this type requires BLUETOOTH_CONNECT — which the BLE handshake
 * flow already requests — and constrains the service to genuinely
 * connected-device work (no general-purpose background CPU).
 *
 * ## Notification UX
 *
 * Single ongoing notification on a low-importance channel. Small
 * icon is the monochrome keystone-arch glyph. Tapping the body
 * returns the user to MainActivity; tapping "Stop" stops the
 * service immediately. The notification cannot be swiped away
 * while the service is running (foreground service guarantee).
 */
class TransportForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val reason = intent.getStringExtra(EXTRA_REASON)?.let {
                    runCatching { Reason.valueOf(it) }.getOrNull()
                } ?: Reason.Transport
                ensureChannel()
                refCount.incrementAndGet()
                startForegroundCompat(reason)
            }
            ACTION_STOP -> {
                val count = refCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                if (count == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP_ALL -> {
                refCount.set(0)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(reason: Reason) {
        val notification = buildNotification(reason)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while Keystone is keeping a Bluetooth or " +
                    "WiFi connection alive on your behalf."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(reason: Reason): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAll = PendingIntent.getService(
            this,
            1,
            Intent(this, TransportForegroundService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, body) = when (reason) {
            Reason.Handshake -> "Keystone" to "Pairing in progress"
            Reason.Sharing -> "Keystone" to "Sharing app with a peer"
            Reason.Transport -> "Keystone" to "Connection active"
            Reason.WalletSync -> "Keystone" to "Wallet syncing in background"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stopAll)
            .build()
    }

    enum class Reason { Handshake, Sharing, Transport, WalletSync }

    companion object {
        private const val ACTION_START = "com.keystone.transport.START"
        private const val ACTION_STOP = "com.keystone.transport.STOP"
        private const val ACTION_STOP_ALL = "com.keystone.transport.STOP_ALL"
        private const val EXTRA_REASON = "reason"
        private const val CHANNEL_ID = "transport"
        private const val NOTIFICATION_ID = 1

        /**
         * Tracks the number of outstanding [start] / [stop] pairs
         * across features. Single-process app, so a plain
         * AtomicInteger is enough — no IPC complexity.
         */
        private val refCount = AtomicInteger(0)

        fun start(context: Context, reason: Reason) {
            val intent = Intent(context, TransportForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_REASON, reason.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TransportForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            // Plain startService — we're not promoting to foreground
            // again, just letting the existing instance decrement its
            // ref count and stop itself if it hits zero.
            runCatching { context.startService(intent) }
        }
    }
}
