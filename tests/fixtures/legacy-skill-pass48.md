---
name: corral
description: >-
  Lease an Android device (USB, WiFi or emulator) exclusively through the `corral` CLI, and read
  the gate that refuses unpinned adb calls. Use when the user says "claim a device", "lease an
  Android device", "corral wait", "corral lease", "device is busy", "corral doctor", or "the gate
  refused my adb call" — and before any adb, `install*` or `connected*AndroidTest` run on a host
  where Corral is installed. Prefer Corral over raw adb there: it gives one agent session sole use
  of a device, and the three enforcement layers turn away the calls that skip it.
---

# Corral

One device, one agent, enforced. Corral pools the Android devices attached to the host and
leases each to exactly one agent session at a time. On a Corral host you are not alone: another
agent, a background build, or a human may be driving the other devices.

## When to use it

Use Corral whenever you are about to touch a device — `adb shell`, `adb install`, a screenshot,
`am instrument`, `./gradlew connectedAndroidTest` or `install*`. Do not start with
`adb devices` and grab a serial: claim or lease first.

What that buys you:

- the device is yours alone, so a failure is your failure and not someone else's scrolling;
- the lease keeps everyone else out while your command runs — the gate refuses their calls;
- a released, crashed or expired lease goes to the longest-waiting agent in arrival order, so
  waiting is fair rather than a race.

## The core loop

**Prefer the form that queues *and* holds** — it waits in the FIFO queue, then runs your command
with the served device leased until the command exits:

```sh
corral lease --timeout 120 -- ./gradlew connectedAndroidTest
corral lease --timeout 180 --type emulator -- adb install app.apk
corral lease --timeout 60 --model Pixel_7 -- adb shell input keyevent 3
```

- Everything after `--` is your command; nothing before it is ever run as a program.
- The command's own output is the only output on the success path.
- The device is held from before the command starts until after it exits, then released. A
  command that crashes or is signalled still releases (see *Signals*).
- `--timeout S` caps the queue wait (max 600, clamped with a warning; a negative value is a usage
  error). Omit it to fail at once when the pool is busy. A timed-out ticket runs nothing.
- With no `--timeout`, a busy pool prints a self-teaching line naming the holders and this exact
  `--timeout` form on stderr and exits 1.

The command sees: `ANDROID_SERIAL=<serial>`, `CORRAL_OWNER=<your identity>`, and — when the CLI
can find an adb itself — that adb's directory prepended to `PATH`. The lookup is the CLI's own
environment, never the daemon's config: `CORRAL_REAL_ADB` or `CORRAL_ADB`, then `ANDROID_HOME` /
`ANDROID_SDK_ROOT` (and `%LOCALAPPDATA%\Android\Sdk` on Windows), then `~/Android/Sdk`. With none
of those, nothing is prepended and a bare `adb` inside the command does not resolve at all, so set
`CORRAL_ADB` on the CLI's environment if the daemon's adb lives elsewhere.

**Or claim, use, free** when the work spans several commands or processes:

```sh
serial=$(corral claim)              # the serial alone on stdout; diagnostics go to stderr
ANDROID_SERIAL=$serial adb shell ...
corral free "$serial"
```

A claim's TTL is the hold (30 min from the CLI) and its owner string is the identity, so it
outlives the process that made it. A long session renews with `corral renew <serial>`. `corral
free --all` drops every lease you own — run it in a trap or at the end of a long session: an
abandoned claim keeps the device from everyone until its TTL expires.

**`corral wait` is the poll-then-decide form, not the one to prefer:**

```sh
serial=$(corral wait --timeout 60)   # blocks until a matching device is offered
```

It prints the serial and hands the lease back when the wait process exits, so the next ticket can
take the device while you are still working on it. Use it only when you need to know which device
you would get before deciding what to do. `--timeout` takes the same 600 s cap (clamped with a
warning) and the same rejection of a negative value as `lease`.

**Selectors** for `claim`, `wait` and `lease` (empty = any usable device; they compose):

```
--serial S   --model M   --os V (minimum)   --type usb|wifi|emulator   --avd A
```

**Read commands, no lease needed** — `corral devices` (the inventory as JSON), `corral status`
(devices and their holders), `corral queue` (outstanding tickets with position and remaining wait),
`corral owner` (your identity), `corral check` (whether you may use each device), `corral doctor`
(host health).

**Identity.** `corral owner` prints `CORRAL_OWNER` if set, else a process-ancestry string
(`proc:<comm>:<pid>:<starttime>`), else `user:$USER`. If your harness starts a new process per
prompt (so the ancestry changes between calls), set `CORRAL_OWNER` to a stable name and keep it
across commands — the gate only honours the lease of the owner that made it, so a drifting identity
makes your own device read as someone else's.

## The gate — why an adb call can be refused

Three independent layers enforce the lease:

1. an `adb` wrapper earlier on `PATH` — every PATH-resolved adb call is classified;
2. the SDK `platform-tools/adb` shim — the same check for absolute-path calls into the SDK;
3. a Gradle `init.d` gate — AGP bypasses the adb binary entirely, so `install*` and
   `connected*AndroidTest` are pre-flighted at task execution (configuration-cache compatible);
   a build pinned with `ANDROID_SERIAL` is checked against that device only.

A refusal is the gate working, and it teaches the way through:

```
corral: no device pinned (no -s, no ANDROID_SERIAL)
Lease it first:
  corral lease -- <cmd>
  ser=$(corral claim) && ANDROID_SERIAL=$ser <cmd>
status: corral status
```

or `corral: lease check failed for devA: {"allowed":false,"reason":"no live lease on devA"}` / a
`reason` naming the holder (`"devA is leased by <owner>, <n>s left"`). The reason is the daemon's
raw JSON, so match on the `reason` substring; an exact match on the printed line does not work. The
answer is to wrap the work in a lease, or to work on the device you do hold — not to retry the
call, and not to find another adb.

The read-only allowlist is the `readonly=` line of `~/.config/corral/rules` (override
`CORRAL_RULES`); by default it is `devices,start-server,kill-server,track-devices,version,help` —
the commands the gate lets through without a lease. That is **not** the same as harmless:
`kill-server` and `start-server` act on the adb **server**, not on one device — `kill-server` drops
every session's connection to every device at once, including another agent's `am instrument`,
`logcat` or Gradle connected test; the gate allows an unleased `kill-server` even while someone
else holds a device. Run either only when `corral status` shows no live leases, and otherwise ask
the human. `-s <serial>` and `ANDROID_SERIAL` are always lease-checked whatever the file says, and
a missing rules file fails closed: **every** call is refused — pinned, unpinned and read-only
alike. `corral gate -- <adb args>` runs the same classification the wrapper does — that is how the
Windows `.bat` twin reaches it — if you need to test one call. Do not edit that file to make your
command pass: adding a device-touching name (`shell`, `install`, `push`, `pull`, `logcat`, …)
disables the unpinned guard for it, for everyone on the host.

**Documented bypasses — know them, don't use them.** The layers have known holes, and the threat
model is cooperating agents, not a hostile local process:

- ddmlib clients that talk to the adb server on `:5037` directly — Android Studio's Run/deploy,
  Logcat, Device Explorer: no adb binary, no Gradle task, so all three layers miss them;
- `platform-tools/adb.real`, or any adb outside the SDK directory by absolute path: outside the
  shim, and outside the wrapper when it does not go through `PATH`.

Identity is self-declared: a client states its owner and the daemon believes it. Corral is a rail
against accidental collision, not an authentication boundary. Reaching for `adb.real` or `:5037` to
get around a refusal breaks the one guarantee you are relying on — you may drive a device another
session is mid-run on.

## Signals — what a supervisor must know

When the `corral lease` CLI receives SIGTERM, SIGINT or SIGHUP it stops your command's whole
process tree **before** releasing the device: TERM to the child and its descendants, up to 2 s of
grace, forced kill for the survivors, then the release. Measured ~360 ms for a plain child, ~2.4 s
for a tree that ignores TERM. So a supervisor's normal stop is safe — the next waiter starts on an
idle device.

**SIGKILL is not covered.** The JVM runs no shutdown hooks, so the command is orphaned and keeps
running while the daemon's dead-pid prune frees the device after about 3 s and serves the next
ticket — two sessions on one device. This is what `kill -9`, a supervisor escalating past its grace
period (`timeout -k`, a stop timeout) or the OOM killer choosing the CLI's JVM produces. Send TERM
and give it a few seconds; the orphan's own PATH adb calls are refused once the next agent holds
the device, but an in-flight Gradle build (AGP talks to `:5037`) or a running `am instrument` is
not.

## Emulators

```sh
corral avd list
corral avd start Pixel_7
corral avd stop Pixel_7            # refused while leased; --force overrides
```

A freshly started AVD joins the pool **unbooted** and is not claimable until Android reports boot
complete; a claim that finds only booting AVDs says so and names the form that waits. Boot and hold
in one step:

```sh
corral avd start Pixel_7
corral lease --timeout 180 --type emulator -- ./gradlew connectedAndroidTest
```

That ticket is served the moment the boot flip lands, and the AVD stays leased for the command.
Stopping an emulator releases its lease, and an emulator lease is dropped when its serial leaves
the pool (physical and WiFi leases survive a replug). When the daemon runs as the installed
`corral.service` unit each AVD runs in its own transient `systemd-run --user` unit that survives a
daemon restart, with its output at `~/.corral/emulator-<avd>.log`.

## Troubleshooting

**`corral doctor` first, always.** One pass, exiting non-zero on a real problem: daemon
reachability and device count, wedged emulators and their remedy, the SDK shim, the rules file, the
update state. Read it like this:

- `daemon: ok (… 0 device(s))` plus a `devices: hint` line — the adb server's device list can go
  empty after an `adb reconnect`; `adb kill-server && adb start-server` recovers, but both act on
  the server, not one device, and `kill-server` drops every session's connection to every device.
  Check `corral status` first and run them only when it shows no live leases; otherwise ask the
  human. Only believe "nothing is plugged in" after that.
- `sdk adb shim: skipped - unmanaged adb path (…)` — the configured adb is not the SDK shim, so the
  shim layer is not covering that path; the wrapper and the Gradle gate still are.
- `rules: FAIL` — the rules file is missing or incomplete, so the gate is failing closed. Restart
  the daemon (it restores the default from the jar) rather than hand-writing it.
- `update: unknown` — reported honestly, never as "current".

Then, in order:

1. `corral status` — who holds what. `corral queue` — who is waiting and your position.
2. `corral devices` — the pool; a device missing here is not claimable.
3. `corral owner` vs `CORRAL_OWNER` — "leased by <someone else>" on a lease you made means your
   identity drifted between claim and use.
4. A wedged emulator is named by doctor even when it has no pool serial yet.

Windows: the CLI is `corral.bat`, and a `.bat`/`.cmd` caller must write `call corral …` — without
`call`, cmd.exe transfers control to the shim and the caller's remaining lines never run. Doctor
names the platform's own hint: the `CorralDaemon` Scheduled Task (`schtasks /query /tn CorralDaemon`)
on Windows, `systemctl --user status corral` on Linux.

Where the docs are: `CUTOVER.md` in the install bundle (or the repo) is the adoption guide —
requirements, install, core loop, selectors, Gradle, emulators, config, doctor, updates, uninstall,
known limits; `README.md` in the repo describes the design; `SECURITY.md` holds the threat model
and the bypass list. The default gate rules file is `corral-rules` beside the installer in the
bundle (`scripts/corral-rules` in a source checkout), installed as `~/.config/corral/rules`. This
skill lives at `~/.agents/skills/corral/SKILL.md` on the house install; Claude Code reads
`~/.claude/skills/<name>/` instead, so copy it there if that is your harness.
