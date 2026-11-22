package dev.roc.android

/**
 * The JNI surface of `host/host.zig`. One engine per process: the
 * host keeps a single opaque engine state, so each example runs in its own
 * process (see AndroidManifest.xml). Load the library before first use.
 */
object RocEngine {
    @JvmStatic external fun nativeVersion(): Int
    /** Mount; returns the first command batch. */
    @JvmStatic external fun nativeInit(): ByteArray
    /** One turn; returns its command batch. */
    @JvmStatic external fun nativeEvent(node: Int, handler: Int, payload: ByteArray): ByteArray
    @JvmStatic external fun nativeDrop()
    /** The `dbg` text the last call printed, one entry per line. */
    @JvmStatic external fun nativeDbg(): ByteArray
}
