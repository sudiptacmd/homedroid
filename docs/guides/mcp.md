# Connecting AI assistants (MCP)

**Video:** [Watch the setup on the Homedroid website](https://homedroid.seltiv.com/guides/mcp/)

Homedroid has a built-in [Model Context Protocol](https://modelcontextprotocol.io) server, so
assistants such as Claude, Cursor and VS Code can check on the phone and, if you allow it,
manage it: status, services and their logs, modules, deployments, files, the network, and
Wake on LAN.

It is off by default.

## Turn it on

1. Open the dashboard → Settings → MCP and turn the server on.
2. Copy the **MCP token**. It is separate from the dashboard password, and only lets clients
   use the tools below. **Regenerate** it to lock out every client at once.
3. Note the server address. On your home network that's `http://<phone-ip>:8800/mcp`; the
   settings list the phone's addresses. From elsewhere, use Tailscale (with Serve on:
   `https://<name>.<your-tailnet>.ts.net/mcp`) or a Cloudflare Tunnel hostname that points at
   port 8800.

From a script, with the dashboard password:

```sh
curl -H "Authorization: Bearer $HOMEDROID_PASSWORD" -H "Content-Type: application/json" \
     -H "X-Homedroid-Request: 1" -d '{"enabled":true}' http://192.168.1.20:8800/api/mcp/settings
curl -H "Authorization: Bearer $HOMEDROID_PASSWORD" http://192.168.1.20:8800/api/mcp/token
```

## Client setup

Replace `192.168.1.20` with the phone's address and `hdm_…` with your token.

### Claude Code

```sh
claude mcp add --transport http homedroid http://192.168.1.20:8800/mcp \
  --header "Authorization: Bearer hdm_…"
```

### Claude Desktop

Settings → Connectors → Add custom connector works for servers reachable over HTTPS (for
example through Tailscale Serve or a Cloudflare Tunnel). For a server on your home network,
add it to `claude_desktop_config.json` through the `mcp-remote` bridge:

```json
{
  "mcpServers": {
    "homedroid": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://192.168.1.20:8800/mcp", "--allow-http",
               "--header", "Authorization: Bearer ${HOMEDROID_MCP_TOKEN}"],
      "env": { "HOMEDROID_MCP_TOKEN": "hdm_…" }
    }
  }
}
```

### Cursor

`~/.cursor/mcp.json` (or `.cursor/mcp.json` in a project):

```json
{
  "mcpServers": {
    "homedroid": {
      "url": "http://192.168.1.20:8800/mcp",
      "headers": { "Authorization": "Bearer hdm_…" }
    }
  }
}
```

### VS Code

`.vscode/mcp.json`:

```json
{
  "inputs": [
    { "type": "promptString", "id": "homedroid-token", "description": "Homedroid MCP token", "password": true }
  ],
  "servers": {
    "homedroid": {
      "type": "http",
      "url": "http://192.168.1.20:8800/mcp",
      "headers": { "Authorization": "Bearer ${input:homedroid-token}" }
    }
  }
}
```

### Older clients (HTTP + SSE)

Clients that only speak the 2024-11-05 transport connect to `http://<phone-ip>:8800/mcp/sse`
with the same `Authorization` header; the stream tells them where to post messages.

## Tools

| Tool | What it does | Needs "allow write" |
|------|--------------|:---:|
| `get_status` | Battery, temperature, memory, storage, CPU, uptime, addresses | |
| `network_info` | Network interfaces, Tailscale and Cloudflare Tunnel state | |
| `list_services` | Services and their state | |
| `get_service_logs` | Last lines of a service's log (`name`, `lines` up to 200) | |
| `list_modules` | Built-in modules and catalog apps | |
| `list_deployments` | Git deployments | |
| `list_files` | Locations, or a folder in one (`root`, `path`) | |
| `read_text_file` | Start of a text file (`root`, `path`, `maxBytes` up to 256 KB) | |
| `restart_service` | Restarts a service (`name`) | yes |
| `redeploy` | Pulls and rebuilds a deployment (`id`) | yes |
| `wake_on_lan` | Wakes a saved device (`device`: its name or id) | yes |

Tools that change things are only offered after you turn on **Allow write** in the MCP
settings. Files are limited to the file browser's locations, with the same rules: nothing
outside a location, including through `..` or symlinks. Note that this includes the SSH home
folder and, if you granted storage access, the phone's storage.

## Security

- Clients need the MCP token; five wrong tokens from one address lock it out for 15 minutes.
- Requests from web pages on other sites are refused (the `Origin` header must match), which
  also stops DNS-rebinding attacks.
- Every tool call is logged to `files/logs/mcp-audit.jsonl` (time, client address, tool,
  arguments, result). The dashboard shows it, and `GET /api/mcp/audit?limit=100` returns it.
- Plain `http://` on your home network is like the dashboard itself: fine on a network you
  trust. From outside, use Tailscale or a Cloudflare Tunnel, which add HTTPS.

## The dashboard agent

The dashboard's AI chat can use the same tools with the models you set up in AI (on the phone
or a cloud service; the model must support tool calling). Read-only tools run right away;
anything that changes something waits for you to approve or reject it in the dashboard.
