@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.tv.material3.*
import java.util.Locale

// Languages the Hub is translated into (res/values-<tag>), each listed under its own name.
enum class HubLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    HINDI("hi", "हिन्दी");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun fromTag(tag: String?): HubLanguage? = values().firstOrNull { it.tag == tag }

        // The TV's own language when the Hub has it, else English.
        fun deviceDefault(): HubLanguage {
            val system = ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0]
            return values().firstOrNull { it.tag == system?.language } ?: ENGLISH
        }
    }
}

// The language picked on first launch or in Settings. Kept apart from HubSettings so it can be
// read before anything is drawn (MainActivity.attachBaseContext).
object LanguagePrefs {
    private const val FILE = "hub_language"
    private const val KEY_LANGUAGE = "language"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun chosen(ctx: Context): HubLanguage? = HubLanguage.fromTag(prefs(ctx).getString(KEY_LANGUAGE, null))

    // The language the Hub shows: the one picked, else the TV's own.
    fun current(ctx: Context): HubLanguage = chosen(ctx) ?: HubLanguage.deviceDefault()

    // Written right away: the activity restarts next to apply it.
    fun choose(ctx: Context, language: HubLanguage) = prefs(ctx).edit(commit = true) { putString(KEY_LANGUAGE, language.tag) }

    fun clear(ctx: Context) = prefs(ctx).edit(commit = true) { clear() }
}

// A Context whose resources (and default Locale, used for dates) follow the Hub's language.
fun Context.withHubLanguage(): Context {
    val locale = LanguagePrefs.current(this).locale
    Locale.setDefault(locale)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}

// One row per language. First launch shows it before the setup guide (no Back: there is nothing
// before it); Settings opens it with onBack. Focus starts on the language in use.
@Composable
fun LanguageScreen(current: HubLanguage, onPick: (HubLanguage) -> Unit, onBack: (() -> Unit)?) {
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val focus = rememberInitialFocus()
    val shownIn = Locale.getDefault()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.language_title), fontSize = 38.sp, lineHeight = 44.sp, color = HubColors.Text)
        Text(stringResource(R.string.language_title_all), fontSize = 18.sp, color = HubColors.Muted)
        Spacer(Modifier.height(4.dp))
        HubLanguage.values().forEach { language ->
            val selected = language == current
            HubCard(
                onClick = { onPick(language) },
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().height(56.dp)
                    .then(if (selected) Modifier.focusRequester(focus) else Modifier),
                tint = HubColors.GlowPurple,
                selected = selected
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(language.nativeName, fontSize = 22.sp, color = HubColors.Text)
                    Spacer(Modifier.weight(1f))
                    // The language's name in the language on screen, e.g. "Spanish" next to Español.
                    val localName = language.locale.getDisplayLanguage(shownIn).replaceFirstChar { it.titlecase(shownIn) }
                    if (localName != language.nativeName) Text(localName, fontSize = 16.sp, color = HubColors.Muted)
                    if (selected) Text("✓", fontSize = 20.sp, color = HubColors.GlowPurple, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
}
