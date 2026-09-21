#!/usr/bin/env bash
# corral install/uninstall sandbox suite (L3): everything runs under a temp HOME with fake
# java/adb/systemctl, so the real host (~/.corral, ~/.gradle, ~/bin, SDK, systemd) is never touched.
# Runs from the source tree (builds the jar) or from the deployed bundle (jar beside this script).
set -u

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -f "$SELF/corral-daemon-all.jar" ] && [ -f "$SELF/install.sh" ]; then
  SRC="$SELF"; ROOT=""; JAR="$SELF/corral-daemon-all.jar"; BUILD=0
else
  SRC="$SELF"; ROOT=$(cd "$SELF/.." && pwd); JAR="$ROOT/corral-daemon/build/libs/corral-daemon-all.jar"; BUILD=1
fi

pass=0; fail=0
check() { # check <name> <cmd...>
  local name="$1"; shift
  if "$@"; then printf 'ok %d %s\n' "$((++pass))" "$name"
  else printf 'FAIL %d %s\n' "$((++fail))" "$name"; fi
}

# Build before touching HOME — gradle needs the real ~/.gradle cache.
if [ "$BUILD" = 1 ]; then
  (cd "$ROOT" && ./gradlew :corral-daemon:shadowJar >/dev/null 2>&1) || { echo "build failed"; exit 1; }
fi
[ -f "$JAR" ] || { echo "jar not found: $JAR (build it or run beside corral-daemon-all.jar)"; exit 1; }

REAL_HOME="${HOME:-}"
TMP=$(mktemp -d)
TMPHOME="$TMP/home"
mkdir -p "$TMPHOME"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

# --- guard rails: every path we assert on is under the temp HOME, never the real one ---
case "$TMPHOME" in
  "$REAL_HOME"|"$REAL_HOME"/*) echo "refusing: sandbox HOME is inside the real HOME" >&2; exit 1;;
esac
[ "$TMPHOME" != "$REAL_HOME" ] || { echo "refusing: sandbox HOME equals real HOME" >&2; exit 1; }

# --- fakes: java 17, SDK adb, systemctl stub (PATH-first) ---
STUB="$TMP/stub"
SDK="$TMPHOME/Android/Sdk/platform-tools"
FAKE_ADB="$SDK/adb"
FAKE_JAVA="$STUB/java"
mkdir -p "$STUB" "$SDK" "$TMPHOME/bin"
printf '%s\n' '#!/bin/sh' 'echo "fake-sdk-adb $@"' > "$FAKE_ADB"
printf '%s\n' '#!/bin/sh' 'echo "openjdk version \"17.0.12\" 2024-07-16" >&2' 'exit 0' > "$FAKE_JAVA"
printf '%s\n' '#!/bin/sh' "echo \"systemctl \$*\" >> \"$TMP/systemctl.log\"" 'exit 0' > "$STUB/systemctl"
chmod +x "$FAKE_ADB" "$FAKE_JAVA" "$STUB/systemctl"
: > "$TMPHOME/.bashrc"

# Sandbox the child env: HOME is both exported and passed via --home (belt and braces).
export HOME="$TMPHOME"
unset ANDROID_HOME ANDROID_SDK_ROOT JAVA_HOME
export PATH="$STUB:$PATH"

install_run() {
  bash "$SRC/install.sh" --java "$FAKE_JAVA" --adb "$FAKE_ADB" --jar "$JAR" --home "$TMPHOME"
}
uninstall_run() { bash "$SRC/install.sh" --home "$TMPHOME" --uninstall; }

dump_on_fail() { [ "$1" -eq 0 ] || { echo "--- log ---"; sed -n '1,80p' "$2"; }; }

UNIT="$TMPHOME/.config/systemd/user/corral.service"
RULES="$TMPHOME/.config/corral/rules"
GATE="$TMPHOME/.gradle/init.d/corral-gate.gradle.kts"

# ===== first install =====
install_run >"$TMP/install1.log" 2>&1; rc=$?
dump_on_fail "$rc" "$TMP/install1.log"
check "9.0 install exits 0" test "$rc" -eq 0

# 9.1 unit file
check "9.1 unit file installed" test -f "$UNIT"
check "9.1 unit uses the fake java" grep -qF "$FAKE_JAVA" "$UNIT"
check "9.1 unit has no sdkman path" bash -c "! grep -q sdkman '$UNIT'"

# 9.2 PATH shims
check "9.2 ~/bin/adb installed + executable" test -x "$TMPHOME/bin/adb"
check "9.2 ~/bin/corral installed + executable" test -x "$TMPHOME/bin/corral"
check "9.2 ~/bin/adb is the corral wrapper" grep -q 'corral adb wrapper' "$TMPHOME/bin/adb"

# 9.4 SDK shim
check "9.4 SDK adb is now the corral wrapper" grep -q 'corral adb wrapper' "$SDK/adb"
check "9.4 SDK adb.real keeps the original binary" grep -q 'fake-sdk-adb' "$SDK/adb.real"
check "9.4 .bashrc exports CORRAL_REAL_ADB to adb.real" grep -q 'CORRAL_REAL_ADB.*adb\.real' "$TMPHOME/.bashrc"

# 9.5 gradle gate
check "9.5 gradle gate installed" test -f "$GATE"
check "9.5 gradle-tasks installed" test -f "$TMPHOME/.config/corral/gradle-tasks"
check "9.5 gate placeholder substituted" bash -c "! grep -q '@CORRAL_SHIM@' '$GATE'"
check "9.5 gate points at the sandbox corral shim" grep -qF "$TMPHOME/bin/corral" "$GATE"

# 9.6 rules installed only when absent
check "9.6 rules installed" test -f "$RULES"
printf '\n# sandbox sentinel edit\n' >> "$RULES"

# ===== 9.3 idempotence: second install =====
install_run >"$TMP/install2.log" 2>&1; rc2=$?
dump_on_fail "$rc2" "$TMP/install2.log"
check "9.3 re-run install exits 0" test "$rc2" -eq 0
check "9.6 user rules edit survives re-run" grep -q 'sandbox sentinel edit' "$RULES"
check "9.3 artifacts intact after re-run" bash -c \
  "[ -f '$UNIT' ] && [ -x '$TMPHOME/bin/adb' ] && grep -q 'corral adb wrapper' '$TMPHOME/bin/adb' && [ -f '$SDK/adb.real' ]"

# 9.7/9.8 uninstall
uninstall_run >"$TMP/uninstall.log" 2>&1; rcu=$?
dump_on_fail "$rcu" "$TMP/uninstall.log"
check "9.7 uninstall exits 0" test "$rcu" -eq 0
check "9.7 unit file removed" test ! -e "$UNIT"
check "9.7 ~/bin/adb removed" test ! -e "$TMPHOME/bin/adb"
check "9.7 ~/bin/corral removed" test ! -e "$TMPHOME/bin/corral"
check "9.7 ~/.corral removed" test ! -e "$TMPHOME/.corral"
check "9.7 gradle gate removed" test ! -e "$GATE"
check "9.7 gradle-tasks removed" test ! -e "$TMPHOME/.config/corral/gradle-tasks"
check "9.7 rules removed" test ! -e "$RULES"
check "9.8 SDK adb restored from adb.real" grep -q 'fake-sdk-adb' "$SDK/adb"
check "9.8 adb.real consumed" test ! -e "$SDK/adb.real"

# ===== --home differs from the exported HOME: rc line must use the absolute target =====
# This block proves the --home override wins over $HOME without betting the reviewer's dotfiles:
# the "real" HOME here is a scratch dir (CORRAL_TEST_HOME to reuse an existing one), so the test
# asserts on a throwaway .bashrc. Pass CORRAL_TEST_HOME= (empty) to point it at the real HOME —
# only for a deliberate manual run.
TMPHOME2="$TMP/home2"
REALHOME2="${CORRAL_TEST_HOME:-$TMP/realhome}"
SDK2="$TMPHOME2/Android/Sdk/platform-tools"
FAKE_ADB2="$SDK2/adb"
mkdir -p "$SDK2" "$TMPHOME2/bin" "$REALHOME2"
printf '%s\n' '#!/bin/sh' 'echo "fake-sdk-adb2 $@"' > "$FAKE_ADB2"
chmod +x "$FAKE_ADB2"
: > "$TMPHOME2/.bashrc"
: > "$REALHOME2/.bashrc"
REAL_BASHRC="$REALHOME2/.bashrc"
real_bashrc_before=$(sha256sum "$REAL_BASHRC" 2>/dev/null || echo missing)

HOME="$REALHOME2" bash "$SRC/install.sh" --java "$FAKE_JAVA" --adb "$FAKE_ADB2" \
  --jar "$JAR" --home "$TMPHOME2" >"$TMP/install-override.log" 2>&1
rco=$?
dump_on_fail "$rco" "$TMP/install-override.log"
check "install with --home != HOME exits 0" test "$rco" -eq 0
check "rc PATH line uses the absolute --home target" \
  grep -qF "export PATH=\"$TMPHOME2/bin:" "$TMPHOME2/.bashrc"
check "the differed HOME's .bashrc is untouched by --home override" test "$real_bashrc_before" = "$(sha256sum "$REAL_BASHRC" 2>/dev/null || echo missing)"

# the PATH stub proved the real systemd session was never addressed
check "systemctl stub handled daemon-reload (real session untouched)" test -s "$TMP/systemctl.log"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
exit "$fail"
