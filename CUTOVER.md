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

This guide is for someone adopting Corral on a Linux host with a working
Android SDK. It assumes no prior knowledge of the project.

## Requirements

- Linux (the daemon and CLI are OS-agnostic Java; the installer and the
  service unit target Linux).
- JDK 17 or newer.
- A working Android SDK with `platform-tools/adb`.
- Optional: `scrcpy`, for the console's read-only live view.

## Install

From a release bundle (`corral-<version>.zip`), unpack it and run the
installer:

```sh
unzip corral-<version>.zip
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
- stops and enables the `corral` systemd **user** service;
- puts an `adb` wrapper and the `corral` CLI on `~/bin` (which must precede
  the SDK directory on `PATH`);
- shims the SDK's `platform-tools/adb` so absolute-path callers are gated too
  (`--no-sdk-shim` to skip);
- installs a Gradle `init.d` gate that pre-flights device-touching tasks;
- optionally installs the desktop console with `--ui` (the default when a
  `corral-ui-all.jar` is present) or `--no-ui`.

Reload your shell, then check that everything came up:

```sh
corral doctor
```

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
corral wait                        # queues for any device, blocks until served
corral wait --timeout 120 --model Pixel_7
```

`corral wait` registers a ticket and polls it. A matching device is offered to
the longest-waiting ticket that is still polling, so waiting is FIFO by
arrival. The wait process returns its lease when it exits; a hard kill is
still covered by the TTL. A ticket that goes quiet is dropped after about
30 seconds. `corral queue` lists the outstanding tickets, and `corral status`
names the current holder of every device.

Identity: leases are owned per agent session, not per unix user. Set
`CORRAL_OWNER` to a stable name if your harness's session outlives a single
process; otherwise Corral derives an owner from process ancestry and falls
back to `user:$USER`.

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
Android reports boot complete. `corral wait --type emulator` queues until the
flip lands, and the queued ticket is served the moment it does; a plain
`corral claim --type emulator` teaches you the same. When the daemon runs as
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
running JVM holds its jar open, so the install refuses and says the jar was
not replaced and that the old daemon is still running — stop it (end the
`CorralDaemon` task and the `java.exe` running the jar) and re-run.

## Uninstall

```sh
scripts/install.sh --uninstall
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
- **Linux host.** The installer, the systemd user service and the emulator
  systemd-run path target Linux. The daemon and CLI themselves are
  OS-agnostic, but non-Linux hosts are not verified by this project.
