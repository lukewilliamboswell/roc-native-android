package dev.roc.android

import android.content.Context
import android.content.SharedPreferences

/**
 * Local storage: one SharedPreferences file per app library, so it outlives
 * the process. Writes are applied asynchronously (SharedPreferences flushes
 * them before the process can be stopped).
 */
class PreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    constructor(app: Context, library: String) : this(app.getSharedPreferences("roc-storage-$library", Context.MODE_PRIVATE))

    // A value of another type is not one this document can read.
    override fun get(key: String): String? = try { prefs.getString(key, null) } catch (e: ClassCastException) { null }
    override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/**
 * Session storage: lives as long as the process, so it survives the engine
 * being dropped and remounted there (like a reload in the same tab), and is
 * gone when the process is.
 */
val processSession = MemoryStore()
