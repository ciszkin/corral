# Corral

Device session manager for coding agents. Herds connected Android devices (USB, WiFi,
emulators) into one pool and leases them exclusively to agents — no two agents driving
the same device.

## What it does

- **Device pool** — live inventory from `adb track-devices` (USB, WiFi, emulators),
  auto-discovering connect/disconnect, enriched with model and OS version.
- **Exclusive leases** — TTL + holder-PID liveness: a crashed holder frees a device
  instantly or within a bounded TTL; leases survive daemon restarts via SQLite (WAL),
  with an append-only audit log.
- **Per-agent identity** — lease owners are per agent session, not per unix user: the
  session id from the agent (`claude:…`, `opencode:…`), falling back to process ancestry
  (`proc:<comm>:<pid>:<starttime>`), so two sessions on one host never collide.
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
- **CLI** — `corral claim [--all|<serial>]`, `free [--all|serial]`, `lease [--all] -- <cmd>`,
  `check`, `owner`, `doctor`, `devices`, `status` — all logic in the jar behind 3-line
  launcher shims, so it works for humans and agents alike.

## Shape of it

One headless Kotlin/Ktor daemon (`java -jar`, OS-agnostic), a native adb-wrapper twin
(`.sh` / `.bat`), an SDK adb shim, a Gradle init.d gate, and the `corral` CLI. Install on
a Linux host with `scripts/install.sh` or the self-contained `corral-installer/` bundle
(the installer manages the daemon unit, the shims, the SDK shim, and the gradle gate, and
restores everything on `--uninstall`).

## Status

Phase 1 — daemon core, leases, HTTP API, adb wrapper + SDK shim, CLI, and the gradle gate —
is built and verified on real devices. Design and plan docs live in `docs/plans/`
(untracked by convention). Corral replaces the `adb-lease` MVP (`adb-lease.md`).

## Roadmap (Phase 2+)

- Selector FIFO queue with long-poll wait (`waitSeconds` today is a simple poll)
- MCP server over streamable HTTP
- Screenshots + Compose Desktop UI, live view via scrcpy
- Emulator lifecycle management