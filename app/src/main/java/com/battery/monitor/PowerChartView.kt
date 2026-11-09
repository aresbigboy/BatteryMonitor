package com.battery.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实时功率曲线 (充电为正, 放电为负)
 *
 * 数据点由外部每秒 push 一次, 最多保留 [MAX_POINTS] 个.
 * 最新的点画在最右边, 整体向左滚动.
 */
class PowerChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    companion object {
        const val MAX_POINTS = 180   // 1秒一个点, 最多3分钟
        const val PAD_LEFT = 70f
        const val PAD_RIGHT = 24f
        const val PAD_TOP = 30f
        const val PAD_BOTTOM = 36f
    }

    private val points = ArrayList<Float>()          // power mW
    private var yMax = 1000f                          // 自适应
    private var yMin = -1000f

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
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dischargePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#616161")
        textSize = 26f
    }
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun pushPoint(powerMw: Float) {
        points.add(powerMw)
        while (points.size > MAX_POINTS) points.removeAt(0)
        // 自适应 Y 轴
        var m = 100f
        for (p in points) {
            if (p > m) m = p
            if (-p > m) m = -p
        }
        // 留 20% 边距
        m = (m * 1.2f).coerceAtLeast(200f)
        yMax = m
        yMin = -m
        invalidate()
    }

    fun clear() {
        points.clear()
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

        // 网格: 上下两条
        val yTop = plotT
        val yBot = plotB
        val yMid = (yTop + yBot) / 2
        canvas.drawLine(plotL, yTop, plotR, yTop, gridPaint)
        canvas.drawLine(plotL, yBot, plotR, yBot, gridPaint)
        canvas.drawLine(plotL, yMid, plotR, yMid, zeroPaint)

        // Y 轴标签
        canvas.drawText(fmtY(yMax), 8f, yTop + 10f, textPaint)
        canvas.drawText("0", 30f, yMid + 8f, textPaint)
        canvas.drawText(fmtY(yMin), 8f, yBot + 6f, textPaint)

        // X 轴时间标签 (首尾)
        if (points.isNotEmpty()) {
            canvas.drawText("-${points.size}s", plotL, h - 8f, textPaint)
            canvas.drawText("now", plotR - 60f, h - 8f, textPaint)
        }

        if (points.size < 2) return

        // 把功率值映射到 y
        fun yOf(p: Float): Float {
            val t = (p - yMin) / (yMax - yMin)   // 0..1
            return yBot - t * plotH
        }
        fun xOf(i: Int): Float {
            // i = 0 最旧 -> plotL, i = size-1 最新 -> plotR
            return plotL + (i.toFloat() / (MAX_POINTS - 1)) * plotW
        }

        // 正半轴 (充电绿) 和负半轴 (放电红) 分别画
        val chargePath = Path()
        val dischargePath = Path()
        var chargeStarted = false
        var dischargeStarted = false
        for (i in points.indices) {
            val x = xOf(i)
            val y = yOf(points[i])
            if (points[i] >= 0f) {
                if (!chargeStarted) { chargePath.moveTo(x, y); chargeStarted = true }
                else chargePath.lineTo(x, y)
            }
            if (points[i] <= 0f) {
                if (!dischargeStarted) { dischargePath.moveTo(x, y); dischargeStarted = true }
                else dischargePath.lineTo(x, y)
            }
        }
        canvas.drawPath(chargePath, chargePaint)
        canvas.drawPath(dischargePath, dischargePaint)

        // 最新点
        val lastX = xOf(points.size - 1)
        val lastY = yOf(points.last())
        canvas.drawCircle(lastX, lastY, 8f, dotPaint)
    }

    private fun fmtY(v: Float): String {
        return when {
            Math.abs(v) >= 1000f -> String.format(Locale.getDefault(), "%.1fW", v / 1000f)
            else -> String.format(Locale.getDefault(), "%.0fmW", v)
        }
    }
}
