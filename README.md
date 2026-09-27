# Corral

Device session manager for coding agents. Herds connected Android devices (USB, WiFi,
emulators) into one pool and leases them exclusively to agents — no two agents driving
the same device.

## What it does

- **Device pool** — live inventory from `adb track-devices` (USB, WiFi, emulators),
  auto-discovering connect/disconnect, enriched with model and OS version (and the AVD
  name for emulators).
- **Selector FIFO queue** — a claim may select by `serial | model | os≥ | type | avd`
  (empty = any usable device). A request with `waitSeconds > 0` gets a **ticket** and the
  client polls it; a matching device is offered to the longest-waiting ticket in arrival
  order. `corral wait` is the polling client and `corral queue` lists outstanding tickets.
  An offer that is not polled within a short hold is dropped, and the device moves on. A queued
  ticket must keep being polled — one that goes quiet is dropped after ~30 s — and every freed,
  expired or reclaimed device goes to the longest-waiting ticket that is still polling. A ticket
  that pauses keeps its position but loses offers, including to direct claims; a direct claim
  cannot overtake a ticket that is polling. Nothing is parked server-side, so a crashed client
  simply leaves an expiring ticket.
- **Exclusive leases** — TTL + holder-PID liveness: a crashed holder frees a device
  instantly (when a pid is recorded) or within a bounded TTL; leases survive daemon
  restarts via SQLite (WAL), with an append-only audit log.
- **Per-agent identity** — lease owners are per agent session, not per unix user. One
  declared identifier (`CORRAL_OWNER`) wins; otherwise the owner derives from process
  ancestry (`proc:<comm>:<pid>:<starttime>`, universal, no agent cooperation needed), read
  from `/proc` on Linux and from `ProcessHandle` on Windows/macOS, with `user:$USER` as the
  floor. Where `/proc` is unreadable (macOS/Windows) the PATH wrapper delegates its identity
  to `corral owner` — one JVM spawn per unpinned adb call — so wrapper and CLI still agree;
  `user:$USER` if `corral` is off PATH. The ancestor skip/floor lists (`shell_skip`,
  `init_floor`) live in the same editable
  `~/.config/corral/rules` file as the gate lists; a file from an older install without
  those keys simply keeps the built-in defaults (add the keys to customize). The pid is
  only a liveness *hint*, recorded
  when the process actually spans the work — so a harness whose session outlives a single
  process (per-prompt or per-tool-call) sets `CORRAL_OWNER` and gets a durable identity.
- **Exec-time enforcement** — three complementary gates:
  - an `adb` wrapper on PATH checks every PATH-resolved adb invocation;
  - an SDK `platform-tools/adb` shim catches absolute-path calls (IDE, scripts);
  - a Gradle `init.d` gate pre-flights `install*` / `connected*AndroidTest` tasks —
    AGP bypasses the adb binary entirely, so only a build-level gate can cover it; a
    build pinned with `ANDROID_SERIAL` is checked against that device only. The check
    runs at task execution, so gated builds stay configuration-cache compatible.
  The wrapper's read-only-command and value-flag lists live in one editable file,
  `~/.config/corral/rules` (override `CORRAL_RULES`), read by both the bash wrapper and the
  CLI; the Windows `.bat` delegates to `corral gate`, so the three cannot drift. A missing
  rules file fails closed and the daemon restores the default from the jar.
  Denials are self-teaching: they name the two working forms.
- **Self-healing** — the daemon watches the SDK adb shim and restores it automatically
  after SDK updates (stashing the updated binary), and restores a missing or blank
  `~/.config/corral/rules` from the jar default; `corral doctor` reports the state of
  every enforcement layer on demand.
- **CLI** — `corral claim [--all|<serial>] [selector]`, `wait [--timeout S] [selector]`,
  `queue`, `free [--all|<serial>]`, `renew <serial>`, `lease [--all] [selector] -- <cmd>`,
  `screenshot <serial> [--out F]`, `events`, `check`, `owner`, `doctor`, `devices`,
  `status` — all logic in the jar behind 3-line
  launcher shims, so it works for humans and agents alike.
  Selector: `--serial S --model M --os V(min) --type usb|wifi|emulator --avd A`.
- **MCP server** — the same lease API is exposed over streamable HTTP at `/mcp`
  (`list_devices`, `queue`, `claim`, `free`, `renew`, `screenshot`), with one identity per MCP session:
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
  value, built-in default and `restartRequired` flag, plus the gate rules and the `rules` /
  `daemon.env` paths; an unusable file value falls through to inherited env then the default.
  `PUT /api/config` validates the whole payload before writing anything (unknown keys are ignored,
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
  unit's `EnvironmentFile=-…` is optional, so a fresh host still starts; a `PUT` whose `Origin` is
  not loopback or whose port differs from the request `Host` is `403`, and startup logs and falls
  back (rather than exiting) on an unusable `daemon.env` value. `GET /api/doctor` mirrors
  `corral doctor`'s three layers (`daemon` / `sdk-shim` / `rules`) and is read-only; the `rules`
  layer reports `warn` when a hand-edited `readonly` holds a non-host-only command or the identity
  lists differ from the defaults.
- **Desktop UI** — `corral-ui [--url http://127.0.0.1:port]` (else `CORRAL_URL`, then the stored
  pref, default `http://127.0.0.1:8798`; the daemon is loopback-only, so `--url` picks a
  non-default local port unless the operator deliberately rebinds it) opens a Compose operator
  console: one five-section screen with a left rail and scrollspy — **Devices** (card grid with
  live thumbnails, holder and TTL countdown), **Queue** (the wait list), **Doctor** (per-layer
  health with a rail badge on FAIL), **Rules** and **Settings** — plus a connection bar with the
  last event `seq`. Rules and Settings are editors: the gate rules and daemon knobs go over the
  daemon's config API (the `readonly`/`valueflags` lists, the knobs with the restart-required hint
  and per-field validation), while the client's own `CORRAL_URL`/`CORRAL_OWNER`/`CORRAL_SCRCPY`
  are local prefs applied live on save; `shell_skip`/`init_floor` are file-only, so edit
  `~/.config/corral/rules` directly. The
  theme (System/Light/Dark) and language (English/Беларуская) apply immediately and persist, with
  the client's window geometry, to `~/.config/corral/ui.conf`. Force-free sits behind a confirm
  dialog naming the holder, and a scrcpy live view is strictly read-only (`--no-control`,
  `--no-clipboard-autosync`, `--no-audio`), so an observer can watch a leased device but never
  touch it. The window and tray use the Corral mark. The window's close button parks it in the
  system tray where the platform has one (Windows, macOS, and Linux desktops that host an X
  tray — GNOME does via its appindicator support), and clicking the tray icon (either button)
  brings the window back; since close only parks it, the window's **Corral** menu holds **Quit**.
  Where `isTraySupported` is false, close quits, and the **Corral** launcher `install.sh --ui`
  drops into `~/.local/share/applications` (app grid, pinnable to the dock, `Icon=` the Corral
  mark) starts it again. Its own traffic is HTTP/WS — the UI never talks adb itself — and it is an
  observer, not a leaser: its identity is `CORRAL_OWNER` (default `corral-ui`). The one adb it can
  spawn is the read-only scrcpy live view above: scrcpy resolves adb itself (a static build ships
  its own), so that one path generally sits outside corral's lease gate — an acknowledged
  exception, not an oversight (see `cutover.md` §4). Install it with `install.sh --ui` (or
  `--no-ui` to skip; the default follows whether a `corral-ui-all.jar` is found — beside the
  installer in the bundle, or in `corral-ui/build/libs/` in a source checkout). Because
  screenshots are strict by default, the installer seeds `CORRAL_SCREENSHOT_OBSERVERS=corral-ui`
  into the daemon unit env **only when that line is absent** — visible and editable forever after
  (emptying it disables observers and survives reinstalls); a remote UI denied with `403` raises a
  banner naming the variable.
- **Loopback only** — the daemon binds `127.0.0.1` and refuses any non-local `Host`
  header with `403 corral: local only`, which also covers `/mcp`. A browser on the host
  cannot be tricked by DNS rebinding into driving the daemon.

## Shape of it

One headless Kotlin/Ktor daemon (`java -jar`, OS-agnostic), a Compose Desktop operator
console, a native adb-wrapper twin (`.sh` / `.bat`), an SDK adb shim, a Gradle init.d gate,
and the `corral` CLI. Install on
a Linux host with `scripts/install.sh` or the self-contained `corral-installer/` bundle
(the installer manages the daemon unit, the shims, the SDK shim, and the gradle gate, and
restores everything on `--uninstall`).

## Status

Phase 1 — daemon core, leases, HTTP API, adb wrapper + SDK shim, CLI, and the gradle gate —
is built and verified on real devices. Phase 2 — the selector FIFO queue and the generalized
agent identity — is built and verified with fake devices and the real `omp` / `hermes`
harnesses; the queue was later reworked to client-polled tickets, so no request is parked
server-side and a crashed client cannot stall it. Phase 3 — the MCP server
over streamable HTTP at `/mcp` — is built and verified with fake devices via a scripted MCP
handshake. Later phases are built too: M4 observability (screenshots + `/ws/events`), M5 the
Compose Desktop UI — rebuilt into the five-section operator console with rules/settings editors,
theme modes and English/Belarusian resources — and M7 the config/doctor API. Corral replaces the
`adb-lease` MVP (`adb-lease.md`).

## Roadmap (Phase 3+)

- ~~Compose Desktop UI, live view via scrcpy~~ — done (M5): a third `corral-ui` module rebuilt
  into the operator console (five sections, rules/settings editors, theme modes, en/be resources,
  the Corral mark), component install, observer seeding
- Emulator lifecycle management
- Verified Windows/macOS support: the `.bat` delegates the gate to `corral gate`, the CLI
  walks ancestry via `ProcessHandle` where `/proc` is absent, and the bash wrapper delegates
  identity to `corral owner` there — real non-Linux-host verification is still outstanding
