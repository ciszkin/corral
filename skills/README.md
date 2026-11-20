# What lives here

`corral/SKILL.md` is the agent skill — one file, and **the only hand-maintained copy**. The installer
bundles it verbatim (`scripts/make-installer.sh` mirrors it into
`corral-installer/skills/corral/SKILL.md`, a generated, byte-identical copy), so edit this one and let the
script regenerate the mirror. Do not add a second copy of it for a packaging format — point the
packaging format at this file (see the plugin entry below) so the two can never drift.

## What the installer does with it

`scripts/install.sh` copies the skill to `~/.agents/skills/corral/SKILL.md`, creating
`~/.agents/skills` (and `~/.agents`) when the host has none: a fresh machine — the launch's new
user — must get the skill from the one command, so the installer no longer waits for a directory it
was never going to find. It also creates or refreshes `~/.claude/skills/corral` as a link to
`~/.agents/skills/corral`, because Claude Code reads `~/.claude/skills/<name>/` and not
`~/.agents/skills/`; one file then serves both layouts and stays current. This block is the same on
POSIX and Windows (the installer is bash on both; there is no separate Windows skill path), and on
Windows the link is a directory junction when MSYS cannot make a real symlink — a copy would go
stale, so the installer does not fall back to one. If neither is possible it says so and prints the
one command to run:

```sh
mkdir -p ~/.claude/skills && ln -s ~/.agents/skills/corral ~/.claude/skills/corral
```

The link is never forced over a real directory, a file or a link into somewhere else: that path
belongs to the operator, and the installer names it instead. The skill file itself is refreshed on
every install and on `corral update --apply` **while it is unedited**; an edited copy is left alone
until you delete it or restore the shipped bytes. Uninstall removes the skill and the link it
installed, and removes `~/.agents/skills` only when that leaves it empty — a directory holding
another skill stays, and the summary names it under "Left alone". The plugin paths below need
neither link nor copy.

## Install it with one command

Two distribution paths exist. Both resolve the same single `corral/SKILL.md`.

### 1. The `skills` CLI (any of the agents it supports)

```sh
npx skills add ciszkin/corral          # resolves skills/corral/SKILL.md from the default branch
npx skills add ciszkin/corral@main -a claude-code -y
```

The CLI searches a fixed list of container directories, `skills/` among them, walking each up to
three levels deep for a `SKILL.md` (["Creating skills"](https://github.com/vercel-labs/skills/blob/main/docs/creating-skills.md),
section *Skill Discovery*). `skills/corral/SKILL.md` is exactly the flat
`skills/<name>/SKILL.md` layout, so this repo needs **no manifest** for that command — the folder
layout is the manifest. Requires Node.js 22.20 or later.

### 2. The Claude Code plugin marketplace

```sh
/plugin marketplace add ciszkin/corral   # in a session; or: claude plugin marketplace add ciszkin/corral
/plugin install corral@corral            # entry name @ marketplace name
```

`.claude-plugin/marketplace.json` at the repo root declares one plugin, `corral`, whose `source` is
`"./"` — the repo root is the plugin root, so its `skills/` directory is the plugin's skill
directory and `corral/SKILL.md` reaches a session as `/corral:corral`. The shape is the documented
one ([Create a marketplace](https://code.claude.com/docs/en/plugin-marketplaces),
[Marketplace reference](https://code.claude.com/docs/en/plugins/marketplace-reference)): `name`,
`owner`, `plugins[]`, each entry with `name` + `source`, relative paths resolved from the
marketplace root and required to start with `./`. There is deliberately no `plugin.json`: with no
plugin manifest present the marketplace entry *is* the plugin manifest, so a second file would only
be a second place to keep a name and a description.

`corral-installer/` does not ship `.claude-plugin/` or this file. The bundle is an install artifact
for a host, not a marketplace checkout.

## Which one should a user pick?

- Corral already installed through `scripts/install.sh` — the installer placed the skill and linked
  it where Claude Code reads it, so neither path below is needed for that harness layout. Run path 1
  or path 2 when you want the skill in a harness that reads neither directory (`npx skills add`
  reaches more agents; `/plugin` is one command inside Claude Code).
- Corral installed, agent elsewhere (a laptop driving the phone box over HTTP) — either path works;
  `npx skills add` reaches more agents, `/plugin` is one command inside Claude Code.
- Neither — install Corral first. The skill teaches the `corral` CLI; without the CLI it explains
  commands that are not there.

## Known gaps

- `claude plugin validate .` — the validator the marketplace docs describe — has **not** been run
  against this manifest. No host used for these checks has the `claude` CLI, so the shape above is
  verified only by a strict JSON parse and field-by-field against the two cited docs; the things only
  the validator would prove (the `"./"` spelling passing its relative-path check, and whether a stray
  `skills/README.md` beside `skills/corral/` draws a warning) are open. Run it once on a box with
  Claude Code before advertising the marketplace, and fix whatever it reports.
