// The files example on the desktop: `FilesFeature` over plain temp files.
// Choosing mints handles for files queued in `TempFileAccess`, saving writes
// into its directory, and the answers arrive as turns of their own when the
// manual clock advances.

import dev.roc.android.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class TempFileAccess(val dir: File) : FileAccess {
    /** What the next `choose` calls answer, in order (nothing when empty). */
    val toChoose = ArrayDeque<List<HostFile>>()
    val saved = mutableListOf<File>()

    override fun background(work: () -> Unit) = work()
    override fun choose(multiple: Boolean, types: List<String>, done: (List<HostFile>) -> Unit) =
        done(toChoose.removeFirstOrNull() ?: emptyList())
    override fun open(location: Any): InputStream? = (location as File).takeIf { it.isFile }?.inputStream()
    override fun saveTarget(name: String, type: String, done: (Any?) -> Unit) {
        val f = File(dir, name)
        saved += f
        done(f)
    }
    override fun create(location: Any): OutputStream? = (location as File).outputStream()
    override fun tempFile(): File = File.createTempFile("roc-", ".tmp", dir)
    override fun url(location: Any): String = (location as File).toURI().toString()

    fun file(name: String, type: String, bytes: ByteArray): HostFile {
        val f = File(dir, name)
        f.writeBytes(bytes)
        return HostFile(name, bytes.size.toLong(), type, f)
    }
}

/** The features a smoke run of `example` needs beyond the host's own. */
fun filesSmokeFeatures(example: String): List<Feature> =
    if (example == "files") listOf(FilesFeature(smokeFiles)) else emptyList()

val smokeFiles by lazy { TempFileAccess(kotlin.io.path.createTempDirectory("roc-files").toFile()) }

fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun filesSmoke(host: Host, platform: ManualPlatform) {
    val files = host.feature<FilesFeature>()!!
    val access = smokeFiles
    val text = { host.tree.root.textContent() }
    val picker = host.find { it.tag == "input" && it.attr("type") == "file" }
    expect("a file picker labelled", picker.attr("label") == "Choose a file" && picker.handlers.containsKey("change"))
    expect("nothing chosen", text().contains("No file chosen yet."))

    val hello = "Hello, files!\nA second line.\n".toByteArray()
    val first = access.file("hello.txt", "text/plain", hello)
    access.toChoose.add(listOf(first))
    files.pick(host, picker)
    expect("one handle minted", RocFiles.nativeLive() == 1 && files.files.size == 1)
    platform.advance(0)
    println(show(host.tree.root))
    expect("name, size and type", text().contains("hello.txt") && text().contains("${hello.size} bytes, text/plain"))
    expect("text preview", text().contains("Hello, files!\nA second line."))
    expect("sha-256", text().contains("SHA-256: ${sha256Hex(hello)}"))
    expect("file url", text().contains("URL: file:") && text().contains("hello.txt"))

    host.dispatch(host.button("Save a copy"), "click")
    platform.advance(0)
    val copy = access.saved.singleOrNull()
    expect("saved a copy", copy?.name == "Copy of hello.txt" && copy.readBytes().contentEquals(hello))

    // A file that is not UTF-8 text, then a cancelled chooser.
    val bin = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 1, 2)
    access.toChoose.add(listOf(access.file("data.bin", "", bin)))
    files.pick(host, picker)
    platform.advance(0)
    expect("second file shown", text().contains("data.bin") && text().contains("5 bytes, unknown type"))
    expect("binary has no preview", text().contains("(no preview: not UTF-8 text)"))
    expect("binary digest", text().contains("SHA-256: ${sha256Hex(bin)}"))
    files.pick(host, picker) // cancelled: nothing queued
    platform.advance(0)
    expect("cancel keeps the file", text().contains("data.bin"))

    // A third file: nothing holds data.bin now, so Roc released its handle
    // and the host let it go. hello.txt stays: the save outbox still holds
    // its last post.
    access.toChoose.add(listOf(access.file("three.txt", "text/plain", "3".toByteArray())))
    files.pick(host, picker)
    platform.advance(0)
    val held = files.files.values.map { it.name }.sorted()
    println("live handles: ${RocFiles.nativeLive()}, held files: $held")
    expect("released handle let go", held == listOf("hello.txt", "three.txt") && RocFiles.nativeLive() == 2)

    host.stop()
    expect("stop lets every file go", files.files.isEmpty() && RocFiles.nativeLive() == 0)
}
