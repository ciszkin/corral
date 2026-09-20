#!/usr/bin/env bash
# corral process-level e2e suite (L2): daemon lifecycle, queue timing, signal handling.
# Complements scripts/test_wrapper.sh; it covers only what that suite does NOT (no duplicate
# 1.13/5.2/5.7/5.13/gate/identity cases). Source-tree only: it needs the built shadow jar.
#
# Isolation: every path is redirected into a mktemp tree (CORRAL_DB/CORRAL_RULES/
# CORRAL_FAKE_DEVICES/CORRAL_ADB); nothing touches ~/.corral, ~/.config/corral or a real
# device. Daemons and waiters are killed by exact pid only — never `pkill -f` a pattern that
# also appears in this script's own command line (a `corral wait` JVM has "corral" in argv).
set -u

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SELF/.." && pwd)
JAR="$ROOT/corral-daemon/build/libs/corral-daemon-all.jar"
PORT="${E2E_PORT:-8801}"                 # wrapper suite owns 8799; never reuse it
CORRAL_URL="http://127.0.0.1:$PORT"
STALE_S=$((3 * 5000 / 1000))            # MCP cadence 5000ms x STALE_POLLS=3 -> 15s

pass=0; fail=0
check() { # check <name> <cond...>
  local name="$1"; shift
  if "$@"; then printf 'ok %d %s\n' "$((++pass))" "$name"
  else printf 'FAIL %d %s\n' "$((++fail))" "$name"; fi
}
skip() { printf 'ok %d %s (skipped; set SLOW=1)\n' "$((++pass))" "$1"; }

ms_now() { date +%s%N; }
elapsed_ms() { echo $(( ($2 - $1) / 1000000 )); }

# --- setup ---
(cd "$ROOT" && ./gradlew :corral-daemon:shadowJar >/dev/null 2>&1) || { echo "build failed"; exit 1; }
[ -f "$JAR" ] || { echo "jar not found: $JAR (run from the source tree)"; exit 1; }

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

mkdir -p "$TMP/bin" "$TMP/sdk/platform-tools" "$TMP/bind"
printf 'dev1 device Pixel_6 13\ndev2 device SM-S918B 13\n' > "$TMP/fake-devices"
printf '#!/bin/sh\necho "fake-adb $@"\n' > "$TMP/fake-adb"; chmod +x "$TMP/fake-adb"
cp "$SELF/adb-wrapper" "$TMP/bin/adb"; chmod +x "$TMP/bin/adb"
cp "$SELF/corral" "$TMP/bin/corral"; chmod +x "$TMP/bin/corral"
cp "$SELF/corral-rules" "$TMP/corral-rules"
# faithful platform-tools layout: adb (the wrapper/shim) + adb.real (the real binary)
printf '#!/bin/sh\necho "sdk-shim $@"\n' > "$TMP/sdk/platform-tools/adb"; chmod +x "$TMP/sdk/platform-tools/adb"
printf '#!/bin/sh\necho "sdk-real $@"\n' > "$TMP/sdk/platform-tools/adb.real"; chmod +x "$TMP/sdk/platform-tools/adb.real"

export CORRAL_JAR="$JAR" CORRAL_URL CORRAL_OWNER=tester
export CORRAL_REAL_ADB="$TMP/fake-adb" CORRAL_ADB="$TMP/fake-adb"
export CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/corral.db" CORRAL_RULES="$TMP/corral-rules"
export PATH="$TMP/bin:$PATH"
unset ANDROID_SERIAL

start_daemon() { # start_daemon [log] [db] [port]
  local log="${1:-$TMP/daemon.log}" db="${2:-$TMP/corral.db}" port="${3:-$PORT}"
  setsid env CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$db" CORRAL_RULES="$TMP/corral-rules" \
    CORRAL_PORT="$port" \
    java -Dcorral.test.tag=$$ -jar "$JAR" </dev/null >"$log" 2>&1 &
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

# --- 1.10: two daemons, one DB. Unsupported. Observed 2026-09: both start, independent
# in-memory maps over one SQLite file, and the same serial can be leased by both (silent
# divergence). Pin whichever loud/silent mode we actually get, never an invisible success.
check_1_10() {
  local port2=$((PORT + 1)) p2 drc c1 c2 i result=1
  setsid env CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/corral.db" \
    CORRAL_RULES="$TMP/corral-rules" CORRAL_PORT="$port2" \
    java -Dcorral.test.tag=$$ -jar "$JAR" </dev/null >"$TMP/daemon-2db.log" 2>&1 &
  p2=$!
  for i in $(seq 1 50); do
    curl -s --max-time 1 "http://127.0.0.1:$port2/api/devices" >/dev/null 2>&1 && break
    kill -0 "$p2" 2>/dev/null || break
    sleep 0.2
  done
  if ! kill -0 "$p2" 2>/dev/null; then
    wait "$p2" 2>/dev/null; drc=$?
    printf '# 1.10: second daemon on the same DB exited rc=%s (loud)\n' "$drc"
    [ "$drc" -ne 0 ] && [ "$drc" -ne 124 ] && result=0
  else
    c1=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$CORRAL_URL/api/leases" \
      -H 'Content-Type: application/json' -d '{"selector":{"serial":"dev1"},"owner":"main"}')
    c2=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$port2/api/leases" \
      -H 'Content-Type: application/json' -d '{"selector":{"serial":"dev1"},"owner":"second"}')
    printf '# 1.10: both daemons started on one DB; double-lease main=%s second=%s (silent divergence)\n' "$c1" "$c2"
    [ "$c1" = 200 ] && [ "$c2" = 200 ] && result=0
    kill -9 "$p2" 2>/dev/null
  fi
  wait "$p2" 2>/dev/null
  return $result
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

# --- run ---
start_daemon || exit 1
check "8.6 daemon binds loopback only" check_8_6
check "1.9 second daemon on same port exits non-zero" check_1_9
check "1.10 two daemons same DB: unsupported, loud exit or silent divergence pinned" check_1_10

stop_daemon
rm -f "$TMP/corral.db"*
start_daemon "$TMP/daemon-main.log" || exit 1
check "1.6 restart with tickets queued: waiter errors promptly, never hangs" check_1_6
check "5.20 SIGINT cancels the waiter ticket immediately" check_5_20
check "5.8 SIGKILLed corral wait: next waiter served < 3s (pid prune)" check_5_8
check "5.9/5.11 quiet REST/MCP waiter skipped within staleness window, not offer hold" check_5_9
if [ "${SLOW:-0}" = 1 ]; then
  check "1.14 poll-silence drop leaves corral queue within POLL_SILENCE_S + slack" check_1_14
else
  skip "1.14 poll-silence drop"
fi
check "6.19 SDK-dir-first PATH: ~/bin wrapper never intercepts" check_6_19

printf '\n%d passed, %d failed\n' "$pass" "$fail"
exit "$fail"
