package dev.scenenote.android

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴（规格 §3.1「触发：手机不出口袋」的 Android 入口）。
 * 点击 → 以 VIEW intent 打开 MainActivity 的 `scenenote://` 深链，由 `DeepLinks` 路由到会话页。
 * 锁屏时先 unlockAndRun：麦克风前台服务必须由可见 UI 启动（Android 14+ 硬规则），M1 冷启动路径本来就是「磁贴 → 解锁 → 自动开始」。
 * Android 14+ 用 startActivityAndCollapse(PendingIntent)，旧版本走已弃用的 Intent 重载。
 * 磁贴只是入口，不承载状态：始终 INACTIVE（可点），不做开关语义。
 */
abstract class SceneTileService(private val deepLink: String, private val tileLabel: String) : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = tileLabel
            if (Build.VERSION.SDK_INT >= 29) subtitle = "场记"
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { launch() } else launch()
    }

    private fun launch() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
            .setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, deepLink.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** 「仅听」：M0 直接开始（autostart=1），一按就听。 */
class ListenTileService : SceneTileService("scenenote://scene/listen?autostart=1", "仅听")

/** 「速译一句」：M4 是按住说 → 松手出字，不自动开始，只把页面打开到「按住说话」。 */
class QuickPhraseTileService : SceneTileService("scenenote://scene/quick_phrase", "速译一句")
