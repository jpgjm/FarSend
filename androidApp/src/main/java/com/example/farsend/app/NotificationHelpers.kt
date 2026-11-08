package com.example.farsend.app

import com.example.farsend.AppInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/*
 * Notification code shared by IncomingNotifierImpl, TransferNotifierImpl and NodeService,
 * which used to repeat it. Behaviour is unchanged.
 */

/**
 * Creates a notification channel (no longer checks for existence first).
 * For an existing channel only the name and description are updated; importance changed by the user is kept
 * (AOSP android16-release PreferencesHelper.createNotificationChannel).
 */
internal fun NotificationManager.ensureChannel(id: String, name: String, importance: Int) {
    createNotificationChannel(NotificationChannel(id, name, importance))
    Log.d("${AppInfo.NAME}/Notify", "notification channel ensured: $id")
}

/**
 * Posts a notification and logs `what` at debug level (nothing if null).
 * no longer catches SecurityException. Without POST_NOTIFICATIONS the system silently
 * drops the notification instead of throwing (AOSP android16-release NotificationManagerService.enqueueNotificationInternal).
 */
internal fun NotificationManager.notifyLogged(tag: String, id: Int, notification: Notification, what: String? = null) {
    notify(id, notification)
    what?.let { Log.d(tag, it) }
}

/**
 * Broadcast action name for notification buttons (`<package name>.<name>`).
 * Built from the package name instead of hard-coding "com.example.farsend.ACCEPT_OFFER" etc.,
 * so they follow the applicationId.
 */
internal fun Context.appAction(name: String): String = "$packageName.$name"

/** PendingIntent that opens the app (MainActivity) when the notification is tapped. */
internal fun Context.openAppIntent(requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        this, requestCode, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
