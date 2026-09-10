#!/usr/bin/env bash
# Start the WLJS Notebook with symjascript as its kernel, for manual testing.
#
#   src/test/wljs/start-wljs.sh            start with the binaries as they are
#   src/test/wljs/start-wljs.sh --build    rebuild symja (parser, core) and the console first
#   src/test/wljs/start-wljs.sh --open     open the browser once the notebook is up
#
# Environment (defaults assume the three repositories are checked out side by side):
#   SYMJA_DIR         symja_android_library checkout
#   WLJS_NOTEBOOK_DIR wljs-notebook checkout, on the symja-backend branch
#   WLJS_LOG          log file (default: target/wljs-notebook.log)
#
# Ctrl-C stops the master kernel and the evaluation kernel it launched.
set -euo pipefail

CONSOLE_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"          # .../matheclipse-console/matheclipse-console
GIT_ROOT="$(cd "$CONSOLE_DIR/../.." && pwd)"                   # the directory holding the checkouts
SYMJA_DIR="${SYMJA_DIR:-$GIT_ROOT/symja_android_library}"
WLJS_NOTEBOOK_DIR="${WLJS_NOTEBOOK_DIR:-$GIT_ROOT/wljs-notebook}"
WLJS_LOG="${WLJS_LOG:-$CONSOLE_DIR/target/wljs-notebook.log}"
SYMJASCRIPT="$CONSOLE_DIR/target/appassembler/bin/symjascript"
URL="http://127.0.0.1:20560"

build=false
open_browser=false
for arg in "$@"; do
  case "$arg" in
    --build) build=true ;;
    --open) open_browser=true ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

if $build; then
  echo ">> building symja (parser, core) in $SYMJA_DIR"
  (cd "$SYMJA_DIR/symja_android_library" && mvn -o -q install -pl matheclipse-parser,matheclipse-core -DskipTests)
  echo ">> building the console in $CONSOLE_DIR"
  (cd "$CONSOLE_DIR" && mvn -o -q package -DskipTests)
fi

[ -x "$SYMJASCRIPT" ] || { echo "no $SYMJASCRIPT - run with --build" >&2; exit 1; }
[ -f "$WLJS_NOTEBOOK_DIR/Scripts/start.wls" ] || { echo "no WLJS checkout at $WLJS_NOTEBOOK_DIR (set WLJS_NOTEBOOK_DIR)" >&2; exit 1; }
[ -f "$WLJS_NOTEBOOK_DIR/Packages/CSockets/Kernel/Symja.wl" ] || {
  echo "the WLJS checkout has no Symja socket adapter - check out the symja-backend branch" >&2; exit 1; }

for port in 20560 20559; do
  if lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1; then
    echo "port $port is in use - is a notebook already running? (lsof -iTCP:$port)" >&2; exit 1
  fi
done

mkdir -p "$(dirname "$WLJS_LOG")"
: > "$WLJS_LOG"
echo ">> starting the notebook from $WLJS_NOTEBOOK_DIR (takes about 90 s), log: $WLJS_LOG"

# the master kernel launches the evaluation kernel itself; its own process group lets Ctrl-C reach both
cd "$WLJS_NOTEBOOK_DIR"
set -m
"$SYMJASCRIPT" -script Scripts/start.wls >>"$WLJS_LOG" 2>&1 &
master=$!
set +m

stop() {
  trap - INT TERM EXIT
  echo; echo ">> stopping the kernels"
  kill -TERM -- "-$master" 2>/dev/null || true
  sleep 2
  kill -KILL -- "-$master" 2>/dev/null || true
  # the evaluation kernel was started with -wstp and may have left the group
  pkill -f "symjascript.*-wstp" 2>/dev/null || pkill -f "SymjaScript.*-wstp" 2>/dev/null || true
  exit 0
}
trap stop INT TERM EXIT

# follow the log until the server says it is up. Do not probe the port meanwhile: the server is
# single threaded and still busy, and connections it cannot serve yet jam it ("SOCKET CLOSED!!!")
tail -n +1 -f "$WLJS_LOG" &
tailer=$!
while kill -0 "$master" 2>/dev/null; do
  if grep -q "Open http" "$WLJS_LOG"; then
    kill "$tailer" 2>/dev/null || true
    echo ">> ready: $URL - the page answers about 30 s after this line"
    echo ">> Ctrl-C stops both kernels; the log keeps going to $WLJS_LOG"
    if $open_browser; then
      sleep 30
      command -v open >/dev/null && open "$URL" || xdg-open "$URL" || true
    fi
    break
  fi
  sleep 2
done
kill "$tailer" 2>/dev/null || true
wait "$master"
