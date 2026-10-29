# Adopting Corral

Corral is a device session manager for coding agents. It herds the Android
devices attached to one machine — USB, WiFi and emulators — into a pool and
leases each device to exactly one agent session at a time.

You may not need this. `adb` hands every connected device to every process
that calls it. That is fine for one developer with one device. It stops being
fine the moment a second agent, a second test run, or a background build
grabs the same device mid-run: one harness scrolls while another screenshots,
a `connectedAndroidTest` wipes an app another session is driving, and the two
blame each other's failures. Corral puts a lease in front of adb so that only
the session holding a device can touch it, and everything else either waits
its turn or is turned away with a message naming the holder.

This guide is for someone adopting Corral on a Linux host — or on Windows, via the
installer's Windows branch — with a working Android SDK. It assumes no prior
knowledge of the project.

## Requirements

- Linux, or Windows through the installer's Windows branch (Git Bash or Cygwin).
- JDK 17 or newer.
- A working Android SDK with `platform-tools/adb`.
- Optional: `scrcpy`, for the console's read-only live view.

### Windows

Windows is an **install target**: `install.sh` has a Windows branch that writes the
`.bat` shims to `%USERPROFILE%\bin`, installs a Gradle gate, and runs the daemon from
a per-user `CorralDaemon` Scheduled Task (there is no systemd and no `~/bin` shell
shim). The desktop console is verified on Windows with JDK 25, and Windows was
host-verified on one box for the install and uninstall paths, the per-user
`CorralDaemon` Scheduled Task, the `.bat` shims (including a call in flight across
an install), and the process-ancestry identity walk. macOS is not an install
target: the daemon and CLI are portable Java, but there is no installer, service
or host verification for it.

Two Windows nuances remain, and both are reported by the installer rather than hidden:

- a `.bat` twin that has to be rewritten can be read at a stale offset by a call
  already in flight — an unchanged twin is never written at all;
- a running daemon or console holds its jar open, so that jar cannot be replaced: the
  run continues, refreshes everything else, names every jar it left behind, and exits
  non-zero.

## Install

From a release bundle (`corral-<version>.zip`), unpack it and run the
installer. The zip is **flat** — `install.sh` and both jars sit at the zip root, which
is what `corral update --apply` expects when it extracts a release:

```sh
unzip corral-<version>.zip -d corral-<version>
cd corral-<version>
./install.sh
```

From a source checkout, build the daemon jar and install it:

```sh
./gradlew :corral-daemon:shadowJar
scripts/install.sh
```

The installer is user-scoped — no root — and idempotent, so it is safe to
re-run. It:

- detects Java and adb, or takes `--java PATH` / `--adb PATH` explicitly;
- enables the `corral` systemd **user** service at login and restarts it;
- puts an `adb` wrapper and the `corral` CLI on `~/bin` (which must precede
  the SDK directory on `PATH`);
- shims the SDK's `platform-tools/adb` so absolute-path callers are gated too
  (`--no-sdk-shim` to skip);
- installs a Gradle `init.d` gate that pre-flights device-touching tasks;
- installs the `corral` agent skill (how an agent should lease devices and read the gate) to
  `~/.agents/skills/corral/SKILL.md` when `~/.agents/skills` already exists — the house harness
  discovers it from there, while Claude Code reads `~/.claude/skills/<name>/` instead;
- optionally installs the desktop console with `--ui` (the default when a
  `corral-ui-all.jar` is present) or `--no-ui`.

Reload your shell, then check that everything came up:

```sh
corral doctor
```

On Windows the CLI is `corral.bat`, so from another `.bat`/`.cmd` invoke it as
**`call corral …`** — without `call`, cmd.exe transfers control to the shim and
the caller's remaining lines never run. Interactive cmd.exe and PowerShell are
unaffected.

## The core loop

Claim a device, use it, release it. There are two styles.

Hold a device with a claim and free it when done:

```sh
serial=$(corral claim)          # prints the serial of a free, usable device
ANDROID_SERIAL=$serial adb shell ...
corral free "$serial"
```

A claim's TTL is the hold, and its owner string is the identity, so a claim
outlives the process that made it. `corral free --all` drops everything you
own.

Or let a wrapper do the claim/free around one command:

```sh
corral lease -- ./gradlew connectedAndroidTest
corral lease --type emulator -- adb install app.apk
```

`corral lease` claims a matching device, exports `ANDROID_SERIAL`, runs the
command after `--`, and releases the device when the command exits. The lease
is tied to the lifetime of the command, so a crashed command frees its device
through the usual TTL/pid machinery.

When nothing is free and you would rather wait than poll:

```sh
corral lease --timeout 120 -- ./gradlew connectedAndroidTest
corral lease --timeout 120 --model Pixel_7 -- adb install app.apk
```

`corral lease --timeout S` registers a ticket, blocks until a matching device is
offered, then runs the command **holding** that device until the command exits. A
matching device is offered to the longest-waiting ticket that is still polling, so
waiting is FIFO by arrival — and because the lease lives for the command's lifetime
(the CLI renews it while the command runs), the device cannot move on to the next
ticket while the command runs. The one exception is SIGKILL to the `corral lease`
process itself: the JVM runs no shutdown hook, so the CLI cannot clean up — the next
paragraph covers how the hold ends in that case. Send TERM (not KILL) and give it a
few seconds. A command that crashes still frees its device through the usual TTL/pid
machinery, and a ticket that times out runs nothing.

How the hold ends matters to whoever writes the kill policy. On `SIGTERM`, `SIGINT` or
`SIGHUP`, `corral lease` stops the command's whole process tree and only then hands the
device back: a command that ignores `SIGTERM` gets two seconds and is then killed, and
the next ticket starts on an idle device. `SIGKILL` is the exception — a JVM runs no
shutdown hooks for it, so the CLI cannot clean up and the command is orphaned and keeps
driving the device. The daemon sees the CLI's pid die, but the lease also carries the
pid of the command the CLI started, so the device stays held while that command lives,
up to the lease's TTL. The CLI renews the lease while the command runs, so the TTL
bounds only a CLI that stopped renewing — exactly this SIGKILL case. That is bounded,
not prevented: once the TTL runs out (or an operator force-frees the device) the device
moves on — the orphan's own `adb` calls through Corral's wrapper are refused from then
on, but a client that bypasses the wrapper — an in-flight `./gradlew
connectedAndroidTest`, an `am instrument` already running — keeps going. Prefer
`SIGTERM`, and if a supervisor has to escalate to `SIGKILL`, kill the command with it
(the process group, or the command's pid) rather than the CLI alone.

`corral wait` uses the same queue but is the poll-then-decide form: it blocks until
served, prints the serial, and returns the lease when the wait process exits. Use it
when you want to know which device you would get and then decide what to do; use
`lease --timeout` when you want the device held for the work. A ticket that goes quiet
is dropped after about 30 seconds. `corral queue` lists the outstanding tickets, and
`corral status` names the current holder of every device.

Identity: leases are owned per agent session, not per unix user. Set
`CORRAL_OWNER` to a stable name if your harness's session outlives a single
process; otherwise Corral derives an owner from process ancestry and falls
back to `user:$USER`. Membership in the ancestry `shell_skip`/`init_floor`
lists ignores case and one trailing `.exe` on **both** sides, so a list entry
written once per program (`bash`) also matches `bash.exe`, and either spelling
is accepted. On Linux a file literally named `bash.exe` is therefore skipped as
well — an accepted trade.

## Selectors

`claim`, `wait` and `lease` accept the same selector flags. (`queue` takes none.)
An empty selector means "any usable device".

```
--serial S                     exact device serial
--model M                      device model
--os V                         Android version, as a minimum (e.g. --os 13)
--type usb | wifi | emulator   transport class
--avd A                        emulator AVD name
```

Selectors compose: `corral claim --type emulator --os 14` claims a booted
emulator running Android 14 or newer.

## Gradle builds

Android Gradle Plugin invokes adb without going through the adb binary, so the
`PATH` wrapper and the SDK shim cannot see it. The installer therefore drops a
Gradle `init.d` gate that pre-flights `install*` and `connected*AndroidTest`
tasks and refuses to run them against a device you do not hold. The check runs
at task execution, so gated builds stay configuration-cache compatible.

The intended flow is to wrap the build in a lease:

```sh
corral lease -- ./gradlew connectedAndroidTest
```

A build pinned with `ANDROID_SERIAL` is checked against that one device
instead of the whole selector. `corral doctor` reports the state of the gate.

## Emulators

Corral manages AVDs directly:

```sh
corral avd list
corral avd start Pixel_7_API_34
corral avd stop Pixel_7_API_34          # refused while leased; --force overrides
```

A freshly started AVD joins the pool **unbooted** and is not claimable until
Android reports boot complete. `corral lease --timeout S --type emulator -- <cmd>`
queues until the flip lands, then runs the command **holding** that AVD; a plain
`corral claim --type emulator` teaches you the same. (`corral wait --type emulator`
waits for the flip too, but it hands the lease back when it exits, so it is the
poll-then-decide form — wrap work in `lease --timeout`.) When the daemon runs as
the installed `corral.service` unit, each AVD runs in its own transient
`systemd-run --user` unit that survives a daemon restart, with its output at
`~/.corral/emulator-<avd>.log`. Stopping an emulator releases its lease.

## The desktop console

```sh
corral-ui                       # connects to http://127.0.0.1:8798
corral-ui --url http://127.0.0.1:9000
```

The console resolves its daemon URL from `--url`, else `CORRAL_URL`, else a
stored preference, else the default `http://127.0.0.1:8798`. It is a single
screen with a left rail and six sections:

- **Devices** — a card carousel with live thumbnails, the holder and a TTL
  countdown;
- **Emulators** — AVDs, running serials, a boot chip, and start/stop;
- **Queue** — the wait list;
- **Doctor** — per-layer health, with a rail badge on failure;
- **Rules** — the gate rules;
- **Settings** — the daemon knobs and this client's own preferences.

It is an observer, not a leaser: its identity is `CORRAL_OWNER` (default
`corral-ui`), and its scrcpy live view is strictly read-only. Install it with
`install.sh --ui`. Its own traffic is HTTP/WebSocket to the daemon; it never
talks adb itself.

## Configuration

Daemon settings persist to `~/.config/corral/daemon.env` (override with
`CORRAL_ENV_FILE`), with **file > inherited environment > built-in default**
precedence. A deliberate edit beats the service unit's `Environment=` and
survives restarts. There is no interactive `config` wizard: edit that file by
hand, or use the console's **Settings** section, which reads and writes the
same knobs through the daemon's config API. The settings include the port, the
adb and emulator paths, the screenshot policy and its observer list, the
database path, and the Gradle task list.

The gate rules (which adb commands are read-only, which flags take a value,
and the identity ancestor lists) live in the same editable file,
`~/.config/corral/rules` (override `CORRAL_RULES`). A missing rules file fails
closed, and the daemon restores the built-in default from the jar.

The update check is on by default; see [Updates](#updates) for its kill-switch
(`CORRAL_UPDATE_CHECK`).

## `corral doctor`

`corral doctor` is the health check. It reports, in one pass:

- whether the daemon is reachable, and how many devices it sees;
- any wedged emulator and its remedy;
- the state of the SDK adb shim;
- the state of the gate rules;
- the update state, honestly — "unknown" is reported as unknown, never as
  "current".

It exits non-zero when a layer has a real problem. Run it after installing and
after any SDK or environment change.

## Updates

The daemon checks for updates once a day. The check is a single
unauthenticated `GET` to
`https://api.github.com/repos/<owner>/<repo>/releases/latest` (default
`ciszkin/corral`; `CORRAL_REPO` points both the check and the apply at another
repository — an environment variable only, not a config knob, which is what a
fork wants). It sends no token and no credentials.

The check is **on by default**: an unmodified daemon calls `api.github.com`
once a day. `CORRAL_UPDATE_CHECK=off` in `daemon.env` or the environment turns
it off, and the daemon re-reads it every cycle, so no restart is needed. The
daemon's `GET /api/update` is a read — current version, latest version,
whether an update is available, whether the check is enabled; it starts
nothing.

`corral update` reports what the last check found; it downloads nothing.

Applying an update is always explicit:

```sh
corral update --apply
```

Without `--apply`, nothing is downloaded and nothing is run. Applying
downloads the release `corral-<version>.zip` and its `.zip.sha256` from the
same release, verifies the SHA-256, and only then extracts the bundle and runs
its `install.sh`, which restarts the daemon. A checksum mismatch, a missing
checksum, or an unreadable one stops the apply before any file is extracted.

**What the checksum proves.** The `.sha256` comes from the same release as the
zip, so it proves the download was not corrupted or truncated — **not** that
the publisher is genuine. Anyone who can publish a release on that repository
(a leaked token, a compromised account) runs `install.sh`, i.e. arbitrary code
as you, on every host that applies it. A detached signature checked against a
key pinned in the jar (minisign, cosign, GPG) would separate "the download is
intact" from "the publisher is genuine"; none is shipped. See
[SECURITY.md](SECURITY.md) for the trust boundary.

Replacing the jars is atomic: `install.sh` writes each jar to a temporary file
beside it and renames it into place, so a daemon (or console) that is running
keeps the old file and the next start picks up the new one. On Windows a
running JVM holds its jar open, so a jar cannot be replaced: the installer
says which jar it could not replace, still refreshes the `~/bin` shims, the
gradle gate and the `CorralDaemon` Scheduled Task, does **not** start a second
daemon (a running one holds the port), names **every** jar left behind in one
closing summary, and exits non-zero. Quit the console and stop the daemon
(end the `CorralDaemon` task and the `java.exe` running the jar), then re-run
to finish.

## Uninstall

```sh
./install.sh --uninstall          # from a bundle
scripts/install.sh --uninstall    # from a source checkout
```

This stops and disables the `corral` user service, removes the unit, the adb
wrapper, the `corral` and `corral-ui` shims, the desktop launcher and icon, the
Gradle `init.d` gate, and the `~/.config/corral` files, and removes `~/.corral`
(the daemon jar and its SQLite database). Your Android SDK and your `PATH`
edits in the shell rc files are left alone.

## Known limits

- **Identity is cooperative, not authenticated.** A client declares who it is
  and the daemon believes it. Corral is a rail against accidental or unaware
  snooping on a shared host, not a security boundary against a hostile local
  process. Real separation would need issued tokens, which are not shipped.
- **The absolute-path adb exception.** The `PATH` wrapper catches PATH-resolved
  adb calls and the SDK shim catches calls to the SDK's own absolute path, but a
  client that invokes an adb by an absolute path outside the SDK directory (a
  tool carrying its own adb build, for example) can sit outside the gate. The
  console's scrcpy live view is one such case: scrcpy resolves adb itself.
  This is a documented, acknowledged exception.
- **Loopback only.** The daemon binds `127.0.0.1` and rejects non-local Host
  headers, so it cannot be reached from another machine. A non-default port via
  `--url` still has to be a local one.
- **Linux-specific service paths.** The systemd user service and the emulator's
  `systemd-run` path are Linux-only; Windows runs the daemon from a per-user Scheduled
  Task instead (see [Windows](#windows)), and macOS has no installer. The daemon and
  CLI themselves are OS-agnostic Java.
