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
# Pin the platform to POSIX for the whole suite so a run on a Windows host still exercises the
# Linux path; the Windows section below overrides this with its own MINGW `uname` first on PATH.
printf '%s\n' '#!/bin/sh' 'echo "Linux"' > "$STUB/uname"
chmod +x "$FAKE_ADB" "$FAKE_JAVA" "$STUB/systemctl" "$STUB/uname"
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

# ===== closing protection-state claim: report what is TRUE after the attempt =====
# The installer must never say "shim is active" unless the adb it resolved really is the wrapper.
# A sandbox run that needs no systemd/systemctl: --home keeps everything under the temp root.
shim_sandbox() { # shim_sandbox <home> [args...]
  local h="$1"; shift
  env HOME="$TMPHOME" PATH="$STUB:$PATH" \
    bash "$SRC/install.sh" --java "$FAKE_JAVA" --jar "$JAR" --no-ui --home "$h" "$@"
}

# 1: no adb resolvable at all. The old code requested SHIM=1, printed the warning, shimmed nothing,
# then still claimed the shim was active. Reproduce faithfully: no --home (the --home sandbox refuses
# a missing adb before the closing message), default shim, and a PATH of only the tools install.sh needs
# so the host's own /usr/bin/adb cannot leak in.
CORETOOLS="$TMP/coretools"; mkdir -p "$CORETOOLS"
# Wrapper scripts, NOT symlinks: an MSYS symlink to /usr/bin/<tool> makes the child resolve that
# POSIX path as its own image and fail to find its runtime DLLs on a real Windows host ("error while
# loading shared libraries: ?") — the tool never runs, so the row failed for a reason that has
# nothing to do with install.sh. A one-line exec wrapper behaves identically on Linux and MSYS.
for t in dirname sed head awk grep cp chmod mkdir cut sha256sum readlink mv rm cat; do
  p=$(command -v "$t") && printf '#!/bin/sh\nexec "%s" "$@"\n' "$p" > "$CORETOOLS/$t" && chmod +x "$CORETOOLS/$t"
done
BASH_BIN=$(command -v bash)
TMPHOME10="$TMP/home10"; mkdir -p "$TMPHOME10/bin"; : > "$TMPHOME10/.bashrc"
env HOME="$TMPHOME10" PATH="$CORETOOLS:$STUB" \
  "$BASH_BIN" "$SRC/install.sh" --java "$FAKE_JAVA" --jar "$JAR" --no-ui >"$TMP/claim-noadb.log" 2>&1; rc10=$?
dump_on_fail "$rc10" "$TMP/claim-noadb.log"
check "no adb found -> does NOT claim the shim is active, and says absolute-path calls are not gated" \
  bash -c "[ '$rc10' -eq 0 ] && ! grep -qi 'shim is active' '$TMP/claim-noadb.log' && grep -qi 'absolute-path adb calls are NOT lease-gated' '$TMP/claim-noadb.log'"

# 2: a real adb inside the root is shimmed -> the claim is made, and scoped to the SDK path.
TMPHOME11="$TMP/home11"; SDK11="$TMPHOME11/Android/Sdk/platform-tools"
mkdir -p "$SDK11" "$TMPHOME11/bin"; : > "$TMPHOME11/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "sandbox-adb11 $@"' > "$SDK11/adb"; chmod +x "$SDK11/adb"
shim_sandbox "$TMPHOME11" >"$TMP/claim-active.log" 2>&1; rc11=$?
dump_on_fail "$rc11" "$TMP/claim-active.log"
check "shim actually installed -> claims active, scoped to the SDK platform-tools path" bash -c \
  "[ '$rc11' -eq 0 ] && grep -qi 'shim is active' '$TMP/claim-active.log' && grep -q 'corral adb wrapper' '$SDK11/adb' && grep -qi 'platform-tools path is lease-gated' '$TMP/claim-active.log'"

# 3: a shim left by a previous install (wrapper already at the adb path) still counts as active.
TMPHOME12="$TMP/home12"; SDK12="$TMPHOME12/Android/Sdk/platform-tools"
mkdir -p "$SDK12" "$TMPHOME12/bin"; : > "$TMPHOME12/.bashrc"
cp "$SRC/adb-wrapper" "$SDK12/adb"; chmod +x "$SDK12/adb"
printf '%s\n' '#!/bin/sh' 'echo "previous-real-adb12 $@"' > "$SDK12/adb.real"; chmod +x "$SDK12/adb.real"
shim_sandbox "$TMPHOME12" >"$TMP/claim-rerun.log" 2>&1; rc12=$?
dump_on_fail "$rc12" "$TMP/claim-rerun.log"
check "a shim left by a previous install still counts as active" bash -c \
  "[ '$rc12' -eq 0 ] && grep -qi 'shim is active' '$TMP/claim-rerun.log' && grep -q 'previous-real-adb12' '$SDK12/adb.real'"

# 4: --no-sdk-shim opts out -> never claims active, even with a real adb in the root.
TMPHOME13="$TMP/home13"; SDK13="$TMPHOME13/Android/Sdk/platform-tools"
mkdir -p "$SDK13" "$TMPHOME13/bin"; : > "$TMPHOME13/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "sandbox-adb13 $@"' > "$SDK13/adb"; chmod +x "$SDK13/adb"
shim_sandbox "$TMPHOME13" --no-sdk-shim >"$TMP/claim-noshim.log" 2>&1; rc13=$?
dump_on_fail "$rc13" "$TMP/claim-noshim.log"
check "--no-sdk-shim -> never claims active" bash -c \
  "[ '$rc13' -eq 0 ] && ! grep -qi 'shim is active' '$TMP/claim-noshim.log'"

# 5: an incomplete bundle must be refused BEFORE any mutation: the adb-wrapper pre-check runs before
# the shim stash, so the real adb at the SDK path is never even moved aside.
BROKEN="$TMP/broken-bundle"; mkdir -p "$BROKEN"
cp "$SRC/install.sh" "$BROKEN/install.sh"
TMPHOME14="$TMP/home14"; SDK14="$TMPHOME14/Android/Sdk/platform-tools"
mkdir -p "$SDK14" "$TMPHOME14/bin"; : > "$TMPHOME14/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "broken-adb14 $@"' > "$SDK14/adb"; chmod +x "$SDK14/adb"
env HOME="$TMPHOME" PATH="$STUB:$PATH" \
  "$BASH_BIN" "$BROKEN/install.sh" --java "$FAKE_JAVA" --jar "$JAR" --no-ui --home "$TMPHOME14" >"$TMP/claim-shimfail.log" 2>&1; rc14=$?
dump_on_fail "$rc14" "$TMP/claim-shimfail.log"
check "incomplete bundle -> refused before any mutation (real adb never stashed aside)" bash -c \
  "[ '$rc14' -ne 0 ] && grep -qi 'adb-wrapper missing from the bundle' '$TMP/claim-shimfail.log' && ! grep -qi 'shim is active' '$TMP/claim-shimfail.log' && ! grep -qi 'Ensured SDK adb shim' '$TMP/claim-shimfail.log' && ! grep -qi 'Stashed real adb' '$TMP/claim-shimfail.log' && grep -q 'broken-adb14' '$SDK14/adb' && [ ! -e '$SDK14/adb.real' ]"

# 6: the reviewer's data-loss scenario. A wrapper sits at adb_path with NO adb.real; an incomplete
# bundle makes the earlier cp fail. The old rollback's `rm -f` deleted the only adb on the system —
# the pre-check must leave it byte-for-byte intact. This row fails on 2d948e2.
BROKEN2="$TMP/broken-bundle2"; mkdir -p "$BROKEN2"
cp "$SRC/install.sh" "$BROKEN2/install.sh"
TMPHOME15="$TMP/home15"; SDK15="$TMPHOME15/Android/Sdk/platform-tools"
mkdir -p "$SDK15" "$TMPHOME15/bin"; : > "$TMPHOME15/.bashrc"
cp "$SRC/adb-wrapper" "$SDK15/adb"; chmod +x "$SDK15/adb"   # previous install; adb.real deliberately absent
before_sha=$(sha256sum "$SDK15/adb" | cut -d' ' -f1)
env HOME="$TMPHOME" PATH="$STUB:$PATH" \
  "$BASH_BIN" "$BROKEN2/install.sh" --java "$FAKE_JAVA" --jar "$JAR" --no-ui --home "$TMPHOME15" >"$TMP/claim-nodelete.log" 2>&1; rc15=$?
dump_on_fail "$rc15" "$TMP/claim-nodelete.log"
check "failed shim install keeps an existing wrapper with no adb.real byte-for-byte intact" bash -c \
  "[ '$rc15' -ne 0 ] && [ -f '$SDK15/adb' ] && [ \"\$(sha256sum '$SDK15/adb' | cut -d' ' -f1)\" = '$before_sha' ] && grep -q 'corral adb wrapper' '$SDK15/adb' && [ ! -e '$SDK15/adb.real' ] && ! grep -qi 'shim is active' '$TMP/claim-nodelete.log'"

# 7: the TRACKED .gitignore (not .git/info/exclude) must ignore both generated trees for every clone.
# `git check-ignore -q` only takes one path per call, so assert each — this fails if either entry is gone.
if [ "$BUILD" = 1 ]; then
  check "tracked .gitignore ignores corral-installer/ and dist/" bash -c \
    "git -C '$ROOT' check-ignore -q corral-installer/x && git -C '$ROOT' check-ignore -q dist/y"
fi

# ===== Windows platform: install .bat shims, skip systemd, report honestly =====
# install.sh detects Windows from `uname -s`; a stubbed `uname` first on PATH is how this suite tells
# it MINGW*. This is a SIMULATION of Windows (Git Bash semantics); only a real Windows box can truly
# verify cmd.exe/PowerShell behaviour.
WIN_STUB="$TMP/winstub"; mkdir -p "$WIN_STUB"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$WIN_STUB/uname"
# PowerShell stub for the daemon-stop path: it emulates the two commands install.sh runs. PS_TABLE
# holds "<pid>|<process name> <command line>" per line; a query prints the pid of every java
# process whose command line contains $CORRAL_DAEMON_JAR, and Stop-Process -Id removes those pids.
# The emulation reads the FILTER off the query command itself (the java family and whether path
# separators are normalised), so an installer that stops asking for either fails these rows.
# PS_KILL_FAIL=1 leaves them alive (a stop that did not work); PS_QUERY_FAIL=1 fails every query;
# PS_QUERY_FAIL_AT=<n> fails only the n-th query (a transient failure).
cat > "$TMP/powershell-stub.sh" <<EOF
#!/bin/sh
TABLE=\${PS_TABLE:-$TMP/win-procs}
PSLOG=\${PS_LOG:-$TMP/powershell.log}
KILLED=\${PS_KILLED:-$TMP/powershell-killed}
QCOUNT=\${PS_QUERY_COUNT:-$TMP/powershell-queries}
printf '%s\n' "\$*" >> "\$PSLOG"
cmd="\$*"
case "\$cmd" in
  *Stop-Process*)
    ids=\${cmd#*Stop-Process -Id }; ids=\${ids%% *}
    old=\$IFS; IFS=,
    for id in \$ids; do
      [ "\${PS_KILL_FAIL:-0}" = 1 ] && continue
      if [ -f "\$TABLE" ]; then grep -v "^\$id|" "\$TABLE" > "\$TABLE.tmp" 2>/dev/null; mv "\$TABLE.tmp" "\$TABLE"; fi
      printf '%s\n' "\$id" >> "\$KILLED"
    done
    IFS=\$old;;
  *Get-CimInstance*)
    [ "\${PS_QUERY_FAIL:-0}" = 1 ] && exit 1
    qn=0
    [ -f "\$QCOUNT" ] && qn=\$(cat "\$QCOUNT")
    qn=\$((qn + 1))
    printf '%s\n' "\$qn" > "\$QCOUNT"
    [ "\${PS_QUERY_FAIL_AT:-0}" = "\$qn" ] && exit 1
    [ -n "\${CORRAL_DAEMON_JAR:-}" ] || exit 0
    [ -f "\$TABLE" ] || exit 0
    names=java.exe
    case "\$cmd" in *javaw*) names="java.exe javaw.exe";; esac
    norm=0
    case "\$cmd" in *Replace*) norm=1;; esac
    want=\$CORRAL_DAEMON_JAR
    [ "\$norm" = 1 ] && want=\$(printf '%s' "\$want" | tr '\134' '/')
    while IFS='|' read -r pid rest; do
      [ -n "\$rest" ] || continue
      ok=0
      for n in \$names; do [ "\${rest%% *}" = "\$n" ] && ok=1; done
      [ "\$ok" = 1 ] || continue
      cl=\$rest
      [ "\$norm" = 1 ] && cl=\$(printf '%s' "\$cl" | tr '\134' '/')
      case "\$cl" in *"\$want"*) printf '%s\n' "\$pid";; esac
    done < "\$TABLE";;
esac
exit 0
EOF
chmod +x "$TMP/powershell-stub.sh"
# rm stub: models a daemon still holding ~/.corral (Windows "Device or resource busy"). Every other
# path is delegated to the real rm, so the rest of the uninstall behaves normally.
REAL_RM=$(command -v rm)
cat > "$TMP/rm-stub-holder.sh" <<EOF
#!/bin/sh
for a in "\$@"; do
  case "\$a" in *.corral) echo "rm: cannot remove '\$a': Device or resource busy" >&2; exit 1;; esac
done
exec "$REAL_RM" "\$@"
EOF
chmod +x "$TMP/rm-stub-holder.sh"
mkdir -p "$TMP/rm-stub"; cp "$TMP/rm-stub-holder.sh" "$TMP/rm-stub/rm"
# A successful schtasks stub: the existing Windows scenarios must exercise the Scheduled Task path
# (its own /sc onlogon create, then /run). Individual task rows below use their own stubs to force
# the refusal and error-line cases.
cat > "$WIN_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TMP/win-schtasks.log"
echo 'SUCCESS: The scheduled task "CorralDaemon" has successfully been created.'
exit 0
EOF
chmod +x "$WIN_STUB/uname" "$WIN_STUB/schtasks"
cp "$TMP/powershell-stub.sh" "$WIN_STUB/powershell.exe"; chmod +x "$WIN_STUB/powershell.exe"

TMPHOME_W="$TMP/home-win"; SDK_W="$TMPHOME_W/Android/Sdk/platform-tools"
mkdir -p "$SDK_W" "$TMPHOME_W/bin"; : > "$TMPHOME_W/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "win-sdk-adb $@"' > "$SDK_W/adb"; chmod +x "$SDK_W/adb"
WIN_ADB_SHA=$(sha256sum "$SDK_W/adb" | cut -d' ' -f1)
UNIT_W="$TMPHOME_W/.config/systemd/user/corral.service"
sc_before=$(wc -l < "$TMP/systemctl.log" 2>/dev/null || echo 0)

env HOME="$TMPHOME_W" PATH="$WIN_STUB:$STUB:$PATH" \
  bash "$SRC/install.sh" --java "$FAKE_JAVA" --adb "$SDK_W/adb" --jar "$JAR" --no-ui --home "$TMPHOME_W" \
  >"$TMP/win-install.log" 2>&1; rcw=$?
dump_on_fail "$rcw" "$TMP/win-install.log"
sc_after=$(wc -l < "$TMP/systemctl.log" 2>/dev/null || echo 0)

check "win: install exits 0" test "$rcw" -eq 0
check "win: corral.bat installed verbatim" bash -c \
  "[ -f '$TMPHOME_W/bin/corral.bat' ] && cmp -s '$SRC/corral.bat' '$TMPHOME_W/bin/corral.bat'"
check "win: adb-wrapper.bat installed verbatim" bash -c \
  "[ -f '$TMPHOME_W/bin/adb-wrapper.bat' ] && cmp -s '$SRC/adb-wrapper.bat' '$TMPHOME_W/bin/adb-wrapper.bat'"
# F2 regression: the POSIX corral/adb scripts must NOT land on the Windows user PATH
check "win: POSIX corral/adb shims are NOT installed (F2)" bash -c \
  "[ ! -e '$TMPHOME_W/bin/corral' ] && [ ! -e '$TMPHOME_W/bin/adb' ]"
check "win: no systemd unit generated" test ! -e "$UNIT_W"
check "win: systemctl never invoked" test "$sc_before" = "$sc_after"
# the SDK shim is the same POSIX-on-the-adb-path defect, so Windows must not install it either
check "win: SDK adb left untouched (no POSIX wrapper on the adb path)" \
  test "$(sha256sum "$SDK_W/adb" | cut -d' ' -f1)" = "$WIN_ADB_SHA"
# honest reporting: platform, the not-done lines, and no claim of an absent shim/unit/launcher
check "win: output states the platform" grep -q 'platform: Windows' "$TMP/win-install.log"
check "win: output says the POSIX shim, systemd unit and UI launcher were NOT done" bash -c \
  "grep -q 'no POSIX corral/adb shim' '$TMP/win-install.log' && grep -q 'no systemd unit' '$TMP/win-install.log' && grep -q 'no corral-ui shim' '$TMP/win-install.log'"
check "win: never claims a shim/unit that does not exist" bash -c \
  "! grep -qi 'shim is active' '$TMP/win-install.log' && ! grep -q 'Installed: corral-ui (~/bin/corral-ui' '$TMP/win-install.log'"
check "win: gate points at the .bat shim" grep -qF "$TMPHOME_W/bin/corral.bat" "$TMPHOME_W/.gradle/init.d/corral-gate.gradle.kts"

# ===== POSIX byte-for-byte guard: the Linux path must not change =====
# Compare the current installer against the pre-change installer (the commit this row branched from,
# ee9cc77) on identical POSIX sandboxes: same exit code, same messages, same installed files/content.
# Reusing one sandbox path keeps absolute paths identical so hashes and messages are comparable.
# The skip is LOUD: a silent skip would leave every Linux user unguarded without anyone noticing.
if [ "$BUILD" != 1 ]; then
  echo "skip: POSIX byte-for-byte guard needs the source tree + git history (a deployed bundle has neither)"
elif ! git -C "$ROOT" cat-file -e ee9cc77:scripts/install.sh 2>/dev/null; then
  echo "skip: POSIX byte-for-byte guard — base commit ee9cc77 is not in this clone, so the Linux path is"
  echo "      UNGUARDED here; CI must check out with actions/checkout fetch-depth: 0."
else
  # Each installer runs from its own bundle dir with identical sibling assets, so SCRIPT_DIR is
  # analogous and the only difference is install.sh itself.
  for label in pre new; do
    mkdir -p "$TMP/posix-$label-bundle"
    cp "$SRC/adb-wrapper" "$SRC/corral" "$SRC/corral.service" "$SRC/corral-gate.gradle.kts" \
       "$SRC/corral-rules" "$TMP/posix-$label-bundle/"
  done
  git -C "$ROOT" show ee9cc77:scripts/install.sh > "$TMP/posix-pre-bundle/install.sh"
  cp "$SRC/install.sh" "$TMP/posix-new-bundle/install.sh"

  CMP_HOME="$TMP/cmp-home"
  posix_snapshot() { # posix_snapshot <bundle-dir> <label>
    rm -rf "$CMP_HOME"
    mkdir -p "$CMP_HOME/Android/Sdk/platform-tools" "$CMP_HOME/bin"
    printf '%s\n' '#!/bin/sh' 'echo "cmp-adb $@"' > "$CMP_HOME/Android/Sdk/platform-tools/adb"
    chmod +x "$CMP_HOME/Android/Sdk/platform-tools/adb"
    : > "$CMP_HOME/.bashrc"
    env HOME="$CMP_HOME" PATH="$STUB:$PATH" bash "$1/install.sh" --java "$FAKE_JAVA" \
      --adb "$CMP_HOME/Android/Sdk/platform-tools/adb" --jar "$JAR" --no-ui --home "$CMP_HOME" \
      > "$TMP/$2.log" 2>&1
    echo "$?" > "$TMP/$2.rc"
    ( cd "$CMP_HOME" && find . -type f | sort | xargs sha256sum ) > "$TMP/$2.files"
  }
  posix_snapshot "$TMP/posix-pre-bundle" posix-pre
  posix_snapshot "$TMP/posix-new-bundle" posix-new
  check "POSIX: exit code matches the pre-change installer" cmp -s "$TMP/posix-pre.rc" "$TMP/posix-new.rc"
  check "POSIX: every message matches the pre-change installer (byte-for-byte)" cmp -s "$TMP/posix-pre.log" "$TMP/posix-new.log"
  check "POSIX: installed files + contents match the pre-change installer" cmp -s "$TMP/posix-pre.files" "$TMP/posix-new.files"
fi

# ===== POSIX guard, second scenario: an SDK at the Windows location must not change POSIX (SF1) ==
# The guard above always puts the SDK at the classic $HOME/Android/Sdk path AND passes --adb, so it
# never exercises adb DETECTION — which is exactly the code this change touched. With an SDK (only)
# under $HOME/AppData/Local/Android/Sdk, no --adb and no SDK on PATH, detection decides everything:
# the change's parent (f4f2270) refuses (rc=1, nothing written), while HEAD without the IS_WINDOWS
# gate resolves that adb, wraps it with install_sdk_shim and exits 0 — a Linux behaviour change no
# row above would have caught. The reference is the parent commit, and the parity claim is the full
# one (exit code, messages and installed file set): the POSIX refusal text is the parent's verbatim,
# because only the Windows branch may name the Windows SDK location. The skip is LOUD, as above.
if [ "$BUILD" != 1 ]; then
  echo "skip: POSIX AppData-path guard needs the source tree + git history (a deployed bundle has neither)"
elif ! git -C "$ROOT" cat-file -e f4f2270:scripts/install.sh 2>/dev/null; then
  echo "skip: POSIX AppData-path guard — parent commit f4f2270 is not in this clone, so the Windows"
  echo "      SDK location is UNGUARDED on POSIX here; CI must check out with fetch-depth: 0."
else
  for label in adpre adnew; do
    mkdir -p "$TMP/appdata-$label-bundle"
    cp "$SRC/adb-wrapper" "$SRC/corral" "$SRC/corral.service" "$SRC/corral-gate.gradle.kts" \
       "$SRC/corral-rules" "$TMP/appdata-$label-bundle/"
  done
  git -C "$ROOT" show f4f2270:scripts/install.sh > "$TMP/appdata-adpre-bundle/install.sh"
  cp "$SRC/install.sh" "$TMP/appdata-adnew-bundle/install.sh"

  # The sandbox's ONLY adb sits at the AppData path, spelled the POSIX way (`adb`, because ADB_NAMES
  # on POSIX is `adb` alone). One sandbox path, reused, so both runs name identical absolute paths.
  AD_HOME="$TMP/appdata-home"
  AD_ADB="$AD_HOME/AppData/Local/Android/Sdk/platform-tools/adb"
  appdata_snapshot() { # appdata_snapshot <bundle-dir> <label>
    rm -rf "$AD_HOME"
    mkdir -p "$AD_HOME/AppData/Local/Android/Sdk/platform-tools" "$AD_HOME/bin"
    printf '%s\n' '#!/bin/sh' 'echo "appdata-adb $@"' > "$AD_ADB"
    chmod +x "$AD_ADB"
    : > "$AD_HOME/.bashrc"
    env HOME="$AD_HOME" PATH="$STUB:$PATH" bash "$1/install.sh" --java "$FAKE_JAVA" --jar "$JAR" \
      --no-ui --home "$AD_HOME" > "$TMP/$2.log" 2>&1
    echo "$?" > "$TMP/$2.rc"
    ( cd "$AD_HOME" && find . -type f | sort | xargs sha256sum ) > "$TMP/$2.files"
  }
  appdata_snapshot "$TMP/appdata-adpre-bundle" ad-pre
  appdata_snapshot "$TMP/appdata-adnew-bundle" ad-new
  # Discriminating rows: each fails the moment the AppData candidate resolves on POSIX.
  check "POSIX+AppData SDK: both refuse the out-of-tree adb — HEAD's exit code is the parent's (rc=1)" \
    bash -c "[ \"\$(cat '$TMP/ad-pre.rc')\" = 1 ] && cmp -s '$TMP/ad-pre.rc' '$TMP/ad-new.rc'"
  check "POSIX+AppData SDK: HEAD's refusal message is the parent's, byte-for-byte" \
    cmp -s "$TMP/ad-pre.log" "$TMP/ad-new.log"
  check "POSIX+AppData SDK: HEAD's file set + hashes are the parent's (nothing resolved, nothing shimmed)" \
    cmp -s "$TMP/ad-pre.files" "$TMP/ad-new.files"
  check "POSIX+AppData SDK: HEAD never gets as far as USING or shimming that adb" bash -c \
    "! grep -q 'Using adb' '$TMP/ad-new.log' && ! grep -q 'Ensured SDK adb shim' '$TMP/ad-new.log'"
  # Guard rows: true of both installers, and they define what "refused" has to mean — a refusal that
  # stopped being a refusal fails here even though both installers would agree with each other.
  check "POSIX+AppData SDK: the refusal is the adb one, and it offers --adb" bash -c \
    "grep -q 'refusing to shim one from outside' '$TMP/ad-new.log' && grep -q -- '--adb' '$TMP/ad-new.log' && grep -q -- '--adb' '$TMP/ad-pre.log'"
  check "POSIX+AppData SDK: the AppData adb is left as a real adb (no adb.real, no wrapper over it)" \
    bash -c "[ ! -e '$AD_ADB.real' ] && grep -q 'appdata-adb' '$AD_ADB' && ! grep -q 'corral adb wrapper' '$AD_ADB'"
  check "POSIX+AppData SDK: nothing was installed into the sandbox by the refused run" \
    bash -c "[ ! -e '$AD_HOME/.corral' ] && [ ! -e '$AD_HOME/bin/adb' ] && [ ! -e '$AD_HOME/bin/corral' ]"
fi

# ===== uninstall provenance (POSIX): only remove shims whose content carries our marker =====
# The row-14 defect class: uninstall must never delete a file Corral did not install. Overwrite our
# own ~/bin/corral with a hand-written one and plant a user's ~/bin/adb; both must survive. UI on, so
# an untouched shim of ours (~/bin/corral-ui) is present to prove the marker path still removes ours.
TMPHOME_UP="$TMP/home-uninstall-posix"; SDK_UP="$TMPHOME_UP/Android/Sdk/platform-tools"
mkdir -p "$SDK_UP" "$TMPHOME_UP/bin"; : > "$TMPHOME_UP/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "up-sdk-adb $@"' > "$SDK_UP/adb"; chmod +x "$SDK_UP/adb"
env HOME="$TMPHOME_UP" PATH="$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$SDK_UP/adb" --jar "$JAR" --home "$TMPHOME_UP" >"$TMP/up-install.log" 2>&1; rci=$?
dump_on_fail "$rci" "$TMP/up-install.log"
printf '%s\n' '#!/bin/sh' 'echo mine' > "$TMPHOME_UP/bin/corral"
printf '%s\n' '#!/bin/sh' 'echo user-adb' > "$TMPHOME_UP/bin/adb"
env HOME="$TMPHOME_UP" PATH="$STUB:$PATH" bash "$SRC/install.sh" --home "$TMPHOME_UP" --uninstall \
  >"$TMP/up-uninstall.log" 2>&1; rcup=$?
dump_on_fail "$rcup" "$TMP/up-uninstall.log"
check "uninstall provenance: hand-written ~/bin/corral survives" bash -c \
  "[ -f '$TMPHOME_UP/bin/corral' ] && grep -q 'echo mine' '$TMPHOME_UP/bin/corral'"
check "uninstall provenance: user-planted ~/bin/adb survives" bash -c \
  "[ -f '$TMPHOME_UP/bin/adb' ] && grep -q 'echo user-adb' '$TMPHOME_UP/bin/adb'"
check "uninstall provenance: output says the unrecognised shims were left alone" bash -c \
  "grep -q 'Left alone' '$TMP/up-uninstall.log' && grep -qF '$TMPHOME_UP/bin/corral' '$TMP/up-uninstall.log' && grep -qF '$TMPHOME_UP/bin/adb' '$TMP/up-uninstall.log'"
check "uninstall provenance: our own files are still removed" bash -c \
  "[ ! -e '$TMPHOME_UP/.config/systemd/user/corral.service' ] && [ ! -e '$TMPHOME_UP/.corral' ]"
# The marker must MATCH our shipped shim, or the guard would silently never remove it.
check "uninstall provenance: an untouched shim of ours (~/bin/corral-ui) is still removed" bash -c \
  "[ ! -e '$TMPHOME_UP/bin/corral-ui' ] && grep -qF '~/bin/corral-ui' '$TMP/up-uninstall.log'"
# The summary must name the kept shims only under 'Left alone', never under 'Removed:'.
check "uninstall provenance: the Removed: line never claims the kept shims were removed" bash -c \
  "! grep '^Removed:' '$TMP/up-uninstall.log' | grep -qE '~/bin/(adb|corral),'"

# ===== Windows uninstall: remove our .bat shims, never the POSIX names, keep a user's files =====
# The exact QA repro: simulated-Windows install -> plant ~/bin/adb and a hand-edited corral.bat ->
# --uninstall -> both survive. Before the fix the POSIX list was rm -f'd unconditionally.
TMPHOME_WU="$TMP/home-win-uninstall"; SDK_WU="$TMPHOME_WU/Android/Sdk/platform-tools"
mkdir -p "$SDK_WU" "$TMPHOME_WU/bin"; : > "$TMPHOME_WU/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "winup-sdk-adb $@"' > "$SDK_WU/adb"; chmod +x "$SDK_WU/adb"
env HOME="$TMPHOME_WU" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$SDK_WU/adb" --jar "$JAR" --no-ui --home "$TMPHOME_WU" >"$TMP/winup-install.log" 2>&1; rciw=$?
dump_on_fail "$rciw" "$TMP/winup-install.log"
printf '%s\n' '#!/bin/sh' 'echo user-win-adb' > "$TMPHOME_WU/bin/adb"
printf '@echo off\r\necho user corral\r\n' > "$TMPHOME_WU/bin/corral.bat"
env HOME="$TMPHOME_WU" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --home "$TMPHOME_WU" --uninstall \
  >"$TMP/winup-uninstall.log" 2>&1; rcwu=$?
dump_on_fail "$rcwu" "$TMP/winup-uninstall.log"
check "win uninstall: exits 0" test "$rcwu" -eq 0
check "win uninstall: the user's ~/bin/adb (POSIX name) survives" bash -c \
  "[ -f '$TMPHOME_WU/bin/adb' ] && grep -q 'user-win-adb' '$TMPHOME_WU/bin/adb'"
check "win uninstall: the hand-edited corral.bat survives" bash -c \
  "[ -f '$TMPHOME_WU/bin/corral.bat' ] && grep -q 'user corral' '$TMPHOME_WU/bin/corral.bat'"
check "win uninstall: our adb-wrapper.bat is removed" test ! -e "$TMPHOME_WU/bin/adb-wrapper.bat"
# The operator's own ~/bin/adb is unrecognised, so the generic both-platform summary names it under
# "Left alone"; there is no longer a Windows-specific line claiming the POSIX names are never
# installed here (they are ours on an upgraded box, and uninstall now removes those — see below).
check "win uninstall: summary names the removed .bat shim and the kept foreign ~/bin/adb" bash -c \
  "grep -qF '~/bin/adb-wrapper.bat' '$TMP/winup-uninstall.log' && grep -q 'Left alone' '$TMP/winup-uninstall.log' && grep -qF '$TMPHOME_WU/bin/adb' '$TMP/winup-uninstall.log' && ! grep -q 'never installs here' '$TMP/winup-uninstall.log'"
# The Windows summary must name what it removed and never list the POSIX shims as removed.
check "win uninstall: the Removed: line names only the removed .bat shim" bash -c \
  "line=\$(grep '^Removed:' '$TMP/winup-uninstall.log'); printf '%s' \"\$line\" | grep -qF '~/bin/adb-wrapper.bat' && ! printf '%s' \"\$line\" | grep -qE '~/bin/(adb|corral),'"

# Windows normal uninstall: our own .bat shims go and the summary names them
env HOME="$TMPHOME_W" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --home "$TMPHOME_W" --uninstall \
  >"$TMP/win-uninstall.log" 2>&1; rcuw=$?
dump_on_fail "$rcuw" "$TMP/win-uninstall.log"
check "win uninstall: our corral.bat + adb-wrapper.bat are removed" bash -c \
  "[ ! -e '$TMPHOME_W/bin/corral.bat' ] && [ ! -e '$TMPHOME_W/bin/adb-wrapper.bat' ]"
check "win uninstall: summary names both .bat shims" bash -c \
  "grep -qF '~/bin/corral.bat' '$TMP/win-uninstall.log' && grep -qF '~/bin/adb-wrapper.bat' '$TMP/win-uninstall.log'"

# ===== Windows: adb under the standard %LOCALAPPDATA%\Android\Sdk location (F-NEW-2) =====
# Helpers: the installer prints one daemon start line per shell, so read the CORRAL_ADB out of the
# printed line itself (same_file compares the value with the adb the installer resolved — it works
# whichever way the path is spelled, POSIX or Windows). Parsing is done with shell builtins on
# purpose: a regex argument containing a `"` is mangled by the MSYS runtime when it reaches git
# bash's native grep/sed, so a quoted-string pattern silently never matches on a real Windows host.
win_cmd_adb() { # win_cmd_adb <log>
  local line v
  while IFS= read -r line; do
    case "$line" in
      '  cmd.exe     set "CORRAL_ADB='*)
        v=${line#*CORRAL_ADB=}; printf '%s\n' "${v%%\"*}"; return 0;;
    esac
  done < "$1"
}
win_ps_adb() { # win_ps_adb <log>
  local line v
  while IFS= read -r line; do
    case "$line" in
      '  PowerShell  '*"CORRAL_ADB='"*)
        v=${line#*CORRAL_ADB=\'}; printf '%s\n' "${v%%\'*}"; return 0;;
    esac
  done < "$1"
}
has_both_shell_lines() { # has_both_shell_lines <log>
  local line cmd=0 ps=0
  while IFS= read -r line; do
    case "$line" in '  cmd.exe     set "CORRAL_ADB='*) cmd=1;; esac
    case "$line" in '  PowerShell  '*"CORRAL_ADB='"*) ps=1;; esac
  done < "$1"
  [ "$cmd" = 1 ] && [ "$ps" = 1 ]
}
same_file() { [ -n "$1" ] && [ -x "$1" ] && cmp -s "$1" "$2"; }
same_value() { [ -n "$1" ] && [ "$1" = "$2" ]; }

# A default Windows box keeps the SDK ONLY there, with ANDROID_HOME/ANDROID_SDK_ROOT unset and no
# adb on PATH: before this change the installer warned "no real adb found" and left CORRAL_ADB unset,
# so the daemon it told the operator to start could never see a device. No --adb and no --home here —
# this is the detection path a real operator hits.
WIN_APP_HOME="$TMP/home-win-appdata"; WIN_APP_SDK="$WIN_APP_HOME/AppData/Local/Android/Sdk"
WIN_APP_ADB="$WIN_APP_SDK/platform-tools/adb.exe"
mkdir -p "$WIN_APP_SDK/platform-tools" "$WIN_APP_HOME/bin"; : > "$WIN_APP_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "win-appdata-adb $@"' > "$WIN_APP_ADB"
chmod +x "$WIN_APP_ADB"

env HOME="$WIN_APP_HOME" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" \
  --java "$FAKE_JAVA" --jar "$JAR" --no-ui >"$TMP/win-appdata.log" 2>&1; rcapp=$?
dump_on_fail "$rcapp" "$TMP/win-appdata.log"
check "win appdata: install exits 0" test "$rcapp" -eq 0
check "win appdata: adb IS found at \$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
  grep -qF "Using adb: $WIN_APP_ADB" "$TMP/win-appdata.log"
check "win appdata: the 'no real adb found' warning is ABSENT" \
  bash -c "! grep -q 'no real adb found' '$TMP/win-appdata.log'"
# the adb is threaded into the closing message the operator actually starts the daemon from
check "win appdata: the daemon start instruction carries CORRAL_ADB" \
  bash -c "grep -q 'CORRAL_ADB=' '$TMP/win-appdata.log'"
check "win appdata: CORRAL_ADB names the adb the installer resolved (same file, executable)" \
  same_file "$(win_cmd_adb "$TMP/win-appdata.log")" "$WIN_APP_ADB"
check "win appdata: the daemon instruction is printed for cmd.exe and PowerShell" \
  has_both_shell_lines "$TMP/win-appdata.log"
check "win appdata: both printed forms carry the SAME CORRAL_ADB value" \
  same_value "$(win_cmd_adb "$TMP/win-appdata.log")" "$(win_ps_adb "$TMP/win-appdata.log")"

# ===== Windows: --home stays confined away from the operator's AppData SDK (F-NEW-2) =====
# OP_SDK stands in for the operator's REAL %LOCALAPPDATA%\Android\Sdk. LOCALAPPDATA is pointed at it
# and it is handed to both installs; the sandbox has none, so a --home install must refuse rather
# than resolve (and, on POSIX, overwrite) an adb from outside the root.
OP_SDK="$TMP/operator-appdata"
mkdir -p "$OP_SDK/Android/Sdk/platform-tools"
printf '%s\n' '#!/bin/sh' 'echo "operator-adb $@"' > "$OP_SDK/Android/Sdk/platform-tools/adb.exe"
chmod +x "$OP_SDK/Android/Sdk/platform-tools/adb.exe"
OP_SDK_SHA=$(sha256sum "$OP_SDK/Android/Sdk/platform-tools/adb.exe" | cut -d' ' -f1)
OP_LOCALAPPDATA=$(cygpath -w "$OP_SDK" 2>/dev/null || printf '%s' "$OP_SDK")
REALISH_HOME="$TMP/operator-home"; mkdir -p "$REALISH_HOME"; : > "$REALISH_HOME/.bashrc"

WIN_CONF_HOME="$TMP/home-win-confined"; WIN_CONF_SDK="$WIN_CONF_HOME/AppData/Local/Android/Sdk"
mkdir -p "$WIN_CONF_SDK/platform-tools" "$WIN_CONF_HOME/bin"; : > "$WIN_CONF_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "win-confined-adb $@"' > "$WIN_CONF_SDK/platform-tools/adb.exe"
chmod +x "$WIN_CONF_SDK/platform-tools/adb.exe"
env HOME="$REALISH_HOME" PATH="$WIN_STUB:$STUB:$PATH" LOCALAPPDATA="$OP_LOCALAPPDATA" \
  bash "$SRC/install.sh" --java "$FAKE_JAVA" --jar "$JAR" --no-ui --home "$WIN_CONF_HOME" \
  >"$TMP/win-confined.log" 2>&1; rccf=$?
dump_on_fail "$rccf" "$TMP/win-confined.log"
check "win --home: an in-root AppData SDK is used, not the operator's" \
  grep -qF "Using adb: $WIN_CONF_SDK/platform-tools/adb.exe" "$TMP/win-confined.log"
check "win --home: the daemon line's CORRAL_ADB is that in-root adb.exe" \
  same_file "$(win_cmd_adb "$TMP/win-confined.log")" "$WIN_CONF_SDK/platform-tools/adb.exe"

WIN_EMPTY_HOME="$TMP/home-win-empty"; mkdir -p "$WIN_EMPTY_HOME/bin"; : > "$WIN_EMPTY_HOME/.bashrc"
env HOME="$REALISH_HOME" PATH="$WIN_STUB:$STUB:$PATH" LOCALAPPDATA="$OP_LOCALAPPDATA" \
  bash "$SRC/install.sh" --java "$FAKE_JAVA" --jar "$JAR" --no-ui --home "$WIN_EMPTY_HOME" \
  >"$TMP/win-confine-refuse.log" 2>&1; rcrf=$?
check "win --home: refuses to reach the operator's AppData SDK (exit non-zero)" test "$rcrf" -ne 0
# The POSIX analogue (:334-335) greps the refusal REASON. Assert it here too: without it any
# unrelated early failure (a missing jar, a bad --java) would pass all three rows below.
check "win --home: the refusal is the adb one and it offers --adb" bash -c \
  "[ '$rcrf' -ne 0 ] && grep -q -- '--adb' '$TMP/win-confine-refuse.log' && grep -q 'refusing to shim one from outside' '$TMP/win-confine-refuse.log' && grep -q 'looked in' '$TMP/win-confine-refuse.log'"
check "win --home: the outside SDK adb.exe is byte-for-byte untouched" \
  test "$OP_SDK_SHA" = "$(sha256sum "$OP_SDK/Android/Sdk/platform-tools/adb.exe" | cut -d' ' -f1)"
check "win --home: nothing at all was written into the empty sandbox" bash -c \
  "[ ! -e '$WIN_EMPTY_HOME/.corral' ] && [ ! -e '$WIN_EMPTY_HOME/bin/adb' ] && [ ! -e '$WIN_EMPTY_HOME/bin/corral.bat' ] && [ ! -e '$WIN_EMPTY_HOME/AppData' ]"

# ===== Windows uninstall of the legacy pre-.bat POSIX shims (F-NEW-1) =====
# A box installed BEFORE the .bat path still carries the bash `corral`/`adb`/`corral-ui` the old
# installer put on its PATH — the stray bash `adb` finding F2 was about. Uninstall used to gate those
# three names on IS_WINDOWS, so it never looked at them and left them forever. The sandbox really is
# an upgraded box: the POSIX installer writes the shims, then the uninstall runs as Windows.
legacy_posix_install() { # legacy_posix_install <home> <sdk-adb>
  env HOME="$1" PATH="$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" --adb "$2" \
    --jar "$JAR" --home "$1" --ui
}
legacy_win_uninstall() { # legacy_win_uninstall <home>
  env HOME="$1" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --home "$1" --uninstall
}

WIN_LEG_HOME="$TMP/home-win-legacy"; WIN_LEG_SDK="$WIN_LEG_HOME/Android/Sdk/platform-tools"
mkdir -p "$WIN_LEG_SDK" "$WIN_LEG_HOME/bin"; : > "$WIN_LEG_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "legacy-sdk-adb $@"' > "$WIN_LEG_SDK/adb"; chmod +x "$WIN_LEG_SDK/adb"
legacy_posix_install "$WIN_LEG_HOME" "$WIN_LEG_SDK/adb" >"$TMP/win-legacy-posix.log" 2>&1; rclp=$?
dump_on_fail "$rclp" "$TMP/win-legacy-posix.log"
check "win legacy: the pre-change install really left bash adb/corral/corral-ui behind (markers)" bash -c \
  "[ -x '$WIN_LEG_HOME/bin/adb' ] && [ -x '$WIN_LEG_HOME/bin/corral' ] && [ -x '$WIN_LEG_HOME/bin/corral-ui' ] && grep -q 'corral adb wrapper' '$WIN_LEG_HOME/bin/adb' && grep -q 'dev.corral.CorralCli' '$WIN_LEG_HOME/bin/corral'"
legacy_win_uninstall "$WIN_LEG_HOME" >"$TMP/win-legacy-uninstall.log" 2>&1; rclu=$?
dump_on_fail "$rclu" "$TMP/win-legacy-uninstall.log"
check "win legacy: uninstall exits 0" test "$rclu" -eq 0
check "win legacy: the legacy bash ~/bin/adb carrying our marker IS removed" test ! -e "$WIN_LEG_HOME/bin/adb"
check "win legacy: the legacy bash ~/bin/corral carrying our marker IS removed" test ! -e "$WIN_LEG_HOME/bin/corral"
check "win legacy: the legacy bash ~/bin/corral-ui carrying our marker IS removed" test ! -e "$WIN_LEG_HOME/bin/corral-ui"
check "win legacy: the summary names the legacy shims under Removed:" bash -c \
  "line=\$(grep '^Removed:' '$TMP/win-legacy-uninstall.log'); printf '%s' \"\$line\" | grep -qF '~/bin/adb,' && printf '%s' \"\$line\" | grep -qF '~/bin/corral,' && printf '%s' \"\$line\" | grep -qF '~/bin/corral-ui,'"
check "win legacy: nothing is claimed to be 'never installs here' any more" \
  bash -c "! grep -q 'never installs here' '$TMP/win-legacy-uninstall.log'"

# The other half, and the P1 data-loss guard: an operator's OWN ~/bin/adb has no marker and must
# survive a Windows uninstall byte-for-byte while our legacy ~/bin/corral next to it does not.
WIN_FOR_HOME="$TMP/home-win-foreign"; WIN_FOR_SDK="$WIN_FOR_HOME/Android/Sdk/platform-tools"
mkdir -p "$WIN_FOR_SDK" "$WIN_FOR_HOME/bin"; : > "$WIN_FOR_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "foreign-sdk-adb $@"' > "$WIN_FOR_SDK/adb"; chmod +x "$WIN_FOR_SDK/adb"
legacy_posix_install "$WIN_FOR_HOME" "$WIN_FOR_SDK/adb" >"$TMP/win-foreign-posix.log" 2>&1; rcfp=$?
dump_on_fail "$rcfp" "$TMP/win-foreign-posix.log"
printf '%s\n' '#!/bin/sh' 'echo my-own-adb' > "$WIN_FOR_HOME/bin/adb"
chmod +x "$WIN_FOR_HOME/bin/adb"
FOREIGN_SHA=$(sha256sum "$WIN_FOR_HOME/bin/adb" | cut -d' ' -f1)
legacy_win_uninstall "$WIN_FOR_HOME" >"$TMP/win-foreign-uninstall.log" 2>&1; rcfu=$?
dump_on_fail "$rcfu" "$TMP/win-foreign-uninstall.log"
check "win foreign: uninstall exits 0" test "$rcfu" -eq 0
check "win foreign: the operator's own ~/bin/adb SURVIVES byte-for-byte" bash -c \
  "[ -f '$WIN_FOR_HOME/bin/adb' ] && [ \"\$(sha256sum '$WIN_FOR_HOME/bin/adb' | cut -d' ' -f1)\" = '$FOREIGN_SHA' ] && grep -q 'my-own-adb' '$WIN_FOR_HOME/bin/adb'"
check "win foreign: our legacy ~/bin/corral next to it is still removed" \
  test ! -e "$WIN_FOR_HOME/bin/corral"
check "win foreign: the kept file is named under 'Left alone', never under 'Removed:'" bash -c \
  "grep -q 'Left alone' '$TMP/win-foreign-uninstall.log' && grep -qF '$WIN_FOR_HOME/bin/adb' '$TMP/win-foreign-uninstall.log' && ! grep '^Removed:' '$TMP/win-foreign-uninstall.log' | grep -qF '~/bin/adb,'"

# ===== the shim markers really match the shipped shims (a guard that can fail) =====
# remove_shim deletes a file ONLY when its content carries its marker. If an edit to a shipped shim
# changes that string the marker stops matching in silence, and every legacy shim survives on both
# --uninstall and the Windows install — the "F2 never fixed" failure mode. Pin every marker to the
# file it is meant to recognise, so a shim edit has to update the marker on purpose.
check "markers: ~/bin/adb marker is in scripts/adb-wrapper" \
  grep -qF 'corral adb wrapper' "$SRC/adb-wrapper"
check "markers: ~/bin/corral marker is in scripts/corral" \
  grep -qF 'CORRAL_JAR:-$HOME/.corral/corral-daemon-all.jar' "$SRC/corral"
check "markers: ~/bin/corral-ui marker is in scripts/corral-ui" \
  grep -qF 'CORRAL_UI_JAR:-$HOME/.corral/corral-ui-all.jar' "$SRC/corral-ui"
check "markers: ~/bin/corral.bat marker is in scripts/corral.bat" \
  grep -qF 'dev.corral.CorralCli' "$SRC/corral.bat"

# ===== Windows INSTALL on an upgraded box: our legacy POSIX shims go, a foreign file stays (SF2) ==
# A box installed before the .bat path carries the bash adb/corral/corral-ui on its PATH. --uninstall
# dealt with them; a plain upgrade did not, so the stray bash `adb` finding F2 was about stayed in
# front of the real adb.exe for the life of the box. These sandboxes ARE such upgrades: the POSIX
# installer writes the shims, then the Windows installer runs over them.
WIN_UPG_HOME="$TMP/home-win-upgrade"; WIN_UPG_SDK="$WIN_UPG_HOME/Android/Sdk/platform-tools"
mkdir -p "$WIN_UPG_SDK" "$WIN_UPG_HOME/bin"; : > "$WIN_UPG_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "upg-sdk-adb $@"' > "$WIN_UPG_SDK/adb"; chmod +x "$WIN_UPG_SDK/adb"
legacy_posix_install "$WIN_UPG_HOME" "$WIN_UPG_SDK/adb" >"$TMP/win-upgrade-posix.log" 2>&1; rcup=$?
dump_on_fail "$rcup" "$TMP/win-upgrade-posix.log"
check "upgrade: the pre-change POSIX install left bash adb/corral/corral-ui behind (the hazard)" bash -c \
  "[ -x '$WIN_UPG_HOME/bin/adb' ] && [ -x '$WIN_UPG_HOME/bin/corral' ] && [ -x '$WIN_UPG_HOME/bin/corral-ui' ]"
env HOME="$WIN_UPG_HOME" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$WIN_UPG_SDK/adb" --jar "$JAR" --no-ui --home "$WIN_UPG_HOME" >"$TMP/win-upgrade-install.log" 2>&1; rcwi=$?
dump_on_fail "$rcwi" "$TMP/win-upgrade-install.log"
check "upgrade: the Windows install exits 0" test "$rcwi" -eq 0
check "upgrade: the stale bash ~/bin/adb is GONE after the install (the F2 hazard)" \
  test ! -e "$WIN_UPG_HOME/bin/adb"
check "upgrade: the stale bash ~/bin/corral and ~/bin/corral-ui are gone too" bash -c \
  "[ ! -e '$WIN_UPG_HOME/bin/corral' ] && [ ! -e '$WIN_UPG_HOME/bin/corral-ui' ]"
check "upgrade: the install SAYS which legacy shims it removed" bash -c \
  "grep -q 'Removed legacy POSIX shims from an earlier corral install' '$TMP/win-upgrade-install.log' && grep -qF '~/bin/adb,' '$TMP/win-upgrade-install.log' && grep -qF '~/bin/corral-ui' '$TMP/win-upgrade-install.log'"
check "upgrade: the .bat shims are installed alongside" bash -c \
  "[ -f '$WIN_UPG_HOME/bin/corral.bat' ] && [ -f '$WIN_UPG_HOME/bin/adb-wrapper.bat' ]"

# The P1 data-loss half: an operator's OWN ~/bin/adb has no marker. It must survive the install
# byte-for-byte while our legacy ~/bin/corral next to it is removed, and the output must say so.
WIN_FORUP_HOME="$TMP/home-win-upgrade-foreign"; WIN_FORUP_SDK="$WIN_FORUP_HOME/Android/Sdk/platform-tools"
mkdir -p "$WIN_FORUP_SDK" "$WIN_FORUP_HOME/bin"; : > "$WIN_FORUP_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "forup-sdk-adb $@"' > "$WIN_FORUP_SDK/adb"; chmod +x "$WIN_FORUP_SDK/adb"
legacy_posix_install "$WIN_FORUP_HOME" "$WIN_FORUP_SDK/adb" >"$TMP/win-upgfor-posix.log" 2>&1; rcfp2=$?
dump_on_fail "$rcfp2" "$TMP/win-upgfor-posix.log"
printf '%s\n' '#!/bin/sh' 'echo my-own-adb' > "$WIN_FORUP_HOME/bin/adb"
chmod +x "$WIN_FORUP_HOME/bin/adb"
FORUP_SHA=$(sha256sum "$WIN_FORUP_HOME/bin/adb" | cut -d' ' -f1)
env HOME="$WIN_FORUP_HOME" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$WIN_FORUP_SDK/adb" --jar "$JAR" --no-ui --home "$WIN_FORUP_HOME" >"$TMP/win-upgfor-install.log" 2>&1; rcwf=$?
dump_on_fail "$rcwf" "$TMP/win-upgfor-install.log"
check "upgrade+foreign: the Windows install exits 0" test "$rcwf" -eq 0
check "upgrade+foreign: the operator's own ~/bin/adb SURVIVES the install byte-for-byte" bash -c \
  "[ -f '$WIN_FORUP_HOME/bin/adb' ] && [ \"\$(sha256sum '$WIN_FORUP_HOME/bin/adb' | cut -d' ' -f1)\" = '$FORUP_SHA' ] && grep -q 'my-own-adb' '$WIN_FORUP_HOME/bin/adb'"
check "upgrade+foreign: our legacy ~/bin/corral next to it is still removed" \
  test ! -e "$WIN_FORUP_HOME/bin/corral"
check "upgrade+foreign: the install names the kept file AND warns it is still on the PATH" bash -c \
  "grep -q 'Left alone' '$TMP/win-upgfor-install.log' && grep -qF '$WIN_FORUP_HOME/bin/adb' '$TMP/win-upgfor-install.log' && grep -q 'WARNING' '$TMP/win-upgfor-install.log' && grep -q 'shadows/confuses the real adb.exe' '$TMP/win-upgfor-install.log'"
check "upgrade+foreign: the removed list names our corral shims, never the foreign ~/bin/adb" bash -c \
  "line=\$(grep 'Removed legacy POSIX shims' '$TMP/win-upgfor-install.log'); printf '%s' \"\$line\" | grep -qF '~/bin/corral,' && ! printf '%s' \"\$line\" | grep -qF '~/bin/adb,'"

# The strengthened ~/bin/corral marker must still spare an operator's own launcher: this one runs
# the very main class the old marker keyed on (the false positive the marker change exists to
# avoid), so this row FAILS against the FQN-only marker.
WIN_MARK_HOME="$TMP/home-win-marker"; WIN_MARK_SDK="$WIN_MARK_HOME/Android/Sdk/platform-tools"
mkdir -p "$WIN_MARK_SDK" "$WIN_MARK_HOME/bin"; : > "$WIN_MARK_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "mark-sdk-adb $@"' > "$WIN_MARK_SDK/adb"; chmod +x "$WIN_MARK_SDK/adb"
legacy_posix_install "$WIN_MARK_HOME" "$WIN_MARK_SDK/adb" >"$TMP/win-marker-posix.log" 2>&1; rcml=$?
dump_on_fail "$rcml" "$TMP/win-marker-posix.log"
printf '%s\n' '#!/bin/sh' 'exec java -cp "$HOME/.corral/corral-daemon-all.jar" dev.corral.CorralCli "$@"' \
  > "$WIN_MARK_HOME/bin/corral"
MARK_SHA=$(sha256sum "$WIN_MARK_HOME/bin/corral" | cut -d' ' -f1)
env HOME="$WIN_MARK_HOME" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$WIN_MARK_SDK/adb" --jar "$JAR" --no-ui --home "$WIN_MARK_HOME" >"$TMP/win-marker-install.log" 2>&1
check "marker: a hand-written ~/bin/corral naming dev.corral.CorralCli SURVIVES (the FQN-only marker deleted it)" bash -c \
  "[ -f '$WIN_MARK_HOME/bin/corral' ] && [ \"\$(sha256sum '$WIN_MARK_HOME/bin/corral' | cut -d' ' -f1)\" = '$MARK_SHA' ]"
check "marker: the install reports it under 'Left alone'" bash -c \
  "grep -q 'Left alone' '$TMP/win-marker-install.log' && grep -qF '$WIN_MARK_HOME/bin/corral' '$TMP/win-marker-install.log'"

# ===== the printed start commands survive a real path (spaces + `&` + `'`); NIT 7 =====
# These two lines are the operator's only way to start the daemon with CORRAL_ADB on Windows, so
# their quoting has to hold for a real SDK path. The sandbox below puts the SDK under a directory
# whose name has a space, an `&` AND a `'`; the test then feeds the assignment from each printed
# line to the REAL shells on this host (cmd.exe through its command-line parser, PowerShell through
# -Command -) and requires the value that lands in the environment to be byte-exact. The SDK sits
# at the CLASSIC path on purpose: the row then isolates the quoting from adb detection, so it probes
# exactly the fix and not the Windows-location lookup the rows above already cover.
WIN_ESC_HOME="$TMP/home win's&esc"; WIN_ESC_ADB="$WIN_ESC_HOME/Android/Sdk/platform-tools/adb.exe"
mkdir -p "$WIN_ESC_HOME/Android/Sdk/platform-tools" "$WIN_ESC_HOME/bin"; : > "$WIN_ESC_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "esc-adb $@"' > "$WIN_ESC_ADB"; chmod +x "$WIN_ESC_ADB"
env HOME="$TMPHOME" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --jar "$JAR" --no-ui --home "$WIN_ESC_HOME" >"$TMP/win-escape.log" 2>&1; rce=$?
dump_on_fail "$rce" "$TMP/win-escape.log"
check "escape: install with a space, an & and a ' in the SDK path exits 0" test "$rce" -eq 0
check "escape: adb is resolved from that path" grep -qF "Using adb: $WIN_ESC_ADB" "$TMP/win-escape.log"

# Read the assignment out of each printed line with shell builtins: a regex containing a `"` is
# mangled on the way to grep on a real Windows host (see the note on win_cmd_adb above).
win_cmd_assign() { # win_cmd_assign <log> -> the whole `set "CORRAL_ADB=..."` statement from the cmd.exe line
  local line v
  while IFS= read -r line; do
    case "$line" in
      '  cmd.exe     set "CORRAL_ADB='*)
        v=${line#"  cmd.exe     "}      # strip the printed prompt label, keep the statement
        printf '%s\n' "${v%% && *}"     # drop the ` && "java" ...` tail
        return 0;;
    esac
  done < "$1"
}
win_ps_assign() { # win_ps_assign <log> -> the `$env:CORRAL_ADB=...` assignment the PowerShell line uses
  local line v
  while IFS= read -r line; do
    case "$line" in
      '  PowerShell  '*"CORRAL_ADB="*'; & '*)
        v=${line#*CORRAL_ADB=}
        printf '%s\n' "\$env:CORRAL_ADB=${v%%; & *}"
        return 0;;
    esac
  done < "$1"
}
CMD_ASSIGN=$(win_cmd_assign "$TMP/win-escape.log")
PS_ASSIGN=$(win_ps_assign "$TMP/win-escape.log")
check "escape: a cmd.exe line is printed for that path" test -n "$CMD_ASSIGN"
check "escape: a PowerShell line is printed for that path" test -n "$PS_ASSIGN"
check "escape: the PowerShell paths are single-quoted, so a \$ in a path cannot expand" bash -c \
  "grep -qF \"; & '\" '$TMP/win-escape.log' && grep -q \"\\-jar '\" '$TMP/win-escape.log'"

# WIN_ESC_EXP is the same spelling the installer printed (win_path == cygpath -w here); on a host
# without cygpath both sides stay POSIX.
WIN_ESC_EXP=$(cygpath -w "$WIN_ESC_ADB" 2>/dev/null || printf '%s' "$WIN_ESC_ADB")
if command -v cmd.exe >/dev/null 2>&1; then
  cmd_env_value() { # cmd_env_value <set-statement> -> what a real cmd.exe stores, read back
    printf '%s\r\nset CORRAL_ADB\r\n' "$1" | cmd.exe 2>/dev/null | tr -d '\r' | while IFS= read -r l; do
      case "$l" in CORRAL_ADB=*) printf '%s\n' "${l#CORRAL_ADB=}";; esac
    done
  }
  check "escape: a REAL cmd.exe stores the exact path (spaces, & and ' intact)" \
    same_value "$(cmd_env_value "$CMD_ASSIGN")" "$WIN_ESC_EXP"
else
  echo "skip: the cmd.exe round-trip needs a real Windows host (no cmd.exe on PATH here)"
fi
if command -v powershell.exe >/dev/null 2>&1; then
  ps_env_value() { # ps_env_value <assignment> -> what a real PowerShell stores, read back
    printf '%s\n%s\n' "$1" '$env:CORRAL_ADB' | powershell.exe -NoProfile -Command - 2>/dev/null | tr -d '\r'
  }
  check "escape: a REAL PowerShell stores the exact path ('' round-trips, & and spaces intact)" \
    same_value "$(ps_env_value "$PS_ASSIGN")" "$WIN_ESC_EXP"
else
  echo "skip: the PowerShell round-trip needs a real Windows host (no powershell.exe on PATH here)"
fi

# A path cmd.exe genuinely cannot spell (`%NAME%` is expanded before it looks at the quotes; there
# is no escape) must be SAID, not printed as a command that silently sets a different value.
WIN_UNS_HOME="$TMP/home win%TEMP%x"; WIN_UNS_ADB="$WIN_UNS_HOME/Android/Sdk/platform-tools/adb.exe"
mkdir -p "$WIN_UNS_HOME/Android/Sdk/platform-tools" "$WIN_UNS_HOME/bin"; : > "$WIN_UNS_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "uns-adb $@"' > "$WIN_UNS_ADB"; chmod +x "$WIN_UNS_ADB"
env HOME="$TMPHOME" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --jar "$JAR" --no-ui --home "$WIN_UNS_HOME" >"$TMP/win-unspellable.log" 2>&1; rcu2=$?
dump_on_fail "$rcu2" "$TMP/win-unspellable.log"
check "escape: a %NAME% path prints WHY there is no cmd.exe line instead of a broken one" bash -c \
  "[ '$rcu2' -eq 0 ] && ! grep -q '^  cmd.exe     set' '$TMP/win-unspellable.log' && grep -q 'no line above' '$TMP/win-unspellable.log'"
check "escape: the PowerShell line is still printed for that path" \
  bash -c "grep -q '^  PowerShell  \$env:CORRAL_ADB=' '$TMP/win-unspellable.log'"

# ===== usage() documents where adb is looked for (NIT 6) =====
check "usage documents the Windows SDK location for --adb" bash -c \
  "bash '$SRC/install.sh' --help | grep -q 'AppData/Local/Android/Sdk'"

# ===== Windows: durable daemon via a per-user Scheduled Task =====
# Windows has no systemd. install.sh writes ~/bin/corral-daemon.bat (carrying CORRAL_ADB) and
# registers CorralDaemon, trying /sc onlogon and falling back to /sc once + /run when the token is
# UAC-filtered. schtasks is stubbed here: only a real Windows box can prove the task really
# survives a logout, so these rows pin the install's commands, claims and fallback, not the OS.
winp() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }

# Write a realistic `schtasks /query /tn CorralDaemon /fo LIST /v` dump (CRLF, space-padded
# values) for a schtasks stub to cat. Values captured from a medium-integrity token on the box.
win_query_dump() { # <file> <status> <last-run-time> <task-to-run> <schedule-type>
  {
    printf 'Folder: \\\n'
    printf 'HostName:                             WIN-G86E4BG8RLB\n'
    printf 'TaskName:                             \\CorralDaemon\n'
    printf 'Status:                               %s\n' "$2"
    printf 'Last Run Time:                        %s\n' "$3"
    printf 'Task To Run:                          %s\n' "$4"
    printf 'Schedule Type:                        %s\n' "$5"
  } | sed 's/$/\r/' > "$1"
}

# (a) happy path: onlogon works, task created, launcher carries CORRAL_ADB.
TASK_A_HOME="$TMP/home-task-a"; TASK_A_SDK="$TASK_A_HOME/Android/Sdk/platform-tools"
TASK_A_STUB="$TMP/taskstub-a"; TASK_A_LOG="$TMP/taskstub-a.log"
mkdir -p "$TASK_A_SDK" "$TASK_A_HOME/bin" "$TASK_A_STUB"; : > "$TASK_A_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-a-adb $@"' > "$TASK_A_SDK/adb"; chmod +x "$TASK_A_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_A_STUB/uname"
cat > "$TASK_A_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_A_LOG"
echo 'SUCCESS: The scheduled task "CorralDaemon" has successfully been created.'
exit 0
EOF
chmod +x "$TASK_A_STUB/uname" "$TASK_A_STUB/schtasks"
cp "$TMP/powershell-stub.sh" "$TASK_A_STUB/powershell.exe"; chmod +x "$TASK_A_STUB/powershell.exe"
env HOME="$TASK_A_HOME" PATH="$TASK_A_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_A_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_A_HOME" >"$TMP/task-a.log" 2>&1; rcta=$?
dump_on_fail "$rcta" "$TMP/task-a.log"
TASK_A_BAT="$TASK_A_HOME/bin/corral-daemon.bat"
check "task: Windows install exits 0" test "$rcta" -eq 0
check "task: ~/bin/corral-daemon.bat carries CORRAL_ADB (the adb the installer resolved)" \
  grep -qF "set \"CORRAL_ADB=$(winp "$TASK_A_SDK/adb")\"" "$TASK_A_BAT"
check "task: the launcher runs the daemon jar with the tuned JVM flags" bash -c \
  "grep -qF -- '-Xmx64m' '$TASK_A_BAT' && grep -qF -- 'corral-daemon-all.jar' '$TASK_A_BAT'"
check "task: /create registers CorralDaemon for onlogon with the .bat as /tr" \
  grep -qF -- "/create /tn CorralDaemon /tr $(winp "$TASK_A_BAT") /sc onlogon /rl LIMITED /f" "$TASK_A_LOG"
check "task: summary reports the task created and auto-starting" \
  grep -q 'Scheduled Task CorralDaemon created; auto-starts at logon' "$TMP/task-a.log"

# (b) the onlogon create is refused (UAC-filtered token): /sc once + /run must run instead,
#     and the operator must be given the exact elevated command for real auto-start.
TASK_B_HOME="$TMP/home-task-b"; TASK_B_SDK="$TASK_B_HOME/Android/Sdk/platform-tools"
TASK_B_STUB="$TMP/taskstub-b"; TASK_B_LOG="$TMP/taskstub-b.log"
mkdir -p "$TASK_B_SDK" "$TASK_B_HOME/bin" "$TASK_B_STUB"; : > "$TASK_B_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-b-adb $@"' > "$TASK_B_SDK/adb"; chmod +x "$TASK_B_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_B_STUB/uname"
cat > "$TASK_B_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_B_LOG"
case " \$* " in
  *" /sc onlogon "*) echo "ERROR: Access is denied." >&2; exit 5;;
  *) echo 'SUCCESS: The scheduled task "CorralDaemon" has successfully been created.'; exit 0;;
esac
EOF
chmod +x "$TASK_B_STUB/uname" "$TASK_B_STUB/schtasks"
env HOME="$TASK_B_HOME" PATH="$TASK_B_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_B_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_B_HOME" >"$TMP/task-b.log" 2>&1; rctb=$?
dump_on_fail "$rctb" "$TMP/task-b.log"
TASK_B_BAT="$TASK_B_HOME/bin/corral-daemon.bat"
TASK_B_ELEV="schtasks /create /tn CorralDaemon /tr \"$(winp "$TASK_B_BAT")\" /sc onlogon /rl LIMITED /f"
check "task fallback: install exits 0" test "$rctb" -eq 0
check "task fallback: a /sc once create runs when onlogon is access-denied" \
  grep -qF -- "/create /tn CorralDaemon /tr $(winp "$TASK_B_BAT") /sc once /st 00:00 /rl LIMITED /f" "$TASK_B_LOG"
check "task fallback: schtasks /run starts the once task now" \
  grep -qF -- "/run /tn CorralDaemon" "$TASK_B_LOG"
check "task fallback: the exact elevated command for auto-start is PRINTED" \
  grep -qF -- "$TASK_B_ELEV" "$TMP/task-b.log"
check "task fallback: summary says NOT auto-starting" \
  grep -q 'NOT auto-starting' "$TMP/task-b.log"

# (b2) the task already exists and was created by an elevated token, so BOTH creates are refused
#      with "Access is denied.". The refusal proves only that this session cannot write: the task
#      must be READ BACK before the summary claims it exists, and its Status decides whether a
#      daemon may already be running. The stub answers /query in the real /fo LIST /v shape
#      (captured from a medium-integrity token on the Windows box; CRLF, padded values).
TASK_I_HOME="$TMP/home-task-i"; TASK_I_SDK="$TASK_I_HOME/Android/Sdk/platform-tools"
TASK_I_STUB="$TMP/taskstub-i"; TASK_I_LOG="$TMP/taskstub-i.log"; TASK_I_Q="$TMP/task-i-query.out"
mkdir -p "$TASK_I_SDK" "$TASK_I_HOME/bin" "$TASK_I_STUB"; : > "$TASK_I_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-i-adb $@"' > "$TASK_I_SDK/adb"; chmod +x "$TASK_I_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_I_STUB/uname"
win_query_dump "$TASK_I_Q" Running '10/19/2026 7:00:03 PM' 'C:\Users\ciszk\bin\corral-daemon.bat' 'One Time Only'
cat > "$TASK_I_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_I_LOG"
case " \$* " in
  *" /create "*) echo "ERROR: Access is denied." >&2; exit 5;;
  *" /query "*) cat "$TASK_I_Q"; exit 0;;
  *) exit 0;;
esac
EOF
chmod +x "$TASK_I_STUB/uname" "$TASK_I_STUB/schtasks"
env HOME="$TASK_I_HOME" PATH="$TASK_I_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_I_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_I_HOME" >"$TMP/task-i.log" 2>&1; rcti=$?
dump_on_fail "$rcti" "$TMP/task-i.log"
TASK_I_BAT="$TASK_I_HOME/bin/corral-daemon.bat"
check "task access-denied: install exits 0 with both creates attempted" bash -c \
  "[ '$rcti' -eq 0 ] && grep -qF -- '/sc onlogon' '$TASK_I_LOG' && grep -qF -- '/sc once' '$TASK_I_LOG'"
check "task access-denied: the task is READ BACK after both refusals (before and after)" bash -c \
  "[ \"\$(grep -cF -- '/query /tn CorralDaemon /fo LIST /v' '$TASK_I_LOG')\" -ge 2 ]"
check "task access-denied: the summary claims existence only from the read, with the real Status" bash -c \
  "grep -q 'A Scheduled Task CorralDaemon EXISTS (confirmed by schtasks /query; Status: Running)' '$TMP/task-i.log'"
check "task access-denied: the old unverified 'ALREADY EXISTS' / 'daemon was NOT started' wording is gone" bash -c \
  "! grep -q 'ALREADY EXISTS' '$TMP/task-i.log' && ! grep -q 'The daemon was NOT started' '$TMP/task-i.log' && ! grep -q 'was NOT created (schtasks failed)' '$TMP/task-i.log'"
check "task access-denied: it says what it did not do, and that a daemon may already be running" bash -c \
  "grep -q 'both /create attempts were refused (schtasks: Access is denied)' '$TMP/task-i.log' && grep -q 'Status Running means a daemon may already be serving from it; this run did not start it' '$TMP/task-i.log'"
check "task access-denied: 'left untouched' is a compared before/after claim" \
  grep -q 'The task was left untouched: Task To Run, Schedule Type, Status and Last Run Time are the same before and after' "$TMP/task-i.log"
check "task access-denied: the elevated remove and create commands are PRINTED" bash -c \
  "grep -qF 'schtasks /delete /tn CorralDaemon /f' '$TMP/task-i.log' && grep -qF 'schtasks /create /tn CorralDaemon /tr \"$(winp "$TASK_I_BAT")\" /sc onlogon /rl LIMITED /f' '$TMP/task-i.log'"

# (b2b) both creates refused AND the task cannot be read back: the summary must not invent an
#       existing task. It names the refusal and the failed read, and asks the operator to look.
TASK_L_HOME="$TMP/home-task-l"; TASK_L_SDK="$TASK_L_HOME/Android/Sdk/platform-tools"
TASK_L_STUB="$TMP/taskstub-l"; TASK_L_LOG="$TMP/taskstub-l.log"
mkdir -p "$TASK_L_SDK" "$TASK_L_HOME/bin" "$TASK_L_STUB"; : > "$TASK_L_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-l-adb $@"' > "$TASK_L_SDK/adb"; chmod +x "$TASK_L_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_L_STUB/uname"
cat > "$TASK_L_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_L_LOG"
case " \$* " in
  *" /create "*) echo "ERROR: Access is denied." >&2; exit 5;;
  *" /query "*) echo "ERROR: The system cannot find the file specified." >&2; exit 1;;
  *) exit 0;;
esac
EOF
chmod +x "$TASK_L_STUB/uname" "$TASK_L_STUB/schtasks"
env HOME="$TASK_L_HOME" PATH="$TASK_L_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_L_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_L_HOME" >"$TMP/task-l.log" 2>&1; rctl=$?
dump_on_fail "$rctl" "$TMP/task-l.log"
check "task access-denied+unreadable: /query is attempted and the install still exits 0" bash -c \
  "[ '$rctl' -eq 0 ] && grep -qF -- '/query /tn CorralDaemon /fo LIST /v' '$TASK_L_LOG'"
check "task access-denied+unreadable: no task IS claimed, and the failed read is named" bash -c \
  "grep -q 'could not be read back' '$TMP/task-l.log' && grep -q 'cannot say whether CorralDaemon exists' '$TMP/task-l.log' && ! grep -q 'EXISTS' '$TMP/task-l.log'"
check "task access-denied+unreadable: the delete command (which needs a known task) is NOT printed" bash -c \
  "! grep -qF 'schtasks /delete /tn CorralDaemon /f' '$TMP/task-l.log'"

# (b2c) the task's fields CHANGE between the two reads (its owner re-registered it mid-run): the
#       summary must say it cannot claim the task was untouched, and must not print the untouched
#       sentence. The second read is Ready rather than Running — the "not Running" sentence.
TASK_M_HOME="$TMP/home-task-m"; TASK_M_SDK="$TASK_M_HOME/Android/Sdk/platform-tools"
TASK_M_STUB="$TMP/taskstub-m"; TASK_M_LOG="$TMP/taskstub-m.log"
TASK_M_Q1="$TMP/task-m-query-1.out"; TASK_M_Q2="$TMP/task-m-query-2.out"; TASK_M_N="$TMP/task-m-query-count"
mkdir -p "$TASK_M_SDK" "$TASK_M_HOME/bin" "$TASK_M_STUB"; : > "$TASK_M_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-m-adb $@"' > "$TASK_M_SDK/adb"; chmod +x "$TASK_M_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_M_STUB/uname"
win_query_dump "$TASK_M_Q1" Running '10/19/2026 7:00:03 PM' 'C:\Users\ciszk\bin\corral-daemon.bat' 'One Time Only'
win_query_dump "$TASK_M_Q2" Ready   '10/20/2026 1:05:11 PM' 'cmd /c exit 0' 'At logon time'
cat > "$TASK_M_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_M_LOG"
n=\$(cat "$TASK_M_N" 2>/dev/null || echo 0); n=\$((n+1)); printf '%s\n' "\$n" > "$TASK_M_N"
case " \$* " in
  *" /create "*) echo "ERROR: Access is denied." >&2; exit 5;;
  *" /query "*) if [ "\$n" -eq 1 ]; then cat "$TASK_M_Q1"; else cat "$TASK_M_Q2"; fi; exit 0;;
  *) exit 0;;
esac
EOF
chmod +x "$TASK_M_STUB/uname" "$TASK_M_STUB/schtasks"
env HOME="$TASK_M_HOME" PATH="$TASK_M_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_M_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_M_HOME" >"$TMP/task-m.log" 2>&1; rctm=$?
dump_on_fail "$rctm" "$TMP/task-m.log"
check "task changed: the summary WARNS, and never claims the task was left untouched" bash -c \
  "[ '$rctm' -eq 0 ] && grep -q 'WARNING: its Task To Run / Schedule Type / Status / Last Run Time changed between the two reads' '$TMP/task-m.log' && ! grep -q 'The task was left untouched' '$TMP/task-m.log'"
check "task changed: the AFTER read's non-Running Status gets the 'not Running' sentence" bash -c \
  "grep -q 'EXISTS (confirmed by schtasks /query; Status: Ready)' '$TMP/task-m.log' && grep -q 'The task is not Running now, and this run did not start it' '$TMP/task-m.log' && ! grep -q 'Status Running means' '$TMP/task-m.log'"

# (b2d) the BEFORE read fails but the AFTER read succeeds: existence is claimable, "left untouched"
#       is not. The two claims come from different reads and must not be conflated.
TASK_N_HOME="$TMP/home-task-n"; TASK_N_SDK="$TASK_N_HOME/Android/Sdk/platform-tools"
TASK_N_STUB="$TMP/taskstub-n"; TASK_N_LOG="$TMP/taskstub-n.log"
TASK_N_Q="$TMP/task-n-query.out"; TASK_N_N="$TMP/task-n-query-count"
mkdir -p "$TASK_N_SDK" "$TASK_N_HOME/bin" "$TASK_N_STUB"; : > "$TASK_N_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-n-adb $@"' > "$TASK_N_SDK/adb"; chmod +x "$TASK_N_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_N_STUB/uname"
win_query_dump "$TASK_N_Q" Ready '10/19/2026 7:00:03 PM' 'C:\Users\ciszk\bin\corral-daemon.bat' 'One Time Only'
cat > "$TASK_N_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_N_LOG"
n=\$(cat "$TASK_N_N" 2>/dev/null || echo 0); n=\$((n+1)); printf '%s\n' "\$n" > "$TASK_N_N"
case " \$* " in
  *" /create "*) echo "ERROR: Access is denied." >&2; exit 5;;
  *" /query "*)
    if [ "\$n" -eq 1 ]; then echo "ERROR: The system cannot find the file specified." >&2; exit 1; fi
    cat "$TASK_N_Q"; exit 0;;
  *) exit 0;;
esac
EOF
chmod +x "$TASK_N_STUB/uname" "$TASK_N_STUB/schtasks"
env HOME="$TASK_N_HOME" PATH="$TASK_N_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_N_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_N_HOME" >"$TMP/task-n.log" 2>&1; rctn=$?
dump_on_fail "$rctn" "$TMP/task-n.log"
check "task unreadable-before: existence is still claimed from the successful AFTER read" bash -c \
  "[ '$rctn' -eq 0 ] && grep -q 'EXISTS (confirmed by schtasks /query; Status: Ready)' '$TMP/task-n.log'"
check "task unreadable-before: without a before read it does NOT claim the task was left untouched" bash -c \
  "grep -q 'The task could not be read before the attempt, so this run does not claim it was left untouched' '$TMP/task-n.log' && ! grep -q 'The task was left untouched' '$TMP/task-n.log'"

# (b2e) the onlogon create is refused but the /sc once fallback fails for a DIFFERENT reason: "both
#       attempts were refused with Access is denied" would be a claim about output nobody looked
#       at. The wording must say which refusal was seen and that the fallback failed otherwise.
TASK_O_HOME="$TMP/home-task-o"; TASK_O_SDK="$TASK_O_HOME/Android/Sdk/platform-tools"
TASK_O_STUB="$TMP/taskstub-o"; TASK_O_LOG="$TMP/taskstub-o.log"; TASK_O_Q="$TMP/task-o-query.out"
mkdir -p "$TASK_O_SDK" "$TASK_O_HOME/bin" "$TASK_O_STUB"; : > "$TASK_O_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-o-adb $@"' > "$TASK_O_SDK/adb"; chmod +x "$TASK_O_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_O_STUB/uname"
win_query_dump "$TASK_O_Q" Ready '10/19/2026 7:00:03 PM' 'C:\Users\ciszk\bin\corral-daemon.bat' 'One Time Only'
cat > "$TASK_O_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_O_LOG"
case " \$* " in
  *" /create "*)
    case " \$* " in
      *" /sc onlogon "*) echo "ERROR: Access is denied." >&2; exit 5;;
      *) echo "ERROR: The parameter is incorrect." >&2; exit 1;;
    esac;;
  *" /query "*) cat "$TASK_O_Q"; exit 0;;
  *) exit 0;;
esac
EOF
chmod +x "$TASK_O_STUB/uname" "$TASK_O_STUB/schtasks"
env HOME="$TASK_O_HOME" PATH="$TASK_O_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_O_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_O_HOME" >"$TMP/task-o.log" 2>&1; rcto=$?
dump_on_fail "$rcto" "$TMP/task-o.log"
check "task one refusal: a fallback failure that is NOT a denial is not reported as one" bash -c \
  "[ '$rcto' -eq 0 ] && grep -q 'the /sc once fallback failed too, without an access-denied refusal' '$TMP/task-o.log' && ! grep -q 'both /create attempts were refused' '$TMP/task-o.log'"
check "task one refusal: the refusal that WAS observed is still named" bash -c \
  "grep -q 'the /sc onlogon create was refused (schtasks: Access is denied)' '$TMP/task-o.log' && grep -q 'The task was left untouched' '$TMP/task-o.log'"

# (b3) a create failure that is NOT access-denied: the specific "task exists" advice must not appear;
#      the generic wording stays, because nothing says an existing task could not be modified.
TASK_J_HOME="$TMP/home-task-j"; TASK_J_SDK="$TASK_J_HOME/Android/Sdk/platform-tools"
TASK_J_STUB="$TMP/taskstub-j"; TASK_J_LOG="$TMP/taskstub-j.log"
mkdir -p "$TASK_J_SDK" "$TASK_J_HOME/bin" "$TASK_J_STUB"; : > "$TASK_J_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-j-adb $@"' > "$TASK_J_SDK/adb"; chmod +x "$TASK_J_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_J_STUB/uname"
cat > "$TASK_J_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_J_LOG"
case " \$* " in
  *" /create "*) echo "ERROR: something else" >&2; exit 1;;
  *) exit 0;;
esac
EOF
chmod +x "$TASK_J_STUB/uname" "$TASK_J_STUB/schtasks"
env HOME="$TASK_J_HOME" PATH="$TASK_J_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_J_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_J_HOME" >"$TMP/task-j.log" 2>&1; rctj=$?
dump_on_fail "$rctj" "$TMP/task-j.log"
check "task non-access failure: install exits 0 with the generic wording" bash -c \
  "[ '$rctj' -eq 0 ] && grep -q 'Scheduled Task CorralDaemon was NOT created (schtasks failed)' '$TMP/task-j.log'"
check "task non-access failure: the access-denied advice (existing task, delete) is NOT printed" bash -c \
  "! grep -q 'ALREADY EXISTS' '$TMP/task-j.log' && ! grep -qF 'schtasks /delete /tn CorralDaemon /f' '$TMP/task-j.log'"

# (c) schtasks exits 0 but prints an error: that is FAILURE (exit code AND output), and the
#     summary must not claim a durable daemon.
TASK_C_HOME="$TMP/home-task-c"; TASK_C_SDK="$TASK_C_HOME/Android/Sdk/platform-tools"
TASK_C_STUB="$TMP/taskstub-c"; TASK_C_LOG="$TMP/taskstub-c.log"
mkdir -p "$TASK_C_SDK" "$TASK_C_HOME/bin" "$TASK_C_STUB"; : > "$TASK_C_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-c-adb $@"' > "$TASK_C_SDK/adb"; chmod +x "$TASK_C_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_C_STUB/uname"
cat > "$TASK_C_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_C_LOG"
case " \$* " in
  *" /create "*) echo "ERROR: The parameter is incorrect." >&2; exit 0;;
  *) exit 0;;
esac
EOF
chmod +x "$TASK_C_STUB/uname" "$TASK_C_STUB/schtasks"
env HOME="$TASK_C_HOME" PATH="$TASK_C_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_C_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_C_HOME" >"$TMP/task-c.log" 2>&1; rctc=$?
dump_on_fail "$rctc" "$TMP/task-c.log"
check "task error-line: install exits 0" test "$rctc" -eq 0
check "task error-line: exit 0 + an error line is treated as task creation FAILURE" \
  grep -q 'Scheduled Task CorralDaemon was NOT created' "$TMP/task-c.log"
check "task error-line: the summary does NOT claim a durable daemon" bash -c \
  "grep -q 'daemon was NOT started' '$TMP/task-c.log' && ! grep -q 'auto-starts at logon' '$TMP/task-c.log' && ! grep -q 'created and running' '$TMP/task-c.log'"

# (c2) a path that literally contains a space-delimited "error" segment must not be mistaken for a
#      schtasks error report: task_ok anchors error/failed to the START of a line, which a path can
#      never be. Before the narrowing this reported a successful create as a failure and skipped /run.
TASK_D_HOME="$TMP/home qa error home"; TASK_D_SDK="$TASK_D_HOME/Android/Sdk/platform-tools"
TASK_D_STUB="$TMP/taskstub-d"; TASK_D_LOG="$TMP/taskstub-d.log"
mkdir -p "$TASK_D_SDK" "$TASK_D_HOME/bin" "$TASK_D_STUB"; : > "$TASK_D_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-d-adb $@"' > "$TASK_D_SDK/adb"; chmod +x "$TASK_D_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_D_STUB/uname"
cat > "$TASK_D_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_D_LOG"
printf 'SUCCESS: %s\n' "\$*"
exit 0
EOF
chmod +x "$TASK_D_STUB/uname" "$TASK_D_STUB/schtasks"
env HOME="$TASK_D_HOME" PATH="$TASK_D_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_D_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_D_HOME" >"$TMP/task-d.log" 2>&1; rctd=$?
dump_on_fail "$rctd" "$TMP/task-d.log"
check "task path: an 'error' path segment does not turn a successful create into a failure" bash -c \
  "[ '$rctd' -eq 0 ] && grep -q 'Scheduled Task CorralDaemon created; auto-starts at logon and is running now' '$TMP/task-d.log'"
check "task path: /create and /run are both attempted for that path" bash -c \
  "grep -qF -- '/create /tn CorralDaemon' '$TASK_D_LOG' && grep -qF -- '/run /tn CorralDaemon' '$TASK_D_LOG'"

# (d) uninstall deletes the task and the launcher, and names both in the Removed: summary.
# The daemon java survives /end on a real box; the stub table says ours is running so the new
# verification-and-stop path is what produces the "(daemon stopped)" claim.
TASK_A_BAT_BEFORE=0; [ -f "$TASK_A_BAT" ] && TASK_A_BAT_BEFORE=1
TASK_A_PROCS="$TMP/win-procs-a"
printf '%s\n' "111|java.exe -jar $(winp "$TASK_A_HOME/.corral/corral-daemon-all.jar")" > "$TASK_A_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$TASK_A_HOME" PATH="$TASK_A_STUB:$STUB:$PATH" PS_TABLE="$TASK_A_PROCS" bash "$SRC/install.sh" --home "$TASK_A_HOME" --uninstall \
  >"$TMP/task-uninstall.log" 2>&1; rctu=$?
dump_on_fail "$rctu" "$TMP/task-uninstall.log"
check "task uninstall: exits 0" test "$rctu" -eq 0
check "task uninstall: /end runs BEFORE /delete (a deleted task cannot be ended)" bash -c \
  "awk '/\\/end \\/tn CorralDaemon/{e=NR} /\\/delete \\/tn CorralDaemon/{d=NR} END{exit !(e && d && e < d)}' '$TASK_A_LOG'"
check "task uninstall: the Scheduled Task is deleted" \
  grep -qF -- "/delete /tn CorralDaemon /f" "$TASK_A_LOG"
check "task uninstall: ~/bin/corral-daemon.bat (written by the install) is removed" bash -c \
  "[ '$TASK_A_BAT_BEFORE' = 1 ] && [ ! -e '$TASK_A_BAT' ]"
check "task uninstall: Removed: names the task and the .bat" bash -c \
  "line=\$(grep '^Removed:' '$TMP/task-uninstall.log'); printf '%s' \"\$line\" | grep -qF 'Scheduled Task CorralDaemon' && printf '%s' \"\$line\" | grep -qF '~/bin/corral-daemon.bat'"
check "task uninstall: Removed: names the stopped daemon when the process is verified gone" bash -c \
  "grep '^Removed:' '$TMP/task-uninstall.log' | grep -qF 'Scheduled Task CorralDaemon (daemon stopped)'"
check "task uninstall: the daemon java is stopped by PID after /end, and the table is empty" bash -c \
  "[ \"\$(cat '$TMP/powershell-killed')\" = 111 ] && [ ! -s '$TASK_A_PROCS' ]"

# (e) a box that never had the task: schtasks /delete fails, uninstall still exits 0 and does not
#     claim it removed a task. The log assertion is what makes these rows fail on the old installer,
#     which never called schtasks on uninstall at all.
TASK_E_HOME="$TMP/home-task-e"; TASK_E_STUB="$TMP/taskstub-e"; TASK_E_LOG="$TMP/taskstub-e.log"
mkdir -p "$TASK_E_HOME/bin" "$TASK_E_STUB"; : > "$TASK_E_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_E_STUB/uname"
cat > "$TASK_E_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_E_LOG"
case " \$* " in
  *" /delete "*) echo "ERROR: The system cannot find the task." >&2; exit 1;;
  *) exit 0;;
esac
EOF
chmod +x "$TASK_E_STUB/uname" "$TASK_E_STUB/schtasks"
env HOME="$TASK_E_HOME" PATH="$TASK_E_STUB:$STUB:$PATH" bash "$SRC/install.sh" --home "$TASK_E_HOME" --uninstall \
  >"$TMP/task-e-uninstall.log" 2>&1; rcte=$?
dump_on_fail "$rcte" "$TMP/task-e-uninstall.log"
check "task uninstall: no task present still exits 0 and /delete is attempted" bash -c \
  "[ '$rcte' -eq 0 ] && grep -qF -- '/delete /tn CorralDaemon /f' '$TASK_E_LOG'"
check "task uninstall: no task present is not claimed as removed" bash -c \
  "grep -qF -- '/delete /tn CorralDaemon /f' '$TASK_E_LOG' && ! grep '^Removed:' '$TMP/task-e-uninstall.log' | grep -q 'Scheduled Task CorralDaemon'"

# (e2) the task is deleted but /end fails (e.g. it was not running): the uninstall still exits 0,
#     reports the task removed, and must NOT claim the daemon was stopped.
TASK_G_HOME="$TMP/home-task-g"; TASK_G_STUB="$TMP/taskstub-g"; TASK_G_LOG="$TMP/taskstub-g.log"
mkdir -p "$TASK_G_HOME/bin" "$TASK_G_STUB"; : > "$TASK_G_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_G_STUB/uname"
cat > "$TASK_G_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_G_LOG"
case " \$* " in
  *" /end "*) echo "ERROR: The task is not running." >&2; exit 1;;
  *) echo 'SUCCESS: The scheduled task "CorralDaemon" has successfully been deleted.'; exit 0;;
esac
EOF
chmod +x "$TASK_G_STUB/uname" "$TASK_G_STUB/schtasks"
env HOME="$TASK_G_HOME" PATH="$TASK_G_STUB:$STUB:$PATH" bash "$SRC/install.sh" --home "$TASK_G_HOME" --uninstall \
  >"$TMP/task-g-uninstall.log" 2>&1; rctg=$?
dump_on_fail "$rctg" "$TMP/task-g-uninstall.log"
check "task uninstall: a failed /end still exits 0 and /delete is attempted" bash -c \
  "[ '$rctg' -eq 0 ] && grep -qF -- '/delete /tn CorralDaemon /f' '$TASK_G_LOG'"
check "task uninstall: a failed /end does not claim the daemon was stopped" bash -c \
  "line=\$(grep '^Removed:' '$TMP/task-g-uninstall.log'); printf '%s' \"\$line\" | grep -qF 'Scheduled Task CorralDaemon' && ! printf '%s' \"\$line\" | grep -q 'daemon stopped'"

# (e3) schtasks exits 0 but prints the error on STDERR: /end must capture both streams, or the
#     summary claims a stop that never happened. The old installer's `2>/dev/null` lost this line.
TASK_H_HOME="$TMP/home-task-h"; TASK_H_STUB="$TMP/taskstub-h"; TASK_H_LOG="$TMP/taskstub-h.log"
mkdir -p "$TASK_H_HOME/bin" "$TASK_H_STUB"; : > "$TASK_H_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_H_STUB/uname"
cat > "$TASK_H_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_H_LOG"
case " \$* " in
  *" /end "*) echo "ERROR: Access is denied." >&2; exit 0;;
  *) echo 'SUCCESS: The scheduled task "CorralDaemon" has successfully been deleted.'; exit 0;;
esac
EOF
chmod +x "$TASK_H_STUB/uname" "$TASK_H_STUB/schtasks"
env HOME="$TASK_H_HOME" PATH="$TASK_H_STUB:$STUB:$PATH" bash "$SRC/install.sh" --home "$TASK_H_HOME" --uninstall \
  >"$TMP/task-h-uninstall.log" 2>&1; rcth=$?
dump_on_fail "$rcth" "$TMP/task-h-uninstall.log"
check "task uninstall: a /end error on STDERR with exit 0 still exits 0 and /delete is attempted" bash -c \
  "[ '$rcth' -eq 0 ] && grep -qF -- '/delete /tn CorralDaemon /f' '$TASK_H_LOG'"
check "task uninstall: a /end error on STDERR with exit 0 is NOT claimed as a stopped daemon" bash -c \
  "line=\$(grep '^Removed:' '$TMP/task-h-uninstall.log'); printf '%s' \"\$line\" | grep -qF 'Scheduled Task CorralDaemon' && ! printf '%s' \"\$line\" | grep -q 'daemon stopped'"

# (f) POSIX is untouched: no Scheduled Task call and no daemon .bat.
POSIX_T_HOME="$TMP/home-posix-task"; POSIX_T_SDK="$POSIX_T_HOME/Android/Sdk/platform-tools"
POSIX_T_STUB="$TMP/posix-task-stub"; POSIX_T_LOG="$TMP/posix-schtasks.log"
mkdir -p "$POSIX_T_SDK" "$POSIX_T_HOME/bin" "$POSIX_T_STUB"; : > "$POSIX_T_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "posix-task-adb $@"' > "$POSIX_T_SDK/adb"; chmod +x "$POSIX_T_SDK/adb"
cat > "$POSIX_T_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$POSIX_T_LOG"
exit 0
EOF
: > "$POSIX_T_LOG"
chmod +x "$POSIX_T_STUB/schtasks"
env HOME="$POSIX_T_HOME" PATH="$POSIX_T_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$POSIX_T_SDK/adb" --jar "$JAR" --no-ui --home "$POSIX_T_HOME" >"$TMP/posix-task.log" 2>&1; rcpt=$?
dump_on_fail "$rcpt" "$TMP/posix-task.log"
check "task: a POSIX install creates no task, no daemon .bat, and never calls schtasks" bash -c \
  "[ '$rcpt' -eq 0 ] && [ ! -e '$POSIX_T_HOME/bin/corral-daemon.bat' ] && [ ! -s '$POSIX_T_LOG' ]"

# (f1) a Windows --home run is a SANDBOX too: it must not create, replace or delete the Scheduled
#      Task, and must not print the elevated commands. On a box whose CorralDaemon was registered by
#      a non-elevated token the task is user-owned, so the old unconditional /create SUCCEEDED and
#      repointed the LIVE task at the sandbox launcher. HOME and --home differ here on purpose:
#      that difference is what sets SANDBOX=1.
TASK_K_HOME="$TMP/home-task-k"; TASK_K_SB="$TMP/home-task-k-sandbox"
TASK_K_SDK="$TASK_K_SB/Android/Sdk/platform-tools"
TASK_K_STUB="$TMP/taskstub-k"; TASK_K_LOG="$TMP/taskstub-k.log"
mkdir -p "$TASK_K_SDK" "$TASK_K_SB/bin" "$TASK_K_HOME" "$TASK_K_STUB"; : > "$TASK_K_SB/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-k-adb $@"' > "$TASK_K_SDK/adb"; chmod +x "$TASK_K_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_K_STUB/uname"
cat > "$TASK_K_STUB/schtasks" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >> "$TASK_K_LOG"
echo 'SUCCESS: The scheduled task "CorralDaemon" has successfully been created.'
exit 0
EOF
chmod +x "$TASK_K_STUB/uname" "$TASK_K_STUB/schtasks"
: > "$TASK_K_LOG"
env HOME="$TASK_K_HOME" PATH="$TASK_K_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_K_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_K_SB" >"$TMP/task-k.log" 2>&1; rctk=$?
dump_on_fail "$rctk" "$TMP/task-k.log"
check "task sandbox: a Windows --home install exits 0 and never calls schtasks" bash -c \
  "[ '$rctk' -eq 0 ] && [ ! -s '$TASK_K_LOG' ]"
check "task sandbox: it says no task was created/replaced/deleted and no daemon was started" bash -c \
  "grep -q 'sandbox: no Scheduled Task CorralDaemon was created, replaced or deleted' '$TMP/task-k.log' && grep -q 'This installer started no daemon either' '$TMP/task-k.log'"
check "task sandbox: the elevated schtasks advice is NOT printed (it would name the sandbox launcher)" bash -c \
  "! grep -qF 'schtasks /create' '$TMP/task-k.log' && ! grep -qF 'schtasks /delete' '$TMP/task-k.log' && ! grep -q 'auto-starts at logon' '$TMP/task-k.log'"

# (f2) the launcher write itself fails (ENOSPC here via /dev/full): the summary must name the WRITE
#      failure, not blame an unspellable path, and must still say no task/no daemon.
TASK_F_HOME="$TMP/home-task-f"; TASK_F_SDK="$TASK_F_HOME/Android/Sdk/platform-tools"
TASK_F_STUB="$TMP/taskstub-f"
mkdir -p "$TASK_F_SDK" "$TASK_F_HOME/bin" "$TASK_F_STUB"; : > "$TASK_F_HOME/.bashrc"
printf '%s\n' '#!/bin/sh' 'echo "task-f-adb $@"' > "$TASK_F_SDK/adb"; chmod +x "$TASK_F_SDK/adb"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$TASK_F_STUB/uname"
printf '%s\n' '#!/bin/sh' 'exit 0' > "$TASK_F_STUB/schtasks"
chmod +x "$TASK_F_STUB/uname" "$TASK_F_STUB/schtasks"
ln -s /dev/full "$TASK_F_HOME/bin/corral-daemon.bat"
env HOME="$TASK_F_HOME" PATH="$TASK_F_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
  --adb "$TASK_F_SDK/adb" --jar "$JAR" --no-ui --home "$TASK_F_HOME" >"$TMP/task-f.log" 2>&1; rctf=$?
dump_on_fail "$rctf" "$TMP/task-f.log"
check "task launcher: a failed write exits 0 and names the WRITE failure" bash -c \
  "[ '$rctf' -eq 0 ] && grep -q 'could NOT be written to' '$TMP/task-f.log' && grep -q '(the write failed)' '$TMP/task-f.log'"
check "task launcher: a failed write is not blamed on an unspellable path" bash -c \
  "! grep -q 'cannot spell' '$TMP/task-f.log'"
check "task launcher: a failed write still says no task was created and no daemon started" bash -c \
  "grep -q 'Scheduled Task was NOT created and the daemon was NOT started' '$TMP/task-f.log'"

# ===== Windows uninstall: only claim what was verified =====
# The real-box defect: /end kills the task's cmd.exe, not the java.exe it started, so the daemon
# keeps serving; rm -rf ~/.corral then fails on the held jar/DB ("Device or resource busy"), yet the
# old summary said "daemon stopped" and listed ~/.corral as removed. These rows drive the
# powershell stub's process table and the rm stub to pin what may be claimed.
win_new_home() { # win_new_home <name> -> an installed Windows sandbox printed on stdout
  local name="$1" h="$TMP/home-$1" sdk="$TMP/home-$1/Android/Sdk/platform-tools"
  mkdir -p "$sdk" "$h/bin"; : > "$h/.bashrc"
  printf '%s\n' '#!/bin/sh' "echo '$name-adb \$@'" > "$sdk/adb"; chmod +x "$sdk/adb"
  env HOME="$h" PATH="$WIN_STUB:$STUB:$PATH" bash "$SRC/install.sh" --java "$FAKE_JAVA" \
    --adb "$sdk/adb" --jar "$JAR" --no-ui --home "$h" >"$TMP/$name-install.log" 2>&1
  printf '%s\n' "$h"
}
win_proc_line() { # win_proc_line <pid> <home> -> a process-table line for the daemon jar
  printf '%s|java.exe -jar %s\n' "$1" "$(winp "$2/.corral/corral-daemon-all.jar")"
}

# (a) the kill is attempted but the daemon survives (and still holds ~/.corral): the summary must
#     NOT say "daemon stopped" and must NOT list ~/.corral as removed; it must say what is true.
KILL_HOME=$(win_new_home kill-fail)
KILL_PROCS="$TMP/win-procs-killfail"; win_proc_line 111 "$KILL_HOME" > "$KILL_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$KILL_HOME" PATH="$TMP/rm-stub:$WIN_STUB:$STUB:$PATH" PS_TABLE="$KILL_PROCS" PS_KILL_FAIL=1 \
  bash "$SRC/install.sh" --home "$KILL_HOME" --uninstall >"$TMP/killfail-uninstall.log" 2>&1; rckf=$?
dump_on_fail "$rckf" "$TMP/killfail-uninstall.log"
check "uninstall kill-fail: exits 0" test "$rckf" -eq 0
check "uninstall kill-fail: a stop was ATTEMPTED for the daemon pid" bash -c \
  "grep -q 'Stop-Process -Id 111' '$TMP/powershell.log'"
check "uninstall kill-fail: the summary does NOT claim the daemon stopped" bash -c \
  "! grep '^Removed:' '$TMP/killfail-uninstall.log' | grep -q 'daemon stopped'"
check "uninstall kill-fail: the summary says the daemon could NOT be stopped, with its PID" bash -c \
  "grep -q 'daemon process could NOT be stopped (still running: PID 111)' '$TMP/killfail-uninstall.log'"
check "uninstall kill-fail: ~/.corral is NOT listed as removed and its loss is reported" bash -c \
  "! grep '^Removed:' '$TMP/killfail-uninstall.log' | grep -qF '~/.corral' && grep -q '~/.corral could NOT be removed' '$TMP/killfail-uninstall.log' && grep -q 'Device or resource busy' '$TMP/killfail-uninstall.log' && [ -e '$KILL_HOME/.corral/corral-daemon-all.jar' ]"
check "uninstall kill-fail: the task itself is still named as removed" bash -c \
  "grep '^Removed:' '$TMP/killfail-uninstall.log' | grep -qF 'Scheduled Task CorralDaemon'"

# (b) the daemon is verified gone and ~/.corral is really gone: both claims are made.
OK_HOME=$(win_new_home stop-ok)
OK_PROCS="$TMP/win-procs-stopok"; win_proc_line 222 "$OK_HOME" > "$OK_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$OK_HOME" PATH="$WIN_STUB:$STUB:$PATH" PS_TABLE="$OK_PROCS" \
  bash "$SRC/install.sh" --home "$OK_HOME" --uninstall >"$TMP/stopok-uninstall.log" 2>&1; rcso=$?
dump_on_fail "$rcso" "$TMP/stopok-uninstall.log"
check "uninstall stop-ok: exits 0" test "$rcso" -eq 0
check "uninstall stop-ok: the daemon java is stopped and gone (verified, not assumed)" bash -c \
  "[ \"\$(cat '$TMP/powershell-killed')\" = 222 ] && [ ! -s '$OK_PROCS' ]"
check "uninstall stop-ok: the summary claims the daemon stopped AND ~/.corral removed" bash -c \
  "line=\$(grep '^Removed:' '$TMP/stopok-uninstall.log'); printf '%s' \"\$line\" | grep -qF 'Scheduled Task CorralDaemon (daemon stopped)' && printf '%s' \"\$line\" | grep -qF '~/.corral'"
check "uninstall stop-ok: ~/.corral really is gone" test ! -e "$OK_HOME/.corral"

# (c) the daemon is gone but ~/.corral survives (e.g. another holder): the summary must not list it
#     as removed and must name what is left and the rm failure.
RM_HOME=$(win_new_home rm-fail)
RM_PROCS="$TMP/win-procs-rmfail"; win_proc_line 333 "$RM_HOME" > "$RM_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$RM_HOME" PATH="$TMP/rm-stub:$WIN_STUB:$STUB:$PATH" PS_TABLE="$RM_PROCS" \
  bash "$SRC/install.sh" --home "$RM_HOME" --uninstall >"$TMP/rmfail-uninstall.log" 2>&1; rcrf=$?
dump_on_fail "$rcrf" "$TMP/rmfail-uninstall.log"
check "uninstall rm-fail: exits 0" test "$rcrf" -eq 0
check "uninstall rm-fail: ~/.corral is NOT listed as removed" bash -c \
  "! grep '^Removed:' '$TMP/rmfail-uninstall.log' | grep -qF '~/.corral'"
check "uninstall rm-fail: the summary names what survived and why" bash -c \
  "grep -q '~/.corral could NOT be removed (still holds: corral-daemon-all.jar' '$TMP/rmfail-uninstall.log' && grep -q 'Device or resource busy' '$TMP/rmfail-uninstall.log' && [ -e '$RM_HOME/.corral/corral-daemon-all.jar' ]"
check "uninstall rm-fail: the verified daemon stop is still claimed" bash -c \
  "grep '^Removed:' '$TMP/rmfail-uninstall.log' | grep -qF 'Scheduled Task CorralDaemon (daemon stopped)'"

# (d) no daemon process was running: nothing is killed, nothing false is claimed, exit 0.
NONE_HOME=$(win_new_home no-daemon)
NONE_PROCS="$TMP/win-procs-none"; : > "$NONE_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$NONE_HOME" PATH="$WIN_STUB:$STUB:$PATH" PS_TABLE="$NONE_PROCS" \
  bash "$SRC/install.sh" --home "$NONE_HOME" --uninstall >"$TMP/nodaemon-uninstall.log" 2>&1; rcnone=$?
dump_on_fail "$rcnone" "$TMP/nodaemon-uninstall.log"
check "uninstall no-daemon: exits 0" test "$rcnone" -eq 0
check "uninstall no-daemon: no process is killed and no stop is claimed" bash -c \
  "[ ! -s '$TMP/powershell-killed' ] && ! grep -q 'Stop-Process' '$TMP/powershell.log' && ! grep '^Removed:' '$TMP/nodaemon-uninstall.log' | grep -q 'daemon stopped'"
check "uninstall no-daemon: the summary says nothing was running and names the task/.bat removed" bash -c \
  "line=\$(grep '^Removed:' '$TMP/nodaemon-uninstall.log'); printf '%s' \"\$line\" | grep -qF 'Scheduled Task CorralDaemon (no daemon process was running)' && printf '%s' \"\$line\" | grep -qF '~/bin/corral-daemon.bat' && printf '%s' \"\$line\" | grep -qF '~/.corral'"

# (e) targeting: an unrelated java (an IDE) and a corral-ui java must NOT be stopped; only the
#     process whose command line carries OUR daemon jar name is, and the match is by that name.
TGT_HOME=$(win_new_home targeted)
TGT_PROCS="$TMP/win-procs-targeted"
{
  win_proc_line 444 "$TGT_HOME"
  printf '%s\n' '555|java.exe -jar C:\\Program Files\\JetBrains\\idea_rt.jar'
  printf '%s\n' '666|java.exe -jar '"$(winp "$TGT_HOME/.corral/corral-ui-all.jar")"
} > "$TGT_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$TGT_HOME" PATH="$WIN_STUB:$STUB:$PATH" PS_TABLE="$TGT_PROCS" \
  bash "$SRC/install.sh" --home "$TGT_HOME" --uninstall >"$TMP/targeted-uninstall.log" 2>&1; rctg2=$?
dump_on_fail "$rctg2" "$TMP/targeted-uninstall.log"
check "uninstall targeted: only our daemon pid is stopped; the IDE and corral-ui javas survive" bash -c \
  "[ \"\$(cat '$TMP/powershell-killed')\" = 444 ] && grep -q '^555|' '$TGT_PROCS' && grep -q '^666|' '$TGT_PROCS' && ! grep -q '^444|' '$TGT_PROCS'"
check "uninstall targeted: the query matched on the daemon jar name, never by port or all java" bash -c \
  "grep -q 'Get-CimInstance Win32_Process' '$TMP/powershell.log' && grep -q 'CORRAL_DAEMON_JAR' '$TMP/powershell.log' && ! grep -qi 'Get-Process -Name java' '$TMP/powershell.log' && ! grep -q '8798' '$TMP/powershell.log'"

# (f) the query itself fails: an empty answer is NOT evidence that nothing was running, so neither
#     false claim may appear; the summary says it could not verify.
Q_HOME=$(win_new_home query-fail)
Q_PROCS="$TMP/win-procs-qfail"; win_proc_line 777 "$Q_HOME" > "$Q_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$Q_HOME" PATH="$TMP/rm-stub:$WIN_STUB:$STUB:$PATH" PS_TABLE="$Q_PROCS" PS_QUERY_FAIL=1 \
  bash "$SRC/install.sh" --home "$Q_HOME" --uninstall >"$TMP/qfail-uninstall.log" 2>&1; rcq=$?
dump_on_fail "$rcq" "$TMP/qfail-uninstall.log"
check "uninstall query-fail: exits 0" test "$rcq" -eq 0
check "uninstall query-fail: neither 'daemon stopped' nor 'no daemon process was running' is claimed" bash -c \
  "! grep -q 'daemon stopped' '$TMP/qfail-uninstall.log' && ! grep -q 'no daemon process was running' '$TMP/qfail-uninstall.log'"
check "uninstall query-fail: the summary says the stop could not be verified" bash -c \
  "grep -q 'could not verify whether the corral daemon process is still running' '$TMP/qfail-uninstall.log' && [ -e '$Q_HOME/.corral/corral-daemon-all.jar' ]"

# (g) the daemon's command line spells the jar path with FORWARD slashes (an MSYS `java -jar
#     C:/...` start) while the installer passes the win_path spelling with backslashes. A
#     separator-blind match misses it and the summary then claims "no daemon process was running"
#     while the daemon is alive — the false claim this whole path exists to remove. cygpath is
#     stubbed so the installer really does receive a backslash-spelled jar path on POSIX.
FW_STUB="$TMP/fwstub"; mkdir -p "$FW_STUB"
cp "$WIN_STUB/uname" "$WIN_STUB/schtasks" "$WIN_STUB/powershell.exe" "$FW_STUB/"
cat > "$FW_STUB/cygpath" <<'EOF'
#!/bin/sh
p=${2#/}
printf 'C:\\%s\n' "$(printf '%s' "$p" | tr '/' '\\')"
EOF
chmod +x "$FW_STUB/cygpath"
FW_HOME=$(win_new_home fw-slash)
FW_POSIX_JAR="$FW_HOME/.corral/corral-daemon-all.jar"
FW_WIN_JAR="C:$(printf '%s' "$FW_POSIX_JAR" | tr '/' '\\')"
FW_FWD_JAR=${FW_WIN_JAR//\\//}
FW_PROCS="$TMP/win-procs-fwslash"
printf '%s\n' "888|java.exe -jar $FW_FWD_JAR" > "$FW_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$FW_HOME" PATH="$FW_STUB:$STUB:$PATH" PS_TABLE="$FW_PROCS" \
  bash "$SRC/install.sh" --home "$FW_HOME" --uninstall >"$TMP/fw-uninstall.log" 2>&1; rcfw=$?
dump_on_fail "$rcfw" "$TMP/fw-uninstall.log"
check "uninstall forward-slash jar path: the daemon is matched and stopped" bash -c \
  "[ '$rcfw' -eq 0 ] && [ \"\$(cat '$TMP/powershell-killed')\" = 888 ] && [ ! -s '$FW_PROCS' ]"
check "uninstall forward-slash jar path: the summary never says no daemon process was running" bash -c \
  "grep '^Removed:' '$TMP/fw-uninstall.log' | grep -qF '(daemon stopped)' && ! grep -q 'no daemon process was running' '$TMP/fw-uninstall.log'"

# (h) the daemon runs as javaw.exe (corral-daemon.bat honours CORRAL_JAVA). A filter on java.exe
#     alone cannot see it, and the summary would again claim nothing was running.
JAVAW_HOME=$(win_new_home javaw)
JAVAW_PROCS="$TMP/win-procs-javaw"
printf '%s\n' "999|javaw.exe -jar $(winp "$JAVAW_HOME/.corral/corral-daemon-all.jar")" > "$JAVAW_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$JAVAW_HOME" PATH="$WIN_STUB:$STUB:$PATH" PS_TABLE="$JAVAW_PROCS" \
  bash "$SRC/install.sh" --home "$JAVAW_HOME" --uninstall >"$TMP/javaw-uninstall.log" 2>&1; rcjw=$?
dump_on_fail "$rcjw" "$TMP/javaw-uninstall.log"
check "uninstall javaw: a javaw.exe daemon is matched and stopped" bash -c \
  "[ '$rcjw' -eq 0 ] && [ \"\$(cat '$TMP/powershell-killed')\" = 999 ] && [ ! -s '$JAVAW_PROCS' ]"
check "uninstall javaw: the summary never says no daemon process was running" bash -c \
  "grep '^Removed:' '$TMP/javaw-uninstall.log' | grep -qF '(daemon stopped)' && ! grep -q 'no daemon process was running' '$TMP/javaw-uninstall.log'"

# (h2) the daemon runs from a jar COPIED to another directory: its command line carries a path this
#      install never wrote. Matching the installed PATH misses it and the summary then claims no
#      daemon was running while one is alive (found on a real Windows box); the FILE NAME match
#      finds and stops it.
CP_HOME=$(win_new_home copied-jar)
CP_PROCS="$TMP/win-procs-copied"; printf '%s\n' "4242|java.exe -jar $(winp "$TMP/copied/corral-daemon-all.jar")" > "$CP_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"
env HOME="$CP_HOME" PATH="$WIN_STUB:$STUB:$PATH" PS_TABLE="$CP_PROCS" \
  bash "$SRC/install.sh" --home "$CP_HOME" --uninstall >"$TMP/copied-uninstall.log" 2>&1; rccp=$?
dump_on_fail "$rccp" "$TMP/copied-uninstall.log"
check "uninstall copied jar: the daemon started from a copied jar is matched and stopped" bash -c \
  "[ '$rccp' -eq 0 ] && [ \"\$(cat '$TMP/powershell-killed')\" = 4242 ] && [ ! -s '$CP_PROCS' ]"
check "uninstall copied jar: the summary never says no daemon process was running" bash -c \
  "grep '^Removed:' '$TMP/copied-uninstall.log' | grep -qF '(daemon stopped)' && ! grep -q 'no daemon process was running' '$TMP/copied-uninstall.log'"

# (i) a re-query fails transiently right after the kill, then a later poll succeeds and shows the
#     daemon gone. The flag must be CLEARED by that success, or the summary keeps saying "could not
#     verify" after it has observed the answer.
TR_HOME=$(win_new_home transient)
TR_PROCS="$TMP/win-procs-transient"; win_proc_line 1234 "$TR_HOME" > "$TR_PROCS"
: > "$TMP/powershell-killed"; : > "$TMP/powershell.log"; : > "$TMP/powershell-queries"
env HOME="$TR_HOME" PATH="$WIN_STUB:$STUB:$PATH" PS_TABLE="$TR_PROCS" PS_QUERY_FAIL_AT=2 \
  bash "$SRC/install.sh" --home "$TR_HOME" --uninstall >"$TMP/transient-uninstall.log" 2>&1; rctr=$?
dump_on_fail "$rctr" "$TMP/transient-uninstall.log"
check "uninstall transient query failure: the daemon is still stopped" bash -c \
  "[ '$rctr' -eq 0 ] && [ \"\$(cat '$TMP/powershell-killed')\" = 1234 ] && [ ! -s '$TR_PROCS' ]"
check "uninstall transient query failure: a later successful poll reports the daemon stopped, not 'could not verify'" bash -c \
  "grep '^Removed:' '$TMP/transient-uninstall.log' | grep -qF 'Scheduled Task CorralDaemon (daemon stopped)' && ! grep -q 'could not verify' '$TMP/transient-uninstall.log'"

# (j) no PowerShell at all: the daemon cannot be identified, so neither "daemon stopped" nor "no
#     daemon process was running" may appear, and the uninstall still succeeds. The restricted PATH
#     holds only the tools install.sh needs (no powershell.exe/powershell/pwsh anywhere on it).
NOP_HOME=$(win_new_home no-powershell)
NOP_STUB="$TMP/nopsstub"; mkdir -p "$NOP_STUB"
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$NOP_STUB/uname"
chmod +x "$NOP_STUB/uname"
env HOME="$NOP_HOME" PATH="$NOP_STUB:$CORETOOLS" "$BASH_BIN" \
  "$SRC/install.sh" --home "$NOP_HOME" --uninstall >"$TMP/nops-uninstall.log" 2>&1; rcnp=$?
dump_on_fail "$rcnp" "$TMP/nops-uninstall.log"
check "uninstall no-powershell: exits 0" test "$rcnp" -eq 0
check "uninstall no-powershell: claims neither 'daemon stopped' nor 'no daemon process was running'" bash -c \
  "! grep -q 'daemon stopped' '$TMP/nops-uninstall.log' && ! grep -q 'no daemon process was running' '$TMP/nops-uninstall.log'"
check "uninstall no-powershell: says the daemon process could not be identified" bash -c \
  "grep -q 'PowerShell was not found, so the corral daemon process could not be identified' '$TMP/nops-uninstall.log'"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
exit "$fail"
