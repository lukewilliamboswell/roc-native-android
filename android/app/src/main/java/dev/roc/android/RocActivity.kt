package dev.roc.android

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.ScrollView

/**
 * Shows one Roc app: attaches views to the process's mounted engine
 * (`RocRuntime`). Each example is a subclass in its own process, because a
 * library holds one engine and every app exports the same JNI names.
 */
abstract class RocActivity : Activity() {
    abstract val library: String

    private lateinit var runtime: RocRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = RocRuntime.obtain(this, library)
        runtime.activity = this
        runtime.uiMode.set(resources.configuration.uiMode)
        val host = runtime.host
        val renderer = ViewRenderer(this) { host }
        val scroll = ScrollView(this).apply { addView(renderer.rootView(host.tree)) }
        host.renderer = renderer
        // Android 15+ draws apps edge to edge: keep content clear of the
        // status and navigation bars and the keyboard. (No action bar: the
        // app draws its own title with Ui.title.)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    // Visibility (FX 7): the engine's page is visible while its activity is started.
    override fun onStart() { super.onStart(); runtime.visible.set(true) }
    override fun onStop() { runtime.visible.set(false); super.onStop() }

    override fun onDestroy() {
        if (runtime.activity === this) {
            runtime.host.renderer = null
            runtime.activity = null
        }
        // Recreated for a configuration change: keep the engine mounted.
        if (isFinishing) RocRuntime.finish()
        super.onDestroy()
    }
}

class CounterActivity : RocActivity() { override val library = "counter" }
class TodosActivity : RocActivity() { override val library = "todos" }
class StopwatchActivity : RocActivity() { override val library = "stopwatch" }
class HttpActivity : RocActivity() { override val library = "http" }
class DeviceActivity : RocActivity() { override val library = "device" }
