package dev.scenenote.core.platform

import android.os.Debug
import java.io.File

actual object MemoryInfo {
    /** PSS（KB → B）：含原生堆里的 onnxruntime 模型。 */
    actual fun residentBytes(): Long = Debug.getPss() * 1024L

    /** /proc/meminfo 的 MemTotal（不需要 Context）。 */
    actual fun totalBytes(): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024L) ?: -1L
        }
    }.getOrDefault(-1L)
}
