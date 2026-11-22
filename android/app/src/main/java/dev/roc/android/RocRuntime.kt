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
    runtime.navigation,
)

/**
 * The engine of this process, which outlives any one activity: rotating the
 * screen or changing the theme recreates the activity, which reattaches its
 * views to the same mounted engine instead of mounting a new one. The engine
 * is dropped only when its activity finishes (each example runs in its own
 * process, so one runtime per process).
 */
class RocRuntime private constructor(app: Context, val library: String, launchUrl: String = "/") {
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

    /** The history and system back (NavigationFeature.kt); declared before `host`, which reads it. */
    val navigation = NavigationFeature(launchUrl)

    val host = Host(platform, androidFeatures(app, this))

    /** System back: false when the history is at its first entry (let the activity go back). */
    fun back(): Boolean = navigation.back(host)

    companion object {
        private var current: RocRuntime? = null

        /** This process's runtime, mounting the engine in `library` on first use. */
        fun obtain(activity: Activity, library: String): RocRuntime {
            current?.let { return it }
            System.loadLibrary(library)
            val runtime = RocRuntime(activity.applicationContext, library, launchUrl(activity))
            current = runtime
            runtime.activity = activity
            runtime.host.start()
            return runtime
        }

        /**
         * The first history entry: the launching intent's data URI as
         * `path?query#fragment` (a deep link, e.g. `am start -d
         * roc://pages/about`), else `/`.
         */
        private fun launchUrl(activity: Activity): String {
            val data = activity.intent?.data ?: return "/"
            val path = data.encodedPath?.takeIf { it.startsWith("/") } ?: "/"
            return path + (data.encodedQuery?.let { "?$it" } ?: "") + (data.encodedFragment?.let { "#$it" } ?: "")
        }

        /** Drop the engine; the next `obtain` mounts afresh. */
        fun finish() {
            current?.host?.stop()
            current = null
        }
    }
}
