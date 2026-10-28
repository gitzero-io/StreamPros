# StreamPros Hub (Android TV, Kotlin + Compose for TV)

## Build and verify
- export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
- Compile check: ./gradlew :app:compileDebugKotlin
- Build APK: ./gradlew :app:assembleDebug (release: ./gradlew :app:assembleRelease, signed with ~/streampros-keystore.properties)
- Unit tests: ./gradlew :app:testDebugUnitTest
- Install on emulator/device: ./gradlew :app:installDebug
- Screenshot: adb exec-out screencap -p > /tmp/screen.png

## Project rules
- App ID com.streampros.hub (debug builds: com.streampros.hub.debug; code package com.example.hub), minSdk 23, one generic Android TV APK (not Fire-TV-specific)
- UI uses androidx.tv.material3; every screen must work with D-pad only
- Every new screen sets initial focus explicitly and handles Back with BackHandler
- The app only installs, launches and closes other apps (Close running apps: killBackgroundProcesses before Android 14, else the system App info page). It never hosts or streams content.
- Network use is limited to downloading catalog APKs, the Hub's own update check (hub-version.json at the gradle property streampros.updateUrl) and the signed catalog (catalog.json + catalog.json.sig at streampros.catalogUrl). No trackers or analytics.
- Catalog updates between Hub releases: `python3 scripts/sign_catalog.py`, then replace catalog.json and catalog.json.sig on the latest GitHub release. A downloaded catalog only updates apps the bundled catalog has; new apps need a Hub release.
- Hub releases: follow PUBLISHING.md (release signing from local.properties or env vars, R8 on, `python3 scripts/make_hub_version.py --notes ...`, upload dist/ files to a new GitHub release)
- The app list lives in data (JSON or sampleApps), never hardcoded inside composables
- Add or refresh catalog apps with `python3 scripts/catalog_tool.py add "<Name>"` (rows come from apps.txt); it fills catalog.json, artwork, trending.json and the manifest <queries> block. Never hand-edit packageName, hashes or signers. Signer fingerprints are saved only with the user's approval.
- `python3 scripts/catalog_tool.py check` reports broken links and new files behind "latest" links
- scripts/validate_catalog.py runs before every Gradle build; a catalog package missing from <queries> fails the build (isInstalled can't see it on Android 11+)

## Workflow
- Run the compile check after each series of edits and don't say you're done until it passes
- Make small, focused commits per milestone
- Never use raw hex colors in UI code; use HubColors in Colors.kt. Color roles: GlowRed is for focus only (border plus glow), GlowBlue for primary actions and progress, GlowGreen for success, GlowPurple for brand and discovery, Warning for failed installs. Never use red for errors.
