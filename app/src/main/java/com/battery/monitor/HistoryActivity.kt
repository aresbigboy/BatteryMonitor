package com.battery.monitor

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.battery.monitor.db.BatteryDbHelper
import com.battery.monitor.db.PowerSample
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var chart: HistoryChartView
    private lateinit var tvStats: TextView
    private lateinit var tvCount: TextView
    private lateinit var db: BatteryDbHelper
    private var currentHours: Long = 1

    private lateinit var btn1h: Button
    private lateinit var btn6h: Button
    private lateinit var btn12h: Button
    private lateinit var btn24h: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.title = "功率历史"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        chart = findViewById(R.id.history_chart)
        tvStats = findViewById(R.id.tv_stats)
        tvCount = findViewById(R.id.tv_count)
        db = BatteryDbHelper(this)

        btn1h = findViewById(R.id.btn_1h)
        btn6h = findViewById(R.id.btn_6h)
        btn12h = findViewById(R.id.btn_12h)
        btn24h = findViewById(R.id.btn_24h)

        btn1h.setOnClickListener { switchRange(1) }
        btn6h.setOnClickListener { switchRange(6) }
        btn12h.setOnClickListener { switchRange(12) }
        btn24h.setOnClickListener { switchRange(24) }

        switchRange(1)
    }

    private fun switchRange(hours: Long) {
        currentHours = hours
        // 高亮当前按钮
        val active = android.graphics.Color.parseColor("#1565C0")
        val inactive = android.graphics.Color.parseColor("#9E9E9E")
        btn1h.setTextColor(if (hours == 1L) active else inactive)
        btn6h.setTextColor(if (hours == 6L) active else inactive)
        btn12h.setTextColor(if (hours == 12L) active else inactive)
        btn24h.setTextColor(if (hours == 24L) active else inactive)
        load()
    }

    private fun load() {
        val list: List<PowerSample> = db.queryRecent(currentHours)
        chart.setData(list, windowHours = currentHours)

        if (list.isEmpty()) {
            tvStats.text = "暂无数据\n请保持 App 在后台运行一段时间"
            tvCount.text = ""
            return
        }

        var maxCharge = 0f
        var maxDischarge = 0f
        var sum = 0f
        var chargeEnergyWh = 0.0
        var dischargeEnergyWh = 0.0
        val dtHours = 30.0 / 3600.0

        for (s in list) {
            if (s.powerMw > maxCharge) maxCharge = s.powerMw
            if (s.powerMw < maxDischarge) maxDischarge = s.powerMw
            sum += s.powerMw
            if (s.powerMw > 0) chargeEnergyWh += s.powerMw / 1000.0 * dtHours
            else dischargeEnergyWh += -s.powerMw / 1000.0 * dtHours
        }
        val avg = sum / list.size

        tvStats.text = buildString {
            appendLine("时间范围:     最近 ${currentHours} 小时")
            appendLine("最大充电功率: ${fmtW(maxCharge)}")
            appendLine("最大放电功率: ${fmtW(maxDischarge)}")
            appendLine("平均功率:     ${fmtW(avg)}")
            appendLine("累计充电能量: ${String.format(Locale.getDefault(), "%.2f Wh", chargeEnergyWh)}")
            append("累计放电能量: ${String.format(Locale.getDefault(), "%.2f Wh", dischargeEnergyWh)}")
        }
        tvCount.text = "采样点数: ${list.size} (每 30 秒一次)"
    }

    private fun fmtW(v: Float): String {
        return when {
            Math.abs(v) >= 1000f -> String.format(Locale.getDefault(), "%.2f W", v / 1000f)
            else -> String.format(Locale.getDefault(), "%.0f mW", v)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
