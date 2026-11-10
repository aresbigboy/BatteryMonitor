package com.battery.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

object BatteryReader {

    data class BatteryInfo(
        val levelPct: Int,
        val health: String,
        val status: String,
        val statusIsCharging: Boolean,
        val source: String,
        val temperatureC: Float,
        val voltageMv: Int,
        val currentMa: Float,
        val powerMw: Float,
        val cycleCount: Int,
        val designCapacityMah: Int,
        val settlingSeconds: Int   // >0 表示正在稳定期
    )

    @Volatile private var cachedCycleCount: Int = -2
    @Volatile private var cachedDesignMah: Int = -2
    @Volatile private var emaCurrentMa: Float = 0f
    @Volatile private var emaInitialized: Boolean = false
    @Volatile private var lastIsCharging: Boolean = false
    @Volatile private var settleUntilMs: Long = 0   // 充放电切换后的稳定期截止时间

    fun read(ctx: Context): BatteryInfo {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val br: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = br?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = br?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPct = if (level >= 0 && scale > 0) (level * 100f / scale).toInt() else -1

        val voltageMv = br?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temperatureTenth = br?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temperatureC = if (temperatureTenth > 0) temperatureTenth / 10f else -1f

        val statusInt = br?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val statusStr = when (statusInt) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
        val pluggedTmp = br?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val isCharging = pluggedTmp != 0 && pluggedTmp != -1

        val healthInt = br?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthStr = when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        val tech = br?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"

        val sourceStr = when (pluggedTmp) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery"
        }

        val rawCurrent = try {
            val avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            if (avg != 0) avg else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (t: Throwable) {
            try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
            catch (_: Throwable) { -1 }
        }

        val absRaw = Math.abs(rawCurrent)
        var currentMa = when {
            rawCurrent == 0 -> 0f
            absRaw < 10000 -> rawCurrent.toFloat()
            else -> rawCurrent / 1000f
        }
        if (isCharging && currentMa < 0) currentMa = -currentMa
        else if (!isCharging && statusInt != BatteryManager.BATTERY_STATUS_FULL && currentMa > 0)
            currentMa = -currentMa
        // EMA 平滑: 充放电状态切换时重置, alpha=0.5
        if (isCharging != lastIsCharging) {
            lastIsCharging = isCharging
            emaInitialized = false
            settleUntilMs = System.currentTimeMillis() + 5000   // 5秒稳定期
        }
        // 插拔充电器后5秒内电流剧烈跳变, 不记录功率
        val settleLeftMs = (settleUntilMs - System.currentTimeMillis()).coerceAtLeast(0)
        val settling = settleLeftMs > 0
        currentMa = if (!emaInitialized) {
            emaInitialized = true
            currentMa
        } else {
            emaCurrentMa * 0.5f + currentMa * 0.5f
        }
        emaCurrentMa = currentMa
        if (settling) { currentMa = 0f }

        val powerMw = if (!settling && voltageMv > 0) voltageMv * currentMa / 1000f else 0f

        val cycleCount = readCycleCount(bm, br)
        val designMah = readDesignCapacity(br)

        return BatteryInfo(
            levelPct = levelPct,
            health = healthStr,
            status = statusStr,
            statusIsCharging = isCharging,
            source = sourceStr,
            temperatureC = temperatureC,
            voltageMv = voltageMv,
            currentMa = currentMa,
            powerMw = powerMw,
            cycleCount = cycleCount,
            designCapacityMah = designMah,
            settlingSeconds = ((settleLeftMs + 999) / 1000).toInt()
        )
    }

    private fun readCycleCount(bm: BatteryManager, br: Intent?): Int {
        if (cachedCycleCount != -2) return cachedCycleCount
        var best = -1
        br?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.let { if (it > best) best = it }
        try {
            val v = bm.getIntProperty(6)
            if (v > best) best = v
        } catch (_: Throwable) {}
        cachedCycleCount = best
        return best
    }

    private fun readDesignCapacity(br: Intent?): Int {
        if (cachedDesignMah != -2) return cachedDesignMah
        val level = br?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = br?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val chargeCounter = br?.getIntExtra("charge_counter", -1) ?: -1
        var best = -1
        if (level > 0 && scale > 0 && chargeCounter > 0) {
            val pct = level.toFloat() / scale.toFloat()
            if (pct > 0.05f) best = (chargeCounter / pct / 1000f).toInt()
        }
        cachedDesignMah = best
        return best
    }

    /** 开屏时重置 EMA, 并触发短暂稳定期, 避免传感器跳变 */
    fun resetSmoothing() {
        emaInitialized = false
        settleUntilMs = System.currentTimeMillis() + 10000
    }

    fun registerStateListener(ctx: Context, onChanged: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { onChanged() }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ctx.registerReceiver(receiver, f)
        return receiver
    }
}
