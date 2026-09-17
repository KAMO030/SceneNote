package dev.scenenote.core.platform

/** 进程常驻内存与设备总内存（字节）。 */
expect object MemoryInfo {
    fun residentBytes(): Long
    /** 设备物理内存总量；取不到返回 -1。 */
    fun totalBytes(): Long
}

/** 05 篇 §7.1 内存分级：决定实时档驻留哪些模型（vivo V2054A 3.6 GB 实测：zipformer + VAD + SenseVoice 已 700 MB PSS）。 */
enum class MemoryTier {
    LOW, MID, HIGH;

    companion object {
        fun of(totalBytes: Long): MemoryTier = when {
            totalBytes <= 0 -> MID
            totalBytes < 4L * 1024 * 1024 * 1024 -> LOW
            totalBytes < 6L * 1024 * 1024 * 1024 -> MID
            else -> HIGH
        }
        val current: MemoryTier by lazy { of(MemoryInfo.totalBytes()) }
    }
}
