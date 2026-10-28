#!/usr/bin/env python3
"""Write hub-version.json for a signed StreamPros release.

  ./gradlew :app:assembleRelease
  python3 scripts/make_hub_version.py --notes "What's new in this version"

Reads versionCode, versionName and minSdk from the release APK (aapt2), its SHA-256, and its
signing certificate SHA-256 (apksigner). Writes dist/hub-version.json plus dist/StreamPros.apk,
the two files to upload to a new GitHub release. Installed Hubs fetch
  <repo>/releases/latest/download/hub-version.json
and only install the update if the APK's package, SHA-256 and signer all match (see PUBLISHING.md).
"""
import argparse
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import catalog_tool as tools  # noqa: E402  (shared aapt2/apksigner helpers)

ROOT = tools.ROOT
DEFAULT_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
DEFAULT_REPO = "https://github.com/gitzero-io/Streampros-Hub"
APK_NAME = "StreamPros.apk"
PACKAGE = "com.streampros.app"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--notes", required=True, help="one line shown to users with the update")
    p.add_argument("--repo", default=DEFAULT_REPO, help="GitHub repo URL (default: %(default)s)")
    p.add_argument("--apk", default=str(DEFAULT_APK), help="signed release APK (default: %(default)s)")
    p.add_argument("--out", default=str(ROOT / "dist"), help="output folder (default: %(default)s)")
    p.add_argument("--allow-debug-key", action="store_true", help="allow the Android Debug certificate (testing only)")
    args = p.parse_args()

    repo = args.repo.rstrip("/")
    if not repo.startswith("https://"):
        raise SystemExit("--repo must be an https URL")
    apk = Path(args.apk)
    if not apk.is_file():
        raise SystemExit(f"{apk} not found; build it with ./gradlew :app:assembleRelease")

    aapt2, apksigner = tools.build_tool("aapt2"), tools.build_tool("apksigner")
    try:
        info = tools.badging(aapt2, apk)
        digests, subjects = tools.signers(apksigner, apk)
    except tools.Problem as e:
        raise SystemExit(f"Not a valid signed APK: {e}")
    if info["packageName"] != PACKAGE:
        raise SystemExit(f"APK package is {info['packageName']}, expected {PACKAGE} (is this a debug build?)")
    if any("Android Debug" in s for s in subjects) and not args.allow_debug_key:
        raise SystemExit("This APK is signed with the Android Debug certificate. Sign it with the release key "
                         "(PUBLISHING.md); every update must use that same key.")
    if len(digests) != 1:
        raise SystemExit(f"Expected exactly one signer, found {len(digests)}")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, out / APK_NAME)
    version = {
        "versionCode": info["versionCode"],
        "versionName": info["versionName"],
        "apkUrl": f"{repo}/releases/latest/download/{APK_NAME}",
        "sha256": tools.sha256_file(out / APK_NAME),
        "signerSha256": digests[0],
        "minSdk": info["minSdk"],
        "notes": args.notes,
    }
    tools.write_json(out / "hub-version.json", version)

    print(f"{info['packageName']} {info['versionName']} ({info['versionCode']}), minSdk {info['minSdk']}")
    print(f"signer {digests[0]} ({subjects[0]})")
    print(f"Wrote {out / APK_NAME} and {out / 'hub-version.json'}")
    # The release should carry the same signed catalog the APK ships, or Hubs keep the bundled one.
    signed = out / "catalog.json"
    bundled_version = tools.load_json(tools.CATALOG, {}).get("catalogVersion")
    if not (signed.is_file() and (out / "catalog.json.sig").is_file()) or \
            tools.load_json(signed, {}).get("catalogVersion") != bundled_version:
        print("WARNING: dist/catalog.json(.sig) is missing or doesn't match the bundled catalog. Run "
              "python3 scripts/sign_catalog.py, rebuild, and run this again.")
    else:
        print(f"dist/catalog.json + .sig match the bundled catalog (catalogVersion {bundled_version}); upload them too.")
    print(f"Upload both to a new GitHub release (tag v{info['versionName']}) in {repo}; don't reuse an old tag.")


if __name__ == "__main__":
    main()
