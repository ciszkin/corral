<p align="center">
  <img src="corral.png" width="112" alt="Corral">
</p>

# Corral

Device session manager for coding agents. Herds connected Android devices (USB, WiFi,
emulators) into one pool and leases them exclusively to agents — no two agents driving
the same device.

[Adopting Corral](CUTOVER.md) · [Security policy and threat model](SECURITY.md) ·
[Agent skill](skills/corral/SKILL.md) · Apache-2.0 ([LICENSE](LICENSE), [NOTICE](NOTICE))

## What it does

- **Device pool** — live inventory from `adb track-devices` (USB, WiFi, emulators),
  auto-discovering connect/disconnect, enriched with model and OS version (and the AVD
  name for emulators).
- **Selector FIFO queue** — a claim may select by `serial | model | os≥ | type | avd`
  (empty = any usable device). A request with `waitSeconds > 0` gets a **ticket** and the
  client polls it; a matching device is offered to the longest-waiting ticket in arrival
  order. `corral lease --timeout S -- <cmd>` is the queue-and-hold form: it waits in that
  queue, then runs the command with the served device leased until the command exits, so
  the first waiter keeps the device and the next ticket gets the next one. `SIGTERM`,
  `SIGINT` and `SIGHUP` stop the command's tree before the lease goes back; a `SIGKILL`ed
  CLI cannot, so the lease also carries the command's pid and holds the device while that
  command lives (bounded by the TTL — this is not SIGKILL safety). `corral wait`
  is the polling client that returns its lease when the wait process exits (poll, then
  decide), and `corral queue` lists outstanding tickets. A `corral claim`'s TTL is the
  hold and its owner string is the identity, so a claim outlives the process that made it.
  `corral free` drops an owned lease and `corral status` names the current holders.
  An offer that is not polled within a short hold is dropped, and the device moves on. A queued
  ticket must keep being polled — one that goes quiet is dropped after ~30 s — and every freed,
  expired or reclaimed device goes to the longest-waiting ticket that is still polling. A ticket
  that pauses keeps its position but loses offers, including to direct claims; a direct claim
  cannot overtake a ticket that is polling. Nothing is parked server-side, so a crashed client
  simply leaves an expiring ticket.
- **Emulator lifecycle** — list, boot and stop existing AVDs (`emulator -list-avds` / `-avd`,
  `adb emu kill`) through `corral avd list|start A|stop A [--force]`, `/api/emulators`, and the
  MCP `start_emulator`/`stop_emulator` tools. The binary resolves from `CORRAL_EMULATOR`, else the
  SDK `emulator/emulator` beside adb, else an `emulator` on PATH. A `start` dedupes on a live
  spawn under a per-AVD lock, not just the pool, so concurrent or repeat starts of an
  already-spawned or booting AVD answer `alreadyRunning` and exactly one process runs. By default
  the emulator is a child process of the daemon, destroyed on a graceful daemon stop (a `SIGKILL`ed
  daemon can orphan it untracked); when the daemon runs as the `corral.service` unit
  (or `CORRAL_EMULATOR_LAUNCHER=systemd`), each AVD instead runs in its own transient
  `systemd-run --user` unit that survives a daemon restart, with its output written to
  `~/.corral/emulator-<avd>.log` (truncated per start) and the daemon's `ANDROID_*` environment
  forwarded (`CORRAL_EMULATOR_LAUNCHER=direct` forces the child-process path; unset auto-detects).
- **Emulator boot gate & stop** — a freshly started AVD appears in the pool **unbooted** — not
  claimable until Android reports `sys.boot_completed=1`; a plain `corral claim --type emulator`
  teaches that `corral lease --timeout S --type emulator -- <cmd>` queues until boot and then holds
  the AVD for the command, and the queued ticket is served the moment the flip lands. Stop is
  refused while the AVD is leased unless `--force` / `{"force":true}` is given — the CLI and REST
  take a force; the MCP `stop_emulator` (and `free`) do not. Stopping
  an emulator releases its lease, and an emulator lease is dropped when its serial leaves the pool,
  while physical/WiFi leases survive a replug. `GET /api/emulators` carries an AVD's last log line
  as `error` only when its last run exited non-zero — a late boot failure or crash. A run that ends
  cleanly on its own (exit 0, e.g. the emulator window closed) and one Corral stopped both report
  no `error`: `stop` rotates the log to `~/.corral/emulator-<avd>.log.1` (keeping the evidence) and
  clears the exit status, and every start writes a fresh log.
- **Exclusive leases** — TTL + holder-PID liveness: a crashed holder frees a device
  instantly (when a pid is recorded) or within a bounded TTL; a lease also carries the
  pids of the command its CLI started, so a killed CLI does not free a device its command
  is still driving. Leases survive daemon
  restarts via SQLite (WAL), with an append-only audit log.
- **Per-agent identity** — lease owners are per agent session, not per unix user. One
  declared identifier (`CORRAL_OWNER`) wins; otherwise the owner derives from process
  ancestry (`proc:<comm>:<pid>:<starttime>`, universal, no agent cooperation needed), read
  from `/proc` on Linux and from `ProcessHandle` on Windows/macOS, with `user:$USER` as the
  floor. Where `/proc` is unreadable (macOS; Windows never installs this bash wrapper) the
  PATH wrapper delegates its identity to `corral owner` — one JVM spawn per adb call, because
  the owner is computed before the pin — so wrapper and CLI still agree; `user:$USER` if
  `corral` is off PATH. The wrapper needs bash 4+ (stock macOS `/bin/bash` is 3.2), and the
  Windows `.bat` twin instead delegates to `corral gate` and fails closed when `corral` is not
  on PATH. The ancestor skip/floor lists (`shell_skip`,
  `init_floor`) live in the same editable
  `~/.config/corral/rules` file as the gate lists; a file from an older install without
  those keys simply keeps the built-in defaults (add the keys to customize). Membership
  ignores case and one trailing `.exe` on **both** sides, so a list written once per
  program (`bash`) matches `bash.exe` too and either spelling is accepted. The trade: on
  Linux a file literally named `bash.exe` is skipped too — accepted. The pid is
  only a liveness *hint*, recorded
  when the process actually spans the work — so a harness whose session outlives a single
  process (per-prompt or per-tool-call) sets `CORRAL_OWNER` and gets a durable identity.
- **Exec-time enforcement** — three complementary gates on a POSIX install:
  - an `adb` wrapper on PATH checks every PATH-resolved adb invocation;
  - an SDK `platform-tools/adb` shim catches absolute-path calls (IDE, scripts);
  - a Gradle `init.d` gate pre-flights `install*` / `connected*AndroidTest` tasks —
    AGP bypasses the adb binary entirely, so only a build-level gate can cover it; a
    build pinned with `ANDROID_SERIAL` is checked against that device only. The check
    runs at task execution, so gated builds stay configuration-cache compatible.

  A Windows install has two layers — the `.bat` wrapper and the Gradle gate — because the
  SDK's `adb.exe` is not shimmed.
  The wrapper's read-only-command and value-flag lists live in one editable file,
  `~/.config/corral/rules` (override `CORRAL_RULES`), read by both the bash wrapper and the
  CLI; the Windows `.bat` delegates to `corral gate`, so the three cannot drift. A missing
  rules file fails closed and the daemon restores the default from the jar.
  Denials are self-teaching: they name the two working forms.
  On Windows `corral` is `corral.bat`, so a **batch caller must write `call corral …`**:
  without `call`, cmd.exe transfers control to the shim and the caller's remaining lines
  never run (its `exit /b` ends the caller). Interactive cmd.exe/PowerShell are unaffected.
- **Self-healing** — while the daemon runs it checks the SDK adb shim every 10 seconds
  and restores it after an SDK update, keeping the updated binary as `adb.real`; if the
  daemon was stopped when the SDK updated, it heals at the next start. It also restores a
  missing or blank `~/.config/corral/rules` from the jar default, and `corral doctor`
  reports each layer it can inspect. Re-running the installer (or `corral update --apply`)
  is the manual fallback, not a routine step after an SDK update.
- **CLI** — `corral claim [--all|<serial>] [selector]`, `wait [--timeout S] [selector]`,
  `queue`, `free [--all|<serial>]`, `renew <serial>`,
  `lease [--all | --timeout S] [selector] -- <cmd>`,
  `screenshot <serial> [--out F]`, `events`, `check`, `owner`, `doctor`, `devices`, `status`,
  `gate -- <adb args>`, `update [--apply]`, `avd [list|start A|stop A [--force]]` — all logic
  in the jar behind two-line launcher shims, so it works for humans and agents alike.
  `corral --version` prints the CLI jar's version with no daemon; the queue wait for `wait`/`lease`
  is capped at 600 s (a larger `--timeout` is clamped with a warning, a negative one is a usage
  error). Selector: `--serial S --model M --os V(min) --type usb|wifi|emulator --avd A`.
- **Updates** — the daemon checks the release feed once a day, **on by default**
  (every daemon calls `api.github.com`; `CORRAL_UPDATE_CHECK=off` stops it, and
  `CORRAL_REPO` points both the check and the apply at another repository —
  env only). `corral update` reports what the check found, `corral update
  --apply` downloads the release zip and its `.sha256`, verifies the digest and
  only then runs the bundle's `install.sh`, and `GET /api/update` is the same
  state as a read (`?force` runs a fresh check at once — the console's check
  button uses it, and its install button confirms first and then applies the
  same gated path). The digest comes from the same release as the zip, so it
  proves the download was intact — **not** that the publisher is genuine:
  anyone who can publish a release there runs `install.sh`, i.e. arbitrary code
  as you, on every host that applies it. Jars are replaced by rename, so a
  running daemon or console is not corrupted. See
  [CUTOVER.md](CUTOVER.md#updates).
- **MCP server** — the same lease API is exposed over streamable HTTP at `/mcp` as eight tools
  (`list_devices`, `queue`, `claim`, `free`, `renew`, `screenshot`, `start_emulator`,
  `stop_emulator`), with one identity per MCP session:
  `claude mcp add --transport http corral http://127.0.0.1:8798/mcp`. `claim` with
  `waitSeconds > 0` returns a ticket; re-call `claim` with that ticket to pick up the device.
  Each ticket carries a poll cadence (MCP advertises 5 s; a client may propose its own,
  clamped to 1–10 s) and the accepted value comes back in `retryAfterMs`.
- **Observability** — `GET /api/devices/{serial}/screenshot?owner=O` returns a PNG
  (`adb -s <serial> exec-out screencap -p`, single-flight per serial with a 1 s coalescing
  window). Strict by default: only the lease holder may read; an owner listed in
  `CORRAL_SCREENSHOT_OBSERVERS` (empty unless configured — the UI opts in), or anyone when
  `CORRAL_SCREENSHOTS=open`, is also allowed. Observer names are case-sensitive. Strict mode
  answers `403` for any serial the caller may not read, including unknown or unusable ones (no
  existence leak); `404`/`502` apply once access is allowed. `GET /ws/events` streams a
  `hello` frame then additive `state.changed` nudges — `{"type","resources","seq"}` with
  resources `devices`/`queue`; a client ignores unknown types, refetches on an unknown
  resource, and treats a seq gap after reconnect as refetch-all. The MCP `screenshot` tool
  returns an image content block, and `corral screenshot <serial> [--out F]` / `corral events`
  are the CLI verbs.
- **Config & doctor API** — `GET /api/config` reports every daemon knob with its **effective**
  value, built-in default and `restartRequired` flag, plus the gate rules, the `rules` /
  `daemon.env` paths and the update source (`CORRAL_REPO`, environment-only and read-only);
  an unusable file value falls through to inherited env then the default.
  `PUT /api/config` validates the whole payload before writing anything (an unknown setting key is
  refused with a `400` that names the accepted ones, and `CORRAL_REPO` with a read-only message;
  control characters rejected, settings paths checked, the port must be 1024–65535), so a bad
  request is `400` and writes nothing. A rules entry that cannot round-trip through the rules file
  (whitespace, `,`, `#`, `=`) is refused, as is a `valueflags` entry without a leading dash. Over
  the API `readonly` is an **allowlist** of host-only commands (`devices`, `start-server`,
  `kill-server`, `track-devices`, `version`, `help`) and `shell_skip` / `init_floor` are file-only:
  edit `~/.config/corral/rules` directly for a device-touching exemption or an identity change.
  A rules `PUT` is idempotent for `GET`→`PUT` — a list equal to the file's current list, or an empty
  list, means "no change", so a save on a hand-edited host works; otherwise `readonly` /
  `valueflags` replace the whole list. `shell_skip` / `init_floor` are file-only: any submitted
  change is refused, including a revert to the defaults.
  The response is honest about scope: `restartRequired` covers settings, while `rulesLive` marks
  rules the wrapper reads live. Settings persist to `~/.config/corral/daemon.env` (override
  `CORRAL_ENV_FILE`) with **file > inherited env > default** precedence — a deliberate edit beats
  the unit's `Environment` and survives restarts. The installer seeds the file absent-only and the
  unit's `EnvironmentFile=-…` is optional, so a fresh host still starts; every mutating route
  (claim, ticket, free, renew, emulator start/stop, config) rejects an `Origin` that is not
  loopback or whose port differs from the request `Host` with `403`, and startup logs and falls
  back (rather than exiting) on an unusable `daemon.env` value. `GET /api/doctor` mirrors
  `corral doctor`'s layers (`daemon` / `sdk-shim` / `rules`, plus `emulators` while an AVD is
  wedged) and is read-only; the `rules` layer reports `warn` when a hand-edited `readonly` holds a
  non-host-only command or the identity lists differ from the defaults. An empty pool stays `ok`,
  but the `daemon` layer (and `corral doctor`) then adds a hint that an `adb reconnect` can leave
  the adb server's device list empty until the server is restarted, with the `adb kill-server &&
  adb start-server` recovery — so zero devices no longer reads as only "nothing is plugged in".
- **Desktop UI** — `corral-ui [--url http://127.0.0.1:port]` (else `CORRAL_URL`, then the stored
  pref, default `http://127.0.0.1:8798`; the daemon is loopback-only, so `--url` picks a
  non-default local port unless the operator deliberately rebinds it) opens a Compose operator
  console — **verified on JDK 17 (Linux)**, with the Windows coverage stated once in
  [CUTOVER.md § Windows](CUTOVER.md#windows); very new JDKs beyond those are untested. The daemon
  and CLI are unaffected either way. One six-section screen with a left rail and scrollspy —
  **Devices** (horizontal card carousel with paging chevrons, live thumbnails, holder and TTL
  countdown), **Emulators** (AVDs with running serials, a boot chip while booting, and start/stop),
  **Queue** (the wait list), **Doctor** (per-layer health with a rail badge on FAIL), **Rules** and
  **Settings** — plus a status bar with the live event `seq`, the daemon's version (`0.0.0-dev`
  for an untagged build, shown as-is) and an update pill when a newer release is known; Settings
  shows the console's own version beside the daemon's. Rules and Settings are editors: the gate
  rules and daemon knobs go over the daemon's config API (the `readonly`/`valueflags` lists, the
  knobs with the restart-required hint and per-field validation), while the client's own
  `CORRAL_URL`/`CORRAL_OWNER` apply on save, and a saved `CORRAL_SCRCPY` path is used by the next
  live view — it wins over `CORRAL_SCRCPY` in the console's environment, which remains the
  fallback; `shell_skip`/`init_floor` are file-only, so edit `~/.config/corral/rules` directly.
  Settings also
  holds the update row: the last check's honest state, a "check now" that asks for a fresh check
  (`?force`), and an install that confirms first and then runs the same checksum-gated applier as
  `corral update --apply`. The interface is English, and the theme (System/Light/Dark) applies
  immediately and persists, with the client's window geometry, to `~/.config/corral/ui.conf`.
  Force-free sits behind a confirm dialog naming the holder, and a scrcpy live view is strictly
  read-only (`--no-control`, `--no-clipboard-autosync`, `--no-audio`), so an observer can watch a
  leased device but never touch it. The window and tray use the Corral mark. The window's close
  button parks it in the system tray where the platform has one — Windows, macOS and X11 desktops
  other than GNOME (a Wayland session or GNOME quits instead) — and clicking the tray icon
  (either button) brings the window back; since close only parks it, **Quit** sits at the foot of
  the left rail and asks for confirmation.
  Where `isTraySupported` is false, close quits, and the **Corral** launcher `install.sh --ui`
  drops into `~/.local/share/applications` (app grid, pinnable to the dock, `Icon=` the Corral
  mark) starts it again. Its own traffic is HTTP/WS — the UI never talks adb itself — and it is an
  observer, not a leaser: its identity is `CORRAL_OWNER` (default `corral-ui`). The one adb it can
  spawn is the read-only scrcpy live view above: scrcpy resolves adb itself (a static build ships
  its own), so that one path generally sits outside corral's lease gate — an acknowledged
  exception, not an oversight (see the "Known limits" section of
  [CUTOVER.md](CUTOVER.md#known-limits)). Install it with `install.sh --ui` (or
  `--no-ui` to skip; the default follows whether a `corral-ui-all.jar` is found — beside the
  installer in the bundle, or in `corral-ui/build/libs/` in a source checkout). Because
  screenshots are strict by default, the installer seeds `CORRAL_SCREENSHOT_OBSERVERS=corral-ui`
  into the daemon unit env when it installs the console and the line is absent (POSIX unit only) —
  visible and editable forever after
  (emptying it disables observers and survives reinstalls); a remote UI denied with `403` raises a
  banner naming the variable.
- **Loopback only** — the daemon binds `127.0.0.1` and refuses any non-local `Host`
  header with `403 corral: local only`, which also covers `/mcp`. A browser on the host
  cannot be tricked by DNS rebinding into driving the daemon.

## Shape of it

Four Gradle modules, one repository:

- `corral-common` — the DTOs the daemon, CLI and console share (pure Kotlin +
  kotlinx-serialization, KMP-ready, no `java.*`);
- `corral-update` — the release-apply gate: download, verify the published `.sha256`, then run the
  bundle's `install.sh`. Zero dependencies, shared by the daemon's CLI and the console, so one
  module owns the security-critical check;
- `corral-daemon` — the headless Kotlin/Ktor daemon (`java -jar`, OS-agnostic: leases, HTTP/WS/MCP
  API, SQLite, emulator control) and the `corral` CLI;
- `corral-ui` — the Compose Desktop operator console.

Install with `scripts/install.sh` or the self-contained `corral-installer/` bundle on a Linux host,
or on Windows through the installer's Windows branch ([CUTOVER.md § Windows](CUTOVER.md#windows));
the installer manages the installed components — the daemon unit or Scheduled Task, the shims, the
SDK adb shim on POSIX, the Gradle gate, the agent skill and the optional console; `--uninstall`
removes what is still its own and names edited files under "Left alone".

## Build and test

```sh
./gradlew test                        # all four modules; offline, no adb, no device
./gradlew :corral-daemon:shadowJar    # -> corral-daemon/build/libs/corral-daemon-all.jar
./gradlew :corral-ui:shadowJar        # -> corral-ui/build/libs/corral-ui-all.jar
scripts/make-installer.sh             # build both jars and mirror scripts/ + docs into the bundle
scripts/make-installer.sh --check     # report bundle drift, write nothing
```

`scripts/test_wrapper.sh` drives the gate through the bundled wrapper, `SLOW=1 scripts/test_e2e.sh`
runs the daemon end to end against fake devices, and `scripts/test_install.sh` installs into a
sandbox home; CI runs all three plus both shadow jars and the bundle drift check.

## Status

Corral is feature-complete for a first release, and every finding from 52 independent review passes
is closed.

- **Linux and Windows.** The daemon, CLI, console and installer are built and tested offline — four
  Gradle modules, four suites (`./gradlew test`, `test_wrapper.sh`, `SLOW=1 test_e2e.sh`,
  `test_install.sh`) — on Linux CI. The Windows install path is host-verified on one box: the `.bat`
  shims, the per-user `CorralDaemon` Scheduled Task and its ownership rules, the jar-lock and
  `.bat` in-flight refusals, the Gradle gate, and the console.
- **What it is not.** macOS has no installer (the daemon and CLI are portable, nothing is packaged
  or verified there), and the gate is evaluated at exec time — a client that bypasses the wrapper,
  the SDK shim and Gradle entirely is outside it. Both are stated in
  [Known limits](CUTOVER.md#known-limits) rather than implied away.
- **Versions.** Both jars report `0.0.0-dev` until the first `v*` tag; the version is stamped from
  the tag at build time.
