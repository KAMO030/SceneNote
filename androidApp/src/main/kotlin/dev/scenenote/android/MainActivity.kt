package dev.scenenote.android

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
    }

    override fun onDestroy() {
        PickerBridge.detach(this)
        AndroidPip.detach(this)
        super.onDestroy()
    override fun onStop() {
        super.onStop()
        // 离开 App 时刷一次主屏小组件：主题色在设置里改了要跟上
        val app = applicationContext
        CoroutineScope(Dispatchers.Default).launch { runCatching { SceneWidget().updateAll(app) } }
    }

    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        AndroidPip.onModeChanged(isInPictureInPictureMode)
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
    /** Android 10 / 11 没有 PiP autoEnter：系统字幕抓取中用户按 Home / 切 App，这里主动进字幕条（12+ 由系统接管，此调用是空操作）。 */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AndroidPip.onUserLeaveHint()
    }

