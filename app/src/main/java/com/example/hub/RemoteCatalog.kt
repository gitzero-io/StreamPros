package com.example.hub

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory

// Whether `signature` (from scripts/publish_catalog.py: SHA-256 with the app's release key) is valid
// for `data` under any of the keys. RSA and EC keys are both accepted.
fun catalogSignatureValid(data: ByteArray, signature: ByteArray, keys: List<PublicKey>): Boolean = keys.any { key ->
    runCatching {
        val algorithm = if (key.algorithm == "EC") "SHA256withECDSA" else "SHA256with${key.algorithm}"
        Signature.getInstance(algorithm).run { initVerify(key); update(data); verify(signature) }
    }.getOrDefault(false)
}

// Whether a verified downloaded catalog should replace the one in use: same schema, and a higher
// catalogVersion, so an old signed file served again can't roll apps back. With no catalog yet
// (current == null), any signed catalog is used.
fun shouldUseRemoteCatalog(remote: CatalogMeta, current: CatalogMeta?): Boolean =
    current == null || (remote.schemaVersion == current.schemaVersion && remote.catalogVersion > current.catalogVersion)

// Artwork and banner paths from the catalog are used as file names under the catalog folder, so
// only plain relative paths are accepted (no "..", no leading slash).
fun safeArtworkPath(path: String): Boolean =
    path.matches(Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")) && path.split('/').none { it == "." || it == ".." }

// Downloads the members-only catalog from the license server (BuildConfig.LICENSE_URL/v1/catalog/):
// catalog.json + its signature, trending.json and the artwork. Every request carries the device's
// license, so nothing about the apps reaches a device before it is unlocked. The catalog must be
// signed by the same key as this installed app; anything else is ignored and the current one stays.
object CatalogUpdater {
    private const val PREFS = "catalog_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 10_000
    private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
    private const val MAX_SIG_BYTES = 16 * 1024
    private const val MAX_TRENDING_BYTES = 256 * 1024
    private const val MAX_ARTWORK_BYTES = 4 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var checking = false

    // On launch, resume and unlock: checks at most every 3 hours (right away when there is no
    // catalog yet), quietly. Offline or a bad file keeps the current catalog.
    fun checkIfDue(context: Context) {
        if (checking || License.token(context) == null) return
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (catalogFile(ctx).isFile && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY_MS) return
        checking = true
        scope.launch {
            try {
                val changed = withContext(Dispatchers.IO) { fetchAndSave(ctx) }
                prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
                if (changed) reloadAll(ctx)
            } catch (e: Exception) {
                // Try again on the next launch or resume.
            } finally {
                checking = false
            }
        }
    }

    // Troubleshoot: checks right away, ignoring the 3-hour wait. True when a newer catalog was
    // saved, false when there was nothing newer to use. Throws when offline, locked or the server fails.
    suspend fun refreshNow(context: Context): Boolean {
        val ctx = context.applicationContext
        val before = saved(ctx)?.second?.catalogVersion
        val changed = withContext(Dispatchers.IO) { fetchAndSave(ctx) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
        if (changed) reloadAll(ctx)
        return saved(ctx)?.second?.catalogVersion != before
    }

    // The license ended: removes the catalog, trending list and artwork so nothing is left to browse.
    fun clear(context: Context) {
        val ctx = context.applicationContext
        if (dir(ctx).list().isNullOrEmpty()) return
        dir(ctx).deleteRecursively()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        reloadAll(ctx)
    }

    // The saved downloaded catalog and its header, if it is still validly signed for this app.
    internal fun saved(context: Context): Pair<String, CatalogMeta>? = runCatching {
        val data = catalogFile(context).readBytes()
        val sig = sigFile(context).readBytes()
        if (!catalogSignatureValid(data, sig, ownSigningKeys(context))) return null
        val json = String(data, Charsets.UTF_8)
        json to parseCatalogMeta(json)
    }.getOrNull()

    internal fun trendingFile(context: Context) = File(dir(context), "trending.json")

    // The downloaded artwork or banner for a catalog path such as "artwork/app.png", if it's there.
    internal fun artworkFile(context: Context, path: String): File? =
        if (!safeArtworkPath(path)) null else File(dir(context), path).takeIf { it.isFile }

    private fun reloadAll(context: Context) {
        Trending.reset()
        Catalog.reload(context)
    }

    // True when anything on screen changed: a newer catalog, the trending list or new artwork. A
    // catalog that fails the signature check throws, so Troubleshoot doesn't report it as up to date.
    private fun fetchAndSave(context: Context): Boolean {
        val token = License.token(context) ?: throw IOException("Not unlocked")
        val data = get(file("catalog.json"), token, MAX_CATALOG_BYTES)
        // Debug builds are signed with the debug key, so they check the debug signature.
        val sig = get(file(if (BuildConfig.DEBUG) "catalog.json.debug.sig" else "catalog.json.sig"), token, MAX_SIG_BYTES)
        if (!catalogSignatureValid(data, sig, ownSigningKeys(context))) throw IOException("Catalog signature isn't valid")
        val remote = parseCatalogMeta(String(data, Charsets.UTF_8))
        val newCatalog = shouldUseRemoteCatalog(remote, saved(context)?.second)
        if (newCatalog) {
            // Signature last, so a half-written pair never verifies.
            sigFile(context).delete()
            writeAtomically(catalogFile(context), data)
            writeAtomically(sigFile(context), sig)
        }
        val trending = runCatching { get(file("trending.json"), token, MAX_TRENDING_BYTES) }.getOrNull()
        val trendingChanged = trending != null && !trending.contentEquals(runCatching { trendingFile(context).readBytes() }.getOrNull())
        if (trending != null && trendingChanged) writeAtomically(trendingFile(context), trending)
        val artworkChanged = saveArtwork(context, token, refreshAll = newCatalog)
        return newCatalog || trendingChanged || artworkChanged
    }

    // Downloads each artwork and banner the catalog uses: all of them after a new catalog (files
    // may have changed under the same name), otherwise only missing ones. One failing file is
    // skipped; the tile shows initials until the next check.
    private fun saveArtwork(context: Context, token: String, refreshAll: Boolean): Boolean {
        val apps = saved(context)?.let { parseCatalog(it.first) }.orEmpty()
        val paths = apps.flatMap { listOfNotNull(it.artwork, it.banner) }.distinct().filter(::safeArtworkPath)
        var changed = false
        for (path in paths) {
            val target = File(dir(context), path)
            if (!refreshAll && target.isFile) continue
            runCatching {
                val bytes = get(file(path), token, MAX_ARTWORK_BYTES)
                target.parentFile?.mkdirs()
                writeAtomically(target, bytes)
                changed = true
            }
        }
        return changed
    }

    private fun file(path: String) = "${BuildConfig.LICENSE_URL}/v1/catalog/$path"

    private fun dir(context: Context) = File(context.filesDir, "catalog").apply { mkdirs() }
    private fun catalogFile(context: Context) = File(dir(context), "catalog.json")
    private fun sigFile(context: Context) = File(dir(context), "catalog.json.sig")

    private fun writeAtomically(file: File, data: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) throw IOException("Couldn't save ${file.name}")
    }

    private fun get(url: String, token: String, max: Int): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (conn.responseCode == 401 || conn.responseCode == 403) {
                // The server no longer accepts this license (ended or refunded): ask it again.
                License.refreshSoon(force = true)
                throw IOException("License not accepted")
            }
            if (conn.responseCode != 200) throw IOException("Server returned ${conn.responseCode}")
            return conn.inputStream.use { it.readAtMost(max) }
        } finally {
            conn.disconnect()
        }
    }

    // Fails instead of truncating, so an oversized file is never verified or parsed.
    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("File too large")
        }
        return out.toByteArray()
    }

    // Public keys of this installed app's signing certificate(s), including any rotation history.
    // Debug builds get the debug key, so they only accept the debug signature.
    @Suppress("DEPRECATION")
    private fun ownSigningKeys(context: Context): List<PublicKey> = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptyList()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else info.signatures
        val factory = CertificateFactory.getInstance("X.509")
        signatures.orEmpty().map { factory.generateCertificate(it.toByteArray().inputStream()).publicKey }
    }.getOrDefault(emptyList())
}
