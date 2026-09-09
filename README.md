# Corral

Device session manager for coding agents. Herds connected Android devices (USB, WiFi)
and emulators into one pool and leases them exclusively to agents — no two agents
driving the same device.

## What it does

- **Device pool** — live inventory and state from `adb track-devices` (USB, WiFi,
  emulators), enriched with model and OS version.
- **Exclusive leases** — TTL + holder-PID liveness: crashes free a device instantly or
  within a bounded TTL; no state file to desynchronize, leases survive daemon restarts
  via SQLite.
- **Fair queue** — selector-based FIFO (by serial, model, OS, transport) so contending
  agents get devices in arrival order instead of a lottery.
- **Enforcement at exec time** — an `adb` wrapper first on PATH checks every adb
  invocation against the lease table, including tools that shell out to adb internally
  (gradle). Denials are self-teaching: they name the two working forms.
- **Agent surface** — HTTP API and MCP server; screenshot snapshots need no lease.
- **UI** — Compose Desktop client with device cards, queue, force-free, and live view
  via scrcpy.

## Shape of it

One headless Kotlin/Ktor daemon (`java -jar`, OS-agnostic), a native adb-wrapper twin
(`.sh` / `.bat`), and a `corral` CLI living in the jar behind 3-line launcher shims.

## Status

Design and Phase 1 plan are in `docs/plans/` (untracked by convention). Corral replaces
the `adb-lease` MVP described in `adb-lease.md` — one bash script and a PreToolUse hook,
deployed 2026-09-08 and validated by a day of real use.
