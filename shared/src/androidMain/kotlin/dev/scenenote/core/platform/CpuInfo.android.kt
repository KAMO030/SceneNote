package dev.scenenote.core.platform

actual object CpuInfo {
    actual fun cores(): Int = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(-1)
}
