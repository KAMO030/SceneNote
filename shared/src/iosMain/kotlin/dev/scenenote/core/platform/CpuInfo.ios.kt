package dev.scenenote.core.platform

import platform.Foundation.NSProcessInfo

actual object CpuInfo {
    /** activeProcessorCount 而不是 processorCount：省电模式下系统会关掉一部分核。 */
    actual fun cores(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()
}
