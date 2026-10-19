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
  --adb PATH      real adb executable (auto-detect: ANDROID_HOME, ~/Android/Sdk, PATH; on Windows
                  also ~/AppData/Local/Android/Sdk, where %LOCALAPPDATA%\Android\Sdk lives)
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

# Windows (Git Bash/MSYS/Cygwin) needs .bat shims and has no systemd; the POSIX path is untouched.
# `uname -s` is the probe (never an env var a test cannot inject), so a stubbed `uname` on PATH
# can drive the platform from scripts/test_install.sh.
# Caveat: Cygwin is matched here, but its $HOME is a POSIX path corral.bat cannot consume; detection
# is left alone deliberately (a Cygwin operator still needs a Windows-style install root).
UNAME_S=$(uname -s 2>/dev/null || true)
IS_WINDOWS=0
case "$UNAME_S" in
  MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1;;
esac

# adb's file name is the platform's: the Windows SDK ships `adb.exe`, the POSIX SDK `adb`.
# `adb.exe` is honoured on Windows only. On POSIX `adb` is the name that matters, and it is also the
# only one install_sdk_shim can wrap (it replaces `<sdk>/platform-tools/adb` and nothing else), so
# accepting an `adb.exe` there would resolve an adb the gradle/AGP gate cannot cover.
ADB_NAMES=(adb)
if [[ $IS_WINDOWS -eq 1 ]]; then ADB_NAMES=(adb.exe adb); fi

# Render an MSYS/Git-Bash path the way cmd.exe and PowerShell spell it, for the start commands the
# Windows branch prints for a human to paste. cygpath is authoritative where it exists (every real
# Git Bash has it); the fallback rewrites only a genuine `/<drive>/...` root and leaves anything else
# alone, so a POSIX test sandbox is never turned into a fake drive path.
win_path() { # win_path <path>
  local p="$1" w
  if command -v cygpath >/dev/null 2>&1 && w=$(cygpath -w "$p" 2>/dev/null) && [[ -n "$w" ]]; then
    printf '%s\n' "$w"; return 0
  fi
  case "$p" in
    /[A-Za-z]/*)
      local drive; drive=$(printf '%s' "${p:1:1}" | tr 'a-z' 'A-Z')
      printf '%s:\\%s\n' "$drive" "$(printf '%s' "${p:3}" | tr '/' '\\')";;
    *) printf '%s\n' "$p";;
  esac
}

# Render a value as a PowerShell single-quoted literal. Inside single quotes PowerShell takes
# `&`, `%`, `$` and spaces literally, so doubling the quote is the whole escape — and it is
# required: an unescaped `'` in a path ends the string (PowerShell: "Unexpected token"), which is
# exactly the operator the printed line exists for.
ps_quote() { # ps_quote <value>
  local v="$1"
  printf "'%s'" "${v//\'/\'\'}"
}

# cmd.exe cannot spell every path. Inside `set "K=V"` a `"` ends the quoted value, and a `%NAME%`
# pair is expanded before cmd.exe looks at the quotes (a LONE `%` is left alone, so a directory
# named `100%` is fine); there is no escape at the prompt — a caret is literal inside those quotes,
# so `^&`/`^%` end up IN the path. Everything else, including spaces, `&`, `^`, `!` and `()` in a
# space-`&`-`%` path, round-trips exactly through the quoted form. Callers use this to say "no
# cmd.exe line" instead of printing one that silently sets a different value.
cmd_unspellable() { # cmd_unspellable <value>
  case "$1" in *'"'*|*%*%*) return 0;; esac
  return 1
}

# --- what counts as OUR shim: one guard, shared by --uninstall and by a Windows install ----------
# Remove a PATH shim ONLY when its content carries our marker. A hand-written ~/bin/adb or a
# hand-edited corral.bat belongs to the operator, not to us (the exact collision F2 warned about),
# and deleting it is data loss. Anything unrecognised is left on disk and named in the summary.
# The markers already shipped in the shims before this change, so a pre-change install is still
# removed; an unrecognised file is never treated as "ours anyway".
# Each marker is a string the shipped shim really carries, and scripts/test_install.sh pins every
# one against the shipped file: a shim edit that changed the string would otherwise stop the marker
# matching in silence and leave a stale shim on the operator's PATH.
#   ~/bin/adb         <- scripts/adb-wrapper (its own header line)
#   ~/bin/corral      <- scripts/corral      (its CORRAL_JAR default into ~/.corral)
#   ~/bin/corral-ui   <- scripts/corral-ui   (its CORRAL_UI_JAR default, same shape)
#   ~/bin/corral.bat  <- scripts/corral.bat  (the CLI's main class)
# The two launcher markers are deliberately NOT the bare main-class FQNs (`dev.corral.CorralCli`,
# `dev.corral.ui.CorralUiKt`): an operator's own launcher plausibly runs `dev.corral.CorralCli`,
# and now that this guard also runs on Windows — where it deletes during an INSTALL — a false
# positive is data loss, not a stray file. `${X_JAR:-$HOME/.corral/...}` is a string only our shim
# spells that way. corral.bat keys on its own header comment for the same reason: an earlier version
# claimed it had no other distinctive string and kept the FQN, which deleted an operator's own
# launcher on --uninstall.
MARKER_ADB='corral adb wrapper'
MARKER_CORRAL='CORRAL_JAR:-$HOME/.corral/corral-daemon-all.jar'
MARKER_CORRAL_UI='CORRAL_UI_JAR:-$HOME/.corral/corral-ui-all.jar'
MARKER_CORRAL_BAT='rem corral CLI shim'

# Name only what was actually removed: entries are appended as they happen, so a summary can never
# claim a file this install did not create (on Windows the POSIX shims and the unit do not exist; a
# hard-coded list there is exactly the lie row 15 set out to remove).
REMOVED=""
KEPT_SHIMS=""
note_removed() { REMOVED="$REMOVED$1, "; }
remove_shim() { # remove_shim <path> <marker> <label>
  [[ -e "$1" ]] || return 0
  if grep -qF "$2" "$1" 2>/dev/null; then
    rm -f "$1"; note_removed "$3"
  else
    KEPT_SHIMS="$KEPT_SHIMS $1"
  fi
}

if [[ $UNINSTALL -eq 1 ]]; then
  echo "==> Uninstalling corral"
  if [[ $SANDBOX -eq 1 ]]; then
    echo "sandbox: skipping systemctl (--home $TARGET_HOME != \$HOME)"
  elif [[ $IS_WINDOWS -eq 1 ]]; then
    echo "Windows: skipping systemctl (no systemd user session here)"
  else
    systemctl --user disable --now corral 2>/dev/null || true
  fi
  if [[ -e "$UNIT_DIR/corral.service" ]]; then rm -f "$UNIT_DIR/corral.service"; note_removed "corral.service"; fi
  if [[ $SANDBOX -eq 0 && $IS_WINDOWS -eq 0 ]]; then systemctl --user daemon-reload 2>/dev/null || true; fi

  # remove_shim / note_removed / REMOVED / KEPT_SHIMS are defined above, next to the markers, so the
  # Windows install path uses the very same guard (see MARKER_*).
  # The .bat twins are what a Windows install writes, so they are checked only there. The POSIX
  # names are checked on BOTH platforms: a box upgraded from a pre-f4f2270 install still carries
  # the bash `corral`/`adb`/`corral-ui` the old installer put on its PATH (including the stray bash
  # `adb` F2 was about), and nothing else would ever remove them. The marker guard is the safety
  # property; the platform gate was only belt-and-braces, and it is what left them behind forever.
  if [[ $IS_WINDOWS -eq 1 ]]; then
    remove_shim "$BIN_DIR/corral.bat" "$MARKER_CORRAL_BAT" '~/bin/corral.bat'
    remove_shim "$BIN_DIR/adb-wrapper.bat" "$MARKER_ADB" '~/bin/adb-wrapper.bat'
  fi
  remove_shim "$BIN_DIR/adb" "$MARKER_ADB" '~/bin/adb'
  remove_shim "$BIN_DIR/corral" "$MARKER_CORRAL" '~/bin/corral'
  remove_shim "$BIN_DIR/corral-ui" "$MARKER_CORRAL_UI" '~/bin/corral-ui'

  if [[ -e "$TARGET_HOME/.local/share/applications/corral-ui.desktop" ]]; then
    rm -f "$TARGET_HOME/.local/share/applications/corral-ui.desktop"; note_removed "Corral launcher"
  fi
  ICON="$TARGET_HOME/.local/share/icons/corral.png"
  if [[ -f "$ICON.sha" && "$(sha256sum "$ICON" 2>/dev/null | cut -d' ' -f1)" = "$(cat "$ICON.sha")" ]]; then
    rm -f "$ICON"; note_removed "Corral icon"
  fi
  rm -f "$ICON.sha"
  if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$TARGET_HOME/.local/share/applications" >/dev/null 2>&1 || true
  fi
  for f in "$TARGET_HOME/.gradle/init.d/corral-gate.gradle" "$TARGET_HOME/.gradle/init.d/corral-gate.gradle.kts" \
           "$TARGET_HOME/.config/corral/gradle-tasks" "$TARGET_HOME/.config/corral/rules" \
           "$TARGET_HOME/.config/corral/daemon.env" "$TARGET_HOME/.config/corral/ui.conf"; do
    if [[ -e "$f" ]]; then rm -f "$f"; note_removed "~/${f#"$TARGET_HOME"/}"; fi
  done
  if [[ -e "$CORRAL_HOME" ]]; then rm -rf "$CORRAL_HOME"; note_removed "~/.corral"; fi

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

  if [[ -n "$REMOVED" ]]; then
    echo "Removed: ${REMOVED%, }"
  else
    echo "Removed: nothing (no corral install found here)"
  fi
  if [[ -n "$KEPT_SHIMS" ]]; then
    echo "Left alone (not installed by corral; unrecognised shim content):$KEPT_SHIMS"
  fi
  if [[ "$REMOVED" == *"~/bin/adb,"* ]]; then
    echo "Note: if ~/bin was the only place adb resolved from, restore the real adb onto PATH."
  fi
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

# The first real adb under <sdk-root>/platform-tools, trying the platform's own name first (see
# ADB_NAMES), or nothing. `adb.exe` is probed only on Windows.
find_sdk_adb() { # find_sdk_adb <sdk-root>
  local root="$1/platform-tools" n
  for n in "${ADB_NAMES[@]}"; do
    if [[ -x "$root/$n" ]]; then echo "$root/$n"; return 0; fi
  done
  return 1
}

detect_adb() {
  local cand
  if [[ -n "$ADB_ARG" ]]; then echo "$ADB_ARG"; return 0; fi
  # --home is a sandbox: install_sdk_shim overwrites the adb it resolves, so an adb outside
  # TARGET_HOME must never be used. Only an SDK inside the root is acceptable (explicit --adb
  # above is the operator opting in to a specific path).
  #
  # The standard Windows SDK lives at %LOCALAPPDATA%\Android\Sdk. It is spelled TARGET_HOME-relative
  # (`$TARGET_HOME/AppData/Local/Android/Sdk`) and deliberately never read from the LOCALAPPDATA
  # variable: for a normal Windows install $HOME is %USERPROFILE%, so the two name the same
  # directory and nothing is missed, while under --home (or any HOME override) LOCALAPPDATA names
  # the OPERATOR's SDK — outside the install root, which is exactly the reach-out both branches
  # below exist to refuse.
  #
  # That candidate is added ONLY when IS_WINDOWS=1. POSIX never looked there, and resolving an
  # `adb` from an AppData path on POSIX both changes what the Linux installer does and lets
  # install_sdk_shim wrap an adb the Linux gate was never about — on a host whose sandbox holds
  # only that path, ungated HEAD returns 0 and shims it where the pre-change installer refused.
  # scripts/test_install.sh now runs the POSIX byte-for-byte guard with an AppData-path SDK present,
  # so a missing gate here fails a row instead of silently changing Linux behaviour.
  local -a sdk_roots=("$TARGET_HOME/Android/Sdk")
  if [[ $IS_WINDOWS -eq 1 ]]; then sdk_roots+=("$TARGET_HOME/AppData/Local/Android/Sdk"); fi
  if [[ -n "$HOME_ARG" ]]; then
    for cand in "${sdk_roots[@]}"; do
      if cand=$(find_sdk_adb "$cand"); then echo "$cand"; return 0; fi
    done
    return 1
  fi
  for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${sdk_roots[@]}"; do
    [[ -n "$cand" ]] || continue
    if cand=$(find_sdk_adb "$cand"); then echo "$cand"; return 0; fi
  done
  for cand in "${ADB_NAMES[@]}"; do
    if command -v "$cand" >/dev/null 2>&1; then command -v "$cand"; return 0; fi
  done
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
  # The refusal names exactly the candidates that were probed on THIS platform: the AppData SDK is
  # a Windows-only candidate (see detect_adb), so a POSIX host is not told to look in a directory
  # this installer never opens. The POSIX branch is the pre-change installer's text verbatim, so a
  # POSIX refusal is byte-for-byte unchanged; the parity rows in scripts/test_install.sh pin that.
  if [[ $IS_WINDOWS -eq 1 ]]; then
    echo "ERROR: no adb under $TARGET_HOME (looked in $TARGET_HOME/Android/Sdk/platform-tools and" >&2
    echo "       $TARGET_HOME/AppData/Local/Android/Sdk/platform-tools); refusing to shim one from outside." >&2
    echo "       A --home install deliberately ignores \$ANDROID_HOME, \$ANDROID_SDK_ROOT and \$LOCALAPPDATA" >&2
    echo "       even when they point at an SDK, and ignores PATH — because install_sdk_shim OVERWRITES" >&2
    echo "       the adb it resolves. Put an SDK inside the root, or pass --adb PATH for one you accept" >&2
    echo "       being rewritten." >&2
  else
    echo "ERROR: no adb at $TARGET_HOME/Android/Sdk/platform-tools/adb; refusing to shim one from outside." >&2
    echo "       A --home install deliberately ignores \$ANDROID_HOME and \$ANDROID_SDK_ROOT even when they" >&2
    echo "       point inside the root at a non-standard path, and ignores PATH — because" >&2
    echo "       install_sdk_shim OVERWRITES the adb it resolves. Put an SDK at the path above, or pass" >&2
    echo "       --adb PATH for one you accept being rewritten." >&2
  fi
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
if [[ $IS_WINDOWS -eq 0 ]]; then
  if want_ui && [[ ! -f "$SCRIPT_DIR/corral-ui" ]]; then
    echo "ERROR: scripts/corral-ui missing (incomplete bundle); cannot install the ui shim" >&2
    exit 1
  fi
  if want_ui && [[ ! -f "$SCRIPT_DIR/corral.png" ]]; then
    echo "ERROR: $SCRIPT_DIR/corral.png missing (incomplete bundle); cannot install the launcher icon" >&2
    exit 1
  fi
fi
# Both the ~/bin wrapper and the SDK shim copy this file; validate it before anything is moved so an
# incomplete bundle fails with the filesystem completely untouched (no adb stashed aside).
if [[ $IS_WINDOWS -eq 1 ]]; then
  for f in corral.bat adb-wrapper.bat; do
    if [[ ! -f "$SCRIPT_DIR/$f" ]]; then
      echo "ERROR: $SCRIPT_DIR/$f missing from the bundle; the Windows shims were NOT installed." >&2
      exit 1
    fi
  done
elif [[ ! -f "$SCRIPT_DIR/adb-wrapper" ]]; then
  echo "ERROR: $SCRIPT_DIR/adb-wrapper missing from the bundle; the adb wrapper and SDK adb shim were NOT installed." >&2
  exit 1
fi

echo "==> Installing to $TARGET_HOME"
mkdir -p "$CORRAL_HOME" "$BIN_DIR"
if [[ $IS_WINDOWS -eq 0 ]]; then mkdir -p "$UNIT_DIR"; fi
cp "$JAR" "$CORRAL_HOME/corral-daemon-all.jar"
if [[ $IS_WINDOWS -eq 1 ]]; then
  # The bundle ships .bat twins; on Windows they replace the POSIX shims. An extensionless POSIX
  # `adb` on the Windows PATH would shadow/confuse the real adb.exe, so it is never installed here.
  cp "$SCRIPT_DIR/corral.bat" "$BIN_DIR/corral.bat" \
    || { echo "ERROR: cannot install $BIN_DIR/corral.bat." >&2; exit 1; }
  cp "$SCRIPT_DIR/adb-wrapper.bat" "$BIN_DIR/adb-wrapper.bat" \
    || { echo "ERROR: cannot install $BIN_DIR/adb-wrapper.bat." >&2; exit 1; }
  echo "Installed: corral.bat, adb-wrapper.bat ($BIN_DIR) — no POSIX corral/adb shim on Windows"
  # F-NEW-1 on the INSTALL path: a box installed before the .bat path still carries the bash
  # `adb`/`corral`/`corral-ui` the old installer put on its PATH, and until now only --uninstall
  # ever looked at them — so an upgrade left them there, including the stray bash `adb` finding F2
  # was about, still sitting in front of the real adb.exe. They are OURS (the marker decides,
  # exactly as on uninstall, so a foreign file is never touched), so installing removes them.
  # Silence about either outcome is the row-15 defect this file already fixed once: both are named.
  remove_shim "$BIN_DIR/adb" "$MARKER_ADB" '~/bin/adb'
  remove_shim "$BIN_DIR/corral" "$MARKER_CORRAL" '~/bin/corral'
  remove_shim "$BIN_DIR/corral-ui" "$MARKER_CORRAL_UI" '~/bin/corral-ui'
  if [[ -n "$REMOVED" ]]; then
    echo "Removed legacy POSIX shims from an earlier corral install: ${REMOVED%, }"
  fi
  if [[ -n "$KEPT_SHIMS" ]]; then
    echo "Left alone (not a corral shim; unrecognised content):$KEPT_SHIMS"
    echo "WARNING: a bash shim left on the Windows PATH shadows/confuses the real adb.exe — an"
    echo "         extensionless 'adb' especially. Remove or rename it yourself; corral will not."
  fi
else
  if ! cp "$SCRIPT_DIR/adb-wrapper" "$BIN_DIR/adb" || ! chmod +x "$BIN_DIR/adb"; then
    echo "ERROR: cannot install the executable adb wrapper at $BIN_DIR/adb." >&2
    exit 1
  fi
  cp "$SCRIPT_DIR/corral"      "$BIN_DIR/corral"; chmod +x "$BIN_DIR/corral"
fi

if want_ui && [[ $IS_WINDOWS -eq 1 ]]; then
  # The bundle has no corral-ui.bat, so there is no working Windows UI shim or launcher to install.
  # Copy the jar so it can be started with java -jar, and say plainly what is missing.
  cp "$UI_JAR" "$CORRAL_HOME/corral-ui-all.jar" || { echo "ERROR: cannot copy $UI_JAR to $CORRAL_HOME" >&2; exit 1; }
  echo "Installed: corral-ui jar ($CORRAL_HOME/corral-ui-all.jar) — no corral-ui shim or app-grid launcher on Windows"
elif want_ui; then
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
# The SDK shim copies the POSIX adb-wrapper over the SDK's adb; on Windows that is the same stray
# POSIX-script-on-the-adb-path defect as the ~/bin shim (and the real binary is adb.exe anyway), so
# it is not installed there — reported honestly at the end.
[[ $SHIM -eq 1 && $IS_WINDOWS -eq 0 ]] && install_sdk_shim

# gradle gate: init.d script + editable task-pattern config
if [[ ! -f "$SCRIPT_DIR/corral-gate.gradle.kts" ]]; then
  echo "ERROR: scripts/corral-gate.gradle.kts missing (incomplete bundle); cannot install the gradle gate" >&2
  exit 1
fi
mkdir -p "$TARGET_HOME/.gradle/init.d" "$TARGET_HOME/.config/corral"
if [[ $IS_WINDOWS -eq 1 ]]; then CORRAL_SHIM="$BIN_DIR/corral.bat"; else CORRAL_SHIM="$BIN_DIR/corral"; fi
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

if [[ $IS_WINDOWS -eq 0 ]]; then
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

if [[ $IS_WINDOWS -eq 1 ]]; then
  echo
  echo "==> Installed to $TARGET_HOME (platform: Windows, uname: $UNAME_S)"
  echo "Installed Windows shims: corral.bat, adb-wrapper.bat ($BIN_DIR) — run corral from cmd.exe/PowerShell (PATH note at the end)"
  echo "Installed gradle gate (~/.gradle/init.d/corral-gate.gradle.kts -> $CORRAL_SHIM)"
  echo "NOT done on Windows:"
  echo "  - no POSIX corral/adb shim (an extensionless 'adb' on the Windows PATH would shadow the real adb.exe)"
  echo "  - no systemd unit and no systemctl call (this host has no systemd user session)"
  echo "  - no corral-ui shim and no app-grid launcher (the bundle ships no corral-ui.bat)"
  if want_ui; then
    echo "    start the console instead: \"$JAVA_BIN\" -jar \"$CORRAL_HOME/corral-ui-all.jar\""
  else
    echo "    (corral-ui not installed; start corral-ui-all.jar with: \"$JAVA_BIN\" -jar <path-to-corral-ui-all.jar>)"
  fi
  echo
  # On Linux the daemon gets CORRAL_ADB from the systemd unit's Environment=; Windows has no unit,
  # and Java cannot exec a bare `adb`, so the start command itself has to carry it or the daemon
  # comes up with devices:[]. One line per shell: cmd.exe needs `set "K=V"` and `&&`, PowerShell
  # needs `$env:K='V'` and the `&` call operator — neither form is valid in the other shell, so both
  # are printed rather than one ambiguous line.
  # Quoting: cmd.exe's `set "K=V"` keeps a path with spaces, `&`, `^`, `!`, `()` and a LONE `%`
  # intact — the outer quotes neutralize them (verified by running the printed line in a real
  # cmd.exe). Only a `"` or a `%NAME%` pair cannot be spelled there at all, and cmd_unspellable
  # says so instead of printing a command that silently sets a different value. PowerShell's paths
  # are single-quoted through ps_quote, so a `'` no longer ends the string (it produced "Unexpected
  # token") and `$`/`&`/`%` in a path stay literal.
  DAEMON_FLAGS="-Xms24m -Xmx64m -XX:+UseSerialGC -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:TieredStopAtLevel=1"
  WIN_JAVA=$(win_path "$JAVA_BIN")
  WIN_JAR=$(win_path "$CORRAL_HOME/corral-daemon-all.jar")
  CMD_NOTE=0
  if cmd_unspellable "$WIN_JAVA" || cmd_unspellable "$WIN_JAR"; then CMD_NOTE=1; fi
  if [[ -n "$ADB" ]]; then
    WIN_ADB=$(win_path "$ADB")
    cmd_unspellable "$WIN_ADB" && CMD_NOTE=1
    echo "Start the daemon in the foreground (CORRAL_ADB is what lets it see devices — pick your shell):"
    if [[ $CMD_NOTE -eq 0 ]]; then
      echo "  cmd.exe     set \"CORRAL_ADB=$WIN_ADB\" && \"$WIN_JAVA\" $DAEMON_FLAGS -jar \"$WIN_JAR\""
    fi
    echo "  PowerShell  \$env:CORRAL_ADB=$(ps_quote "$WIN_ADB"); & $(ps_quote "$WIN_JAVA") $DAEMON_FLAGS -jar $(ps_quote "$WIN_JAR")"
  else
    echo "Start the daemon in the foreground — no adb was found, so the command carries no CORRAL_ADB"
    echo "  and the daemon will report no devices (install platform-tools, or re-run with --adb PATH):"
    if [[ $CMD_NOTE -eq 0 ]]; then
      echo "  cmd.exe     \"$WIN_JAVA\" $DAEMON_FLAGS -jar \"$WIN_JAR\""
    fi
    echo "  PowerShell  & $(ps_quote "$WIN_JAVA") $DAEMON_FLAGS -jar $(ps_quote "$WIN_JAR")"
  fi
  if [[ $CMD_NOTE -eq 1 ]]; then
    echo "  cmd.exe     (no line above: a path contains a %NAME% pair or a \" that cmd.exe expands or"
    echo "               strips and cannot be escaped at the prompt — use the PowerShell line)"
  fi
  echo
  echo "Smoke-test from cmd.exe/PowerShell — add %USERPROFILE%\\bin to your PATH first (the PATH and"
  echo "  CORRAL_REAL_ADB exports this install wrote went to .bashrc/.zshrc only; cmd.exe/PowerShell never read them):"
  echo "  curl -s http://127.0.0.1:8798/api/devices"
  echo "  corral devices"
  echo
  if [[ $SHIM_ACTIVE -eq 1 ]]; then
    echo "SDK adb shim is active — the SDK's platform-tools path is lease-gated."
  elif [[ $SHIM -eq 0 ]]; then
    echo "SDK adb shim is off (--no-sdk-shim, as requested) — absolute-path adb calls are NOT lease-gated."
  else
    echo "SDK adb shim is NOT installed on Windows — adb is NOT lease-gated by this install."
  fi
else
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
fi