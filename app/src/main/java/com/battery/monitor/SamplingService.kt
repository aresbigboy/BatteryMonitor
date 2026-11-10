package com.battery.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.battery.monitor.db.BatteryDbHelper
import com.battery.monitor.db.PowerSample
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 前台采样服务: 每 [SAMPLE_INTERVAL_S] 秒读一次电池信息, 写入 SQLite.
 * App 退到后台/被杀前由本服务持续积累 24h 历史.
 */
class SamplingService : Service() {

    companion object {
        const val TAG = "SamplingService"
        const val CHANNEL_ID = "battery_monitor_sampling"
        const val NOTI_ID = 1001
        const val SAMPLE_INTERVAL_S = 60L
        const val READS_PER_SAMPLE = 3   // 每次采样连续读3次取中位数, 剔除尖刺
        const val READ_GAP_MS = 2000L

        fun start(ctx: Context) {
            val i = Intent(ctx, SamplingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }

    private lateinit var db: BatteryDbHelper
    private var scheduler: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        db = BatteryDbHelper(this)
        createChannel()
        startForeground(NOTI_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
            scheduler!!.scheduleAtFixedRate({
                try {
                    // 连续读3次取中位数, 剔除传感器尖刺
                    val powers = ArrayList<Float>(READS_PER_SAMPLE)
                    var lastV = 0; var lastI = 0f; var lastT = 0f
                    repeat(READS_PER_SAMPLE) { i ->
                        val info = BatteryReader.read(this)
                        powers.add(info.powerMw)
                        lastV = info.voltageMv; lastI = info.currentMa; lastT = info.temperatureC
                        if (i < READS_PER_SAMPLE - 1) Thread.sleep(READ_GAP_MS)
                    }
                    powers.sort()
                    val medianPower = powers[powers.size / 2]
                    db.insert(
                        PowerSample(
                            ts = System.currentTimeMillis(),
                            powerMw = medianPower,
                            voltageMv = lastV,
                            currentMa = lastI,
                            tempC = lastT
                        )
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "sample failed", t)
                }
            }, 0, SAMPLE_INTERVAL_S, TimeUnit.SECONDS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "电池采样服务",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "持续记录电池功率历史"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle("电池监控运行中")
            .setContentText("正在记录功率历史")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}
