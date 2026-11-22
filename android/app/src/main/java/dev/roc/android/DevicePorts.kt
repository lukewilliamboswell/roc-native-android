package dev.roc.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.widget.Toast

/**
 * The services behind `examples/ports`: Android APIs reached from Roc through
 * ports, with no change to the engine. Each holds the application context
 * only, since the engine (and so its subscriptions) outlives an activity.
 * Callbacks arrive on the main thread, the engine's thread.
 */
fun devicePorts(app: Context): Map<String, PortService> = mapOf(
    // Call: answered at once.
    "dev.roc.android/device" to PortService.Call { _, reply ->
        reply.answer(
            mapOf(
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "release" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT,
            ),
        )
        null
    },

    // Command: nothing comes back.
    "dev.roc.android/toast" to PortService.Command { value ->
        val text = (value as Map<*, *>)["text"] as String
        Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    },

    // Subscription (Every): the sticky battery broadcast, one message per change of level or charging.
    "dev.roc.android/battery" to PortService.Subscription { _, sink ->
        var last: Map<String, Any>? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level < 0 || scale <= 0) return
                val now = mapOf(
                    "percent" to level * 100 / scale,
                    "charging" to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL),
                )
                if (now != last && sink.emit(now)) last = now
            }
        }
        // The broadcast is sticky: registering delivers the current state at once.
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        return@Subscription { app.unregisterReceiver(receiver) }
    },

    // Subscription (Latest): the accelerometer while the app's switch is on.
    "dev.roc.android/motion" to PortService.Subscription { arg, sink ->
        val sensors = app.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            sink.fail("this device has no accelerometer")
            return@Subscription null
        }
        val everyMs = ((arg as Map<*, *>)["every_ms"] as Long).coerceIn(10, 10_000)
        fun hundredths(v: Float) = Math.round(v * 100.0) / 100.0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                sink.emit(mapOf("x" to hundredths(e.values[0]), "y" to hundredths(e.values[1]), "z" to hundredths(e.values[2])))
            }
            override fun onAccuracyChanged(s: Sensor, accuracy: Int) {}
        }
        sensors.registerListener(listener, sensor, (everyMs * 1000).toInt())
        return@Subscription { sensors.unregisterListener(listener) }
    },
)
