# StreamPros license server

A Cloudflare Worker with a D1 database. It runs on Cloudflare's free plan.

- **Trials:** 12 hours, once per TV, and at most 2 per internet connection (IP address) every 30 days.
  IPv6 connections count by their /64 range, which is what one home gets.
- **Purchases:** Shopify sends a webhook when an order is paid, refunded or cancelled. Monthly gives
  32 days per payment (a month plus a day of grace for the renewal). Lifetime never ends unless refunded.
- **Licenses:** the app gets a license signed with `LICENSE_PRIVATE_KEY`. It checks the license
  with the public key built into the APK.
- **Catalog:** `catalog/` (catalog.json + signatures, trending.json, artwork) is only served to
  devices with a valid license. No one can download it directly.

IP addresses and emails are never stored as is, only as keyed hashes (`HASH_KEY`).

## One-time setup

You need Node.js (`brew install node`), a free Cloudflare account and admin access to the Shopify store.

### 1. Cloudflare

```sh
cd server
npm install
npx wrangler login                      # opens the browser
npx wrangler d1 create streampros       # prints a database_id
```

Paste the `database_id` into `wrangler.toml`, then create the tables:

```sh
npx wrangler d1 execute streampros --remote --file=schema.sql
```

### 2. Secrets

```sh
npx wrangler secret put LICENSE_PRIVATE_KEY < ~/streampros-license-private.pem
openssl rand -hex 32 | npx wrangler secret put HASH_KEY
```

`~/streampros-license-private.pem` signs every license. Back it up next to the release keystore
and never commit it. If it's lost, make a new pair, put the new public key in `gradle.properties`
and `wrangler.toml`, and release an app update. Licenses already issued stop working until each
device checks in again.

### 3. Shopify product

In Shopify admin:

1. **Products > Add product:** name it "StreamPros". Under Shipping, untick "This is a physical
   product". Add an option "Plan" with two variants:
   - **Monthly** at $5.00
   - **Lifetime** at $60.00
2. Install the free **Shopify Subscriptions** app. Create a plan: deliver and bill every 1 month,
   for the **Monthly** variant only.
3. Find the IDs and put them in `wrangler.toml` under `[vars]`:
   - **Variant IDs:** open each variant in the product page. The URL ends in `/variants/<ID>`.
   - **Selling plan ID:** open `https://streampros.shop/products/<product-handle>.js` and copy
     `selling_plan_groups[0].selling_plans[0].id`.

### 4. Deploy and connect Shopify

```sh
cd ..
python3 scripts/publish_catalog.py      # builds server/catalog/ and runs wrangler deploy
```

The deploy prints the worker's address, like `https://streampros-license.<you>.workers.dev`.

1. Put that address in `gradle.properties` as `streampros.licenseUrl`.
2. In Shopify admin, go to **Settings > Notifications > Webhooks** and create three webhooks. Each
   uses format JSON and the URL `https://streampros-license.<you>.workers.dev/shopify/webhook`:
   - Order payment
   - Refund creation
   - Order cancellation
3. That page shows "Your webhooks will be signed with ...". Save that key:

   ```sh
   npx wrangler secret put SHOPIFY_WEBHOOK_SECRET    # paste the key
   ```

### 5. Test a real purchase

1. Install a release build on a TV. Scan the QR code with a phone: it should open checkout with
   StreamPros in the cart and a "Device code" line under it.
2. Pay with a 100% discount code, or with Shopify's test gateway (Settings > Payments > test mode).
3. The TV should unlock within about 5 seconds.

If checkout opens without the "Device code" line, the TV won't unlock on its own. The customer can
still use **I ALREADY PAID** with the order number and email, but report it so the checkout link
(`checkoutUrl` in `src/lib.js`) can be adjusted for the store's theme.

## Updating the catalog

Edit the catalog in the free Hub project (`scripts/catalog_tool.py` there), then from this repo:

```sh
python3 scripts/publish_catalog.py
```

Unlocked devices pick it up within 3 hours (Settings > Troubleshoot checks right away).

## Customer support

Run these with `npx wrangler d1 execute streampros --remote --command "<SQL>"`:

- **Find a TV:** `SELECT * FROM devices WHERE code = 'K7P4QX'`
- **An order's TVs:** `SELECT * FROM orders WHERE name = '#1042'`
- **Move a purchase to a new TV:**
  `UPDATE orders SET device_id = (SELECT id FROM devices WHERE code = 'NEWCODE') WHERE name = '#1042'`
- **Give a TV another trial:** `UPDATE devices SET trial_started_at = NULL, trial_ip = NULL WHERE code = 'K7P4QX'`
- **Free lifetime for a TV:**
  `INSERT INTO orders (id, order_id, name, plan, paid_at, device_id) VALUES ('manual-1', 'manual-1', 'manual', 'lifetime', strftime('%s','now')*1000, (SELECT id FROM devices WHERE code = 'K7P4QX'))`

A factory reset gives the TV a new device id and code, so moving the purchase is how a reset TV
gets its license back.

## Local testing

```sh
cp .dev.vars.example .dev.vars          # fill in LICENSE_PRIVATE_KEY as described inside
npx wrangler d1 execute streampros --local --file=schema.sql
python3 ../scripts/publish_catalog.py --no-deploy
npx wrangler dev                        # http://localhost:8787
python3 test/e2e.py                     # in another terminal
```

To test the app against it, run `adb reverse tcp:8787 tcp:8787`, then
`./gradlew :app:installDebug -Pstreampros.debugLicenseUrl=http://localhost:8787`.
