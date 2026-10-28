@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*

enum class ReportReason(@StringRes val label: Int) {
    CRASH(R.string.report_crash),
    INSTALL(R.string.report_install),
    VERSION(R.string.report_version),
    PLAYBACK(R.string.report_playback),
    OTHER(R.string.report_other)
}

// What a report would carry: the catalog app, the version the catalog offers, the version on
// this TV (null if not installed) and the reason picked.
data class AppReport(
    val packageName: String,
    val catalogVersion: String?,
    val installedVersionCode: Long?,
    val reason: ReportReason
)

// Where reports go. Not connected to anything yet: submit() drops the report, and nothing leaves
// the TV. Hook up a destination here later (CLAUDE.md's network rule would need updating to send
// reports over the network).
object Reports {
    fun submit(report: AppReport) = Unit
}

// Flag button that opens the report dialog. `label` adds text after the icon (App Detail);
// without it the button is icon-only (app rows).
@Composable
fun ReportButton(app: HubApp, installedVersion: Long?, modifier: Modifier = Modifier, label: String? = null) {
    var open by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val description = stringResource(R.string.cd_report, app.name)
    val buttonModifier = modifier.focusRequester(focus).semantics { contentDescription = description }
    if (label == null) HubIconButton({ open = true }, buttonModifier, color = HubColors.Muted) {
        FlagIcon(HubColors.Muted, Modifier.size(16.dp))
    } else HubButton({ open = true }, buttonModifier, color = HubColors.Muted) {
        FlagIcon(HubColors.Muted, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, softWrap = false)
    }
    if (open) ReportDialog(app, installedVersion) {
        open = false
        runCatching { focus.requestFocus() }
    }
}

// Pick a reason, then a short thank-you. Back or CANCEL closes without reporting.
@Composable
private fun ReportDialog(app: HubApp, installedVersion: Long?, onClose: () -> Unit) {
    var sent by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(560.dp).background(HubColors.SurfaceRaised, RoundedCornerShape(20.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.report_label), fontSize = 13.sp, color = HubColors.Muted)
            Text(app.name, fontSize = 28.sp, lineHeight = 34.sp, color = HubColors.Text)
            key(sent) {
                val focus = rememberInitialFocus()
                if (!sent) {
                    Text(stringResource(R.string.report_question), fontSize = 18.sp, color = HubColors.Muted)
                    ReportReason.values().forEachIndexed { i, reason ->
                        HubButton(
                            stringResource(reason.label),
                            {
                                Reports.submit(AppReport(app.packageName, app.version, installedVersion, reason))
                                sent = true
                            },
                            Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(focus) else Modifier)
                        )
                    }
                    HubButton(stringResource(R.string.btn_cancel), onClose, Modifier.padding(top = 4.dp), color = HubColors.Muted)
                } else {
                    Text(stringResource(R.string.report_thanks), fontSize = 20.sp, color = HubColors.GlowGreen)
                    HubButton(stringResource(R.string.btn_done), onClose, Modifier.padding(top = 8.dp).focusRequester(focus))
                }
            }
        }
    }
}

// Flag on a pole, drawn so the app needs no icon library.
@Composable
private fun FlagIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * 0.2f, h * 0.06f), Offset(w * 0.2f, h * 0.96f), w * 0.12f, StrokeCap.Round)
        drawPath(
            Path().apply {
                moveTo(w * 0.2f, h * 0.08f)
                lineTo(w * 0.88f, h * 0.08f)
                lineTo(w * 0.7f, h * 0.3f)
                lineTo(w * 0.88f, h * 0.52f)
                lineTo(w * 0.2f, h * 0.52f)
                close()
            },
            color
        )
    }
}
