//! The JNI host of roc-native-android.
//!
//! Like the roc-signals Wasm host it owns exactly three things between
//! calls: the opaque engine handle, the last turn's command bytes and the
//! work record. It never interprets either; each call moves them into Roc
//! and stores what Roc returns. Kotlin (`dev.roc.android.RocEngine`) gets a
//! copy of each command batch as a `byte[]` and applies it to Views.
//! Beside them it keeps the blob heap of file handles (see "File handles"
//! below, and `resources.zig`).
//!
//! Built freestanding (no libc): memory comes from Zig's allocator over
//! `mmap`, so the same `.so` loads on any arm64 Android without the NDK.
//! A Roc crash cannot return into Roc, so it ends the process through JNI
//! `FatalError`, which writes the message to logcat.
const std = @import("std");
const builtin = @import("builtin");
const resources = @import("resources.zig");

/// A Zig panic (a host bug) ends the process the way a Roc crash does.
pub const panic = std.debug.FullPanic(struct {
    fn call(msg: []const u8, _: ?usize) noreturn {
        fatalPrefixed("host panic: ", msg);
    }
}.call);

const RocList = extern struct {
    ptr: ?[*]u8,
    len: usize,
    cap: usize,

    const empty: RocList = .{ .ptr = null, .len = 0, .cap = 0 };

    fn bytes(self: RocList) []const u8 {
        return if (self.ptr) |p| p[0..self.len] else &.{};
    }
};

/// `{ cmds, state, work }`: all pointer-aligned, so alphabetical.
const Step = extern struct {
    cmds: RocList,
    state: *anyopaque,
    work: RocList,
};

extern fn roc_engine_version() callconv(.c) u32;
extern fn roc_engine_init(boot: RocList) callconv(.c) Step;
extern fn roc_engine_keys() callconv(.c) RocList;
extern fn roc_engine_event(state: *anyopaque, buf: RocList, wbuf: RocList, node: u32, handler: u32, payload: RocList) callconv(.c) Step;
extern fn roc_engine_files(state: *anyopaque, buf: RocList, wbuf: RocList, node: u32, handler: u32, record: RocList, blobs: RocList) callconv(.c) Step;
extern fn roc_engine_drop(state: *anyopaque, buf: RocList, wbuf: RocList) callconv(.c) void;

var state: ?*anyopaque = null;
var cmds: RocList = RocList.empty;
var work: RocList = RocList.empty;

// ---------------------------------------------------------------------------
// Allocator: an inline header (the requested size) before each allocation.

// Single-threaded: Kotlin calls the engine only from the UI thread. (The
// thread-safe `smp_allocator` would put a TLS segment in the library, which
// Android's loader refuses before API 29.)
var debug_allocator: std.heap.DebugAllocator(.{ .thread_safe = false, .safety = false }) = .init;
const gpa = debug_allocator.allocator();
const Header = extern struct { size: usize, _pad: usize = 0 };

fn alignment(bytes: usize) std.mem.Alignment {
    return std.mem.Alignment.fromByteUnits(@max(bytes, 16));
}

fn allocRaw(size: usize, align_arg: usize) *anyopaque {
    const a = alignment(align_arg);
    const offset = a.toByteUnits();
    const base = gpa.rawAlloc(offset + size, a, @returnAddress()) orelse fatal("out of memory");
    const user = base + offset;
    const header: *Header = @ptrFromInt(@intFromPtr(user) - @sizeOf(Header));
    header.* = .{ .size = size };
    return user;
}

fn freeRaw(ptr: *anyopaque, align_arg: usize) usize {
    const a = alignment(align_arg);
    const offset = a.toByteUnits();
    const user: [*]u8 = @ptrCast(ptr);
    const header: *const Header = @ptrFromInt(@intFromPtr(user) - @sizeOf(Header));
    const size = header.size;
    gpa.rawFree((user - offset)[0 .. offset + size], a, @returnAddress());
    return size;
}

export fn roc_alloc(size: usize, align_arg: usize) callconv(.c) *anyopaque {
    return allocRaw(size, align_arg);
}

export fn roc_dealloc(ptr: *anyopaque, align_arg: usize) callconv(.c) void {
    // A file handle's slot is retired, not freed (see "File handles").
    switch (blobs.route(ptr)) {
        .not_owned => _ = freeRaw(ptr, align_arg),
        .retired => {},
        .corrupt => fatal("internal: a file handle was released twice or is corrupt"),
    }
}

export fn roc_realloc(ptr: *anyopaque, size: usize, align_arg: usize) callconv(.c) *anyopaque {
    if (blobs.contains(ptr)) fatal("internal: a file handle was resized");
    const user: [*]u8 = @ptrCast(ptr);
    const header: *const Header = @ptrFromInt(@intFromPtr(user) - @sizeOf(Header));
    const copied = @min(header.size, size);
    const next = allocRaw(size, align_arg);
    @memcpy(@as([*]u8, @ptrCast(next))[0..copied], user[0..copied]);
    _ = freeRaw(ptr, align_arg);
    return next;
}

// ---------------------------------------------------------------------------
// dbg text: kept for Kotlin to read (and log) after each call.

var dbg_buf: [8192]u8 = undefined;
var dbg_len: usize = 0;

export fn roc_dbg(bytes: [*]const u8, len: usize) callconv(.c) void {
    const room = dbg_buf.len - dbg_len;
    const n = @min(room, len + 1);
    if (n == 0) return;
    @memcpy(dbg_buf[dbg_len..][0 .. n - 1], bytes[0 .. n - 1]);
    dbg_buf[dbg_len + n - 1] = '\n';
    dbg_len += n;
}

export fn roc_expect_failed(bytes: [*]const u8, len: usize) callconv(.c) void {
    fatalPrefixed("expect failed: ", bytes[0..len]);
}

export fn roc_crashed(bytes: [*]const u8, len: usize) callconv(.c) void {
    fatalPrefixed("roc crashed: ", bytes[0..len]);
}

// ---------------------------------------------------------------------------
// JNI, by hand: the JNIEnv function table indices from jni.h.

const JNIEnv = *const *const [229]?*const anyopaque;
const jobject = ?*anyopaque;
const jbyteArray = ?*anyopaque;
const jint = i32;

const J = struct {
    const FatalError = 18;
    const GetArrayLength = 171;
    const NewByteArray = 176;
    const NewLongArray = 180;
    const GetByteArrayRegion = 200;
    const SetByteArrayRegion = 208;
    const SetLongArrayRegion = 212;
};

fn jfn(env: JNIEnv, comptime index: usize, comptime T: type) T {
    return @ptrCast(@alignCast(env.*.*[index].?));
}

/// The JNIEnv of the call in progress, for `FatalError` from a Roc crash.
var current_env: ?JNIEnv = null;

fn fatal(msg: []const u8) noreturn {
    fatalPrefixed("", msg);
}

var fatal_buf: [2048]u8 = undefined;

fn fatalPrefixed(prefix: []const u8, msg: []const u8) noreturn {
    const text = std.fmt.bufPrint(fatal_buf[0 .. fatal_buf.len - 1], "{s}{s}", .{ prefix, msg[0..@min(msg.len, 1800)] }) catch msg[0..0];
    fatal_buf[text.len] = 0;
    if (current_env) |env| {
        const FatalError = *const fn (JNIEnv, [*:0]const u8) callconv(.c) noreturn;
        jfn(env, J.FatalError, FatalError)(env, @ptrCast(&fatal_buf));
    }
    @trap();
}

fn toJava(env: JNIEnv, bytes: []const u8) jbyteArray {
    const NewByteArray = *const fn (JNIEnv, jint) callconv(.c) jbyteArray;
    const SetByteArrayRegion = *const fn (JNIEnv, jbyteArray, jint, jint, [*]const u8) callconv(.c) void;
    const array = jfn(env, J.NewByteArray, NewByteArray)(env, @intCast(bytes.len));
    if (array != null and bytes.len > 0) jfn(env, J.SetByteArrayRegion, SetByteArrayRegion)(env, array, 0, @intCast(bytes.len), bytes.ptr);
    return array;
}

/// A Java `byte[]` copied into a fresh Roc list (which Roc then owns).
fn fromJava(env: JNIEnv, array: jbyteArray) RocList {
    if (array == null) return RocList.empty;
    const GetArrayLength = *const fn (JNIEnv, jbyteArray) callconv(.c) jint;
    const GetByteArrayRegion = *const fn (JNIEnv, jbyteArray, jint, jint, [*]u8) callconv(.c) void;
    const len: usize = @intCast(jfn(env, J.GetArrayLength, GetArrayLength)(env, array));
    if (len == 0) return RocList.empty;
    // As roc-signals' `payload_reserve`: one usize refcount (1) before the
    // elements, and the capacity shifted left by one (the low bit tags a
    // seamless slice).
    const raw: [*]u8 = @ptrCast(allocRaw(len + @sizeOf(usize), @alignOf(usize)));
    @as(*isize, @ptrCast(@alignCast(raw))).* = 1; // refcount
    const elems = raw + @sizeOf(usize);
    jfn(env, J.GetByteArrayRegion, GetByteArrayRegion)(env, array, 0, @intCast(len), elems);
    return .{ .ptr = elems, .len = len, .cap = len << 1 };
}

fn keep(step: Step) void {
    state = step.state;
    cmds = step.cmds;
    work = step.work;
}

export fn Java_dev_roc_android_RocEngine_nativeVersion(_: JNIEnv, _: jobject) callconv(.c) jint {
    return @intCast(roc_engine_version());
}

/// The program's declaration sections, read before mount.
export fn Java_dev_roc_android_RocEngine_nativeKeys(env: JNIEnv, _: jobject) callconv(.c) jbyteArray {
    current_env = env;
    defer current_env = null;
    dbg_len = 0;
    const keys = roc_engine_keys();
    // Called once per process; the list (which may be a static constant or a
    // slice) is left to Roc rather than freed by hand.
    return toJava(env, keys.bytes());
}

export fn Java_dev_roc_android_RocEngine_nativeInit(env: JNIEnv, _: jobject, boot: jbyteArray) callconv(.c) jbyteArray {
    current_env = env;
    defer current_env = null;
    dbg_len = 0;
    if (state != null) fatal("contract: init while an engine is mounted");
    // A fresh engine holds no handles (a dropped one's were abandoned).
    abandonFiles();
    keep(roc_engine_init(fromJava(env, boot)));
    return toJava(env, cmds.bytes());
}

export fn Java_dev_roc_android_RocEngine_nativeEvent(env: JNIEnv, _: jobject, node: jint, handler: jint, payload: jbyteArray) callconv(.c) jbyteArray {
    current_env = env;
    defer current_env = null;
    dbg_len = 0;
    const held = state orelse fatal("contract: event before init");
    const list = fromJava(env, payload);
    // Clear the slots first: they move into Roc with the call.
    state = null;
    const buf = cmds;
    const wbuf = work;
    cmds = RocList.empty;
    work = RocList.empty;
    keep(roc_engine_event(held, buf, wbuf, @bitCast(node), @bitCast(handler), list));
    return toJava(env, cmds.bytes());
}

export fn Java_dev_roc_android_RocEngine_nativeDrop(env: JNIEnv, _: jobject) callconv(.c) void {
    current_env = env;
    defer current_env = null;
    const held = state orelse return;
    state = null;
    const buf = cmds;
    const wbuf = work;
    cmds = RocList.empty;
    work = RocList.empty;
    roc_engine_drop(held, buf, wbuf);
    // The dropped state is never freed, so no Roc value releases the handles
    // it held: forget them all (Kotlin lets every file go when it stops).
    abandonFiles();
}

// ---------------------------------------------------------------------------
// File handles (roc-signals design.md, "Files"), kept as roc-signals' host
// keeps them. Kotlin (`RocFiles`, `FilesFeature`) holds each file (a content
// Uri, a temp file) under a token; the host mints a `Box(U64)` holding that
// token in its blob heap (`resources.zig`), and Roc's reference count
// decides when the app is done with it. A handle whose last reference went
// is retired, not freed: its token stays valid until Kotlin has performed
// the batch's effects (which may still name it) and then drains the
// released list (`nativeReleased`).

/// The most file handles live at once (design.md, "Boundedness"). Files an
/// event carries beyond the free slots are refused.
const max_files: usize = 64;

var blobs: resources.Heap(.blob, max_files, void) = .{};

/// The handles minted for the next files-kind event, as a Roc
/// `List(Box(U64))` the host owns until the call moves it into Roc.
var pending_blobs: RocList = RocList.empty;

fn nothing(_: anytype) void {}

/// The elements of a fresh Roc list of `n` refcounted elements of `size`
/// bytes, laid out as Roc lays one out: the list's original length, then its
/// refcount (1), each a `usize`, then the elements. Roc frees it two
/// `usize`s before the elements.
fn refcountedList(n: usize, size: usize) [*]u8 {
    const base: [*]u8 = @ptrCast(allocRaw(n * size + 2 * @sizeOf(usize), @alignOf(usize)));
    const words: [*]usize = @ptrCast(@alignCast(base));
    words[0] = n;
    words[1] = 1;
    return base + 2 * @sizeOf(usize);
}

/// Release staged handles no call took: nothing in Roc refers to them yet,
/// so the host retires them itself (their tokens drain as released).
fn dropPendingBlobs() void {
    const p = pending_blobs.ptr orelse return;
    const elems: [*]usize = @ptrCast(@alignCast(p));
    for (0..pending_blobs.len) |i| _ = blobs.route(@ptrFromInt(elems[i] - 8));
    _ = freeRaw(p - 2 * @sizeOf(usize), @alignOf(usize));
    pending_blobs = RocList.empty;
}

fn abandonFiles() void {
    if (pending_blobs.ptr) |p| _ = freeRaw(p - 2 * @sizeOf(usize), @alignOf(usize));
    pending_blobs = RocList.empty;
    blobs.abandon(nothing);
}

const jlongArray = ?*anyopaque;

fn longsToJava(env: JNIEnv, values: []const u64) jlongArray {
    const NewLongArray = *const fn (JNIEnv, jint) callconv(.c) jlongArray;
    const SetLongArrayRegion = *const fn (JNIEnv, jlongArray, jint, jint, [*]const u64) callconv(.c) void;
    const array = jfn(env, J.NewLongArray, NewLongArray)(env, @intCast(values.len));
    if (array != null and values.len > 0) jfn(env, J.SetLongArrayRegion, SetLongArrayRegion)(env, array, 0, @intCast(values.len), values.ptr);
    return array;
}

/// Mint up to `n` handles for the next files-kind event, as many as there
/// are free slots, and return their tokens. Handles staged before and never
/// delivered are released first, so at most one staged set exists.
export fn Java_dev_roc_android_RocFiles_nativeStage(env: JNIEnv, _: jobject, n: jint) callconv(.c) jlongArray {
    current_env = env;
    defer current_env = null;
    dropPendingBlobs();
    var tokens: [max_files]u64 = undefined;
    const want = @min(@as(usize, @intCast(@max(n, 0))), max_files - blobs.live_count);
    if (want == 0) return longsToJava(env, tokens[0..0]);
    // A list of box pointers, each element the address of a handle's payload.
    const elems: [*]usize = @ptrCast(@alignCast(refcountedList(want, @sizeOf(usize))));
    for (0..want) |i| {
        const payload = blobs.mint({}) orelse unreachable;
        elems[i] = @intFromPtr(payload);
        tokens[i] = payload.*;
    }
    pending_blobs = .{ .ptr = @ptrCast(elems), .len = want, .cap = want << 1 };
    return longsToJava(env, tokens[0..want]);
}

/// A files-kind event (Appendix B): `record` is the file record
/// (`minted:u32 refused:u32 (size:u64 name:str type:str)*minted`, or on
/// the effect node a result that carries files) and the staged handles are
/// its files, in order; both move into Roc for one turn, like `nativeEvent`.
export fn Java_dev_roc_android_RocFiles_nativeEvent(env: JNIEnv, _: jobject, node: jint, handler: jint, record: jbyteArray) callconv(.c) jbyteArray {
    current_env = env;
    defer current_env = null;
    dbg_len = 0;
    const held = state orelse fatal("contract: event before init");
    const list = fromJava(env, record);
    const staged = pending_blobs;
    state = null;
    pending_blobs = RocList.empty;
    const buf = cmds;
    const wbuf = work;
    cmds = RocList.empty;
    work = RocList.empty;
    keep(roc_engine_files(held, buf, wbuf, @bitCast(node), @bitCast(handler), list, staged));
    return toJava(env, cmds.bytes());
}

/// After Kotlin has applied a batch and performed its effects: free the
/// slots of the handles Roc released and return their tokens, so Kotlin
/// lets their files go.
export fn Java_dev_roc_android_RocFiles_nativeReleased(env: JNIEnv, _: jobject) callconv(.c) jlongArray {
    var tokens: [max_files]u64 = undefined;
    var n: usize = 0;
    var token: u64 = 0;
    while (n < max_files and blobs.releaseOne(&token, nothing)) : (n += 1) tokens[n] = token;
    return longsToJava(env, tokens[0..n]);
}

/// Live file handles, retired ones included until drained (roc-signals'
/// `metric(6)`).
export fn Java_dev_roc_android_RocFiles_nativeLive(_: JNIEnv, _: jobject) callconv(.c) jint {
    return @intCast(blobs.live_count);
}

export fn Java_dev_roc_android_RocEngine_nativeDbg(env: JNIEnv, _: jobject) callconv(.c) jbyteArray {
    return toJava(env, dbg_buf[0..dbg_len]);
}

// ---------------------------------------------------------------------------
// Without libc, Zig's std asks `getauxval` for the page size (arm64 Android
// runs 4 KiB and 16 KiB pages). A library has no start code to record the
// aux vector, so read it from /proc/self/auxv once, by raw syscalls. An app
// that is not debuggable runs non-dumpable, and then /proc/self/auxv is
// root's and cannot be read; for that case the Kotlin host passes the page
// size (`Os.sysconf(_SC_PAGESIZE)`) before its first call into Roc.

const AT_PAGESZ = 6;
var page_size_from_host: usize = 0;

export fn Java_dev_roc_android_RocEngine_nativePageSize(_: JNIEnv, _: jobject, size: jint) callconv(.c) void {
    page_size_from_host = @intCast(size);
}

var auxv: [64][2]usize = undefined;
var auxv_len: usize = 0;
var auxv_read = false;

fn readAuxv() void {
    auxv_read = true;
    const linux = std.os.linux;
    const fd = linux.open("/proc/self/auxv", .{ .ACCMODE = .RDONLY }, 0);
    if (@as(isize, @bitCast(fd)) < 0) return;
    defer _ = linux.close(@intCast(fd));
    const bytes = std.mem.sliceAsBytes(auxv[0..]);
    var got: usize = 0;
    while (got < bytes.len) {
        const n = linux.read(@intCast(fd), bytes[got..].ptr, bytes.len - got);
        if (@as(isize, @bitCast(n)) <= 0) break;
        got += n;
    }
    auxv_len = got / @sizeOf([2]usize);
}

export fn getauxval(kind: usize) callconv(.c) usize {
    if (builtin.os.tag != .linux) return 0;
    if (!auxv_read) readAuxv();
    for (auxv[0..auxv_len]) |entry| {
        if (entry[0] == kind) return entry[1];
        if (entry[0] == 0) break;
    }
    if (kind == AT_PAGESZ) return page_size_from_host;
    return 0;
}

// ---------------------------------------------------------------------------
// The two C runtime routines a freestanding Android `.so` needs and nothing
// else provides (Roc's builtins and this host call them). On macOS libSystem
// supplies them. This object is compiled with -fno-builtin, so these loops
// are not turned back into calls to themselves.

comptime {
    if (builtin.os.tag == .linux) {
        @export(&memcpyImpl, .{ .name = "memcpy" });
        @export(&memsetImpl, .{ .name = "memset" });
    }
}

fn memcpyImpl(noalias dst: ?[*]u8, noalias src: ?[*]const u8, len: usize) callconv(.c) ?[*]u8 {
    const d = dst.?;
    const s = src.?;
    var i: usize = 0;
    // Word at a time when both are word-aligned, then the tail by bytes.
    if ((@intFromPtr(d) | @intFromPtr(s)) % @sizeOf(usize) == 0) {
        const dw: [*]usize = @ptrCast(@alignCast(d));
        const sw: [*]const usize = @ptrCast(@alignCast(s));
        const words = len / @sizeOf(usize);
        var w: usize = 0;
        while (w < words) : (w += 1) dw[w] = sw[w];
        i = words * @sizeOf(usize);
    }
    while (i < len) : (i += 1) d[i] = s[i];
    return dst;
}

fn memsetImpl(dst: ?[*]u8, c: c_int, len: usize) callconv(.c) ?[*]u8 {
    const d = dst.?;
    const byte: u8 = @truncate(@as(c_uint, @bitCast(c)));
    var i: usize = 0;
    if (@intFromPtr(d) % @sizeOf(usize) == 0) {
        const pattern: usize = @as(usize, byte) * (std.math.maxInt(usize) / 0xff);
        const dw: [*]usize = @ptrCast(@alignCast(d));
        const words = len / @sizeOf(usize);
        var w: usize = 0;
        while (w < words) : (w += 1) dw[w] = pattern;
        i = words * @sizeOf(usize);
    }
    while (i < len) : (i += 1) d[i] = byte;
    return dst;
}
