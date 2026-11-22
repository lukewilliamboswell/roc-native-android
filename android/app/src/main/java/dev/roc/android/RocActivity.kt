package dev.roc.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
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
        installBack()
    }

    override fun onDestroy() {
        back?.disarm()
        if (runtime.activity === this) {
            runtime.navigation.changed = null
            runtime.host.renderer = null
            runtime.activity = null
        }
        // Recreated for a configuration change: keep the engine mounted.
        if (isFinishing) RocRuntime.finish()
        super.onDestroy()
    }

    // ---- System back (NavigationFeature.kt) -----------------------------
    // Back pops the app's history (delivering popstate) while it has more
    // than one entry; at the first entry the system goes back as usual. On
    // API 33+ an OnBackInvokedCallback is registered only while there is
    // history to pop, so the system's own back (and its predictive
    // animation) applies at the root; below 33, onBackPressed.

    private var back: BackCallback? = null

    private fun installBack() {
        if (Build.VERSION.SDK_INT >= 33) back = BackCallback(this) { runtime.back() }
        val sync = { back?.arm(runtime.navigation.history.depth > 1); Unit }
        runtime.navigation.changed = sync
        sync()
    }

    @Deprecated("Below API 33, and on 33+ when the dispatcher path is not in use")
    override fun onBackPressed() {
        if (!runtime.back()) super.onBackPressed()
    }

    /** API 33+ only: holds the dispatcher callback, registered while armed. */
    private class BackCallback(private val activity: Activity, onBack: () -> Unit) {
        private val callback = OnBackInvokedCallback { onBack() }
        private var armed = false
        fun arm(on: Boolean) {
            if (on == armed) return
            val dispatcher = activity.onBackInvokedDispatcher
            if (on) dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            else dispatcher.unregisterOnBackInvokedCallback(callback)
            armed = on
        }
        fun disarm() = arm(false)
    }
}

class CounterActivity : RocActivity() { override val library = "counter" }
class TodosActivity : RocActivity() { override val library = "todos" }
class StopwatchActivity : RocActivity() { override val library = "stopwatch" }
class PagesActivity : RocActivity() { override val library = "pages" }
