package app.amend.messenger.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.amend.messenger.MainActivity
import app.amend.messenger.R

object Notifications {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_FOREST = "forest"
    const val CHANNEL_CONNECTION = "connection"
    const val EXTRA_PEER = "peer"
    const val LIVE_ID = 4_242

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FOREST, context.getString(R.string.channel_forest), NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.channel_connection_description)
                setShowBadge(false)
            }
        )
    }

    /** Whether amend may post notifications (permission granted and not blocked in settings). */
    fun areEnabled(context: Context): Boolean {
        val permission = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permission && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openIntent(context: Context, peer: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            peer?.let { putExtra(EXTRA_PEER, it) }
        }
        return PendingIntent.getActivity(
            context, peer?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun idFor(peer: String) = peer.hashCode()

    /** One notification per chat; it updates with the unread count instead of stacking up. */
    fun message(context: Context, peer: String, sender: String, text: String, unread: Int) {
        if (!areEnabled(context)) return
        val title = if (unread > 1) "$sender · $unread new messages" else sender
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFF2BB673.toInt())
            .setNumber(unread)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.notif_hidden))
                    .build()
            )
            .setContentIntent(openIntent(context, peer))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(idFor(peer), n)
        } catch (e: SecurityException) {
            // permission revoked meanwhile
        }
    }

    fun cancel(context: Context, peer: String) {
        NotificationManagerCompat.from(context).cancel(idFor(peer))
    }

    fun treeGrown(context: Context, speciesName: String, count: Int) {
        if (!areEnabled(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_FOREST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_tree_title, speciesName))
            .setContentText(context.getString(R.string.notif_tree_text, count))
            .setColor(0xFF2BB673.toInt())
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(7_000 + count, n)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    /** The quiet, ongoing notification Android requires while amend keeps its connection open. */
    fun liveConnection(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_live_title))
            .setContentText(context.getString(R.string.notif_live_text))
            .setColor(0xFF2BB673.toInt())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent(context, null))
            .build()
}
