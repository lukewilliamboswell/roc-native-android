package dev.roc.android

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The features this Android host serves. Each lives in its own file; add
 * one here to enable it.
 */
fun androidFeatures(app: Context, runtime: RocRuntime): List<Feature> = listOf(
    HttpFeature(),
    StorageFeature(PreferencesStore(app, runtime.library), processSession),
    EnvironmentFeature(AndroidEnvironment(app, runtime)),
)

/**
 * The engine of this process, which outlives any one activity: rotating the
 * screen or changing the theme recreates the activity, which reattaches its
 * views to the same mounted engine instead of mounting a new one. The engine
 * is dropped only when its activity finishes (each example runs in its own
 * process, so one runtime per process).
 */
class RocRuntime private constructor(app: Context, val library: String) {
    private val main = Handler(Looper.getMainLooper())

    /** The activity currently showing the engine's views, if any. */
    var activity: Activity? = null

    /** Whether the engine's activity is started (onStart to onStop). */
    val visible = Watchable(true)

    /** The configuration's `uiMode` (night bits), as activities report it. */
    val uiMode = Watchable(app.resources.configuration.uiMode)

    private val platform = object : Platform {
        override fun nowMs() = System.currentTimeMillis()
        override fun schedule(ms: Long, task: () -> Unit): () -> Unit {
            val r = Runnable(task)
            main.postDelayed(r, ms)
            return { main.removeCallbacks(r) }
        }
        override fun log(text: String) { Log.d("roc", text) }
        override fun title(text: String) { activity?.title = text }
        override fun focus(node: Node) {}
    }

    val host = Host(platform, androidFeatures(app, this))

    companion object {
        private var current: RocRuntime? = null

        /** This process's runtime, mounting the engine in `library` on first use. */
        fun obtain(activity: Activity, library: String): RocRuntime {
            current?.let { return it }
            System.loadLibrary(library)
            val runtime = RocRuntime(activity.applicationContext, library)
            current = runtime
            runtime.activity = activity
            runtime.host.start()
            return runtime
        }

        /** Drop the engine; the next `obtain` mounts afresh. */
        fun finish() {
            current?.host?.stop()
            current = null
        }
    }
}
