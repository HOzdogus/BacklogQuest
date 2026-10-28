package com.backlogquest.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.backlogquest.app.data.TAG_INFO
import java.io.File

enum class BtnKind { A, B, PLAIN }

@Composable
fun PixelButton(
    text: String,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.PLAIN,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when (kind) {
        BtnKind.A -> BQ.A
        BtnKind.B -> BQ.B
        BtnKind.PLAIN -> BQ.Edge
    }
    val fg = when (kind) {
        BtnKind.A -> Color.White
        BtnKind.B -> Color(0xFF2A1D05)
        BtnKind.PLAIN -> BQ.Ink
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = fg,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            fontFamily = BQ.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

/** A pill you tap to choose a filter or tab. */
@Composable
fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) BQ.Ink else Color.Transparent,
        contentColor = if (selected) BQ.Shell else BQ.Muted,
        border = BorderStroke(1.dp, if (selected) BQ.Ink else BQ.Edge),
    ) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, maxLines = 1)
    }
}

/** A sideways-scrolling row of choice chips. */
@Composable
fun ChipRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (key, label) ->
            TabChip(label, selected == key) { onSelect(key) }
        }
    }
}

/** Small blocky status label, like READY or COOLDOWN. */
@Composable
fun StatusLabel(text: String, bg: Color = BQ.Edge, fg: Color = BQ.Muted) {
    Text(
        text,
        Modifier.background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        color = fg,
        fontFamily = BQ.Mono,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** Wishlist tags such as MUST PLAY or GAME PASS. */
@Composable
fun TagRow(tags: List<String>) {
    val shown = tags.mapNotNull { TAG_INFO[it] }
    if (shown.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        shown.forEach { (label, style) ->
            val (bg, fg) = when (style) {
                "must" -> BQ.CoolBg to BQ.CoolFg
                "sub" -> BQ.OkBg to BQ.Ok
                "own" -> BQ.OwnBg to BQ.B
                else -> BQ.Edge to BQ.Muted
            }
            Text(
                label,
                Modifier.background(bg, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                color = fg, fontFamily = BQ.Mono, fontSize = 9.sp, maxLines = 1,
            )
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BQ.Shell2)
            .border(1.dp, BQ.Edge, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier,
        fontFamily = BQ.Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = BQ.Ink,
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = BQ.Muted, fontSize = 13.sp)
}

/** The big LCD-style box that shows spin results. */
@Composable
fun Slot(text: String, dim: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BQ.Lcd)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (dim) BQ.LcdDim else BQ.LcdInk,
            fontFamily = BQ.Mono,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun hueOf(s: String): Float {
    var x = 0L
    for (ch in s) x = (x * 31 + ch.code) and 0xFFFFFFFFL
    return (x % 360).toFloat()
}

fun initialsOf(title: String): String =
    title.split(Regex("\\s+"))
        .filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        .take(2)
        .joinToString("") { it[0].uppercase() }
        .ifEmpty { "?" }

/** Box art, or a generated cartridge label when there's no image. */
@Composable
fun Cover(
    title: String,
    label: String,
    cover: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    badge: String? = null,
) {
    var failed by remember(cover) { mutableStateOf(false) }
    Box(
        modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(5.dp))
            .background(BQ.Edge),
    ) {
        if (cover.isNotBlank() && !failed) {
            AsyncImage(
                model = if (cover.startsWith("/")) File(cover) else cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        } else {
            Cartridge(title, label, small)
        }
        if (!badge.isNullOrEmpty()) {
            Text(
                badge,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color(0xBB000000), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp),
                color = BQ.B, fontFamily = BQ.Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Cartridge(title: String, label: String, small: Boolean) {
    val h = hueOf(title)
    val body = Color.hsl(h, 0.42f, 0.30f)
    val sticker = Color.hsl(h, 0.55f, 0.82f)
    val text = Color.hsl(h, 0.55f, 0.85f)
    if (small) {
        Box(Modifier.fillMaxSize().background(body), contentAlignment = Alignment.Center) {
            Text(
                initialsOf(title),
                Modifier.background(sticker, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                color = BQ.Shell, fontFamily = BQ.Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            )
        }
        return
    }
    Column(
        Modifier.fillMaxSize().background(body).padding(8.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        // Ridges along the top edge, like a cartridge.
        Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(8) { Box(Modifier.weight(1f).height(10.dp).background(Color(0x44000000))) }
        }
        Spacer(Modifier.weight(1f))
        if (label.isNotBlank()) {
            Text(
                label.uppercase(), color = text, fontFamily = BQ.Mono, fontSize = 8.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            title,
            Modifier.fillMaxWidth().background(sticker, RoundedCornerShape(3.dp)).padding(6.dp),
            color = BQ.Shell, fontFamily = BQ.Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp,
            lineHeight = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Text field colours that match the console look. */
@Composable
fun bqFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BQ.B,
    unfocusedBorderColor = BQ.Edge,
    focusedTextColor = BQ.Ink,
    unfocusedTextColor = BQ.Ink,
    focusedContainerColor = BQ.Shell,
    unfocusedContainerColor = BQ.Shell,
    cursorColor = BQ.B,
    focusedLabelColor = BQ.B,
    unfocusedLabelColor = BQ.Muted,
    focusedPlaceholderColor = BQ.Muted,
    unfocusedPlaceholderColor = BQ.Muted,
)
