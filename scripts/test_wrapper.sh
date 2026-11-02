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
export TMP SRC ROOT have_setsid have_pkill
DAEMON_PID=""
# --- host capabilities (row 33 F6: this suite must also run honestly on Windows/Git-Bash) --------
# setsid and pkill are POSIX-only. setsid detaches a daemon from this shell and is what lets the
# orphan row reparent a client onto init; pkill stops every daemon of this run by test tag. Where a
# tool is missing the workaround below is explicit, and a row whose MEANING depends on the missing
# semantics is SKIPPED with a message rather than passing vacuously.
have_setsid=0; command -v setsid >/dev/null 2>&1 && have_setsid=1
have_pkill=0; command -v pkill >/dev/null 2>&1 && have_pkill=1
DAEMON_PIDS=""
# Terminate one process (and its children) NOW. On Windows a plain MSYS `kill` can signal the
# shell's idea of the process while the native JVM keeps the port until it finishes shutting down,
# so a restart races the old daemon for the port. taskkill /F /T is immediate and definitive there.
kill_tree() {
  local p="$1"
  [ -n "$p" ] || return 0
  case "$(uname -s 2>/dev/null || true)" in
    MINGW*|MSYS*|CYGWIN*) MSYS2_ARG_CONV_EXCL='*' taskkill /F /T /PID "$p" >/dev/null 2>&1 || kill -9 "$p" 2>/dev/null ;;
    *) kill -9 "$p" 2>/dev/null ;;
  esac
}
stop_daemons() {
  local p i
  if [ "$have_pkill" = 1 ]; then pkill -9 -f "corral.test.tag=$$" 2>/dev/null || true; fi
  for p in $DAEMON_PID $DAEMON_PIDS; do kill_tree "$p"; done
  # wait for the port to be released: a second daemon cannot bind while the first is still up
  for i in $(seq 1 50); do
    curl -sf --max-time 1 "$CORRAL_URL/api/devices" >/dev/null 2>&1 || return 0
    sleep 0.2
  done
  return 0
}
cleanup() {
  [ -n "$DAEMON_PID" ] && kill_tree "$DAEMON_PID"
  stop_daemons
  [ -n "${WINEPREFIX:-}" ] && command -v wineserver >/dev/null 2>&1 && timeout 20 wineserver -k 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT
export -f kill_tree   # the doctor rows start their own short-lived daemons from `bash -c`

mkdir -p "$TMP/bin"
printf 'dev1 device Pixel_6 13\ndev2 device SM-S918B 13\n' > "$TMP/fake-devices"
printf '#!/bin/sh\necho "fake-adb $@"\n' > "$TMP/fake-adb"
chmod +x "$TMP/fake-adb"

# A "different process tree" runner: a NON-shell parent that runs its argv as a child, so the
# wrapper's ancestry walk sees `proc:<helper>:<pid>` instead of the caller's chain. python3 where a
# real one exists, else perl (Git Bash and macOS ship it). A Store-alias `python3` on Windows looks
# executable but exits with an error, so probe it rather than trusting `command -v`; if neither
# works the two rows that need it are skipped loudly (F6: never a silent, vacuous pass).
tree_helper=""
if python3 -c 'pass' >/dev/null 2>&1; then
  tree_helper=python3
  printf '%s\n' '#!/bin/sh' \
    'exec python3 -c "import subprocess,sys; raise SystemExit(subprocess.run(sys.argv[1:]).returncode)" "$@"' \
    > "$TMP/other-tree"
elif perl -e 'exit 0' >/dev/null 2>&1; then
  tree_helper=perl
  cat > "$TMP/other-tree" <<'EOS'
#!/bin/sh
exec perl -e 'my $rc = system @ARGV; exit(($rc == -1) ? 127 : ($rc >> 8))' -- "$@"
EOS
fi
chmod +x "$TMP/other-tree"
export tree_helper
other_tree() { "$TMP/other-tree" "$@"; }
export -f other_tree

# Can this host's JVM exec an EXTENSIONLESS POSIX script? `corral gate -- <cmd>` execs the command,
# and CreateProcess on Windows does not understand a shebang, so the fake `adb` shim is not
# executable from the CLI there (it is from the bash wrapper). The rows that need exec parity with
# a real argv are skipped on such a host, with the reason; the win: rows cover the .bat path.
gate_exec_ok=1
case "$(uname -s 2>/dev/null || true)" in MINGW*|MSYS*|CYGWIN*) gate_exec_ok=0;; esac
export gate_exec_ok
cp "$SRC/adb-wrapper" "$TMP/bin/adb"
chmod +x "$TMP/bin/adb"
cp "$SRC/corral" "$TMP/bin/corral"
chmod +x "$TMP/bin/corral"
export CORRAL_RULES="$TMP/corral-rules"
cp "$SRC/corral-rules" "$CORRAL_RULES"
export CORRAL_JAR="$JAR"
export CORRAL_ENV_FILE="$TMP/daemon.env"   # absent on purpose: inherited env must keep winning in tests

start_daemon() {
  if [ "$have_setsid" = 1 ]; then
    setsid env CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/corral.db" CORRAL_PORT=$PORT \
      java -Dcorral.test.tag=$$ -jar "$JAR" </dev/null >"$TMP/daemon.log" 2>&1 &
    disown
  else
    env CORRAL_FAKE_DEVICES="$TMP/fake-devices" CORRAL_DB="$TMP/corral.db" CORRAL_PORT=$PORT \
      java -Dcorral.test.tag=$$ -jar "$JAR" </dev/null >"$TMP/daemon.log" 2>&1 &
  fi
  DAEMON_PID=$!
  # disown in BOTH modes: without setsid the daemon is still a job of this shell, and the bare
  # `wait` in the racing-claims rows would then block until the daemon exits (it never does).
  disown 2>/dev/null || true
  DAEMON_PIDS="$DAEMON_PIDS $DAEMON_PID"
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

stop_daemons; sleep 0.5
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
  # a different process tree (a non-shell parent) resolves a different owner. The helper is
  # named by its FILE: env execs a program, so a shell function can never be its argv[0]
  # (the same reason the case-insensitivity row calls $TMP/other-tree directly).
  if env -u CORRAL_OWNER \
      "$TMP/other-tree" "$TMP/bin/adb" -s "$serial" shell ls >/dev/null 2>&1; then return 1; fi
  env -u CORRAL_OWNER corral free "$serial" >/dev/null || return 1
  return 0
}
if [ -n "$tree_helper" ]; then
  check "proc ancestry owner: shared cli/wrapper, distinct across trees" proc_owner_check
else
  echo "skip: proc ancestry row needs a working python3 or perl to create a different parent tree"
  echo "      (a Windows Store alias for python3 is not one); the CLI's walk is covered by AgentIdentityTest."
fi

# the ancestry walk has a floor: an orphaned (reparented onto init) client must not resolve to
# systemd — a shared, immortal principal that would let every orphan free every other's leases
if [ "$have_setsid" = 1 ]; then
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
else
  echo "skip: orphaned-client floor row needs setsid (POSIX reparenting onto init); this host has none,"
  echo "      so the row would test nothing. The floor itself is covered by AgentIdentityTest."
fi

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

# the owner fallback resolves the CLI from PATH entries only: command -v treats "." and empty
# PATH entries as the CWD, so a planted ./corral could answer as the owner. It must be ignored —
# the absolute PATH entry wins, and the planted script must never run.
check "posix: a planted ./corral is not adopted for the owner fallback" bash -c '
  d="$TMP/posix-plant"; fake="$TMP/posix-fakebin"; scratch="$TMP/posix-scratch"
  rm -rf "$d" "$fake" "$scratch"; mkdir -p "$d" "$fake" "$scratch"
  printf "#!/bin/sh\necho planted >> %s/marker\necho planted-owner\n" "$d" > "$d/corral"; chmod +x "$d/corral"
  printf "#!/bin/sh\necho delegated-owner\n" > "$fake/corral"; chmod +x "$fake/corral"
  curl -sf -X POST "$CORRAL_URL/api/leases" -H "Content-Type: application/json" \
    -d "{\"selector\":{\"serial\":\"dev2\"},\"owner\":\"delegated-owner\"}" >/dev/null || exit 1
  # "." first (the CWD alias under test), then an empty scratch dir, then the fake CLI, and only
  # then the tool directories (which supply curl/grep) — so a real corral cannot shadow the fake one.
  # curl lives in /mingw64/bin on Git Bash, not /usr/bin, so name its directory explicitly (F6).
  tooldir=$(dirname "$(command -v curl)")
  ( cd "$d" && PATH=".:$scratch:$fake:/usr/bin:/bin:$tooldir" CORRAL_PROC_ROOT="$TMP/noproc" env -u CORRAL_OWNER \
      "$TMP/bin/adb" -s dev2 shell ls >/dev/null 2>&1 ); rc=$?
  curl -sf -X DELETE "$CORRAL_URL/api/leases/dev2" -H "Content-Type: application/json" \
    -d "{\"owner\":\"delegated-owner\"}" >/dev/null 2>&1
  [ "$rc" -eq 0 ] || { echo "wrapper failed (rc=$rc) with a planted ./corral in the CWD" >&2; exit 1; }
  [ -f "$d/marker" ] && { echo "planted ./corral was executed" >&2; exit 1; }
  exit 0
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
# the wrapper resolve proc:<helper>:* where the claim saw the agent above it). The helper's name is
# added in its OWN case to the skip list: PYTHON3 / PERL.
if [ -n "$tree_helper" ]; then
if [ "$gate_exec_ok" = 1 ]; then
check "rules identity entries match case-insensitively in both readers" bash -c '
  d="$TMP/case-rules"; mkdir -p "$d"
  helper_upper=$(printf "%s" "$tree_helper" | tr "[:lower:]" "[:upper:]")
  printf "readonly=devices\nvalueflags=-P\nshell_skip=bash,sh,env,%s\n" "$helper_upper" > "$d/rules"
  s=$(CORRAL_RULES="$d/rules" env -u CORRAL_OWNER corral claim 2>"$d/claim.err") || {
    echo "claim failed: $(cat "$d/claim.err")" >&2; exit 1; }
  [ -n "$s" ] || { echo "claim returned no serial" >&2; exit 1; }
  rc=0
  # Name the helper FILE, not the other_tree() function: `env` execs a program, and an exported
  # bash function is not one — it never appears as argv[0] of anything (measured on POSIX:
  # "env: other_tree: No such file or directory", rc=127, which is a harness failure, not a
  # product one). $TMP/other-tree is the executable the function wrapped all along.
  err=$(CORRAL_RULES="$d/rules" env -u CORRAL_OWNER \
    "$TMP/other-tree" "$TMP/bin/adb" -s "$s" shell ls 2>&1) || rc=1
  [ "$rc" -eq 0 ] || echo "wrapper denied $s: $err" >&2
  CORRAL_RULES="$d/rules" env -u CORRAL_OWNER corral free "$s" >/dev/null 2>&1
  exit $rc
'
else
# No readable /proc: the CLI and the wrapper are two independent walks, and MSYS fork copies make
# them legitimately disagree (row32 research §6.2a — one may degrade to user:$USER). Comparing them
# on the host chain would be flaky, so run the deterministic half here: a simulated chain whose
# comms are UPPERCASE and `.exe`-spelled must be skipped by the wrapper's normalised list. The CLI
# half of the same rule is AgentIdentityTest.skipListsMatchCaseInsensitivelyButIdentityKeepsCase.
echo "skip: host-chain identity-agreement row needs a POSIX /proc whose ancestry both walks can read"
echo "      (on MSYS the fork copies can make the CLI and the wrapper legitimately disagree, row32"
echo "      research §6.2a); running the deterministic wrapper half instead"
check "uppercase/.exe identity entries are skipped by the wrapper (simulated chain)" bash -c '
  d="$TMP/case-chain"; stub="$d/stub"; rm -rf "$d"; mkdir -p "$d/proc/self" "$stub"
  : > "$d/proc/self/stat"
  cat > "$stub/awk" <<AWK
#!/bin/sh
p=\${2%/*}; p=\${p##*/}
case "\$p" in
  203) echo "agent.exe 1 3" ;;
  201) echo "BASH.EXE 202 1" ;;
  202) echo "Env.Exe 203 2" ;;
  *)   echo "BASH.EXE 201 0" ;;
esac
AWK
  chmod +x "$stub/awk"
  printf "readonly=devices\nvalueflags=-P\nshell_skip=BASH,ENV\n" > "$d/rules"
  corral free dev1 >/dev/null 2>&1
  curl -sf -X POST "$CORRAL_URL/api/leases" -H "Content-Type: application/json" \
    -d "{\"selector\":{\"serial\":\"dev1\"},\"owner\":\"proc:agent.exe:203:3\"}" >/dev/null || {
    echo "could not plant the agent lease on dev1" >&2; exit 1; }
  rc=0
  err=$(PATH="$stub:$PATH" CORRAL_RULES="$d/rules" CORRAL_PROC_ROOT="$d/proc" \
    CORRAL_REAL_ADB="$TMP/fake-adb" env -u CORRAL_OWNER \
    "$TMP/bin/adb" -s dev1 shell ls 2>&1) || rc=1
  [ "$rc" -eq 0 ] || echo "wrapper did not skip the uppercase/.exe entries: $err" >&2
  curl -sf -X DELETE "$CORRAL_URL/api/leases/dev1" -H "Content-Type: application/json" \
    -d "{\"owner\":\"proc:agent.exe:203:3\"}" >/dev/null 2>&1
  exit $rc
'
fi
else
  echo "skip: case-insensitive identity row needs a working python3 or perl as the non-shell parent"
fi

# F3 (row 32): Windows reports `bash.exe` where the identity lists spell `bash`. The walk must step
# over it and resolve the agent ABOVE — this is the behavioural drift guard for the two copies of
# the lists (RulesFile.DEFAULT_SHELL_SKIP and this wrapper's shell_list): a spelling added to one
# reader only, or a one-sided `.exe` entry, fails here. Host-independent: the chain is simulated
# through CORRAL_PROC_ROOT (on Git-Bash the host's own $PPID is a transient fork copy, so the row
# must not depend on it) with a table-driven awk stub on PATH for this row only.
check "windows .exe comm spellings are skipped by the wrapper's walk (F3 drift guard)" bash -c '
  d="$TMP/win-chain"; stub="$d/stub"; rm -rf "$d"; mkdir -p "$d/proc/self" "$stub"
  : > "$d/proc/self/stat"                       # the walk gate: "is there a procfs"
  cat > "$stub/awk" <<AWK
#!/bin/sh
p=\${2%/*}; p=\${p##*/}
case "\$p" in
  103) echo "opencode.exe 1 3" ;;     # the agent
  101) echo "bash.exe 102 1" ;;       # what the walk used to stop on
  102) echo "bash.exe 103 2" ;;
  *)   echo "bash.exe 101 0" ;;       # the transient parent that started the wrapper
esac
AWK
  chmod +x "$stub/awk"
  # dev1 may be leased from an earlier row; free the tester lease first so the plant is deterministic
  corral free dev1 >/dev/null 2>&1
  if ! curl -sf -X POST "$CORRAL_URL/api/leases" -H "Content-Type: application/json" \
      -d "{\"selector\":{\"serial\":\"dev1\"},\"owner\":\"proc:opencode.exe:103:3\"}" >/dev/null; then
    echo "could not plant the agent lease on dev1 (already leased?)" >&2; exit 1
  fi
  rc=0
  PATH="$stub:$PATH" CORRAL_PROC_ROOT="$d/proc" CORRAL_REAL_ADB="$TMP/fake-adb" env -u CORRAL_OWNER \
    "$TMP/bin/adb" -s dev1 shell ls >/dev/null 2>&1 || rc=1
  curl -sf -X DELETE "$CORRAL_URL/api/leases/dev1" -H "Content-Type: application/json" \
    -d "{\"owner\":\"proc:opencode.exe:103:3\"}" >/dev/null 2>&1
  [ "$rc" -eq 0 ] || { echo "the wrapper did not walk past bash.exe to the agent" >&2; exit 1; }
  exit 0
'

# The two readers' built-in skip lists must be the SAME LIST once keyed with the same rule
# (comm_key): a name added to one reader only, or a one-sided `.exe` entry, diverges here. A static
# check is the only way to compare the literals; behaviour can only see the comms a host has.
# Anchored on the constant's declaration so a move fails loudly instead of passing vacuously.
check "wrapper and CLI built-in identity lists agree (list-parity guard)" bash -c '
  [ -n "$ROOT" ] || { echo "skip: deployed bundle has no daemon sources to compare against" >&2; exit 0; }
  norm() { local out="" w; for w in $1; do w=${w,,}; out+=" ${w%.exe}"; done; printf "%s\n" "$out" | tr " " "\n" | sort -u | tr "\n" " "; }
  from_cli=$(sed -n "/^val DEFAULT_SHELL_SKIP = setOf(/,/)/p" \
               "$ROOT/corral-daemon/src/main/kotlin/dev/corral/RulesFile.kt")
  [ -n "$from_cli" ] || { echo "DEFAULT_SHELL_SKIP not found in RulesFile.kt (did it move?)" >&2; exit 1; }
  cli=$(norm "$(printf "%s" "$from_cli" | grep -o "\"[^\"]*\"" | tr -d "\"" | tr "\n" " ")")
  sh=$(norm "$(grep -m1 "^shell_list=" "$SRC/adb-wrapper" | cut -d"\"" -f2)")
  [ "$cli" = "$sh" ] || { echo "skip lists diverge: cli=[$cli] sh=[$sh]" >&2; exit 1; }
  exit 0
'

# drift guard: for each argv the bash wrapper's verdict must equal `corral gate`'s
check "bash wrapper and corral gate agree on classification" bash -c '
  for line in "devices" "--version" "-P 5037 devices" "-t 3 shell ls" "shell ls" "install foo.apk" "-s \"\" devices" "-s"; do
    eval adb $line >/dev/null 2>&1; b=$?
    eval corral gate -- $line >/dev/null 2>&1; g=$?
    # 127 means the CLI classified the argv as ALLOWED and tried to exec it; on Windows a JVM
    # cannot exec the extensionless POSIX shim (CreateProcess has no shebang), so an allowed argv
    # ends there. That is still the same classification as the wrapper'"'"'s 0.
    [ "$gate_exec_ok" = 0 ] && [ "$g" -eq 127 ] && g=0
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
  # see the gate-classification row: 127 = allowed and unexecutable from the JVM on Windows
  [ "$gate_exec_ok" = 0 ] && [ "$g" -eq 127 ] && g=0
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

# an empty pool must not read as "nothing is plugged in": doctor prints the adb-reconnect
# possibility and its recovery, and still exits 0 (nothing is failing)
check "corral doctor prints the empty-pool hint and exits 0" bash -c '
  : > "$TMP/empty-devices"
  # a daemon left on 8801 by another session would answer below; take the next free port
  port=8801
  while curl -sf --max-time 1 "http://127.0.0.1:$port/api/devices" >/dev/null 2>&1; do port=$((port+1)); done
  if [ "$have_setsid" = 1 ]; then
    setsid env CORRAL_FAKE_DEVICES="$TMP/empty-devices" CORRAL_DB="$TMP/corral3.db" CORRAL_PORT=$port \
      java -Dcorral.test.tag=doctor-empty -jar "$CORRAL_JAR" </dev/null >"$TMP/daemon3.log" 2>&1 &
  else
    env CORRAL_FAKE_DEVICES="$TMP/empty-devices" CORRAL_DB="$TMP/corral3.db" CORRAL_PORT=$port \
      java -Dcorral.test.tag=doctor-empty -jar "$CORRAL_JAR" </dev/null >"$TMP/daemon3.log" 2>&1 &
  fi
  d3=$!
  up=0
  for i in $(seq 1 30); do
    curl -sf "http://127.0.0.1:$port/api/devices" >/dev/null 2>&1 && { up=1; break; }; sleep 0.2
  done
  [ $up -eq 1 ] || { kill_tree "$d3"; cat "$TMP/daemon3.log" >&2; exit 1; }
  # pin the SDK probe too, or the exit code reflects the host SDK state, not the pool
  out=$(CORRAL_URL="http://127.0.0.1:$port" CORRAL_ADB="$TMP/fake-adb" corral doctor 2>&1); rc=$?
  kill_tree "$d3"
  echo "$out" | grep -q "^devices: hint - .*adb kill-server && adb start-server" \
    || { echo "no empty-pool hint in: $out" >&2; exit 1; }
  [ $rc -eq 0 ] || { echo "doctor exited $rc on an empty pool: $out" >&2; exit 1; }
  exit 0
'

# a daemon whose configured adb no longer exists must FAIL doctor rather than silently pass
# this shell's own SDK tree (a fallback chain used to mask the stale daemon config)
check "doctor fails on the daemon's stale adb, not the shell's tree" bash -c '
  if [ "$have_setsid" = 1 ]; then
    setsid env CORRAL_ADB="$TMP/gone/adb.real" CORRAL_FAKE_DEVICES="$TMP/fake-devices" \
      CORRAL_DB="$TMP/corral2.db" CORRAL_PORT=8800 java -Dcorral.test.tag=doctor-stale -jar "$CORRAL_JAR" \
      </dev/null >"$TMP/daemon2.log" 2>&1 &
  else
    env CORRAL_ADB="$TMP/gone/adb.real" CORRAL_FAKE_DEVICES="$TMP/fake-devices" \
      CORRAL_DB="$TMP/corral2.db" CORRAL_PORT=8800 java -Dcorral.test.tag=doctor-stale -jar "$CORRAL_JAR" \
      </dev/null >"$TMP/daemon2.log" 2>&1 &
  fi
  d2=$!
  up=0
  for i in $(seq 1 30); do
    curl -sf http://127.0.0.1:8800/api/devices >/dev/null 2>&1 && { up=1; break; }; sleep 0.2
  done
  [ $up -eq 1 ] || { kill_tree "$d2"; cat "$TMP/daemon2.log" >&2; exit 1; }
  out=$(CORRAL_URL=http://127.0.0.1:8800 corral doctor 2>&1); rc=$?
  kill_tree "$d2"
  [ $rc -ne 0 ] || { echo "doctor exited 0 (stale adb passed): $out" >&2; exit 1; }
  echo "$out" | grep -q "the daemon.s configured adb" || { echo "no daemon note in: $out" >&2; exit 1; }
  exit 0
'

check "corral owner prints a resolved identity" bash -c '
  o=$(CORRAL_OWNER=demo-owner corral owner)
  [ "$o" = "demo-owner" ] || exit 1
  exit 0
'
# windows fourth-run: the Windows SDK lives under %LOCALAPPDATA%\Android\Sdk, which the old
# detection never tried, so doctor could find no adb with the daemon down and no ANDROID_HOME.
check "doctor finds the Windows SDK adb under LOCALAPPDATA" bash -c '
  mkdir -p "$TMP/localappdata/Android/Sdk/platform-tools"
  printf "#!/bin/sh\nexit 0\n" > "$TMP/localappdata/Android/Sdk/platform-tools/adb.exe"
  chmod +x "$TMP/localappdata/Android/Sdk/platform-tools/adb.exe"
  out=$(env -u ANDROID_HOME -u ANDROID_SDK_ROOT -u CORRAL_ADB \
    LOCALAPPDATA="$TMP/localappdata" HOME="$TMP/empty-home" CORRAL_URL=http://127.0.0.1:9 corral doctor 2>&1)
  # the printed path is native on a Windows host and POSIX elsewhere; match on the marker plus the
  # trailing components with either separator (row 33 F6: the old single POSIX grep failed on MSYS)
  echo "$out" | grep -q "skipped - unmanaged adb path (" \
    && echo "$out" | grep -qE "localappdata[/\\\\]Android[/\\\\]Sdk[/\\\\]platform-tools[/\\\\]adb\.exe" \
    || { echo "no localappdata adb in: $out" >&2; exit 1; }
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

# The identity rows above can leave a lease recorded under an owner this shell cannot free: on a
# host without /proc the CLI's derived identity can differ between invocations (row32 research
# §6.2a — one walk may degrade to user:$USER), so a claim and a later free need not use the same
# owner. Start the queue rows from a fresh daemon + database instead of an unpredictable lease table.
restart_daemon_clean() {
  stop_daemons
  rm -f "$TMP/corral.db" "$TMP/corral.db-wal" "$TMP/corral.db-shm"
  DAEMON_PID=""; DAEMON_PIDS=""
  start_daemon
}
restart_daemon_clean || exit 1

check "FIFO: first waiter gets the freed device" bash -c '
  qhas() { curl -sf "$CORRAL_URL/api/queue" | grep -q "\"owner\":\"$1\""; }
  wgot() { [ "$(cat "$1" 2>/dev/null)" = "$2" ]; }
  wait_for() { local i; for i in $(seq 1 40); do "$@" && return 0; sleep 0.5; done; return 1; }
  corral free --all >/dev/null 2>&1
  for s in dev1 dev2; do
    r=$(curl -s -X POST "$CORRAL_URL/api/leases" -H "Content-Type: application/json" \
      -d "{\"selector\":{\"serial\":\"$s\"},\"owner\":\"tester\"}")
    case "$r" in
      *"\"serial\":\"$s\""*) ;;
      *) echo "plant $s failed: $r" >&2; exit 1;;
    esac
  done
  (corral wait --timeout 30 >"$TMP/w1.out" 2>&1) &
  (CORRAL_OWNER=second corral wait --serial dev2 --timeout 30 >"$TMP/w2.out" 2>&1) &
  # JVM startup on a Windows host is slower than a fixed 1s sleep allows (F6): poll, do not guess
  wait_for qhas tester || { echo "no tester ticket: $(curl -s "$CORRAL_URL/api/queue")" >&2; exit 1; }
  wait_for qhas second || {
    echo "no second ticket: $(curl -s "$CORRAL_URL/api/queue")" >&2
    echo "w1: $(cat "$TMP/w1.out" 2>&1)" >&2
    echo "w2: $(cat "$TMP/w2.out" 2>&1)" >&2
    exit 1
  }
  corral free dev1 >/dev/null
  wait_for wgot "$TMP/w1.out" dev1 || { echo "w1: $(cat "$TMP/w1.out")" >&2; exit 1; }
  corral free dev2 >/dev/null
  wait_for wgot "$TMP/w2.out" dev2 || { echo "w2: $(cat "$TMP/w2.out")" >&2; exit 1; }
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

# --- Windows .bat line endings ---
# Real cmd.exe misparses a multi-line LF-only .bat, and adb-wrapper.bat/corral.bat use parenthesised
# blocks and `call :label` targets — the constructs that break without CRLF. Wine tolerates LF, so
# this check is the only thing that catches it; .gitattributes forces CRLF on checkout.
# CR-agnostic on purpose (row 33 F6): on MSYS `grep -q $'\r'` cannot match a lone CR, so the old
# check reported a false NEGATIVE there. Count the CR bytes and require one per line instead.
bat_crlf() {
  local f n crs lines
  for f in "$SRC/adb-wrapper.bat" "$SRC/corral.bat"; do
    n=$(tr -cd '\r' < "$f" | wc -c | tr -d ' ')
    lines=$(wc -l < "$f" | tr -d ' ')
    [ "$n" -gt 0 ] || { echo "$f has no CR bytes (LF-only)" >&2; return 1; }
    [ "$n" -eq "$lines" ] || { echo "$f has $n CR bytes for $lines lines (not CRLF throughout)" >&2; return 1; }
  done
}
check "shipped .bat files are CRLF (real cmd.exe misparses LF batch)" bat_crlf

# --- Windows .bat wrapper: resolve the CLI from PATH entries only, never the CWD ---
# where/call also search the CWD, so a planted corral.bat there could both satisfy the guard and
# run as the gate. Two ways to run the batch files (row 33 F6): wine on a POSIX host (installed by
# CI), or a NATIVE cmd.exe on a Windows host (MINGW/MSYS/CYGWIN) — the QA measured those rows at
# seconds there. Wine wins when both exist so CI behaviour is unchanged.
skip_bat_checks() {
  {
    echo
    echo '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'
    echo 'WARNING: no wine and not a native Windows host - the Windows .bat wrapper checks were'
    echo 'SKIPPED, so this run proves NOTHING about adb-wrapper.bat or corral.bat:'
    echo '  win: a planted corral.bat or extensionless corral in the CWD is ignored'
    echo '  win: a planted CLI in the CWD is never executed as the gate'
    echo '  win: corral on PATH is resolved and delegated to, exit code preserved'
    echo '  win: relative, . and empty PATH entries do not satisfy resolution'
    echo '  win: corral.bat ignores a planted java in the CWD and fails closed'
    echo '  win: corral.bat runs the java named by CORRAL_JAVA'
    echo '  win: corral.bat resolves java from an absolute PATH entry'
    echo '  win: a forward-slash absolute PATH entry resolves'
    echo '  win: corral.exe outranks corral.bat, matching cmd PATHEXT'
    echo 'They run under wine (installed by CI, see .github/workflows/ci.yml) or natively on a'
    echo 'Windows host (Git Bash/MSYS/Cygwin with cmd.exe on PATH).'
    echo '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'
    echo
  } >&2
}

WINMODE=missing
if command -v wine >/dev/null 2>&1 && command -v wineboot >/dev/null 2>&1; then
  export WINEPREFIX="$TMP/wineprefix" WINEDEBUG=-all WINEDLLOVERRIDES="mscoree,mshtml="
  if timeout 120 wineboot -i >/dev/null 2>&1 && timeout 60 wine cmd /c "exit" >/dev/null 2>&1; then
    WINMODE=wine
  else
    WINMODE=unusable
  fi
elif command -v cmd.exe >/dev/null 2>&1; then
  case "$(uname -s 2>/dev/null || true)" in
    MINGW*|MSYS*|CYGWIN*) WINMODE=native ;;
  esac
fi

if [ "$WINMODE" = wine ] || [ "$WINMODE" = native ]; then
    if [ "$WINMODE" = wine ]; then
      winpath() { printf 'Z:%s' "${1//\//\\}"; }
      HOST_EXE="$WINEPREFIX/drive_c/windows/system32/hostname.exe"
    else
      winpath() { cygpath -w "$1"; }
      HOST_EXE="$(cygpath -u "${SYSTEMROOT:-C:\\Windows}")/System32/hostname.exe"
    fi
    winpath_fwd() { local p; p=$(winpath "$1"); printf '%s' "${p//\\//}"; }
    # run_launch <dir>: run the launch.bat already written there, in the selected mode.
    run_launch() {
      local d="$1" wd; wd=$(winpath "$d")
      if [ "$WINMODE" = wine ]; then
        ( cd "$d" && timeout 90 wine cmd /c "$wd\\launch.bat" 2>&1 )
      else
        ( cd "$d" && MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1 timeout 90 cmd /c "$wd\\launch.bat" 2>&1 )
      fi
    }
    # These rows plant their own java on PATH. corral.bat deliberately prefers %JAVA_HOME%\bin\java.exe
    # over PATH, so an inherited JAVA_HOME (a CI shell, or a developer exporting JDK 17 so gradle
    # works) would silently replace the fixture under test. Clear it in the generated launcher.
    # CORRAL_JAVA is NOT cleared: the one row that sets it does so deliberately via `extra`.
    # run_bat <dir> <windows PATH> [args...]: run adb-wrapper.bat from <dir> with that PATH
    run_bat() {
      local d="$1" wpath="$2"; shift 2
      local wd; wd=$(winpath "$d")
      # the trailing `exit /b %%errorlevel%%` is explicit on purpose: cmd /c under wine 9 drops the
      # IMPLICIT final errorlevel of a script, so a launch.bat ending in a bare `call` reported 1
      # instead of the delegate's code. Propagate deliberately at every level instead.
      printf '@echo off\r\nset "JAVA_HOME="\r\nset "PATH=%s"\r\ncall "%s\\adb-wrapper.bat" %s\r\nexit /b %%errorlevel%%\r\n' \
        "$wpath" "$wd" "$*" > "$d/launch.bat"
      run_launch "$d"
    }
    # run_corral <dir> <windows PATH> <extra set-line> [args...]: run corral.bat from <dir>
    run_corral() {
      local d="$1" wpath="$2" extra="$3"; shift 3
      local wd; wd=$(winpath "$d")
      printf '@echo off\r\nset "JAVA_HOME="\r\nset "PATH=%s"\r\n%s\r\ncall "%s\\corral.bat" %s\r\nexit /b %%errorlevel%%\r\n' \
        "$wpath" "$extra" "$wd" "$*" > "$d/launch.bat"
      run_launch "$d"
    }
    bat_dir() {
      local d="$TMP/win/$1"; rm -rf "$d"; mkdir -p "$d"
      cp "$SRC/adb-wrapper.bat" "$d/adb-wrapper.bat"; printf '%s' "$d"
    }
    corral_dir() {
      local d="$TMP/win/$1"; rm -rf "$d"; mkdir -p "$d"
      cp "$SRC/corral.bat" "$d/corral.bat"; printf '%s' "$d"
    }

    check_bat_cwd_ignored() {
      local d out
      d=$(bat_dir c6a)
      printf '@echo off\r\necho ran > marker.txt\r\nexit /b 0\r\n' > "$d/corral.bat"
      out=$(run_bat "$d" 'C:\windows\system32' devices)
      case "$out" in *"CLI not on PATH"*) ;; *) echo "corral.bat case: $out" >&2; return 1;; esac
      [ -f "$d/marker.txt" ] && { echo "planted corral.bat ran" >&2; return 1; }
      d=$(bat_dir c6b)
      printf '@echo off\r\necho ran > marker.txt\r\n' > "$d/corral"
      out=$(run_bat "$d" 'C:\windows\system32' devices)
      case "$out" in *"CLI not on PATH"*) ;; *) echo "extensionless case: $out" >&2; return 1;; esac
      [ -f "$d/marker.txt" ] && { echo "planted extensionless corral ran" >&2; return 1; }
      return 0
    }
    check "win: a planted corral.bat or extensionless corral in the CWD is ignored" check_bat_cwd_ignored

    check_bat_not_executed() {
      local d out
      d=$(bat_dir c6mark)
      printf '@echo off\r\necho ran > marker.txt\r\necho PLANTED-SENTINEL\r\nexit /b 0\r\n' > "$d/corral.bat"
      out=$(run_bat "$d" 'C:\windows\system32' devices)
      [ -f "$d/marker.txt" ] && { echo "planted CLI executed" >&2; return 1; }
      case "$out" in *PLANTED-SENTINEL*) echo "planted sentinel leaked: $out" >&2; return 1;; esac
      # anchored: without this the check is pure-negative and would pass if the harness broke and
      # nothing ran at all. Prove the wrapper ran AND decided to fail closed.
      case "$out" in *"CLI not on PATH"*) ;; *) echo "wrapper did not fail closed: $out" >&2; return 1;; esac
      return 0
    }
    check "win: a planted CLI in the CWD is never executed as the gate" check_bat_not_executed

    # the PATH entry has a space (the real install lands under "Program Files") and must resolve
    check_bat_happy() {
      local d out rc
      d=$(bat_dir happy); mkdir -p "$d/real bin"
      printf '@echo off\r\necho REAL-CORRAL gate %%*\r\nexit /b 7\r\n' > "$d/real bin/corral.bat"
      printf '@echo off\r\necho ran > marker.txt\r\nexit /b 0\r\n' > "$d/corral.bat"
      out=$(run_bat "$d" "C:\\windows\\system32;$(winpath "$d/real bin")" devices); rc=$?
      case "$out" in *REAL-CORRAL*) ;; *) echo "$out" >&2; return 1;; esac
      [ "$rc" -eq 7 ] || { echo "exit code $rc != 7" >&2; return 1; }
      [ -f "$d/marker.txt" ] && { echo "planted CLI ran" >&2; return 1; }
      return 0
    }
    check "win: corral on PATH is resolved and delegated to, exit code preserved" check_bat_happy

    check_bat_relative() {
      local d out
      d=$(bat_dir rel); mkdir -p "$d/bin"
      printf '@echo off\r\necho ran > binmarker.txt\r\nexit /b 0\r\n' > "$d/bin/corral.bat"
      printf '@echo off\r\necho ran > cwdmarker.txt\r\nexit /b 0\r\n' > "$d/corral.bat"
      out=$(run_bat "$d" 'C:\windows\system32;bin;.;' devices)
      case "$out" in *"CLI not on PATH"*) ;; *) echo "$out" >&2; return 1;; esac
      [ -f "$d/binmarker.txt" ] && { echo "relative PATH entry resolved" >&2; return 1; }
      [ -f "$d/cwdmarker.txt" ] && { echo "CWD resolved" >&2; return 1; }
      return 0
    }
    check "win: relative, . and empty PATH entries do not satisfy resolution" check_bat_relative

    # corral.bat is the user-facing shim the gate delegates to; a bare `java` there is resolved
    # from the CWD first by cmd, so it must resolve java from PATH entries only (or an override).
    check_corral_planted_java() {
      local d out
      d=$(corral_dir cj-plant)
      printf '@echo off\r\necho RAN > java.marker\r\nexit /b 0\r\n' > "$d/java.bat"
      out=$(run_corral "$d" 'C:\windows\system32;C:\windows' '' claim)
      [ -f "$d/java.marker" ] && { echo "planted java.bat ran" >&2; return 1; }
      case "$out" in *"java not found"*) ;; *) echo "$out" >&2; return 1;; esac
      return 0
    }
    check "win: corral.bat ignores a planted java in the CWD and fails closed" check_corral_planted_java

    check_corral_java_override() {
      local d out rc
      d=$(corral_dir cj-override); mkdir -p "$d/real"
      printf '@echo off\r\necho RAN > override.marker\r\nexit /b 4\r\n' > "$d/real/java.bat"
      out=$(run_corral "$d" 'C:\windows\system32' "set \"CORRAL_JAVA=$(winpath "$d/real/java.bat")\"" claim); rc=$?
      [ -f "$d/override.marker" ] || { echo "CORRAL_JAVA java not run: $out" >&2; return 1; }
      [ "$rc" -eq 4 ] || { echo "rc $rc != 4: $out" >&2; return 1; }
      return 0
    }
    check "win: corral.bat runs the java named by CORRAL_JAVA" check_corral_java_override

    # corral.bat picks the jar by branching on CORRAL_JAR. The java stubs above ignore -cp, so
    # without this the branch is unobservable: dropping either path would pass unnoticed.
    check_corral_jar_branch() {
      local d out binpath custom
      d=$(corral_dir cj-jar); mkdir -p "$d/bin"
      printf '@echo off\r\necho ARGS=%%*\r\nexit /b 0\r\n' > "$d/bin/java.bat"
      binpath=$(winpath "$d/bin"); custom=$(winpath "$d/custom.jar")
      out=$(run_corral "$d" "C:\\windows\\system32;$binpath" "set \"CORRAL_JAR=$custom\"" claim)
      case "$out" in *ARGS=-cp*"$custom"*) ;; *) echo "CORRAL_JAR branch: $out" >&2; return 1;; esac
      # must CLEAR it, not merely omit it: the suite exports CORRAL_JAR for its own jar, and wine
      # inherits the environment, so an omitted set would silently re-take the branch above
      out=$(run_corral "$d" "C:\\windows\\system32;$binpath" 'set "CORRAL_JAR="' claim)
      case "$out" in *ARGS=-cp*corral-daemon-all.jar*) ;; *) echo "default jar branch: $out" >&2; return 1;; esac
      case "$out" in *"$custom"*) echo "default branch used CORRAL_JAR: $out" >&2; return 1;; esac
      return 0
    }
    check "win: corral.bat passes CORRAL_JAR, else the default jar path" check_corral_jar_branch

    check_corral_java_on_path() {
      local d out rc
      d=$(corral_dir cj-path); mkdir -p "$d/bin"
      printf '@echo off\r\necho RAN > abs.marker\r\nexit /b 6\r\n' > "$d/bin/java.bat"
      out=$(run_corral "$d" "C:\\windows\\system32;$(winpath "$d/bin")" '' claim); rc=$?
      [ -f "$d/abs.marker" ] || { echo "PATH java not run: $out" >&2; return 1; }
      [ "$rc" -eq 6 ] || { echo "rc $rc != 6: $out" >&2; return 1; }
      return 0
    }
    check "win: corral.bat resolves java from an absolute PATH entry" check_corral_java_on_path

    # Pin the launcher's JAVA_HOME clear: an inherited value would take corral.bat's JAVA_HOME branch
    # (which tests bin\java.exe specifically) and the fixture planted on PATH would never run.
    check_corral_inherited_javahome() {
      local d jh out rc
      d=$(corral_dir cj-inherit); mkdir -p "$d/bin"
      printf '@echo off\r\necho RAN > path.marker\r\nexit /b 6\r\n' > "$d/bin/java.bat"
      jh="$d/inherited"; mkdir -p "$jh/bin"
      # both names: corral.bat keys on java.exe there, and the java.bat proves the batch stub in
      # JAVA_HOME is never consulted either
      printf '@echo off\r\necho RAN > javahome-bat.marker\r\nexit /b 5\r\n' > "$jh/bin/java.bat"
      cp "$HOST_EXE" "$jh/bin/java.exe"
      out=$(export JAVA_HOME="$(winpath "$jh")"; run_corral "$d" "C:\\windows\\system32;$(winpath "$d/bin")" '' claim); rc=$?
      [ -f "$d/path.marker" ] || { echo "inherited JAVA_HOME outranked the PATH java: $out" >&2; return 1; }
      [ "$rc" -eq 6 ] || { echo "rc $rc != 6: $out" >&2; return 1; }
      [ -f "$jh/javahome-bat.marker" ] && { echo "JAVA_HOME batch java ran" >&2; return 1; }
      return 0
    }
    check "win: an inherited JAVA_HOME cannot outrank the planted java" check_corral_inherited_javahome

    check_bat_forward_slash() {
      local d out rc
      d=$(bat_dir fwd); mkdir -p "$d/bin"
      printf '@echo off\r\necho FWD-CORRAL gate %%*\r\nexit /b 9\r\n' > "$d/bin/corral.bat"
      out=$(run_bat "$d" "C:\\windows\\system32;$(winpath_fwd "$d/bin")" devices); rc=$?
      case "$out" in *FWD-CORRAL*) ;; *) echo "$out" >&2; return 1;; esac
      [ "$rc" -eq 9 ] || { echo "rc $rc != 9: $out" >&2; return 1; }
      return 0
    }
    check "win: a forward-slash absolute PATH entry resolves" check_bat_forward_slash

    check_bat_pathext_order() {
      local d out pe
      d=$(bat_dir ext); mkdir -p "$d/bin"
      pe="$HOST_EXE"
      [ -f "$pe" ] || { echo "no PE to plant at $pe" >&2; return 1; }
      cp "$pe" "$d/bin/corral.exe"
      printf '@echo off\r\necho BAT-WON > bat.marker\r\nexit /b 0\r\n' > "$d/bin/corral.bat"
      out=$(run_bat "$d" "C:\\windows\\system32;$(winpath "$d/bin")" devices)
      [ -f "$d/bat.marker" ] && { echo "corral.bat outranked corral.exe" >&2; return 1; }
      case "$out" in *"CLI not on PATH"*) echo "did not pick corral.exe: $out" >&2; return 1;; esac
      return 0
    }
    check "win: corral.exe outranks corral.bat, matching cmd PATHEXT" check_bat_pathext_order
elif [ "$WINMODE" = unusable ]; then
  skip_bat_checks
  # These checks exist because CI silently skipped them in a green build. Under CI a skip must
  # FAIL, or a wineboot flake on a cold runner reproduces the original hole. Locally it stays a
  # warning so the suite still runs on a machine without wine.
  [ -n "${CI:-}" ] && check "win: .bat wrapper checks actually RAN (wine present but unusable)" false
else
  skip_bat_checks
  [ -n "${CI:-}" ] && check "win: .bat wrapper checks actually RAN (no wine and not a Windows host)" false
fi

# --- report ---
printf '\n%d passed, %d failed\n' "$pass" "$fail"
exit "$fail"
