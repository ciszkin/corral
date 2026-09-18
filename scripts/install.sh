#!/usr/bin/env bash
# corral install.sh — install corral-daemon, the adb wrapper and the corral CLI
# on a Linux host. User-scoped (no root). Idempotent; safe to re-run.
# Usage: scripts/install.sh [--java PATH] [--adb PATH] [--jar PATH] [--uninstall]
set -u

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
CORRAL_HOME="$HOME/.corral"
BIN_DIR="$HOME/bin"
UNIT_DIR="$HOME/.config/systemd/user"

JAVA_ARG=""
ADB_ARG=""
JAR_ARG=""
UNINSTALL=0
SHIM=1

usage() {
  cat <<'EOF'
Usage: install.sh [options]

  --java PATH     Java 17+ executable (auto-detect: JAVA_HOME, PATH, sdkman)
  --adb PATH      real adb executable (auto-detect: ANDROID_HOME, ~/Android/Sdk, PATH)
  --jar PATH      corral-daemon-all.jar (default: ./corral-daemon/build/libs/corral-daemon-all.jar)
  --no-sdk-shim   do NOT shim the SDK's platform-tools/adb (needed to gate gradle/AGP installs)
  --uninstall     stop + remove the daemon service, shims, and ~/.corral
EOF
}

while [[ $# -gt 0 ]]; do
  case $1 in
    --java) JAVA_ARG=${2:?--java needs a path}; shift 2;;
    --adb) ADB_ARG=${2:?--adb needs a path}; shift 2;;
    --jar) JAR_ARG=${2:?--jar needs a path}; shift 2;;
    --no-sdk-shim) SHIM=0; shift;;
    --uninstall) UNINSTALL=1; shift;;
    -h|--help) usage; exit 0;;
    *) echo "unknown option: $1" >&2; usage; exit 2;;
  esac
done

if [[ $UNINSTALL -eq 1 ]]; then
  echo "==> Uninstalling corral"
  systemctl --user disable --now corral 2>/dev/null || true
  rm -f "$UNIT_DIR/corral.service"
  systemctl --user daemon-reload 2>/dev/null || true
  rm -f "$BIN_DIR/adb" "$BIN_DIR/corral"
  rm -f "$HOME/.gradle/init.d/corral-gate.gradle" "$HOME/.gradle/init.d/corral-gate.gradle.kts"
  rm -f "$HOME/.config/corral/gradle-tasks"
  rm -f "$HOME/.config/corral/rules"
  rm -rf "$CORRAL_HOME"
  # restore the SDK adb if it was shimmed (adb.real -> adb)
  for d in "$HOME/Android/Sdk/platform-tools" "${ANDROID_HOME:-}/platform-tools" "${ANDROID_SDK_ROOT:-}/platform-tools"; do
    if [[ -e "$d/adb.real" ]]; then
      rm -f "$d/adb"
      mv "$d/adb.real" "$d/adb"
      echo "Restored SDK adb at $d/adb"
    fi
  done
  echo "Removed: corral.service, ~/bin/adb, ~/bin/corral, ~/.corral, ~/.gradle/init.d/corral-gate.gradle*, ~/.config/corral/gradle-tasks, ~/.config/corral/rules"
  echo "Note: if ~/bin was the only place adb resolved from, restore the real adb onto PATH."
  rmdir "$HOME/.gradle/init.d" "$HOME/.config/corral" 2>/dev/null || true
  exit 0
fi

detect_java() {
  local j=""
  if [[ -n "$JAVA_ARG" ]]; then j="$JAVA_ARG"
  elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then j="$JAVA_HOME/bin/java"
  elif command -v java >/dev/null 2>&1; then j=$(command -v java)
  elif [[ -x "$HOME/.sdkman/candidates/java/current/bin/java" ]]; then j="$HOME/.sdkman/candidates/java/current/bin/java"
  else echo "ERROR: no Java 17+ found. Install JDK 17 or pass --java PATH." >&2; exit 1; fi
  j=$(readlink -f "$j" 2>/dev/null || echo "$j")
  local maj; maj=$("$j" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')
  if [[ -z "$maj" || "$maj" -lt 17 ]]; then
    echo "ERROR: $j is not Java 17+ (got ${maj:-unknown}); pass --java PATH." >&2; exit 1
  fi
  echo "$j"
}

detect_adb() {
  if [[ -n "$ADB_ARG" ]]; then echo "$ADB_ARG"; return 0; fi
  if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then echo "$ANDROID_HOME/platform-tools/adb"; return 0; fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then echo "$ANDROID_SDK_ROOT/platform-tools/adb"; return 0; fi
  if [[ -x "$HOME/Android/Sdk/platform-tools/adb" ]]; then echo "$HOME/Android/Sdk/platform-tools/adb"; return 0; fi
  if command -v adb >/dev/null 2>&1; then command -v adb; return 0; fi
  return 1
}

resolve_jar() {
  if [[ -n "$JAR_ARG" ]]; then echo "$JAR_ARG"; return 0; fi
  if [[ -f "$SCRIPT_DIR/corral-daemon-all.jar" ]]; then echo "$SCRIPT_DIR/corral-daemon-all.jar"; return 0; fi
  if [[ -f "$REPO_ROOT/corral-daemon/build/libs/corral-daemon-all.jar" ]]; then echo "$REPO_ROOT/corral-daemon/build/libs/corral-daemon-all.jar"; return 0; fi
  echo "ERROR: corral-daemon-all.jar not found. Build it (./gradlew :corral-daemon:shadowJar) or pass --jar PATH." >&2
  exit 1
}

# Gate EVERY adb call, including absolute-path ones from gradle/AGP/IDE, by replacing the
# SDK's platform-tools/adb with the wrapper (real binary kept as adb.real next to it).
# Updates $ADB to the real binary so the daemon and the ~/bin wrapper use it directly.
install_sdk_shim() {
  [[ -n "$ADB" ]] || return 0
  local adb_dir adb_path
  adb_dir=$(dirname "$ADB")
  adb_path="$adb_dir/adb"
  [[ -f "$adb_path" ]] || return 0
  # Only question that matters: is what sits at adb_path our wrapper? If it's a real binary
  # (first install OR a freshly SDK-updated adb), stash it as adb.real — replacing a stale
  # adb.real is correct, never clobbering the newer binary.
  if ! grep -q 'corral adb wrapper' "$adb_path" 2>/dev/null; then
    mv "$adb_path" "$adb_path.real"
    echo "Stashed real adb as $ADB.real (or replaced a stale one)"
  fi
  cp "$SCRIPT_DIR/adb-wrapper" "$adb_path"; chmod +x "$adb_path"
  ADB="$adb_path.real"
  echo "Ensured SDK adb shim at $adb_path (real: $ADB; re-run install.sh after SDK updates)"
}

JAVA_BIN=$(detect_java)
if [[ -z "$JAVA_BIN" ]]; then echo "ERROR: could not detect a usable Java; pass --java PATH." >&2; exit 1; fi
if ADB=$(detect_adb); then
  echo "Using adb: $ADB"
else
  ADB=""
  echo "WARNING: no real adb found — the daemon will have an empty device inventory."
  echo "         Install Android platform-tools or pass --adb PATH, then re-run."
fi
JAR=$(resolve_jar)
if [[ -z "$JAR" || ! -f "$JAR" ]]; then echo "ERROR: jar not found at $JAR; pass --jar PATH." >&2; exit 1; fi

echo "==> Installing to $HOME"
mkdir -p "$CORRAL_HOME" "$BIN_DIR" "$UNIT_DIR"
cp "$JAR" "$CORRAL_HOME/corral-daemon-all.jar"
cp "$SCRIPT_DIR/adb-wrapper" "$BIN_DIR/adb";  chmod +x "$BIN_DIR/adb"
cp "$SCRIPT_DIR/corral"      "$BIN_DIR/corral"; chmod +x "$BIN_DIR/corral"

# Shimming changes $ADB to the real binary (adb.real), used by the unit + the rc export.
[[ $SHIM -eq 1 ]] && install_sdk_shim

# gradle gate: init.d script + editable task-pattern config
if [[ ! -f "$SCRIPT_DIR/corral-gate.gradle.kts" ]]; then
  echo "ERROR: scripts/corral-gate.gradle.kts missing (incomplete bundle); cannot install the gradle gate" >&2
  exit 1
fi
mkdir -p "$HOME/.gradle/init.d" "$HOME/.config/corral"
CORRAL_SHIM="$BIN_DIR/corral"
CORRAL_SHIM_ESCAPED=${CORRAL_SHIM//\\/\\\\}
CORRAL_SHIM_ESCAPED=${CORRAL_SHIM_ESCAPED//&/\\&}
CORRAL_SHIM_ESCAPED=${CORRAL_SHIM_ESCAPED//#/\\#}
rm -f "$HOME/.gradle/init.d/corral-gate.gradle"   # remove the pre-Kotlin Groovy gate
sed "s#@CORRAL_SHIM@#$CORRAL_SHIM_ESCAPED#g" "$SCRIPT_DIR/corral-gate.gradle.kts" > "$HOME/.gradle/init.d/corral-gate.gradle.kts"
if [[ ! -f "$HOME/.config/corral/gradle-tasks" ]]; then
  printf 'install.*\nconnected.*AndroidTest\n' > "$HOME/.config/corral/gradle-tasks"
fi
echo "Installed gradle gate (~/.gradle/init.d/corral-gate.gradle.kts); edit ~/.config/corral/gradle-tasks to change gated tasks"

# gate rules: user-editable, never clobbered; the daemon self-heals a missing file
if [[ ! -f "$SCRIPT_DIR/corral-rules" ]]; then
  echo "ERROR: scripts/corral-rules missing (incomplete bundle); cannot install the gate rules" >&2
  exit 1
fi
if [[ ! -f "$HOME/.config/corral/rules" ]]; then
  cp "$SCRIPT_DIR/corral-rules" "$HOME/.config/corral/rules"
fi
echo "Installed gate rules (~/.config/corral/rules); edit to customize read-only commands and flags"

# Generate the per-host unit from the committed template, keeping the tuned JVM flags.
if [[ -n "$ADB" ]]; then
  sed -e "s#%h/.sdkman/candidates/java/current/bin/java#$JAVA_BIN#" \
      -e "s#Environment=CORRAL_ADB=%h/Android/Sdk/platform-tools/adb#Environment=CORRAL_ADB=$ADB#" \
      -e "/^Environment=PATH=/d" \
      "$SCRIPT_DIR/corral.service" > "$UNIT_DIR/corral.service"
else
  sed -e "s#%h/.sdkman/candidates/java/current/bin/java#$JAVA_BIN#" \
      -e "/^Environment=CORRAL_ADB=/d" \
      -e "/^Environment=PATH=/d" \
      "$SCRIPT_DIR/corral.service" > "$UNIT_DIR/corral.service"
fi

# Ensure ~/bin is on PATH (the wrapper must precede the SDK dir) and the wrapper
# knows the real adb path.
PATH_LINE='export PATH="$HOME/bin:$PATH"'
ADB_LINE="export CORRAL_REAL_ADB=\"$ADB\""
for rc in "$HOME/.bashrc" "$HOME/.zshrc"; do
  [[ -f "$rc" ]] || continue
  if ! grep -q 'HOME/bin' "$rc" 2>/dev/null; then
    printf '%s\n' "$PATH_LINE" >> "$rc"
    echo "Added PATH export to $rc (reload your shell or log out/in)"
  fi
  if [[ -n "$ADB" ]] && ! grep -q 'CORRAL_REAL_ADB' "$rc" 2>/dev/null; then
    printf '%s\n' "$ADB_LINE" >> "$rc"
    echo "Added CORRAL_REAL_ADB export to $rc (reload your shell or log out/in)"
  fi
done

if systemctl --user daemon-reload 2>/dev/null; then
  systemctl --user enable corral
  systemctl --user restart corral
  systemctl --user status corral --no-pager
else
  echo "No systemd user session here — start the daemon manually:"
  echo "  $JAVA_BIN -Xms24m -Xmx64m -XX:+UseSerialGC -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:TieredStopAtLevel=1 -jar $CORRAL_HOME/corral-daemon-all.jar &"
fi

echo
echo "==> Installed. Smoke-test:"
echo "  curl -s http://127.0.0.1:8798/api/devices"
echo "  corral devices | python3 -m json.tool"
if [[ $SHIM -eq 1 ]]; then
  echo "SDK adb shim is active — gradle/AGP absolute-path adb calls are lease-gated."
fi
echo
echo "Cutover (scripts/cutover.md): ~/bin/adb --version, corral devices, corral lease -- adb shell echo hi,"
echo "then verify a gradle installDebug allow/deny, retire the adb-lease hook, keep old scripts backed up a week."