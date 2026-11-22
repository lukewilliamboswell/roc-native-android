#!/bin/sh
# Bundle the platform: its Roc source (found by `roc bundle`), plus what
# `roc bundle` does not find by itself, which must be named: each target's
# host output and the license files that cover everything inside.
#
#   ROC=roc ./bundle.sh [output-dir]     (run ./build-host.sh first)
set -e
cd "$(dirname "$0")/platform"
OUT=${1:-../build/bundle}
mkdir -p "$OUT"
"${ROC:-roc}" bundle main.roc \
	targets/arm64musl/libhost.a targets/arm64mac/libhost.a \
	LICENSE THIRD_PARTY_LICENSES.md \
	--output-dir "$OUT"
