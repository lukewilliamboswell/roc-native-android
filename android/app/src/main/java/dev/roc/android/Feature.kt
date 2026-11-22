package dev.roc.android

/** One effect request (`FX id kind …`), decoded by the feature that owns its kind. */
class Fx(val id: Int, val kind: Int, val request: Any)

/**
 * A slice of host behaviour: the effect kinds and commands it decodes and
 * performs, and the boot facts it supplies. The host routes each FX kind
 * and each opcode to the one feature that declares it, so features live in
 * their own files and are composed where the host is built (see
 * `RocRuntime` on Android and `Smoke.kt` on the desktop).
 */
interface Feature {
    /** Decoders for the FX kinds this feature owns: read exactly the operands. */
    val fxDecoders: Map<Int, (Reader) -> Any> get() = emptyMap()

    /** Decoders for the commands (opcodes) this feature owns. */
    val opDecoders: Map<Int, (Reader) -> Any> get() = emptyMap()

    /**
     * Start an effect after its batch is applied. Answer with
     * `host.answer(...)`; return how to cancel it, or null when nothing is
     * left to cancel.
     */
    fun perform(host: Host, fx: Fx): (() -> Unit)? = null

    /** Perform a command after its batch is applied, in stream order. */
    fun command(host: Host, op: Int, value: Any) {}

    /**
     * Supply boot facts: before mount, and again for each environment
     * snapshot (`Host.environment`), so read the live values each time.
     */
    fun boot(facts: BootFacts, decl: Declarations) {}

    /** The engine has mounted (and acknowledged startup is queued). */
    fun started(host: Host) {}

    /** The engine is being dropped: release everything held. */
    fun stopped(host: Host) {}
}
