package dev.roc.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The features this Android host serves. Each lives in its own file; add
 * one here to enable it.
 */
fun androidFeatures(app: Context, runtime: RocRuntime): List<Feature> = listOf(
    FilesFeature(AndroidFiles(app, runtime)),
)

/**
 * The engine of this process, which outlives any one activity: rotating the
 * screen or changing the theme recreates the activity, which reattaches its
 * views to the same mounted engine instead of mounting a new one. The engine
 * is dropped only when its activity finishes (each example runs in its own
 * process, so one runtime per process).
 */
class RocRuntime private constructor(app: Context) {
    private val main = Handler(Looper.getMainLooper())

    /** The activity currently showing the engine's views, if any. */
    var activity: Activity? = null

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

    // ---- activity results (the file chooser and save dialogs) ------------
    // Kept here, not in an activity, so a result delivered to an activity
    // recreated while the dialog was open still reaches its caller.

    private val results = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextRequest = 1

    /**
     * Start `intent` from the current activity for a result, which `done`
     * receives (result code, data) on the main thread. False when there is no
     * activity or nothing handles the intent.
     */
    fun launch(intent: Intent, done: (Int, Intent?) -> Unit): Boolean {
        val a = activity ?: return false
        val code = nextRequest
        nextRequest = nextRequest % 0xffff + 1
        results[code] = done
        return try {
            a.startActivityForResult(intent, code)
            true
        } catch (e: ActivityNotFoundException) {
            results.remove(code)
            false
        }
    }

    /** An activity received a result (`RocActivity.onActivityResult`). */
    fun activityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        results.remove(requestCode)?.invoke(resultCode, data)
    }

    companion object {
        private var current: RocRuntime? = null

        /** This process's runtime, mounting the engine in `library` on first use. */
        fun obtain(activity: Activity, library: String): RocRuntime {
            current?.let { return it }
            System.loadLibrary(library)
            val runtime = RocRuntime(activity.applicationContext)
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
