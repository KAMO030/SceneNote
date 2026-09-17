package dev.scenenote.core.platform

import android.content.Context
import java.io.File

class AndroidAppPaths(context: Context) : AppPaths {
    private val ctx = context.applicationContext
    override val filesDir: String get() = ctx.filesDir.absolutePath
    override val cacheDir: String get() = ctx.cacheDir.absolutePath
    override fun ensureDir(path: String) { File(path).mkdirs() }
    override fun exists(path: String): Boolean = File(path).exists()
    override fun sizeBytes(path: String): Long = File(path).length()
    override fun listFiles(dir: String): List<String> = File(dir).listFiles()?.map { it.absolutePath }?.sorted() ?: emptyList()
    override fun delete(path: String): Boolean = File(path).delete()
    override fun readText(path: String): String? = File(path).takeIf { it.exists() }?.readText()
    override fun appendText(path: String, text: String) { File(path).appendText(text) }
}
