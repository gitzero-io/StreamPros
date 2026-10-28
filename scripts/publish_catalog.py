#!/usr/bin/env python3
"""Publish the members-only catalog to the license server.

  python3 scripts/publish_catalog.py            # build server/catalog/ and deploy the worker
  python3 scripts/publish_catalog.py --no-deploy

StreamPros uses the free StreamPros Hub's catalog (catalog.json, trending.json, artwork), which is
kept up to date there with scripts/catalog_tool.py. This copies it from the Hub project
(--hub, default ../HUB) into server/catalog/, stamps a new catalogVersion (Unix time; the app only
takes a catalog newer than the one it has), and signs it twice:

  catalog.json.sig        release key (local.properties or env, see PUBLISHING.md)
  catalog.json.debug.sig  ~/.android/debug.keystore, so debug builds can test against the same server

Trial devices get trial/catalog.json instead (signed the same way): the same catalog without
download links (apkUrl, downloaderCode, etag). The links go to server/catalog/private/links.json,
which the worker never serves; it hands them out one app at a time through /v1/install (2 apps
during the trial).

server/catalog/ is git-ignored and only ever reaches devices through the worker, which checks
their license first. Nothing about the apps goes into this repo or the APK.
Needs openssl (OpenSSL 3) and, for deploying, Node.js (npx wrangler).
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "server"
OUT = SERVER / "catalog"
DEBUG_KEYSTORE = Path.home() / ".android" / "debug.keystore"


def local_properties():
    props = {}
    path = ROOT / "local.properties"
    if path.is_file():
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                props[key.strip()] = value.strip()
    return props


def release_keystore():
    """(keystore path, store password) from local.properties or env, the same settings Gradle uses."""
    props = local_properties()

    def secret(key, env):
        return props.get(key) or os.environ.get(env)

    path = secret("streampros.keystore.file", "STREAMPROS_KEYSTORE_FILE")
    password = secret("streampros.keystore.password", "STREAMPROS_KEYSTORE_PASSWORD")
    if not path or not password:
        raise SystemExit("Release signing settings not found: set streampros.keystore.file and "
                         "streampros.keystore.password in local.properties (PUBLISHING.md)")
    return Path(path).expanduser(), password


def openssl(*args, password=""):
    env = dict(os.environ, SIGN_CATALOG_PASS=password)
    result = subprocess.run(["openssl", *args], env=env, capture_output=True)
    if result.returncode != 0:
        raise SystemExit(f"openssl {args[0]} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout


def sign(data_path, sig_path, keystore, password):
    """SHA-256 signature of data_path with the keystore's key, checked against its certificate."""
    with tempfile.TemporaryDirectory() as tmp:
        key = Path(tmp) / "key.pem"
        cert = Path(tmp) / "cert.pem"
        pub = Path(tmp) / "pub.pem"
        key.touch(mode=0o600)
        key.write_bytes(openssl("pkcs12", "-in", str(keystore), "-nocerts", "-nodes",
                                "-passin", "env:SIGN_CATALOG_PASS", password=password))
        cert.write_bytes(openssl("pkcs12", "-in", str(keystore), "-nokeys", "-clcerts",
                                 "-passin", "env:SIGN_CATALOG_PASS", password=password))
        openssl("dgst", "-sha256", "-sign", str(key), "-out", str(sig_path), str(data_path))
        pub.write_bytes(openssl("x509", "-in", str(cert), "-pubkey", "-noout"))
        openssl("dgst", "-sha256", "-verify", str(pub), "-signature", str(sig_path), str(data_path))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--hub", default=str(ROOT.parent / "HUB"), help="free Hub project folder (default: %(default)s)")
    p.add_argument("--no-deploy", action="store_true", help="only build server/catalog/")
    args = p.parse_args()

    if shutil.which("openssl") is None:
        raise SystemExit("openssl not found")
    assets = Path(args.hub) / "app" / "src" / "main" / "assets"
    catalog_src, trending_src, artwork_src = assets / "catalog.json", assets / "trending.json", assets / "artwork"
    if not catalog_src.is_file():
        raise SystemExit(f"{catalog_src} not found; pass --hub <free Hub project folder>")

    # The Hub's own checks (schema, trending entries) before anything is published.
    validator = Path(args.hub) / "scripts" / "validate_catalog.py"
    if validator.is_file():
        check = subprocess.run([sys.executable, str(validator), str(catalog_src), str(trending_src)])
        if check.returncode != 0:
            raise SystemExit("validate_catalog.py failed; fix the catalog in the Hub project first")

    catalog = json.loads(catalog_src.read_text(encoding="utf-8"))
    previous = OUT / "catalog.json"
    old_version = 0
    if previous.is_file():
        old_version = int(json.loads(previous.read_text(encoding="utf-8")).get("catalogVersion") or 0)
    catalog["catalogVersion"] = max(int(time.time()), int(catalog.get("catalogVersion") or 0) + 1, old_version + 1)
    # The Hub's move switch is for free Hubs only.
    catalog.pop("moveTo", None)
    links = {a["packageName"]: a["apkUrl"] for a in catalog.get("apps", []) if a.get("apkUrl")}
    trial = json.loads(json.dumps(catalog))
    for app in trial.get("apps", []):
        for field in ("apkUrl", "downloaderCode", "etag"):
            app.pop(field, None)

    # Rebuilt from scratch so apps removed from the Hub catalog don't leave artwork behind.
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    signed = OUT / "catalog.json"
    signed.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if trending_src.is_file():
        shutil.copy2(trending_src, OUT / "trending.json")
    if artwork_src.is_dir():
        shutil.copytree(artwork_src, OUT / "artwork")
    (OUT / "private").mkdir()
    (OUT / "private" / "links.json").write_text(json.dumps(links, indent=2) + "\n", encoding="utf-8")
    (OUT / "trial").mkdir()
    trial_signed = OUT / "trial" / "catalog.json"
    trial_signed.write_text(json.dumps(trial, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    for data in (signed, trial_signed):
        sign(data, data.with_name("catalog.json.sig"), *release_keystore())
        if DEBUG_KEYSTORE.is_file():
            sign(data, data.with_name("catalog.json.debug.sig"), DEBUG_KEYSTORE, "android")
    if not DEBUG_KEYSTORE.is_file():
        print(f"{DEBUG_KEYSTORE} not found: debug builds won't accept this catalog")

    print(f"catalogVersion {catalog['catalogVersion']}, {len(catalog.get('apps', []))} apps, {len(links)} links -> {OUT}")
    if args.no_deploy:
        print("Not deployed. Run `npx wrangler deploy` in server/ to publish it.")
        return
    result = subprocess.run(["npx", "wrangler", "deploy"], cwd=SERVER)
    if result.returncode != 0:
        raise SystemExit("wrangler deploy failed")
    print("Deployed. Unlocked devices pick it up within 3 hours (Troubleshoot checks right away).")


if __name__ == "__main__":
    main()
