#!/usr/bin/env bash
# corral install/uninstall sandbox suite (L3): everything runs under a temp HOME with fake
# java/adb/systemctl, so the real host (~/.corral, ~/.gradle, ~/bin, SDK, systemd) is never touched.
# Runs from the source tree (builds the jar) or from the deployed bundle (jar beside this script).
set -u

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [ -f "$SELF/corral-daemon-all.jar" ] && [ -f "$SELF/install.sh" ]; then
  SRC="$SELF"; ROOT=""; JAR="$SELF/corral-daemon-all.jar"; UI_JAR="$SELF/corral-ui-all.jar"; BUILD=0
else
  SRC="$SELF"; ROOT=$(cd "$SELF/.." && pwd); JAR="$ROOT/corral-daemon/build/libs/corral-daemon-all.jar"; UI_JAR="$ROOT/corral-ui/build/libs/corral-ui-all.jar"; BUILD=1
fi

pass=0; fail=0
check() { # check <name> <cmd...>
  local name="$1"; shift
  if "$@"; then printf 'ok %d %s\n' "$((++pass))" "$name"
  else printf 'FAIL %d %s\n' "$((++fail))" "$name"; fi
}

# Build before touching HOME — gradle needs the real ~/.gradle cache.
if [ "$BUILD" = 1 ]; then
  (cd "$ROOT" && ./gradlew :corral-daemon:shadowJar :corral-ui:shadowJar >/dev/null 2>&1) || { echo "build failed"; exit 1; }
fi
[ -f "$JAR" ] || { echo "jar not found: $JAR (build it or run beside corral-daemon-all.jar)"; exit 1; }
[ -f "$UI_JAR" ] || { echo "ui jar not found: $UI_JAR (build it or run beside corral-ui-all.jar)"; exit 1; }

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
DAEMON_ENV="$TMPHOME/.config/corral/daemon.env"
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

# 9.9 optional EnvironmentFile: the generated unit carries it; daemon.env installed absent-only
check "9.9 unit carries optional EnvironmentFile line" grep -q '^EnvironmentFile=-.*daemon.env' "$UNIT"
check "9.9 daemon.env created on install" test -f "$DAEMON_ENV"
printf '\n# sandbox daemon.env sentinel edit\n' >> "$DAEMON_ENV"

# ===== 9.3 idempotence: second install =====
install_run >"$TMP/install2.log" 2>&1; rc2=$?
dump_on_fail "$rc2" "$TMP/install2.log"
check "9.3 re-run install exits 0" test "$rc2" -eq 0
check "9.6 user rules edit survives re-run" grep -q 'sandbox sentinel edit' "$RULES"
check "9.9 daemon.env edit survives re-run" grep -q 'sandbox daemon.env sentinel edit' "$DAEMON_ENV"
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
check "9.7 daemon.env removed" test ! -e "$DAEMON_ENV"
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

# ===== UI component: --ui/--no-ui, observer seeding, absent-only edits, bundle default =====
TMPHOME3="$TMP/home3"
SDK3="$TMPHOME3/Android/Sdk/platform-tools"
FAKE_ADB3="$SDK3/adb"
mkdir -p "$SDK3" "$TMPHOME3/bin"
printf '%s\n' '#!/bin/sh' 'echo "fake-sdk-adb3 $@"' > "$FAKE_ADB3"
chmod +x "$FAKE_ADB3"
: > "$TMPHOME3/.bashrc"
UNIT3="$TMPHOME3/.config/systemd/user/corral.service"
DESKTOP3="$TMPHOME3/.local/share/applications/corral-ui.desktop"
export CORRAL_UI_JAR="$UI_JAR"

ui_run() { bash "$SRC/install.sh" --java "$FAKE_JAVA" --adb "$FAKE_ADB3" --jar "$JAR" --home "$TMPHOME3" "$@"; }

# exactly one observer Environment= line with <value>, and it sits in [Service] (before [Install])
obs_is() {
  [ "$(grep -c '^Environment=CORRAL_SCREENSHOT_OBSERVERS=' "$1")" = 1 ] || return 1
  grep -q "^Environment=CORRAL_SCREENSHOT_OBSERVERS=$2\$" "$1" || return 1
  awk '/^\[Install\]/ { exit } /^Environment=CORRAL_SCREENSHOT_OBSERVERS=/ { found=1 } END { exit !found }' "$1"
}

# systemd ignores Environment= outside [Service]; catch that when the tool is available
verify_unit() {
  command -v systemd-analyze >/dev/null 2>&1 || return 0
  ! systemd-analyze verify "$1" 2>&1 | grep -qiE 'unknown key|invalid'
}

# a malformed .desktop silently disappears from the app grid; validate when the tool is available
verify_desktop() {
  command -v desktop-file-validate >/dev/null 2>&1 || return 0
  desktop-file-validate "$1"
}

ui_run --no-ui >"$TMP/ui-no.log" 2>&1; rcui=$?
dump_on_fail "$rcui" "$TMP/ui-no.log"
check "--no-ui installs neither" bash -c \
  "[ ! -e '$TMPHOME3/.corral/corral-ui-all.jar' ] && [ ! -e '$TMPHOME3/bin/corral-ui' ] && ! grep -q 'CORRAL_SCREENSHOT_OBSERVERS' '$UNIT3'"
check "--no-ui installs no launcher" test ! -e "$DESKTOP3"

# explicit --ui with a missing jar must fail before touching anything
env CORRAL_UI_JAR=/no/such.jar bash "$SRC/install.sh" --java "$FAKE_JAVA" --adb "$FAKE_ADB3" \
  --jar "$JAR" --home "$TMPHOME3" --ui >"$TMP/ui-missing.log" 2>&1; rcm=$?
check "--ui with a missing jar exits non-zero" test "$rcm" -ne 0
check "--ui with a missing jar installs neither" bash -c \
  "[ ! -e '$TMPHOME3/.corral/corral-ui-all.jar' ] && [ ! -e '$TMPHOME3/bin/corral-ui' ] && ! grep -q 'CORRAL_SCREENSHOT_OBSERVERS' '$UNIT3'"

ui_run --ui >"$TMP/ui-yes.log" 2>&1; rcui=$?
dump_on_fail "$rcui" "$TMP/ui-yes.log"
check "ui jar + shim installed with --ui" bash -c \
  "[ -f '$TMPHOME3/.corral/corral-ui-all.jar' ] && [ -x '$TMPHOME3/bin/corral-ui' ]"
check "observer line seeded in [Service] when ui installs" obs_is "$UNIT3" corral-ui
check "generated unit passes systemd-analyze verify" verify_unit "$UNIT3"
check "Corral launcher installed with --ui" bash -c \
  "[ -f '$DESKTOP3' ] && grep -q '^Name=Corral' '$DESKTOP3' && grep -q '^StartupWMClass=dev-corral-ui-CorralUiKt' '$DESKTOP3' && grep -q 'corral-ui-all.jar' '$DESKTOP3'"
check "launcher icon installed and referenced" bash -c \
  "[ -f '$TMPHOME3/.local/share/icons/corral.png' ] && grep -qF 'Icon=$TMPHOME3/.local/share/icons/corral.png' '$DESKTOP3'"
check "launcher passes desktop-file-validate" verify_desktop "$DESKTOP3"

# simulate a user edit, then reinstall --ui: the edit must survive verbatim, once, in [Service]
grep -v '^Environment=CORRAL_SCREENSHOT_OBSERVERS=' "$UNIT3" > "$UNIT3.tmp"; mv "$UNIT3.tmp" "$UNIT3"
printf 'Environment=CORRAL_SCREENSHOT_OBSERVERS=custom-name\n' >> "$UNIT3"
ui_run --ui >"$TMP/ui-custom.log" 2>&1; rcui=$?
dump_on_fail "$rcui" "$TMP/ui-custom.log"
check "existing custom observer edit survives --ui reinstall" obs_is "$UNIT3" custom-name

# an emptied value means "observers off": it is present, so it must survive rather than re-seed
grep -v '^Environment=CORRAL_SCREENSHOT_OBSERVERS=' "$UNIT3" > "$UNIT3.tmp"; mv "$UNIT3.tmp" "$UNIT3"
printf 'Environment=CORRAL_SCREENSHOT_OBSERVERS=\n' >> "$UNIT3"
ui_run --ui >"$TMP/ui-empty.log" 2>&1; rcui=$?
dump_on_fail "$rcui" "$TMP/ui-empty.log"
check "emptied observer line survives --ui reinstall" obs_is "$UNIT3" ""

# --no-ui carries the edit too, and does not remove already-installed artifacts (that's --uninstall)
ui_run --no-ui >"$TMP/ui-carry.log" 2>&1; rcui=$?
dump_on_fail "$rcui" "$TMP/ui-carry.log"
check "custom observer edit survives --no-ui reinstall" obs_is "$UNIT3" ""
check "--no-ui leaves already-installed ui artifacts alone" bash -c \
  "[ -f '$TMPHOME3/.corral/corral-ui-all.jar' ] && [ -x '$TMPHOME3/bin/corral-ui' ]"

mkdir -p "$TMPHOME3/.config/corral"
printf '{}\n' > "$TMPHOME3/.config/corral/ui.conf"
bash "$SRC/install.sh" --home "$TMPHOME3" --uninstall >"$TMP/ui-uninstall.log" 2>&1; rcui=$?
dump_on_fail "$rcui" "$TMP/ui-uninstall.log"
check "uninstall removes ui artifacts" bash -c \
  "[ ! -e '$TMPHOME3/.corral/corral-ui-all.jar' ] && [ ! -e '$TMPHOME3/bin/corral-ui' ]"
check "uninstall removes the launcher" test ! -e "$DESKTOP3"
check "uninstall removes the launcher icon" test ! -e "$TMPHOME3/.local/share/icons/corral.png"
check "uninstall removes client prefs ui.conf" test ! -e "$TMPHOME3/.config/corral/ui.conf"

# no CORRAL_UI_JAR and no jar beside install.sh: --ui must fall back to the source-tree build
if [ "$BUILD" = 1 ]; then
  env -u CORRAL_UI_JAR bash "$SRC/install.sh" --java "$FAKE_JAVA" --adb "$FAKE_ADB3" --jar "$JAR" \
    --home "$TMPHOME3" --ui >"$TMP/ui-fallback.log" 2>&1; rcf=$?
  dump_on_fail "$rcf" "$TMP/ui-fallback.log"
  check "source-tree --ui falls back to the build jar" bash -c \
    "[ -f '$TMPHOME3/.corral/corral-ui-all.jar' ] && [ -x '$TMPHOME3/bin/corral-ui' ]"
fi

# bundle simulation: install.sh + both jars in one dir; default auto must pick the ui jar up
BUNDLE3="$TMP/bundle3"; TMPHOME4="$TMP/home4"; SDK4="$TMPHOME4/Android/Sdk/platform-tools"; FAKE_ADB4="$SDK4/adb"
mkdir -p "$BUNDLE3" "$SDK4" "$TMPHOME4/bin"
cp "$SRC/install.sh" "$SRC/adb-wrapper" "$SRC/corral" "$SRC/corral-ui" "$SRC/corral.service" \
   "$SRC/corral-gate.gradle.kts" "$SRC/corral-rules" "$SRC/corral.png" "$BUNDLE3/"
cp "$JAR" "$BUNDLE3/corral-daemon-all.jar"; cp "$UI_JAR" "$BUNDLE3/corral-ui-all.jar"
chmod +x "$BUNDLE3/corral-ui" "$BUNDLE3/adb-wrapper" "$BUNDLE3/corral"
printf '%s\n' '#!/bin/sh' 'echo "fake-sdk-adb4 $@"' > "$FAKE_ADB4"
chmod +x "$FAKE_ADB4"
: > "$TMPHOME4/.bashrc"
env -u CORRAL_UI_JAR HOME="$TMPHOME" bash "$BUNDLE3/install.sh" --java "$FAKE_JAVA" --adb "$FAKE_ADB4" \
  --jar "$BUNDLE3/corral-daemon-all.jar" --home "$TMPHOME4" >"$TMP/bundle-ui.log" 2>&1; rcb=$?
dump_on_fail "$rcb" "$TMP/bundle-ui.log"
check "bundle-mode default installs ui when jar present" bash -c \
  "[ -f '$TMPHOME4/.corral/corral-ui-all.jar' ] && [ -x '$TMPHOME4/bin/corral-ui' ]"
check "bundle-mode observer seeded in [Service]" obs_is "$TMPHOME4/.config/systemd/user/corral.service" corral-ui
check "bundle-mode installs the launcher too" test -f "$TMPHOME4/.local/share/applications/corral-ui.desktop"

# a changed bundle png must refresh the installed icon on upgrade (the sidecar marks ours as pristine)
printf 'changed-icon\n' > "$BUNDLE3/corral.png"
env -u CORRAL_UI_JAR HOME="$TMPHOME" bash "$BUNDLE3/install.sh" --java "$FAKE_JAVA" --adb "$FAKE_ADB4" \
  --jar "$BUNDLE3/corral-daemon-all.jar" --home "$TMPHOME4" >"$TMP/bundle-refresh.log" 2>&1; rcr=$?
dump_on_fail "$rcr" "$TMP/bundle-refresh.log"
check "reinstall refreshes the installed icon when the bundle png changes" \
  cmp -s "$BUNDLE3/corral.png" "$TMPHOME4/.local/share/icons/corral.png"

# a user-replaced icon must survive uninstall (absent-only install promises never to clobber it)
printf 'user-icon\n' > "$TMPHOME4/.local/share/icons/corral.png"
env -u CORRAL_UI_JAR bash "$BUNDLE3/install.sh" --home "$TMPHOME4" --uninstall >"$TMP/bundle-uninstall.log" 2>&1; rcbu=$?
dump_on_fail "$rcbu" "$TMP/bundle-uninstall.log"
check "uninstall keeps a user-replaced icon" grep -q 'user-icon' "$TMPHOME4/.local/share/icons/corral.png"

# a pre-sidecar install whose icon already equals the bundle's: install adopts it (writes the
# sidecar), so uninstall then owns it and removes it
TMPHOME5="$TMP/home5"; mkdir -p "$TMPHOME5/.local/share/icons"
printf 'changed-icon\n' > "$TMPHOME5/.local/share/icons/corral.png"
env -u CORRAL_UI_JAR HOME="$TMPHOME" bash "$BUNDLE3/install.sh" --java "$FAKE_JAVA" --adb "$FAKE_ADB4" \
  --jar "$BUNDLE3/corral-daemon-all.jar" --home "$TMPHOME5" >"$TMP/bundle-adopt.log" 2>&1; rca=$?
dump_on_fail "$rca" "$TMP/bundle-adopt.log"
check "install adopts a pre-sidecar icon equal to the bundle (writes the sidecar)" \
  test -f "$TMPHOME5/.local/share/icons/corral.png.sha"
env -u CORRAL_UI_JAR bash "$BUNDLE3/install.sh" --home "$TMPHOME5" --uninstall >"$TMP/bundle-adopt-uninstall.log" 2>&1; rcau=$?
dump_on_fail "$rcau" "$TMP/bundle-adopt-uninstall.log"
check "uninstall removes an adopted icon" test ! -e "$TMPHOME5/.local/share/icons/corral.png"

# ===== --home isolation: adb resolution must never reach outside the install root =====
# A decoy adb OUTSIDE every sandbox stands in for the user's real ~/bin/adb. Under --home the
# installer must only use an SDK inside the root (or an explicit --adb); it must never fall back
# to this decoy, because install_sdk_shim overwrites whichever adb it resolves.
DECDIR="$TMP/ambient-bin"; DECOY="$DECDIR/adb"
mkdir -p "$DECDIR"
printf '%s\n' '#!/bin/sh' 'echo "decoy-adb $@"' > "$DECOY"; chmod +x "$DECOY"
DECOY_SHA=$(sha256sum "$DECOY" | cut -d' ' -f1)

# isolate_home <home> [args...]: run the installer with --home and the ambient decoy first on PATH.
isolate_home() {
  local h="$1"; shift
  env HOME="$TMPHOME" PATH="$DECDIR:$STUB:$PATH" \
    bash "$SRC/install.sh" --java "$FAKE_JAVA" --jar "$JAR" --no-ui --home "$h" "$@"
}

# 1 + 4: a sandbox with its own SDK, decoy also on PATH -> decoy untouched, in-root SDK used/shimmed.
TMPHOME6="$TMP/home6"; SB6_SDK="$TMPHOME6/Android/Sdk/platform-tools"
mkdir -p "$SB6_SDK" "$TMPHOME6/bin"
printf '%s\n' '#!/bin/sh' 'echo "sandbox-adb6 $@"' > "$SB6_SDK/adb"; chmod +x "$SB6_SDK/adb"
: > "$TMPHOME6/.bashrc"
isolate_home "$TMPHOME6" >"$TMP/iso-sdk.log" 2>&1; rc6=$?
dump_on_fail "$rc6" "$TMP/iso-sdk.log"
check "sandboxed install with --home does not write outside the sandbox" bash -c \
  "[ '$rc6' -eq 0 ] && [ -x '$TMPHOME6/bin/adb' ] && grep -q 'corral adb wrapper' '$TMPHOME6/bin/adb' && grep -q 'corral adb wrapper' '$SB6_SDK/adb' && grep -q 'sandbox-adb6' '$SB6_SDK/adb.real' && [ \"\$(sha256sum '$DECOY' | cut -d' ' -f1)\" = '$DECOY_SHA' ] && [ ! -e '$DECOY.real' ]"
check "sandboxed install uses an SDK inside the sandbox" grep -qF "Using adb: $SB6_SDK/adb" "$TMP/iso-sdk.log"

# 2: an empty sandbox with only the out-of-sandbox decoy available must be a loud, early refusal.
TMPHOME7="$TMP/home7"; mkdir -p "$TMPHOME7/bin"; : > "$TMPHOME7/.bashrc"
isolate_home "$TMPHOME7" >"$TMP/iso-refuse.log" 2>&1; rc7=$?
check "sandboxed install refuses an out-of-sandbox adb" bash -c \
  "[ '$rc7' -ne 0 ] && grep -q -- '--adb' '$TMP/iso-refuse.log' && [ \"\$(sha256sum '$DECOY' | cut -d' ' -f1)\" = '$DECOY_SHA' ] && [ ! -e '$DECOY.real' ] && [ ! -e '$TMPHOME7/.corral' ] && [ ! -e '$TMPHOME7/bin/adb' ]"

# 3: an explicit --adb inside the sandbox is honoured.
TMPHOME8="$TMP/home8"; SB8_TOOL="$TMPHOME8/tools"
mkdir -p "$SB8_TOOL" "$TMPHOME8/bin"
printf '%s\n' '#!/bin/sh' 'echo "explicit-adb8 $@"' > "$SB8_TOOL/adb"; chmod +x "$SB8_TOOL/adb"
isolate_home "$TMPHOME8" --adb "$SB8_TOOL/adb" >"$TMP/iso-explicit.log" 2>&1; rc8=$?
dump_on_fail "$rc8" "$TMP/iso-explicit.log"
check "sandboxed install honours an explicit --adb" bash -c \
  "[ '$rc8' -eq 0 ] && [ -x '$TMPHOME8/bin/adb' ] && grep -q 'explicit-adb8' '$SB8_TOOL/adb.real' && [ \"\$(sha256sum '$DECOY' | cut -d' ' -f1)\" = '$DECOY_SHA' ]"

# 5: regression guard — without --home, adb still resolves from PATH exactly as before and is shimmed.
TMPHOME9="$TMP/home9"; PATHDIR="$TMP/path-bin"; mkdir -p "$TMPHOME9" "$PATHDIR"
printf '%s\n' '#!/bin/sh' 'echo "path-adb9 $@"' > "$PATHDIR/adb"; chmod +x "$PATHDIR/adb"
: > "$TMPHOME9/.bashrc"
env HOME="$TMPHOME9" PATH="$PATHDIR:$STUB:$PATH" bash "$SRC/install.sh" \
  --java "$FAKE_JAVA" --jar "$JAR" --no-ui >"$TMP/iso-nohome.log" 2>&1; rc9=$?
dump_on_fail "$rc9" "$TMP/iso-nohome.log"
check "a normal install still resolves adb from PATH as before" bash -c \
  "[ '$rc9' -eq 0 ] && grep -qF 'Using adb: $PATHDIR/adb' '$TMP/iso-nohome.log' && grep -q 'path-adb9' '$PATHDIR/adb.real'"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
exit "$fail"
