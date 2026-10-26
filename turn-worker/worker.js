/**
 * amend TURN credentials service — a Cloudflare Worker.
 *
 * The amend app asks GET /ice before each call and gets short-lived TURN
 * credentials (6 hours) for Cloudflare's global TURN network. The long-lived
 * TURN API token stays here, as a Worker secret, never inside the app.
 *
 * Secrets (Settings → Variables and Secrets):
 *   TURN_KEY_ID          – from Realtime → TURN Server → your key
 *   TURN_KEY_API_TOKEN   – the API token shown when the key was created
 *   APP_KEY   (optional) – if set, requests must send header X-Amend-Key with this value
 *
 * The TURN server only relays already-encrypted (DTLS-SRTP) call traffic.
 */
const TTL_SECONDS = 6 * 3600;
const PER_IP_PER_MINUTE = 20;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return json({ ok: true, service: "amend-turn" });
    }
    if (url.pathname !== "/ice") return json({ error: "not found" }, 404);
    if (request.method !== "GET" && request.method !== "POST") return json({ error: "method" }, 405);
    if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return json({ error: "TURN secrets are not set" }, 500);
    if (env.APP_KEY && request.headers.get("X-Amend-Key") !== env.APP_KEY) return json({ error: "forbidden" }, 403);

    // A light rate limit per IP address (per Cloudflare location), so nobody burns the free quota.
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const bucket = new Request(`https://rate.amend.invalid/${encodeURIComponent(ip)}/${Math.floor(Date.now() / 60000)}`);
    const cache = caches.default;
    const hit = await cache.match(bucket);
    const count = hit ? parseInt(await hit.text(), 10) || 0 : 0;
    if (count >= PER_IP_PER_MINUTE) return json({ error: "too many requests" }, 429);
    ctx.waitUntil(cache.put(bucket, new Response(String(count + 1), { headers: { "Cache-Control": "max-age=60" } })));

    const r = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`, "Content-Type": "application/json" },
        body: JSON.stringify({ ttl: TTL_SECONDS }),
      },
    );
    if (!r.ok) return json({ error: `cloudflare turn returned ${r.status}` }, 502);
    const data = await r.json();
    return json({ iceServers: data.iceServers || [], ttl: TTL_SECONDS }, 200, { "Cache-Control": "no-store" });
  },
};

function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}
