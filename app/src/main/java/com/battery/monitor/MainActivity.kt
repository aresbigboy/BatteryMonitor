package com.battery.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        const val REFRESH_INTERVAL_MS = 1000L
        const val REQ_NOTIF = 100
    }

    private lateinit var tvLevel: TextView
    private lateinit var tvHealth: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvCycle: TextView
    private lateinit var tvDesign: TextView
    private lateinit var tvPowerBig: TextView
    private lateinit var tvPowerSub: TextView
    private lateinit var chart: PowerChartView

    private val handler = Handler(Looper.getMainLooper())
    private var stateReceiver: android.content.BroadcastReceiver? = null

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.title = "电池监控"

        bindViews()
        requestNotifPermission()

        // 启动后台采样服务
        SamplingService.start(this)

        // 点击曲线 -> 历史
        chart.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
    }

    private fun bindViews() {
        tvLevel = findViewById(R.id.tv_level)
        tvHealth = findViewById(R.id.tv_health)
        tvStatus = findViewById(R.id.tv_status)
        tvSource = findViewById(R.id.tv_source)
        tvTemp = findViewById(R.id.tv_temp)
        tvVoltage = findViewById(R.id.tv_voltage)
        tvCurrent = findViewById(R.id.tv_current)
        tvCycle = findViewById(R.id.tv_cycle)
        tvDesign = findViewById(R.id.tv_design)
        tvPowerBig = findViewById(R.id.tv_power_big)
        tvPowerSub = findViewById(R.id.tv_power_sub)
        chart = findViewById(R.id.chart)
    }

    override fun onResume() {
        super.onResume()
        BatteryReader.resetSmoothing()
        handler.post(tick)
        stateReceiver = BatteryReader.registerStateListener(this) { refresh() }
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        stateReceiver?.let { unregisterReceiver(it) }
        stateReceiver = null
        super.onPause()
    }

    private fun refresh() {
        try {
            val info = BatteryReader.read(this)
            tvLevel.text = if (info.levelPct >= 0) "${info.levelPct} %" else "--"
            tvHealth.text = info.health
            tvStatus.text = info.status
            tvSource.text = info.source
            tvTemp.text = if (info.temperatureC > 0) String.format(Locale.getDefault(), "%.1f °C", info.temperatureC) else "--"
            tvVoltage.text = if (info.voltageMv > 0) "${info.voltageMv} mV" else "--"
            tvCurrent.text = String.format(Locale.getDefault(), "%.1f mA", info.currentMa)
            tvCycle.text = if (info.cycleCount > 0) info.cycleCount.toString() else "--"
            tvDesign.text = if (info.designCapacityMah > 0) "${info.designCapacityMah} mAh" else "--"

            // 功率大字
            if (info.settlingSeconds > 0) {
                tvPowerBig.text = "稳定中"
                tvPowerSub.text = "${info.settlingSeconds}s 后开始记录"
                tvPowerBig.setTextColor(ContextCompat.getColor(this, R.color.charge_green))
            } else {
                val absP = Math.abs(info.powerMw)
                val pStr = when {
                    absP >= 1000f -> String.format(Locale.getDefault(), "%.2f W", info.powerMw / 1000f)
                    else -> String.format(Locale.getDefault(), "%.0f mW", info.powerMw)
                }
                tvPowerBig.text = pStr
                tvPowerSub.text = if (info.statusIsCharging) "充电中 ▲" else "放电中 ▼"
                tvPowerBig.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (info.statusIsCharging) R.color.charge_green else R.color.discharge_red
                    )
                )
            }

            chart.pushPoint(info.powerMw)
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed", t)
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF && grantResults.isNotEmpty()
            && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授予通知权限, 前台服务可能被系统限制", Toast.LENGTH_LONG).show()
        }
    }
}
