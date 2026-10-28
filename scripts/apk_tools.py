"""aapt2/apksigner helpers for make_hub_version.py (the free Hub keeps these in its catalog_tool.py)."""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SDK = Path.home() / "Library" / "Android" / "sdk"
JAVA_HOME = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"


class Problem(Exception):
    pass


def build_tool(name):
    versions = sorted((SDK / "build-tools").glob("*/" + name), key=lambda p: [int(x) for x in re.findall(r"\d+", p.parent.name)])
    if not versions:
        raise SystemExit(f"{name} not found under {SDK}/build-tools; install build-tools with sdkmanager")
    return str(versions[-1])


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def sha256_file(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def badging(aapt2, apk):
    r = run([aapt2, "dump", "badging", str(apk)])
    if r.returncode != 0 or "package: name=" not in r.stdout:
        raise Problem("aapt2 could not read the APK: " + (r.stderr.strip().splitlines() or ["?"])[-1])
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", r.stdout)
    if not pkg:
        raise Problem("no package name/versionCode in the APK")
    min_sdk = re.search(r"^minSdkVersion:'(\d+)'", r.stdout, re.M)
    return {
        "packageName": pkg.group(1),
        "versionCode": int(pkg.group(2)),
        "versionName": pkg.group(3) or None,
        "minSdk": int(min_sdk.group(1)) if min_sdk else None,
    }


def signers(apksigner, apk):
    r = run([apksigner, "verify", "--print-certs", str(apk)], env={"JAVA_HOME": JAVA_HOME, "PATH": "/usr/bin:/bin"})
    if r.returncode != 0:
        raise Problem("signature does not verify: " + (r.stderr.strip() or r.stdout.strip())[:200])
    # Lines look like "Signer #1 certificate ..." or "V2 Signer: certificate ..."; source stamps aren't signers.
    signer = r"^(?!Source Stamp)\S*\s?Signer(?: #\d+)?:? certificate"
    digests = list(dict.fromkeys(re.findall(signer + r" SHA-256 digest: ([0-9a-f]{64})", r.stdout, re.M)))
    subjects = list(dict.fromkeys(re.findall(signer + r" DN: (.*)", r.stdout, re.M)))
    if not digests:
        raise Problem("apksigner printed no signer")
    return digests, subjects


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
