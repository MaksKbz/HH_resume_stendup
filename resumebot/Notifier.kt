package kz.hh.resumebot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Системные уведомления Android о результатах поднятия. */
object Notifier {

    private const val CHANNEL = "hh_raise"

    fun notify(ctx: Context, title: String, text: String, openApp: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Поднятие резюме HH", NotificationManager.IMPORTANCE_DEFAULT)
                )
                Notification.Builder(ctx, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }

            builder
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setAutoCancel(true)

            if (openApp) {
                val i = Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pi = PendingIntent.getActivity(
                    ctx, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentIntent(pi)
            }

            nm.notify((System.currentTimeMillis() % 100_000).toInt(), builder.build())
        } catch (_: Throwable) {
            // уведомления — не критично, игнорируем
        }
    }
}
