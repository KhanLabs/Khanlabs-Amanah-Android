package dev.khanlabs.lanshare.transfer

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

/** The app's notifications: "Ready to receive", incoming sends to accept, and finished sends. */
object Notifications {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_OFFERS = "offers"
    const val CHANNEL_DONE = "done"
    const val SERVICE_ID = 1

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_SERVICE, "Ready to receive", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this device can receive files"
                setShowBadge(false)
            },
            NotificationChannel(CHANNEL_OFFERS, "Incoming sends", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Someone wants to send you files or text"
            },
            NotificationChannel(CHANNEL_DONE, "Finished sends", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Files and text you received"
            },
        ))
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(context: Context, action: String, offerId: String, code: Int): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setAction(action).putExtra(ActionReceiver.EXTRA_OFFER, offerId)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun offerTag(offerId: String) = "offer:$offerId"

    fun service(context: Context, text: String) = NotificationCompat.Builder(context, CHANNEL_SERVICE)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle("Ready to receive")
        .setContentText(text)
        .setContentIntent(openApp(context))
        .setOngoing(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    fun offer(context: Context, v: IncomingView) {
        if (!allowed(context)) return
        val what = when {
            v.fileCount == 0 -> "a message"
            v.fileCount == 1 -> "1 file (${Format.size(v.total)})"
            else -> "${v.fileCount} files (${Format.size(v.total)})"
        }
        val idText = if (v.senderShortId.isNotEmpty()) " (new device, ID ${v.senderShortId})" else " (new device)"
        val body = buildString {
            append("${v.senderName}$idText wants to send you $what.")
            if (v.text.isNotEmpty()) append("\n\n").append(v.text.take(500))
        }
        val code = v.id.hashCode()
        val n = NotificationCompat.Builder(context, CHANNEL_OFFERS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("${v.senderName} wants to send $what")
            .setContentText("Tap Accept to receive it")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .addAction(0, "Accept", action(context, ActionReceiver.ACCEPT, v.id, code))
            .addAction(0, "Decline", action(context, ActionReceiver.DECLINE, v.id, code + 1))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(offerTag(v.id), 0, n)
        } catch (e: SecurityException) {
            // notifications were turned off in the meantime
        }
    }

    fun cancelOffer(context: Context, offerId: String) {
        NotificationManagerCompat.from(context).cancel(offerTag(offerId), 0)
    }

    fun received(context: Context, v: IncomingView) {
        if (!allowed(context)) return
        val title = when {
            v.saved.isEmpty() -> "Message from ${v.senderName}"
            v.saved.size == 1 -> "Received ${v.saved[0].substringAfterLast('/')} from ${v.senderName}"
            else -> "Received ${v.saved.size} files from ${v.senderName}"
        }
        val text = if (v.text.isNotEmpty()) v.text else "Saved in Download/KhanLabs"
        val b = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        if (v.text.isNotEmpty()) b.addAction(0, "Copy text", action(context, ActionReceiver.COPY, v.id, v.id.hashCode() + 2))
        try {
            NotificationManagerCompat.from(context).notify("done:${v.id}", 0, b.build())
        } catch (e: SecurityException) {
            // notifications were turned off in the meantime
        }
    }
}

object Format {
    fun size(n: Long): String {
        if (n < 1024) return "$n B"
        var v = n.toDouble()
        for (unit in listOf("KB", "MB", "GB", "TB")) {
            v /= 1024
            if (v < 1024 || unit == "TB") return String.format(java.util.Locale.US, "%.1f %s", v, unit)
        }
        return "$n B"
    }

    fun speed(bytesPerSecond: Double): String = if (bytesPerSecond <= 0) "" else size(bytesPerSecond.toLong()) + "/s"

    fun eta(seconds: Long?): String = when {
        seconds == null -> ""
        seconds < 60 -> "${seconds}s left"
        seconds < 3600 -> "${seconds / 60} min left"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min left"
    }
}
