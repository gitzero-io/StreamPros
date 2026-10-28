#!/usr/bin/env python3
"""End-to-end checks against a local license server.

Run `npx wrangler dev` in server/ first, with a fresh local database
(rm -rf .wrangler/state && npx wrangler d1 execute streampros --local --file=schema.sql)
and .dev.vars made from .dev.vars.example. Needs openssl and ~/streampros-license-private.pem.
"""
import json, urllib.request, urllib.error, hashlib, hmac, base64, subprocess, tempfile, os, sys
B = "http://localhost:8787"
def req(method, path, body=None, headers=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    r = urllib.request.Request(B + path, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k): return None
    opener = urllib.request.build_opener(NoRedirect)
    try:
        resp = opener.open(r); return resp.status, resp.read(), resp.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers
def j(method, path, body=None, ip="1.1.1.1"):
    st, b, _ = req(method, path, body, {"CF-Connecting-IP": ip})
    return st, json.loads(b)
def dev(n): return hashlib.sha256(n.encode()).hexdigest()
ok = True
def check(name, cond, extra=""):
    global ok
    print(("PASS " if cond else "FAIL ") + name, extra if not cond else "")
    ok &= bool(cond)

A, Bd, C, D = dev("a"), dev("b"), dev("c"), dev("d")
st, s = j("POST", "/v1/status", {"device": A}); check("new device locked, trial available", st == 200 and s["status"] == "locked" and s["trial"] == "available" and len(s["code"]) == 6, s)
codeA = s["code"]
st, s2 = j("POST", "/v1/status", {"device": A}); check("same code on second call", s2["code"] == codeA)
st, _ = j("POST", "/v1/status", {"device": "nothex"}); check("bad device id refused", st == 400)
st, _, _ = req("GET", "/v1/catalog/catalog.json"); check("catalog without license -> 401", st == 401)
st, _, _ = req("GET", "/catalog.json"); check("asset not reachable directly", st == 404)
st, s = j("POST", "/v1/trial", {"device": A}); check("trial starts", st == 200 and s["status"] == "unlocked" and s["plan"] == "trial", s)
tokA = s["token"]
check("trial lasts 12h", abs(s["expiresAt"] - s["now"] - 12*3600*1000) < 5000)
# verify token signature with openssl (raw r||s -> DER)
p64, s64 = tokA.split(".")
pad = lambda x: x + "=" * (-len(x) % 4)
payload = base64.urlsafe_b64decode(pad(p64)); sig = base64.urlsafe_b64decode(pad(s64))
def der_int(b):
    b = b.lstrip(b"\0") or b"\0"
    if b[0] & 0x80: b = b"\0" + b
    return b"\x02" + bytes([len(b)]) + b
body = der_int(sig[:32]) + der_int(sig[32:]); der = b"\x30" + bytes([len(body)]) + body
with tempfile.TemporaryDirectory() as t:
    open(f"{t}/p", "wb").write(payload); open(f"{t}/s", "wb").write(der)
    subprocess.run(f"openssl pkey -in ~/streampros-license-private.pem -pubout -out {t}/pub.pem", shell=True, check=True)
    v = subprocess.run(["openssl", "dgst", "-sha256", "-verify", f"{t}/pub.pem", "-signature", f"{t}/s", f"{t}/p"], capture_output=True)
check("license signature verifies with openssl", v.returncode == 0, v.stdout + v.stderr)
check("payload fields", json.loads(payload)["d"] == A and json.loads(payload)["p"] == "trial")
auth = {"Authorization": "Bearer " + tokA}
st, cat, h = req("GET", "/v1/catalog/catalog.json", headers=auth); check("catalog with license -> 200", st == 200 and b'"apps"' in cat)
check("catalog not cached", h.get("Cache-Control") == "private, no-store")
st, sg, _ = req("GET", "/v1/catalog/catalog.json.sig", headers=auth); check("sig served", st == 200 and len(sg) > 100)
art = json.loads(cat)["apps"][0]["artwork"]
st, img, _ = req("GET", "/v1/catalog/" + art, headers=auth); check("artwork served", st == 200 and img[:4] == b"\x89PNG", art)
st, _, _ = req("GET", "/v1/catalog/../wrangler.toml", headers=auth); check("traversal refused", st == 404)
st, _, _ = req("GET", "/v1/catalog/catalog.json", headers={"Authorization": "Bearer " + p64 + "." + s64[:-4] + "AAAA"}); check("forged license -> 401", st == 401)
st, s = j("POST", "/v1/trial", {"device": A}); check("second trial same device: still unlocked (in trial)", s["status"] == "unlocked")
# IP limit: A used one trial on 1.1.1.1; B gets the second; C is refused
st, s = j("POST", "/v1/trial", {"device": Bd}); check("2nd trial on same IP ok", s["status"] == "unlocked", s)
st, s = j("POST", "/v1/status", {"device": C}); check("3rd device on same IP: ip_limit", s["trial"] == "ip_limit", s)
codeC = s["code"]
st, s = j("POST", "/v1/trial", {"device": C}); check("3rd trial refused 409", st == 409 and s["error"] == "trial_ip_limit", s)
st, s = j("POST", "/v1/status", {"device": C}, ip="2.2.2.2"); check("other IP: trial available", s["trial"] == "available", s)
st, s = j("POST", "/v1/status", {"device": dev("v6a")}, ip="2001:db8:1:2::5"); 
st, s = j("POST", "/v1/trial", {"device": dev("v6a")}, ip="2001:db8:1:2::5")
st, s = j("POST", "/v1/trial", {"device": dev("v6b")}, ip="2001:0db8:0001:0002:aaaa::9")
st, s = j("POST", "/v1/status", {"device": dev("v6c")}, ip="2001:db8:1:2:ffff::1"); check("IPv6 /64 shares the limit", s["trial"] == "ip_limit", s)
# buy redirect
st, _, h = req("GET", f"/buy/monthly/{codeC}"); loc = h.get("Location", "")
check("buy redirect", st == 302 and loc.startswith("https://streampros.shop/cart/add?") and "selling_plan=333" in loc and "id=111" in loc and codeC in loc, loc)
st, _, h = req("GET", f"/buy/lifetime/{codeC[:3]}-{codeC[3:]}"); check("buy with dashed code", st == 302 and "id=222" in h.get("Location", "") and "selling_plan" not in h.get("Location", ""))
st, _, _ = req("GET", "/buy/lifetime/ZZZZZZ"); check("unknown code 404", st == 404)
# webhooks
def hook(topic, payload, secret=b"local-test-webhook"):
    raw = json.dumps(payload).encode()
    mac = base64.b64encode(hmac.new(secret, raw, hashlib.sha256).digest()).decode()
    st, b, _ = req("POST", "/shopify/webhook", raw=raw, headers={"X-Shopify-Topic": topic, "X-Shopify-Hmac-Sha256": mac})
    return st
order = {"id": 9001, "name": "#1001", "email": "Buyer@Example.com", "customer": {"id": 77},
         "line_items": [{"id": 5001, "variant_id": 111, "properties": [{"name": "Device code", "value": f"{codeC[:3]}-{codeC[3:].lower()}"}]},
                        {"id": 5002, "variant_id": 999, "properties": []}]}
check("bad hmac refused", hook("orders/paid", order, b"wrong") == 401)
check("orders/paid accepted", hook("orders/paid", order) == 200)
check("duplicate webhook ok", hook("orders/paid", order) == 200)
st, s = j("POST", "/v1/status", {"device": C}); check("C unlocked monthly", s["status"] == "unlocked" and s["plan"] == "monthly", s)
# renewal without code links by customer
renew = {"id": 9002, "name": "#1002", "email": "buyer@example.com", "customer": {"id": 77}, "line_items": [{"id": 5003, "variant_id": 111, "properties": []}]}
hook("orders/paid", renew)
st, s = j("POST", "/v1/status", {"device": C}); check("renewal extends", s["plan"] == "monthly", s)
# lifetime order without code -> claim
life = {"id": 9003, "name": "#1003", "email": "other@example.com", "line_items": [{"id": 5004, "variant_id": 222}]}
hook("orders/paid", life)
st, s = j("POST", "/v1/status", {"device": D}, ip="3.3.3.3"); check("D locked before claim", s["status"] == "locked")
st, s = j("POST", "/v1/claim", {"device": D, "order": "1003", "email": "nope@example.com"}, ip="3.3.3.3"); check("wrong email refused", st == 404 and s["error"] == "no_match", s)
st, s = j("POST", "/v1/claim", {"device": D, "order": "#1003", "email": " Other@Example.com "}, ip="3.3.3.3"); check("claim unlocks lifetime", st == 200 and s["plan"] == "lifetime", s)
tokD = s["token"]
st, s = j("POST", "/v1/claim", {"device": dev("e"), "order": "1003", "email": "other@example.com"}, ip="3.3.3.3"); check("claimed order can't be reused", st == 404 and s["error"] == "already_used", s)
# refund
check("refund accepted", hook("refunds/create", {"order_id": 9003, "refund_line_items": [{"line_item_id": 5004}]}) == 200)
st, s = j("POST", "/v1/status", {"device": D}, ip="3.3.3.3"); check("refunded lifetime locks", s["status"] == "locked", s)
st, _, _ = req("GET", "/v1/catalog/catalog.json", headers={"Authorization": "Bearer " + tokD}); check("refunded device catalog -> 403", st == 403)
hook("orders/cancelled", {"id": 9001})
hook("orders/cancelled", {"id": 9002})
st, s = j("POST", "/v1/status", {"device": C}, ip="2.2.2.2"); check("cancelled monthly locks; trial still available on new IP", s["status"] == "locked" and s["trial"] == "available", s)
# claim rate limit
for _ in range(10): j("POST", "/v1/claim", {"device": dev("f"), "order": "1", "email": "x@y.z"}, ip="4.4.4.4")
st, s = j("POST", "/v1/claim", {"device": dev("f"), "order": "1003", "email": "other@example.com"}, ip="4.4.4.4"); check("claim rate limited", st == 429, s)
print("ALL PASS" if ok else "SOME FAILED")
