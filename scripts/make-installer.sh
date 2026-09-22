#!/usr/bin/env bash
# corral make-installer.sh — build the daemon + ui jars and mirror every shipped scripts/ artifact
# into corral-installer/, verifying content, exec bits, and jar freshness.
# Usage: scripts/make-installer.sh [--check]
#   (default)  ./gradlew :corral-daemon:shadowJar :corral-ui:shadowJar, then copy the jars + scripts/ files
#   --check    verify only, copy nothing; exit non-zero listing any drift
#              (compares each bundle jar to the build jar when present)
# Source-tree only. Writes only under corral-installer/, corral-daemon/build/, and corral-ui/build/.
set -u

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
BUNDLE="$REPO_ROOT/corral-installer"
BUILD_JAR="$REPO_ROOT/corral-daemon/build/libs/corral-daemon-all.jar"
BUNDLE_JAR="$BUNDLE/corral-daemon-all.jar"
UI_BUILD_JAR="$REPO_ROOT/corral-ui/build/libs/corral-ui-all.jar"
UI_BUNDLE_JAR="$BUNDLE/corral-ui-all.jar"

# scripts/ files that are NOT shipped in the bundle:
#   make-installer.sh  the mirroring tool itself (source-tree only)
#   cutover.md         docs, mirrored by hand when the docs change
EXCLUDE="make-installer.sh cutover.md"

CHECK=0
case "${1:-}" in
  --check) CHECK=1;;
  "") ;;
  -h|--help) echo "Usage: scripts/make-installer.sh [--check]"; exit 0;;
  *) echo "unknown option: $1" >&2; exit 2;;
esac

if [ "$CHECK" = 1 ]; then
  [ -d "$BUNDLE" ] || { echo "ERROR: bundle not found: $BUNDLE" >&2; exit 1; }
else
  mkdir -p "$BUNDLE" || { echo "ERROR: cannot create bundle: $BUNDLE" >&2; exit 1; }
fi

drift=0

same_mode() { # same_mode <a> <b> — same executability (all three exec bits)
  [ "$([ -x "$1" ] && echo x)" = "$([ -x "$2" ] && echo x)" ]
}

mirror() { # mirror <src> <name> — copy if needed (default) and report identical/UPDATED
  local src=$1 name=$2 dest="$BUNDLE/$2" label
  if [ ! -f "$dest" ]; then
    label="UPDATED (missing)"
  elif cmp -s "$src" "$dest" && same_mode "$src" "$dest"; then
    label="identical"
  else
    label="UPDATED"
  fi
  if [ "$CHECK" = 0 ] && [ "$label" != "identical" ]; then
    cp -p "$src" "$dest" || { echo "ERROR: copy failed: $name" >&2; exit 1; }
    cmp -s "$src" "$dest" && same_mode "$src" "$dest" \
      || { echo "ERROR: post-copy mismatch: $name" >&2; exit 1; }
  fi
  printf '%s -> %s\n' "$name" "$label"
  [ "$label" = "identical" ] || drift=1
}

need_unzip() {
  command -v unzip >/dev/null 2>&1 \
    || { echo "ERROR: unzip not installed (needed to verify corral-rules inside the jar)" >&2; exit 1; }
}

rules_ok() { # rules_ok <jar> — the embedded rules carry all four gate/identity keys
  local jar=$1 k body
  [ -f "$jar" ] || return 1
  body=$(unzip -p "$jar" corral-rules 2>/dev/null) || return 1
  for k in readonly= valueflags= shell_skip= init_floor=; do
    case "$body" in *"$k"*) ;; *) return 1;; esac
  done
}

if [ "$CHECK" = 0 ]; then
  (cd "$REPO_ROOT" && ./gradlew :corral-daemon:shadowJar :corral-ui:shadowJar) || { echo "ERROR: shadowJar failed" >&2; exit 1; }
  [ -f "$BUILD_JAR" ] || { echo "ERROR: jar not built: $BUILD_JAR" >&2; exit 1; }
  [ -f "$UI_BUILD_JAR" ] || { echo "ERROR: jar not built: $UI_BUILD_JAR" >&2; exit 1; }
fi

for src in "$SCRIPT_DIR"/*; do
  [ -f "$src" ] || continue
  name=$(basename "$src")
  case " $EXCLUDE " in *" $name "*) continue;; esac
  mirror "$src" "$name"
done

if [ "$CHECK" = 0 ]; then
  mirror "$BUILD_JAR" "corral-daemon-all.jar"
  mirror "$UI_BUILD_JAR" "corral-ui-all.jar"
  need_unzip
  rules_ok "$BUNDLE_JAR" || { echo "ERROR: $BUNDLE_JAR corral-rules missing gate keys" >&2; exit 1; }
elif [ ! -f "$BUNDLE_JAR" ]; then
  printf 'corral-daemon-all.jar -> UPDATED (missing)\n'
  drift=1
elif [ -f "$BUILD_JAR" ]; then
  if cmp -s "$BUILD_JAR" "$BUNDLE_JAR"; then
    printf 'corral-daemon-all.jar -> identical\n'
  else
    printf 'corral-daemon-all.jar -> UPDATED (stale vs build)\n'
    drift=1
  fi
else
  need_unzip
  if rules_ok "$BUNDLE_JAR"; then
    printf 'corral-daemon-all.jar -> present (rules ok)\n'
  else
    printf 'corral-daemon-all.jar -> UPDATED (rules missing keys)\n'
    drift=1
  fi
fi

# UI jar: no embedded rules to check, so compare against the build jar when one is present.
if [ "$CHECK" = 1 ]; then
  if [ ! -f "$UI_BUNDLE_JAR" ]; then
    printf 'corral-ui-all.jar -> UPDATED (missing)\n'
    drift=1
  elif [ -f "$UI_BUILD_JAR" ]; then
    if cmp -s "$UI_BUILD_JAR" "$UI_BUNDLE_JAR"; then
      printf 'corral-ui-all.jar -> identical\n'
    else
      printf 'corral-ui-all.jar -> UPDATED (stale vs build)\n'
      drift=1
    fi
  else
    printf 'corral-ui-all.jar -> present\n'
  fi
fi

if [ "$CHECK" = 1 ]; then
  [ "$drift" = 0 ] || { echo "bundle drift detected" >&2; exit 1; }
  echo "bundle up to date"
fi
