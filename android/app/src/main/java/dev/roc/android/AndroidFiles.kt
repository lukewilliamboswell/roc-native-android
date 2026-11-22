package dev.roc.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * `FilesFeature`'s Android half. The user chooses files with the system
 * chooser (`ACTION_OPEN_DOCUMENT`) and saves with `ACTION_CREATE_DOCUMENT`,
 * both started from the current activity (`RocRuntime.launch`); a chosen
 * file is its content `Uri`, read through the `ContentResolver` while the
 * grant the chooser gave lasts (this process's lifetime: nothing takes a
 * persistable permission). Reads, digests and copies run on one background
 * thread. Downloads and the copies behind a range's URL are temporary files
 * in the app's cache directory, deleted when their handle is released.
 *
 * A file's URL (FX 14) is its content URI as text (`content://…`), or for a
 * range a `file://` URI of a cached copy of the range; an `ImageView` can
 * show either with `setImageURI`, but neither is a web URL.
 */
class AndroidFiles(private val app: Context, private val runtime: RocRuntime) : FileAccess {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "roc-files").apply { isDaemon = true } }

    override fun background(work: () -> Unit) = worker.execute(work)

    override fun choose(multiple: Boolean, types: List<String>, done: (List<HostFile>) -> Unit) {
        // The chooser filters by media type; `accept`-style extensions are dropped.
        val media = types.filter { it.contains('/') }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = media.singleOrNull() ?: "*/*"
            if (media.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, media.toTypedArray())
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val started = runtime.launch(intent) { code, data ->
            done(if (code == Activity.RESULT_OK && data != null) chosen(data).mapNotNull(::describe) else emptyList())
        }
        if (!started) done(emptyList())
    }

    private fun chosen(data: Intent): List<Uri> {
        val clip = data.clipData
        if (clip != null) return List(clip.itemCount) { clip.getItemAt(it).uri }.filterNotNull()
        return listOfNotNull(data.data)
    }

    /** A chosen document's name, size and type, as its provider reports them. */
    private fun describe(uri: Uri): HostFile? {
        val resolver = app.contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (e: Exception) {
            Log.d("roc", "files: cannot describe $uri: $e")
            return null
        }
        // A provider that does not know the size: measure it once.
        if (size < 0) size = try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it >= 0 }
                ?: resolver.openInputStream(uri)?.use { it.skip(Long.MAX_VALUE) } ?: return null
        } catch (e: Exception) { return null }
        return HostFile(name, size, resolver.getType(uri) ?: "", uri)
    }

    override fun open(location: Any): InputStream? = try {
        when (location) {
            is Uri -> app.contentResolver.openInputStream(location)
            is File -> location.takeIf { it.isFile }?.inputStream()
            else -> null
        }
    } catch (e: Exception) { null }

    override fun saveTarget(name: String, type: String, done: (Any?) -> Unit) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType(type.ifEmpty { "application/octet-stream" })
            putExtra(Intent.EXTRA_TITLE, name)
        }
        val started = runtime.launch(intent) { code, data -> done(if (code == Activity.RESULT_OK) data?.data else null) }
        if (!started) done(null)
    }

    override fun create(location: Any): OutputStream? = when (location) {
        is Uri -> app.contentResolver.openOutputStream(location, "wt")
        is File -> location.outputStream()
        else -> null
    }

    override fun tempFile(): File = File.createTempFile("roc-", ".tmp", app.cacheDir)

    override fun url(location: Any): String = when (location) {
        is Uri -> location.toString()
        is File -> Uri.fromFile(location).toString()
        else -> error("unreachable")
    }

    override fun log(text: String) { Log.d("roc", text) }
}
