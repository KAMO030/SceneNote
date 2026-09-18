package dev.scenenote.nmt

actual fun testEnv(name: String): String? = System.getenv(name)
