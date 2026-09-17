package dev.scenenote.core.platform

/** 本地通知（I7）：录后 10 分钟「纪要整理好了」/ 3 天「还没分享」。deepLink 点开进对应产物页。 */
interface Notifier {
    /** 首次用前申请权限（Android 13+ POST_NOTIFICATIONS / iOS UNUserNotificationCenter）。 */
    suspend fun requestPermission(): Boolean
    fun schedule(id: String, atEpochMs: Long, title: String, body: String, deepLink: String)
    fun cancel(id: String)
}
class NoopNotifier : Notifier {
    override suspend fun requestPermission() = false
    override fun schedule(id: String, atEpochMs: Long, title: String, body: String, deepLink: String) {}
    override fun cancel(id: String) {}
}
