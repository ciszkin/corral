# adb-lease — device session manager for parallel agents

> **Superseded.** The `adb-lease` MVP described here was replaced by Corral. This page is kept as the
> historical design write-up and is not current guidance — start at [README.md](README.md) and
> [CUTOVER.md](CUTOVER.md).

**Written:** 2026-09-09 · **Deployed:** 2026-09-08 16:33 · **Status:** retired (superseded by Corral)
**Last change:** 2026-09-09 — gate tracks `export`/bare assignments across a block

Stops two agents from driving the same Android device at once. One bash script leases
devices; a PreToolUse hook makes going through it mandatory.

| Path | Role |
|---|---|
| `~/bin/adb-lease` | claim / free / renew / status / devices |
| `~/bin/withdev` | symlink; claims, exports `ANDROID_SERIAL`, puts adb on PATH, frees on exit |
| `~/bin/test_lease.sh` | 18 checks |
| `~/bin/test_adb_gate.sh` | 34 checks |
| `~/.claude/hooks/adb_gate.py` | PreToolUse gate, registered in `~/.claude/settings.json` |

Design spec and implementation plan live in the repo at
`docs/superpowers/specs/2026-09-08-adb-lease-design.md` and
`docs/superpowers/plans/2026-09-08-adb-lease.md` (both untracked by design).

---

## How it works

### The lease

A lease is a **directory**, `$XDG_RUNTIME_DIR/adb-leases/<serial>.lease/`, created with
`mkdir` — atomic on POSIX. Two racing claimers both attempt it; exactly one gets it, the
other gets `EEXIST`. Inside sits one `meta` file:

```
pid=12345          # live holder process, or 0 for a detached long lease
expiry=1757340000  # epoch seconds
owner=<claude session id>
```

Busy/available is **never stored**. It is derived by asking which directories exist and are
not stale. There is no state file to desynchronise from reality, no daemon, and nothing to
repair after a crash. It lives on tmpfs, so a reboot clears every lease for free.

### Staleness — two mechanisms, deliberately

A lease is dead if `pid != 0 && !kill -0 pid`, **or** `now > expiry`. Two rules because the
two entry points differ:

- `withdev` runs your command as a child of a wrapper that lives for the whole command, so
  `pid` is real and a crash frees the device *instantly*.
- `adb-lease claim` prints a serial and exits — no surviving holder — so `pid=0` and the
  lease rests on TTL alone (default 30 min, extendable with `renew`).

Reclaim is lazy: performed by the next claimer that trips over a dead lease. No reaper
process, no background timer.

### The one subtle bit

Reclaiming a stale lease uses `mv`, not `rm -rf`. With `rm -rf`, a slow racer can delete the
*winner's freshly created* lease and then create its own — and both sides believe they hold
the device. `mv "$d" "$d.reap.$$"` is atomic, so the loser's source is already gone and its
`mv` fails.

This is the single check most worth protecting. `test_lease.sh` forks 5 concurrent claimers
at 2 serials and detects double-holds with an independent `mkdir` marker, so a "simplification"
back to `rm -rf` fails the suite.

### Two entry points

```bash
# Short: the lease lives exactly as long as the command.
withdev adb shell input tap 540 566
withdev python3 scripts/guard.py            # guard.py inherits ANDROID_SERIAL

# Long: the lease spans many separate calls.
ser=$(adb-lease claim)
export ANDROID_SERIAL=$ser
export PATH=~/Android/Sdk/platform-tools:$PATH   # only withdev does this for you
adb install app.apk
adb-lease free "$ser"                            # TTL is 30 min if you forget
```

`adb-lease status` shows who holds what; `adb-lease devices` lists serials.

### Enforcement

A `PreToolUse` hook on Bash. It does **not** regex for `adb`. It strips heredoc bodies,
splits into logical lines (absorbing lines while a quote is still open), collapses `$(...)`,
and lexes each line with `shlex` — so only a token in **command position** counts.

It allows the call when it invokes `withdev`/`adb-lease`, or when it pins
`ANDROID_SERIAL=`/`-s <serial>` whose live lease is owned by this session. The pin may be
prefixed onto the adb call itself or set earlier in the same block by `export VAR=value` or
a bare `VAR=value` line — assignments are carried forward across the command. Exit code 2
blocks the call and shows the reason to the model.

Because the hook runs *before* the shell expands anything and *before* the command executes,
`$ser` cannot be resolved and a block's own `adb-lease claim` has not run yet. For unexpanded
serials it therefore falls back to: allow if this session already holds some live lease, or
if the command claims one for itself.

---

## Capabilities

- Mutual exclusion across separate processes — different Claude sessions, subagents, plain
  terminals — with no shared parent.
- Multiple devices handled automatically; a claimer takes the first serial with no live lease.
- Crash recovery: instant for `withdev` (dead PID), bounded by TTL otherwise.
- Contention handling: poll every 2s to a deadline (default 10 min), then fail with each
  serial's owner and remaining TTL.
- Ownership enforcement: `free` refuses a lease you do not own (`ADB_LEASE_FORCE=1` overrides).
- Self-teaching denials: every blocked call prints the two working forms and `adb-lease status`.
- Test seams: `ADB_LEASE_DIR` and `ADB_LEASE_FAKE_DEVICES` let the suites run without a device.

---

## What one day of real use showed

Reviewed 9 sessions that used the tooling since deployment.

**Usage:** `withdev` 79 · `claim` 24 · `free` 23 · `status` 32 · `renew` 4. It is being used,
not routed around.

### It prevented a real collision 2 minutes after going live

| Time (2026-09-08) | Session | Event |
|---|---|---|
| 16:33:32 | `61707722` | claims `192.168.100.7:5555` (M2101K7BG, Android 13) |
| 16:35:18 | `72b700c4` | sees that device — **busy**, owner `61707722`, 1693s left |
| 16:35:44 | `72b700c4` | tries to **free the other session's lease** → *refused* |
| 16:35:52 | `72b700c4` | probes whether PID 1667 is alive, i.e. whether the lease is orphaned |
| 16:37:46 | — | second device `18906454` appears; one free, one busy |
| 16:37:55 | `72b700c4` | claims `18906454` (2109119DG, Android 14) |
| 16:44:44 | `61707722` | frees; status confirms `72b700c4` still holds the other |

The entire premise, demonstrated end to end. `72b700c4` was *told* about `ADB_LEASE_FORCE=1`
and chose not to use it — it investigated staleness instead, which is the reasoning the
design intends.

### One real defect: `export` is invisible to the gate

The most natural way to pin a device for a multi-command block is denied, **even with a
literal serial and a valid held lease**:

```
DENIED   export ANDROID_SERIAL=$ser    (earlier line)  -> "this call pins no device"
DENIED   export ANDROID_SERIAL=dev1    (earlier line)  -> "this call pins no device"
allowed  ANDROID_SERIAL=$ser adb ...   (same line)
```

`split_env` only stripped assignments prefixed onto the *same* command, so an `export` on an
earlier line was never seen. Three sessions hit it (`0444e595`, `67691ac7`, `9fe993b2`); one
retried verbatim, one switched to an `ADB=path` workaround.

**Fixed 2026-09-09.** The gate now carries a `block_env` across the whole command, populated
by `export VAR=value` and by bare `VAR=value` lines, and consults it when an adb call has no
prefixed assignment of its own. Six checks cover it, including that another session's device
and an unleased serial are still denied through the same path.

### Contention never happened

Zero `waited Ns` events, zero claim timeouts. Deferring FIFO fairness was correct — and the
second device appearing mid-session is *why*: the pool grew instead of queuing.

### Two corrections to the first pass of this review

- Two apparent "denials" were commands that merely *printed* the gate's source text.
- 21 ungated `./gradlew install` calls turned out to be from **Aug 6 and Aug 20**, before the
  gate existed; those transcript files simply have recent mtimes. **No gradle install has run
  since deployment**, so that ceiling is real but unexercised.

---

## Known ceilings

| Ceiling | Consequence | Trigger to fix |
|---|---|---|
| `./gradlew installDebug` shells out to adb internally | Invisible to the gate; installs on whatever adb picks | Any post-gate occurrence — none yet |
| Unexpanded `$ser` falls back to "holds any lease" | Cannot verify you drive the device you claimed | An agent holding one device, driving another |
| Lottery, not FIFO | A waiter can be passed over repeatedly | A logged long wait — none yet |
| `CLAIMS_ITSELF` matches the literal text `adb-lease claim` | A command merely mentioning it gets the looser check | Low value to fix |
| Nothing constrains non-Claude processes | Your own terminal bypasses everything | By design |

Each is marked with a `ponytail:` comment in the source naming the ceiling and its upgrade path.

---

## Pros and cons

**Pros.** No daemon, no state file, no cleanup path — the failure modes that plague lock
managers do not exist because the state is derived rather than stored. Crash recovery is
instant for `withdev` and bounded by TTL otherwise. The gate teaches: every denial names the
fix, and sessions demonstrably followed it. Multi-device support fell out for free. Total
surface is one bash script and one Python file, covered by 46 checks.

**Cons.** The gate adds a Python interpreter start (~30ms) to *every* Bash call in every
session, for a hook that matters to a small fraction of them. Its command parsing is a
heuristic and always will be — it has already produced one false allow and several false
denials, each fixed reactively rather than by construction. The unexpanded-serial fallback is
genuinely weaker than the spec originally promised. And the biggest device consumer, gradle,
sits entirely outside the fence.

---

## Recommendation

The `export` tracking is done. Leave everything else. The gradle hole is worth closing only
when something actually installs through it; the deferral has held for a day of real use.
