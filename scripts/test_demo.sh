#!/usr/bin/env bash
# corral demo-mode suite — the isolation contract for `corral demo` (plan §14 rows D2–D8, D11).
#
# `corral demo` supervises a second, isolated daemon whose pool is in-JVM. These rows are the
# safety contract, one per invariant:
#   D2  I1  no external binary is ever executed (booby-trapped PATH: adb, emulator, scrcpy, gradle)
#   D3  I2  the SDK platform-tools/adb shim is never touched, even with an absolute CORRAL_ADB
#   D4  I3  nothing in the real ~/.corral or ~/.config/corral is read-modified or created
#   D5  I4  no gate is installed (no PATH wrapper, no gradle init.d, no systemd unit)
#   D6  I5  no update feed is fetched and `update --apply` installs nothing
#   D7  I6/I8 a real daemon on 8798 keeps serving while the demo runs on 8799; DBs and leases stay apart
#   D8  I6  a busy port moves the demo to the next free one; two demos run side by side
#   D11 I10 SIGINT stops it (POSIX only: Git Bash cannot signal a native JVM), a killed supervisor
#           leaves no daemon JVM, and cleanup removes marked homes only
#
# Isolation mirrors scripts/test_e2e.sh: a mktemp tree, `-Duser.home` at a sandbox, `-Djava.io.tmpdir`
# at a private home directory, kill by exact pid. Nothing here starts, stops or installs the real
# ~/.corral daemon, and no row takes a real device lease.
#
# Runs from the source tree (rebuilds the jar) and from the deployed bundle (uses the jar beside this
# script). `CORRAL_JAR=<other jar>` runs the rows against that jar instead — that is how the rows are
# demonstrated to fail on a commit without the feature (they must fail because `corral demo` does not
# exist, never because this script is missing).
set -u

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -n "${CORRAL_JAR:-}" ]; then
  SRC="$SELF"; ROOT=""; JAR="$CORRAL_JAR"; BUILD=0
elif [ -f "$SELF/corral-daemon-all.jar" ] && [ -f "$SELF/adb-wrapper" ]; then
  SRC="$SELF"; ROOT=""; JAR="$SELF/corral-daemon-all.jar"; BUILD=0
else
  SRC="$SELF"; ROOT=$(cd "$SELF/.." && pwd); JAR="$ROOT/corral-daemon/build/libs/corral-daemon-all.jar"; BUILD=1
fi

pass=0; fail=0; skipped=0
check() { # check <name> <cond...>
  local name="$1"; shift
  if "$@"; then printf 'ok %d %s\n' "$((++pass))" "$name"
  else printf 'FAIL %d %s\n' "$((++fail))" "$name"; fi
}
skip() { printf 'skip %d %s (%s)\n' "$((++skipped))" "$1" "$2"; }
note() { printf '%s\n' "  $*"; }

# --- host capabilities -------------------------------------------------------------------------
# Git Bash can start a native JVM but cannot deliver SIGINT/SIGTERM to it: `kill -INT` is silently
# dropped and `kill -TERM` is TerminateProcess (no shutdown hook). Rows whose MEANING is the POSIX
# signal contract are SKIPPED here, loudly, rather than passing vacuously — the same convention as
# the F6 rows in test_wrapper.sh.
posix_signals=1
case "$(uname -s 2>/dev/null || true)" in MINGW*|MSYS*|CYGWIN*) posix_signals=0;; esac

# --- setup ------------------------------------------------------------------------------------
if [ "$BUILD" = 1 ]; then
  (cd "$ROOT" && ./gradlew :corral-daemon:shadowJar >/dev/null 2>&1) || { echo "build failed"; exit 1; }
fi
[ -f "$JAR" ] || { echo "jar not found: $JAR (run from the source tree or beside corral-daemon-all.jar)"; exit 1; }

# Git Bash hands Java an MSYS path; Java needs the Windows form of anything it is told to open.
npath() { cygpath -m "$1" 2>/dev/null || printf '%s' "$1"; }
upath() { cygpath -u "$1" 2>/dev/null || printf '%s' "$1"; }
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"; JAVA_BIN=${JAVA_BIN:-java}

TMP=$(mktemp -d)
TMPW=$(npath "$TMP")
SBX="$TMP/home"            # the sandbox user.home: the "real home" the demo must never touch
SBXW=$(npath "$SBX")
DEMOHOMES="$TMP/tmp"       # the supervisor's java.io.tmpdir: only demo homes live here
TRAP="$TMP/trap"           # booby-trapped PATH: each binary writes a hit marker
HITS="$TMP/trap-hits"
DEMO_PID=""
REAL_PID=""
DEMO_PORT=""
DEMO_HOME=""
DEMO_LOG="$TMP/demo.log"

# A bash `$!` is a CYGWIN pid; taskkill resolves WINDOWS pids. Handing it the Cygwin number either
# finds nothing (this branch used to be dead on Git Bash) or, on a collision, kills an unrelated
# process tree — on a dev box possibly the real daemon. Resolve the Windows pid first, and never
# fall back to taskkill once the process is gone. `msys_kill` is the one place that mapping lives.
msys_kill() { # msys_kill <cygwin pid> <taskkill flags...>: taskkill on the Windows pid, else kill -9
  local p="$1" wp=""; shift
  [ -n "$p" ] || return 1
  if [ -r "/proc/$p/winpid" ]; then wp=$(cat "/proc/$p/winpid" 2>/dev/null); fi
  if [ -n "$wp" ] && MSYS2_ARG_CONV_EXCL='*' taskkill /F "$@" /PID "$wp" >/dev/null 2>&1; then return 0; fi
  kill -9 "$p" 2>/dev/null
}
kill_tree() { # kill_tree <pid>: TerminateProcess on the process AND its children (MSYS `kill` leaves the JVM)
  local p="$1"
  [ -n "$p" ] || return 0
  case "$(uname -s 2>/dev/null || true)" in
    MINGW*|MSYS*|CYGWIN*) msys_kill "$p" /T ;;
    *) kill -9 "$p" 2>/dev/null ;;
  esac
}
kill_supervisor_only() { # kill_supervisor_only <pid>: the supervisor alone — the daemon must notice by itself
  local p="$1"
  [ -n "$p" ] || return 0
  case "$(uname -s 2>/dev/null || true)" in
    MINGW*|MSYS*|CYGWIN*) msys_kill "$p" ;;
    *) kill -9 "$p" 2>/dev/null ;;
  esac
}
cleanup() {
  trap - EXIT
  [ -n "$DEMO_PID" ] && kill_tree "$DEMO_PID"
  [ -n "$REAL_PID" ] && kill_tree "$REAL_PID"
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$SBX/.corral" "$SBX/.config/corral" "$DEMOHOMES" "$TRAP"
TRAP_HITS="$HITS"
for b in adb emulator scrcpy gradle; do
  printf '#!/bin/sh\necho "%s $*" >> "%s"\nexit 1\n' "$b" "$HITS" > "$TRAP/$b"
  chmod +x "$TRAP/$b"
done

# --- helpers -----------------------------------------------------------------------------------
hash_of() { # hash_of <file>: content hash, or "-" for a missing file
  [ -f "$1" ] || { echo "-"; return 0; }
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
  else cksum "$1" | cut -d' ' -f1; fi
}
file_sig() { # file_sig <file>: hash + mtime, so a rewrite that restores the bytes still shows
  if [ -f "$1" ]; then printf '%s %s' "$(hash_of "$1")" "$(stat -c %Y "$1" 2>/dev/null || echo 0)"; else echo "absent"; fi
}
tree_sig() { # tree_sig <dir>: every path under it, plus file sigs — catches a file created anywhere
  [ -d "$1" ] || { echo "absent"; return 0; }
  ( cd "$1" && find . -type f | LC_ALL=C sort | while IFS= read -r f; do printf '%s %s\n' "$f" "$(file_sig "$f")"; done )
}
api_ok() { curl -s --max-time 1 "http://127.0.0.1:$1/api/devices" >/dev/null 2>&1; }
api_body() { curl -s --max-time 2 "http://127.0.0.1:$1$2" 2>/dev/null; }
free_port() { # free_port <start>: the first port in the +20 scan window that nothing answers on
  local p; for p in $(seq "$1" $(( $1 + 20 ))); do api_ok "$p" || { printf '%s' "$p"; return 0; }; done; return 1
}
wait_api() { # wait_api <port>: bounded readiness poll (10 s), never a fixed sleep
  local i; for i in $(seq 1 100); do api_ok "$1" && return 0; sleep 0.1; done; return 1
}
wait_api_gone() { # wait_api_gone <port> [tenths]: the port stops answering
  local i n="${2:-20}"; for i in $(seq 1 "$n"); do api_ok "$1" || return 0; sleep 0.1; done; return 1
}
proc_alive() { # proc_alive <windows pid>: the JVM is really gone, not just its port
  local p="$1"
  [ -n "$p" ] && [ "$p" != "0" ] || return 1
  if ps -W >/dev/null 2>&1; then ps -W | awk -v p="$p" '$4==p {f=1} END {exit !f}'
  else MSYS_NO_PATHCONV=1 tasklist /FI "PID eq $p" 2>/dev/null | grep -q " $p "; fi
}
wait_proc_gone() { # wait_proc_gone <pid> [tenths]: bounded wait for a JVM to leave
  local i n="${2:-30}"; for i in $(seq 1 "$n"); do proc_alive "$1" || return 0; sleep 0.1; done; return 1
}
marker_get() { # marker_get <home> <key>
  sed -n "s/^$2=//p" "$1/.corral-demo" 2>/dev/null | head -1
}

demo_start() { # demo_start [args...]: launch the supervisor, return once its daemon answers
  : > "$DEMO_LOG"
  PATH="$TRAP:$PATH" HOME="$SBX" \
    "$JAVA_BIN" -Duser.home="$SBXW" -Djava.io.tmpdir="$(npath "$DEMOHOMES")" -cp "$JAR" dev.corral.CorralCli \
      demo "$@" >>"$DEMO_LOG" 2>&1 &
  DEMO_PID=$!
  local i
  for i in $(seq 1 150); do grep -q '^demo: port ' "$DEMO_LOG" 2>/dev/null && break; sleep 0.1; done
  DEMO_PORT=$(sed -n 's/^demo: port \([0-9][0-9]*\)$/\1/p' "$DEMO_LOG" | head -1)
  DEMO_HOME=$(sed -n 's/^demo: home \(.*\)$/\1/p' "$DEMO_LOG" | head -1)
  if [ -z "$DEMO_PORT" ] || [ -z "$DEMO_HOME" ]; then
    note "corral demo did not reach its banner; output was:"
    sed -n '1,12p' "$DEMO_LOG" | sed 's/^/    /'
    kill_tree "$DEMO_PID"; wait "$DEMO_PID" 2>/dev/null; DEMO_PID=""; return 1
  fi
  DEMO_HOME=$(upath "$DEMO_HOME")
  wait_api "$DEMO_PORT" || {
    note "the demo daemon never answered on $DEMO_PORT; output was:"
    sed -n '1,12p' "$DEMO_LOG" | sed 's/^/    /'
    kill_tree "$DEMO_PID"; wait "$DEMO_PID" 2>/dev/null; DEMO_PID=""; return 1
  }
  return 0
}
demo_stop() {
  [ -n "$DEMO_PID" ] && kill_tree "$DEMO_PID"
  [ -n "$DEMO_PID" ] && wait "$DEMO_PID" 2>/dev/null
  DEMO_PID=""; DEMO_PORT=""; DEMO_HOME=""
}
demo_cli() { # demo_cli <url> <args...>: the CLI against a given daemon
  local url="$1"; shift
  HOME="$SBX" CORRAL_URL="$url" "$JAVA_BIN" -Duser.home="$SBXW" -cp "$JAR" dev.corral.CorralCli "$@"
}

# Seeded "real home": the files I3 names. Every row that starts a demo runs against this sandbox.
printf 'seeded-db\n' > "$SBX/.corral/corral.db"
printf 'readonly=-s\nvalueflags=-p\nshell_skip=bash\ninit_floor=systemd\n' > "$SBX/.config/corral/rules"
printf 'CORRAL_SCREENSHOTS=strict\n' > "$SBX/.config/corral/daemon.env"
REAL_HOME_SIG_BEFORE=$(tree_sig "$SBX")

# A fake SDK tree whose platform-tools/adb carries a sentinel: the shim heals by moving that file
# aside and writing a wrapper in its place, so the hash is the canary (I2). It must be a pair
# `SdkShim.ensure` actually manages — `adb.real` beside it, plus a `$HOME/bin/adb` carrying the
# `corral adb wrapper` marker — or `realPathOf` returns null and the heal never fires, which would
# make D3 pass with the demo gate deleted (review F2).
SDK="$TMP/sdk"; mkdir -p "$SDK/platform-tools"
printf '#!/bin/sh\necho "sentinel-sdk-adb $*"\n' > "$SDK/platform-tools/adb"
chmod +x "$SDK/platform-tools/adb"
printf 'sentinel-real-adb\n' > "$SDK/platform-tools/adb.real"   # the .real sibling realPathOf looks for
SDK_ADB_SIG_BEFORE=$(file_sig "$SDK/platform-tools/adb")
SDK_REAL_SIG_BEFORE=$(file_sig "$SDK/platform-tools/adb.real")
# The wrapper source (`$HOME/bin/adb`) is seeded in D3's own private home, not in $SBX: putting a
# `corral adb wrapper` file in the shared sandbox home would make D5's "no PATH wrapper" vacuous.

# Our own "real" daemon for D7: a normal daemon (no demo flag) on 8798 with a fake inventory.
REAL_DIR="$TMP/real"; mkdir -p "$REAL_DIR"
printf 'realdev device Pixel_9 14\n' > "$REAL_DIR/devices"
printf '#!/bin/sh\necho "real-adb $*"\n' > "$REAL_DIR/adb"; chmod +x "$REAL_DIR/adb"
REAL_URL="http://127.0.0.1:8798"
REAL_DB="$REAL_DIR/corral.db"

# The ports these rows own. Anything already answering there is another suite or a live install:
# name it and leave it alone — D7 reports it as its own failure reason rather than touching it.
for p in 8798 8799 8841; do
  api_ok "$p" && note "something already answers on $p; no row will disturb it"
done

# ---------------------------------------------------------------------------------------------
# D2 (I1) — booby-trapped PATH: no adb/emulator/scrcpy/gradle may ever run, and the demo must
# still serve its pool (a row that passes because nothing started proves nothing).
# ---------------------------------------------------------------------------------------------
d2_no_external_binary() {
  rm -f "$HITS"
  demo_start || return 1
  local n; n=$(api_body "$DEMO_PORT" "/api/devices" | grep -o '"serial"' | wc -l | tr -d ' ')
  local hits_marker="no"
  [ -f "$HITS" ] && hits_marker="yes"
  demo_stop
  [ "$n" -ge 3 ] || { note "the demo pool served $n device(s), expected 3"; return 1; }
  if [ "$hits_marker" = "yes" ]; then
    note "an external binary ran: $(head -3 "$HITS" | tr '\n' ';')"; return 1
  fi
  return 0
}
check "D2 demo executes no external binary (adb/emulator/scrcpy/gradle booby-trapped on PATH)" d2_no_external_binary

# ---------------------------------------------------------------------------------------------
# D3 (I2) — an absolute CORRAL_ADB pointing at a MANAGED shim pair is exactly what makes the
# self-heal write. Two halves, because they fail differently:
#   A. under `corral demo` the absolute path is inherited by the supervisor and stripped before the
#      child starts (DEMO_UNSET), so the daemon cannot even resolve it — assert both the stripped
#      value and the untouched file;
#   B. a hand-started demo daemon that DOES resolve the pair (`CORRAL_DEMO=1` + absolute
#      CORRAL_ADB) must still leave platform-tools/adb byte-, mode- and mtime-identical for more
#      than the 10 s self-heal tick. This is the half that fails when the demo gate is removed.
# ---------------------------------------------------------------------------------------------
d3_sdk_shim_untouched() {
  CORRAL_ADB="$SDK/platform-tools/adb" demo_start || return 1
  sleep 2
  local child_adb
  child_adb=$(api_body "$DEMO_PORT" "/api/config" \
    | grep -o '"key":"CORRAL_ADB","value":"[^"]*"' | sed 's/.*"value":"//; s/"$//')
  demo_stop
  [ "$child_adb" = "adb" ] || {
    note "the demo daemon resolved CORRAL_ADB '$child_adb' - the supervisor must strip the inherited path"
    return 1; }

  local d3home="$TMP/d3home" d3port
  mkdir -p "$d3home/bin" "$d3home/tmp"
  printf '#!/bin/sh\necho "corral adb wrapper (suite)"\n' > "$d3home/bin/adb"   # the wrapper source SdkShim reads
  d3port=$(free_port 8861) || { note "no free port in 8861..8881 for the direct demo daemon"; return 1; }
  CORRAL_DEMO=1 CORRAL_ADB="$SDK/platform-tools/adb" CORRAL_PORT="$d3port" \
    CORRAL_DB="$(npath "$d3home/corral.db")" CORRAL_RULES="$(npath "$d3home/rules")" \
    CORRAL_ENV_FILE="$(npath "$d3home/daemon.env")" CORRAL_UPDATE_CHECK=off HOME="$SBX" \
    "$JAVA_BIN" -Duser.home="$(npath "$d3home")" -Djava.io.tmpdir="$(npath "$d3home/tmp")" \
    -jar "$JAR" >>"$TMP/d3.log" 2>&1 &
  local d3pid=$!
  wait_api "$d3port" || {
    note "the direct demo daemon never answered on $d3port:"
    sed -n '1,8p' "$TMP/d3.log" | sed 's/^/    /'
    kill_tree "$d3pid"; wait "$d3pid" 2>/dev/null; return 1; }
  sleep 15     # > the 10 s tick: the periodic heal must be inert too
  local still_up=1; api_ok "$d3port" || still_up=0
  kill_tree "$d3pid"; wait "$d3pid" 2>/dev/null
  [ "$still_up" = 1 ] || { note "the direct demo daemon died during the 15 s window"; return 1; }

  local after real_after
  after=$(file_sig "$SDK/platform-tools/adb")
  real_after=$(file_sig "$SDK/platform-tools/adb.real")
  [ "$after" = "$SDK_ADB_SIG_BEFORE" ] || { note "platform-tools/adb changed: $SDK_ADB_SIG_BEFORE -> $after"; return 1; }
  [ "$real_after" = "$SDK_REAL_SIG_BEFORE" ] || { note "platform-tools/adb.real changed: $SDK_REAL_SIG_BEFORE -> $real_after"; return 1; }
  return 0
}
check "D3 demo never touches the SDK platform-tools/adb shim (absolute CORRAL_ADB, supervised and direct, 15 s)" d3_sdk_shim_untouched

# ---------------------------------------------------------------------------------------------
# D4 (I3) — the seeded real home: hashes, mtimes and the file set must be identical afterwards.
# ---------------------------------------------------------------------------------------------
d4_real_home_untouched() {
  # an absolute CORRAL_RULES / CORRAL_ENV_FILE pointing into the real home is the tempting failure
  CORRAL_RULES="$SBX/.config/corral/rules" CORRAL_ENV_FILE="$SBX/.config/corral/daemon.env" \
    demo_start || return 1
  local demo_home="$DEMO_HOME"
  sleep 2
  # The self-heal is gated, not merely redirected: demo mode's rules file is $DEMO_HOME/rules (CORRAL_RULES
  # points there), so delete it and wait past a 10 s tick. An ungated heal recreates it from the jar
  # resource; a gated one never does. (Watching $DEMO_HOME/.config/corral/rules could never fire — the
  # daemon does not use that path in demo mode.)
  rm -f "$demo_home/rules"
  sleep 12
  local healed="no"
  [ -e "$demo_home/rules" ] && healed="yes"
  demo_stop
  [ "$healed" = "no" ] || { note "the rules self-heal ran inside the demo home: $demo_home/rules came back"; return 1; }
  local after; after=$(tree_sig "$SBX")
  if [ "$after" != "$REAL_HOME_SIG_BEFORE" ]; then
    note "the real home changed:"; diff <(printf '%s\n' "$REAL_HOME_SIG_BEFORE") \
      <(printf '%s\n' "$after") | sed -n '1,8p' | sed 's/^/    /'
    return 1
  fi
  return 0
}
check "D4 demo reads/writes nothing under the real ~/.corral or ~/.config/corral (I3)" d4_real_home_untouched

# ---------------------------------------------------------------------------------------------
# D5 (I4) — no gate: no PATH wrapper, no gradle init.d, no systemd unit, no Scheduled Task.
# ---------------------------------------------------------------------------------------------
d5_no_gate_installed() {
  demo_start || return 1
  sleep 1
  demo_stop
  local bad=""
  [ -e "$SBX/bin/adb" ] && bad="$bad PATH-wrapper"
  [ -e "$SBX/.gradle/init.d" ] && bad="$bad gradle-init.d"
  [ -e "$SBX/.config/systemd/user/corral.service" ] && bad="$bad systemd-unit"
  [ -e "$SBX/.local/share/applications/Corral.desktop" ] && bad="$bad ui-launcher"
  if [ -n "$bad" ]; then note "a gate was installed:$bad"; return 1; fi
  return 0
}
check "D5 demo installs no gate (no PATH wrapper, gradle init.d, unit or task)" d5_no_gate_installed

# ---------------------------------------------------------------------------------------------
# D6 (I5) — updates are off server-side (even for ?force, the only route that re-checks), the log
# names no feed, and `update --apply` installs nothing.
# ---------------------------------------------------------------------------------------------
d6_no_network() {
  demo_start || return 1
  local cached forced
  cached=$(api_body "$DEMO_PORT" "/api/update")
  forced=$(api_body "$DEMO_PORT" "/api/update?force")
  local applied=0; demo_cli "http://127.0.0.1:$DEMO_PORT" update --apply >"$TMP/update.out" 2>&1 || applied=1
  demo_stop
  case "$cached" in *'"checksEnabled":false'*) ;; *) note "GET /api/update said: $cached"; return 1;; esac
  case "$forced" in *'"state":"DISABLED"'*) ;; *) note "GET /api/update?force said: $forced"; return 1;; esac
  [ "$applied" = 1 ] || { note "update --apply did not refuse"; return 1; }
  if grep -qi 'github\.com\|api\.github' "$DEMO_LOG"; then note "the demo log names the update feed"; return 1; fi
  return 0
}
check "D6 demo checks for no update and refuses update --apply (I5)" d6_no_network

# ---------------------------------------------------------------------------------------------
# D7 (I6, I8) — a normal daemon on 8798 and the demo on 8799 at the same time: both answer, the
# databases differ, and a lease in one is invisible in the other.
# ---------------------------------------------------------------------------------------------
d7_side_by_side() {
  if api_ok 8798; then
    note "a daemon already answers on 8798 — refusing to start a second one there"; return 1
  fi
  if api_ok 8799; then
    note "port 8799 is busy (is test_wrapper.sh running?)"; return 1
  fi
  CORRAL_FAKE_DEVICES="$REAL_DIR/devices" CORRAL_DB="$REAL_DB" CORRAL_RULES="$REAL_DIR/rules" \
    CORRAL_ENV_FILE="$REAL_DIR/daemon.env" CORRAL_ADB="$REAL_DIR/adb" CORRAL_PORT=8798 \
    CORRAL_UPDATE_CHECK=off HOME="$SBX" \
    "$JAVA_BIN" -Duser.home="$(npath "$REAL_DIR")" -jar "$JAR" >>"$TMP/real.log" 2>&1 &
  REAL_PID=$!
  wait_api 8798 || { note "the real daemon never answered on 8798"; sed -n '1,10p' "$TMP/real.log"; return 1; }

  demo_start --port 8799 || return 1
  local started_port="$DEMO_PORT"
  local claimed=0
  demo_cli "http://127.0.0.1:$started_port" claim DEMO01234567 >"$TMP/claim.out" 2>&1 || claimed=1

  local real_up=0; api_ok 8798 && real_up=1
  local real_devices demo_devices real_has_lease demo_has_lease
  real_devices=$(api_body 8798 "/api/devices" | grep -o '"serial"' | wc -l | tr -d ' ')
  demo_devices=$(api_body "$started_port" "/api/devices" | grep -o '"serial"' | wc -l | tr -d ' ')
  api_body 8798 "/api/status" | grep -q '"lease":{' && real_has_lease=1 || real_has_lease=0
  api_body "$started_port" "/api/status" | grep -q 'DEMO01234567","lease":{' && demo_has_lease=1 || demo_has_lease=0
  local demo_db="$DEMO_HOME/corral.db" demo_db_ok=0
  [ -f "$demo_db" ] && demo_db_ok=1

  demo_stop
  [ -n "$REAL_PID" ] && { kill_tree "$REAL_PID"; wait "$REAL_PID" 2>/dev/null; }; REAL_PID=""

  [ "$started_port" = "8799" ] || { note "the demo did not take 8799 while 8798 was taken (got $started_port)"; return 1; }
  [ "$real_up" = 1 ] || { note "the real daemon stopped answering while the demo ran"; return 1; }
  [ "$real_devices" = "1" ] || { note "the real daemon lost its pool ($real_devices devices)"; return 1; }
  [ "$demo_devices" = "3" ] || { note "the demo pool served $demo_devices device(s), expected 3"; return 1; }
  [ "$claimed" = 0 ] || { note "claiming a demo device failed: $(head -3 "$TMP/claim.out")"; return 1; }
  [ "$real_has_lease" = 0 ] || { note "the demo's lease appeared in the real daemon"; return 1; }
  [ "$demo_has_lease" = 1 ] || { note "the demo daemon did not record its own lease"; return 1; }
  [ "$demo_db_ok" = 1 ] || { note "no separate demo database at $demo_db"; return 1; }
  [ "$demo_db" != "$REAL_DB" ] || { note "the demo is using the real database"; return 1; }
  return 0
}
check "D7 a real daemon on 8798 keeps serving while the demo runs on 8799; leases and DBs stay apart" d7_side_by_side

# ---------------------------------------------------------------------------------------------
# D8 (I6) — a busy start port moves it to the next free one (and says so), and two demos coexist.
# ---------------------------------------------------------------------------------------------
d8_busy_port_and_concurrent_demos() {
  demo_start --port 8841 || return 1
  local first_port="$DEMO_PORT" first_home="$DEMO_HOME" first_pid="$DEMO_PID"
  # a second demo asking for the same port must move, and must say which port it moved to
  : > "$TMP/demo2.log"
  PATH="$TRAP:$PATH" HOME="$SBX" \
    "$JAVA_BIN" -Duser.home="$SBXW" -Djava.io.tmpdir="$(npath "$DEMOHOMES")" -cp "$JAR" dev.corral.CorralCli \
      demo --port 8841 >>"$TMP/demo2.log" 2>&1 &
  local second_pid=$!
  local i; for i in $(seq 1 150); do grep -q '^demo: port ' "$TMP/demo2.log" 2>/dev/null && break; sleep 0.1; done
  local second_port; second_port=$(sed -n 's/^demo: port \([0-9][0-9]*\)$/\1/p' "$TMP/demo2.log" | head -1)
  local says_busy=0; grep -q '8841 is busy' "$TMP/demo2.log" && says_busy=1
  wait_api "$second_port" || { note "the second demo never answered on '$second_port'"; }
  local both=0
  api_ok "$first_port" && api_ok "$second_port" && both=1
  local second_devices; second_devices=$(api_body "$second_port" "/api/devices" | grep -o '"serial"' | wc -l | tr -d ' ')
  kill_tree "$second_pid"; wait "$second_pid" 2>/dev/null
  DEMO_PID="$first_pid"; demo_stop
  [ "$first_port" = "8841" ] || { note "the first demo did not take 8841 (got '$first_port')"; return 1; }
  [ "$says_busy" = 1 ] || { note "the second demo never said 8841 was busy: $(head -3 "$TMP/demo2.log")"; return 1; }
  [ -n "$second_port" ] && [ "$second_port" != "8841" ] || { note "the second demo did not move off the busy port"; return 1; }
  [ "$both" = 1 ] || { note "the two demos did not serve side by side"; return 1; }
  [ "$second_devices" = "3" ] || { note "the second demo pool served $second_devices device(s)"; return 1; }
  return 0
}
check "D8 a busy port moves the demo to the next free one; two demos run side by side" d8_busy_port_and_concurrent_demos

# ---------------------------------------------------------------------------------------------
# D11a (I10, POSIX only) — SIGINT: exit within 2 s, no daemon JVM left, home removed.
# ---------------------------------------------------------------------------------------------
d11a_sigint_stops_cleanly() {
  demo_start || return 1
  local port="$DEMO_PORT" home="$DEMO_HOME" sup="$DEMO_PID" daemon_pid
  daemon_pid=$(marker_get "$home" daemon)
  local t0 t1
  t0=$(date +%s%N)
  kill -INT "$sup" 2>/dev/null
  local i; for i in $(seq 1 30); do kill -0 "$sup" 2>/dev/null || break; sleep 0.1; done
  t1=$(date +%s%N)
  local ms=$(( (t1 - t0) / 1000000 ))
  DEMO_PID=""
  wait_api_gone "$port"
  local gone=0; wait_api_gone "$port" && gone=1
  local home_gone=0; [ -e "$home" ] || home_gone=1
  local daemon_gone=0; proc_alive "$daemon_pid" || daemon_gone=1
  [ "$ms" -le 2000 ] || { note "the supervisor took ${ms}ms to stop"; return 1; }
  [ "$gone" = 1 ] || { note "the demo daemon still answers on $port"; return 1; }
  [ "$daemon_gone" = 1 ] || { note "daemon JVM $daemon_pid is still alive"; return 1; }
  [ "$home_gone" = 1 ] || { note "the demo home survived: $home"; return 1; }
  return 0
}
if [ "$posix_signals" = 1 ]; then
  check "D11a SIGINT stops the demo within 2 s, leaves no daemon JVM and removes the home" d11a_sigint_stops_cleanly
else
  skip "D11a SIGINT stops the demo within 2 s, leaves no daemon JVM and removes the home" \
    "POSIX signals only: Git Bash cannot signal a native JVM (same reason test_e2e.sh needs setsid)"
fi

# ---------------------------------------------------------------------------------------------
# D11b (I10) — the harsher case, on every host: the supervisor is killed outright (no shutdown
# hook runs). The daemon must notice and exit, so a crashed demo leaves no JVM serving a port.
# ---------------------------------------------------------------------------------------------
d11b_killed_supervisor_leaves_no_daemon() {
  demo_start || return 1
  local port="$DEMO_PORT" home="$DEMO_HOME" daemon_pid
  daemon_pid=$(marker_get "$home" daemon)
  [ -n "$daemon_pid" ] || { note "the marker names no daemon pid"; demo_stop; return 1; }
  # Kill the supervisor ALONE (no /T): a tree kill would take the daemon down with it and prove
  # nothing. What has to save the port here is the daemon's own supervisor watchdog.
  kill_supervisor_only "$DEMO_PID"; wait "$DEMO_PID" 2>/dev/null
  DEMO_PID=""
  local gone=0; wait_api_gone "$port" && gone=1
  local daemon_gone=0; wait_proc_gone "$daemon_pid" && daemon_gone=1
  [ "$gone" = 1 ] || { note "the orphaned demo daemon still answers on $port"; return 1; }
  [ "$daemon_gone" = 1 ] || { note "daemon JVM $daemon_pid outlived its supervisor"; return 1; }
  # the home is left deliberately — evidence of the crash, and a marker for --clean (row D11c)
  [ -f "$home/.corral-demo" ] || { note "a killed demo lost its provenance marker"; return 1; }
  return 0
}
check "D11b a killed supervisor leaves no daemon JVM behind" d11b_killed_supervisor_leaves_no_daemon

# ---------------------------------------------------------------------------------------------
# D11c (I10) — --keep retains the home; --clean removes marked, non-running homes only and NAMES
# anything it left alone (a foreign directory is never a guess).
# ---------------------------------------------------------------------------------------------
d11c_keep_and_clean() {
  demo_start --keep || return 1
  local port="$DEMO_PORT" home="$DEMO_HOME" daemon_pid
  daemon_pid=$(marker_get "$home" daemon)
  : > "$TMP/clean.out"
  kill_tree "$DEMO_PID"; wait "$DEMO_PID" 2>/dev/null      # a hard kill: the marker is what proves provenance, not the exit path
  DEMO_PID=""
  wait_api_gone "$port"
  wait_proc_gone "$daemon_pid"
  local kept=0; [ -f "$home/.corral-demo" ] && kept=1
  local foreign="$DEMOHOMES/corral-demo-foreign"
  mkdir -p "$foreign"; printf 'not ours\n' > "$foreign/keep.txt"
  HOME="$SBX" "$JAVA_BIN" -Duser.home="$SBXW" -Djava.io.tmpdir="$(npath "$DEMOHOMES")" -cp "$JAR" \
    dev.corral.CorralCli demo --clean >"$TMP/clean.out" 2>&1
  local rc=$?
  local removed=0; [ -e "$home" ] || removed=1
  local foreign_kept=0; [ -f "$foreign/keep.txt" ] && foreign_kept=1
  local named_foreign=0; grep -q "left alone.*corral-demo-foreign" "$TMP/clean.out" && named_foreign=1
  [ "$kept" = 1 ] || { note "--keep did not retain the home"; sed -n '1,5p' "$DEMO_LOG"; return 1; }
  [ "$rc" = 0 ] || { note "--clean exited $rc: $(sed -n '1,5p' "$TMP/clean.out")"; return 1; }
  [ "$removed" = 1 ] || { note "--clean left the marked home in place: $home"; return 1; }
  [ "$foreign_kept" = 1 ] || { note "--clean removed a foreign directory"; return 1; }
  [ "$named_foreign" = 1 ] || { note "--clean did not name the foreign directory: $(head -3 "$TMP/clean.out")"; return 1; }
  rm -rf "$foreign"
  return 0
}
check "D11c --keep retains the home; --clean removes marked homes only and names foreign ones" d11c_keep_and_clean

# --- summary ----------------------------------------------------------------------------------
printf '\n%d passed, %d failed, %d skipped\n' "$pass" "$fail" "$skipped"
[ "$fail" = 0 ] || exit 1
exit 0
