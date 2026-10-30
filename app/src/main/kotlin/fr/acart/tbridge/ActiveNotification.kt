package fr.acart.tbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Date

/**
 * Notification silencieuse et persistante (glyphe de pont dans la status bar) affichée
 * tant que tbridge accepte des commandes : service connecté ET fenêtre d'écoute ouverte.
 * Pilotée par [TalkBackGestureService].
 */
internal object ActiveNotification {

    // IMPORTANCE_DEFAULT (not LOW): Pixel hides the status bar icon of "silent" (LOW)
    // notifications. Quiet anyway: no sound, no vibration, and DEFAULT never pops up.
    // A channel's importance can't change after creation, hence the new id.
    private const val CHANNEL_ID = "tbridge_active_status"
    private const val LEGACY_CHANNEL_ID = "tbridge_active"
    private const val NOTIFICATION_ID = 1

    fun show(context: Context, activeUntilMs: Long) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val until = DateFormat.getTimeFormat(context).format(Date(activeUntilMs))

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_text, until))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Live countdown to the end of the listening window.
            .setWhen(activeUntilMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            // System-side removal at expiry, even if the service's Handler is delayed by deep sleep.
            .setTimeoutAfter((activeUntilMs - System.currentTimeMillis()).coerceAtLeast(1L))
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
