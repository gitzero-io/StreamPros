package com.example.hub

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Text for the screen that isn't turned into a string yet: a string resource, translated when it
// is shown, or text that is already final (a system message). Lets code without a Context, such
// as the installer and update checks, report messages in the viewer's language.
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.toTypedArray())
    is UiText.Raw -> text
}

@Composable
fun UiText.text(): String = resolve(LocalContext.current)
