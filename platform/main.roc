## The roc-native-android platform runs the roc-signals engine behind a JNI
## host. Ui.roc supplies native elements, and the Kotlin host applies the
## engine's command batches to Android Views.
##
## - `roc build --target=arm64musl` gives `libapp.so` for an arm64 Android
##   device or emulator (`../host/host.zig`, built by `host/build.zig`, freestanding, no libc).
## - `roc build --target=arm64mac` gives a `.dylib` with the same JNI symbols,
##   which the desktop smoke test loads into a plain JVM.
platform ""
	requires {
		[Store : st] for main : Engine.Program(st)
	}
	exposes [Ui]
	packages { engine: "https://github.com/lukewilliamboswell/roc-signals/releases/download/0.8.0-rc1/engine-5CJF6WUFya83N1eWziLTMKHNLS5ZucFAMF5j7vWsyjQy.tar.zst" }
	provides {
		"roc_engine_drop": drop_for_host,
		"roc_engine_event": event_for_host,
		"roc_engine_files": files_for_host,
		"roc_engine_init": init_for_host,
		"roc_engine_keys": keys_for_host,
		"roc_engine_version": version_for_host,
	}
	targets: {
		inputs_dir: "targets/",
		arm64musl: {
			inputs: ["libhost.a", app],
			output: Shared,
		},
		arm64mac: {
			inputs: ["libhost.a", app],
			output: Shared,
		},
	}

import engine.Engine
import engine.EngineHost
import Ui

## One host call's result: the state (owned by the host until the next call),
## this turn's command bytes and the work record (both owned by the host and
## passed back into the next call for reuse).
Step : { cmds : List(U8), state : Handle, work : List(U8) }

## The host owns one opaque box containing the store and the site tree.
## The nested boxed Iter is the #12953 workaround, as in roc-signals.
Handle : Box({ store : Store, sites : Box(Iter(EngineHost.Sites(Store))) })

hold : EngineHost.App(Store) -> Handle
hold = |engine| {
	(store, sites) = EngineHost.split(engine)
	Box.box({
		store,
		sites: Box.box(
			Iter.custom(
				Some(sites),
				Ok(1),
				|seed|
					match seed {
						Some(state) => Ok((state, None))
						None => Err(NoMore)
					},
			),
		),
	})
}

release : Handle -> EngineHost.App(Store)
release = |handle| {
	{ store, sites } = Box.unbox(handle)
	match Box.unbox(sites).next() {
		One({ item, rest: _ }) => EngineHost.join(store, item)
		_ => crash "internal: the host passed an engine handle whose state was already taken"
	}
}

version_for_host : () -> U32
version_for_host = || EngineHost.driver(main).version

## The program's declaration sections (design.md, Appendix B, "Boot"): the
## storage keys, ports, media queries, time-zone span, session keys and
## startup catalogue the host reads before it builds the boot bytes. Unlike
## the browser root, no protocol-facts section 12 is appended: this host
## decodes protocol 23 itself.
keys_for_host : () -> List(U8)
keys_for_host = || (EngineHost.driver(main).keys)()

## Mount with the boot bytes the host built from the declarations.
init_for_host : List(U8) -> Step
init_for_host = |boot| {
	(state, cmds, work) = (EngineHost.driver(main).init)(boot)
	{ cmds, state: hold(state), work }
}

## One turn: a View event (node id, handler number, payload bytes), an effect
## result (node 0xffffffff) or the startup acknowledgement.
event_for_host : Handle, List(U8), List(U8), U32, U32, List(U8) -> Step
event_for_host = |boxed, buf, wbuf, node, handler, payload| {
	(next, cmds, work) = (EngineHost.driver(main).event)(release(boxed), buf, wbuf, node, handler, payload)
	{ cmds, state: hold(next), work }
}

## A files-kind event: the file record and the handles the host minted for its
## files, which move into the engine with the call (as roc-signals'
## `files_for_host`). On the effect node it is a result that carries files.
files_for_host : Handle, List(U8), List(U8), U32, U32, List(U8), List(Box(U64)) -> Step
files_for_host = |boxed, buf, wbuf, node, handler, record, blobs| {
	(next, cmds, work) = (EngineHost.driver(main).files)(release(boxed), buf, wbuf, node, handler, record, blobs)
	{ cmds, state: hold(next), work }
}

drop_for_host : Handle, List(U8), List(U8) -> {}
drop_for_host = |_boxed, _buf, _wbuf| {}
