package com.wellsey.perch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * How the phone is tilted relative to how you're holding it, smoothed, in -1..1 on each axis.
 * The glass uses it to move a soft highlight, like light catching real glass.
 */
object Tilt : SensorEventListener {
    var x = 0f
        private set
    var y = 0f
        private set

    private var baseX = Float.NaN
    private var baseY = 0f
    private var sm: SensorManager? = null
    private var drawnX = 0f
    private var drawnY = 0f

    fun start(c: Context) {
        if (!Prefs.tiltLight || Motion.reduced) {
            stop(); x = 0f; y = 0f; return
        }
        if (sm != null) return
        val m = c.getSystemService(SensorManager::class.java) ?: return
        val s = m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm = m
        baseX = Float.NaN
        m.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm?.unregisterListener(this)
        sm = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]
        val gy = e.values[1]
        if (baseX.isNaN()) {
            baseX = gx; baseY = gy
        }
        // The resting pose drifts slowly, so the light centres on however you hold the phone.
        baseX += (gx - baseX) * 0.015f
        baseY += (gy - baseY) * 0.015f
        val tx = (-(gx - baseX) / 3.5f).coerceIn(-1f, 1f)
        val ty = ((gy - baseY) / 3.5f).coerceIn(-1f, 1f)
        x += (tx - x) * 0.18f
        y += (ty - y) * 0.18f
        if (abs(x - drawnX) > 0.012f || abs(y - drawnY) > 0.012f) {
            drawnX = x; drawnY = y
            Backdrop.invalidateAll()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
