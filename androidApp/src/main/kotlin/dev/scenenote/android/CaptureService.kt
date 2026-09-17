package dev.scenenote.android

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import dev.scenenote.app.R
import dev.scenenote.screen.AndroidSystemAudioCapture

/**
 * 系统字幕（Android S1）的前台服务：类型 `mediaProjection|microphone`。
 * 只做三件事：① startForeground（Android 14+ 必须先于 getMediaProjection）；② 用 MainActivity 拿到的
 * resultCode / data 取 MediaProjection；③ 交给共享层 [AndroidSystemAudioCapture] 建 AudioRecord。
 * 音频线程、可抓性探测、MediaProjection 回调都在共享层；服务本身不持有 projection。
 * 通知：渠道「系统字幕」，一条常驻 + 「停止」动作；点通知回到 App。
 */
class CaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val capture = AndroidSystemAudioCapture.active
        val code = intent.getIntExtra(AndroidSystemAudioCapture.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, AndroidSystemAudioCapture.EXTRA_RESULT_DATA, Intent::class.java)
        if (capture == null || code != Activity.RESULT_OK || data == null || Build.VERSION.SDK_INT < 29) {
            capture?.onServiceFailed("bad start args")
            stopSelf(); return START_NOT_STICKY
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (t: Throwable) {
            capture.onServiceFailed("startForeground: ${t.message}")
            stopSelf(); return START_NOT_STICKY
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (projection == null || !capture.onProjectionReady(projection)) {
            if (projection == null) capture.onServiceFailed("getMediaProjection returned null")
            stopSelf(); return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AndroidSystemAudioCapture.active?.onServiceDestroyed()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "系统字幕", NotificationManager.IMPORTANCE_LOW).apply {
                description = "正在抓取其他应用的声音并显示字幕时常驻"
                setShowBadge(false)
            })
        }
    }

    private fun buildNotification(): android.app.Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_listen)
            .setContentTitle("系统字幕")
            .setContentText("正在聆听…")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { open?.let { setContentIntent(it) } }
            .addAction(0, "停止", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "dev.scenenote.android.action.STOP_CAPTURE"
        private const val CHANNEL_ID = "screen_caption"
        private const val NOTIFICATION_ID = 2001
    }
}
