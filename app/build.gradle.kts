import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing secrets: from local.properties (never committed) or environment variables.
// See PUBLISHING.md. Without them the release build is left unsigned.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun secret(key: String, env: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile = secret("streampros.keystore.file", "STREAMPROS_KEYSTORE_FILE")
val releaseKeyAlias = secret("streampros.keystore.alias", "STREAMPROS_KEY_ALIAS")
val releaseStorePassword = secret("streampros.keystore.password", "STREAMPROS_KEYSTORE_PASSWORD")
val releaseKeyPassword = secret("streampros.key.password", "STREAMPROS_KEY_PASSWORD") ?: releaseStorePassword

android {
    namespace = "com.example.hub"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Public app ID; permanent once people install it. (The code package stays com.example.hub.)
        // Separate from the free StreamPros Hub (com.streampros.hub), so both can be installed.
        applicationId = "com.streampros.app"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the Hub looks for hub-version.json (see scripts/make_hub_version.py). Set
        // streampros.updateUrl in gradle.properties; empty turns update checks off.
        val updateUrl = (findProperty("streampros.updateUrl") as String?).orEmpty()
        require(updateUrl.isEmpty() || updateUrl.startsWith("https://")) { "streampros.updateUrl must be https" }
        buildConfigField("String", "HUB_UPDATE_URL", "\"$updateUrl\"")

        // License server (server/ in this repo, a Cloudflare Worker): trials, Shopify purchases and
        // the members-only catalog. Set streampros.licenseUrl in gradle.properties.
        val licenseUrl = (findProperty("streampros.licenseUrl") as String?).orEmpty().trimEnd('/')
        require(licenseUrl.startsWith("https://")) { "streampros.licenseUrl must be set to the https link of the license server" }
        buildConfigField("String", "LICENSE_URL", "\"$licenseUrl\"")

        // Public half of the key the license server signs licenses with (base64 X.509 SubjectPublicKeyInfo,
        // EC P-256). The private half is the server's LICENSE_PRIVATE_KEY secret.
        val licenseKey = (findProperty("streampros.licenseKey") as String?).orEmpty()
        require(licenseKey.isNotBlank()) { "streampros.licenseKey must be set (see server/README.md)" }
        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licenseKey\"")
    }

    signingConfigs {
        if (releaseStoreFile != null && releaseKeyAlias != null && releaseStorePassword != null) create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            // R8 code shrinking plus resource shrinking. App-specific rules: proguard-rules.pro.
            optimization {
                enable = true
            }
            proguardFiles("proguard-rules.pro")
            // Without signing secrets the release APK is left unsigned (make_hub_version.py rejects it).
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // Lets a test build sit next to the released app on the same TV.
            applicationIdSuffix = ".debug"
            // Testing against `npx wrangler dev` (server/README.md): pass
            // -Pstreampros.debugLicenseUrl=http://localhost:8787 and run `adb reverse tcp:8787 tcp:8787`.
            // Debug builds only; cleartext is allowed for localhost by src/debug's network config.
            (findProperty("streampros.debugLicenseUrl") as String?)?.takeIf { it.isNotBlank() }?.let {
                buildConfigField("String", "LICENSE_URL", "\"${it.trimEnd('/')}\"")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.tv:tv-material:1.0.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.google.zxing:core:3.5.3") // QR codes on the paywall
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // real org.json for JVM tests; Android provides it at runtime
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
