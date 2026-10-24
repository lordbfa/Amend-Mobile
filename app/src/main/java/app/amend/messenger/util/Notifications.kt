package app.amend.messenger.util

import android.Manifest
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
    const val EXTRA_PEER = "peer"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FOREST, context.getString(R.string.channel_forest), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openIntent(context: Context, peer: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            peer?.let { putExtra(EXTRA_PEER, it) }
        }
        return PendingIntent.getActivity(
            context, peer?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun message(context: Context, peer: String, sender: String, text: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sender)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFF2BB673.toInt())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openIntent(context, peer))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(peer.hashCode(), n)
        } catch (e: SecurityException) {
            // permission revoked meanwhile
        }
    }

    fun treeGrown(context: Context, speciesName: String, count: Int) {
        if (!canPost(context)) return
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
}
