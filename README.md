# Corral

Device session manager for coding agents. Herds connected Android devices (USB, WiFi,
emulators) into one pool and leases them exclusively to agents — no two agents driving
the same device.

## What it does

- **Device pool** — live inventory from `adb track-devices` (USB, WiFi, emulators),
  auto-discovering connect/disconnect, enriched with model and OS version (and the AVD
  name for emulators).
- **Selector FIFO queue** — a claim may select by `serial | model | os≥ | type | avd`
  (empty = any usable device). A request with `waitSeconds > 0` long-polls until a matching
  device is offered to it in arrival order; an offer must be accepted (first `check`/`renew`)
  within a 30 s window, or it is reclaimed and re-offered to the next waiter. `corral wait`
  is the long-poll client and `corral queue` lists the waiters. Every freed, expired or
  reclaimed device is handed to the longest-waiting matching waiter, so a direct claim can
  never overtake a queued one.
- **Exclusive leases** — TTL + holder-PID liveness: a crashed holder frees a device
  instantly (when a pid is recorded) or within a bounded TTL; leases survive daemon
  restarts via SQLite (WAL), with an append-only audit log.
- **Per-agent identity** — lease owners are per agent session, not per unix user. One
  declared identifier (`CORRAL_OWNER`) wins; otherwise the owner derives from process
  ancestry (`proc:<comm>:<pid>:<starttime>`, universal, no agent cooperation needed), with
  `user:$USER` as the floor. The pid is only a liveness *hint*, recorded when the process
  actually spans the work — so a harness whose session outlives a single process (per-prompt
  or per-tool-call) sets `CORRAL_OWNER` and gets a durable identity.
- **Exec-time enforcement** — three complementary gates:
  - an `adb` wrapper on PATH checks every PATH-resolved adb invocation;
  - an SDK `platform-tools/adb` shim catches absolute-path calls (IDE, scripts);
  - a Gradle `init.d` gate pre-flights `install*` / `connected*AndroidTest` tasks —
    AGP bypasses the adb binary entirely, so only a build-level gate can cover it; a
    build pinned with `ANDROID_SERIAL` is checked against that device only. The check
    runs at task execution, so gated builds stay configuration-cache compatible.
  Denials are self-teaching: they name the two working forms.
- **Self-healing** — the daemon watches the SDK adb shim and restores it automatically
  after SDK updates (stashing the updated binary); `corral doctor` reports the state of
  every enforcement layer on demand.
- **CLI** — `corral claim [--all|<serial>] [selector]`, `wait [--timeout S] [selector]`,
  `queue`, `free [--all|<serial>]`, `renew <serial>`, `lease [--all] [selector] -- <cmd>`,
  `check`, `owner`, `doctor`, `devices`, `status` — all logic in the jar behind 3-line
  launcher shims, so it works for humans and agents alike.
  Selector: `--serial S --model M --os V(min) --type usb|wifi|emulator --avd A`.

## Shape of it

One headless Kotlin/Ktor daemon (`java -jar`, OS-agnostic), a native adb-wrapper twin
(`.sh` / `.bat`), an SDK adb shim, a Gradle init.d gate, and the `corral` CLI. Install on
a Linux host with `scripts/install.sh` or the self-contained `corral-installer/` bundle
(the installer manages the daemon unit, the shims, the SDK shim, and the gradle gate, and
restores everything on `--uninstall`).

## Status

Phase 1 — daemon core, leases, HTTP API, adb wrapper + SDK shim, CLI, and the gradle gate —
is built and verified on real devices. Phase 2 — the selector FIFO queue (long-poll
`corral wait`, acceptance-window offers) and the generalized agent identity — is built and
verified with fake devices and the real `omp` / `hermes` harnesses. Design and plan docs
live in `docs/plans/` (untracked by convention). Corral replaces the `adb-lease` MVP
(`adb-lease.md`).

## Roadmap (Phase 3+)

- MCP server over streamable HTTP
- Screenshots + Compose Desktop UI, live view via scrcpy
- Emulator lifecycle management
- Verified Windows support: a `ProcessHandle` ancestry fallback in the CLI plus `.bat`
  delegation of owner resolution (`.bat` wrappers ship; ancestry is Linux-only today)
