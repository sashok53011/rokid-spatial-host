package com.rokid.spatialhost.battery

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thermal/FPS governor: exposes a target frame rate the render loop should
 * respect. On thermal pressure the target drops (and the renderer lowers
 * resolution scale) instead of letting the system throttle us into jank.
 */
class ThermalGovernor(context: Context) {

    data class Budget(
        val targetFps: Int,
        val resolutionScale: Float,
        val thermalStatus: Int,
    )

    private val _budget = MutableStateFlow(Budget(90, 1f, PowerManager.THERMAL_STATUS_NONE))
    val budget: StateFlow<Budget> = _budget

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var started = false

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        _budget.value = when (status) {
            PowerManager.THERMAL_STATUS_MODERATE -> Budget(60, 0.85f, status)
            PowerManager.THERMAL_STATUS_SEVERE -> Budget(45, 0.7f, status)
            PowerManager.THERMAL_STATUS_CRITICAL -> Budget(30, 0.5f, status)
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
            -> Budget(20, 0.4f, status)

            else -> Budget(90, 1f, status)
        }
    }

    fun start() {
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener(listener)
            // Sync initial state
            listener.onThermalStatusChanged(powerManager.currentThermalStatus)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.removeThermalStatusListener(listener)
        }
    }

    /** Frame interval in nanoseconds for the current budget. */
    fun frameIntervalNanos(): Long = 1_000_000_000L / _budget.value.targetFps
}

/**
 * Session power report: mAh consumed since start and projected drain rate.
 * Uses the battery current counter (coulomb counter) when available.
 */
class PowerReport(context: Context) {

    data class Stats(
        val elapsedSeconds: Long,
        val mahUsed: Float,
        val drainMa: Float,
        val batteryPercent: Int,
    )

    private val _stats = MutableStateFlow(Stats(0, 0f, 0f, -1))
    val stats: StateFlow<Stats> = _stats

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager

    private var startNanos = 0L
    private var startMah = 0f

    fun start() {
        startNanos = System.nanoTime()
        startMah = readChargeCounterMah()
        _stats.value = Stats(0, 0f, 0f, batteryPercent())
    }

    fun sample() {
        if (startNanos == 0L) return
        val elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000L
        val used = (readChargeCounterMah() - startMah).coerceAtLeast(0f)
        val drainMa = if (elapsedSec > 0) used / (elapsedSec / 3600f) else 0f
        _stats.value = Stats(elapsedSec, used, drainMa, batteryPercent())
    }

    private fun readChargeCounterMah(): Float {
        val uc = batteryManager.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return if (uc > 0) uc / 1000f else 0f // µAh → mAh
    }

    private fun batteryPercent(): Int {
        val v = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // Some boards return Integer.MIN_VALUE when the fuel-gauge property
        // is unavailable — report -1 so the HUD shows "?" instead of garbage.
        return if (v in 0..100) v else -1
    }
}
