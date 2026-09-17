package dev.scenenote.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.scenenote.core.platform.DeepLinks
import dev.scenenote.screen.AndroidMediaPicker
import dev.scenenote.screen.PickerBridge
import dev.scenenote.screen.AndroidPip
import dev.scenenote.ui.App

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    /** 相册 / 文件选择、投屏授权：结果经进程内单例 [PickerBridge] 回到发起的协程。 */
    private val activityResults = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        PickerBridge.deliver(r.resultCode, r.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PickerBridge.attach(this) { activityResults.launch(it) }
        AndroidPip.attach(this)
        setContent { App() }
        requestRuntimePermissions()
        if (savedInstanceState == null) handleIntent(intent)   // 重建（深色 / 字号切换）不重复触发磁贴深链 / 分享导入
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        PickerBridge.detach(this)
        AndroidPip.detach(this)
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        AndroidPip.onModeChanged(isInPictureInPictureMode)
    }

    /** 深链（磁贴 / 外部）与分享面板（ACTION_SEND 视频 / 音频）：后者先复制进 cacheDir，再以深链交给路由。 */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // 从最近任务 / 更新后重开：系统会重放上一次的启动 Intent（含 autostart 深链）——那不是用户此刻的意图，忽略
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action == Intent.ACTION_SEND) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
            AndroidMediaPicker.importShared(this, uri) { item ->
                DeepLinks.handle("scenenote://screen?shared=${Uri.encode(item.path)}&name=${Uri.encode(item.name)}")
            }
            return
        }
        intent.dataString?.let { DeepLinks.handle(it) }
    }

    /** I0：进入即申请麦克风 / 蓝牙 / 通知；正式流程在 I4 改为场景内按需引导（显式同意页）。 */
    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }
}
