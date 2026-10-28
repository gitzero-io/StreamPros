// StreamPros license server (Cloudflare Worker + D1).
//
// The app asks here whether its device is unlocked (paid or in the 12-hour trial) and gets a
// signed license back. Shopify tells it about purchases through webhooks. The catalog (static
// assets in ./catalog) is only served to devices holding a valid license.
//
//   POST /v1/status   {device}                 this device's state (creates the device on first call)
//   POST /v1/trial    {device}                 starts the free trial if this device and IP may
//   POST /v1/claim    {device, order, email}   links an order paid without the device code
//   GET  /v1/catalog/<file>                    catalog files, with "Authorization: Bearer <license>"
//   GET  /buy/<monthly|lifetime>/<code>        the paywall QR: redirects to the Shopify checkout
//   POST /shopify/webhook                      orders/paid, refunds/create, orders/cancelled

import {
  CLAIM_FAILS_PER_HOUR,
  HOUR,
  IP_WINDOW_MS,
  checkoutUrl,
  ipKey,
  licenseFrom,
  normalizeCode,
  randomCode,
  shopifySignatureValid,
  signLicense,
  streamProsLines,
  trialOffer,
  verifyLicense,
} from "./lib.js";

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (e) {
      console.error(e);
      return json({ error: "server" }, 500);
    }
  },
};

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  if (request.method === "POST") {
    if (path === "/v1/status") return deviceCall(request, env, (device, now) => statusBody(env, device, ipOf(request), now));
    if (path === "/v1/trial") return deviceCall(request, env, (device, now) => startTrial(env, device, ipOf(request), now));
    if (path === "/v1/claim") return deviceCall(request, env, (device, now, body) => claim(env, device, body, ipOf(request), now));
    if (path === "/shopify/webhook") return shopifyWebhook(request, env);
  }
  if (request.method === "GET") {
    if (path.startsWith("/v1/catalog/")) return catalogFile(request, env, path.slice("/v1/catalog/".length));
    const buy = path.match(/^\/buy\/(monthly|lifetime)\/([A-Za-z0-9-]{6,7})$/);
    if (buy) return buyRedirect(env, buy[1], normalizeCode(buy[2]));
  }
  return json({ error: "not_found" }, 404);
}

// ---------- App calls ----------

async function deviceCall(request, env, handler) {
  const body = await request.json().catch(() => null);
  const id = body && typeof body.device === "string" ? body.device : "";
  if (!/^[0-9a-f]{64}$/.test(id)) return json({ error: "bad_device" }, 400);
  const now = Date.now();
  const device = await getOrCreateDevice(env, id, now);
  const [result, status = 200] = [].concat(await handler(device, now, body));
  return json(result, status);
}

async function getOrCreateDevice(env, id, now) {
  const existing = await env.DB.prepare("SELECT * FROM devices WHERE id = ?").bind(id).first();
  if (existing) return existing;
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = randomCode();
    const inserted = await env.DB.prepare("INSERT OR IGNORE INTO devices (id, code, created_at) VALUES (?, ?, ?)")
      .bind(id, code, now).run();
    if (inserted.meta.changes === 1) return { id, code, created_at: now, trial_started_at: null, trial_ip: null, claim_fails: 0, claim_window: 0 };
    // Either the code was taken (try another) or another request created this device first.
    const raced = await env.DB.prepare("SELECT * FROM devices WHERE id = ?").bind(id).first();
    if (raced) return raced;
  }
  throw new Error("Couldn't find a free device code");
}

async function licenseFor(env, device, now) {
  const row = await env.DB.prepare(
    `SELECT MAX(CASE WHEN plan = 'lifetime' THEN 1 ELSE 0 END) AS lifetime,
            MAX(CASE WHEN plan = 'monthly' THEN paid_at END) AS last_monthly
       FROM orders WHERE device_id = ? AND refunded = 0`
  ).bind(device.id).first();
  return licenseFrom({ lifetime: row?.lifetime === 1, lastMonthlyPaidAt: row?.last_monthly ?? null, trialStartedAt: device.trial_started_at }, now);
}

async function recentTrialsOnIp(env, ipHash, now) {
  const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE trial_ip = ? AND trial_started_at > ?")
    .bind(ipHash, now - IP_WINDOW_MS).first();
  return row?.n ?? 0;
}

async function statusBody(env, device, ip, now) {
  const license = await licenseFor(env, device, now);
  if (license) {
    const token = await signLicense(env, { d: device.id, p: license.plan, e: license.expiresAt, i: now });
    return { status: "unlocked", code: device.code, plan: license.plan, expiresAt: license.expiresAt, token, now };
  }
  const offer = trialOffer(device.trial_started_at, await recentTrialsOnIp(env, await ipHash(env, ip), now));
  return { status: "locked", code: device.code, trial: offer, now };
}

async function startTrial(env, device, ip, now) {
  if (await licenseFor(env, device, now)) return statusBody(env, device, ip, now);
  const hash = await ipHash(env, ip);
  const offer = trialOffer(device.trial_started_at, await recentTrialsOnIp(env, hash, now));
  if (offer !== "available") return [{ ...(await statusBody(env, device, ip, now)), error: `trial_${offer === "used" ? "used" : "ip_limit"}` }, 409];
  // The WHERE clause keeps two requests at once from starting it twice.
  await env.DB.prepare("UPDATE devices SET trial_started_at = ?, trial_ip = ? WHERE id = ? AND trial_started_at IS NULL")
    .bind(now, hash, device.id).run();
  return statusBody(env, { ...device, trial_started_at: device.trial_started_at ?? now }, ip, now);
}

// "I already paid": the order number and email of an order whose device code didn't arrive.
async function claim(env, device, body, ip, now) {
  const windowStart = device.claim_window > now - HOUR ? device.claim_window : now;
  const fails = windowStart === device.claim_window ? device.claim_fails : 0;
  if (fails >= CLAIM_FAILS_PER_HOUR) return [{ ...(await statusBody(env, device, ip, now)), error: "too_many_tries" }, 429];

  const number = String(body.order ?? "").replace(/\D/g, "");
  const email = String(body.email ?? "").trim().toLowerCase();
  const lines = number && email
    ? (await env.DB.prepare("SELECT id, device_id FROM orders WHERE name = ? AND email_hash = ? AND refunded = 0")
        .bind(`#${number}`, await keyedHash(env, `email:${email}`)).all()).results
    : [];
  const unclaimed = lines.find((l) => l.device_id == null);
  const mine = lines.find((l) => l.device_id === device.id);
  if (unclaimed) {
    await env.DB.prepare("UPDATE orders SET device_id = ? WHERE id = ? AND device_id IS NULL").bind(device.id, unclaimed.id).run();
  } else if (!mine) {
    await env.DB.prepare("UPDATE devices SET claim_fails = ?, claim_window = ? WHERE id = ?").bind(fails + 1, windowStart, device.id).run();
    return [{ ...(await statusBody(env, device, ip, now)), error: lines.length ? "already_used" : "no_match" }, 404];
  }
  return statusBody(env, device, ip, now);
}

// ---------- Catalog ----------

async function catalogFile(request, env, file) {
  if (!/^[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/.test(file) || file.split("/").some((p) => p === "." || p === "..")) {
    return json({ error: "not_found" }, 404);
  }
  const auth = request.headers.get("Authorization") || "";
  const token = auth.startsWith("Bearer ") ? await verifyLicense(env, auth.slice(7)) : null;
  const now = Date.now();
  if (!token || token.e <= now) return json({ error: "unauthorized" }, 401);
  // The catalog itself also checks the database, so a refunded order stops downloads right away.
  if (file === "catalog.json") {
    const device = await env.DB.prepare("SELECT * FROM devices WHERE id = ?").bind(token.d).first();
    if (!device || !(await licenseFor(env, device, now))) return json({ error: "unauthorized" }, 403);
  }
  const response = await env.ASSETS.fetch(new Request(new URL(`/${file}`, request.url)));
  if (!response.ok) return json({ error: "not_found" }, 404);
  const headers = new Headers(response.headers);
  headers.set("Cache-Control", "private, no-store");
  return new Response(response.body, { status: 200, headers });
}

// ---------- Checkout ----------

async function buyRedirect(env, plan, code) {
  const device = await env.DB.prepare("SELECT id FROM devices WHERE code = ?").bind(code).first();
  const variant = plan === "monthly" ? env.MONTHLY_VARIANT_ID : env.LIFETIME_VARIANT_ID;
  if (!device || !variant) return new Response("This checkout link isn't valid. Scan the code on your TV again.", { status: 404 });
  return Response.redirect(checkoutUrl(env, plan, code), 302);
}

// ---------- Shopify ----------

async function shopifyWebhook(request, env) {
  const raw = await request.arrayBuffer();
  if (!(await shopifySignatureValid(env.SHOPIFY_WEBHOOK_SECRET, raw, request.headers.get("X-Shopify-Hmac-Sha256")))) {
    return json({ error: "bad_signature" }, 401);
  }
  const topic = request.headers.get("X-Shopify-Topic");
  const payload = JSON.parse(new TextDecoder().decode(raw));
  if (topic === "orders/paid") await recordPaidOrder(env, payload, Date.now());
  else if (topic === "refunds/create") await recordRefund(env, payload);
  else if (topic === "orders/cancelled") {
    await env.DB.prepare("UPDATE orders SET refunded = 1 WHERE order_id = ?").bind(String(payload.id)).run();
  }
  // Anything else is acknowledged so Shopify doesn't keep retrying it.
  return json({ ok: true });
}

async function recordPaidOrder(env, order, now) {
  const email = String(order.email || order.contact_email || order.customer?.email || "").trim().toLowerCase();
  const emailHash = email ? await keyedHash(env, `email:${email}`) : null;
  const customerId = order.customer?.id != null ? String(order.customer.id) : null;
  for (const line of streamProsLines(env, order)) {
    let deviceId = null;
    if (line.code) deviceId = (await env.DB.prepare("SELECT id FROM devices WHERE code = ?").bind(line.code).first())?.id ?? null;
    // Subscription renewals may arrive without the property: use the device of the customer's last monthly order.
    if (!deviceId && line.plan === "monthly" && customerId) {
      deviceId = (await env.DB.prepare(
        "SELECT device_id FROM orders WHERE customer_id = ? AND plan = 'monthly' AND device_id IS NOT NULL ORDER BY paid_at DESC LIMIT 1"
      ).bind(customerId).first())?.device_id ?? null;
    }
    // INSERT OR IGNORE: Shopify may deliver the same webhook more than once.
    await env.DB.prepare(
      `INSERT OR IGNORE INTO orders (id, order_id, name, email_hash, customer_id, plan, paid_at, device_id)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(line.lineId, String(order.id), order.name ?? null, emailHash, customerId, line.plan, now, deviceId).run();
  }
}

async function recordRefund(env, refund) {
  const ids = (refund.refund_line_items || []).map((r) => String(r.line_item_id));
  for (const id of ids) await env.DB.prepare("UPDATE orders SET refunded = 1 WHERE id = ?").bind(id).run();
}

// ---------- Helpers ----------

function ipOf(request) {
  return request.headers.get("CF-Connecting-IP") || "";
}

async function ipHash(env, ip) {
  return keyedHash(env, `ip:${ipKey(ip)}`);
}

async function keyedHash(env, text) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.HASH_KEY), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(text));
  return [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
}
