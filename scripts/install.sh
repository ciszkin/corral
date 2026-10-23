#!/usr/bin/env bash
# corral install.sh — install corral-daemon, the adb wrapper, the corral CLI, the gradle gate and
# (optionally) the corral-ui console — on Linux (systemd user unit) and on Windows from Git
# Bash/MSYS (per-user Scheduled Task, .bat shims). User-scoped (no root). Idempotent; safe to re-run.
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

# schtasks can exit 0 and still print an error line, so success needs BOTH a zero exit and clean
# output. Used for create, end, query and delete; a delete of a task that does not exist is not an error.
task_ok() { # task_ok <exit-code> <output>
  [ "$1" -eq 0 ] || return 1
  ! printf '%s' "$2" | grep -qiE '^(error|failed)([[:space:]:]|$)|access is denied'
}

# The fields a refused create could have changed, and the only evidence that one did not. schtasks
# pads each value with spaces and ends lines with CRLF; `Schedule:` also exists, so match the exact
# names. Compared as one block, before versus after the create attempts.
task_fingerprint() { # task_fingerprint <schtasks /query /fo LIST /v output>
  printf '%s\n' "$1" | tr -d '\r' \
    | grep -E '^(Status|Task To Run|Schedule Type|Last Run Time):' | sed 's/[[:space:]]*$//'
}

# Git Bash rewrites native-program arguments that start with `/` (e.g. /create) into paths. These
# two switches are the documented opt-out; without them install.sh's schtasks calls reach the
# executable as C:/Program Files/Git/create. The printed elevated command is for cmd/PowerShell.
schtasks_run() { MSYS2_ARG_CONV_EXCL='*' MSYS_NO_PATHCONV=1 schtasks "$@"; }

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
MARKER_DAEMON_BAT='rem corral daemon launcher'

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
    # /end stops the task's cmd.exe wrapper, NOT the java.exe it launched: the daemon survives it
    # and keeps the API port and ~/.corral. PowerShell (wmic is gone from Windows 11) finds OUR java
    # by the daemon jar in its command line — never by port (configurable, and another process may
    # own it) and never all java.exe — and a stop is CLAIMED only after a re-query shows no
    # matching process remains. No match is success: there was nothing to stop.
    #
    # The match is confined to THIS install root, and that guard is the whole point of this block.
    # Matching the jar FILE NAME alone (what this did before) over-matches: on a box with one
    # Corral, a sandbox's "our daemon" and the operator's live CorralDaemon are the same process,
    # so `--home <sandbox> --uninstall` — and the shipped test suite, whose stub dirs mostly did
    # not carry a powershell.exe — killed the production daemon and then claimed "daemon stopped".
    # The Scheduled Task has been sandbox-guarded for the same reason (see the TASK_STATE=sandbox
    # branch below); note that SANDBOX=1 never even enters this branch (line above), but a sandbox
    # is usually spelled HOME=<sandbox> --home <sandbox>, which is SANDBOX=0 — so the containment
    # check is what actually protects it.
    #
    # Containment is decided on the JAR ARGUMENT, never on the whole command line. The query takes
    # the process's `-jar` operand (or, failing that, the token that names the daemon jar) and tests
    # THAT path, so a process which merely carries the root in a non-jar token — `-Dfoo=<root>`, a
    # classpath entry, a java.exe living under the root — while running its jar from elsewhere is
    # NOT ours. Two tiers are ours: the jar under $CORRAL_HOME (primary: the installed location),
    # and, as the fallback for a daemon started from a COPIED jar, a jar of that name anywhere under
    # $TARGET_HOME. A jar path that is not absolute (a relative `-jar`, a command line that only
    # mentions the name) is also left running: it cannot be SHOWN to be ours, and an uninstall never
    # kills on a guess. Any other match is named, never stopped.
    POWERSHELL=""
    for ps in powershell.exe powershell pwsh.exe pwsh; do
      if command -v "$ps" >/dev/null 2>&1; then POWERSHELL="$ps"; break; fi
    done
    DAEMON_JAR_NAME='corral-daemon-all.jar'
    # Both sides are compared separator-blind and case-insensitively (inside PowerShell): win_path
    # renders the MSYS root the way cmd.exe/PowerShell spell it (C:\...), the process command line
    # may carry either separator, and Windows paths are case-insensitive. The roots are rendered
    # only when there IS a PowerShell to ask, so the no-PowerShell path does not depend on
    # cygpath/tr for a value it never uses.
    DAEMON_ROOT=""
    DAEMON_CORRAL_ROOT=""
    # PIDs whose JAR ARGUMENT is under this install go to DAEMON_PIDS; a jar outside both roots goes
    # to DAEMON_OUTSIDE, and one that cannot be resolved to an absolute path goes to
    # DAEMON_UNRESOLVED — neither is ever stopped, both are named. DAEMON_QUERY_FAILED records that
    # the query itself failed, which is NOT the same as "nothing matched" and is never claimed as a
    # stop or a no-op.
    DAEMON_QUERY_FAILED=0
    DAEMON_PIDS=""
    DAEMON_OUTSIDE=""
    DAEMON_UNRESOLVED=""
    daemon_scan() {
      local raw tag pid
      if raw=$(CORRAL_DAEMON_JAR="$DAEMON_JAR_NAME" CORRAL_INSTALL_ROOT="$DAEMON_ROOT" \
        CORRAL_DAEMON_HOME="$DAEMON_CORRAL_ROOT" \
        "$POWERSHELL" -NoProfile -NonInteractive -Command \
        "\$jar = \$env:CORRAL_DAEMON_JAR.Replace('\','/'); \$root = \$env:CORRAL_INSTALL_ROOT.Replace('\','/').TrimEnd('/'); \$chome = \$env:CORRAL_DAEMON_HOME.Replace('\','/').TrimEnd('/'); if (\$root -eq '') { \$root = ':no-install-root:' }; if (\$chome -eq '') { \$chome = \$root + '/.corral' }; Get-CimInstance Win32_Process | Where-Object { \$_.Name -match '^javaw?\.exe\$' -and \$_.CommandLine -and \$_.CommandLine.Replace('\','/').IndexOf(\$jar, [StringComparison]::OrdinalIgnoreCase) -ge 0 } | ForEach-Object { \$cl = \$_.CommandLine.Replace('\','/'); \$jp = ''; \$m = [regex]::Match(\$cl, '(?i)(?:^|\s)-jar\s+(?:\"([^\"]+)\"|(\S+))'); if (\$m.Success) { if (\$m.Groups[1].Length -gt 0) { \$jp = \$m.Groups[1].Value } else { \$jp = \$m.Groups[2].Value } } else { foreach (\$t in (\$cl -split '\s+')) { \$u = \$t.Trim('\"'); if (\$u.EndsWith('/' + \$jar, [StringComparison]::OrdinalIgnoreCase)) { \$jp = \$u; break } } }; if (\$jp -ne '') { \$jp = \$jp.Replace('\','/').TrimEnd('\"') }; if (\$jp -eq '' -or -not (\$jp.StartsWith('/') -or \$jp -match '^[A-Za-z]:/')) { 'rel ' + \$_.ProcessId } elseif (\$jp.StartsWith(\$chome + '/', [StringComparison]::OrdinalIgnoreCase) -or \$jp.StartsWith(\$root + '/', [StringComparison]::OrdinalIgnoreCase)) { 'in ' + \$_.ProcessId } else { 'out ' + \$_.ProcessId } }" \
        2>/dev/null); then
        DAEMON_QUERY_FAILED=0
      else
        DAEMON_QUERY_FAILED=1; raw=""
      fi
      DAEMON_PIDS=""
      DAEMON_OUTSIDE=""
      DAEMON_UNRESOLVED=""
      while read -r tag pid; do
        case "$tag" in
          in)  [[ -n "$pid" ]] && DAEMON_PIDS="${DAEMON_PIDS:+$DAEMON_PIDS }$pid";;
          out) [[ -n "$pid" ]] && DAEMON_OUTSIDE="${DAEMON_OUTSIDE:+$DAEMON_OUTSIDE }$pid";;
          rel) [[ -n "$pid" ]] && DAEMON_UNRESOLVED="${DAEMON_UNRESOLVED:+$DAEMON_UNRESOLVED }$pid";;
        esac
      done <<EOF
$(printf '%s\n' "$raw" | tr -d '\r')
EOF
    }
    daemon_before=""
    daemon_left=""
    daemon_outside=""
    daemon_unresolved=""
    if [[ -n "$POWERSHELL" ]]; then
      DAEMON_ROOT=$(win_path "$TARGET_HOME" | tr '\\' '/'); DAEMON_ROOT="${DAEMON_ROOT%/}"
      DAEMON_CORRAL_ROOT=$(win_path "$CORRAL_HOME" | tr '\\' '/'); DAEMON_CORRAL_ROOT="${DAEMON_CORRAL_ROOT%/}"
      daemon_scan
      daemon_before=$DAEMON_PIDS; daemon_outside=$DAEMON_OUTSIDE; daemon_unresolved=$DAEMON_UNRESOLVED
      daemon_left=$daemon_before
    fi

    # Stop the running task BEFORE deleting it: deleting a task does not stop its process.
    # A task that is not running fails the /end and a box that never had the task fails the delete;
    # neither is worth failing on. The process stop runs regardless, because a previous uninstall
    # may have deleted the task and left the java child running — exactly what blocks ~/.corral.
    if command -v schtasks >/dev/null 2>&1; then
      schtasks_run /end /tn CorralDaemon >/dev/null 2>&1 || true
      del_out=$(schtasks_run /delete /tn CorralDaemon /f 2>&1); del_rc=$?
    else
      del_rc=1; del_out=""
    fi

    if [[ -n "$POWERSHELL" ]]; then
      daemon_ids=""
      for p in $daemon_before; do daemon_ids="${daemon_ids:+$daemon_ids,}$p"; done
      if [[ -n "$daemon_ids" ]]; then
        # The install root is passed to the STOP, not only to the query: the test stub refuses a
        # stop that arrives without it, so the guard the suite advertises is the one that actually
        # runs (a regression to an unscoped stop fails the suite instead of killing a daemon), and a
        # future PowerShell-side check has the value it needs.
        CORRAL_INSTALL_ROOT="$DAEMON_ROOT" \
          "$POWERSHELL" -NoProfile -NonInteractive -Command \
          "Stop-Process -Id $daemon_ids -Force -ErrorAction SilentlyContinue" 2>/dev/null
        # Stop-Process is not guaranteed synchronous; poll briefly before deciding what to claim.
        for _ in 1 2 3 4 5; do
          daemon_scan; daemon_left=$DAEMON_PIDS
          daemon_outside=$DAEMON_OUTSIDE; daemon_unresolved=$DAEMON_UNRESOLVED
          [[ $DAEMON_QUERY_FAILED -eq 0 && -z "$daemon_left" ]] && break
          sleep 0.2 2>/dev/null || true
        done
      fi
      if [[ $DAEMON_QUERY_FAILED -eq 1 ]]; then
        echo "WARNING: could not verify whether the corral daemon process is still running"
        echo "         (the PowerShell process query failed), so no stop is claimed."
      elif [[ -n "$daemon_left" ]]; then
        echo "WARNING: the corral daemon process could NOT be stopped (still running: PID $(echo $daemon_left));"
        echo "         it may still serve the API and keep ~/.corral from being removed."
      fi
      if [[ -n "$daemon_outside" ]]; then
        echo "NOTE: no daemon outside this install root was touched: PID $(echo $daemon_outside)"
        echo "      runs $DAEMON_JAR_NAME from a jar path outside both $CORRAL_HOME and $TARGET_HOME,"
        echo "      so it belongs to another install and was left running."
      fi
      if [[ -n "$daemon_unresolved" ]]; then
        echo "NOTE: left a daemon running whose jar could not be resolved: PID $(echo $daemon_unresolved)"
        echo "      its command line names $DAEMON_JAR_NAME but not as an absolute -jar path (a"
        echo "      relative jar, say), so it cannot be shown to belong to this install root."
      fi
    else
      echo "WARNING: PowerShell was not found, so the corral daemon process could not be identified;"
      echo "         if java.exe is still running the daemon jar it keeps ~/.corral from being removed."
    fi

    if task_ok "$del_rc" "$del_out"; then
      if [[ -z "$POWERSHELL" || $DAEMON_QUERY_FAILED -eq 1 || -n "$daemon_left" ]]; then
        note_removed "Scheduled Task CorralDaemon"
      elif [[ -n "$daemon_before" && -n "$daemon_outside$daemon_unresolved" ]]; then
        note_removed "Scheduled Task CorralDaemon (daemon stopped; another daemon process was left running: PID $(echo $daemon_outside $daemon_unresolved))"
      elif [[ -n "$daemon_before" ]]; then
        note_removed "Scheduled Task CorralDaemon (daemon stopped)"
      elif [[ -n "$daemon_outside" ]]; then
        note_removed "Scheduled Task CorralDaemon (no daemon in this install root; a daemon outside it was left untouched)"
      elif [[ -n "$daemon_unresolved" ]]; then
        note_removed "Scheduled Task CorralDaemon (no daemon in this install root; a daemon whose jar could not be resolved was left untouched)"
      else
        note_removed "Scheduled Task CorralDaemon (no daemon process was running)"
      fi
    fi
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
    remove_shim "$BIN_DIR/corral-daemon.bat" "$MARKER_DAEMON_BAT" '~/bin/corral-daemon.bat'
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
  if [[ -e "$CORRAL_HOME" ]]; then
    # rm -rf fails on Windows while the daemon still holds the jar/DB. Only claim the removal when
    # the directory is really gone; otherwise name what survived and why (rm's own message).
    rm_err=$(rm -rf "$CORRAL_HOME" 2>&1)
    if [[ -e "$CORRAL_HOME" ]]; then
      left=$(ls -A "$CORRAL_HOME" 2>/dev/null | tr '\n' ' ')
      echo "WARNING: ~/.corral could NOT be removed${left:+ (still holds: ${left% })}"
      if [[ -n "$rm_err" ]]; then echo "         $rm_err"; fi
    else
      note_removed "~/.corral"
    fi
  fi

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

# Replace a file WITHOUT rewriting it in place. `cp` onto an existing file truncates and rewrites the
# same inode, so a running JVM reading that jar lazily reads new bytes at old offsets and stays alive
# but unable to load a class — a live zombie the service manager never restarts (review pass 42 §1).
# A temp file in the target's own directory plus a rename is atomic: the running JVM keeps the old
# inode, the next start gets the new one. On Windows the rename itself fails while a JVM holds the
# jar open, and the caller must say the replacement did NOT happen rather than claim success.
replace_file() { # replace_file <src> <dst> <label> [mode]
  # The optional mode is set on the TEMP file, before the rename: the shims are executables, and a
  # rename carries the mode over, so `mv -f` alone would install a non-executable wrapper.
  local src="$1" dst="$2" label="$3" mode="${4:-}" tmp="$2.new.$$"
  if ! cp "$src" "$tmp"; then
    rm -f "$tmp"
    echo "ERROR: cannot copy $src to $tmp; $label was NOT replaced." >&2
    return 1
  fi
  if [ -n "$mode" ] && ! chmod "$mode" "$tmp"; then
    rm -f "$tmp"
    echo "ERROR: cannot set mode $mode on $tmp; $label was NOT replaced." >&2
    return 1
  fi
  if ! mv -f "$tmp" "$dst"; then
    rm -f "$tmp"
    echo "ERROR: cannot rename $tmp over $dst; $label was NOT replaced (the file still there is the old one)." >&2
    return 1
  fi
}

# --- the Windows .bat twins: never write a file whose bytes are already right -------------------
# cmd.exe does NOT keep a batch file's handle open across commands: it RE-OPENS the file by NAME and
# resumes at the saved byte offset. Rewriting corral.bat under an in-flight `corral devices` call
# therefore makes that call read the NEW file at the OLD offset — measured on this box as
# `'C:\Program' is not recognized`, rc=9009, with the CLI's work already done — and the same race
# against adb-wrapper.bat turned a successful adb call's rc from 0 into 1 (QA pass 43 §5 F2).
# The temp-file rename that makes the POSIX shims safe does NOT transfer: cmd.exe re-opens the NAME,
# so it lands on the new inode anyway, and the QA proved the rename atomic and the `.bat` still
# garbled. What works is not writing at all. The twins are release-FROZEN content (the release-
# varying logic lives in the jar, which IS replaced atomically), so a twin that is already
# byte-identical to the bundle is left completely untouched: same inode, same mtime, no window of
# any kind. When the bytes genuinely DIFFER (a release that edits a twin) the new content still has
# to land, so it is written atomically — no caller can ever see a half-written file — and the
# summary names the one window that remains, a call already in flight ACROSS that write. Refusing
# instead would leave the box permanently unable to receive a changed twin; see row31-report.md.
# `cmp` is the platform's own (Git Bash and Cygwin both ship coreutils), so this adds no dependency.
BAT_KEPT=""
BAT_WRITTEN=""
BAT_REPLACED=""
bat_install() { # bat_install <file name>
  local name="$1" src="$SCRIPT_DIR/$1" dst="$BIN_DIR/$1"
  if [ -f "$dst" ] && cmp -s "$src" "$dst"; then
    BAT_KEPT="$BAT_KEPT $name"
    return 0
  fi
  if [ -e "$dst" ]; then BAT_REPLACED="$BAT_REPLACED $name"; fi
  replace_file "$src" "$dst" "$name" || return 1
  BAT_WRITTEN="$BAT_WRITTEN $name"
}
bat_list() { # bat_list <space-separated names> -> "a.bat, b.bat"
  local out="" n
  for n in $1; do out="${out:+$out, }$n"; done
  printf '%s' "$out"
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
  # Both copies go through replace_file, never a `cp` in place: bash reads a script as it runs and
  # resumes at the same byte offset after each top-level command, so rewriting the wrapper under a
  # call that is inside its gate `curl` makes the rest of that call come from the NEW file at the OLD
  # offset — a garbled line, or a different line, and often a silent success without adb ever running
  # (review pass 43 §2). A temp file plus a rename leaves the running call on the old inode. The 755
  # is set on the temp file because the rename carries the mode: the shim must stay executable.
  # On any failure: the temp file is gone, the old file is still in place, and the caller restores
  # the real adb rather than leave the SDK with no adb at all.
  if ! replace_file "$SCRIPT_DIR/adb-wrapper" "$adb_path" "the SDK adb shim" 755 \
     || ! grep -q 'corral adb wrapper' "$adb_path" 2>/dev/null; then
    if [ -e "$adb_path.real" ]; then
      mv -f "$adb_path.real" "$adb_path"
      echo "ERROR: failed to install the SDK adb shim at $adb_path; restored the real adb from $adb_path.real." >&2
    else
      # No adb.real: the stash mv never ran, so whatever is here now is either a valid wrapper left by a
      # previous install (keep it) or — impossible after replace_file, which leaves the old file whole —
      # something that is not our wrapper (remove only that). Never a real adb: a real adb would have
      # been stashed to adb.real above.
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
if ! replace_file "$JAR" "$CORRAL_HOME/corral-daemon-all.jar" "corral-daemon-all.jar"; then
  if [[ $IS_WINDOWS -eq 1 ]]; then
    # The Windows limit: a running JVM keeps its jar open, so no rename lands. Ending the task first
    # instead can leave the box with NO daemon (the Scheduled Task create is refused for an
    # unelevated token), so this refuses loudly and names what is still running. Review pass 42 §1.
    echo "       A running JVM on Windows keeps its jar open. The daemon is still running the OLD jar" >&2
    echo "       and still serving the previous version; nothing was replaced. Stop it and re-run:" >&2
    echo "       schtasks /end /tn CorralDaemon, then stop the java.exe whose command line runs" >&2
    echo "       corral-daemon-all.jar (Task Manager, or Stop-Process on its PID)." >&2
  fi
  exit 1
fi
if [[ $IS_WINDOWS -eq 1 ]]; then
  # The bundle ships .bat twins; on Windows they replace the POSIX shims. An extensionless POSIX
  # `adb` on the Windows PATH would shadow/confuse the real adb.exe, so it is never installed here.
  # bat_install skips the write entirely when the installed twin already matches the bundle, which
  # is what keeps an in-flight cmd.exe call off a rewritten file (see the helper).
  bat_install corral.bat \
    || { echo "ERROR: cannot install $BIN_DIR/corral.bat." >&2; exit 1; }
  bat_install adb-wrapper.bat \
    || { echo "ERROR: cannot install $BIN_DIR/adb-wrapper.bat." >&2; exit 1; }
  # Name only what this run actually did: a twin that was already current is reported as unchanged,
  # never under "Installed:".
  if [[ -n "$BAT_WRITTEN" ]]; then
    echo "Installed: $(bat_list "$BAT_WRITTEN") ($BIN_DIR) — no POSIX corral/adb shim on Windows"
  fi
  if [[ -n "$BAT_KEPT" ]]; then
    echo "Unchanged: $(bat_list "$BAT_KEPT") ($BIN_DIR) — already byte-identical to the bundle, so NOT rewritten (a call in flight keeps the file it started)"
  fi
  if [[ -n "$BAT_REPLACED" ]]; then
    echo "WARNING: rewrote $(bat_list "$BAT_REPLACED") — the bundle's bytes differ from the installed file."
    echo "         cmd.exe re-opens a running .bat by name, so a call in flight ACROSS this write can"
    echo "         execute a line at a stale offset (QA pass 43 §5 F2): 'is not recognized', rc 9009,"
    echo "         or no line at all. If a corral/adb call was running during this install, its output"
    echo "         and exit code cannot be trusted — re-run that call now and read the result. Nothing"
    echo "         can protect a call already in flight; an unchanged twin is never written at all."
  fi
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
  # Same atomic replace as the jars and the SDK shim: a PATH-resolved adb call in flight while the
  # wrapper is being replaced keeps reading the old inode (review pass 43 §2), and both copies are
  # checked — the corral shim used to be installed unchecked, so a failed copy still reached the
  # success summary below.
  replace_file "$SCRIPT_DIR/adb-wrapper" "$BIN_DIR/adb" "the adb wrapper" 755 \
    || { echo "ERROR: cannot install the executable adb wrapper at $BIN_DIR/adb." >&2; exit 1; }
  replace_file "$SCRIPT_DIR/corral" "$BIN_DIR/corral" "the corral shim" 755 \
    || { echo "ERROR: cannot install the corral shim at $BIN_DIR/corral." >&2; exit 1; }
fi

if want_ui && [[ $IS_WINDOWS -eq 1 ]]; then
  # The bundle has no corral-ui.bat, so there is no working Windows UI shim or launcher to install.
  # Copy the jar so it can be started with java -jar, and say plainly what is missing.
  replace_file "$UI_JAR" "$CORRAL_HOME/corral-ui-all.jar" "corral-ui-all.jar" || {
    echo "ERROR: cannot copy $UI_JAR to $CORRAL_HOME" >&2
    echo "       On Windows a running console holds its jar open; quit Corral and re-run." >&2
    exit 1
  }
  echo "Installed: corral-ui jar ($CORRAL_HOME/corral-ui-all.jar) — no corral-ui shim or app-grid launcher on Windows"
elif want_ui; then
  replace_file "$UI_JAR" "$CORRAL_HOME/corral-ui-all.jar" "corral-ui-all.jar" \
    || { echo "ERROR: cannot copy $UI_JAR to $CORRAL_HOME" >&2; exit 1; }
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

# On Windows there is no systemd unit. A per-user Scheduled Task's parent chain is svchost.exe
# (not the SSH session), so the daemon survives a logout; a foreground command does not. The
# launcher carries CORRAL_ADB because Java cannot exec a bare `adb`, and without it the daemon
# comes up with devices:[].
DAEMON_FLAGS="-Xms24m -Xmx64m -XX:+UseSerialGC -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=64m -XX:TieredStopAtLevel=1"
if [[ $IS_WINDOWS -eq 1 ]]; then
  WIN_JAVA=$(win_path "$JAVA_BIN")
  WIN_JAR=$(win_path "$CORRAL_HOME/corral-daemon-all.jar")
  WIN_ADB=""
  if [[ -n "$ADB" ]]; then WIN_ADB=$(win_path "$ADB"); fi
  DAEMON_BAT="$BIN_DIR/corral-daemon.bat"
  LAUNCHER_OK=0
  LAUNCHER_UNSPELLABLE=0
  # A `"` or a %NAME% pair cannot be spelled in a .bat at all, so do not write a launcher that
  # silently runs a different path: the summary then says the task was not created.
  if ! cmd_unspellable "$WIN_JAR" && { [[ -z "$WIN_ADB" ]] || ! cmd_unspellable "$WIN_ADB"; }; then
    if {
      printf '@echo off\r\n'
      printf 'rem corral daemon launcher: started by the CorralDaemon Scheduled Task\r\n'
      printf 'rem java lookup mirrors corral.bat: CORRAL_JAVA, then %%JAVA_HOME%%\\bin\\java.exe, then PATH.\r\n'
      printf 'setlocal\r\n'
      printf 'set "JAVA=%%CORRAL_JAVA%%"\r\n'
      printf 'if not defined JAVA if defined JAVA_HOME if exist "%%JAVA_HOME%%\\bin\\java.exe" set "JAVA=%%JAVA_HOME%%\\bin\\java.exe"\r\n'
      printf 'if not defined JAVA for %%%%d in ("%%PATH:;=" "%%") do if not defined JAVA call :find_java "%%%%~d"\r\n'
      printf 'if not defined JAVA (\r\n'
      printf '  echo corral: java not found ^(set CORRAL_JAVA or JAVA_HOME, or put java on PATH^) - failing closed\r\n'
      printf '  exit /b 1\r\n'
      printf ')\r\n'
      if [[ -n "$WIN_ADB" ]]; then printf 'set "CORRAL_ADB=%s"\r\n' "$WIN_ADB"; fi
      printf '"%%JAVA%%" %s -jar "%s"\r\n' "$DAEMON_FLAGS" "$WIN_JAR"
      printf 'exit /b %%errorlevel%%\r\n'
      printf '\r\n'
      printf ':find_java\r\n'
      printf 'rem %%~1 is one PATH entry (quotes stripped): skip empty, ".", and non-absolute entries.\r\n'
      printf 'set "e=%%~1"\r\n'
      printf 'if "%%e%%"=="" exit /b 0\r\n'
      printf 'if "%%e%%"=="." exit /b 0\r\n'
      printf 'set "abs="\r\n'
      printf 'if "%%e:~1,1%%"==":" if "%%e:~2,1%%"=="\\" set "abs=1"\r\n'
      printf 'if "%%e:~1,1%%"==":" if "%%e:~2,1%%"=="/" set "abs=1"\r\n'
      printf 'if "%%e:~0,2%%"=="\\\\" set "abs=1"\r\n'
      printf 'if not defined abs exit /b 0\r\n'
      printf "rem cmd's default PATHEXT order (.com;.exe;.bat;.cmd); first hit wins like PATH lookup.\r\n"
      printf 'for %%%%f in (com exe bat cmd) do if not defined JAVA if exist "%%~1\\java.%%%%f" set "JAVA=%%~1\\java.%%%%f"\r\n'
      printf 'exit /b 0\r\n'
    } > "$DAEMON_BAT" 2>/dev/null; then
      LAUNCHER_OK=1
    fi
  else
    LAUNCHER_UNSPELLABLE=1
  fi

  # /sc onlogon needs an elevated token; /sc once + /run works unelevated. Try the good case,
  # fall back to the one that works, and report which one actually happened.
  TASK_STATE=failed
  TASK_DENIED=0
  TASK_BOTH_DENIED=0
  TASK_ELEV=""
  TASK_READ=0
  TASK_STATUS=""
  TASK_UNTOUCHED=2   # 1 = verified unchanged, 0 = verified changed, 2 = no before-read to compare
  if [[ $LAUNCHER_OK -eq 1 ]]; then
    TASK_WIN_BAT=$(win_path "$DAEMON_BAT")
    TASK_ELEV="schtasks /create /tn CorralDaemon /tr \"$TASK_WIN_BAT\" /sc onlogon /rl LIMITED /f"
  fi
  # --home is a sandbox, and the POSIX branch skips systemctl for one; on Windows it must not
  # create, replace or delete the Scheduled Task either. This is not cosmetic: a task registered by
  # a non-elevated token is user-owned (the user SID keeps an inherited Full allow), so the create
  # SUCCEEDS there and silently repoints the live CorralDaemon at this sandbox's launcher.
  if [[ $SANDBOX -eq 1 ]]; then
    TASK_STATE=sandbox
  elif [[ $LAUNCHER_OK -eq 1 ]] && command -v schtasks >/dev/null 2>&1; then
    # "the existing task was left untouched" is a claim that needs a before and an after, not an
    # inference from the refusal; a medium-integrity token CAN read the task (verified on a real
    # box), so snapshot the fields a refused create could have changed.
    task_before=$(schtasks_run /query /tn CorralDaemon /fo LIST /v 2>&1); before_rc=$?
    if task_ok "$before_rc" "$task_before"; then task_before=$(task_fingerprint "$task_before"); else task_before=""; fi
    task_out=$(schtasks_run /create /tn CorralDaemon /tr "$TASK_WIN_BAT" /sc onlogon /rl LIMITED /f 2>&1); task_rc=$?
    if task_ok "$task_rc" "$task_out"; then
      TASK_STATE=onlogon-created
      run_out=$(schtasks_run /run /tn CorralDaemon 2>&1); run_rc=$?
      if task_ok "$run_rc" "$run_out"; then TASK_STATE=onlogon; fi
    elif printf '%s' "$task_out" | grep -qi 'access is denied'; then
      TASK_DENIED=1
      once_out=$(schtasks_run /create /tn CorralDaemon /tr "$TASK_WIN_BAT" /sc once /st 00:00 /rl LIMITED /f 2>&1); once_rc=$?
      if task_ok "$once_rc" "$once_out"; then
        TASK_STATE=once-norun
        run_out=$(schtasks_run /run /tn CorralDaemon 2>&1); run_rc=$?
        if task_ok "$run_rc" "$run_out"; then TASK_STATE=once; fi
      elif printf '%s' "$once_out" | grep -qi 'access is denied'; then
        # Say "both were refused" only when the fallback's own output says so too.
        TASK_BOTH_DENIED=1
      fi
      if [[ $TASK_STATE == failed ]]; then
        # Both creates failed. Read the task back before saying anything about it: a refusal
        # proves this session could not write, never that a task exists.
        task_after=$(schtasks_run /query /tn CorralDaemon /fo LIST /v 2>&1); after_rc=$?
        if task_ok "$after_rc" "$task_after"; then
          TASK_READ=1
          TASK_STATUS=$(printf '%s\n' "$task_after" | tr -d '\r' \
            | sed -n 's/^Status:[[:space:]]*//p' | head -1 | sed 's/[[:space:]]*$//')
          task_after=$(task_fingerprint "$task_after")
          if [[ -n "$task_before" ]]; then
            if [[ "$task_after" == "$task_before" ]]; then TASK_UNTOUCHED=1; else TASK_UNTOUCHED=0; fi
          fi
        fi
      fi
    fi
  fi
fi

if [[ $IS_WINDOWS -eq 1 ]]; then
  echo
  echo "==> Installed to $TARGET_HOME (platform: Windows, uname: $UNAME_S)"
  # The closing summary repeats the .bat outcome in the same honest terms as the install line: a
  # twin this run did not write is called unchanged, never installed.
  if [[ -n "$BAT_WRITTEN" ]]; then
    echo "Installed Windows shims: $(bat_list "$BAT_WRITTEN") ($BIN_DIR) — run corral from cmd.exe/PowerShell (PATH note at the end)"
  fi
  if [[ -n "$BAT_KEPT" ]]; then
    echo "Unchanged Windows shims: $(bat_list "$BAT_KEPT") ($BIN_DIR) — byte-identical to the bundle, NOT rewritten, so a call already running keeps its file"
  fi
  echo "Installed gradle gate (~/.gradle/init.d/corral-gate.gradle.kts -> $CORRAL_SHIM)"
  echo "Daemon:"
  case "$TASK_STATE" in
    onlogon)
      echo "  Scheduled Task CorralDaemon created; auto-starts at logon and is running now."
      ;;
    onlogon-created)
      echo "  Scheduled Task CorralDaemon created; auto-starts at logon (schtasks /run failed, so it is NOT running now)."
      ;;
    once)
      echo "  Scheduled Task CorralDaemon created and running, but NOT auto-starting (the onlogon trigger needs elevation)."
      echo "  Run this from an elevated prompt to auto-start at logon:"
      echo "  $TASK_ELEV"
      ;;
    once-norun)
      echo "  Scheduled Task CorralDaemon created but NOT running (schtasks /run failed), and NOT auto-starting (the onlogon trigger needs elevation)."
      echo "  Run this from an elevated prompt to auto-start at logon:"
      echo "  $TASK_ELEV"
      ;;
    sandbox)
      echo "  sandbox: no Scheduled Task CorralDaemon was created, replaced or deleted (--home $TARGET_HOME != \$HOME)."
      echo "  This installer started no daemon either."
      ;;
    *)
      if [[ $TASK_DENIED -eq 1 ]]; then
        if [[ $TASK_READ -eq 1 ]]; then
          echo "  A Scheduled Task CorralDaemon EXISTS (confirmed by schtasks /query; Status: ${TASK_STATUS:-unknown})."
          if [[ $TASK_BOTH_DENIED -eq 1 ]]; then
            echo "  This installer could not modify it: both /create attempts were refused (schtasks: Access is denied),"
            echo "  so it created no task and started no daemon."
          else
            echo "  This installer could not modify it: the /sc onlogon create was refused (schtasks: Access is denied) and"
            echo "  the /sc once fallback failed too, without an access-denied refusal. It started no daemon either way."
          fi
          case "$TASK_UNTOUCHED" in
            1) echo "  The task was left untouched: Task To Run, Schedule Type, Status and Last Run Time are the same before and after.";;
            0) echo "  WARNING: its Task To Run / Schedule Type / Status / Last Run Time changed between the two reads, so this run cannot claim it left the task untouched.";;
            *) echo "  The task could not be read before the attempt, so this run does not claim it was left untouched.";;
          esac
          if [[ "$TASK_STATUS" == "Running" ]]; then
            echo "  Status Running means a daemon may already be serving from it; this run did not start it."
          else
            echo "  The task is not Running now, and this run did not start it."
          fi
          echo "  From an elevated prompt, remove or replace it, then re-run install.sh:"
          echo "  schtasks /delete /tn CorralDaemon /f"
          echo "  $TASK_ELEV"
        else
          echo "  This session's schtasks /create was refused (Access is denied) and the task could not be read back"
          echo "  (schtasks /query failed too), so this installer cannot say whether CorralDaemon exists."
          echo "  It created no task and started no daemon."
          echo "  From an elevated prompt, check for it, then create or replace it and re-run install.sh:"
          echo "  schtasks /query /tn CorralDaemon /fo LIST /v"
          echo "  $TASK_ELEV"
        fi
      elif [[ $LAUNCHER_OK -eq 1 ]]; then
        echo "  Scheduled Task CorralDaemon was NOT created (schtasks failed) — the daemon was NOT started."
      elif [[ $LAUNCHER_UNSPELLABLE -eq 1 ]]; then
        echo "  corral-daemon.bat was NOT written (a path contains a \" or a %NAME% pair cmd.exe cannot spell),"
        echo "  so the Scheduled Task was NOT created and the daemon was NOT started."
      else
        echo "  corral-daemon.bat could NOT be written to $DAEMON_BAT (the write failed),"
        echo "  so the Scheduled Task was NOT created and the daemon was NOT started."
      fi
      if [[ $TASK_DENIED -eq 0 && -n "$TASK_ELEV" ]]; then
        echo "  Create it from an elevated prompt, then re-run install.sh:"
        echo "  $TASK_ELEV"
      fi
      ;;
  esac
  echo "NOT done on Windows:"
  echo "  - no POSIX corral/adb shim (an extensionless 'adb' on the Windows PATH would shadow the real adb.exe)"
  echo "  - no systemd unit and no systemctl call (a per-user Scheduled Task runs the daemon instead)"
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
  CMD_NOTE=0
  if cmd_unspellable "$WIN_JAVA" || cmd_unspellable "$WIN_JAR"; then CMD_NOTE=1; fi
  if [[ -n "$ADB" ]]; then
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
echo "Cutover checklist (in the corral source repo, scripts/cutover.md; in the bundle, CUTOVER.md beside this installer): ~/bin/adb --version, corral devices, corral lease -- adb shell echo hi,"
echo "then verify a gradle installDebug allow/deny, retire the adb-lease hook, keep old scripts backed up a week."
fi