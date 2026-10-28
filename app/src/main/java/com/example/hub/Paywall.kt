@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// How often the paywall asks the server whether this device was just paid for.
private const val PAYWALL_POLL_MS = 5_000L

// Shown instead of the app until the device is unlocked: checking, offline, or the plans.
// Back leaves the app.
@Composable
fun LicenseGate(state: LicenseState) {
    val activity = LocalContext.current as? Activity
    BackHandler { activity?.finish() }
    when (state) {
        LicenseState.Checking -> CheckingScreen()
        LicenseState.Offline -> OfflineScreen()
        is LicenseState.Locked -> {
            // Unlocks on its own once Shopify tells the server about the order.
            LaunchedEffect(Unit) {
                while (true) {
                    delay(PAYWALL_POLL_MS)
                    License.refresh()
                }
            }
            PlansScreen(state.code, state.trial, onBack = null)
        }
        is LicenseState.Unlocked -> Unit
    }
}

@Composable
private fun CheckingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.paywall_checking), fontSize = 22.sp, color = HubColors.Muted)
    }
}

@Composable
private fun OfflineScreen() {
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    val focus = rememberInitialFocus()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Text(stringResource(R.string.paywall_offline_title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
        Text(stringResource(R.string.paywall_offline_body), fontSize = 18.sp, color = HubColors.Muted)
        HubButton(
            stringResource(if (retrying) R.string.btn_checking else R.string.btn_check_again),
            { if (!retrying) scope.launch { retrying = true; License.refresh(); retrying = false } },
            Modifier.padding(top = 8.dp).focusRequester(focus)
        )
    }
}

// The two plans with a checkout QR code for the focused one, the free trial and "I already paid".
// trial: null hides the trial (opened from Settings during the trial). onBack: null on the paywall
// itself, where Back leaves the app.
@Composable
fun PlansScreen(code: String, trial: TrialOffer?, onBack: (() -> Unit)?) {
    if (onBack != null) BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf(Plan.MONTHLY) }
    var startingTrial by remember { mutableStateOf(false) }
    var claiming by remember { mutableStateOf(false) }
    val focus = rememberInitialFocus()

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.app_name), fontSize = 13.sp, color = HubColors.GlowPurple)
            Text(stringResource(R.string.paywall_title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
            Text(stringResource(R.string.paywall_body), fontSize = 18.sp, lineHeight = 25.sp, color = HubColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 6.dp)) {
                // The trial button takes first focus when it's offered, otherwise Monthly.
                val monthlyFocus = if (trial == TrialOffer.AVAILABLE) Modifier else Modifier.focusRequester(focus)
                PlanCard(
                    stringResource(R.string.plan_monthly), stringResource(R.string.plan_monthly_price), stringResource(R.string.plan_monthly_note),
                    selected = plan == Plan.MONTHLY, modifier = monthlyFocus.weight(1f)
                ) { plan = Plan.MONTHLY }
                PlanCard(
                    stringResource(R.string.plan_lifetime), stringResource(R.string.plan_lifetime_price), stringResource(R.string.plan_lifetime_note),
                    selected = plan == Plan.LIFETIME, modifier = Modifier.weight(1f)
                ) { plan = Plan.LIFETIME }
            }
            when (trial) {
                TrialOffer.AVAILABLE -> HubButton(
                    stringResource(if (startingTrial) R.string.btn_checking else R.string.btn_start_trial),
                    { if (!startingTrial) scope.launch { startingTrial = true; License.startTrial(); startingTrial = false } },
                    Modifier.padding(top = 6.dp).focusRequester(focus),
                    color = HubColors.GlowGreen
                )
                TrialOffer.USED -> Text(stringResource(R.string.trial_used), fontSize = 16.sp, color = HubColors.Muted)
                TrialOffer.IP_LIMIT -> Text(stringResource(R.string.trial_ip_limit), fontSize = 16.sp, lineHeight = 22.sp, color = HubColors.Muted)
                null -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HubButton(stringResource(R.string.btn_already_paid), { claiming = true }, color = HubColors.GlowPurple)
                if (onBack != null) HubButton(stringResource(R.string.btn_back_to_settings), onBack, color = HubColors.Muted)
            }
        }
        CheckoutPanel(plan, code, Modifier.width(300.dp))
    }

    if (claiming) ClaimDialog(onDismiss = { claiming = false })
}

// A plan to pick. Focusing it is enough to show its QR code.
@Composable
private fun PlanCard(title: String, price: String, note: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    HubCard(
        onClick = onSelect,
        modifier = modifier.onFocusChanged { if (it.isFocused) onSelect() },
        tint = if (selected) HubColors.GlowBlue else HubColors.Line,
        selected = selected
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, color = if (selected) HubColors.GlowBlue else HubColors.Muted)
            Text(price, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = HubColors.Text)
            Text(note, fontSize = 14.sp, color = HubColors.Muted)
        }
    }
}

@Composable
private fun CheckoutPanel(plan: Plan, code: String, modifier: Modifier) {
    val url = License.buyUrl(plan, code)
    val price = stringResource(if (plan == Plan.MONTHLY) R.string.plan_monthly_price else R.string.plan_lifetime_price)
    Column(
        modifier.background(HubColors.Surface, RoundedCornerShape(20.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.paywall_scan, price), fontSize = 16.sp, color = HubColors.Text)
        QrCode(url, Modifier.size(200.dp))
        Text(stringResource(R.string.paywall_device_code, formatDeviceCode(code)), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = HubColors.GlowPurple)
        Text(stringResource(R.string.paywall_auto), fontSize = 13.sp, lineHeight = 18.sp, color = HubColors.Muted)
    }
}

// "K7P4QX" -> "K7P-4QX", easier to read off the screen.
fun formatDeviceCode(code: String): String = if (code.length == 6) code.take(3) + "-" + code.drop(3) else code

// Dark modules on a light square with a quiet zone, so phone cameras read it on a dark screen.
@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { qrMatrix(text) }
    val description = stringResource(R.string.cd_checkout_qr)
    Canvas(modifier.background(HubColors.Text, RoundedCornerShape(10.dp)).padding(12.dp).semantics { contentDescription = description }) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(HubColors.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

private fun qrMatrix(text: String): BitMatrix =
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))

// For orders paid without this device's code (typed on another device, or the link lost it):
// the order number and email from the confirmation unlock this device.
@Composable
private fun ClaimDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var order by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val submit = {
        if (!busy && order.isNotBlank() && email.isNotBlank()) scope.launch {
            busy = true; failed = false
            if (License.claim(order, email)) onDismiss() else failed = true
            busy = false
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        val focus = rememberInitialFocus()
        Column(
            Modifier.width(560.dp).background(HubColors.SurfaceRaised, RoundedCornerShape(20.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.claim_title), fontSize = 28.sp, lineHeight = 34.sp, color = HubColors.Text)
            Text(stringResource(R.string.claim_body), fontSize = 16.sp, lineHeight = 22.sp, color = HubColors.Muted)
            ClaimField(order, { order = it.filter(Char::isDigit).take(12) }, stringResource(R.string.claim_order_hint), KeyboardType.Number, Modifier.focusRequester(focus))
            ClaimField(email, { email = it.trim().take(200) }, stringResource(R.string.claim_email_hint), KeyboardType.Email, Modifier)
            if (failed) Text(stringResource(R.string.claim_failed), fontSize = 16.sp, color = HubColors.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                HubButton(stringResource(if (busy) R.string.btn_checking else R.string.btn_unlock), { submit() })
                HubButton(stringResource(R.string.btn_cancel), onDismiss, color = HubColors.Muted)
            }
        }
    }
}

@Composable
private fun ClaimField(value: String, onValueChange: (String) -> Unit, hint: String, type: KeyboardType, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth()
            .glow(HubColors.GlowRed, cornerRadius = 12.dp, spread = 12.dp)
            .background(if (focused) HubColors.Black else HubColors.Surface, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) HubColors.GlowRed else HubColors.Line, shape)
            .semantics { contentDescription = hint },
        singleLine = true,
        interactionSource = interaction,
        textStyle = TextStyle(fontSize = 20.sp, color = HubColors.Text),
        cursorBrush = SolidColor(HubColors.GlowBlue),
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                if (value.isEmpty()) Text(hint, fontSize = 20.sp, color = HubColors.Muted)
                inner()
            }
        }
    )
}
