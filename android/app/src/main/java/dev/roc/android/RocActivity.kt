package dev.roc.android

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.widget.ScrollView

/**
 * Runs one Roc app: loads its library, mounts the engine and shows the
 * views it draws. Each example is a subclass in its own process, because
 * a library holds one engine and every app exports the same JNI names.
 */
abstract class RocActivity : Activity() {
    abstract val library: String

    private lateinit var host: Host
    private lateinit var renderer: ViewRenderer
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary(library)
        val activity = this
        val platform = object : Platform {
            override fun nowMs() = System.currentTimeMillis()
            override fun schedule(ms: Long, task: () -> Unit): () -> Unit {
                val r = Runnable(task)
                main.postDelayed(r, ms)
                return { main.removeCallbacks(r) }
            }
            override fun log(text: String) { Log.d("roc", text) }
            override fun title(text: String) { activity.title = text }
            override fun focus(node: Node) {}
        }
        renderer = ViewRenderer(this) { host }
        host = Host(platform, renderer)
        host.start()
        val scroll = ScrollView(this).apply { addView(renderer.rootView(host.tree)) }
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

    override fun onDestroy() {
        host.stop()
        super.onDestroy()
    }
}

class CounterActivity : RocActivity() { override val library = "counter" }
class TodosActivity : RocActivity() { override val library = "todos" }
class StopwatchActivity : RocActivity() { override val library = "stopwatch" }
