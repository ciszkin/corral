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
- **Loopback only** — the daemon binds `127.0.0.1` and refuses any non-local `Host`
  header with `403 corral: local only`, which also covers `/mcp`. A browser on the host
  cannot be tricked by DNS rebinding into driving the daemon.

## Shape of it

One headless Kotlin/Ktor daemon (`java -jar`, OS-agnostic), a native adb-wrapper twin
(`.sh` / `.bat`), an SDK adb shim, a Gradle init.d gate, and the `corral` CLI. Install on
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
handshake. Corral replaces the `adb-lease` MVP (`adb-lease.md`).

## Roadmap (Phase 3+)

- Compose Desktop UI, live view via scrcpy (screenshots + event stream are done)
- Emulator lifecycle management
- Verified Windows/macOS support: the `.bat` delegates the gate to `corral gate`, the CLI
  walks ancestry via `ProcessHandle` where `/proc` is absent, and the bash wrapper delegates
  identity to `corral owner` there — real non-Linux-host verification is still outstanding
