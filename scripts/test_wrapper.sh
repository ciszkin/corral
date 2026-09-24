#!/usr/bin/env bash
# corral adb-wrapper integration test suite.
set -u

# Runs from the source tree (rebuilds the jar) and from the deployed bundle (uses the jar beside
# this script): the bundle has no gradle project and no scripts/ subdirectory.
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -f "$SELF/corral-daemon-all.jar" ] && [ -f "$SELF/adb-wrapper" ]; then
  SRC="$SELF"; ROOT=""; JAR="$SELF/corral-daemon-all.jar"; BUILD=0
else
  SRC="$SELF"; ROOT=$(cd "$SELF/.." && pwd); JAR="$ROOT/corral-daemon/build/libs/corral-daemon-all.jar"; BUILD=1
fi
PORT=8799
CORRAL_URL="http://127.0.0.1:$PORT"

pass=0; fail=0
check() { # check <name> <cond>
  local name="$1"; shift
  if "$@"; then printf 'ok %d %s\n' "$((++pass))" "$name"
  else printf 'FAIL %d %s\n' "$((++fail))" "$name"; fi
}

# --- setup ---
if [ "$BUILD" = 1 ]; then
  (cd "$ROOT" && ./gradlew :corral-daemon:shadowJar >/dev/null 2>&1) || { echo "build failed"; exit 1; }
fi
[ -f "$JAR" ] || { echo "jar not found: $JAR (run from the source tree or beside corral-daemon-all.jar)"; exit 1; }

TMP=$(mktemp -d)
export TMP
DAEMON_PID=""
cleanup() {
  [ -n "$DAEMON_PID" ] && kill "$DAEMON_PID" 2>/dev/null
  pkill -f "corral.test.tag=$$" 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT

mkdir -p "$TMP/bin"
printf 'dev1 device Pixel_6 13\ndev2 device SM-S918B 13\n' > "$TMP/fake-devices"
printf '#!/bin/sh\necho "fake-adb $@"\n' > "$TMP/fake-adb"
chmod +x "$TMP/fake-adb"
cp "$SRC/adb-wrapper" "$TMP/bin/adb"
chmod +x "$TMP/bin/adb"
cp "$SRC/corral" "$TMP/bin/corral"
chmod +x "$TMP/bin/corral"
export CORRAL_RULES="$TMP/corral-rules"
cp "$SRC/corral-rules" "$CORRAL_RULES"
export CORRAL_JAR="$JAR"
export CORRAL_ENV_FILE="$TMP/daemon.env"   # absent on purpose: inherited env must keep winning in tests

start_daemon() {
  setsid env CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/corral.db" CORRAL_PORT=$PORT \
    java -Dcorral.test.tag=$$ -jar "$JAR" </dev/null >"$TMP/daemon.log" 2>&1 &
  disown
  DAEMON_PID=$!
  local i
  for i in $(seq 1 30); do
    curl -sf "$CORRAL_URL/api/devices" >/dev/null 2>&1 && return 0
    sleep 0.2
  done
  echo "daemon failed to start"; cat "$TMP/daemon.log"; return 1
}

if curl -s --max-time 1 http://127.0.0.1:8799/api/devices >/dev/null 2>&1; then
  echo "FAIL: port 8799 already in use — refusing to test against a foreign daemon"; exit 1
fi
start_daemon || exit 1

export CORRAL_URL CORRAL_REAL_ADB="$TMP/fake-adb" CORRAL_OWNER=tester
export PATH="$TMP/bin:$PATH"
unset ANDROID_SERIAL

# --- checks ---
check "adb devices allowed" bash -c 'adb devices >/dev/null'

out=$(adb --version 2>&1)
check "adb --version passes through" bash -c "echo '$out' | grep -q fake-adb"
out=$(adb --help 2>&1)
check "adb --help passes through" bash -c "echo '$out' | grep -q fake-adb"
out=$(adb -v devices 2>&1)
check "adb -v devices passes through" bash -c "echo '$out' | grep -q fake-adb"
out=$(adb -P 5037 devices 2>&1)
check "adb -P 5037 devices (value flag) passes through" bash -c "echo '$out' | grep -q fake-adb"
out=$(adb -t 3 shell ls 2>&1)
check "adb -t 3 shell ls denied (device selector, no pin)" bash -c "echo '$out' | grep -q 'no device pinned'"

if adb shell ls >/dev/null 2>"$TMP/c2"; then r=1; else r=0; fi
grep -q "no device pinned" "$TMP/c2" && c=0 || c=1
check "adb shell ls denied, no pin" bash -c "exit $((r || c))"

if adb -s dev1 shell ls >/dev/null 2>"$TMP/c3"; then r=1; else r=0; fi
check "adb -s dev1 denied before lease" bash -c "exit $r"

curl -sf -X POST "$CORRAL_URL/api/leases" -H 'Content-Type: application/json' \
  -d '{"selector":{"serial":"dev1"},"owner":"tester"}' >/dev/null
out=$(adb -s dev1 shell ls 2>&1)
check "adb -s dev1 shell ls allowed with lease" bash -c "echo '$out' | grep -q fake-adb"

out=$(ANDROID_SERIAL=dev1 adb shell echo hi 2>&1)
check "ANDROID_SERIAL=dev1 env pin allowed" bash -c "echo '$out' | grep -q fake-adb"

if CORRAL_OWNER=other adb -s dev1 shell ls >/dev/null 2>"$TMP/c6"; then r=1; else r=0; fi
check "CORRAL_OWNER=other denied" bash -c "exit $r"

pkill -f "corral.test.tag=$$" 2>/dev/null; sleep 0.5
if adb -s dev1 shell ls >/dev/null 2>"$TMP/c7"; then r=1; else r=0; fi
grep -q "daemon unreachable" "$TMP/c7" && c=0 || c=1
check "fails closed when daemon down" bash -c "exit $((r || c))"

start_daemon || exit 1
# dev1 lease from earlier was lost with daemon restart; dev2 free for racing
curl -sf -o "$TMP/r1" -w '%{http_code}' -X POST "$CORRAL_URL/api/leases" \
  -H 'Content-Type: application/json' -d '{"selector":{"serial":"dev2"},"owner":"tester"}' > "$TMP/c1" &
curl -sf -o "$TMP/r2" -w '%{http_code}' -X POST "$CORRAL_URL/api/leases" \
  -H 'Content-Type: application/json' -d '{"selector":{"serial":"dev2"},"owner":"tester"}' > "$TMP/c2" &
wait
c1=$(cat "$TMP/c1"); c2=$(cat "$TMP/c2")
ok=0
if { [ "$c1" = "200" ] && [ "$c2" = "409" ]; } || { [ "$c1" = "409" ] && [ "$c2" = "200" ]; }; then ok=1; fi
check "two racing claims: one 200 one 409" bash -c "exit $((1-ok))"

# dev2 now leased; free it, then ensure dev1 has a tester lease for check 10
curl -sf -X DELETE "$CORRAL_URL/api/leases/dev2" -H 'Content-Type: application/json' \
  -d '{"owner":"tester"}' >/dev/null
# reclaim dev1 (no-op if its lease survived the restart; needed if it did not)
curl -sf -X POST "$CORRAL_URL/api/leases" -H 'Content-Type: application/json' \
  -d '{"selector":{"serial":"dev1"},"owner":"tester"}' >/dev/null

if adb install foo.apk >/dev/null 2>"$TMP/c9"; then r=1; else r=0; fi
check "adb install (no pin) denied" bash -c "exit $r"

out=$(adb -s dev1 install foo.apk 2>&1)
check "adb -s dev1 install allowed with lease" bash -c "echo '$out' | grep -q fake-adb"

# dev2 is free here (dev1 is leased by tester); a declared CORRAL_OWNER is the identity and
# separates sessions that declare different identifiers
check "declared corral owner is namespaced and separates sessions" bash -c '
  curl -sf -X POST "$CORRAL_URL/api/leases" -H "Content-Type: application/json" \
    -d "{\"selector\":{\"serial\":\"dev2\"},\"owner\":\"sesA\"}" >/dev/null || exit 1
  CORRAL_OWNER=sesA adb -s dev2 shell ls 2>&1 | grep -q fake-adb || exit 1
  if CORRAL_OWNER=sesB adb -s dev2 shell ls >/dev/null 2>&1; then exit 1; fi
  curl -sf -X DELETE "$CORRAL_URL/api/leases/dev2" -H "Content-Type: application/json" \
    -d "{\"owner\":\"sesA\"}" >/dev/null
  exit 0
'

# proc ancestry tier: with no declared identifier, the CLI and the wrapper must
# derive the SAME owner from the process tree, and a different tree must differ.
proc_owner_check() {
  local serial
  serial=$(env -u CORRAL_OWNER corral claim) || return 1
  [ -n "$serial" ] || return 1
  env -u CORRAL_OWNER \
    "$TMP/bin/adb" -s "$serial" shell ls 2>&1 | grep -q fake-adb || return 1
  # a different process tree (python3 as the direct parent) resolves a different owner
  if env -u CORRAL_OWNER \
      python3 -c 'import subprocess,sys; raise SystemExit(subprocess.run(sys.argv[1:]).returncode)' \
      "$TMP/bin/adb" -s "$serial" shell ls >/dev/null 2>&1; then return 1; fi
  env -u CORRAL_OWNER corral free "$serial" >/dev/null || return 1
  return 0
}
check "proc ancestry owner: shared cli/wrapper, distinct across trees" proc_owner_check

# the ancestry walk has a floor: an orphaned (reparented onto init) client must not resolve to
# systemd — a shared, immortal principal that would let every orphan free every other's leases
check "orphaned client resolves to user:*, never proc:systemd:*" bash -c '
  rm -f "$TMP/orphan.owner"
  bash -c "setsid bash -c \"sleep 0.4; env -u CORRAL_OWNER corral owner > $TMP/orphan.owner 2>&1\" </dev/null >/dev/null 2>&1 &"
  sleep 2
  o=$(cat "$TMP/orphan.owner")
  case "$o" in
    user:*) exit 0;;
    *) echo "orphan owner was: $o" >&2; exit 1;;
  esac
'

# identity agreement: for each identity source, the CLI (Kotlin chain) and the wrapper (bash chain)
# must resolve the same owner — a CLI-claimed lease is honored by the wrapper with the same env.
check "identity agreement: cli and wrapper resolve the same owner" bash -c '
  claim_check_free() {
    # $@ = env prefix as a command, e.g. "env CORRAL_OWNER=agree1" or "env -u CORRAL_OWNER"
    s=$("$@" corral claim 2>/dev/null) || return 1
    [ -n "$s" ] || return 1
    "$@" adb -s "$s" shell ls 2>&1 | grep -q fake-adb || return 1
    "$@" corral free "$s" >/dev/null || return 1
    return 0
  }
  claim_check_free env CORRAL_OWNER=agree1 || exit 1
  claim_check_free env -u CORRAL_OWNER || exit 1
  exit 0
'

# where /proc is absent (macOS/Windows hosts) the wrapper cannot walk the tree itself; it
# delegates identity to `corral owner` (the CLI's walk), so the owner it checks with is the
# one the delegate reports — and a disagreeing delegate is denied
check "no-proc host: wrapper delegates identity to corral owner" bash -c '
  mkdir -p "$TMP/noproc" "$TMP/fakebin"
  printf "#!/bin/sh\necho delegated-owner\n" > "$TMP/fakebin/corral"; chmod +x "$TMP/fakebin/corral"
  curl -sf -X POST "$CORRAL_URL/api/leases" -H "Content-Type: application/json" \
    -d "{\"selector\":{\"serial\":\"dev2\"},\"owner\":\"delegated-owner\"}" >/dev/null || exit 1
  rc=0
  PATH="$TMP/fakebin:$PATH" CORRAL_PROC_ROOT="$TMP/noproc" env -u CORRAL_OWNER \
    "$TMP/bin/adb" -s dev2 shell ls >/dev/null 2>&1 || rc=1
  printf "#!/bin/sh\necho someone-else\n" > "$TMP/fakebin/corral"
  PATH="$TMP/fakebin:$PATH" CORRAL_PROC_ROOT="$TMP/noproc" env -u CORRAL_OWNER \
    "$TMP/bin/adb" -s dev2 shell ls >/dev/null 2>&1 && rc=1
  curl -sf -X DELETE "$CORRAL_URL/api/leases/dev2" -H "Content-Type: application/json" \
    -d "{\"owner\":\"delegated-owner\"}" >/dev/null
  exit $rc
'

# blank identity keys fall back to the built-in lists in both readers; an emptied shell_skip
# would make the wrapper resolve proc:bash:* where the CLI skips bash, breaking the lease check
check "blank identity keys keep defaults in both readers" bash -c '
  d="$TMP/blank"; mkdir -p "$d"
  printf "readonly=devices\nvalueflags=-P\nshell_skip=\ninit_floor=\n" > "$d/rules"
  s=$(CORRAL_RULES="$d/rules" env -u CORRAL_OWNER corral claim 2>/dev/null) || exit 1
  [ -n "$s" ] || exit 1
  CORRAL_RULES="$d/rules" env -u CORRAL_OWNER "$TMP/bin/adb" -s "$s" shell ls 2>&1 | grep -q fake-adb || exit 1
  CORRAL_RULES="$d/rules" env -u CORRAL_OWNER corral free "$s" >/dev/null || exit 1
  exit 0
'

# identity entries in the rules file match the lowercased comm in both readers: an uppercase
# shell_skip entry must still skip that process, not be silently dead (a dead entry would make
# the wrapper resolve proc:python3:* where the claim saw the agent above it)
check "rules identity entries match case-insensitively in both readers" bash -c '
  d="$TMP/case-rules"; mkdir -p "$d"
  printf "readonly=devices\nvalueflags=-P\nshell_skip=bash,sh,env,PYTHON3\n" > "$d/rules"
  s=$(CORRAL_RULES="$d/rules" env -u CORRAL_OWNER corral claim 2>/dev/null) || exit 1
  [ -n "$s" ] || exit 1
  rc=0
  CORRAL_RULES="$d/rules" env -u CORRAL_OWNER \
    python3 -c "import subprocess,sys; raise SystemExit(subprocess.run(sys.argv[1:]).returncode)" \
    "$TMP/bin/adb" -s "$s" shell ls >/dev/null 2>&1 || rc=1
  CORRAL_RULES="$d/rules" env -u CORRAL_OWNER corral free "$s" >/dev/null 2>&1
  exit $rc
'

# drift guard: for each argv the bash wrapper's verdict must equal `corral gate`'s
check "bash wrapper and corral gate agree on classification" bash -c '
  for line in "devices" "--version" "-P 5037 devices" "-t 3 shell ls" "shell ls" "install foo.apk" "-s \"\" devices" "-s"; do
    eval adb $line >/dev/null 2>&1; b=$?
    eval corral gate -- $line >/dev/null 2>&1; g=$?
    [ "$b" = "$g" ] || { echo "mismatch [$line]: bash=$b gate=$g" >&2; exit 1; }
  done
  exit 0
'

# the wrapper only consults valueflags inside its `-*)` branch, so a non-dash entry would be a
# subcommand there but a flag here — the API must refuse to write that drift at all
check "api rejects non-dash valueflags" bash -c '
  code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$CORRAL_URL/api/config" \
    -H "Content-Type: application/json" \
    -d "{\"rules\":{\"readonly\":[\"devices\"],\"valueflags\":[\"shell\"],\"shell_skip\":[],\"init_floor\":[]}}")
  [ "$code" = 400 ] || { echo "expected 400, got $code" >&2; exit 1; }
  exit 0
'

check "missing rules file fails closed in both readers" bash -c '
  missing="$TMP/no-such-rules"
  CORRAL_RULES="$missing" adb devices >/dev/null 2>&1 && exit 1
  CORRAL_RULES="$missing" corral gate -- devices >/dev/null 2>&1 && exit 1
  exit 0
'

check "hand-edited rules parse identically in bash and kotlin" bash -c '
  d="$TMP/hand"; mkdir -p "$d"
  printf "readonly = devices\nvalueflags = -P,-H,-L,-p,-t" > "$d/rules"
  CORRAL_RULES="$d/rules" adb -P 5037 devices >/dev/null 2>&1; b=$?
  CORRAL_RULES="$d/rules" corral gate -- -P 5037 devices >/dev/null 2>&1; g=$?
  [ "$b" = "$g" ] && [ "$b" = 0 ] || { echo "mismatch: bash=$b gate=$g (expected both 0)" >&2; exit 1; }
  exit 0
'

check "corral claim <serial> claims the pinned device" bash -c '
  s=$(corral claim dev2) || exit 1
  [ "$s" = "dev2" ] || exit 1
  corral check dev2 >/dev/null || exit 1
  corral free dev2 >/dev/null || exit 1
  exit 0
'

check "corral doctor reports daemon status" bash -c '
  corral doctor 2>&1 | grep -q "daemon: ok"
'

# a daemon whose configured adb no longer exists must FAIL doctor rather than silently pass
# this shell's own SDK tree (a fallback chain used to mask the stale daemon config)
check "doctor fails on the daemon's stale adb, not the shell's tree" bash -c '
  setsid env CORRAL_ADB="$TMP/gone/adb.real" CORRAL_FAKE_DEVICES="$TMP/fake-devices" \
    CORRAL_DB="$TMP/corral2.db" CORRAL_PORT=8800 java -Dcorral.test.tag=doctor-stale -jar "$CORRAL_JAR" \
    </dev/null >"$TMP/daemon2.log" 2>&1 &
  d2=$!
  up=0
  for i in $(seq 1 30); do
    curl -sf http://127.0.0.1:8800/api/devices >/dev/null 2>&1 && { up=1; break; }; sleep 0.2
  done
  [ $up -eq 1 ] || { kill $d2 2>/dev/null; cat "$TMP/daemon2.log" >&2; exit 1; }
  out=$(CORRAL_URL=http://127.0.0.1:8800 corral doctor 2>&1); rc=$?
  kill $d2 2>/dev/null
  [ $rc -ne 0 ] || { echo "doctor exited 0 (stale adb passed): $out" >&2; exit 1; }
  echo "$out" | grep -q "the daemon.s configured adb" || { echo "no daemon note in: $out" >&2; exit 1; }
  exit 0
'

check "corral owner prints a resolved identity" bash -c '
  o=$(CORRAL_OWNER=demo-owner corral owner)
  [ "$o" = "demo-owner" ] || exit 1
  exit 0
'
check "corral check fails when not all devices are leased" bash -c '
  if corral check >/dev/null 2>&1; then exit 1; fi
  exit 0
'
check "corral check passes once everything is leased" bash -c '
  for s in $(corral claim --all); do [ -n "$s" ] || exit 1; done
  corral check || exit 1
  corral check "$(corral devices | grep -o "\"serial\":\"[^\"]*\"" | head -1 | cut -d\" -f4)" || exit 1
  corral check --owner tester >/dev/null || exit 1
  exit 0
'
check "corral free --all then check fails again" bash -c '
  corral free --all >/dev/null || exit 1
  if corral check >/dev/null 2>&1; then exit 1; fi
  exit 0
'

check "corral claim -> adb allowed -> corral free" bash -c '
  serial=$(corral claim) || exit 1
  [ -n "$serial" ] || exit 1
  adb -s "$serial" shell ls 2>&1 | grep -q fake-adb || exit 1
  corral free "$serial" >/dev/null || exit 1
'

check "corral lease runs child with ANDROID_SERIAL then frees" bash -c '
  serial=$(corral lease -- env | grep ANDROID_SERIAL | cut -d= -f2)
  [ -n "$serial" ] || exit 1
  curl -s "$CORRAL_URL/api/check?serial=$serial&owner=$CORRAL_OWNER" | grep -q "\"allowed\":false"
'

check "foreign corral free refused" bash -c '
  serial=$(corral claim) || exit 1
  [ -n "$serial" ] || exit 1
  if CORRAL_OWNER=other corral free "$serial" >/dev/null 2>&1; then exit 1; fi
  exit 0
'

check "corral lease --all claims all then frees" bash -c '
  corral lease --all -- bash -c "corral check" || exit 1
  if corral check >/dev/null 2>&1; then exit 1; fi
  exit 0
'

# --- Phase 2: selector FIFO queue ---
# check 27's refused-foreign-free leaks a tester lease; clear it so the model
# checks below start from a known all-free state.
corral free --all >/dev/null 2>&1
check "selector claim by model via api" bash -c '
  s=$(curl -sf -X POST "$CORRAL_URL/api/leases" -H "Content-Type: application/json" \
    -d "{\"selector\":{\"model\":\"Pixel_6\"},\"owner\":\"tester\"}") || exit 1
  echo "$s" | grep -q "\"serial\":\"dev1\"" || exit 1
  curl -sf -X DELETE "$CORRAL_URL/api/leases/dev1" -H "Content-Type: application/json" \
    -d "{\"owner\":\"tester\"}" >/dev/null
  exit 0
'

check "corral claim --model and queue listing" bash -c '
  s=$(corral claim --model SM-S918B) || exit 1
  [ "$s" = "dev2" ] || exit 1
  [ "$(corral queue)" = "[]" ] || exit 1         # empty queue serializes as []
  corral free dev2 >/dev/null || exit 1
  exit 0
'

check "FIFO: first waiter gets the freed device" bash -c '
  corral claim dev1 >/dev/null; corral claim dev2 >/dev/null
  (corral wait --timeout 30 >"$TMP/w1.out" 2>&1) &
  sleep 1
  (CORRAL_OWNER=second corral wait --serial dev2 --timeout 30 >"$TMP/w2.out" 2>&1) &
  sleep 1
  curl -sf "$CORRAL_URL/api/queue" | grep -q "\"owner\":\"tester\"" || exit 1
  curl -sf "$CORRAL_URL/api/queue" | grep -q "\"owner\":\"second\"" || exit 1
  corral free dev1 >/dev/null
  sleep 2
  [ "$(cat "$TMP/w1.out")" = "dev1" ] || exit 1
  corral free dev2 >/dev/null
  sleep 2
  [ "$(cat "$TMP/w2.out")" = "dev2" ] || exit 1
  wait
  exit 0
'

check "corral wait times out with exit 1" bash -c '
  corral claim dev1 >/dev/null; corral claim dev2 >/dev/null
  if corral wait --timeout 1 --model Nope >/dev/null 2>&1; then
    corral free --all >/dev/null; exit 1
  fi
  corral free --all >/dev/null
  exit 0
'

check "lapsed unpolled ticket is dropped and the next waiter is served" bash -c '
  corral claim dev1 >/dev/null; corral claim dev2 >/dev/null
  # ghost ticket for dev1 is never polled; its offer must lapse and be dropped, not rotate
  curl -sf -X POST "$CORRAL_URL/api/leases" -H "Content-Type: application/json" \
    -d "{\"selector\":{\"serial\":\"dev1\"},\"owner\":\"ghost\",\"waitSeconds\":60}" >/dev/null
  (CORRAL_OWNER=second corral wait --serial dev1 --timeout 30 >"$TMP/w2.out" 2>&1) &
  sleep 1
  corral free dev1 >/dev/null
  for i in $(seq 1 25); do
    [ -s "$TMP/w2.out" ] && break
    sleep 1
  done
  [ "$(cat "$TMP/w2.out")" = "dev1" ] || exit 1
  CORRAL_OWNER=second corral free dev1 >/dev/null 2>&1
  corral free --all >/dev/null
  wait
  exit 0
'

# --- report ---
printf '\n%d passed, %d failed\n' "$pass" "$fail"
exit "$fail"
