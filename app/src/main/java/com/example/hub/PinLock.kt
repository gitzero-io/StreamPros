@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.tv.material3.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom

const val PIN_LENGTH = 4

fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

// SHA-256 of salt + PIN, so the PIN itself is never stored.
fun pinHash(pin: String, salt: String): String = sha256Hex((salt + pin).toByteArray())

// Optional PIN for shared or family TVs. When set, installs, updates, uninstalls and Reset ask for
// it; a correct PIN unlocks them for a few minutes so Update all or several installs ask once.
// It lives in app data, so clearing the Hub's data in Android settings removes it.
object PinLock {
    private const val FILE = "hub_pin"
    private const val KEY_HASH = "hash"
    private const val KEY_SALT = "salt"
    private const val UNLOCK_MS = 5 * 60 * 1000L

    @Volatile private var unlockedUntil = 0L
    private val _request = MutableStateFlow<(() -> Unit)?>(null)
    // The action waiting for the PIN; HubRoot shows PinEntryDialog while it is set.
    val request: StateFlow<(() -> Unit)?> = _request

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isSet(ctx: Context): Boolean = prefs(ctx).contains(KEY_HASH)

    fun set(ctx: Context, pin: String) {
        require(isValidPin(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit { putString(KEY_SALT, salt); putString(KEY_HASH, pinHash(pin, salt)) }
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit { clear() }
        unlockedUntil = 0
    }

    fun matches(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val hash = p.getString(KEY_HASH, null) ?: return true
        return pinHash(pin, p.getString(KEY_SALT, "").orEmpty()) == hash
    }

    // Runs `action` now when there's no PIN or it was entered recently; otherwise asks for it first.
    fun guard(ctx: Context, action: () -> Unit) {
        if (!isSet(ctx) || System.currentTimeMillis() < unlockedUntil) action() else _request.value = action
    }

    // True when the PIN was right; the waiting action then runs.
    fun submit(ctx: Context, pin: String): Boolean {
        if (!matches(ctx, pin)) return false
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
        val action = _request.value
        _request.value = null
        action?.invoke()
        return true
    }

    fun dismiss() { _request.value = null }
}

// Asks for the PIN for PinLock.request. Back cancels the action.
@Composable
fun PinEntryDialog() {
    val context = LocalContext.current
    var wrong by remember { mutableStateOf(false) }
    PinDialog(
        title = stringResource(R.string.pin_enter),
        error = if (wrong) stringResource(R.string.pin_wrong) else null,
        onPin = { wrong = !PinLock.submit(context, it) },
        onDismiss = { PinLock.dismiss() }
    )
}

// Choosing a new PIN: enter it, then again to confirm.
@Composable
fun PinSetupDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    PinDialog(
        title = stringResource(if (first == null) R.string.pin_new else R.string.pin_confirm),
        error = if (mismatch) stringResource(R.string.pin_mismatch) else null,
        onPin = { pin ->
            val chosen = first
            when {
                chosen == null -> { first = pin; mismatch = false }
                chosen == pin -> onDone(pin)
                else -> { first = null; mismatch = true }
            }
        },
        onDismiss = onDismiss
    )
}

// A PIN pad that works with the D-pad (on-screen keys) and with remote number keys. Calls onPin
// once PIN_LENGTH digits are entered, then clears the entry.
@Composable
private fun PinDialog(title: String, error: String?, onPin: (String) -> Unit, onDismiss: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    val type: (Char) -> Unit = { d ->
        if (entry.length < PIN_LENGTH) {
            entry += d
            if (entry.length == PIN_LENGTH) { val pin = entry; entry = ""; onPin(pin) }
        }
    }
    val delete = { entry = entry.dropLast(1) }
    Dialog(onDismissRequest = onDismiss) {
        val focus = rememberInitialFocus()
        Column(
            Modifier.width(440.dp).background(HubColors.SurfaceRaised, RoundedCornerShape(20.dp)).padding(32.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = digitForKey(e.key)
                    when {
                        digit != null -> { type(digit); true }
                        e.key == Key.Backspace || e.key == Key.Delete -> { delete(); true }
                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, fontSize = 26.sp, lineHeight = 32.sp, color = HubColors.Text, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(PIN_LENGTH) { i ->
                    Box(
                        Modifier.size(18.dp).background(if (i < entry.length) HubColors.GlowBlue else HubColors.Line, CircleShape)
                    )
                }
            }
            Text(error ?: stringResource(R.string.pin_hint), fontSize = 15.sp, color = if (error != null) HubColors.Warning else HubColors.Muted, textAlign = TextAlign.Center)
            val deleteDescription = stringResource(R.string.cd_delete)
            // Phone-style pad; the last row is a gap, 0 and delete.
            listOf("123", "456", "789", " 0<").forEach { keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.forEach { k ->
                        val mod = Modifier.width(96.dp)
                        when (k) {
                            ' ' -> Spacer(mod)
                            '<' -> HubButton(delete, mod.semantics { contentDescription = deleteDescription }, color = HubColors.Muted) {
                                PadLabel("⌫")
                            }
                            else -> HubButton({ type(k) }, if (k == '1') mod.focusRequester(focus) else mod) { PadLabel(k.toString()) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadLabel(text: String) {
    Text(text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
}

private fun digitForKey(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
