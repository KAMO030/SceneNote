package dev.scenenote.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSProcessInfoThermalStateDidChangeNotification
import platform.Foundation.NSThread
import platform.Foundation.thermalState
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryLevelDidChangeNotification
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * 热 / 电四级阶梯（iOS，规格 §5.5）：
 * - 热：NSProcessInfo.thermalState 直接对应 NOMINAL / FAIR / SERIOUS / CRITICAL，变化走 NSProcessInfoThermalStateDidChangeNotification。
 * - 电：UIDevice 电量监测，电量 < 20% 且不在充电（Charging / Full 都算在充）→ lowBattery，
 *   电量与充电状态两个通知都监听（插上充电器要立刻解除省电）。
 * 模拟器 batteryLevel 为 −1（未知），按"不低电"处理。start / stop 由 VM 生命周期调用；stop 关闭电量监测。
 */
class IosThermalMonitor : ThermalMonitor {
    private val _level = MutableStateFlow(ThermalLevel.NOMINAL)
    override val level: StateFlow<ThermalLevel> = _level.asStateFlow()
    private val _lowBattery = MutableStateFlow(false)
    override val lowBattery: StateFlow<Boolean> = _lowBattery.asStateFlow()

    private var observers: List<Any> = emptyList()

    override fun start() = onMain {
        if (observers.isNotEmpty()) return@onMain
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        refreshThermal(); refreshBattery()
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        // 热状态通知可能从任意线程发出，统一投递到主队列
        val thermal = center.addObserverForName(NSProcessInfoThermalStateDidChangeNotification, `object` = null, queue = main) { _: NSNotification? -> refreshThermal() }
        val levelObs = center.addObserverForName(UIDeviceBatteryLevelDidChangeNotification, `object` = null, queue = main) { _: NSNotification? -> refreshBattery() }
        val stateObs = center.addObserverForName(UIDeviceBatteryStateDidChangeNotification, `object` = null, queue = main) { _: NSNotification? -> refreshBattery() }
        observers = listOf(thermal, levelObs, stateObs)
    }

    override fun stop() = onMain {
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers = emptyList()
        UIDevice.currentDevice.batteryMonitoringEnabled = false
    }

    private fun refreshThermal() {
        _level.value = when (NSProcessInfo.processInfo.thermalState) {
            NSProcessInfoThermalState.NSProcessInfoThermalStateNominal -> ThermalLevel.NOMINAL
            NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> ThermalLevel.FAIR
            NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> ThermalLevel.SERIOUS
            NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> ThermalLevel.CRITICAL
            else -> ThermalLevel.NOMINAL   // cinterop 共享化后该枚举在 iosMain 视为 expect enum，when 必须带 else
        }
    }

    private fun refreshBattery() {
        val device = UIDevice.currentDevice
        val levelValue = device.batteryLevel               // 0.0–1.0；未知（模拟器）为 −1
        val charging = device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
        _lowBattery.value = levelValue >= 0f && levelValue < LOW_BATTERY && !charging
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }

    private companion object { const val LOW_BATTERY = 0.2f }
}
