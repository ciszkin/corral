#!/usr/bin/env bash
# corral process-level e2e suite (L2): daemon lifecycle, queue timing, signal handling.
# Complements scripts/test_wrapper.sh; it covers only what that suite does NOT (no duplicate
# 1.13/5.2/5.7/5.13/gate/identity cases).
#
# Runs from the source tree (rebuilds the jar) and from the deployed bundle (uses the jar beside
# this script): the bundle has no gradle project and no scripts/ subdirectory.
#
# Isolation: every path is redirected into a mktemp tree (CORRAL_DB/CORRAL_RULES/
# CORRAL_FAKE_DEVICES/CORRAL_ADB/CORRAL_EMULATOR/CORRAL_ENV_FILE); start_daemon also repoints
# the main daemon JVM's user.home there, so nothing touches ~/.corral, ~/.config/corral or a
# real device. Daemons and waiters are killed by exact pid only —
# never `pkill -f` a pattern that also appears in this script's own command line (a
# `corral wait` JVM has "corral" in argv).
set -u

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -f "$SELF/corral-daemon-all.jar" ] && [ -f "$SELF/adb-wrapper" ]; then
  SRC="$SELF"; ROOT=""; JAR="$SELF/corral-daemon-all.jar"; BUILD=0
else
  SRC="$SELF"; ROOT=$(cd "$SELF/.." && pwd); JAR="$ROOT/corral-daemon/build/libs/corral-daemon-all.jar"; BUILD=1
fi
PORT="${E2E_PORT:-8801}"                 # wrapper suite owns 8799; never reuse it
CORRAL_URL="http://127.0.0.1:$PORT"
STALE_S=$((3 * 5000 / 1000))            # MCP cadence 5000ms x STALE_POLLS=3 -> 15s

pass=0; fail=0; skipped=0
check() { # check <name> <cond...>
  local name="$1"; shift
  if "$@"; then printf 'ok %d %s\n' "$((++pass))" "$name"
  else printf 'FAIL %d %s\n' "$((++fail))" "$name"; fi
}
skip() { printf 'skip %d %s (set SLOW=1 to run)\n' "$((++skipped))" "$1"; }

ms_now() { date +%s%N; }
elapsed_ms() { echo $(( ($2 - $1) / 1000000 )); }

# --- setup ---
if [ "$BUILD" = 1 ]; then
  (cd "$ROOT" && ./gradlew :corral-daemon:shadowJar >/dev/null 2>&1) || { echo "build failed"; exit 1; }
fi
[ -f "$JAR" ] || { echo "jar not found: $JAR (run from the source tree or beside corral-daemon-all.jar)"; exit 1; }

for p in "$PORT" "$((PORT + 1))"; do
  if curl -s --max-time 1 "http://127.0.0.1:$p/api/devices" >/dev/null 2>&1; then
    echo "FAIL: port $p already in use — refusing to test against a foreign daemon"; exit 1
  fi
done

TMP=$(mktemp -d)
DAEMON_PID=""
cleanup() {
  trap - EXIT
  [ -n "$DAEMON_PID" ] && kill -9 "$DAEMON_PID" 2>/dev/null
  jobs -p 2>/dev/null | xargs -r kill -9 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$TMP/bin" "$TMP/sdk/platform-tools" "$TMP/bind" "$TMP/home"
printf 'dev1 device Pixel_6 13\ndev2 device SM-S918B 13\n' > "$TMP/fake-devices"
# boot gate marker: a file path so the flipping test needs no daemon restart
printf '0\n' > "$TMP/booted"
cat > "$TMP/fake-adb" <<'SH'
#!/bin/sh
case "$*" in
  *sys.boot_completed*) if [ -f "${FAKE_ADB_BOOTED:-}" ]; then cat "$FAKE_ADB_BOOTED"; else echo "${FAKE_ADB_BOOTED:-1}"; fi;;
  *exec-out*screencap*) printf 'fake-screen';;
  *) echo "fake-adb $@";;
esac
SH
chmod +x "$TMP/fake-adb"
cp "$SRC/fake-emulator" "$TMP/fake-emulator"; chmod +x "$TMP/fake-emulator"
cp "$SRC/adb-wrapper" "$TMP/bin/adb"; chmod +x "$TMP/bin/adb"
cp "$SRC/corral" "$TMP/bin/corral"; chmod +x "$TMP/bin/corral"
cp "$SRC/corral-rules" "$TMP/corral-rules"
# faithful platform-tools layout: adb (the wrapper/shim) + adb.real (the real binary)
printf '#!/bin/sh\necho "sdk-shim $@"\n' > "$TMP/sdk/platform-tools/adb"; chmod +x "$TMP/sdk/platform-tools/adb"
printf '#!/bin/sh\necho "sdk-real $@"\n' > "$TMP/sdk/platform-tools/adb.real"; chmod +x "$TMP/sdk/platform-tools/adb.real"

export CORRAL_JAR="$JAR" CORRAL_URL CORRAL_OWNER=tester
export CORRAL_REAL_ADB="$TMP/fake-adb" CORRAL_ADB="$TMP/fake-adb"
export CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/corral.db" CORRAL_RULES="$TMP/corral-rules"
export CORRAL_EMULATOR="$TMP/fake-emulator" FAKE_EMULATOR_STARTED="$TMP/emulator-started"
export FAKE_ADB_BOOTED="$TMP/booted"
export CORRAL_ENV_FILE="$TMP/daemon.env"   # absent on purpose: inherited env must keep winning in tests
export PATH="$TMP/bin:$PATH"
unset ANDROID_SERIAL

start_daemon() { # start_daemon [log] [db] [port]
  local log="${1:-$TMP/daemon.log}" db="${2:-$TMP/corral.db}" port="${3:-$PORT}"
  # -u INVOCATION_ID: keep the daemon off the systemd launcher even when this harness itself runs
  # as a systemd unit (a dev session), so the fake-emulator child path stays under test.
  setsid env -u INVOCATION_ID CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$db" CORRAL_RULES="$TMP/corral-rules" \
    CORRAL_PORT="$port" \
    java -Dcorral.test.tag=$$ -Duser.home="$TMP/home" -jar "$JAR" </dev/null >"$log" 2>&1 &
  DAEMON_PID=$!
  wait_daemon "$port" "$log"
}

wait_daemon() { # wait_daemon <port> <log>
  local port="$1" log="$2" i n
  for i in $(seq 1 50); do
    n=$(curl -s "http://127.0.0.1:$port/api/devices" 2>/dev/null | grep -o '"state":"device"' | wc -l)
    [ "$n" -ge 2 ] && return 0
    sleep 0.2
  done
  echo "daemon failed to start on port $port"; cat "$log"; return 1
}

stop_daemon() {
  [ -n "$DAEMON_PID" ] && kill -9 "$DAEMON_PID" 2>/dev/null
  [ -n "$DAEMON_PID" ] && wait "$DAEMON_PID" 2>/dev/null
  DAEMON_PID=""
}

free_all() { corral free --all >/dev/null 2>&1; }
claim_two() { corral claim dev1 >/dev/null 2>&1 && corral claim dev2 >/dev/null 2>&1; }
queue_empty() { [ "$(curl -s "$CORRAL_URL/api/queue")" = "[]" ]; }
wait_queue_not_empty() {
  local i; for i in $(seq 1 50); do queue_empty || return 0; sleep 0.1; done; return 1
}
enqueue_ticket() { # enqueue_ticket <json> -> ticket id
  curl -s -X POST "$CORRAL_URL/api/leases" -H 'Content-Type: application/json' -d "$1" \
    | grep -o '"ticket":"[^"]*"' | cut -d'"' -f4
}
cancel_ticket() { # cancel_ticket <id> <owner>
  curl -s -X DELETE "$CORRAL_URL/api/leases/tickets/$1" -H 'Content-Type: application/json' \
    -d "{\"owner\":\"$2\"}" >/dev/null
}

# --- 8.6: the daemon binds loopback only ---
check_8_6() {
  local out
  out=$(ss -tlnH "sport = :$PORT" 2>/dev/null) || return 1
  [ -n "$out" ] || return 1
  printf '%s\n' "$out" | grep -qE "127\.0\.0\.1.*:$PORT" || return 1
  if printf '%s\n' "$out" | grep -qE "(0\.0\.0\.0|\[::\]|\*):$PORT"; then return 1; fi
  return 0
}

# --- 1.9: a second daemon on the same port exits non-zero (bind failure) ---
check_1_9() {
  local rc
  timeout 15 env CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/bind/corral.db" \
    CORRAL_RULES="$TMP/corral-rules" CORRAL_PORT="$PORT" \
    java -Dcorral.test.tag=$$ -jar "$JAR" </dev/null >"$TMP/bind.log" 2>&1
  rc=$?
  [ "$rc" -ne 0 ] && [ "$rc" -ne 124 ]   # 124 = it hung; a bind failure exits fast
}

# --- 1.10: two daemons, one DB is refused. The daemon takes an exclusive advisory lock on
# CORRAL_DB at startup; a live holder makes the second daemon exit non-zero with a message,
# so the same device can never be leased by two owners. ---
check_1_10() {
  local port2=$((PORT + 1)) p2 drc i
  setsid env CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/corral.db" \
    CORRAL_RULES="$TMP/corral-rules" CORRAL_PORT="$port2" \
    java -Dcorral.test.tag=$$ -jar "$JAR" </dev/null >"$TMP/daemon-2db.log" 2>&1 &
  p2=$!
  for i in $(seq 1 50); do
    kill -0 "$p2" 2>/dev/null || break
    sleep 0.2
  done
  if kill -0 "$p2" 2>/dev/null; then
    kill -9 "$p2" 2>/dev/null; wait "$p2" 2>/dev/null
    printf '# 1.10: second daemon on the same DB stayed up — expected a loud refusal\n'
    return 1
  fi
  wait "$p2" 2>/dev/null; drc=$?
  printf '# 1.10: second daemon on the same DB exited rc=%s\n' "$drc"
  [ "$drc" -ne 0 ] && [ "$drc" -ne 124 ] && grep -q "refusing to start" "$TMP/daemon-2db.log"
}

# --- 1.6: tickets are in-memory; a restart drops them and the waiter errors, never hangs ---
check_1_6() {
  claim_two || return 1
  CORRAL_OWNER=waiter corral wait --serial dev1 --timeout 30 >"$TMP/restart.out" 2>&1 &
  local wp=$! t0 t1 rc el i
  wait_queue_not_empty || { kill -9 "$wp" 2>/dev/null; return 1; }
  kill -9 "$DAEMON_PID" 2>/dev/null; wait "$DAEMON_PID" 2>/dev/null
  t0=$(ms_now)
  start_daemon "$TMP/daemon-restart.log" || return 1
  local exited=0
  for i in $(seq 1 150); do kill -0 "$wp" 2>/dev/null || { exited=1; break; }; sleep 0.1; done
  t1=$(ms_now)
  if [ "$exited" = 1 ]; then
    wait "$wp" 2>/dev/null; rc=$?
  else
    kill -9 "$wp" 2>/dev/null; wait "$wp" 2>/dev/null; rc=1   # bounded: never block on a live waiter
  fi
  el=$(elapsed_ms "$t0" "$t1")
  # before the timeout (30s): either "daemon unreachable" (kill) or a 410 ticket-gone
  local ok=0
  [ "$rc" -ne 0 ] && [ "$el" -lt 15000 ] && ok=1
  free_all
  return $((1 - ok))
}

# --- 5.20: SIGINT runs the shutdown hook, cancelling the ticket immediately ---
check_5_20() {
  claim_two || return 1
  CORRAL_OWNER=sigint corral wait --serial dev1 --timeout 30 >"$TMP/sigint.out" 2>&1 &
  local wp=$! i
  wait_queue_not_empty || { kill -9 "$wp" 2>/dev/null; return 1; }
  kill -INT "$wp"
  for i in $(seq 1 30); do queue_empty && break; sleep 0.1; done
  if ! queue_empty; then sleep 2; fi          # the hook can be slow under CI load
  if ! queue_empty; then
    kill -INT "$wp" 2>/dev/null               # idempotent nudge while the JVM shuts down
    for i in $(seq 1 20); do queue_empty && break; sleep 0.1; done
  fi
  kill -9 "$wp" 2>/dev/null; wait "$wp" 2>/dev/null
  free_all
  queue_empty                                  # honest: the ticket must be cancelled either way
}

# --- windows-smoke F1: a served `corral wait` hands its lease back on exit ---
check_f1_handback() {
  claim_two || return 1
  CORRAL_OWNER=handback corral wait --serial dev1 --timeout 30 >"$TMP/handback.out" 2>&1 &
  local wp=$! i served=0
  # serve the waiter by freeing dev1; bounded wait for the serial on stdout
  for i in $(seq 1 50); do
    if [ -s "$TMP/handback.out" ]; then served=1; break; fi
    corral free dev1 >/dev/null 2>&1
    sleep 0.1
  done
  [ "$served" = 1 ] || { kill -9 "$wp" 2>/dev/null; wait "$wp" 2>/dev/null; free_all; return 1; }
  wait "$wp"; local rc=$?
  [ "$rc" = 0 ] || { free_all; return 1; }
  # the waiter is gone; without any `corral free`, dev1 must return to the pool within a few seconds
  local freed=0
  for i in $(seq 1 50); do
    if ! curl -sf "$CORRAL_URL/api/status" | grep -q '"serial":"dev1","lease":{'; then freed=1; break; fi
    sleep 0.1
  done
  free_all
  [ "$freed" = 1 ]
}

# --- 5.8: a SIGKILLed `corral wait` is pruned by pid at dispatch; next served < 3s ---
check_5_8() {
  claim_two || return 1
  CORRAL_OWNER=deadwait corral wait --serial dev1 --timeout 30 >"$TMP/dead.out" 2>&1 &
  local dead=$!
  wait_queue_not_empty || { kill -9 "$dead" 2>/dev/null; return 1; }
  CORRAL_OWNER=livewait corral wait --serial dev1 --timeout 30 >"$TMP/live58.out" 2>&1 &
  local live=$! i t0 t1 el
  for i in $(seq 1 50); do
    curl -s "$CORRAL_URL/api/queue" | grep -q '"owner":"livewait"' && break; sleep 0.1
  done
  kill -9 "$dead" 2>/dev/null; wait "$dead" 2>/dev/null
  t0=$(ms_now)
  free_all
  for i in $(seq 1 40); do [ -s "$TMP/live58.out" ] && break; sleep 0.1; done
  t1=$(ms_now)
  el=$(elapsed_ms "$t0" "$t1")
  CORRAL_OWNER=livewait corral free dev1 >/dev/null 2>&1
  kill -9 "$live" 2>/dev/null; wait "$live" 2>/dev/null
  free_all
  [ -s "$TMP/live58.out" ] && [ "$el" -lt 3000 ]
}

# --- 5.9/5.11: a REST/MCP waiter that stopped polling has no live pid (enqueue omits it),
# so dispatch skips it once cadence x STALE_POLLS has passed — the live ticket is served
# promptly, not after an OFFER_HOLD_S lapse. ---
check_5_9() {
  claim_two || return 1
  local tid
  tid=$(enqueue_ticket '{"selector":{"serial":"dev1"},"owner":"ghost","waitSeconds":120,"retryAfterMs":5000}')
  [ -n "$tid" ] || return 1
  CORRAL_OWNER=livewait corral wait --serial dev1 --timeout 30 >"$TMP/live59.out" 2>&1 &
  local live=$! i t0 t1 el
  for i in $(seq 1 50); do
    curl -s "$CORRAL_URL/api/queue" | grep -q '"owner":"livewait"' && break; sleep 0.1
  done
  sleep $((STALE_S + 1))          # the ghost is now stale (15s window) but not yet silent (30s)
  t0=$(ms_now)
  free_all
  for i in $(seq 1 40); do [ -s "$TMP/live59.out" ] && break; sleep 0.1; done
  t1=$(ms_now)
  el=$(elapsed_ms "$t0" "$t1")
  cancel_ticket "$tid" ghost
  CORRAL_OWNER=livewait corral free dev1 >/dev/null 2>&1
  kill -9 "$live" 2>/dev/null; wait "$live" 2>/dev/null
  free_all
  [ -s "$TMP/live59.out" ] && [ "$el" -lt 3000 ]
}

# --- 1.14 (slow): a ticket that stops polling is gone from `corral queue` at ~POLL_SILENCE_S ---
check_1_14() {
  claim_two || return 1
  local tid t0 t1 el i
  tid=$(enqueue_ticket "{\"selector\":{\"serial\":\"dev1\"},\"owner\":\"quiet\",\"pid\":$$,\"waitSeconds\":120}")
  [ -n "$tid" ] || return 1
  queue_empty && return 1
  t0=$(ms_now)
  for i in $(seq 1 40); do queue_empty && break; sleep 1; done
  t1=$(ms_now)
  el=$(elapsed_ms "$t0" "$t1")
  cancel_ticket "$tid" quiet
  free_all
  queue_empty && [ "$el" -le 35000 ]
}

# --- 6.19: with the SDK platform-tools dir ahead of ~/bin, PATH resolves adb to the SDK
# shim, so the ~/bin wrapper never intercepts. Caveat: in production the SDK adb is itself
# the corral shim (SdkShim.kt), so PATH order changes which wrapper runs, not whether the
# gate runs; absolute-path adb (AGP/ddmlib) still hits the SDK shim. ---
check_6_19() {
  local out resolved
  resolved=$(env PATH="$TMP/sdk/platform-tools:$TMP/bin:$PATH" sh -c 'command -v adb')
  [ "$resolved" = "$TMP/sdk/platform-tools/adb" ] || return 1
  out=$(env PATH="$TMP/sdk/platform-tools:$TMP/bin:$PATH" adb devices 2>&1)
  printf '%s\n' "$out" | grep -q 'sdk-shim' || return 1
  printf '%s\n' "$out" | grep -q 'fake-adb' && return 1
  return 0
}

# --- windows-smoke item2: `corral lease` prepends the configured adb's directory to the child
# PATH, so a bare `adb` resolves even when the launching session's PATH lacks platform-tools.
# PATH is scrubbed (no $TMP/bin, no SDK dir) so only the prepend can resolve it. ---
check_lease_adb_on_path() {
  local adbdir javadir out
  adbdir="$TMP/sdk/platform-tools"
  javadir=$(dirname "$(command -v java)")
  free_all
  out=$(env PATH="$javadir:/usr/bin:/bin" \
    CORRAL_ADB="$adbdir/adb.real" CORRAL_REAL_ADB="$adbdir/adb.real" \
    CORRAL_URL="$CORRAL_URL" CORRAL_OWNER=tester CORRAL_JAR="$JAR" \
    "$TMP/bin/corral" lease -- sh -c 'command -v adb' 2>&1)
  free_all
  [ "$out" = "$adbdir/adb" ] \
    || { echo "# lease child PATH resolved '$out', expected $adbdir/adb"; return 1; }
  return 0
}

# --- 11.1/11.2 + CLI: screenshot route. Strict by default: the lease holder (here the
# declared CORRAL_OWNER=tester) reads a PNG; a stranger is denied; the CLI verb writes it. ---
check_11_screenshot() {
  free_all
  corral claim dev1 >/dev/null 2>&1 || return 1
  local denied allowed
  denied=$(curl -s -o /dev/null -w '%{http_code}' "$CORRAL_URL/api/devices/dev1/screenshot?owner=stranger")
  allowed=$(curl -s -o "$TMP/shot.bin" -w '%{http_code}' "$CORRAL_URL/api/devices/dev1/screenshot?owner=tester")
  corral screenshot dev1 --out "$TMP/dev1.png" >/dev/null 2>&1
  local cli=$?
  free_all
  [ "$denied" = 403 ] || return 1
  [ "$allowed" = 200 ] || return 1
  [ "$cli" = 0 ] || return 1
  grep -q fake-screen "$TMP/shot.bin" || return 1
  grep -q fake-screen "$TMP/dev1.png" || return 1
  return 0
}

# --- 11.9/11.10: /ws/events sends a hello frame, then a state.changed nudge after a claim/free ---
check_11_events() {
  free_all
  timeout 12 corral events >"$TMP/events.out" 2>&1 &
  local ep=$! i
  for i in $(seq 1 50); do grep -q '"type":"hello"' "$TMP/events.out" && break; sleep 0.1; done
  corral claim dev2 >/dev/null 2>&1
  corral free dev2 >/dev/null 2>&1
  for i in $(seq 1 50); do grep -q 'state.changed' "$TMP/events.out" && break; sleep 0.1; done
  kill "$ep" 2>/dev/null; wait "$ep" 2>/dev/null
  free_all
  grep -q '"type":"hello"' "$TMP/events.out" || return 1
  grep -q 'state.changed' "$TMP/events.out" || return 1
  return 0
}

# --- 14.6/14.8/14.10: emulator lifecycle: fake-AVD start, boot gate, ticket on the boot flip,
# then stop refused while leased and force-stop. Cleanup always runs (lease, fake-devices
# line, spawned fake emulator) so later checks start clean. ---
emulator_cleanup() {
  curl -s -X DELETE "$CORRAL_URL/api/leases/emulator-5554" -H 'Content-Type: application/json' \
    -d '{"owner":"boot","force":true}' >/dev/null 2>&1
  if [ -f "$TMP/fake-devices" ] && grep -q 'emulator-5554' "$TMP/fake-devices"; then
    grep -v 'emulator-5554' "$TMP/fake-devices" > "$TMP/fake-devices.tmp"
    mv "$TMP/fake-devices.tmp" "$TMP/fake-devices"
  fi
  # the monitor re-reads fake-devices on its 2s poll, so the daemon's pool can still list
  # emulator-5554 right after the line is gone; the log-serial reconciliation (win-fixes-2)
  # would then match it to the next AVD started and answer stopped:["emulator-5554"]
  local i
  for i in $(seq 1 25); do
    curl -s "$CORRAL_URL/api/devices" | grep -q 'emulator-5554' || break
    sleep 0.2
  done
  if [ -s "$FAKE_EMULATOR_STARTED" ]; then
    local ep; ep=$(cat "$FAKE_EMULATOR_STARTED")
    pkill -9 -P "$ep" 2>/dev/null          # the fake emulator's foreground `sleep`
    kill -9 "$ep" 2>/dev/null
    rm -f "$FAKE_EMULATOR_STARTED"
  fi
  free_all
}

emulator_lifecycle() {
  local i code out tid got
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORRAL_URL/api/emulators/Pixel6AVD/start")
  [ "$code" = 202 ] || { echo "# 14.6/14.8/14.10 start: expected 202, got $code"; return 1; }
  for i in $(seq 1 25); do [ -s "$FAKE_EMULATOR_STARTED" ] && break; sleep 0.2; done
  [ -s "$FAKE_EMULATOR_STARTED" ] || { echo "# 14.6/14.8/14.10: fake emulator did not start"; return 1; }

  printf 'emulator-5554 device Pixel_6 13 Pixel6AVD\n' >> "$TMP/fake-devices"
  for i in $(seq 1 25); do
    curl -s "$CORRAL_URL/api/devices" | grep -q 'emulator-5554' && break; sleep 0.2
  done
  curl -s "$CORRAL_URL/api/devices" | grep -q 'emulator-5554' \
    || { echo "# 14.6/14.8/14.10: emulator never appeared in the pool"; return 1; }

  # Task 5 CLI parity: the avd verb reads the same /api/emulators route as the REST check
  corral avd list | grep -q 'Pixel6AVD' \
    || { echo "# 14.6/14.8/14.10: corral avd list missing Pixel6AVD"; return 1; }

  # boot gate: a plain claim is refused, and the remedy names booting + --type emulator
  out=$(CORRAL_OWNER=boot corral claim --type emulator 2>&1)
  printf '%s' "$out" | grep -q 'booting' || { echo "# 14.6/14.8/14.10 boot gate: $out"; return 1; }
  printf '%s' "$out" | grep -q -- '--type emulator' || { echo "# 14.6/14.8/14.10 boot remedy: $out"; return 1; }

  # §11.3: a wait timeout says so and names the still-booting match
  out=$(CORRAL_OWNER=waiter corral wait --type emulator --timeout 1 2>&1)
  printf '%s' "$out" | grep -q 'gave up after 1s' || { echo "# 11.3 wait timeout: $out"; return 1; }
  printf '%s' "$out" | grep -q 'still booting' || { echo "# 11.3 wait timeout booting: $out"; return 1; }

  # queue a ticket; while booted=0 it stays queued
  tid=$(enqueue_ticket '{"selector":{"transport":"emulator"},"owner":"boot","waitSeconds":30}')
  [ -n "$tid" ] || { echo "# 14.6/14.8/14.10: enqueue returned no ticket"; return 1; }
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORRAL_URL/api/leases/tickets/$tid/poll" \
    -H 'Content-Type: application/json' -d '{"owner":"boot"}')
  [ "$code" = 202 ] || { echo "# 14.6/14.8/14.10: first poll expected 202, got $code"; return 1; }

  # flip boot; the monitor's checkBoot dispatches the queued ticket (events nudge path)
  printf '1\n' > "$TMP/booted"
  got=""
  for i in $(seq 1 30); do
    code=$(curl -s -o "$TMP/offer.json" -w '%{http_code}' \
      -X POST "$CORRAL_URL/api/leases/tickets/$tid/poll" \
      -H 'Content-Type: application/json' -d '{"owner":"boot"}')
    if [ "$code" = 200 ]; then got=$(grep -o '"serial":"[^"]*"' "$TMP/offer.json" | cut -d'"' -f4); break; fi
    sleep 0.3
  done
  [ "$got" = "emulator-5554" ] \
    || { echo "# 14.6/14.8/14.10: boot flip did not serve the ticket (code=$code, got=$got)"; return 1; }

  code=$(curl -s -o "$TMP/stop.json" -w '%{http_code}' -X POST "$CORRAL_URL/api/emulators/Pixel6AVD/stop" \
    -H 'Content-Type: application/json' -d '{"force":false}')
  [ "$code" = 409 ] || { echo "# 14.6/14.8/14.10 stop: expected 409, got $code"; return 1; }
  grep -q 'is leased by boot' "$TMP/stop.json" || { echo "# 14.6/14.8/14.10 stop body: $(cat "$TMP/stop.json")"; return 1; }
  grep -q 'force' "$TMP/stop.json" || { echo "# 14.6/14.8/14.10 stop remedy: $(cat "$TMP/stop.json")"; return 1; }

  code=$(curl -s -o "$TMP/stop-force.json" -w '%{http_code}' \
    -X POST "$CORRAL_URL/api/emulators/Pixel6AVD/stop" \
    -H 'Content-Type: application/json' -d '{"force":true}')
  [ "$code" = 200 ] || { echo "# 14.6/14.8/14.10 force stop: expected 200, got $code"; return 1; }
  grep -q 'emulator-5554' "$TMP/stop-force.json" \
    || { echo "# 14.6/14.8/14.10 force body: $(cat "$TMP/stop-force.json")"; return 1; }
  return 0
}

check_14_6_to_10() {
  local rc=1
  if emulator_lifecycle; then rc=0; fi
  emulator_cleanup
  return $rc
}

# --- M8a: version plumbing and `corral free` with no arguments ---

# corral --version answers from the jar's own resource; no daemon or device is touched.
check_version() {
  local out rc
  out=$(corral --version 2>&1); rc=$?
  [ "$rc" = 0 ] || { echo "# --version: rc=$rc out=$out"; return 1; }
  printf '%s\n' "$out" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+' \
    || { echo "# --version: $out"; return 1; }
  return 0
}

# A bare `corral free` when nothing is owned is an honest no-op and exits 0, not the usage error.
check_free_bare_noop() {
  free_all
  local out rc
  out=$(CORRAL_OWNER=nobody corral free 2>&1); rc=$?
  [ "$rc" = 0 ] || { echo "# bare free noop: rc=$rc out=$out"; return 1; }
  printf '%s\n' "$out" | grep -q 'no leases owned by nobody' || { echo "# bare free noop: $out"; return 1; }
  return 0
}

# A bare `corral free` while the caller holds a lease releases exactly that lease.
check_free_bare_own() {
  free_all
  corral claim dev1 >/dev/null 2>&1 || { echo "# bare free own: claim failed"; return 1; }
  local out rc
  out=$(CORRAL_OWNER=tester corral free 2>&1); rc=$?
  [ "$rc" = 0 ] || { echo "# bare free own: rc=$rc out=$out"; return 1; }
  printf '%s\n' "$out" | grep -q 'freed:.*dev1' || { echo "# bare free own: $out"; return 1; }
  curl -sf "$CORRAL_URL/api/status" | grep -q '"serial":"dev1","lease":{' \
    && { echo "# bare free own: dev1 is still leased"; return 1; }
  return 0
}

# A bare `corral free` never touches another owner's lease — the D5 guard, kept for the bare form.
check_free_bare_foreign() {
  free_all
  curl -s -X POST "$CORRAL_URL/api/leases" -H 'Content-Type: application/json' \
    -d '{"selector":{"serial":"dev1"},"owner":"other"}' >/dev/null || { echo "# bare free foreign: claim failed"; return 1; }
  local out rc
  out=$(CORRAL_OWNER=tester corral free 2>&1); rc=$?
  [ "$rc" = 0 ] || { echo "# bare free foreign: rc=$rc out=$out"; return 1; }
  printf '%s\n' "$out" | grep -q 'no leases owned by tester' || { echo "# bare free foreign: $out"; return 1; }
  if ! curl -sf "$CORRAL_URL/api/status" | grep -q '"owner":"other"'; then
    echo "# bare free foreign: another owner's lease was freed"; return 1
  fi
  curl -s -X DELETE "$CORRAL_URL/api/leases/dev1" -H 'Content-Type: application/json' \
    -d '{"owner":"other"}' >/dev/null
  free_all
  return 0
}

# --- M8e: the console update surface's CLI verbs ---

# `corral update` prints the honest summary (version + the daemon's update line) and exits 0; it
# downloads nothing and runs no apply. The switch is pinned off so no network check decides the row.
check_update_bare() {
  local out rc
  curl -s -o /dev/null -X PUT "$CORRAL_URL/api/config" -H 'Content-Type: application/json' \
    -d '{"settings":{"CORRAL_UPDATE_CHECK":"off"}}'
  out=$(corral update 2>&1); rc=$?
  curl -s -o /dev/null -X PUT "$CORRAL_URL/api/config" -H 'Content-Type: application/json' \
    -d '{"settings":{"CORRAL_UPDATE_CHECK":"on"}}'
  [ "$rc" = 0 ] || { echo "# corral update: rc=$rc out=$out"; return 1; }
  printf '%s\n' "$out" | grep -qE '[0-9]+\.[0-9]+\.[0-9]+' || { echo "# corral update version: $out"; return 1; }
  printf '%s\n' "$out" | grep -q '^update: ' || { echo "# corral update line: $out"; return 1; }
  return 0
}

# `corral update --apply` with no release available refuses cleanly and installs nothing (no download,
# no systemctl, no install.sh): the apply path is not even entered.
check_update_apply_none() {
  local out rc
  curl -s -o /dev/null -X PUT "$CORRAL_URL/api/config" -H 'Content-Type: application/json' \
    -d '{"settings":{"CORRAL_UPDATE_CHECK":"off"}}'
  out=$(corral update --apply 2>&1); rc=$?
  curl -s -o /dev/null -X PUT "$CORRAL_URL/api/config" -H 'Content-Type: application/json' \
    -d '{"settings":{"CORRAL_UPDATE_CHECK":"on"}}'
  [ "$rc" != 0 ] || { echo "# update --apply no release: expected non-zero exit: $out"; return 1; }
  printf '%s\n' "$out" | grep -qi 'no update available' || { echo "# update --apply refusal: $out"; return 1; }
  return 0
}

# --- pass-38: stopping a running AVD adb doesn't list ends the tracked spawn and says so,
# distinct from the `{"stopped":[]}` no-op when nothing was running. SmallAVD is started but
# never appended to fake-devices, so the route has no serial and must fall through to stopAvd. ---
emulator_terminated() {
  local i code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORRAL_URL/api/emulators/SmallAVD/start")
  [ "$code" = 202 ] || { echo "# terminated start SmallAVD: expected 202, got $code"; return 1; }
  for i in $(seq 1 25); do [ -s "$FAKE_EMULATOR_STARTED" ] && break; sleep 0.2; done
  [ -s "$FAKE_EMULATOR_STARTED" ] || { echo "# terminated: fake emulator did not start"; return 1; }

  code=$(curl -s -o "$TMP/terminated.json" -w '%{http_code}' \
    -X POST "$CORRAL_URL/api/emulators/SmallAVD/stop" \
    -H 'Content-Type: application/json' -d '{"force":false}')
  [ "$code" = 200 ] || { echo "# terminated stop: expected 200, got $code"; return 1; }
  grep -q '"stopped":\[\]' "$TMP/terminated.json" \
    || { echo "# terminated stopped should be empty: $(cat "$TMP/terminated.json")"; return 1; }
  grep -q '"terminated":"pid [0-9]*"' "$TMP/terminated.json" \
    || { echo "# terminated body: $(cat "$TMP/terminated.json")"; return 1; }
  return 0
}

check_emulator_terminated() {
  local rc=1
  if emulator_terminated; then rc=0; fi
  emulator_cleanup
  return $rc
}

# --- review-39 §2: concurrent starts of one AVD spawn exactly one instance. The per-AVD lock
# makes the isRunning re-check and spawn atomic, so the losers answer `200 alreadyRunning`
# (under systemd, "already exists" from systemd-run is answered the same way, not as a 502).
# SmallAVD is known to the fake emulator but never appended to fake-devices, so no serial hides
# the launcher path. Stops the AVD on the way out; the wrapper kills any spawn and frees leases. ---
emulator_concurrent_starts() {
  local i pids=() code n202=0 n200=0
  rm -f "$TMP/start-code-"* "$TMP/start-body-"*
  for i in $(seq 1 8); do
    curl -s -o "$TMP/start-body-$i" -w '%{http_code}' \
      -X POST "$CORRAL_URL/api/emulators/SmallAVD/start" > "$TMP/start-code-$i" &
    pids+=($!)
  done
  wait "${pids[@]}"
  for i in $(seq 1 8); do
    code=$(cat "$TMP/start-code-$i" 2>/dev/null)
    if [ "$code" = 202 ]; then
      n202=$((n202 + 1))
      grep -q '"alreadyRunning":false' "$TMP/start-body-$i" \
        || { echo "# concurrent start 202 body: $(cat "$TMP/start-body-$i")"; return 1; }
    elif [ "$code" = 200 ]; then
      n200=$((n200 + 1))
      grep -q '"alreadyRunning":true' "$TMP/start-body-$i" \
        || { echo "# concurrent start 200 body: $(cat "$TMP/start-body-$i")"; return 1; }
    else
      echo "# concurrent start: unexpected HTTP $code"; return 1
    fi
  done
  if [ "$n202" != 1 ] || [ "$n200" != 7 ]; then
    echo "# concurrent start: expected 1x202 + 7x200, got ${n202}x202 + ${n200}x200"; return 1
  fi
  [ -s "$FAKE_EMULATOR_STARTED" ] || { echo "# concurrent start: no fake emulator spawned"; return 1; }
  code=$(curl -s -o "$TMP/conc-stop.json" -w '%{http_code}' \
    -X POST "$CORRAL_URL/api/emulators/SmallAVD/stop" -H 'Content-Type: application/json' -d '{"force":false}')
  [ "$code" = 200 ] || { echo "# concurrent start stop: expected 200, got $code"; return 1; }
  return 0
}

check_emulator_concurrent_starts() {
  local rc=1
  if emulator_concurrent_starts; then rc=0; fi
  emulator_cleanup
  return $rc
}

# --- review-39 §3: Corral's own stop clears the AVD log, so a clean stop must not surface its
# last line as the AVD's `error` (the console's "last error"). Start, stop, then read the entry
# straight from GET /api/emulators. ---
emulator_clean_stop() {
  local i code entry
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORRAL_URL/api/emulators/SmallAVD/start")
  [ "$code" = 202 ] || { echo "# clean stop start: expected 202, got $code"; return 1; }
  for i in $(seq 1 25); do [ -s "$FAKE_EMULATOR_STARTED" ] && break; sleep 0.2; done
  [ -s "$FAKE_EMULATOR_STARTED" ] || { echo "# clean stop: fake emulator did not start"; return 1; }
  code=$(curl -s -o "$TMP/clean-stop.json" -w '%{http_code}' \
    -X POST "$CORRAL_URL/api/emulators/SmallAVD/stop" -H 'Content-Type: application/json' -d '{"force":false}')
  [ "$code" = 200 ] || { echo "# clean stop: expected 200, got $code"; return 1; }
  entry=$(curl -s "$CORRAL_URL/api/emulators" | grep -o '{[^{}]*"avd":"SmallAVD"[^{}]*}')
  [ -n "$entry" ] || { echo "# clean stop: SmallAVD missing from GET /api/emulators"; return 1; }
  if printf '%s' "$entry" | grep -q '"error"'; then
    echo "# clean stop: SmallAVD still reports an error: $entry"; return 1
  fi
  return 0
}

check_emulator_clean_stop() {
  local rc=1
  if emulator_clean_stop; then rc=0; fi
  emulator_cleanup
  return $rc
}

# --- review-41: `error` follows the exit status. FailAVD/CleanAVD are known to the fake emulator
# but never appended to fake-devices, so they have no serial and the tracked-child path decides:
# the direct launcher writes `<log>.exit` on exit, and a non-zero status surfaces the log tail as
# `error` while a clean self-exit (window closed) leaves none. A fresh start deletes the old log
# and status, so the previous failure is gone while the new run is live. ---
wait_emulator_exit() { # wait_emulator_exit <avd> [ticks] -> bounded 4s on the 100ms sidecar poll
  local avd="$1" n="${2:-40}" i
  for i in $(seq 1 "$n"); do
    [ -f "$TMP/home/.corral/emulator-$avd.log.exit" ] && return 0
    sleep 0.1
  done
  return 1
}

emulator_entry() { curl -s "$CORRAL_URL/api/emulators" | grep -o "{[^{}]*\"avd\":\"$1\"[^{}]*}"; }

emulator_error_status() {
  local code entry
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORRAL_URL/api/emulators/FailAVD/start")
  [ "$code" = 202 ] || { echo "# exit-status start FailAVD: expected 202, got $code"; return 1; }
  wait_emulator_exit FailAVD || { echo "# exit-status: FailAVD did not exit within 4s"; return 1; }
  entry=$(emulator_entry FailAVD)
  [ -n "$entry" ] || { echo "# exit-status: FailAVD missing from GET /api/emulators"; return 1; }
  printf '%s' "$entry" | grep -q 'simulated late failure' \
    || { echo "# exit-status: non-zero exit did not surface error: $entry"; return 1; }

  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORRAL_URL/api/emulators/CleanAVD/start")
  [ "$code" = 202 ] || { echo "# exit-status start CleanAVD: expected 202, got $code"; return 1; }
  wait_emulator_exit CleanAVD || { echo "# exit-status: CleanAVD did not exit within 4s"; return 1; }
  entry=$(emulator_entry CleanAVD)
  [ -n "$entry" ] || { echo "# exit-status: CleanAVD missing from GET /api/emulators"; return 1; }
  if printf '%s' "$entry" | grep -q '"error"'; then
    echo "# exit-status: clean self-exit reported an error: $entry"; return 1
  fi

  # restart clears the recorded failure while the new run is live
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORRAL_URL/api/emulators/FailAVD/start")
  [ "$code" = 202 ] || { echo "# exit-status restart FailAVD: expected 202, got $code"; return 1; }
  [ ! -f "$TMP/home/.corral/emulator-FailAVD.log.exit" ] \
    || { echo "# exit-status: restart left the previous exit status"; return 1; }
  entry=$(emulator_entry FailAVD)
  if printf '%s' "$entry" | grep -q '"error"'; then
    echo "# exit-status: fresh start did not clear the previous error: $entry"; return 1
  fi
  return 0
}

emulator_error_cleanup() {
  for avd in FailAVD CleanAVD; do
    curl -s -X POST "$CORRAL_URL/api/emulators/$avd/stop" -H 'Content-Type: application/json' \
      -d '{"force":true}' >/dev/null 2>&1
  done
  if [ -s "$FAKE_EMULATOR_STARTED" ]; then
    local ep; ep=$(cat "$FAKE_EMULATOR_STARTED")
    pkill -9 -P "$ep" 2>/dev/null
    kill -9 "$ep" 2>/dev/null
    rm -f "$FAKE_EMULATOR_STARTED"
  fi
  free_all
}

# --- review 42 §1 (re-review I-1): the upgrade replaces the jar through ONE shipped helper,
# `replace_file` — a temp file beside the target plus a rename, never an in-place `cp` onto the
# same inode. This row proves the contrast on a live daemon, using the payload a release actually
# ships: a RE-ZIPPED jar (same entries, moved, so the bytes at the old offsets differ). An in-place
# `cp` of that payload leaves the reviewer's zombie (process alive, endpoints dead,
# NoClassDefFoundError); the same payload handed to the installer's own helper leaves the daemon
# answering. The helper is extracted from install.sh and called here, so this row fails if
# install.sh ever goes back to an in-place `cp`. ---
jar_payload_modified() { # jar_payload_modified <src jar> <out jar> -> same entries, re-zipped backwards
  local src="$1" out="$2" d
  d=$(mktemp -d "$TMP/jarzip.XXXXXX") || return 1
  ( cd "$d" && unzip -qq "$src" \
    && unzip -Z1 "$src" | grep -v '/$' | tac | zip -q -X -@ "$out" ) \
    || { rm -rf "$d"; return 1; }
  rm -rf "$d"
  unzip -tq "$out" >/dev/null   # valid jar: only the offsets moved, nothing is corrupt
}

jar_daemon_up() { # jar_daemon_up <dir> <port> <db> <log> -> sets JARPID once /api/devices answers
  local dir="$1" port="$2" db="$3" log="$4"
  setsid env -u INVOCATION_ID CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$db" \
    CORRAL_RULES="$TMP/corral-rules" CORRAL_PORT="$port" \
    java -Dcorral.test.tag=$$ -Duser.home="$TMP/home" -jar "$dir/corral-daemon-all.jar" </dev/null >"$log" 2>&1 &
  JARPID=$!
  wait_daemon "$port" "$log" || { kill -9 "$JARPID" 2>/dev/null; return 1; }
}

jar_http() { curl -s -o /dev/null --max-time 5 -w '%{http_code}' "http://127.0.0.1:$1/api/$2"; }

jar_replace_live() {
  local installer="$SELF/install.sh" helper port4=$((PORT + 3)) port5=$((PORT + 4)) p ep code
  local ino_before ino_after dir

  helper=$(sed -n '/^replace_file()/,/^}/p' "$installer")
  if [ -z "$helper" ]; then
    echo "# jar replace: $installer ships no replace_file helper — it replaces the jar in place"
    return 1
  fi
  grep -q 'replace_file "\$JAR" "\$CORRAL_HOME/corral-daemon-all.jar"' "$installer" \
    || { echo "# jar replace: install.sh does not route the daemon jar through replace_file"; return 1; }
  eval "$helper"

  mkdir -p "$TMP/jar"
  jar_payload_modified "$JAR" "$TMP/jar/new.jar" \
    || { echo "# jar replace: cannot build the re-zipped payload"; return 1; }

  # (a) the symptom the fix removes: an in-place `cp` of the payload, exactly what install.sh did
  dir="$TMP/jar/inplace"; mkdir -p "$dir"; cp "$JAR" "$dir/corral-daemon-all.jar"
  jar_daemon_up "$dir" "$port4" "$TMP/jar/inplace.db" "$TMP/jar/inplace.log" || return 1
  p=$JARPID
  cp "$TMP/jar/new.jar" "$dir/corral-daemon-all.jar"
  # /api/devices is warmed by wait_daemon *before* the corruption, so its classes are usually
  # already loaded and it can legitimately keep answering 200 — that says nothing about the
  # payload. The three routes nothing called before the corruption cannot come from memory.
  for ep in doctor config queue; do
    code=$(jar_http "$port4" "$ep")
    if [ "$code" = 200 ]; then
      echo "# jar replace: after an in-place cp of the re-zipped jar /api/$ep still answered 200 (devices=$(jar_http "$port4" devices), process alive:$(kill -0 "$p" 2>/dev/null && echo yes || echo no)) — the daemon is not broken, so the contrast proves nothing: either the payload does not reach the old offsets or the daemon already had that route in memory"
      kill -9 "$p" 2>/dev/null; return 1
    fi
  done
  kill -0 "$p" 2>/dev/null \
    || { echo "# jar replace: the in-place cp killed the daemon outright (the §1 symptom is alive-but-dead)"; kill -9 "$p" 2>/dev/null; return 1; }
  grep -q NoClassDefFoundError "$TMP/jar/inplace.log" \
    || { echo "# jar replace: the in-place cp left the daemon alive with no NoClassDefFoundError — a different symptom than review 42 §1"; kill -9 "$p" 2>/dev/null; return 1; }
  kill -9 "$p" 2>/dev/null; wait "$p" 2>/dev/null

  # (b) the fix: the same payload through the installer's own helper
  dir="$TMP/jar/rename"; mkdir -p "$dir"; cp "$JAR" "$dir/corral-daemon-all.jar"
  jar_daemon_up "$dir" "$port5" "$TMP/jar/rename.db" "$TMP/jar/rename.log" || return 1
  p=$JARPID
  ino_before=$(ls -i "$dir/corral-daemon-all.jar" | cut -d' ' -f1)
  replace_file "$TMP/jar/new.jar" "$dir/corral-daemon-all.jar" "corral-daemon-all.jar" \
    || { echo "# jar replace: the shipped replace_file failed"; kill -9 "$p" 2>/dev/null; return 1; }
  ino_after=$(ls -i "$dir/corral-daemon-all.jar" | cut -d' ' -f1)
  if [ "$ino_before" = "$ino_after" ]; then
    echo "# jar replace: the target kept its inode — that is an in-place rewrite, not a rename"
    kill -9 "$p" 2>/dev/null; return 1
  fi
  cmp -s "$TMP/jar/new.jar" "$dir/corral-daemon-all.jar" \
    || { echo "# jar replace: the installed jar is not the payload"; kill -9 "$p" 2>/dev/null; return 1; }
  for ep in devices doctor config queue; do
    code=$(jar_http "$port5" "$ep")
    if [ "$code" != 200 ]; then
      echo "# jar replace: /api/$ep answered $code after the rename (daemon pid $p alive:$(kill -0 "$p" 2>/dev/null && echo yes || echo no))"
      kill -9 "$p" 2>/dev/null; return 1
    fi
  done
  if grep -q NoClassDefFoundError "$TMP/jar/rename.log"; then
    echo "# jar replace: the rename still left NoClassDefFoundError in the daemon log"
    kill -9 "$p" 2>/dev/null; return 1
  fi
  if ls "$dir" | grep -q '\.new\.'; then
    echo "# jar replace: a temp file was left behind"; kill -9 "$p" 2>/dev/null; return 1
  fi
  kill -9 "$p" 2>/dev/null; wait "$p" 2>/dev/null
  return 0
}

# A dedicated daemon on a spare port, so the suite's own daemon is never disturbed. ---
emulator_shutdown_hook() {
  local port3=$((PORT + 2)) p3 i ep U3
  U3="http://127.0.0.1:$port3"
  rm -f "$FAKE_EMULATOR_STARTED"
  setsid env -u INVOCATION_ID CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/hook/corral.db" \
    CORRAL_RULES="$TMP/corral-rules" CORRAL_PORT="$port3" \
    java -Dcorral.test.tag=$$ -Duser.home="$TMP/home" -jar "$JAR" </dev/null >"$TMP/hook.log" 2>&1 &
  p3=$!
  wait_daemon "$port3" "$TMP/hook.log" || { kill -9 "$p3" 2>/dev/null; return 1; }
  curl -s -o /dev/null -X POST "$U3/api/emulators/Pixel6AVD/start"
  for i in $(seq 1 25); do [ -s "$FAKE_EMULATOR_STARTED" ] && break; sleep 0.2; done
  ep=$(cat "$FAKE_EMULATOR_STARTED" 2>/dev/null) || { echo "# shutdown-hook: emulator never started"; kill -9 "$p3" 2>/dev/null; return 1; }
  kill -0 "$ep" 2>/dev/null || { echo "# shutdown-hook: emulator died before the test"; kill -9 "$p3" 2>/dev/null; return 1; }
  kill -TERM "$p3"; wait "$p3" 2>/dev/null   # graceful stop -> the hook must destroy the tracked child
  for i in $(seq 1 25); do ! kill -0 "$ep" 2>/dev/null && break; sleep 0.2; done
  if kill -0 "$ep" 2>/dev/null; then
    echo "# shutdown-hook: direct emulator survived its daemon"
    kill -9 "$ep" 2>/dev/null; kill -9 "$p3" 2>/dev/null; return 1
  fi
  rm -f "$FAKE_EMULATOR_STARTED"
}

check_emulator_shutdown_hook() {
  local rc=1
  if emulator_shutdown_hook; then rc=0; fi
  return $rc
}

check_emulator_error_status() {
  local rc=1
  if emulator_error_status; then rc=0; fi
  emulator_error_cleanup
  return $rc
}

# --- windows fourth-run: an emulator stuck `offline` in the pool never gets its avd tag (the
# monitor's `adb -s SERIAL emu avd name` enrichment fails on an unbooted device), so every
# avd-scoped lookup missed it. The serial the emulator prints into its own start log reconciles
# it: GET shows it, a second start is alreadyRunning, and stop attempts the kill by serial. ---
emulator_offline_reconcile() {
  local i code entry
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORRAL_URL/api/emulators/Pixel6AVD/start")
  [ "$code" = 202 ] || { echo "# offline reconcile start: expected 202, got $code"; return 1; }
  for i in $(seq 1 25); do [ -s "$FAKE_EMULATOR_STARTED" ] && break; sleep 0.2; done
  [ -s "$FAKE_EMULATOR_STARTED" ] || { echo "# offline reconcile: fake emulator did not start"; return 1; }

  # the pool lists the serial but never enriches it with an avd (stuck offline)
  printf 'emulator-5554 offline\n' >> "$TMP/fake-devices"
  for i in $(seq 1 25); do
    curl -s "$CORRAL_URL/api/devices" | grep -q 'emulator-5554' && break; sleep 0.2
  done
  curl -s "$CORRAL_URL/api/devices" | grep -q 'emulator-5554' \
    || { echo "# offline reconcile: emulator never appeared in the pool"; return 1; }

  entry=$(curl -s "$CORRAL_URL/api/emulators" | grep -o '{[^{}]*"avd":"Pixel6AVD"[^{}]*}')
  printf '%s' "$entry" | grep -q '"runningSerials":\["emulator-5554"\]' \
    || { echo "# offline reconcile GET: $entry"; return 1; }

  code=$(curl -s -o "$TMP/reconcile-start.json" -w '%{http_code}' -X POST "$CORRAL_URL/api/emulators/Pixel6AVD/start")
  [ "$code" = 200 ] || { echo "# offline reconcile restart: expected 200, got $code"; return 1; }
  grep -q '"alreadyRunning":true' "$TMP/reconcile-start.json" \
    || { echo "# offline reconcile restart body: $(cat "$TMP/reconcile-start.json")"; return 1; }

  code=$(curl -s -o "$TMP/reconcile-stop.json" -w '%{http_code}' \
    -X POST "$CORRAL_URL/api/emulators/Pixel6AVD/stop" -H 'Content-Type: application/json' -d '{"force":false}')
  [ "$code" = 200 ] || { echo "# offline reconcile stop: expected 200, got $code"; return 1; }
  grep -q 'emulator-5554' "$TMP/reconcile-stop.json" \
    || { echo "# offline reconcile stop body: $(cat "$TMP/reconcile-stop.json")"; return 1; }
  return 0
}

check_emulator_offline_reconcile() {
  local rc=1
  if emulator_offline_reconcile; then rc=0; fi
  emulator_cleanup
  return $rc
}

# --- M7 Plan A: a PUT port change lands in daemon.env and survives a restart whose inherited
# CORRAL_PORT differs — the file wins over the environment. This is the final check: it leaves
# the daemon running on PORT+1 for the EXIT trap (nothing after it, so no port collision). ---
check_13_precedence() {
  local newp=$((PORT + 1))
  curl -sf -X PUT "$CORRAL_URL/api/config" -H 'Content-Type: application/json' \
    -d "{\"settings\":{\"CORRAL_PORT\":\"$newp\"}}" >/dev/null || return 1
  grep -q "^CORRAL_PORT=$newp$" "$CORRAL_ENV_FILE" || return 1
  stop_daemon
  setsid env CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/corral.db" \
    CORRAL_RULES="$TMP/corral-rules" CORRAL_PORT="$PORT" \
    java -Dcorral.test.tag=$$ -jar "$JAR" </dev/null >"$TMP/daemon-precedence.log" 2>&1 &
  DAEMON_PID=$!
  wait_daemon "$newp" "$TMP/daemon-precedence.log"   # daemon.env beats the inherited CORRAL_PORT
  # the restart is the first load after the launcher PUT, so GET now reports the normalised file value
  curl -sf "http://127.0.0.1:$newp/api/config" \
    | grep -q '"key":"CORRAL_EMULATOR_LAUNCHER","value":"direct"' || return 1
}

# --- run ---
start_daemon || exit 1
check "8.6 daemon binds loopback only" check_8_6
check "1.9 second daemon on same port exits non-zero" check_1_9
check "1.10 two daemons on one DB: second refused loudly (exclusive lock)" check_1_10
check "M8a corral --version prints a version and exits 0" check_version
check "M8a bare corral free with no lease is an honest no-op" check_free_bare_noop
check "M8a bare corral free releases the caller's own lease" check_free_bare_own
check "M8a bare corral free never frees another owner's lease" check_free_bare_foreign
check "M8e corral update prints the honest summary and exits 0" check_update_bare
check "M8e corral update --apply with no release refuses and installs nothing" check_update_apply_none

stop_daemon
rm -f "$TMP/corral.db"*
start_daemon "$TMP/daemon-main.log" || exit 1
check "1.6 restart with tickets queued: waiter errors promptly, never hangs" check_1_6
check "5.20 SIGINT cancels the waiter ticket immediately" check_5_20
check "5.8 SIGKILLed corral wait: next waiter served < 3s (pid prune)" check_5_8
check "F1 served corral wait hands its lease back on exit" check_f1_handback
check "5.9/5.11 quiet REST/MCP waiter skipped within staleness window, not offer hold" check_5_9
if [ "${SLOW:-0}" = 1 ]; then
  check "1.14 poll-silence drop leaves corral queue within POLL_SILENCE_S + slack" check_1_14
else
  skip "1.14 poll-silence drop"
fi
check "6.19 SDK-dir-first PATH: ~/bin wrapper never intercepts" check_6_19
check "lease child gets the configured adb dir on PATH (daemon-session PATH scrub)" check_lease_adb_on_path
check "11.1/11.2 + CLI screenshot: holder reads PNG, stranger 403, CLI verb" check_11_screenshot
check "11.9/11.10 ws events: hello frame, then state.changed on claim/free" check_11_events

check "14.6/14.8/14.10 emulator lifecycle: start, boot gate, claim via queue, stop refusal" check_14_6_to_10
check "stop of an unlisted running emulator reports the pid it terminated" check_emulator_terminated
check "concurrent starts spawn one instance, the rest alreadyRunning" check_emulator_concurrent_starts
check "a clean stop leaves no error in GET /api/emulators" check_emulator_clean_stop
check "a non-zero exit surfaces its error; a clean self-exit shows none; restart clears it" check_emulator_error_status
check "an offline orphan is reconciled from its log for get/start/stop" check_emulator_offline_reconcile
check "a direct-launched emulator dies with its daemon (shutdown hook)" check_emulator_shutdown_hook
# Slow: two extra daemon starts plus 30MB jar re-zips; CI runs it with SLOW=1.
if [ "${SLOW:-0}" = 1 ] && command -v zip >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1; then
  check "install's replace_file keeps a running daemon answering where an in-place cp kills it (review 42 §1)" jar_replace_live
else
  skip "install's replace_file vs an in-place cp under a live daemon (review 42 §1)"
fi

# --- M7 Plan A: config/doctor API. Run last: the round-trip row writes CORRAL_SCREENSHOTS=open
# into the pinned env file, and the final precedence row restarts the daemon on PORT+1. ---
check "config get/put round-trip and precedence" bash -c '
  curl -sf "$CORRAL_URL/api/config" | grep -q "\"key\":\"CORRAL_PORT\"" || exit 1
  curl -sf -X PUT "$CORRAL_URL/api/config" -H "Content-Type: application/json" \
    -d "{\"settings\":{\"CORRAL_SCREENSHOTS\":\"open\"}}" | grep -q "\"restartRequired\":true" || exit 1
  grep -q "^CORRAL_SCREENSHOTS=open$" "$CORRAL_ENV_FILE" || exit 1
  exit 0
'
check "invalid put is 400 and writes nothing" bash -c '
  [ -f "$CORRAL_ENV_FILE" ] || exit 1
  before=$(md5sum "$CORRAL_ENV_FILE" | cut -d" " -f1)
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" -d "{\"settings\":{\"CORRAL_PORT\":\"99999\"}}")
  [ "$code" = 400 ] || exit 1
  after=$(md5sum "$CORRAL_ENV_FILE" | cut -d" " -f1)
  [ "$before" = "$after" ] || exit 1
  exit 0
'
check "emulator launcher put validates, writes nothing on 400, and normalises case" bash -c '
  [ -f "$CORRAL_ENV_FILE" ] || exit 1
  before=$(md5sum "$CORRAL_ENV_FILE" | cut -d" " -f1)
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" -d "{\"settings\":{\"CORRAL_EMULATOR_LAUNCHER\":\"bogus\"}}")
  [ "$code" = 400 ] || exit 1
  after=$(md5sum "$CORRAL_ENV_FILE" | cut -d" " -f1)
  [ "$before" = "$after" ] || exit 1
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" -d "{\"settings\":{\"CORRAL_EMULATOR_LAUNCHER\":\"Direct\"}}")
  [ "$code" = 200 ] || exit 1
  grep -q "^CORRAL_EMULATOR_LAUNCHER=direct$" "$CORRAL_ENV_FILE" || exit 1
  exit 0
'
check "doctor reports three layers" bash -c '
  body=$(curl -sf "$CORRAL_URL/api/doctor") || exit 1
  for l in daemon sdk-shim rules; do printf "%s" "$body" | grep -q "\"layer\":\"$l\"" || exit 1; done
  exit 0
'
check "mutating routes refuse a foreign origin" bash -c '
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$CORRAL_URL/api/leases" \
    -H "Content-Type: application/json" -H "Origin: https://evil.example" \
    -d "{\"selector\":{\"serial\":\"dev1\"},\"owner\":\"evil\"}")
  [ "$code" = 403 ] || exit 1
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$CORRAL_URL/api/emulators/Pixel6AVD/start" \
    -H "Origin: https://evil.example")
  [ "$code" = 403 ] || exit 1
  exit 0
'
check "config put refuses device-touching readonly" bash -c '
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" \
    -d "{\"rules\":{\"readonly\":[\"devices\",\"install\"],\"valueflags\":[\"-P\"],\"shell_skip\":[],\"init_floor\":[]}}")
  [ "$code" = 400 ] || exit 1
  exit 0
'
check "config put rejects a foreign origin" bash -c '
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" -H "Origin: https://evil.example" \
    -d "{\"settings\":{\"CORRAL_SCREENSHOTS\":\"open\"}}")
  [ "$code" = 403 ] || exit 1
  exit 0
'
check "config put refuses a non-host-only readonly" bash -c '
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" \
    -d "{\"rules\":{\"readonly\":[\"devices\",\"exec-in\"],\"valueflags\":[\"-P\"]}}")
  [ "$code" = 400 ] || exit 1
  exit 0
'
check "config put refuses a privileged port" bash -c '
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" -d "{\"settings\":{\"CORRAL_PORT\":\"80\"}}")
  [ "$code" = 400 ] || exit 1
  exit 0
'
check "update: GET /api/update answers with the four fields and does not hang" bash -c '
  body=$(curl -s --max-time 5 "$CORRAL_URL/api/update") || exit 1
  for f in currentVersion latestVersion updateAvailable releaseUrl checksEnabled; do
    printf "%s" "$body" | grep -q "\"$f\"" || exit 1
  done
  exit 0
'
check "update: CORRAL_UPDATE_CHECK=off is settable and reports disabled" bash -c '
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" -d "{\"settings\":{\"CORRAL_UPDATE_CHECK\":\"off\"}}")
  [ "$code" = 200 ] || exit 1
  grep -q "^CORRAL_UPDATE_CHECK=off$" "$CORRAL_ENV_FILE" || exit 1
  curl -s --max-time 5 "$CORRAL_URL/api/update" | grep -q "\"checksEnabled\":false" || exit 1
  exit 0
'
check "update: a write of only the live toggle returns restartRequired false" bash -c '
  out=$(curl -s -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" -d "{\"settings\":{\"CORRAL_UPDATE_CHECK\":\"on\"}}")
  printf "%s" "$out" | grep -q "\"restartRequired\":false" || exit 1
  exit 0
'
check "config precedence: daemon.env port beats inherited env across restart" check_13_precedence

printf '\n%d passed, %d failed, %d skipped\n' "$pass" "$fail" "$skipped"
exit "$fail"
