package com.battery.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.battery.monitor.db.PowerSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 24 小时功率历史曲线
 */
class HistoryChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    companion object {
        const val PAD_LEFT = 80f
        const val PAD_RIGHT = 20f
        const val PAD_TOP = 30f
        const val PAD_BOTTOM = 50f
    }

    private var samples: List<PowerSample> = emptyList()
    private var windowMs = TimeUnit.HOURS.toMillis(24)
    private var startTime = 0L

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val chargePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D32")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dischargePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillCharge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#332E7D32")
        style = Paint.Style.FILL
    }
    private val fillDischarge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33C62828")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#616161")
        textSize = 24f
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textSize = 22f
    }

    fun setData(list: List<PowerSample>, windowHours: Long = 24, nowMs: Long = System.currentTimeMillis()) {
        samples = list
        windowMs = TimeUnit.HOURS.toMillis(windowHours)
        startTime = nowMs - windowMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val plotL = PAD_LEFT
        val plotR = w - PAD_RIGHT
        val plotT = PAD_TOP
        val plotB = h - PAD_BOTTOM
        val plotW = plotR - plotL
        val plotH = plotB - plotT

        if (samples.isEmpty()) {
            canvas.drawText("暂无历史数据", plotL + 40f, h / 2f, textPaint)
            return
        }

        // 自适应 Y
        var m = 200f
        for (s in samples) {
            if (s.powerMw > m) m = s.powerMw
            if (-s.powerMw > m) m = -s.powerMw
        }
        m = (m * 1.15f).coerceAtLeast(100f)
        val yMax = m
        val yMin = -m

        // 网格
        canvas.drawLine(plotL, plotT, plotR, plotT, gridPaint)
        canvas.drawLine(plotL, plotB, plotR, plotB, gridPaint)
        val yMid = (plotT + plotB) / 2
        canvas.drawLine(plotL, yMid, plotR, yMid, zeroPaint)

        canvas.drawText(fmtW(yMax), 4f, plotT + 10f, axisTextPaint)
        canvas.drawText("0", 40f, yMid + 8f, axisTextPaint)
        canvas.drawText(fmtW(yMin), 4f, plotB + 6f, axisTextPaint)

        // X 轴时间刻度: 6 段
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (i in 0..6) {
            val t = startTime + windowMs * i / 6
            val x = plotL + plotW * i / 6
            canvas.drawText(sdf.format(Date(t)), x - 20f, h - 14f, axisTextPaint)
        }

        fun xOf(ts: Long): Float {
            val t = ((ts - startTime).toFloat() / windowMs).coerceIn(0f, 1f)
            return plotL + t * plotW
        }
        fun yOf(p: Float): Float {
            val t = (p - yMin) / (yMax - yMin)
            return plotB - t * plotH
        }

        // 充电/放电曲线 + 填充
        val chargePath = Path()
        val dischargePath = Path()
        var cStart = false
        var dStart = false
        var cBaseX = 0f
        var dBaseX = 0f
        for (s in samples) {
            val x = xOf(s.ts)
            val y = yOf(s.powerMw)
            if (s.powerMw >= 0f) {
                if (!cStart) {
                    chargePath.moveTo(x, yMid)
                    chargePath.lineTo(x, y)
                    cStart = true
                    cBaseX = x
                } else chargePath.lineTo(x, y)
            } else {
                // 结束充电段
                if (cStart) {
                    chargePath.lineTo(x, yMid)
                    chargePath.close()
                    cStart = false
                }
            }
            if (s.powerMw <= 0f) {
                if (!dStart) {
                    dischargePath.moveTo(x, yMid)
                    dischargePath.lineTo(x, y)
                    dStart = true
                    dBaseX = x
                } else dischargePath.lineTo(x, y)
            } else {
                if (dStart) {
                    dischargePath.lineTo(x, yMid)
                    dischargePath.close()
                    dStart = false
                }
            }
        }
        if (cStart) { chargePath.lineTo(plotR, yMid); chargePath.close() }
        if (dStart) { dischargePath.lineTo(plotR, yMid); dischargePath.close() }

        canvas.drawPath(chargePath, fillCharge)
        canvas.drawPath(dischargePath, fillDischarge)
        canvas.drawPath(chargePath, chargePaint)
        canvas.drawPath(dischargePath, dischargePaint)

        // 画"已充满"标记点
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E7D32")
            style = Paint.Style.FILL
        }
        val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2E7D32")
            textSize = 22f
            isFakeBoldText = true
        }
        val sdfHM = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (s in samples) {
            if (s.marker == "full") {
                val x = xOf(s.ts)
                // 画一个小圆点
                canvas.drawCircle(x, yMid, 6f, markerPaint)
                // 画虚线
                val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#2E7D32")
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                    pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
                }
                canvas.drawLine(x, plotT, x, plotB, dashPaint)
                // 标注文字
                canvas.drawText("已充满 ${sdfHM.format(Date(s.ts))}", x + 8f, plotT + 20f, markerTextPaint)
            }
        }
    }

    private fun fmtW(v: Float): String {
        return when {
            Math.abs(v) >= 1000f -> String.format(Locale.getDefault(), "%.1fW", v / 1000f)
            else -> String.format(Locale.getDefault(), "%.0fmW", v)
        }
    }
}
