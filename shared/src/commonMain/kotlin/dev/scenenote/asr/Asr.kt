package dev.scenenote.asr

import dev.scenenote.core.model.EngineInfo
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.model.RoutePolicy
import dev.scenenote.core.model.ScenePreset
import dev.scenenote.core.model.Segment
import kotlinx.coroutines.flow.Flow

// ---------- core:asr（附录 A.1；06 篇 §7.5 端云切换） ----------

sealed interface AsrEvent {
    data class Partial(val text: String, val startMs: Long) : AsrEvent
    data class Final(val segment: Segment) : AsrEvent
    data class Error(val message: String, val recoverable: Boolean) : AsrEvent
    /** VAD 判定开始 / 结束说话（ducking 与打断用，规格 §5.4）。 */
    data class SpeechStart(val atMs: Long) : AsrEvent
    data class SpeechEnd(val atMs: Long) : AsrEvent
    /** 一句话的完整音频（VAD 段，16 kHz float）：声纹判向用；可能晚于 Final 到达。 */
    class UtteranceAudio(val utteranceId: String, val samples: FloatArray) : AsrEvent
}

interface StreamingAsrSession {
    fun push(frame: ShortArray)
    fun endOfInput()
    /** TURN_END：远端流强制端点。 */
    fun forceEndpoint()
    val events: Flow<AsrEvent>
}

interface AsrEngine {
    val info: EngineInfo
    val langs: Set<String>
    val cloud: Boolean
    val streaming: Boolean
    fun covers(lang: String): Boolean = lang in langs
    suspend fun openStream(lang: String, hotwords: List<String> = emptyList()): StreamingAsrSession
    suspend fun transcribe(pcm16k: ShortArray, lang: String): List<Segment>
}

enum class RouteKind { LocalOnly, LocalFinalCloudOptional, LocalDraftCloudRefine, CloudOnly, CloudFirst, Refused }

data class Route(val kind: RouteKind, val streaming: AsrEngine?, val finalize: AsrEngine?, val refine: AsrEngine?, val reason: String? = null)

data class SelectorContext(
    val privacy: PrivacyMode,
    val lang: String,
    val online: Boolean,
    val hasCloudKey: Boolean,
    val spendBlocked: Boolean = false,
    val localEngines: List<AsrEngine> = emptyList(),
    val cloudEngines: List<AsrEngine> = emptyList(),
) {
    fun localCovering(): AsrEngine? = localEngines.firstOrNull { it.covers(lang) }
    fun cloudCovering(): AsrEngine? = cloudEngines.firstOrNull { it.covers(lang) }
    /** 端侧是否覆盖该语言（无引擎实例时按 MVP 覆盖表判断）。 */
    val coversPairLocally: Boolean get() = if (localEngines.isEmpty()) lang in Lang.localCoverage else localCovering() != null
}

interface EngineSelector { fun decide(scene: ScenePreset, ctx: SelectorContext): Route }

/**
 * RoutePolicy × PrivacyMode 3×4 组合矩阵（02 篇 §2.5 / 06 篇 §7.5）。
 * 云端可用 = 在线 && 有 Key && 未触发消费闸门 && 隐私档允许音频上云（CloudOnly/CloudFirst/精修都走音频）。
 */
class DefaultEngineSelector : EngineSelector {
    override fun decide(scene: ScenePreset, ctx: SelectorContext): Route {
        val local = ctx.localCovering()
        val cloud = ctx.cloudCovering()
        val localOk = ctx.coversPairLocally
        val cloudAllowed = ctx.privacy.allowsInternetAudio && ctx.online && ctx.hasCloudKey && !ctx.spendBlocked && (ctx.cloudEngines.isEmpty() || cloud != null)
        val consent = ctx.privacy is PrivacyMode.LocalWithPerSegmentConsent

        return when (scene.route) {
            RoutePolicy.LOCAL_ONLY -> if (localOk) Route(RouteKind.LocalOnly, local, local, null)
                else Route(RouteKind.Refused, null, null, null, "端侧不覆盖 ${Lang.displayName(ctx.lang)}，且场景锁定仅本机")

            RoutePolicy.AUTO -> when {
                ctx.privacy is PrivacyMode.Locked -> if (localOk) Route(RouteKind.LocalOnly, local, local, null)
                    else Route(RouteKind.Refused, null, null, null, "隐私锁定，端侧不覆盖 ${Lang.displayName(ctx.lang)}")
                localOk && consent -> Route(RouteKind.LocalFinalCloudOptional, local, local, cloud, "逐段授权后可云端精修")
                localOk && cloudAllowed && scene.refineByDefault -> Route(RouteKind.LocalDraftCloudRefine, local, local, cloud)
                localOk -> Route(RouteKind.LocalFinalCloudOptional, local, local, if (cloudAllowed) cloud else null)
                cloudAllowed -> Route(RouteKind.CloudOnly, cloud, cloud, null, "端侧不覆盖，走云端")
                else -> Route(RouteKind.Refused, null, null, null, refuseReason(ctx))
            }

            RoutePolicy.CLOUD_FIRST -> when {
                cloudAllowed -> Route(RouteKind.CloudFirst, cloud, cloud, null)
                localOk -> Route(RouteKind.LocalFinalCloudOptional, local, local, null, "云端不可用，回退端侧")
                else -> Route(RouteKind.Refused, null, null, null, refuseReason(ctx))
            }
        }
    }

    private fun refuseReason(ctx: SelectorContext): String = when {
        !ctx.privacy.allowsInternetAudio -> "当前隐私档不允许音频上云，且端侧不覆盖 ${Lang.displayName(ctx.lang)}"
        !ctx.online -> "离线，且端侧不覆盖 ${Lang.displayName(ctx.lang)}"
        !ctx.hasCloudKey -> "未配置云端 Key，且端侧不覆盖 ${Lang.displayName(ctx.lang)}"
        ctx.spendBlocked -> "已达本月消费上限"
        else -> "无可用引擎"
    }
}
