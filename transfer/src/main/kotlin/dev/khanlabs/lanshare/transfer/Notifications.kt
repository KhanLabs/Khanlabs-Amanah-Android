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
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.khanlabs.lanshare.net.ChatMessage

/** The app's notifications: "Ready to receive", incoming sends to accept, and finished sends. */
object Notifications {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_OFFERS = "offers"
    const val CHANNEL_DONE = "done"
    const val CHANNEL_MESSAGES = "messages"
    /** Extra on the intent that opens the app on one conversation: the other device's ID. */
    const val EXTRA_CHAT = "dev.khanlabs.lanshare.CHAT"
    private const val MESSAGES_SHOWN = 6
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
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Texts from devices you write to"
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

    private fun chatTag(peer: String) = "chat:$peer"

    private fun openChat(context: Context, peer: String): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_CHAT, peer)
        return PendingIntent.getActivity(context, peer.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A button on a message notification. The Reply button must be mutable: Android fills the typed text into it. */
    private fun chatAction(context: Context, action: String, peer: String, code: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setAction(action).putExtra(ActionReceiver.EXTRA_CHAT, peer)
        val flag = when {
            !mutable -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
            else -> 0
        }
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or flag)
    }

    /**
     * The latest texts of one conversation, with Reply and Mark as read. Posting again replaces the notification;
     * [alert] false does that quietly (after you replied).
     */
    fun message(context: Context, peer: String, name: String, thread: List<ChatMessage>, alert: Boolean = true) {
        if (!allowed(context)) return
        val title = name.ifEmpty { thread.lastOrNull { it.name.isNotEmpty() }?.name ?: "Message" }
        val me = Person.Builder().setName("Me").build()
        val them = Person.Builder().setName(title).setKey(peer).build()
        val style = NotificationCompat.MessagingStyle(me)
        for (m in thread.takeLast(MESSAGES_SHOWN)) style.addMessage(m.text.take(500), m.at, if (m.incoming) them else me)
        val code = peer.hashCode()
        val reply = NotificationCompat.Action.Builder(0, "Reply", chatAction(context, ActionReceiver.REPLY, peer, code + 3, mutable = true))
            .addRemoteInput(RemoteInput.Builder(ActionReceiver.KEY_REPLY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(0, "Mark as read", chatAction(context, ActionReceiver.MARK_READ, peer, code + 4, mutable = false))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(thread.lastOrNull()?.text?.take(200) ?: "")
            .setStyle(style)
            .setContentIntent(openChat(context, peer))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .addAction(reply)
            .addAction(read)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(chatTag(peer), 0, n)
        } catch (e: SecurityException) {
            // notifications were turned off in the meantime
        }
    }

    fun cancelMessage(context: Context, peer: String) {
        NotificationManagerCompat.from(context).cancel(chatTag(peer), 0)
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
