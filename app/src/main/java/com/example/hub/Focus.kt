@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.example.hub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

private val PillShape = RoundedCornerShape(50)

// Soft halo drawn around the element while it (or a child) has focus. Built from layered
// translucent rounded rects instead of a blur, so it follows the shape and works on API 23
// (tv-material's own glow only draws on API 28+). Edge alpha is about 35%, fading outward.
fun Modifier.glow(color: Color, cornerRadius: Dp = 16.dp, spread: Dp = 18.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.hasFocus }
        .drawBehind {
            if (!focused) return@drawBehind
            val steps = 10
            val s = spread.toPx()
            val r = cornerRadius.toPx()
            val layer = color.copy(alpha = 0.042f)
            for (i in steps downTo 1) {
                val inset = s * i / steps
                drawRoundRect(
                    color = layer,
                    topLeft = Offset(-inset, -inset),
                    size = Size(size.width + inset * 2, size.height + inset * 2),
                    cornerRadius = CornerRadius(r + inset)
                )
            }
        }
}

fun focusBorder(shape: androidx.compose.ui.graphics.Shape) =
    Border(BorderStroke(2.dp, HubColors.GlowRed), shape = shape)

// Requests focus once when a screen first appears. When the previous screen's focused item is
// removed, Compose can move focus onto the new item before it starts listening for focus events,
// so it never draws as focused. Clearing and re-requesting a frame later fixes that.
// Pass enabled = false when the screen has nothing focusable (requesting would throw).
@Composable
fun rememberInitialFocus(enabled: Boolean = true): FocusRequester {
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        withFrameNanos { }
        focusManager.clearFocus()
        requester.requestFocus()
    }
    return requester
}

// Dark pill button with a colored border and matching text. Focus adds the red border and glow.
@Composable
fun HubButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = HubColors.GlowBlue,
    enabled: Boolean = true
) = HubButton(onClick, modifier, color, enabled) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, softWrap = false) }

// Same button with custom content (e.g. an icon); content color follows `color`.
@Composable
fun HubButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = HubColors.GlowBlue,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        modifier = modifier.glow(HubColors.GlowRed, cornerRadius = 50.dp, spread = 14.dp),
        shape = ButtonDefaults.shape(PillShape),
        colors = ButtonDefaults.colors(
            containerColor = HubColors.Surface,
            contentColor = color,
            focusedContainerColor = HubColors.SurfaceRaised,
            focusedContentColor = color,
            pressedContainerColor = HubColors.SurfaceRaised,
            pressedContentColor = color,
            disabledContainerColor = HubColors.Surface,
            disabledContentColor = HubColors.Muted
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.5.dp, color), shape = PillShape),
            focusedBorder = focusBorder(PillShape),
            disabledBorder = Border(BorderStroke(1.dp, HubColors.Line), shape = PillShape),
            focusedDisabledBorder = focusBorder(PillShape)
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        content = content
    )
}

// Round icon-only button. Every icon button is this size so app rows line up.
val IconButtonSize = 44.dp

@Composable
fun HubIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = HubColors.GlowBlue,
    content: @Composable RowScope.() -> Unit
) = HubButton(onClick, modifier.size(IconButtonSize), color, contentPadding = PaddingValues(0.dp), content = content)

// Focusable card: tinted border at rest, red border + glow + 1.05 scale on focus.
@Composable
fun HubCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    tint: Color = HubColors.Line,
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.glow(HubColors.GlowRed),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = HubColors.Surface,
            contentColor = HubColors.Text,
            focusedContainerColor = HubColors.SurfaceRaised,
            focusedContentColor = HubColors.Text,
            pressedContainerColor = HubColors.SurfaceRaised
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tint else tint.copy(alpha = 0.55f)),
                shape = shape
            ),
            focusedBorder = focusBorder(shape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        content = content
    )
}
