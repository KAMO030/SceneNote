package dev.scenenote.core.platform

/** 平台目录（Vault 真相源在 files/vault；基准与自检文件在 files/bench）。 */
interface AppPaths {
    val filesDir: String
    val cacheDir: String
    fun join(vararg parts: String): String = parts.joinToString("/") { it.trimEnd('/') }
    fun ensureDir(path: String)
    fun exists(path: String): Boolean
    fun sizeBytes(path: String): Long
    fun listFiles(dir: String): List<String>
    fun delete(path: String): Boolean
    fun readText(path: String): String?
    fun appendText(path: String, text: String)

    val vaultDir: String get() = join(filesDir, "vault")
    val benchDir: String get() = join(filesDir, "bench")
}
