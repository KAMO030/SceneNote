package dev.scenenote.core.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android 系统分享面板：ACTION_SEND + createChooser。
 * - 文字：text/plain，标题进 EXTRA_SUBJECT（邮件 / 备忘录会用作主题）。
 * - 文件：FileProvider（authority = `<applicationId>.fileprovider`，路径表见 androidApp/res/xml/file_paths.xml，
 *   覆盖 filesDir 与 cacheDir 整棵树，Exports 写到 filesDir/export）；ClipData + FLAG_GRANT_READ_URI_PERMISSION
 *   让 chooser 里每个目标都拿到读权限。
 * 从 Application Context 启动 Activity 必须带 FLAG_ACTIVITY_NEW_TASK。
 */
class AndroidSharer(context: Context) : Sharer {
    private val ctx = context.applicationContext

    override fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        launch(send, title)
    }

    override fun shareFile(path: String, mime: String, title: String) {
        val file = File(path)
        if (!file.exists()) return
        val uri = runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file) }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(send, title)
    }

    private fun launch(send: Intent, title: String) {
        val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(chooser) }   // 没有任何可接收的应用时不崩（极少见）
    }
}
