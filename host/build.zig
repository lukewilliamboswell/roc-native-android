//! Builds the JNI host (host.zig) into each target's libhost.a, the host
//! output the platform bundle ships:
//!
//!     zig build --prefix ../platform/targets      (from host/)
//!
//! installs arm64musl/libhost.a and arm64mac/libhost.a. Gradle's `rocHost`
//! task and `scripts/build.roc` both run exactly this.
//!
//! Each archive holds one object, `host.o`, archived by name from its own
//! directory, so the archive's bytes carry no path from the machine that
//! built it.
//!
//! - arm64musl: freestanding (no libc, no NDK), single-threaded so the .so
//!   has no TLS, and without compiler_rt: host.zig defines memcpy/memset,
//!   the only C runtime routines the Android .so needs.
//! - arm64mac: libSystem is resolved at link time by Roc's own stub.
const std = @import("std");

pub fn build(b: *std.Build) void {
    host(b, "arm64musl", .{ .cpu_arch = .aarch64, .os_tag = .linux, .abi = .none }, true);
    host(b, "arm64mac", .{ .cpu_arch = .aarch64, .os_tag = .macos }, false);
}

fn host(b: *std.Build, name: []const u8, query: std.Target.Query, android: bool) void {
    const object = b.addObject(.{
        .name = "host",
        .root_module = b.createModule(.{
            .root_source_file = b.path("host.zig"),
            .target = b.resolveTargetQuery(query),
            .optimize = .ReleaseSmall,
            .pic = true,
            .single_threaded = if (android) true else null,
            .no_builtin = if (android) true else null,
        }),
    });
    object.bundle_compiler_rt = false;

    // `zig ar` runs in a directory holding only host.o, so the member is
    // named `host.o` whatever the cache path is.
    const stage = b.addWriteFiles();
    _ = stage.addCopyFile(object.getEmittedBin(), "host.o");
    const ar = b.addSystemCommand(&.{ b.graph.zig_exe, "ar", "rcsD" });
    ar.setCwd(stage.getDirectory());
    const archive = ar.addOutputFileArg("libhost.a");
    ar.addArg("host.o");

    b.getInstallStep().dependOn(&b.addInstallFile(archive, b.fmt("{s}/libhost.a", .{name})).step);
}
