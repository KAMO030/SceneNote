package dev.scenenote.core.scene

import com.russhwolf.settings.Settings
import dev.scenenote.core.model.LangChip
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.model.RoutePolicy
import dev.scenenote.core.model.ScenePreset
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.model.Style
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** 用户自定义场景（「复制一张再改」，01 篇：高级选项只出现在这里；`.scene` 导入导出 v1.1）。 */
@Serializable data class CustomScene(
    val id: String, val baseId: String, val name: String,
    val myLang: String, val otherLang: String, val style: Style, val route: RoutePolicy, val privacyId: String,
    val bucket: String, val createdAt: Long,
)

/** 路由 × 联网权限的合法性（原型 SceneEdit：「优先云端」× 不联网 / 只发文字 → 行内提示，不弹窗）。 */
object SceneRules {
    fun problem(route: RoutePolicy, privacy: PrivacyMode): String? = when {
        route == RoutePolicy.CLOUD_FIRST && privacy is PrivacyMode.Locked -> "优先云端需要联网权限"
        route == RoutePolicy.CLOUD_FIRST && !privacy.allowsInternetAudio -> "云端识别要上传录音，联网权限得选「文字和录音」"
        else -> null
    }
}

class SceneStore(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _custom = MutableStateFlow(load())
    val custom: StateFlow<List<CustomScene>> = _custom.asStateFlow()

    private fun load(): List<CustomScene> = settings.getStringOrNull(KEY)?.let { runCatching { json.decodeFromString<List<CustomScene>>(it) }.getOrNull() }.orEmpty()
    private fun save(list: List<CustomScene>) { settings.putString(KEY, json.encodeToString(list)); _custom.value = list }

    fun copyOf(base: ScenePreset, name: String = "${base.name} 副本"): CustomScene = CustomScene(
        id = "custom-${Uuid.random()}", baseId = base.id, name = name,
        myLang = base.langChips.firstOrNull { it.default }?.tag ?: "zh-CN", otherLang = base.translationTargets.firstOrNull() ?: "en",
        style = base.style, route = base.route, privacyId = PrivacyMode.idOf(base.privacy), bucket = base.hotwordBucket, createdAt = Clock.System.now().toEpochMilliseconds(),
    )
    fun upsert(s: CustomScene) = save(_custom.value.filter { it.id != s.id } + s)
    fun delete(id: String) = save(_custom.value.filter { it.id != id })
    fun byId(id: String): CustomScene? = _custom.value.firstOrNull { it.id == id }

    /** 自定义场景 → 运行时预设（在基础预设上覆盖语言 / 风格 / 路由 / 隐私 / 词袋）；内置 id 原样返回。 */
    fun resolve(id: String): ScenePreset? {
        val c = byId(id) ?: return Scenes.byId(id)
        val base = Scenes.byId(c.baseId) ?: return null
        return base.copy(id = c.id, name = c.name, style = c.style, route = c.route, privacy = PrivacyMode.fromId(c.privacyId), hotwordBucket = c.bucket,
            langChips = listOf(LangChip(c.myLang, default = true, shownOnCard = true), LangChip(c.otherLang, shownOnCard = true)), translationTargets = listOf(c.otherLang))
    }

    private companion object { const val KEY = "custom_scenes" }
}
