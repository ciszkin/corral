# Security Policy

## Reporting a vulnerability

Please report suspected vulnerabilities privately through a
[GitHub security advisory](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository (Security → "Report a vulnerability"). Do not open a public
issue for a security problem. There is no email address for reports.

## Threat model

Identity in Corral is cooperative and self-declared: a client states who it is
(`CORRAL_OWNER`, or a value derived from process ancestry) and the daemon
believes it. It is a rail against accidental or unaware snooping on a shared
host — keeping one agent session off another session's device — not an
authentication mechanism, and it does not defend against a client that lies
about its identity. Real separation would require issued tokens; that is
recorded as future work and is not shipped.

The daemon binds `127.0.0.1` only, and the CLI talks to it over loopback. The daemon refuses any
non-local `Host` header with `403`, so it is not reachable off-host and cannot
be driven by DNS rebinding from a browser on the same machine.

The lease gate is enforced in three independent layers on POSIX (two on Windows, where there is
no SDK `adb.exe` shim), and each has a known bypass:

- the `adb` wrapper earlier on `PATH` — bypassed by invoking a different adb
  (for example a static build with its own copy) without going through `PATH`;
- the SDK `platform-tools/adb` shim — this gate only covers the adb inside
  the SDK's `platform-tools` directory, so a client that invokes some OTHER
  adb by absolute path (a static build's own copy, for instance) is outside
  this gate as well as the wrapper's. This is a documented, acknowledged
  exception, not an oversight;
- the Gradle `init.d` gate, which pre-flights `install*` /
  `connected*AndroidTest` tasks because AGP bypasses the adb binary — bypassed
  by a build that does not use the installed Gradle gate.

Three more bypasses are common enough to name, because they are the ones a
developer hits without meaning to:

- **ddmlib clients that talk to the adb server on `:5037` directly.** Android
  Studio's Run/deploy, Logcat and Device Explorer do exactly this: no adb
  binary, no Gradle install task, so all the layers miss them.
- **`platform-tools/adb.real`**, the real binary that sits beside the SDK shim.
  Anything that names it by absolute path is outside the shim layer (and, when
  it does not go through `PATH`, the wrapper too).
- **the console's scrcpy live view.** scrcpy resolves adb itself (a static build
  ships its own), so that one path generally sits outside all of them. The
  view is read-only by construction — `--no-control` (which also drops
  drag-and-drop install/push), `--no-clipboard-autosync`, `--no-audio` — so an
  observer can watch a leased device but not type into it.

None of these layers — including the ones above — is a security boundary against
a hostile local process; they are guardrails for cooperating agents and humans,
and a client that goes around adb entirely is outside all of them.

## The demo is a simulation

`corral demo` runs a second, local daemon whose pool, screenshots and emulator host are simulated
in process (`DemoWorld`). It is not a security boundary and not a sandbox for untrusted code: it is
a way to look at the product without a device. It installs nothing — no `adb` wrapper, SDK shim,
Gradle gate, service unit or Scheduled Task — and it starts no adb, emulator or scrcpy process,
makes no network call, and reads or writes neither `~/.corral` nor `~/.config/corral`. Its device
records are fabricated (the shipped ones are labelled `(demo)`), so a claim or a read taken against
a demo daemon says nothing about a real device.

Every surface says DEMO: the CLI's inspection verbs print a demo line on stderr, the devices, status
and config responses carry `demo:true`, and so does the WS hello frame. The console shows a DEMO
banner above its content and a DEMO pill in the status bar. In a demo the console disables its update
row — no Check/Install buttons — and does not render the scrcpy live view, naming the reason.

Its configuration route refuses the writes that would point it back at the host: `CORRAL_ADB`,
`CORRAL_EMULATOR`, `CORRAL_RULES`, `CORRAL_ENV_FILE`, `CORRAL_DB` and `CORRAL_UPDATE_CHECK=on` are
rejected by `PUT /api/config` with a `400` that names demo mode, and a refused body writes nothing;
`CORRAL_UPDATE_CHECK=off` is already the demo's state, so it remains a no-op. The supervisor also
strips inherited `CORRAL_ADB`, `CORRAL_EMULATOR`, `CORRAL_REPO` and the Android SDK selectors before
spawning the child. Those refusals keep a demo from wandering onto real paths; they are guardrails,
not a containment guarantee against a hostile local process.

## The update channel's outbound surface

The daemon makes one outbound call a day on its own, plus one run per explicit
`?force` or off→on switch flip: an unauthenticated
`GET` to `https://api.github.com/repos/<owner>/<repo>/releases/latest`
(`<owner>/<repo>` defaults to `ciszkin/corral`, overridable with
`CORRAL_REPO`). It sends no token and no credentials of any kind. The check is
behind the live kill-switch `CORRAL_UPDATE_CHECK=on|off`; flipping it to `off`
stops the check without a restart.

## Trust boundary of the update apply path

This is the weakest link, and it is stated plainly. When an update is applied,
the release `.zip` and its `.sha256` are fetched from the SAME unauthenticated
origin, `github.com/<owner>/<repo>/releases/...`. Anyone able to publish a
release, or able to intercept the TLS connection, can rewrite the archive and
its checksum together. The checksum therefore buys corruption and truncation
detection and the guarantee that no unverified bytes are ever executed — it
does NOT authenticate the publisher. The actual trust anchor is TLS to GitHub
plus write access to the repository. Applying an update is always an explicit
user action (`corral update --apply`, or the console's apply button); there is
no automatic apply.
