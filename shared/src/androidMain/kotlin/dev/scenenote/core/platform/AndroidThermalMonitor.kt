package dev.scenenote.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Android 热 / 电四级阶梯（规格 §5.5；共享层 [ThermalMonitor]）。
 *
 * - 热：PowerManager.addThermalStatusListener（API 29+）七级映射为四级：
 *   NONE / LIGHT → NOMINAL、MODERATE → FAIR、SEVERE → SERIOUS、CRITICAL / EMERGENCY / SHUTDOWN → CRITICAL。
 *   API < 29 无热状态接口，恒为 NOMINAL。注册时先读 currentThermalStatus 作为初值。
 * - 电：ACTION_BATTERY_CHANGED 粘性广播（registerReceiver 立即返回当前值），电量 < 20% 且未充电 → lowBattery。
 *
 * 回调统一在主线程；start / stop 幂等。
 */
class AndroidThermalMonitor(context: Context) : ThermalMonitor {
    private val ctx = context.applicationContext
    private val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { r -> if (Looper.myLooper() == Looper.getMainLooper()) r.run() else mainHandler.post(r) }

    private val _level = MutableStateFlow(ThermalLevel.NOMINAL)
    override val level: StateFlow<ThermalLevel> = _level.asStateFlow()
    private val _lowBattery = MutableStateFlow(false)
    override val lowBattery: StateFlow<Boolean> = _lowBattery.asStateFlow()

    private var running = false

    /** 热状态监听（API 29+ 才创建，避免低版本类加载问题）。 */
    private val thermalListener: PowerManager.OnThermalStatusChangedListener? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PowerManager.OnThermalStatusChangedListener { status -> onThermal(status) } else null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { intent?.let(::onBattery) }
    }

    override fun start() {
        if (running) return
        running = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = powerManager
            val l = thermalListener
            if (pm != null && l != null) {
                runCatching {
                    onThermal(pm.currentThermalStatus)
                    pm.addThermalStatusListener(mainExecutor, l)
                }.onFailure { Log.w(TAG, "热状态监听注册失败", it) }
            }
        } else {
            _level.value = ThermalLevel.NOMINAL
        }
        // 粘性广播：注册即返回最近一次电量 Intent。系统广播不需要 EXPORTED；目标 SDK ≥ 34 必须显式给出导出标志
        runCatching {
            val sticky = ContextCompat.registerReceiver(ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            sticky?.let(::onBattery)
        }.onFailure { Log.w(TAG, "电量广播注册失败", it) }
    }

    override fun stop() {
        if (!running) return
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val l = thermalListener
            if (l != null) runCatching { powerManager?.removeThermalStatusListener(l) }
        }
        runCatching { ctx.unregisterReceiver(batteryReceiver) }
    }

    private fun onThermal(status: Int) {
        val mapped = mapThermal(status)
        if (_level.value != mapped) Log.d(TAG, "热状态 $status → $mapped")
        _level.value = mapped
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val low = pct < LOW_BATTERY_PCT && !charging
        if (_lowBattery.value != low) Log.d(TAG, "电量 $pct% 充电=$charging → lowBattery=$low")
        _lowBattery.value = low
    }

    private companion object {
        const val TAG = "ThermalMonitor"
        const val LOW_BATTERY_PCT = 20

        /** PowerManager.THERMAL_STATUS_*（0 NONE … 6 SHUTDOWN）→ 四级。 */
        fun mapThermal(status: Int): ThermalLevel = when {
            status <= 1 -> ThermalLevel.NOMINAL      // NONE / LIGHT
            status == 2 -> ThermalLevel.FAIR         // MODERATE
            status == 3 -> ThermalLevel.SERIOUS      // SEVERE
            else -> ThermalLevel.CRITICAL            // CRITICAL / EMERGENCY / SHUTDOWN
        }
    }
}
