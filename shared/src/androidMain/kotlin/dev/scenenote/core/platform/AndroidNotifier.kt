package dev.scenenote.core.platform

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Android 本地通知（I7）：AlarmManager 定时（非精确，不需要 SCHEDULE_EXACT_ALARM）→ [NotifyReceiver] 发通知；
 * 点开走深链（VIEW scenenote://…）回 MainActivity。权限由 MainActivity 启动时申请（POST_NOTIFICATIONS），这里只查结果。
 */
class AndroidNotifier(context: Context) : Notifier {
    private val ctx = context.applicationContext
    private val alarms get() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override suspend fun requestPermission(): Boolean = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    override fun schedule(id: String, atEpochMs: Long, title: String, body: String, deepLink: String) {
        ensureChannel(ctx)
        val pi = pending(id, title, body, deepLink)
        runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pi) }
    }

    override fun cancel(id: String) {
        runCatching { alarms.cancel(pending(id, "", "", "")) }
        runCatching { NotificationManagerCompat.from(ctx).cancel(id.hashCode()) }
    }

    private fun pending(id: String, title: String, body: String, deepLink: String): PendingIntent {
        val i = Intent(ctx, NotifyReceiver::class.java).setAction("dev.scenenote.NOTIFY").setData(Uri.parse("scenenote-notify://$id"))
            .putExtra("title", title).putExtra("body", body).putExtra("deepLink", deepLink).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL = "reminders"
        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "提醒", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "录音后的纪要与回顾提醒" })
        }
    }
}

/** 到点发通知；点开 = VIEW 深链（由 MainActivity 的 intent-filter 接住）。 */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val title = intent.getStringExtra("title") ?: return
        val body = intent.getStringExtra("body") ?: ""
        val link = intent.getStringExtra("deepLink") ?: return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        AndroidNotifier.ensureChannel(context)
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(context, AndroidNotifier.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id.hashCode(), n) }
    }
}
