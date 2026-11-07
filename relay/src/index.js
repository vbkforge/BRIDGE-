// Bridge relay (Cloudflare Worker + Durable Object).
//
// Claude's custom connector  --HTTPS POST /mcp/<token>-->  this Worker
// Bridge app on the tablet   --WebSocket /device/<token>-->  this Worker
//
// The Worker is a dumb pipe: it forwards each MCP JSON-RPC request from Claude
// to the tablet over the tablet's open WebSocket and returns the tablet's answer.
// All tool logic lives in the Bridge app. <token> is a long random secret that
// the Bridge app generates; one Durable Object instance exists per token.

import { DurableObject } from "cloudflare:workers";

const TOKEN_RE = /^[A-Za-z0-9_-]{24,128}$/;
const DEVICE_TIMEOUT_MS = 90_000; // Claude allows 240 s per tool call.

const OFFLINE_TEXT =
  "Bridge on the tablet is STOPPED or OFFLINE, so Claude cannot control the tablet right now. " +
  "Do not retry automatically. Tell the user: open the Bridge app on the tablet and tap Start, " +
  "then ask you to continue.";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);

    // Authless connector: answer 404 to every OAuth discovery probe so Claude
    // does not try to start an OAuth sign-in.
    if (parts[0] === ".well-known") return text("Not found", 404);

    if (parts.length === 0) return text("Bridge relay is running.", 200);

    if ((parts[0] === "mcp" || parts[0] === "device") && parts.length === 2 && TOKEN_RE.test(parts[1])) {
      const stub = env.DEVICE.get(env.DEVICE.idFromName(parts[1]));
      return stub.fetch(request);
    }
    return text("Not found", 404);
  },
};

export class DeviceRelay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pending = new Map(); // rid -> { resolve, timer }
  }

  async fetch(request) {
    const url = new URL(request.url);
    const kind = url.pathname.split("/").filter(Boolean)[0];
    if (kind === "device") return this.acceptDevice(request);
    return this.handleMcp(request);
  }

  // ---- Tablet side -------------------------------------------------------

  acceptDevice(request) {
    if (request.headers.get("Upgrade") !== "websocket") return text("Expected WebSocket", 426);
    // Only one tablet connection at a time: a new connection replaces the old one.
    for (const old of this.ctx.getWebSockets()) {
      try { old.close(4000, "Replaced by a newer connection"); } catch (_) {}
    }
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws, message) {
    let data;
    try { data = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message)); }
    catch (_) { return; }
    if (data && data.type === "ping") { try { ws.send('{"type":"pong"}'); } catch (_) {} return; }
    const entry = data && data.rid ? this.pending.get(data.rid) : null;
    if (!entry) return;
    clearTimeout(entry.timer);
    this.pending.delete(data.rid);
    entry.resolve(data.msg);
  }

  async webSocketClose(ws, code, reason) {
    try { ws.close(code, reason); } catch (_) {}
    if (this.ctx.getWebSockets().filter((s) => s !== ws).length === 0) this.failAllPending();
  }

  async webSocketError(ws) {
    if (this.ctx.getWebSockets().filter((s) => s !== ws).length === 0) this.failAllPending();
  }

  failAllPending() {
    for (const [rid, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.resolve(null);
    }
    this.pending.clear();
  }

  // ---- Claude side (MCP Streamable HTTP, JSON responses only) ------------

  async handleMcp(request) {
    if (request.method === "GET") {
      const accept = request.headers.get("Accept") || "";
      if (accept.includes("text/event-stream")) {
        // We do not offer a server-to-client SSE stream (allowed by the MCP spec).
        return new Response(null, { status: 405, headers: { Allow: "POST" } });
      }
      // A person opening the connector URL in a browser gets a status page.
      const online = this.ctx.getWebSockets().length > 0;
      return text(
        "Bridge relay OK.\nTablet connected: " + (online ? "YES" : "NO (open Bridge and tap Start)") + "\n",
        200,
      );
    }
    if (request.method !== "POST") return new Response(null, { status: 405, headers: { Allow: "POST" } });

    let body;
    try { body = await request.json(); }
    catch (_) { return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, 400); }

    if (Array.isArray(body)) {
      const results = (await Promise.all(body.map((m) => this.forward(m)))).filter((r) => r !== undefined);
      return results.length ? json(results) : new Response(null, { status: 202 });
    }
    const result = await this.forward(body);
    return result === undefined ? new Response(null, { status: 202 }) : json(result);
  }

  // Returns the JSON-RPC response, or undefined for notifications/responses.
  async forward(msg) {
    const isRequest = msg && typeof msg === "object" && msg.method && msg.id !== undefined && msg.id !== null;
    if (!isRequest) return undefined; // notifications need no reply

    const sockets = this.ctx.getWebSockets();
    if (sockets.length === 0) return offlineReply(msg);

    const rid = crypto.randomUUID();
    const reply = await new Promise((resolve) => {
      const timer = setTimeout(() => { this.pending.delete(rid); resolve(null); }, DEVICE_TIMEOUT_MS);
      this.pending.set(rid, { resolve, timer });
      try { sockets[sockets.length - 1].send(JSON.stringify({ rid, msg })); }
      catch (_) { clearTimeout(timer); this.pending.delete(rid); resolve(null); }
    });
    return reply || offlineReply(msg);
  }
}

function offlineReply(msg) {
  if (msg.method === "tools/call") {
    return { jsonrpc: "2.0", id: msg.id, result: { isError: true, content: [{ type: "text", text: OFFLINE_TEXT }] } };
  }
  return { jsonrpc: "2.0", id: msg.id, error: { code: -32000, message: OFFLINE_TEXT } };
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });
}

function text(body, status) {
  return new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8" } });
}
