package com.battery.monitor.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PowerSample(
    val ts: Long,
    val powerMw: Float,
    val voltageMv: Int,
    val currentMa: Float,
    val tempC: Float,
    val marker: String = ""   // "full" = 已充满标记点
)

class BatteryDbHelper(ctx: Context) :
    SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "battery_monitor.db"
        const val DB_VERSION = 2
        const val TABLE = "power_samples"
        const val HISTORY_WINDOW_MS = 24L * 60 * 60 * 1000   // 24h
        const val PRUNE_AFTER_MS = 48L * 60 * 60 * 1000      // 保留 48h
    }

    override fun onCreate(db: SQLiteDatabase?) {
        db?.execSQL(
            """CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                power_mw REAL NOT NULL,
                voltage_mv INTEGER NOT NULL,
                current_ma REAL NOT NULL,
                temp_c REAL NOT NULL,
                marker TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db?.execSQL("CREATE INDEX idx_ts ON $TABLE(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldV: Int, newV: Int) {
        db?.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(s: PowerSample) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("ts", s.ts)
            put("power_mw", s.powerMw)
            put("voltage_mv", s.voltageMv)
            put("current_ma", s.currentMa)
            put("temp_c", s.tempC)
            put("marker", s.marker)
        }
        db.insert(TABLE, null, cv)
        db.delete(TABLE, "ts < ?", arrayOf((s.ts - PRUNE_AFTER_MS).toString()))
    }

    /** 取最近 hours 小时的全部采样 */
    fun queryRecent(hours: Long, nowMs: Long = System.currentTimeMillis()): List<PowerSample> {
        val windowMs = hours * 60L * 60L * 1000L
        val db = readableDatabase
        val from = nowMs - windowMs
        val list = ArrayList<PowerSample>()
        val c = db.query(
            TABLE,
            arrayOf("ts", "power_mw", "voltage_mv", "current_ma", "temp_c", "marker"),
            "ts >= ?",
            arrayOf(from.toString()),
            null, null, "ts ASC"
        )
        c.use {
            while (it.moveToNext()) {
                list.add(
                    PowerSample(
                        ts = it.getLong(0),
                        powerMw = it.getFloat(1),
                        voltageMv = it.getInt(2),
                        currentMa = it.getFloat(3),
                        tempC = it.getFloat(4),
                        marker = it.getString(5) ?: ""
                    )
                )
            }
        }
        return list
    }
}
