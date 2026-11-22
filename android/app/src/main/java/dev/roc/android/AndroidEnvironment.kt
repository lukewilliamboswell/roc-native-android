package dev.roc.android

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.provider.Settings
import java.security.SecureRandom
import java.time.ZoneId

/**
 * The device's facts for `EnvironmentFeature`: connectivity from
 * ConnectivityManager, visibility from the runtime's activity (started or
 * stopped), dark mode from the configuration's night bits, reduced motion
 * from the animator duration scale, languages from LocaleList and the zone
 * from java.time. Every watcher calls back on the main (engine) thread.
 */
class AndroidEnvironment(private val app: Context, private val runtime: RocRuntime) : EnvironmentSource {
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val random = SecureRandom()

    init {
        // A dark-mode change recreates the activity, which reports its
        // configuration too; the application hears it either way.
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) { runtime.uiMode.set(newConfig.uiMode) }
            @Deprecated("Deprecated in Java") override fun onLowMemory() {}
        })
    }

    override fun online(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun visible() = runtime.visible.value

    override fun media() = MediaFacts(
        dark = (runtime.uiMode.value and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        reducedMotion = Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f,
    )

    override fun nowMs() = System.currentTimeMillis()

    override fun seed() = ByteArray(16).also { random.nextBytes(it) }

    override fun languages(): List<String> {
        val list = LocaleList.getDefault()
        return List(list.size()) { list[it].toLanguageTag() }
    }

    override fun zone(): ZoneId = ZoneId.systemDefault()

    override fun watchOnline(changed: () -> Unit): () -> Unit {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
        }
        connectivity.registerDefaultNetworkCallback(callback, main)
        return { connectivity.unregisterNetworkCallback(callback) }
    }

    override fun watchVisible(changed: () -> Unit) = runtime.visible.watch(changed)

    override fun watchMedia(changed: () -> Unit): () -> Unit {
        val stopMode = runtime.uiMode.watch(changed)
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = changed()
        }
        app.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        return {
            stopMode()
            app.contentResolver.unregisterContentObserver(observer)
        }
    }
}
