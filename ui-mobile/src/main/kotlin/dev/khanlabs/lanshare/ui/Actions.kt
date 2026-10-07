package dev.khanlabs.lanshare.ui

import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import dev.khanlabs.lanshare.store.Stores
import dev.khanlabs.lanshare.transfer.Engine

/** What the person can do with files this app received. All of it goes through the system: no extra permissions. */

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

/** Opens one received file in whichever app handles its type. [saved] is the name the receiver reported. */
internal fun openSaved(context: Context, saved: String) {
    val uri = Stores.uriFor(context, saved) ?: return toast(context, "That file is no longer in the save folder.")
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Stores.mimeType(saved.substringAfterLast('/')))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(context, "No app on this phone can open that kind of file.")
    }
}

/** The system share sheet for received files, so they can go to chat, mail, cloud and so on. */
internal fun shareSaved(context: Context, saved: List<String>) {
    val uris = saved.mapNotNull { Stores.uriFor(context, it) }
    if (uris.isEmpty()) return toast(context, "Those files are no longer in the save folder.")
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(Stores.mimeType(saved[0].substringAfterLast('/')))
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("*/*")
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Shows the save folder (or a folder inside it) in the Files app: the folder the person chose, else Download/KhanLabs.
 * Falls back to the Downloads list.
 */
internal fun showFolder(context: Context, inside: String = "") {
    val path = listOf("Download", "KhanLabs", inside).filter { it.isNotEmpty() }.joinToString("/")
    val folder = Stores.folderUri(context, inside) ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    } catch (e: Exception) {
        // no Files app that takes a folder address
    }
    try {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(context, "Open your Files app and look in ${Engine.state.value.folder}.")
    }
}

/** True when the person has turned notifications off for this app, so offers would go unseen while the app is closed. */
internal fun notificationsOff(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 24) return false
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return !nm.areNotificationsEnabled()
}

/** Opens this app's notification page in Android Settings. */
internal fun openNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(context, "Open Settings, then Apps, then Amanah, then Notifications.")
    }
}

internal fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {  // TV boxes often have no browser
        toast(context, "No browser found. Visit $url on another device.")
    }
}
