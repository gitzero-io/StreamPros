@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.launch

private const val RIGHTS_EMAIL = "Streampros.io@gmail.com"

// The disclaimer, shown on About and in the expandable Disclaimer card at the bottom of Home.
// Title and body of each section, in the Hub's language.
@Composable
internal fun disclaimerSections(): List<Pair<String, String>> = listOf(
    stringResource(R.string.disclaimer_what_title) to stringResource(R.string.disclaimer_what_body),
    stringResource(R.string.disclaimer_third_title) to stringResource(R.string.disclaimer_third_body),
    stringResource(R.string.disclaimer_you_title) to stringResource(R.string.disclaimer_you_body),
    stringResource(R.string.disclaimer_rights_title) to stringResource(R.string.disclaimer_rights_body, RIGHTS_EMAIL)
)

// About StreamPros (from Settings): version, what the Hub is, the third-party disclaimer
// and privacy.
// DONE sits by the title and keeps focus; Down/Up scroll the text below it. Back works too.
@Composable
fun AboutScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    val focus = rememberInitialFocus()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.about_title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
                Text(stringResource(R.string.detail_version, version), fontSize = 16.sp, color = HubColors.Muted)
            }
            HubButton(
                stringResource(R.string.btn_done), onDone,
                Modifier.focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = scroll.viewportSize / 2f
                    when (e.key) {
                        Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(step) }; true }
                        Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-step) }; true }
                        else -> false
                    }
                }
            )
        }
        Box(Modifier.weight(1f, fill = false)) {
            AboutBody(Modifier.verticalScroll(scroll))
        }
        if (scroll.canScrollForward) {
            Text(stringResource(R.string.about_more_down), fontSize = 14.sp, color = HubColors.Muted)
        }
    }
}

@Composable
private fun AboutBody(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        disclaimerSections().forEach { (title, body) -> AboutSection(title, body) }
        AboutSection(
            stringResource(R.string.privacy_title),
            stringResource(R.string.privacy_body)
        )
    }
}

@Composable
private fun AboutSection(title: String, body: String) {
    Column(Modifier.widthIn(max = 820.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = HubColors.Text)
        Text(body, fontSize = 18.sp, lineHeight = 26.sp, color = HubColors.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}
