# Publishing StreamPros Hub

A new Hub release needs no code changes. Bump the version, build, write `hub-version.json`, and
upload two files to a new GitHub release. Installed Hubs find it on their own.

- Repo: https://github.com/gitzero-io/Streampros-Hub
- Hubs read: `https://github.com/gitzero-io/Streampros-Hub/releases/latest/download/hub-version.json`
  (`streampros.updateUrl` in `gradle.properties`)
- Download link for users and Downloader codes:
  `https://github.com/gitzero-io/Streampros-Hub/releases/latest/download/StreamProsHub.apk`

## 1. Release key (once)

Every Hub update must be signed with the same key forever. If it's lost, existing installs can
never be updated.

Create it (pick a strong password; PKCS12 uses one password for the store and the key):

```sh
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v \
  -keystore ~/streampros-release.jks -storetype PKCS12 \
  -alias streampros -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=StreamPros Hub, O=StreamPros"
```

Tell Gradle where it is, in `local.properties`. That file is git-ignored; never commit it.

```properties
streampros.keystore.file=/Users/<you>/streampros-release.jks
streampros.keystore.alias=streampros
streampros.keystore.password=<password>
streampros.key.password=<password>
```

Or use environment variables instead, for example on a build server: `STREAMPROS_KEYSTORE_FILE`,
`STREAMPROS_KEY_ALIAS`, `STREAMPROS_KEYSTORE_PASSWORD` and `STREAMPROS_KEY_PASSWORD`.

Back it up now, and keep at least two copies off this Mac:

```sh
cp ~/streampros-release.jks /Volumes/<USB stick>/
```

Store the password in a password manager next to a copy of the `.jks` file. Never upload the key or
the password to GitHub. `.gitignore` already blocks `*.jks`, `*.keystore` and `local.properties`.

Record the key's fingerprint. Every release must show this same value:

```sh
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -list -v \
  -keystore ~/streampros-release.jks -alias streampros | grep SHA256
```

## 2. Build a release

1. In `app/build.gradle.kts`, raise `versionCode` by 1 and set `versionName`, for example 2 / "1.1".
   Hubs only offer an update when the versionCode is higher than their own.
2. Build. R8 code and resource shrinking is on for release builds:

   ```sh
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   ./gradlew :app:assembleRelease
   ```

   Output: `app/build/outputs/apk/release/app-release.apk`. If it ends in `-unsigned.apk`, the
   signing settings above are missing.

## 3. Verify the signature

Check that the APK is signed by the release key, NOT the Android Debug certificate:

```sh
APKSIGNER=~/Library/Android/sdk/build-tools/37.0.0/apksigner
$APKSIGNER verify --print-certs --min-sdk-version 23 app/build/outputs/apk/release/app-release.apk
```

You want:
- `Signer #1 certificate DN: CN=StreamPros Hub, O=StreamPros`. If it says `CN=Android Debug`, stop:
  that build must not be published.
- `Signer #1 certificate SHA-256 digest:` the same fingerprint you recorded in step 1.
- No `DOES NOT VERIFY`. The `WARNING: META-INF/... not protected by signature` lines are normal.

## 4. Sign the catalog and write hub-version.json

Sign the catalog before building (step 2), so the APK ships the same catalogVersion:

```sh
python3 scripts/sign_catalog.py
```

It stamps a new `catalogVersion` into `app/src/main/assets/catalog.json` and writes
`dist/catalog.json` and `dist/catalog.json.sig`. Then:

```sh
python3 scripts/make_hub_version.py --notes "What's new in this version"
```

This reads versionCode, versionName and minSdk (aapt2), the APK's SHA-256 and its signer SHA-256
(apksigner). It refuses debug-signed or wrongly packaged APKs, and writes:

- `dist/StreamProsHub.apk`
- `dist/hub-version.json`, containing versionCode, versionName, apkUrl (the `latest/download`
  link), sha256, signerSha256, minSdk and notes

## 5. Upload

On GitHub: Releases, then Draft a new release. Use a new tag (for example `v1.1`), attach both files
from `dist/` with exactly those names, and publish. Make sure it is marked as the latest release.

Also attach `dist/catalog.json` and `dist/catalog.json.sig`.

Within 12 hours, or right away from Settings, Hub update, CHECK, installed Hubs see the new
version.

## Updating apps without a Hub release

When `catalog_tool.py` has new app versions, links or descriptions, there's no need for a new
Hub release:

1. `python3 scripts/sign_catalog.py`. This signs with the release key from `local.properties`.
2. On GitHub, edit the latest release. Delete its `catalog.json` and `catalog.json.sig` and upload the
   new pair from `dist/`. Keep both names exactly.
3. Commit `assets/catalog.json` so the next Hub release ships the same catalog.

Hubs check `streampros.catalogUrl` (the latest release's `catalog.json`) at most every 3 hours
when they open. They use the file only if:

- `catalog.json.sig` is a valid SHA-256 signature by the same key that signed the installed Hub.
  Debug builds only accept files signed with `--debug-key`, which must never be uploaded.
- Its `schemaVersion` matches, and its `catalogVersion` is higher than the catalog they have, so an
  old file can't roll apps back.

A downloaded catalog can change versions, links, hashes, signers and text for apps the Hub already
ships. It can't add apps: they need their package in the manifest's `<queries>` and their
artwork in the APK, so new apps still need a Hub release. Artwork always comes from the APK.

## What a Hub checks before installing an update

1. `hub-version.json` must have an https apkUrl and valid 64-hex `sha256` and `signerSha256`.
2. Its versionCode must be higher than the installed one, and its `minSdk` must be supported by the
   TV.
3. Its `signerSha256` must equal the installed Hub's own signing certificate.
4. After download, the APK must have package `com.streampros.hub`, the exact `sha256`, and a signer
   matching `signerSha256`.

If any check fails, the Hub shows a message and installs nothing. Android also refuses an update
signed with a different key.
