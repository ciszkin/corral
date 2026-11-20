import { Plugin } from "@opencode/plugin"

// The daemon serves the lease API over MCP at /mcp (corral-daemon/src/main/kotlin/dev/corral/Mcp.kt).
const DEFAULT_URL = "http://127.0.0.1:8798/mcp"

// Registers the Corral MCP server so one `plugins` entry replaces `opencode mcp add corral --url ...`.
// Set `{"package": ".../plugins/corral", "options": {"url": "http://127.0.0.1:OTHER/mcp"}}` for a
// daemon on a non-default CORRAL_PORT.
export default Plugin.define({
  id: "corral",
  async setup(ctx) {
    const url = typeof ctx.options.url === "string" ? ctx.options.url : DEFAULT_URL
    await ctx.mcp.transform((editor) => {
      editor.set("corral", { type: "remote", url })
    })
  },
})
