-- StreamPros license server database (Cloudflare D1).
-- Create once: npx wrangler d1 execute streampros --remote --file=schema.sql

-- One row per TV. id is the app's device id (SHA-256 of ANDROID_ID + hardware, see License.kt).
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,          -- short code shown on the paywall and attached to the order
  created_at INTEGER NOT NULL,
  trial_started_at INTEGER,           -- set once; a device never gets a second trial
  trial_ip TEXT,                      -- keyed hash of the IP (IPv6: its /64) that started the trial
  claim_fails INTEGER NOT NULL DEFAULT 0,
  claim_window INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS devices_trial_ip ON devices (trial_ip, trial_started_at);

-- One row per paid StreamPros line item from Shopify (an order can license several TVs).
CREATE TABLE IF NOT EXISTS orders (
  id TEXT PRIMARY KEY,                -- Shopify line item id
  order_id TEXT NOT NULL,
  name TEXT,                          -- order number as customers see it, e.g. "#1042"
  email_hash TEXT,                    -- keyed hash of the order email, for "I already paid"
  customer_id TEXT,                   -- links monthly renewals to the device
  plan TEXT NOT NULL,                 -- 'monthly' or 'lifetime'
  paid_at INTEGER NOT NULL,
  device_id TEXT,                     -- NULL until the device code or a claim links it
  refunded INTEGER NOT NULL DEFAULT 0 -- refunded or cancelled: no longer counts
);
CREATE INDEX IF NOT EXISTS orders_device ON orders (device_id, plan);
CREATE INDEX IF NOT EXISTS orders_name ON orders (name);
CREATE INDEX IF NOT EXISTS orders_customer ON orders (customer_id, plan);
CREATE INDEX IF NOT EXISTS orders_order ON orders (order_id);

-- Apps a device downloaded during its trial: it gets FREE_INSTALLS different apps (src/lib.js).
-- Installing or updating one of them again is free.
CREATE TABLE IF NOT EXISTS installs (
  device_id TEXT NOT NULL,
  package TEXT NOT NULL,
  at INTEGER NOT NULL,
  PRIMARY KEY (device_id, package)
);
