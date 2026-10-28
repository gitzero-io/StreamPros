# StreamPros (paid StreamPros Hub, Android TV, Kotlin + Compose for TV)

The paid version of the free StreamPros Hub (~/AndroidStudioProjects/HUB): the same app behind a
paywall ($5/month or $60 lifetime through the Shopify store https://streampros.shop) with a
12-hour free trial. server/ is its license server (Cloudflare Worker + D1, see server/README.md).

## Build and verify
- export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
- Compile check: ./gradlew :app:compileDebugKotlin
- Build APK: ./gradlew :app:assembleDebug (release: ./gradlew :app:assembleRelease, signed with ~/streampros-keystore.properties)
- Unit tests: ./gradlew :app:testDebugUnitTest
- Install on emulator/device: ./gradlew :app:installDebug
- Against a local license server: `npx wrangler dev` in server/, `adb reverse tcp:8787 tcp:8787`, then
  ./gradlew :app:installDebug -Pstreampros.debugLicenseUrl=http://localhost:8787
- Server tests: with `npx wrangler dev` running, `python3 server/test/e2e.py`
- Screenshot: adb exec-out screencap -p > /tmp/screen.png

## Project rules
- App ID com.streampros.app (debug builds: com.streampros.app.debug; code package com.example.hub), minSdk 23, one generic Android TV APK. Same release key as the free Hub.
- No app information before the paywall: the APK, the manifest and this repo hold no catalog, artwork or catalog package names. The catalog comes only from the license server (/v1/catalog/, license required) and is deleted from the device when the license ends. The manifest uses QUERY_ALL_PACKAGES instead of <queries> for catalog apps.
- Licensing lives in License.kt (app) and server/src (server). The server decides; the app trusts a signed license only for paid plans and only for 7 days offline. The trial is one per device and 2 per IP (IPv6: /64) every 30 days.
- UI uses androidx.tv.material3; every screen must work with D-pad only
- Every new screen sets initial focus explicitly and handles Back with BackHandler
- The app only installs, launches and closes other apps. It never hosts or streams content.
- Network use is limited to the license server (status, trial, claim, catalog), downloading catalog APKs, and the app's own update check (hub-version.json at streampros.updateUrl). No trackers or analytics.
- Catalog updates: edit the catalog in the free Hub project (its catalog_tool.py), then `python3 scripts/publish_catalog.py` here (copies, stamps, signs and deploys to the license server).
- Releases: follow PUBLISHING.md, upload dist/StreamPros.apk and dist/hub-version.json to a new release on gitzero-io/StreamPros. Never upload catalog files.
- The app list lives in data (the server catalog), never hardcoded inside composables
- Strings go in all five languages (values, -es, -fr, -de, -hi)

## Workflow
- Run the compile check after each series of edits and don't say you're done until it passes
- Make small, focused commits per milestone
- Never use raw hex colors in UI code; use HubColors in Colors.kt. Color roles: GlowRed is for focus only (border plus glow), GlowBlue for primary actions and progress, GlowGreen for success, GlowPurple for brand and discovery, Warning for failed installs. Never use red for errors.
