package com.example.hub

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

enum class Plan(val wire: String) {
    TRIAL("trial"), MONTHLY("monthly"), LIFETIME("lifetime");

    companion object {
        fun from(wire: String?): Plan? = values().firstOrNull { it.wire == wire }
    }
}

// Whether this device can still start the free trial: once per device, and at most 2 per internet
// connection (IP address) every 30 days. Decided by the license server.
enum class TrialOffer(val wire: String) {
    AVAILABLE("available"), USED("used"), IP_LIMIT("ip_limit");

    companion object {
        fun from(wire: String?): TrialOffer = values().firstOrNull { it.wire == wire } ?: USED
    }
}

sealed interface LicenseState {
    data object Checking : LicenseState
    // Can't reach the license server and there's no license that works offline.
    data object Offline : LicenseState
    // code: the short device code shown on the paywall and attached to the Shopify order.
    data class Locked(val code: String, val trial: TrialOffer) : LicenseState
    // expiresAt: null for lifetime. code: as in Locked, for buying a plan during the trial.
    // freeInstalls: the trial's app downloads; null on paid plans.
    data class Unlocked(val plan: Plan, val expiresAt: Long?, val code: String?, val freeInstalls: FreeInstalls? = null) : LicenseState
}

// The trial's downloads: `limit` different apps, `used` the ones already taken. Downloading a used
// app again (retry, reinstall, update) is free. The server keeps count (/v1/install).
data class FreeInstalls(val limit: Int, val used: List<String>) {
    val left: Int get() = (limit - used.size).coerceAtLeast(0)

    // True when downloading `pkg` would need a plan.
    fun blocks(pkg: String): Boolean = pkg !in used && left == 0
}

fun parseFreeInstalls(o: JSONObject?): FreeInstalls? {
    if (o == null || !o.has("limit")) return null
    val used = o.optJSONArray("used")
    return FreeInstalls(o.optInt("limit"), if (used == null) emptyList() else (0 until used.length()).map { used.getString(it) })
}

// A license as the server signed it: d = device id, p = plan, e = expiry, i = issued (Unix ms).
data class LicenseToken(val deviceId: String, val plan: Plan, val expiresAt: Long, val issuedAt: Long)

// Parses and checks a token "<base64url payload>.<base64url signature>". The signature is ECDSA
// P-256 / SHA-256 in the raw r||s form WebCrypto makes. Null when the format or signature is wrong.
fun verifyLicenseToken(token: String, key: PublicKey): LicenseToken? = runCatching {
    val parts = token.split('.')
    if (parts.size != 2) return null
    val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    verifyLicense(Base64.decode(parts[0], flags), Base64.decode(parts[1], flags), key)
}.getOrNull()

// The decoded halves of a token: the JSON payload and its raw signature.
fun verifyLicense(payload: ByteArray, rawSignature: ByteArray, key: PublicKey): LicenseToken? = runCatching {
    val valid = Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(payload); verify(rawToDer(rawSignature)) }
    if (!valid) return null
    val o = JSONObject(String(payload, Charsets.UTF_8))
    LicenseToken(o.getString("d"), Plan.from(o.getString("p")) ?: return null, o.getLong("e"), o.getLong("i"))
}.getOrNull()

// ECDSA raw signature (r||s, 32 bytes each) to the DER SEQUENCE Java's Signature expects.
fun rawToDer(raw: ByteArray): ByteArray {
    require(raw.size == 64) { "Expected a 64-byte P-256 signature" }
    fun int(bytes: ByteArray): ByteArray = BigInteger(1, bytes).toByteArray() // adds a 0 byte when the top bit is set
    val r = int(raw.copyOfRange(0, 32))
    val s = int(raw.copyOfRange(32, 64))
    val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
    return byteArrayOf(0x30, body.size.toByte()) + body
}

// How long a paid license keeps working when the server can't be reached.
const val OFFLINE_GRACE_MS = 7L * 24 * 60 * 60 * 1000
// Clock changes smaller than this (time zone or network time fixes) don't count as winding back.
const val CLOCK_SLACK_MS = 60L * 60 * 1000

// Whether a verified token unlocks this device right now. Online, the server's answer is used
// instead. Offline, only paid plans count (the trial needs the server), for at most a week after
// the server was last reached, and not when the clock has been wound back since then.
fun tokenUnlocks(token: LicenseToken, deviceId: String, now: Long, lastContact: Long, online: Boolean): Boolean {
    if (token.deviceId != deviceId || now >= token.expiresAt) return false
    if (online) return true
    return token.plan != Plan.TRIAL && now + CLOCK_SLACK_MS >= lastContact && now < lastContact + OFFLINE_GRACE_MS
}

// The paywall: asks the license server (BuildConfig.LICENSE_URL) whether this device has paid or is
// in its free trial, keeps the signed license it hands out, and clears the catalog when it ends.
object License {
    private const val PREFS = "license"
    private const val KEY_TOKEN = "token"
    private const val KEY_LAST_CONTACT = "last_contact"
    private const val KEY_CODE = "device_code"
    private const val KEY_FALLBACK_ID = "fallback_device_id"
    private const val TIMEOUT_MS = 10_000
    // Resume asks the server again at most this often; the paywall polls on its own.
    private const val REFRESH_EVERY_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private lateinit var appContext: Context
    @Volatile private var lastRefresh = 0L
    // Server clock minus this TV's clock, from the last answer. TV clocks are often off by hours,
    // so expiry times are shown and timed on the TV's own clock.
    @Volatile private var clockOffset = 0L

    private val _state = MutableStateFlow<LicenseState>(LicenseState.Checking)
    val state: StateFlow<LicenseState> = _state

    // Set when an app needs a plan (the trial's free installs are used); HubRoot shows the plans.
    private val _plansRequested = MutableStateFlow(false)
    val plansRequested: StateFlow<Boolean> = _plansRequested
    fun requestPlans() { _plansRequested.value = true }
    fun plansShown() { _plansRequested.value = false }

    // The trial's free installs, or null on a paid plan (or before the server answered).
    fun freeInstalls(): FreeInstalls? = (_state.value as? LicenseState.Unlocked)?.freeInstalls

    // True when downloading `pkg` needs a plan: the trial's free installs are all used on other apps.
    fun needsPlan(pkg: String): Boolean = freeInstalls()?.blocks(pkg) == true

    // On launch: unlocks right away with a saved paid license (so the app opens offline), then
    // asks the server.
    fun start(context: Context) {
        appContext = context.applicationContext
        val saved = savedToken(appContext)
        if (saved != null && tokenUnlocks(saved, deviceId(appContext), System.currentTimeMillis(), lastContact(appContext), online = false)) {
            _state.value = unlockedState(saved)
        }
        refreshSoon(force = true)
    }

    // On resume, at expiry, or when the catalog server refuses the license.
    fun refreshSoon(force: Boolean = false) {
        if (!::appContext.isInitialized) return
        if (!force && System.currentTimeMillis() - lastRefresh < REFRESH_EVERY_MS) return
        scope.launch { runCatching { refresh() } }
    }

    // The saved license, if it unlocks this device now (for catalog downloads).
    fun token(context: Context): String? {
        val raw = prefs(context).getString(KEY_TOKEN, null) ?: return null
        val token = verifyLicenseToken(raw, publicKey()) ?: return null
        val serverNow = System.currentTimeMillis() + clockOffset
        return raw.takeIf { tokenUnlocks(token, deviceId(context), serverNow, serverNow, online = true) }
    }

    // Asks the server for this device's status.
    suspend fun refresh() = call("/v1/status", JSONObject())

    // Starts the 12-hour trial. Errors come back as the new Locked state (trial used or IP limit).
    suspend fun startTrial() = call("/v1/trial", JSONObject())

    // Links an order that was paid without this device's code: the order number and its email.
    // False when no unclaimed order matches.
    suspend fun claim(order: String, email: String): Boolean {
        val response = call("/v1/claim", JSONObject().put("order", order.trim()).put("email", email.trim()))
        return response?.optString("error").isNullOrEmpty() && _state.value is LicenseState.Unlocked
    }

    // The download link for a catalog app without one: trial catalogs have no links, and the server
    // hands them out for the trial's free installs (and for any app on a paid plan). Throws
    // HubIOException with a message for the screen.
    suspend fun installLink(pkg: String): String {
        val response = mutex.withLock {
            withContext(Dispatchers.IO) { post("/v1/install", JSONObject().put("device", deviceId(appContext)).put("package", pkg)) }
        }
        parseFreeInstalls(response.optJSONObject("freeInstalls"))?.let { free ->
            _state.update { (it as? LicenseState.Unlocked)?.copy(freeInstalls = free) ?: it }
        }
        when (response.optString("error")) {
            "" -> {}
            "trial_limit" -> throw HubIOException(uiText(R.string.error_trial_limit))
            "locked" -> { refreshSoon(force = true); throw HubIOException(uiText(R.string.error_license_ended)) }
            else -> throw HubIOException(uiText(R.string.error_no_link))
        }
        return response.optString("apkUrl").takeIf { it.startsWith("https://") }
            ?: throw HubIOException(uiText(R.string.error_no_link))
    }

    // The checkout link for a plan, opened on a phone through the paywall's QR code. The server
    // turns it into the Shopify checkout with the device code attached.
    fun buyUrl(plan: Plan, code: String): String = "${BuildConfig.LICENSE_URL}/buy/${plan.wire}/$code"

    // One request at a time. Returns the server's JSON, or null when it couldn't be reached (the
    // state then falls back to a license that works offline, or Offline).
    private suspend fun call(path: String, body: JSONObject): JSONObject? = mutex.withLock {
        val ctx = appContext
        val id = deviceId(ctx)
        val response = try {
            withContext(Dispatchers.IO) { post(path, body.put("device", id)) }
        } catch (e: IOException) {
            val saved = savedToken(ctx)
            val worksOffline = saved != null && tokenUnlocks(saved, id, System.currentTimeMillis(), lastContact(ctx), online = false)
            // A dropped request while the paywall polls keeps the paywall up.
            if (!worksOffline && _state.value !is LicenseState.Locked) lock(ctx, LicenseState.Offline)
            return@withLock null
        }
        lastRefresh = System.currentTimeMillis()
        if (response.has("now")) clockOffset = response.getLong("now") - System.currentTimeMillis()
        prefs(ctx).edit { putLong(KEY_LAST_CONTACT, System.currentTimeMillis()) }
        response.optString("code").takeIf { it.isNotEmpty() }?.let { prefs(ctx).edit { putString(KEY_CODE, it) } }
        val rawToken = response.optString("token").takeIf { it.isNotEmpty() }
        val token = rawToken?.let { verifyLicenseToken(it, publicKey()) }
        val serverNow = System.currentTimeMillis() + clockOffset
        if (token != null && tokenUnlocks(token, id, serverNow, serverNow, online = true)) {
            prefs(ctx).edit { putString(KEY_TOKEN, rawToken) }
            _state.value = unlockedState(token, parseFreeInstalls(response.optJSONObject("freeInstalls")))
            CatalogUpdater.checkIfDue(ctx)
        } else if (response.has("code")) {
            lock(ctx, LicenseState.Locked(response.getString("code"), TrialOffer.from(response.optString("trial"))))
        }
        response
    }

    private fun unlockedState(token: LicenseToken, freeInstalls: FreeInstalls? = null) = LicenseState.Unlocked(
        token.plan, if (token.plan == Plan.LIFETIME) null else token.expiresAt - clockOffset, prefs(appContext).getString(KEY_CODE, null),
        freeInstalls.takeIf { token.plan == Plan.TRIAL }
    )

    // Leaving the unlocked state wipes the license and the catalog.
    private fun lock(context: Context, state: LicenseState) {
        val wasUnlocked = _state.value is LicenseState.Unlocked
        if (state !is LicenseState.Offline) prefs(context).edit { remove(KEY_TOKEN) }
        _state.value = state
        if (wasUnlocked || state !is LicenseState.Offline) CatalogUpdater.clear(context)
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val conn = URL(BuildConfig.LICENSE_URL + path).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code >= 500) throw IOException("Server returned $code")
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return runCatching { JSONObject(text) }.getOrElse { throw IOException("Bad response") }
        } finally {
            conn.disconnect()
        }
    }

    private fun savedToken(context: Context): LicenseToken? =
        prefs(context).getString(KEY_TOKEN, null)?.let { verifyLicenseToken(it, publicKey()) }

    private fun lastContact(context: Context) = prefs(context).getLong(KEY_LAST_CONTACT, 0)

    private fun publicKey(): PublicKey = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(Base64.decode(BuildConfig.LICENSE_PUBLIC_KEY, Base64.DEFAULT)))

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // This device's id for trials and licenses: a hash of ANDROID_ID (kept across reinstalls and
    // clearing data; new after a factory reset) and the hardware make, model and board. Devices
    // without a usable ANDROID_ID get a random id saved in prefs.
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != BROKEN_ANDROID_ID }
            ?: prefs(context).getString(KEY_FALLBACK_ID, null)
            ?: UUID.randomUUID().toString().also { prefs(context).edit { putString(KEY_FALLBACK_ID, it) } }
        val hardware = listOf(Build.MANUFACTURER, Build.MODEL, Build.BOARD, Build.HARDWARE).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest("streampros|$androidId|$hardware".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // Many Android 2.2 era devices share this ANDROID_ID.
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"
}
