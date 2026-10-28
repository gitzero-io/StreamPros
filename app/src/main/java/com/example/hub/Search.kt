@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.*

// Search screen. The query lives in the Screen.Search entry so it survives a trip to App Detail
// and back. Matching is searchApps() in SearchMatch.kt and runs on the device; nothing typed or
// spoken is sent anywhere by the Hub.
//
// D-pad: focus starts on the text field (keyboard shown when the query is empty, else on OK).
// Down goes to the results; Right at the end of the text goes to the mic. Back from anywhere
// but the field returns to the field (and stops listening); Back on the field leaves the screen.
@Composable
fun SearchScreen(
    session: Screen.Search,
    resumeTick: Int,
    onOpenDetail: (HubApp) -> Unit,
    onBrowseCategories: () -> Unit
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val catalog = remember { Catalog.get(context) }
    val states by InstallCoordinator.states.collectAsState()
    val installedPkgs = remember(resumeTick, states) {
        catalog.filter { AppLauncher.installedVersionCode(context, it.packageName) != null }.map { it.packageName }.toSet()
    }
    val ranked = remember(catalog) { trendingApps(Trending.get(context), catalog).numbered() }

    var field by remember { mutableStateOf(TextFieldValue(session.query, TextRange(session.query.length))) }
    val setQuery: (String) -> Unit = { text ->
        field = TextFieldValue(text, TextRange(text.length))
        session.query = text
    }
    // Categories match by their catalog name and by their name in the Hub's language.
    val resources = LocalResources.current
    val categoryNames = remember(resources) { Category.values().associateWith { listOf(it.label, resources.getString(it.title)) } }
    val results = remember(field.text) { searchApps(field.text, catalog) { categoryNames.getValue(it) } }
    val blank = normalizeForSearch(field.text).isEmpty()
    // contentFocus goes on the first focusable thing under the search bar: the first result,
    // the first trending card or the Browse categories button. hasContent: one of them exists.
    val hasContent = if (blank) ranked.isNotEmpty() else true

    val fieldFocus = rememberInitialFocus()
    val contentFocus = remember { FocusRequester() }
    val micFocus = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val focusContent = { if (hasContent) runCatching { contentFocus.requestFocus() } }

    // The field is read-only (no input session, so no keyboard) until the user wants to type:
    // editable straight away on an empty search, otherwise after OK on the field. Leaving the
    // field makes it read-only again. A focused editable field restarts its input session and
    // re-shows the keyboard, so hiding it after the fact isn't reliable.
    var editing by remember { mutableStateOf(session.query.isEmpty()) }
    LaunchedEffect(editing, fieldFocused) {
        if (!editing || !fieldFocused) return@LaunchedEffect
        withFrameNanos { }
        keyboard?.show()
    }

    val voice = rememberVoiceSearch(onText = setQuery)
    var explainMic by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start()
        else {
            val activity = context as? Activity
            val blocked = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            voice.message = if (blocked) R.string.voice_mic_blocked else R.string.voice_mic_declined
        }
        runCatching { micFocus.requestFocus() }
    }
    val onMic = {
        when {
            voice.listening -> voice.stop()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> voice.start()
            else -> explainMic = true
        }
    }

    BackHandler(enabled = voice.listening || !fieldFocused) {
        if (voice.listening) voice.cancel() else runCatching { fieldFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = ShelfInset).padding(top = 8.dp).fillMaxWidth()
                .onPreviewKeyEvent { e ->
                    if (e.key != Key.DirectionDown || !hasContent) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) { keyboard?.hide(); focusContent() }
                    true
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SearchField(
                field,
                onValueChange = { field = it; session.query = it.text },
                focused = fieldFocused,
                readOnly = !editing,
                modifier = Modifier.weight(1f).focusRequester(fieldFocus)
                    .onFocusChanged { fieldFocused = it.isFocused; if (!it.isFocused) editing = false }
                    .onPreviewKeyEvent { e ->
                        when (e.key) {
                            // OK starts typing, or brings the keyboard back after Back hid it.
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (e.type == KeyEventType.KeyUp) { if (editing) keyboard?.show() else editing = true }
                                true
                            }
                            Key.DirectionRight -> {
                                val atEnd = field.selection.collapsed && field.selection.end == field.text.length
                                if (!voice.available || !atEnd) return@onPreviewKeyEvent false
                                if (e.type == KeyEventType.KeyDown) runCatching { micFocus.requestFocus() }
                                true
                            }
                            else -> false
                        }
                    },
                onSearch = { keyboard?.hide(); focusContent() }
            )
            if (voice.available) MicButton(voice.listening, onMic, Modifier.focusRequester(micFocus))
        }

        Box(Modifier.padding(horizontal = ShelfInset).padding(top = 10.dp).height(24.dp)) {
            when {
                voice.listening -> Text(stringResource(R.string.search_listening), fontSize = 17.sp, color = HubColors.GlowPurple)
                voice.message != null -> Text(stringResource(voice.message!!), fontSize = 17.sp, color = HubColors.Text)
                !blank && results.isNotEmpty() ->
                    Text(pluralStringResource(R.plurals.search_count, results.size, results.size), fontSize = 17.sp, color = HubColors.Muted)
            }
        }

        when {
            blank -> if (ranked.isNotEmpty()) {
                Shelf(stringResource(R.string.shelf_trending), ranked, contentFocus) { r, mod ->
                    RankedItem(r.rank) { SearchCard(r.app, r.app.packageName in installedPkgs, mod, onOpenDetail) }
                }
            }
            results.isEmpty() -> NoResults(field.text, Modifier.focusRequester(contentFocus), onBrowseCategories)
            else -> LazyVerticalGrid(
                // Fixed cells: wider cells would stretch the AppCard.
                columns = GridCells.FixedSize(AppCardWidth),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = ShelfInset, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                itemsIndexed(results, key = { _, app -> app.packageName }) { i, app ->
                    SearchCard(
                        app, app.packageName in installedPkgs,
                        if (i == 0) Modifier.focusRequester(contentFocus) else Modifier, onOpenDetail
                    )
                }
            }
        }
    }

    if (explainMic) MicExplainDialog(
        onContinue = { explainMic = false; permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onDismiss = { explainMic = false; runCatching { micFocus.requestFocus() } }
    )
}

// The usual app card; OK (or a long press) opens App Detail.
@Composable
private fun SearchCard(app: HubApp, installed: Boolean, modifier: Modifier, onOpenDetail: (HubApp) -> Unit) {
    AppCard(
        app, { onOpenDetail(app) }, modifier, onLongClick = { onOpenDetail(app) },
        subtitle = stringResource(if (installed) R.string.status_installed else R.string.card_get_app),
        subtitleColor = if (installed) HubColors.Muted else HubColors.GlowBlue
    )
}

@Composable
private fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focused: Boolean,
    readOnly: Boolean,
    modifier: Modifier,
    onSearch: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    val description = stringResource(R.string.cd_search_apps)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .glow(HubColors.GlowRed, cornerRadius = 50.dp, spread = 14.dp)
            .background(if (focused) HubColors.SurfaceRaised else HubColors.Surface, shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) HubColors.GlowRed else HubColors.Line, shape)
            .semantics { contentDescription = description },
        singleLine = true,
        readOnly = readOnly,
        textStyle = TextStyle(fontSize = 22.sp, color = HubColors.Text),
        cursorBrush = SolidColor(HubColors.GlowBlue),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { inner ->
            Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchIcon(HubColors.Muted, Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f)) {
                    if (value.text.isEmpty()) Text(stringResource(R.string.search_hint), fontSize = 22.sp, color = HubColors.Muted)
                    inner()
                }
            }
        }
    )
}

@Composable
private fun NoResults(query: String, buttonModifier: Modifier, onBrowseCategories: () -> Unit) {
    Column(
        Modifier.padding(horizontal = ShelfInset).padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(stringResource(R.string.search_no_match, query.trim()), fontSize = 28.sp, color = HubColors.Text)
        Text(stringResource(R.string.search_try), fontSize = 18.sp, color = HubColors.Muted)
        HubButton(stringResource(R.string.btn_browse_categories), onBrowseCategories, buttonModifier.padding(top = 8.dp), color = HubColors.GlowPurple)
    }
}

// Round mic button. While listening it pulses GlowPurple.
@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val micDescription = stringResource(if (listening) R.string.cd_stop_listening else R.string.cd_voice_search)
    val pulse = rememberInfiniteTransition(label = "mic")
    val phase by pulse.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "phase"
    )
    val iconColor = if (listening) HubColors.GlowPurple else HubColors.Text
    Surface(
        onClick = onClick,
        modifier = modifier.size(60.dp)
            .glow(HubColors.GlowRed, cornerRadius = 30.dp, spread = 14.dp)
            .drawBehind {
                if (!listening) return@drawBehind
                // Expanding, fading ring.
                val grow = 22.dp.toPx() * phase
                drawRoundRect(
                    HubColors.GlowPurple.copy(alpha = 0.45f * (1f - phase)),
                    topLeft = Offset(-grow, -grow),
                    size = Size(size.width + grow * 2, size.height + grow * 2),
                    cornerRadius = CornerRadius(size.width / 2 + grow)
                )
            }
            .semantics { contentDescription = micDescription },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (listening) HubColors.GlowPurple.copy(alpha = 0.2f) else HubColors.Surface,
            contentColor = iconColor,
            focusedContainerColor = if (listening) HubColors.GlowPurple.copy(alpha = 0.3f) else HubColors.SurfaceRaised,
            focusedContentColor = iconColor,
            pressedContainerColor = HubColors.SurfaceRaised
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.5.dp, if (listening) HubColors.GlowPurple else HubColors.Line), shape = CircleShape),
            focusedBorder = focusBorder(CircleShape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MicIcon(iconColor, Modifier.size(26.dp)) }
    }
}

// Shown before the system permission dialog, the first time the mic is pressed.
@Composable
private fun MicExplainDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val focus = rememberInitialFocus()
        Column(
            Modifier.width(560.dp).background(HubColors.SurfaceRaised, RoundedCornerShape(20.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.voice_label), fontSize = 13.sp, color = HubColors.GlowPurple)
            Text(stringResource(R.string.voice_title), fontSize = 28.sp, lineHeight = 34.sp, color = HubColors.Text)
            Text(
                stringResource(R.string.voice_body),
                fontSize = 18.sp, lineHeight = 26.sp, color = HubColors.Muted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                HubButton(stringResource(R.string.btn_continue), onContinue, Modifier.focusRequester(focus))
                HubButton(stringResource(R.string.btn_not_now), onDismiss, color = HubColors.Muted)
            }
        }
    }
}

// SpeechRecognizer wrapper. `available` is false when the TV has no recognition service, and
// then the mic is hidden. The recognizer is released when the screen leaves composition and
// stopped when the app goes to the background.
@Stable
internal class VoiceSearch(val available: Boolean) {
    var listening by mutableStateOf(false)
    var message by mutableStateOf<Int?>(null) // string resource
    internal var recognizer: SpeechRecognizer? = null
    internal var packageName: String = ""

    fun start() {
        val r = recognizer ?: return
        message = null
        listening = true
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        })
    }

    // Stops listening but still delivers what was heard so far.
    fun stop() { recognizer?.stopListening() }

    fun cancel() {
        listening = false
        recognizer?.cancel()
    }
}

@Composable
private fun rememberVoiceSearch(onText: (String) -> Unit): VoiceSearch {
    val context = LocalContext.current
    val currentOnText by rememberUpdatedState(onText)
    val voice = remember {
        val recognizer = if (SpeechRecognizer.isRecognitionAvailable(context))
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() else null
        VoiceSearch(recognizer != null).also { it.recognizer = recognizer; it.packageName = context.packageName }
    }
    DisposableEffect(voice) {
        voice.recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (!voice.listening) return
                voice.listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (text.isNullOrEmpty()) voice.message = R.string.voice_no_match
                else currentOnText(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!voice.listening) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { currentOnText(it) }
            }

            override fun onError(error: Int) {
                // Errors after cancel() (we already stopped) are expected; ignore them.
                if (!voice.listening) return
                voice.listening = false
                voice.message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        R.string.voice_no_match
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_mic_blocked
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_busy
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        R.string.voice_unavailable
                    else -> R.string.voice_stopped
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose {
            voice.listening = false
            voice.recognizer?.destroy()
            voice.recognizer = null
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, voice) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && voice.listening) voice.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return voice
}

// Magnifying glass, drawn so the app needs no icon library.
@Composable
internal fun SearchIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.12f
        val r = size.minDimension * 0.32f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, r, c, style = Stroke(stroke))
        val d = r * 0.72f
        drawLine(color, Offset(c.x + d, c.y + d), Offset(size.width * 0.9f, size.height * 0.9f), stroke, StrokeCap.Round)
    }
}

// Microphone: capsule, cradle arc and stand.
@Composable
private fun MicIcon(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        val capsuleW = w * 0.36f
        drawRoundRect(
            color, topLeft = Offset((w - capsuleW) / 2, 0f), size = Size(capsuleW, h * 0.6f),
            cornerRadius = CornerRadius(capsuleW / 2)
        )
        val arcW = w * 0.64f
        drawArc(
            color, 0f, 180f, false, topLeft = Offset((w - arcW) / 2, h * 0.18f), size = Size(arcW, h * 0.56f),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawLine(color, Offset(w / 2, h * 0.74f), Offset(w / 2, h * 0.95f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.32f, h * 0.95f), Offset(w * 0.68f, h * 0.95f), stroke, StrokeCap.Round)
    }
}

// Every category as a card; reached from "Browse categories" when a search finds nothing.
@Composable
fun CategoriesScreen(onOpenCategory: (Category) -> Unit) {
    val first = rememberInitialFocus()
    Column {
        Text(stringResource(R.string.categories_title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text, modifier = Modifier.padding(start = ShelfInset))
        CategoryShelf(first, onOpenCategory, title = stringResource(R.string.search_pick_category))
    }
}
