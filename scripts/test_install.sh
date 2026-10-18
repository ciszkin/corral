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
for t in dirname sed head awk grep cp chmod mkdir cut sha256sum readlink mv rm cat; do
  p=$(command -v "$t") && ln -sf "$p" "$CORETOOLS/$t"
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
printf '%s\n' '#!/bin/sh' 'echo "MINGW64_NT-10.0-22631"' > "$WIN_STUB/uname"; chmod +x "$WIN_STUB/uname"

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
check "win uninstall: summary names the removed .bat shim and says the POSIX names were left alone" bash -c \
  "grep -qF '~/bin/adb-wrapper.bat' '$TMP/winup-uninstall.log' && grep -q 'never installs here' '$TMP/winup-uninstall.log'"
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

printf '\n%d passed, %d failed\n' "$pass" "$fail"
exit "$fail"
