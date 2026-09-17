package dev.scenenote.meeting

import dev.scenenote.core.platform.Notifier
import kotlin.time.Clock

/** 录后提醒（I7）：10 分钟「纪要整理好了」→ 纪要页；3 天「还没分享」→ 纪要页。打开纪要页即撤销 3 天那条。 */
object Reminders {
    fun tenMinId(sessionId: String) = "meeting-$sessionId-10m"
    fun threeDayId(sessionId: String) = "meeting-$sessionId-3d"

    suspend fun afterMeeting(notifier: Notifier, sessionId: String) {
        if (!notifier.requestPermission()) return
        val now = Clock.System.now().toEpochMilliseconds()
        val link = "scenenote://note/$sessionId"
        notifier.schedule(tenMinId(sessionId), now + 10 * 60_000L, "纪要整理好了", "点开看看，顺手分享给同事", link)
        notifier.schedule(threeDayId(sessionId), now + 3 * 86_400_000L, "这份纪要还没分享", "三天前的会议，要不要发出去", link)
    }

    fun opened(notifier: Notifier, sessionId: String) { notifier.cancel(threeDayId(sessionId)) }
}
