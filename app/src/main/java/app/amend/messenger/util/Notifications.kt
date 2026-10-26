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
import app.amend.messenger.util.CallPhase
import app.amend.messenger.util.CallUi

object Notifications {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_FOREST = "forest"
    const val CHANNEL_CONNECTION = "connection"
    const val EXTRA_PEER = "peer"
    const val LIVE_ID = 4_242
    const val CHANNEL_SOS_ALERTS = "sos_alerts"
    const val CHANNEL_SOS_ACTIVE = "sos_active"
    const val SOS_ACTIVE_ID = 9_110
    const val SOS_COUNTDOWN_ID = 9_111
    const val CHANNEL_WALKING = "walking"
    const val CHANNEL_CALLS = "calls_incoming"
    const val CHANNEL_ECO_ALERTS = "eco_alerts"
    const val MESH_ID = 5_200
    const val CHANNEL_CALL_ONGOING = "calls_ongoing"
    const val CALL_INCOMING_ID = 5_100
    const val CALL_ONGOING_ID = 5_101
    /** MainActivity extra: "show" (incoming call screen) or "answer". */
    const val EXTRA_CALL = "amend_call"
    private const val STEP_REPORT_ID = 6_100
    private const val STEP_GOAL_ID = 6_101

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
            NotificationChannel(CHANNEL_SOS_ALERTS, context.getString(R.string.channel_sos_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_sos_alerts_description)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 250, 600, 250, 600)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build(),
                )
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SOS_ACTIVE, context.getString(R.string.channel_sos_active), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            // amend plays the ringtone itself (looping), so the channel stays silent
            NotificationChannel(CHANNEL_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ECO_ALERTS, "Weather & air alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Heatwaves, heavy rain, rising rivers, storms and unhealthy air near you"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL_ONGOING, "Ongoing call", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WALKING, "Walking", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Daily step goal and the monthly walking report"
            }
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

    // ------------------------------------------------------------------ SOS

    private fun sosIdFor(peer: String) = 20_000 + (peer.hashCode() and 0xffff)

    /** Loud alert on a friend's phone: they started (or updated, or ended) an SOS. */
    fun sosAlert(context: Context, peer: String, name: String, sos: app.amend.messenger.data.IncomingSos, phase: app.amend.messenger.data.SosPhase) {
        if (!areEnabled(context)) return
        val title: String
        val text: String
        when (phase) {
            app.amend.messenger.data.SosPhase.STOP -> {
                title = "✅ $name is safe now"
                text = "Their SOS has ended."
            }
            else -> {
                title = "🆘 $name needs help!"
                text = sos.location?.let { "Live location shared · ±${it.accuracyM.toInt()} m. Tap to see where they are." }
                    ?: "They started an SOS. Location is on the way."
            }
        }
        val builder = NotificationCompat.Builder(context, if (phase == app.amend.messenger.data.SosPhase.STOP) CHANNEL_MESSAGES else CHANNEL_SOS_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFFE53935.toInt())
            .setColorized(phase != app.amend.messenger.data.SosPhase.STOP)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(phase == app.amend.messenger.data.SosPhase.STOP)
            .setOngoing(phase != app.amend.messenger.data.SosPhase.STOP)
            .setOnlyAlertOnce(phase == app.amend.messenger.data.SosPhase.UPDATE)
            .setContentIntent(openIntent(context, peer))
        if (phase != app.amend.messenger.data.SosPhase.STOP) {
            sos.location?.let { loc ->
                val map = PendingIntent.getActivity(
                    context, sosIdFor(peer),
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(loc.geoUri())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, "Open map", map)
            }
        }
        try {
            NotificationManagerCompat.from(context).notify(sosIdFor(peer), builder.build())
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun sosCountdown(context: Context, secondsLeft: Int) {
        if (!areEnabled(context)) return
        val cancel = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, SosActionReceiver::class.java).setAction(SosActionReceiver.ACTION_CANCEL_COUNTDOWN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_SOS_ACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🆘 Sending SOS in $secondsLeft…")
            .setContentText("Your live location will go to your contacts. Cancel if this was a mistake.")
            .setColor(0xFFE53935.toInt())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancel)
            .setContentIntent(openIntent(context, null))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(SOS_COUNTDOWN_ID, n)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun cancelSosCountdown(context: Context) = NotificationManagerCompat.from(context).cancel(SOS_COUNTDOWN_ID)

    /** Ongoing notification while my SOS is active, with a one-tap "Call" action. */
    fun sosActiveNotification(context: Context, reached: Int, total: Int, number: String): Notification {
        val dial = PendingIntent.getActivity(
            context, 2,
            Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_SOS_ACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🆘 SOS active — sharing live location")
            .setContentText(if (total > 0) "Reached $reached of $total contacts. Open amend to stop." else "Open amend to see status or stop.")
            .setColor(0xFFE53935.toInt())
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(0, "Call $number", dial)
            .setContentIntent(openIntent(context, null))
            .build()
    }

    fun sosActive(context: Context, reached: Int, total: Int, number: String) {
        if (!areEnabled(context)) return
        try {
            NotificationManagerCompat.from(context).notify(SOS_ACTIVE_ID, sosActiveNotification(context, reached, total, number))
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun cancelSosActive(context: Context) = NotificationManagerCompat.from(context).cancel(SOS_ACTIVE_ID)

    fun cancelSosAlert(context: Context, peer: String) = NotificationManagerCompat.from(context).cancel(sosIdFor(peer))

    // ------------------------------------------------------------ walking

    fun stepReport(context: Context, report: app.amend.messenger.data.StepReportMessage) {
        if (!areEnabled(context)) return
        val r = report.report
        val text = "%,d steps · %s km — tap to see the full report".format(
            java.util.Locale.US, r.totalSteps, app.amend.messenger.data.StepLog.formatKm(r.km),
        )
        val n = NotificationCompat.Builder(context, CHANNEL_WALKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("👣 Your " + app.amend.messenger.data.StepLog.monthName(report.month) + " walking report")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(report.text))
            .setColor(0xFF2BB673.toInt())
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, StepTracker.REPORTS_PEER))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(STEP_REPORT_ID, n)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    fun cancelStepReport(context: Context) = NotificationManagerCompat.from(context).cancel(STEP_REPORT_ID)

    fun stepGoal(context: Context, steps: Int, goal: Int) {
        if (!areEnabled(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_WALKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🎉 Daily goal reached!")
            .setContentText("%,d steps today — your %,d-step goal is done. 🌿".format(java.util.Locale.US, steps, goal))
            .setColor(0xFF2BB673.toInt())
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(STEP_GOAL_ID, n)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    // ------------------------------------------------------------ calls

    private fun callActivityIntent(context: Context, mode: String, code: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL, mode)
        }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun callAction(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code, Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Full-screen ringing notification (shows over the lock screen). */
    fun incomingCall(context: Context, peer: String, name: String, video: Boolean) {
        val person = androidx.core.app.Person.Builder().setName(name).setKey(peer).setImportant(true).build()
        val n = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(if (video) "Incoming amend video call" else "Incoming amend voice call")
            .setColor(0xFF2BB673.toInt())
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(app.amend.messenger.data.CallSignal.MAX_RING_SECONDS * 1000)
            .setContentIntent(callActivityIntent(context, "show", 51))
            .setFullScreenIntent(callActivityIntent(context, "show", 52), true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    callAction(context, CallActionReceiver.ACTION_DECLINE, 53),
                    callActivityIntent(context, "answer", 54),
                ).setIsVideo(video)
            )
            .build()
        try {
            NotificationManagerCompat.from(context).notify(CALL_INCOMING_ID, n)
        } catch (e: SecurityException) {
            // no notification permission: the call still rings while amend is open
        }
    }

    fun cancelIncomingCall(context: Context) = NotificationManagerCompat.from(context).cancel(CALL_INCOMING_ID)

    /** The notification shown while a call is running (the call service's foreground notification). */
    fun ongoingCall(context: Context, st: CallUi): Notification {
        val name = (context.applicationContext as? app.amend.messenger.AmendApp)?.repository?.value?.displayName(st.peer) ?: "amend"
        val person = androidx.core.app.Person.Builder().setName(name).setKey(st.peer.ifBlank { "amend" }).build()
        val text = when (st.phase) {
            CallPhase.OUTGOING -> if (st.ringing) "Ringing…" else "Calling…"
            CallPhase.CONNECTING -> "Connecting…"
            else -> if (st.video) "amend video call" else "amend voice call"
        }
        val b = NotificationCompat.Builder(context, CHANNEL_CALL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(text)
            .setColor(0xFF2BB673.toInt())
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(callActivityIntent(context, "show", 55))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, callAction(context, CallActionReceiver.ACTION_HANGUP, 56)).setIsVideo(st.video))
        if (st.connectedAt > 0) {
            b.setUsesChronometer(true).setWhen(st.connectedAt * 1000).setShowWhen(true)
        }
        return b.build()
    }

    // ------------------------------------------------------------ weather & air

    fun ecoAlert(context: Context, alert: app.amend.messenger.data.EcoAlert, place: String) {
        if (!areEnabled(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_ECO_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(alert.emoji + " " + alert.title + if (place.isNotBlank()) " · $place" else "")
            .setContentText(alert.advice)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.advice + "\n\nOpen amend to tell your people you're safe."))
            .setColor(if (alert.level == app.amend.messenger.data.AlertLevel.DANGER) 0xFFE53935.toInt() else 0xFFFFA000.toInt())
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, null))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(8_000 + (alert.id.hashCode() and 0xff), n)
        } catch (e: SecurityException) {
            // ignore
        }
    }

    // ------------------------------------------------------------ offline mesh

    fun meshRunning(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Offline mesh is on")
            .setContentText("Passing encrypted messages between nearby amend phones — works without internet.")
            .setColor(0xFF2BB673.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent(context, null))
            .build()
}
