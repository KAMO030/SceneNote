package dev.scenenote.nmt

import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun testEnv(name: String): String? = getenv(name)?.toKString()
