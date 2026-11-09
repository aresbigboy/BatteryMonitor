package com.battery.monitor

/**
 * 一次电池采样快照
 * powerMw > 0 表示充电, < 0 表示放电
 */
data class BatteryInfo(
    val levelPct: Int,          // 电量百分比 0-100
    val health: String,         // Good / Overheat / Dead / ...
    val status: String,        // Charging / Discharging / Full / Not charging / Unknown
    val statusIsCharging: Boolean,
    val source: String,        // AC / USB / Wireless / Battery
    val technology: String,    // Li-ion
    val temperatureC: Float,    // °C
    val voltageMv: Int,         // mV
    val currentMa: Float,      // mA (已校正符号: 充电为正, 放电为负)
    val powerMw: Float,         // mW (充电为正, 放电为负)
    val cycleCount: Int,        // -1 = 不可用
    val designCapacityMah: Int, // -1 = 不可用
    val timestampMs: Long = System.currentTimeMillis()
)
