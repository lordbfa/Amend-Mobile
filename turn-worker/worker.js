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
 *   APP_KEY              – requests must send header X-Amend-Key with this value. It ships
 *                          inside the app, so it is not a real secret: it only keeps casual
 *                          scrapers away. Without it the service answers nobody.
 *
 * Abuse limits: per IP address and for the whole service, per minute. They are counted in
 * each Cloudflare location's cache, so they are approximate. For an exact, shared limit add
 * the optional RATE_LIMITER binding (see wrangler.toml); and set a usage alert for the TURN
 * key in the Cloudflare dashboard, which is the real backstop for the free quota.
 *
 * The TURN server only relays already-encrypted (DTLS-SRTP) call traffic.
 */
const TTL_SECONDS = 6 * 3600;
const PER_IP_PER_MINUTE = 20;
/** Everyone together, per Cloudflare location: far above real use, far below a flood. */
const ALL_PER_MINUTE = 600;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/health") {
      return json({ ok: true, service: "amend-turn" });
    }
    if (url.pathname !== "/ice") return json({ error: "not found" }, 404);
    if (request.method !== "GET" && request.method !== "POST") return json({ error: "method" }, 405);
    if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return json({ error: "TURN secrets are not set" }, 500);
    // closed until it is configured: an open /ice hands TURN credentials to anyone who finds it
    if (!env.APP_KEY) return json({ error: "APP_KEY is not set" }, 500);

    // Count every request, wrong keys too, so guessing the key is limited like everything else.
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const minute = Math.floor(Date.now() / 60000);
    if (env.RATE_LIMITER) {
      const { success } = await env.RATE_LIMITER.limit({ key: ip });
      if (!success) return json({ error: "too many requests" }, 429, { "Retry-After": "60" });
    }
    if (!(await under(ctx, `ip/${encodeURIComponent(ip)}/${minute}`, PER_IP_PER_MINUTE)) ||
        !(await under(ctx, `all/${minute}`, ALL_PER_MINUTE))) {
      return json({ error: "too many requests" }, 429, { "Retry-After": "60" });
    }

    if (!(await sameText(request.headers.get("X-Amend-Key") || "", env.APP_KEY))) return json({ error: "forbidden" }, 403);

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

/** Counts one more hit in [name]'s bucket; false once the bucket already holds [limit]. */
async function under(ctx, name, limit) {
  const bucket = new Request(`https://rate.amend.invalid/${name}`);
  const cache = caches.default;
  const hit = await cache.match(bucket);
  const count = hit ? parseInt(await hit.text(), 10) || 0 : 0;
  if (count >= limit) return false;
  ctx.waitUntil(cache.put(bucket, new Response(String(count + 1), { headers: { "Cache-Control": "max-age=60" } })));
  return true;
}

/** Compares two strings without giving away, through timing, how much of them matched. */
async function sameText(a, b) {
  const enc = new TextEncoder();
  const [x, y] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  const p = new Uint8Array(x);
  const q = new Uint8Array(y);
  let diff = 0;
  for (let i = 0; i < p.length; i++) diff |= p[i] ^ q[i];
  return diff === 0;
}

function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", ...headers },
  });
}
