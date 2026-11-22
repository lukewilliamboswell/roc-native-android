package dev.roc.android

// Local and session storage (design.md, "Storage availability" and "Other
// documents' storage"): STORAGE writes (op 16), reads (FX 2 local, FX 17
// session), declared keys at boot (sections 2 and 11), and the two
// subscriptions a single window cannot raise (FX 18, FX 20). Plain Kotlin:
// the stores are behind `KeyValueStore`, so the desktop smoke test runs it.

/**
 * A string store. Any method may throw when the service cannot be reached;
 * `get` answers null for a key with no value (or a value of another type).
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/** A store held in memory: session storage, and the smoke test's fakes. */
class MemoryStore : KeyValueStore {
    val values = linkedMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

/** One STORAGE command: `op` 1 local set, 2 local remove, 3 session set, 4 session remove. */
class StorageWrite(val op: Int, val key: String, val value: String?)

/** Whether Roc text can hold `s` (well-formed UTF-16: no lone surrogate). */
fun readableText(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
            i += 2
        } else if (Character.isLowSurrogate(c)) return false
        else i++
    }
    return true
}

class StorageFeature(private val local: KeyValueStore, private val session: KeyValueStore) : Feature {
    companion object {
        const val VALUE = 3
        const val MISSING = 4
        const val STORAGE_FAILED = 25
    }

    /** Live StorageWriteFailures subscriptions (FX 20). */
    private val failureSubscribers = linkedSetOf<Int>()

    override val opDecoders: Map<Int, (Reader) -> Any> = mapOf(
        16 to { r ->
            val op = r.u8()
            require(op in 1..4) { "protocol: storage op $op" }
            val key = r.str()
            StorageWrite(op, key, if (op == 1 || op == 3) r.str() else null)
        },
    )

    override val fxDecoders: Map<Int, (Reader) -> Any> = mapOf(
        2 to { r -> r.str() },  // local read
        17 to { r -> r.str() }, // session read
        18 to { r -> r.str() }, // StorageChanges(key): no other window writes here
        20 to { _ -> Unit },    // StorageWriteFailures
    )

    override fun command(host: Host, op: Int, value: Any) {
        val w = value as StorageWrite
        val store = if (w.op <= 2) local else session
        try {
            if (w.value != null) store.set(w.key, w.value) else store.remove(w.key)
        } catch (e: Exception) {
            // A failed write persists nothing, warns, and is reported one
            // task later to each live StorageWriteFailures subscription.
            host.log("storage: write of \"${w.key}\" failed: $e")
            val recipients = failureSubscribers.toList()
            if (recipients.isEmpty()) return
            val payload = bytesOf {
                u8(STORAGE_FAILED); u8(if (w.op <= 2) 1 else 2); u8(2) // Unavailable
                bytes(w.key.toByteArray(Charsets.UTF_8))
            }
            host.schedule(0) { for (id in recipients) host.answer(id, payload, done = false) }
        }
    }

    override fun perform(host: Host, fx: Fx): (() -> Unit)? = when (fx.kind) {
        2, 17 -> {
            val store = if (fx.kind == 2) local else session
            val key = fx.request as String
            // Answered one task later, reading the store then.
            host.schedule(0) { host.answer(fx.id, result(read(store, key))) }
        }
        20 -> {
            failureSubscribers += fx.id
            { failureSubscribers -= fx.id }
        }
        else -> null // 18: stays live and idle until cancelled
    }

    override fun boot(facts: BootFacts, decl: Declarations) {
        facts.storageAvailable = pairs(local, decl.localKeys, facts.storage)
        facts.sessionAvailable = pairs(session, decl.sessionKeys, facts.session)
    }

    override fun stopped(host: Host) { failureSubscribers.clear() }

    /** A read's value, or null for nothing this document can read. */
    private fun read(store: KeyValueStore, key: String): String? =
        try { store.get(key)?.takeIf(::readableText) } catch (e: Exception) { null }

    private fun result(value: String?): ByteArray =
        if (value == null) byteArrayOf(MISSING.toByte())
        else bytesOf { u8(VALUE); bytes(value.toByteArray(Charsets.UTF_8)) }

    /**
     * The declared keys that have a value, into `out`; whether the service
     * is available. One that throws or holds an unreadable value is not, and
     * then has no pairs rather than some of them.
     */
    private fun pairs(store: KeyValueStore, keys: List<String>, out: MutableMap<String, String>): Boolean {
        out.clear()
        try {
            for (k in keys) {
                val v = store.get(k) ?: continue
                if (!readableText(v)) { out.clear(); return false }
                out[k] = v
            }
        } catch (e: Exception) {
            out.clear()
            return false
        }
        return true
    }
}
