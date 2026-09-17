package dev.scenenote.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import dev.scenenote.core.platform.DeepLinks
import dev.scenenote.screen.AndroidMediaPicker

/**
 * 深链 / 分享跳板：把 VIEW scenenote:// 或 ACTION_SEND 转成进程内的 [DeepLinks.pending]，再以普通 MAIN intent 拉起 MainActivity 后立刻结束。
 * 自己不进最近任务、不留 task，所以主 task 的根 intent 永远是 MAIN，不会被系统重放深链（见清单注释）。
 * 分享的大文件先在后台复制，复制完再发深链；MainActivity 已在前台等着。
 */
class DeepLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        when {
            i?.action == Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) AndroidMediaPicker.importShared(this, uri) { item ->
                    DeepLinks.handle("scenenote://screen?shared=${Uri.encode(item.path)}&name=${Uri.encode(item.name)}")
                }
            }
            i?.dataString != null -> DeepLinks.handle(i.dataString!!)
        }
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
