# Corral for OpenCode

Opencode V2 gets Corral in two independent pieces: the **MCP server** (device control) and the
**skill** (when and how to use it). The plugin in `plugins/corral/` is optional packaging, not a
third integration.

Sources: <https://opencode.ai/v2/docs/mcp-servers>, <https://opencode.ai/v2/docs/skills>,
<https://opencode.ai/v2/docs/build/plugins/server>.

## 1. Device control: the MCP server

The daemon serves the lease API at `/mcp` (streamable HTTP, `/mcp` in `Mcp.kt`) as eight tools:
`list_devices`, `queue`, `claim`, `free`, `screenshot`, `renew`, `start_emulator`, `stop_emulator`.

```sh
opencode mcp add corral --url http://127.0.0.1:8798/mcp --global
```

`--global` writes `~/.config/opencode/opencode.json` so every project sees it; drop it to scope the
server to the current project. It writes:

```jsonc
{
  "mcp": {
    "servers": {
      "corral": { "type": "remote", "url": "http://127.0.0.1:8798/mcp" },
    },
  },
}
```

- **No OAuth, no headers.** The daemon is loopback-only and answers `403 corral: local only` to any
  other `Host`, so opencode never sees a `401` and never starts a sign-in. Leave `oauth` out.
- **Leave `protocol` at its default.** The daemon speaks the classic `initialize` handshake (it
  answered `protocolVersion: 2025-06-18` to a curl handshake and listed all eight tools).
- Code Mode is on by default, so the tools are grouped as `tools.corral.claim(...)` and cost one
  line of context instead of eight schemas.
- `claim` with `waitSeconds > 0` returns a ticket; re-call `claim` with that ticket on the
  `retryAfterMs` cadence (5 s for MCP) until it returns a lease.

### Connection verified (row 89, v0.1.4)

The daemon now uses `io.modelcontextprotocol:kotlin-sdk-server` **0.16.0** with a small
`McpJsonConverter` that keeps `protocolVersion` in `initialize` replies even for newer requests.
opencode 2.2.5 sends `protocolVersion: "2025-11-25"`, gets the field echoed, and `opencode mcp
list` reports `corral` as connected. The eight tools and the Code Mode grouping above are
unchanged.

An earlier 0.10.0 build omitted `protocolVersion` on its fallback path and opencode refused the
result (`protocolVersion expected string, received undefined`) — that is fixed here, so do not
downgrade the SDK.

Check the connection from a shell with `opencode mcp list` (or `/mcps` inside the TUI).

## 2. The skill: nothing to do on an installer host

`install.sh` puts the skill at `~/.agents/skills/corral/SKILL.md`, creating `~/.agents/skills` on a
fresh host, and opencode discovers `~/.agents/skills` as a *global compatibility* source, so an
install already delivers skill `corral` to every project. Its layout (`<source>/corral/SKILL.md`)
is exactly what opencode expects, and its frontmatter already carries `name` and `description`, so
the skill is advertised without a config entry. A source checkout without the installer needs the
`skills` entry below.

Without the installer (a source checkout), point opencode at this repo's `skills/` directory — the
path resolves from the working directory, not the config file:

```jsonc
{ "skills": ["/path/to/corral/skills"] }
```

`./skills` does not fit the automatic layout: project discovery looks in `.opencode/skills`, and
`skills/corral/SKILL.md` sits outside it.

## 3. The plugin: one entry instead of one command

`plugins/corral/index.ts` is a small server part that registers the MCP server above:

```ts
await ctx.mcp.transform((editor) => {
  editor.set("corral", { type: "remote", url: "http://127.0.0.1:8798/mcp" })
})
```

- `.opencode/plugins/` loads automatically, so **copying this directory into a project** gives every
  teammate the server with no `mcp add` and no per-user config — the URL stays reviewable in the
  repo.
- Kept elsewhere, reference it by path:
  `"plugins": [{"package": "/path/to/corral/.opencode/plugins/corral", "options": {"url": "http://127.0.0.1:<port>/mcp"}}]`.
  `options.url` exists only for a daemon on a non-default `CORRAL_PORT`.
- It costs what it buys: for a single user `opencode mcp add --global` is one command and shows up
  in `/mcps`, so the plugin is not needed. It cannot add more than registration — a plugin cannot
  deny a tool call (`execute.before` may only inspect or replace input), and the Corral gates
  already refuse raw `adb`, so there is no useful "warn on adb" hook; a `corral devices` tool would
  duplicate MCP `list_devices`.
- Because it lives in this repo, it also loads for agents working on Corral itself and logs a failed
  server whenever the daemon is not running. Remove the directory if that noise is not wanted.

## Verifying a change

```sh
# type-check the plugin against the API it imports (throwaway dir, not the Gradle build)
npx tsc --noEmit --target es2022 --module esnext --moduleResolution bundler --strict \
  --skipLibCheck .opencode/plugins/corral/index.ts

# the daemon side, from the served binary
curl -s -X POST http://127.0.0.1:8798/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

`tsc` needs `@opencode/plugin` on disk: `npm i @opencode/plugin@2.3.2 typescript@5` in a scratch
directory and pass `--prefix`, or symlink its `node_modules` next to the plugin while you work (do
not commit it).
