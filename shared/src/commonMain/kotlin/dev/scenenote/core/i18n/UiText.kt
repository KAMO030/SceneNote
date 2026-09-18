package dev.scenenote.core.i18n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * 引擎层 / 纯函数产出的、要给用户看的文案：不在产出处取字符串，到 UI 边缘再按当前语言解析。
 * 参数里允许再套 [UiText]（如"端侧不覆盖 %1$s"里的语言名）。
 */
sealed interface UiText {
    data class Res(val res: StringResource, val args: List<Any> = emptyList()) : UiText
    /** 用户输入、平台原样返回的文本（文件名、系统错误信息等）。 */
    data class Plain(val value: String) : UiText

    companion object {
        fun res(res: StringResource, vararg args: Any): UiText = Res(res, args.toList())
        fun plain(value: String): UiText = Plain(value)
    }
}

@Composable
fun UiText.string(): String = when (this) {
    is UiText.Plain -> value
    is UiText.Res -> if (args.isEmpty()) stringResource(res) else stringResource(res, *args.map { if (it is UiText) it.string() else it }.toTypedArray())
}

suspend fun UiText.load(): String = when (this) {
    is UiText.Plain -> value
    is UiText.Res -> if (args.isEmpty()) getString(res) else getString(res, *args.map { if (it is UiText) it.load() else it }.toTypedArray())
}

@Composable fun UiText?.stringOrNull(): String? = this?.string()

/** 带可本地化文案的异常：平台层 / 引擎层抛出，UI 用 [uiText] 取文案显示。 */
class UiException(val text: UiText, cause: Throwable? = null) : Exception(text.toString(), cause)

fun uiError(res: StringResource, vararg args: Any): Nothing = throw UiException(UiText.res(res, *args))

/** 异常 → 给用户看的文案：[UiException] 用它自带的，其他异常原样显示 message（多为平台 / 网络原文）。 */
fun Throwable.uiText(): UiText = (this as? UiException)?.text ?: UiText.Plain(message ?: toString())
