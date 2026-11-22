## The roc-native-android platform: the roc-signals reactive engine
## (`Core.roc`, `Engine.roc` and the modules they import, copied verbatim
## from roc-signals) behind a JNI host, with `Ui.roc` as the native
## vocabulary apps write screens in. The engine emits the same command
## stream it emits in the browser; the Kotlin host (`android/`) applies it to
## Android Views. `Web.roc` and the parser package remain only because the
## verbatim engine imports them (it checks element and attribute names).
## `Port.roc` (with `PortJson`, `PortJsonBody`, `PortRegistry`, and `Wire`
## and `WorkerCodec`, which `PortJson` imports, with the cbor package) is
## the typed layer for services the Kotlin host supplies.
##
## - `roc build --target=arm64musl` gives `libapp.so` for an arm64 Android
##   device or emulator (`../host/host.zig`, built by `build-host.sh`, freestanding, no libc).
## - `roc build --target=arm64mac` gives a `.dylib` with the same JNI symbols,
##   which the desktop smoke test loads into a plain JVM.
platform ""
	requires {
		[Store : st] for main : Engine.Program(st)
	}
	exposes [Engine, Port, PortJson, Ui]
	packages { cbor: "https://github.com/lukewilliamboswell/roc-cbor/releases/download/0.6.3/J3xX4n61WoMZKK3tdWTnZBdKaVbi8SvXvmtC5i3qDAER.tar.zst", parser: "https://github.com/lukewilliamboswell/roc-parser/releases/download/6.0.0-rc.5/822J9njyNR1vgyX1KG4ck496dBx3AnLfvn2zHXn15Ksw.tar.zst" }
	provides {
		"roc_engine_drop": drop_for_host,
		"roc_engine_event": event_for_host,
		"roc_engine_init": init_for_host,
		"roc_engine_keys": keys_for_host,
		"roc_engine_version": version_for_host,
	}
	targets: {
		inputs_dir: "targets/",
		arm64musl: { inputs: ["libhost.a", app], output: Shared },
		arm64mac: { inputs: ["libhost.a", app], output: Shared },
	}

import Core
import Engine
import Port
import PortJson
import Ui

## One host call's result: the state (owned by the host until the next call),
## this turn's command bytes and the work record (both owned by the host and
## passed back into the next call for reuse).
Step : { cmds : List(U8), state : Handle, work : List(U8) }

## The host owns one opaque box containing the store and the site tree.
## The nested boxed Iter is the #12953 workaround, as in roc-signals.
Handle : Box({ store : Store, sites : Box(Iter(Core.Sites(Store))) })

hold : Core.App(Store) -> Handle
hold = |engine| {
	(store, sites) = Core.split(engine)
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

release : Handle -> Core.App(Store)
release = |handle| {
	{ store, sites } = Box.unbox(handle)
	match Box.unbox(sites).next() {
		One({ item, rest: _ }) => Core.join(store, item)
		_ => crash "internal: the host passed an engine handle whose state was already taken"
	}
}

version_for_host : () -> U32
version_for_host = || Core.driver(main).version

## The program's declaration sections (design.md, Appendix B, "Boot"): the
## storage keys, ports, media queries, time-zone span, session keys and
## startup catalogue the host reads before it builds the boot bytes. Unlike
## the browser root, no protocol-facts section 12 is appended: this host
## decodes protocol 23 itself.
keys_for_host : () -> List(U8)
keys_for_host = || (Core.driver(main).keys)()

## Mount with the boot bytes the host built from the declarations.
init_for_host : List(U8) -> Step
init_for_host = |boot| {
	(state, cmds, work) = (Core.driver(main).init)(boot)
	{ cmds, state: hold(state), work }
}

## One turn: a View event (node id, handler number, payload bytes), an effect
## result (node 0xffffffff) or the startup acknowledgement.
event_for_host : Handle, List(U8), List(U8), U32, U32, List(U8) -> Step
event_for_host = |boxed, buf, wbuf, node, handler, payload| {
	(next, cmds, work) = (Core.driver(main).event)(release(boxed), buf, wbuf, node, handler, payload)
	{ cmds, state: hold(next), work }
}

drop_for_host : Handle, List(U8), List(U8) -> {}
drop_for_host = |_boxed, _buf, _wbuf| {}
