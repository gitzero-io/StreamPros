// Pure helpers for the license server (src/index.js), kept apart so tests can import them:
// a Worker's entry module may only export handlers.

export const HOUR = 60 * 60 * 1000;
export const DAY = 24 * HOUR;
export const TRIAL_MS = 12 * HOUR;
export const IP_WINDOW_MS = 30 * DAY;
export const TRIALS_PER_IP = 2;
// A monthly payment unlocks for a month plus a day of grace for the renewal to arrive.
export const MONTH_MS = 32 * DAY;
// Lifetime licenses are re-issued on each check; this is how long one lasts without checking in.
export const LIFETIME_TOKEN_MS = 30 * DAY;
export const CLAIM_FAILS_PER_HOUR = 10;
// No 0/O, 1/I/L, 2/Z, 5/S, 8/B: easy to read off a TV and type on a phone.
export const CODE_ALPHABET = "ACDEFGHJKMNPQRTUVWXY34679";
export const CODE_LENGTH = 6;
export const DEVICE_CODE_PROPERTY = "Device code";

// What this device may use right now: {plan, expiresAt} or null.
export function licenseFrom({ lifetime, lastMonthlyPaidAt, trialStartedAt }, now) {
  if (lifetime) return { plan: "lifetime", expiresAt: now + LIFETIME_TOKEN_MS };
  if (lastMonthlyPaidAt != null && lastMonthlyPaidAt + MONTH_MS > now) return { plan: "monthly", expiresAt: lastMonthlyPaidAt + MONTH_MS };
  if (trialStartedAt != null && trialStartedAt + TRIAL_MS > now) return { plan: "trial", expiresAt: trialStartedAt + TRIAL_MS };
  return null;
}

// Whether the trial can still start: once per device, and TRIALS_PER_IP per IP every 30 days.
export function trialOffer(trialStartedAt, recentTrialsOnIp) {
  if (trialStartedAt != null) return "used";
  if (recentTrialsOnIp >= TRIALS_PER_IP) return "ip_limit";
  return "available";
}

// Adds the plan to the Shopify cart with the device code as a line item property, then opens checkout.
export function checkoutUrl(env, plan, code) {
  const params = new URLSearchParams({ id: plan === "monthly" ? env.MONTHLY_VARIANT_ID : env.LIFETIME_VARIANT_ID, quantity: "1" });
  if (plan === "monthly" && env.MONTHLY_SELLING_PLAN_ID) params.set("selling_plan", env.MONTHLY_SELLING_PLAN_ID);
  params.set(`properties[${DEVICE_CODE_PROPERTY}]`, code);
  params.set("return_to", "/checkout");
  return `${env.SHOP_URL.replace(/\/$/, "")}/cart/add?${params}`;
}

export async function shopifySignatureValid(secret, raw, header) {
  if (!secret || !header) return false;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  let expected;
  try {
    expected = Uint8Array.from(atob(header), (c) => c.charCodeAt(0));
  } catch {
    return false;
  }
  return crypto.subtle.verify("HMAC", key, expected, raw);
}

// The StreamPros lines of a Shopify order: [{lineId, plan, code}]. Other products are ignored.
export function streamProsLines(env, order) {
  const orderCode = propertyValue(order.note_attributes, DEVICE_CODE_PROPERTY);
  return (order.line_items || []).flatMap((line) => {
    const variant = String(line.variant_id ?? "");
    const plan = variant && variant === String(env.MONTHLY_VARIANT_ID) ? "monthly"
      : variant && variant === String(env.LIFETIME_VARIANT_ID) ? "lifetime" : null;
    if (!plan) return [];
    const code = propertyValue(line.properties, DEVICE_CODE_PROPERTY) || orderCode;
    return [{ lineId: String(line.id), plan, code: code ? normalizeCode(code) : null }];
  });
}

export function propertyValue(list, name) {
  const hit = (list || []).find((p) => String(p.name).trim().toLowerCase() === name.toLowerCase());
  return hit ? String(hit.value).trim() : null;
}

export function normalizeCode(code) {
  return String(code).toUpperCase().replace(/[^A-Z0-9]/g, "");
}

export function randomCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
  return Array.from(bytes, (b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join("");
}

// IPv4 as is; IPv6 cut to its /64, since one home gets a whole /64 and each device may pick its own address.
export function ipKey(ip) {
  if (!ip.includes(":")) return ip;
  const [head, tail = ""] = ip.toLowerCase().split("::");
  const left = head ? head.split(":") : [];
  const right = tail ? tail.split(":") : [];
  const full = ip.includes("::") ? [...left, ...Array(8 - left.length - right.length).fill("0"), ...right] : left;
  return full.slice(0, 4).map((h) => h.replace(/^0+(?=.)/, "")).join(":") + "::/64";
}

const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const fromB64url = (s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
const fromB64 = (s) => Uint8Array.from(atob(s.replace(/\s+/g, "")), (c) => c.charCodeAt(0));

let signingKey, verifyingKey;

// A license: base64url(JSON payload) "." base64url(ECDSA P-256 signature, raw r||s). License.kt checks it.
export async function signLicense(env, payload) {
  signingKey ??= await crypto.subtle.importKey(
    "pkcs8", fromB64(env.LICENSE_PRIVATE_KEY.replace(/-----[^-]+-----/g, "")), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]
  );
  const data = new TextEncoder().encode(JSON.stringify(payload));
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, signingKey, data);
  return `${b64url(data)}.${b64url(sig)}`;
}

export async function verifyLicense(env, token) {
  const [payload, sig] = token.split(".");
  if (!payload || !sig) return null;
  verifyingKey ??= await crypto.subtle.importKey("spki", fromB64(env.LICENSE_PUBLIC_KEY), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
  try {
    const data = fromB64url(payload);
    if (!(await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, verifyingKey, fromB64url(sig), data))) return null;
    return JSON.parse(new TextDecoder().decode(data));
  } catch {
    return null;
  }
}
