package dev.scenenote.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * scenenote:// 深链（07 篇 §7.10：scheme 统一 scenenote://）。
 * 当前支持：scenenote://selftest?autostart=1、scenenote://scene/{id}、scenenote://settings。
 * v1.1：scenenote://join?host&port&pk&salt&lang（M8）。
 */
data class DeepLink(val host: String, val path: List<String>, val query: Map<String, String>, val raw: String)

object DeepLinks {
    private val _pending = MutableStateFlow<DeepLink?>(null)
    val pending: StateFlow<DeepLink?> = _pending.asStateFlow()

    fun handle(url: String) { parse(url)?.let { _pending.value = it } }
    fun consume() { _pending.value = null }

    fun parse(url: String): DeepLink? {
        val prefix = "scenenote://"
        if (!url.startsWith(prefix, ignoreCase = true)) return null
        val rest = url.substring(prefix.length)
        val (pathPart, queryPart) = rest.split("?", limit = 2).let { it[0] to it.getOrNull(1) }
        val segs = pathPart.split("/").filter { it.isNotBlank() }
        if (segs.isEmpty()) return null
        val query = queryPart?.split("&")?.filter { it.isNotBlank() }?.associate { kv ->
            val (k, v) = kv.split("=", limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
            k to v
        } ?: emptyMap()
        return DeepLink(host = segs.first().lowercase(), path = segs.drop(1), query = query, raw = url)
    }
}
