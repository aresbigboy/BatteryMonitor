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
        val isFullCharged: Boolean,  // 100% 电插着充电器, 不记录功率
        val currentValid: Boolean    // 电流传感器读数是否可信
    )

    /** 电流物理上限(毫安): 超过即视为传感器异常值 */
    private const val MAX_CURRENT_MA = 20000f

    @Volatile private var cachedCycleCount: Int = -2
    @Volatile private var cachedDesignMah: Int = -2
    @Volatile private var emaCurrentMa: Float = 0f
    @Volatile private var emaInitialized: Boolean = false
    @Volatile private var lastIsCharging: Boolean = false

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

        val sourceStr = when (pluggedTmp) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery"
        }

        // 逐路取电流: AVERAGE 优先, 无效再试 NOW, 都无效则视为读取失败
        val avgRaw = propOrNull(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val nowRaw = propOrNull(bm, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentValid = sanitizeCurrent(avgRaw) != null || sanitizeCurrent(nowRaw) != null
        var currentMa = sanitizeCurrent(avgRaw) ?: sanitizeCurrent(nowRaw) ?: 0f

        if (isCharging && currentMa < 0) currentMa = -currentMa
        else if (!isCharging && statusInt != BatteryManager.BATTERY_STATUS_FULL && currentMa > 0)
            currentMa = -currentMa
        // EMA 平滑: 充放电状态切换时重置, alpha=0.5
        if (isCharging != lastIsCharging) {
            lastIsCharging = isCharging
            emaInitialized = false
        }
        currentMa = if (!emaInitialized || !currentValid) {
            emaInitialized = currentValid
            currentMa
        } else {
            emaCurrentMa * 0.5f + currentMa * 0.5f
        }
        if (currentValid) emaCurrentMa = currentMa

        // 100% 电插着充电器时, BMS 在做 topping charge 脉冲, 电流读数不可靠
        val fullCharged = isCharging && levelPct >= 100
        val powerMw = if (!fullCharged && currentValid && voltageMv > 0) voltageMv * currentMa / 1000f else 0f

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
            isFullCharged = fullCharged,
            currentValid = currentValid
        )
    }

    /** 读 BatteryManager 的整型属性, 失败返回 null */
    private fun propOrNull(bm: BatteryManager, prop: Int): Int? = try {
        bm.getIntProperty(prop)
    } catch (_: Throwable) {
        null
    }

    /**
     * 校验并换算电流值(mA):
     * - 过滤 Integer.MAX_VALUE/MIN、-1 等传感器哨兵值
     * - 大于 10000 的按 µA 折算成 mA
     * - 超出 ±20A 物理范围的一律视为无效
     */
    private fun sanitizeCurrent(raw: Int?): Float? {
        if (raw == null) return null
        if (raw == 0 || raw == -1 || raw == Int.MAX_VALUE || raw == Int.MIN_VALUE) return null
        val v = if (Math.abs(raw.toLong()) >= 10000L) raw / 1000f else raw.toFloat()
        if (Math.abs(v) > MAX_CURRENT_MA) return null
        return v
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

    /** 开屏时重置 EMA, 从当前读数重新开始跟踪 */
    fun resetSmoothing() {
        emaInitialized = false
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
