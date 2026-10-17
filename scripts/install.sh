#!/usr/bin/env bash
# corral install.sh — install corral-daemon, the adb wrapper and the corral CLI
# on a Linux host. User-scoped (no root). Idempotent; safe to re-run.
# Usage: scripts/install.sh [--java PATH] [--adb PATH] [--jar PATH] [--ui|--no-ui] [--home DIR] [--uninstall]
set -u

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
TARGET_HOME="${HOME:-}"

JAVA_ARG=""
ADB_ARG=""
JAR_ARG=""
HOME_ARG=""
UNINSTALL=0
SHIM=1
SHIM_ACTIVE=0
INSTALL_UI=auto

usage() {
  cat <<'EOF'
Usage: install.sh [options]

  --java PATH     Java 17+ executable (auto-detect: JAVA_HOME, PATH, sdkman)
  --adb PATH      real adb executable (auto-detect: ANDROID_HOME, ~/Android/Sdk, PATH)
  --jar PATH      corral-daemon-all.jar (default: ./corral-daemon/build/libs/corral-daemon-all.jar)
  --ui            install the corral-ui desktop app (~/bin/corral-ui, jar in ~/.corral, and a
                  "Corral" launcher in ~/.local/share/applications for the app grid/dock)
  --no-ui         skip corral-ui (default: install when corral-ui-all.jar is present)
  --home DIR      install root to use instead of $HOME (tests/sandboxes only)
  --no-sdk-shim   do NOT shim the SDK's platform-tools/adb (needed to gate gradle/AGP installs)
  --uninstall     stop + remove the daemon service, shims, and ~/.corral
EOF
}

while [[ $# -gt 0 ]]; do
  case $1 in
    --java) JAVA_ARG=${2:?--java needs a path}; shift 2;;
    --adb) ADB_ARG=${2:?--adb needs a path}; shift 2;;
    --jar) JAR_ARG=${2:?--jar needs a path}; shift 2;;
    --home) HOME_ARG=${2:-}; if [[ -z "$HOME_ARG" ]]; then echo "ERROR: --home needs a non-empty DIR" >&2; exit 2; fi; shift 2;;
    --no-sdk-shim) SHIM=0; shift;;
    --ui) INSTALL_UI=1; shift;;
    --no-ui) INSTALL_UI=0; shift;;
    --uninstall) UNINSTALL=1; shift;;
    -h|--help) usage; exit 0;;
    *) echo "unknown option: $1" >&2; usage; exit 2;;
  esac
done

if [[ -n "$HOME_ARG" ]]; then TARGET_HOME="$HOME_ARG"; fi
if [[ -z "$TARGET_HOME" ]]; then
  echo "ERROR: \$HOME is unset and no --home DIR was given; set HOME or pass --home DIR." >&2
  exit 1
fi
CORRAL_HOME="$TARGET_HOME/.corral"
BIN_DIR="$TARGET_HOME/bin"
UNIT_DIR="$TARGET_HOME/.config/systemd/user"
SANDBOX=0
if [[ "$TARGET_HOME" != "${HOME:-}" ]]; then SANDBOX=1; fi

if [[ $UNINSTALL -eq 1 ]]; then
  echo "==> Uninstalling corral"
  if [[ $SANDBOX -eq 1 ]]; then
    echo "sandbox: skipping systemctl (--home $TARGET_HOME != \$HOME)"
  else
    systemctl --user disable --now corral 2>/dev/null || true
  fi
  rm -f "$UNIT_DIR/corral.service"
  if [[ $SANDBOX -eq 0 ]]; then systemctl --user daemon-reload 2>/dev/null || true; fi
  rm -f "$BIN_DIR/adb" "$BIN_DIR/corral" "$BIN_DIR/corral-ui"
  rm -f "$TARGET_HOME/.local/share/applications/corral-ui.desktop"
  ICON="$TARGET_HOME/.local/share/icons/corral.png"
  if [[ -f "$ICON.sha" && "$(sha256sum "$ICON" 2>/dev/null | cut -d' ' -f1)" = "$(cat "$ICON.sha")" ]]; then
    rm -f "$ICON"
  fi
  rm -f "$ICON.sha"
  if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$TARGET_HOME/.local/share/applications" >/dev/null 2>&1 || true
  fi
  rm -f "$TARGET_HOME/.gradle/init.d/corral-gate.gradle" "$TARGET_HOME/.gradle/init.d/corral-gate.gradle.kts"
  rm -f "$TARGET_HOME/.config/corral/gradle-tasks"
  rm -f "$TARGET_HOME/.config/corral/rules"
  rm -f "$TARGET_HOME/.config/corral/daemon.env"
  rm -f "$TARGET_HOME/.config/corral/ui.conf"
  rm -rf "$CORRAL_HOME"
  # restore the SDK adb if it was shimmed (adb.real -> adb). Under --home only the sandbox's own
  # SDK is ours to restore; an ambient SDK belongs to the real install and must not be touched.
  SDK_DIRS=("$TARGET_HOME/Android/Sdk/platform-tools")
  if [[ -z "$HOME_ARG" ]]; then SDK_DIRS+=("${ANDROID_HOME:-}/platform-tools" "${ANDROID_SDK_ROOT:-}/platform-tools"); fi
  for d in "${SDK_DIRS[@]}"; do
    if [[ -e "$d/adb.real" ]]; then
      rm -f "$d/adb"
      mv "$d/adb.real" "$d/adb"
      echo "Restored SDK adb at $d/adb"
    fi
  done
  echo "Removed: corral.service, ~/bin/adb, ~/bin/corral, ~/bin/corral-ui, Corral launcher, ~/.corral, ~/.gradle/init.d/corral-gate.gradle*, ~/.config/corral/gradle-tasks, ~/.config/corral/rules, ~/.config/corral/daemon.env, ~/.config/corral/ui.conf"
  echo "Note: if ~/bin was the only place adb resolved from, restore the real adb onto PATH."
  rmdir "$TARGET_HOME/.gradle/init.d" "$TARGET_HOME/.config/corral" 2>/dev/null || true
  exit 0
fi

detect_java() {
  local j=""
  if [[ -n "$JAVA_ARG" ]]; then j="$JAVA_ARG"
  elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then j="$JAVA_HOME/bin/java"
  elif command -v java >/dev/null 2>&1; then j=$(command -v java)
  elif [[ -x "$TARGET_HOME/.sdkman/candidates/java/current/bin/java" ]]; then j="$TARGET_HOME/.sdkman/candidates/java/current/bin/java"
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
  # --home is a sandbox: install_sdk_shim overwrites the adb it resolves, so an adb outside
  # TARGET_HOME must never be used. Only an SDK inside the root is acceptable (explicit --adb
  # above is the operator opting in to a specific path).
  if [[ -n "$HOME_ARG" ]]; then
    if [[ -x "$TARGET_HOME/Android/Sdk/platform-tools/adb" ]]; then echo "$TARGET_HOME/Android/Sdk/platform-tools/adb"; return 0; fi
    return 1
  fi
  if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then echo "$ANDROID_HOME/platform-tools/adb"; return 0; fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then echo "$ANDROID_SDK_ROOT/platform-tools/adb"; return 0; fi
  if [[ -x "$TARGET_HOME/Android/Sdk/platform-tools/adb" ]]; then echo "$TARGET_HOME/Android/Sdk/platform-tools/adb"; return 0; fi
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

# The UI jar is optional (want_ui decides), so unlike resolve_jar this never exits:
# an explicit CORRAL_UI_JAR wins, then the bundle beside install.sh, then the source-tree build.
resolve_ui_jar() {
  if [[ -n "${CORRAL_UI_JAR:-}" ]]; then echo "$CORRAL_UI_JAR"; return 0; fi
  if [[ -f "$SCRIPT_DIR/corral-ui-all.jar" ]]; then echo "$SCRIPT_DIR/corral-ui-all.jar"; return 0; fi
  echo "$REPO_ROOT/corral-ui/build/libs/corral-ui-all.jar"
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
    if ! mv "$adb_path" "$adb_path.real"; then
      echo "ERROR: cannot move the real adb aside ($adb_path -> $adb_path.real); the SDK adb shim was NOT installed." >&2
      echo "       gradle/AGP absolute-path adb calls are NOT lease-gated; fix the SDK path and re-run." >&2
      exit 1
    fi
    echo "Stashed real adb as $ADB.real (or replaced a stale one)"
  fi
  # cp/chmod are unchecked by set -u; on an incomplete bundle, a read-only SDK dir or a full disk they
  # can fail AFTER the real adb was moved to adb.real, which would leave the SDK with no adb at all.
  # Treat any failure as fatal and put the real adb back rather than keep a false "active" claim.
  if ! cp "$SCRIPT_DIR/adb-wrapper" "$adb_path" || ! chmod +x "$adb_path" \
     || ! grep -q 'corral adb wrapper' "$adb_path" 2>/dev/null; then
    if [ -e "$adb_path.real" ]; then
      mv -f "$adb_path.real" "$adb_path"
      echo "ERROR: failed to install the SDK adb shim at $adb_path; restored the real adb from $adb_path.real." >&2
    else
      # No adb.real: the stash mv never ran, so whatever is here now is either a valid wrapper left by a
      # previous install (keep it) or a partial file from our failed cp (remove only that). Never a real
      # adb — a real adb would have been stashed to adb.real above.
      grep -q 'corral adb wrapper' "$adb_path" 2>/dev/null || rm -f "$adb_path"
      echo "ERROR: failed to install the SDK adb shim at $adb_path; no adb.real was present to restore." >&2
    fi
    echo "       gradle/AGP absolute-path adb calls are NOT lease-gated. Check scripts/adb-wrapper in the" >&2
    echo "       bundle, the SDK path permissions and free space, then re-run install.sh." >&2
    exit 1
  fi
  ADB="$adb_path.real"
  SHIM_ACTIVE=1
  echo "Ensured SDK adb shim at $adb_path (real: $ADB; re-run install.sh after SDK updates)"
}

JAVA_BIN=$(detect_java)
if [[ -z "$JAVA_BIN" ]]; then echo "ERROR: could not detect a usable Java; pass --java PATH." >&2; exit 1; fi
if ADB=$(detect_adb); then
  echo "Using adb: $ADB"
elif [[ -n "$HOME_ARG" ]]; then
  echo "ERROR: no adb at $TARGET_HOME/Android/Sdk/platform-tools/adb; refusing to shim one from outside." >&2
  echo "       A --home install deliberately ignores \$ANDROID_HOME and \$ANDROID_SDK_ROOT even when they" >&2
  echo "       point inside the root at a non-standard path, and ignores PATH — because" >&2
  echo "       install_sdk_shim OVERWRITES the adb it resolves. Put an SDK at the path above, or pass" >&2
  echo "       --adb PATH for one you accept being rewritten." >&2
  exit 1
else
  ADB=""
  echo "WARNING: no real adb found — the daemon will have an empty device inventory."
  echo "         Install Android platform-tools or pass --adb PATH, then re-run."
fi
JAR=$(resolve_jar)
if [[ -z "$JAR" || ! -f "$JAR" ]]; then echo "ERROR: jar not found at $JAR; pass --jar PATH." >&2; exit 1; fi

UI_JAR=$(resolve_ui_jar)
want_ui() { [ "$INSTALL_UI" = 1 ] || { [ "$INSTALL_UI" != 0 ] && [ -f "$UI_JAR" ]; }; }
if [[ "$INSTALL_UI" = 1 && ! -f "$UI_JAR" ]]; then
  echo "ERROR: --ui given but corral-ui-all.jar not found at $UI_JAR; pass CORRAL_UI_JAR or build it." >&2
  exit 1
fi
if want_ui && [[ ! -f "$SCRIPT_DIR/corral-ui" ]]; then
  echo "ERROR: scripts/corral-ui missing (incomplete bundle); cannot install the ui shim" >&2
  exit 1
fi
if want_ui && [[ ! -f "$SCRIPT_DIR/corral.png" ]]; then
  echo "ERROR: $SCRIPT_DIR/corral.png missing (incomplete bundle); cannot install the launcher icon" >&2
  exit 1
fi
# Both the ~/bin wrapper and the SDK shim copy this file; validate it before anything is moved so an
# incomplete bundle fails with the filesystem completely untouched (no adb stashed aside).
if [[ ! -f "$SCRIPT_DIR/adb-wrapper" ]]; then
  echo "ERROR: $SCRIPT_DIR/adb-wrapper missing from the bundle; the adb wrapper and SDK adb shim were NOT installed." >&2
  exit 1
fi

echo "==> Installing to $TARGET_HOME"
mkdir -p "$CORRAL_HOME" "$BIN_DIR" "$UNIT_DIR"
cp "$JAR" "$CORRAL_HOME/corral-daemon-all.jar"
if ! cp "$SCRIPT_DIR/adb-wrapper" "$BIN_DIR/adb" || ! chmod +x "$BIN_DIR/adb"; then
  echo "ERROR: cannot install the executable adb wrapper at $BIN_DIR/adb." >&2
  exit 1
fi
cp "$SCRIPT_DIR/corral"      "$BIN_DIR/corral"; chmod +x "$BIN_DIR/corral"

if want_ui; then
  cp "$UI_JAR" "$CORRAL_HOME/corral-ui-all.jar" || { echo "ERROR: cannot copy $UI_JAR to $CORRAL_HOME" >&2; exit 1; }
  install -m 755 "$SCRIPT_DIR/corral-ui" "$BIN_DIR/corral-ui" || { echo "ERROR: cannot install $BIN_DIR/corral-ui" >&2; exit 1; }
  echo "Installed: corral-ui (~/bin/corral-ui, jar in ~/.corral)"
  # Launcher for the app grid/dock. Exec uses the detected java + fat jar (not the shim) so a
  # GUI session without the login shell's PATH/JDK still starts it. Icon is the Corral mark: a
  # hash sidecar lets us refresh it on upgrade while never clobbering a user's own replacement.
  # Write the sidecar only when we installed/refreshed the icon, or when an existing icon already
  # equals the shipped one (adopting a pre-sidecar install) — never for an icon we left alone.
  ICON_DIR="$TARGET_HOME/.local/share/icons"
  mkdir -p "$ICON_DIR"
  ICON="$ICON_DIR/corral.png"
  new=$(sha256sum "$SCRIPT_DIR/corral.png" | cut -d' ' -f1)
  if [[ ! -f "$ICON" ]]; then
    cp "$SCRIPT_DIR/corral.png" "$ICON"; printf '%s\n' "$new" > "$ICON.sha"
  elif [[ -f "$ICON.sha" && "$(sha256sum "$ICON" | cut -d' ' -f1)" = "$(cat "$ICON.sha")" ]]; then
    cp "$SCRIPT_DIR/corral.png" "$ICON"; printf '%s\n' "$new" > "$ICON.sha"   # ours, refresh
  elif [[ "$(sha256sum "$ICON" | cut -d' ' -f1)" = "$new" ]]; then
    printf '%s\n' "$new" > "$ICON.sha"                                       # adopt a legacy icon already equal to the bundle's
  else
    :                                                                        # user-replaced: leave it, no sidecar
  fi
  DESKTOP_DIR="$TARGET_HOME/.local/share/applications"
  mkdir -p "$DESKTOP_DIR"
  cat > "$DESKTOP_DIR/corral-ui.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Corral
Comment=Lease Android devices to coding agents — operator console
Exec="$JAVA_BIN" -jar "$CORRAL_HOME/corral-ui-all.jar"
Icon=$TARGET_HOME/.local/share/icons/corral.png
Terminal=false
Categories=Development;
StartupWMClass=dev-corral-ui-CorralUiKt
EOF
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true
  echo "Installed: Corral launcher ($DESKTOP_DIR/corral-ui.desktop — pin it from the app grid)"
fi

# Shimming changes $ADB to the real binary (adb.real), used by the unit + the rc export.
[[ $SHIM -eq 1 ]] && install_sdk_shim

# gradle gate: init.d script + editable task-pattern config
if [[ ! -f "$SCRIPT_DIR/corral-gate.gradle.kts" ]]; then
  echo "ERROR: scripts/corral-gate.gradle.kts missing (incomplete bundle); cannot install the gradle gate" >&2
  exit 1
fi
mkdir -p "$TARGET_HOME/.gradle/init.d" "$TARGET_HOME/.config/corral"
CORRAL_SHIM="$BIN_DIR/corral"
CORRAL_SHIM_ESCAPED=${CORRAL_SHIM//\\/\\\\}
CORRAL_SHIM_ESCAPED=${CORRAL_SHIM_ESCAPED//&/\\&}
CORRAL_SHIM_ESCAPED=${CORRAL_SHIM_ESCAPED//#/\\#}
rm -f "$TARGET_HOME/.gradle/init.d/corral-gate.gradle"   # remove the pre-Kotlin Groovy gate
sed "s#@CORRAL_SHIM@#$CORRAL_SHIM_ESCAPED#g" "$SCRIPT_DIR/corral-gate.gradle.kts" > "$TARGET_HOME/.gradle/init.d/corral-gate.gradle.kts"
if [[ ! -f "$TARGET_HOME/.config/corral/gradle-tasks" ]]; then
  printf 'install.*\nconnected.*AndroidTest\n' > "$TARGET_HOME/.config/corral/gradle-tasks"
fi
echo "Installed gradle gate (~/.gradle/init.d/corral-gate.gradle.kts); edit ~/.config/corral/gradle-tasks to change gated tasks"

# gate rules: user-editable, never clobbered; the daemon self-heals a missing file
if [[ ! -f "$SCRIPT_DIR/corral-rules" ]]; then
  echo "ERROR: scripts/corral-rules missing (incomplete bundle); cannot install the gate rules" >&2
  exit 1
fi
if [[ ! -f "$TARGET_HOME/.config/corral/rules" ]]; then
  cp "$SCRIPT_DIR/corral-rules" "$TARGET_HOME/.config/corral/rules"
fi
echo "Installed gate rules (~/.config/corral/rules); edit to customize read-only commands and flags"

# daemon.env: the /api/config write target; absent-only (a present file is deliberate config)
if [ ! -f "$TARGET_HOME/.config/corral/daemon.env" ]; then
  printf '# corral daemon env — written by PUT /api/config; file wins over the unit env\n' \
    > "$TARGET_HOME/.config/corral/daemon.env"
  echo "Created ~/.config/corral/daemon.env (absent-only)"
fi

# Carry an existing observer line verbatim — including an emptied value (observers off); seed
# corral-ui only when the line is truly absent and the UI installs.
OBS_LINE=""
if grep -q '^Environment=CORRAL_SCREENSHOT_OBSERVERS=' "$UNIT_DIR/corral.service" 2>/dev/null; then
  existing_obs=$(grep -h '^Environment=CORRAL_SCREENSHOT_OBSERVERS=' "$UNIT_DIR/corral.service" | head -1 | cut -d= -f3-)
  OBS_LINE="Environment=CORRAL_SCREENSHOT_OBSERVERS=$existing_obs"
elif want_ui; then
  OBS_LINE="Environment=CORRAL_SCREENSHOT_OBSERVERS=corral-ui"
  echo "Seeded CORRAL_SCREENSHOT_OBSERVERS=corral-ui in the daemon unit (edit freely; absent-only seeding)"
fi

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
# Environment= belongs to [Service]; appending it after [Install] is silently ignored by systemd.
if [[ -n "$OBS_LINE" ]]; then
  awk -v line="$OBS_LINE" '/^\[Install\]/ { print line } { print }' \
    "$UNIT_DIR/corral.service" > "$UNIT_DIR/corral.service.tmp" \
    && mv "$UNIT_DIR/corral.service.tmp" "$UNIT_DIR/corral.service"
fi

# Ensure ~/bin is on PATH (the wrapper must precede the SDK dir) and the wrapper
# knows the real adb path.
RC_HOME='$HOME'
if [[ "$TARGET_HOME" != "${HOME:-}" ]]; then RC_HOME="$TARGET_HOME"; fi
PATH_LINE="export PATH=\"$RC_HOME/bin:\$PATH\""
ADB_LINE="export CORRAL_REAL_ADB=\"$ADB\""
for rc in "$TARGET_HOME/.bashrc" "$TARGET_HOME/.zshrc"; do
  [[ -f "$rc" ]] || continue
  if ! grep -qF "$RC_HOME/bin" "$rc" 2>/dev/null; then
    printf '%s\n' "$PATH_LINE" >> "$rc"
    echo "Added PATH export to $rc (reload your shell or log out/in)"
  fi
  if [[ -n "$ADB" ]] && ! grep -q 'CORRAL_REAL_ADB' "$rc" 2>/dev/null; then
    printf '%s\n' "$ADB_LINE" >> "$rc"
    echo "Added CORRAL_REAL_ADB export to $rc (reload your shell or log out/in)"
  fi
done

if [[ $SANDBOX -eq 1 ]]; then
  echo "sandbox: skipping systemctl (--home $TARGET_HOME != \$HOME)"
elif systemctl --user daemon-reload 2>/dev/null; then
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
if [[ $SHIM_ACTIVE -eq 1 ]]; then
  echo "SDK adb shim is active — adb reached through the SDK's platform-tools path is lease-gated"
  echo "  (gradle/AGP absolute-path calls to it included). An adb elsewhere on this host is not gated."
elif [[ $SHIM -eq 0 ]]; then
  echo "SDK adb shim is off (--no-sdk-shim, as requested) — gradle/AGP absolute-path adb calls are NOT lease-gated."
else
  echo "SDK adb shim is NOT active — gradle/AGP absolute-path adb calls are NOT lease-gated."
  echo "  Install Android platform-tools, or pass --adb PATH to the real adb, then re-run to gate them."
fi
echo
echo "Cutover checklist (in the corral source repo, scripts/cutover.md): ~/bin/adb --version, corral devices, corral lease -- adb shell echo hi,"
echo "then verify a gradle installDebug allow/deny, retire the adb-lease hook, keep old scripts backed up a week."