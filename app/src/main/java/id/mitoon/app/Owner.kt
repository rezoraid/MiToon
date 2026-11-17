package id.mitoon.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Palet owner: ungu + putih saja (tanpa warna lain) supaya tampil khas dan tidak ramai. */
val OwnerLilac = Color(0xFFC9B6FF)
val OwnerPurple = Color(0xFF8B5CFF)
val OwnerDeep = Color(0xFF4B2BB8)

/** Bintang empat sudut kecil (kilau). */
private fun sparkle(cx: Float, cy: Float, r: Float): Path = Path().apply {
    moveTo(cx, cy - r)
    quadraticBezierTo(cx, cy, cx + r, cy)
    quadraticBezierTo(cx, cy, cx, cy + r)
    quadraticBezierTo(cx, cy, cx - r, cy)
    quadraticBezierTo(cx, cy, cx, cy - r)
    close()
}

/**
 * Mark Asal (owner): segel heksagon ungu bergradasi, monogram M putih, satu bintang kecil.
 * Efek hanya satu: kilau putih melintas pelan sekali tiap beberapa detik (digambar di fase draw dari [LvClock]).
 */
@Composable
fun OwnerBadge(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    DisposableEffect(Unit) { LvClock.users++; onDispose { LvClock.users-- } }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        val hex = Path().apply {
            val r = s * 0.47f
            for (i in 0..5) {
                val a = (-90.0 + i * 60.0) * PI / 180.0
                val x = c.x + r * cos(a).toFloat(); val y = c.y + r * sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(hex, Brush.linearGradient(listOf(Color(0xFFB79CFF), OwnerPurple, OwnerDeep), Offset(0f, 0f), Offset(s, s)))
        clipPath(hex) {
            // sisi atas lebih terang: memberi kesan permata bersisi
            val facet = Path().apply { moveTo(c.x, 0f); lineTo(s, s * 0.27f); lineTo(c.x, c.y); lineTo(0f, s * 0.27f); close() }
            drawPath(facet, Color.White.copy(alpha = 0.16f))
            val k = cycle(LvClock.ms, 4200) / 0.35f
            if (k < 1f) {
                val fade = minOf(1f, k * 5f, (1f - k) * 5f)
                val band = s * 0.55f
                val x = -band + k * (s + band * 2)
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f * fade), Color.Transparent), Offset(x, 0f), Offset(x + band, s * 0.6f)))
            }
        }
        drawPath(hex, Brush.linearGradient(listOf(Color.White, OwnerLilac, Color.White), Offset(0f, 0f), Offset(s, s)), style = Stroke(s * 0.05f, join = StrokeJoin.Round))
        val m = Path().apply {
            moveTo(s * 0.31f, s * 0.67f); lineTo(s * 0.31f, s * 0.33f); lineTo(s * 0.50f, s * 0.52f)
            lineTo(s * 0.69f, s * 0.33f); lineTo(s * 0.69f, s * 0.67f)
        }
        drawPath(m, Color.White, style = Stroke(s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (s >= 12.dp.toPx()) { // bintang berkedip lembut di pojok kanan atas
            val tw = 0.65f + 0.35f * sin(2f * PI.toFloat() * cycle(LvClock.ms, 2600))
            drawPath(sparkle(s * 0.84f, s * 0.16f, s * 0.15f * tw), Color.White)
        }
    }
}

/** Teks ungu berkilau: pita putih melintas pelan di atas huruf (hanya menyentuh piksel huruf). */
@Composable
fun OwnerText(
    text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 13.sp, fontWeight: FontWeight = FontWeight.Bold,
    maxLines: Int = 1, overflow: TextOverflow = TextOverflow.Ellipsis
) {
    DisposableEffect(Unit) { LvClock.users++; onDispose { LvClock.users-- } }
    Text(
        text, color = OwnerLilac, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines, overflow = overflow,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val k = cycle(LvClock.ms, 3400) / 0.45f
                if (k < 1f) {
                    val w = this.size.width; val h = this.size.height
                    val band = maxOf(h * 3f, w * 0.35f)
                    val x = -band + k * (w + band * 2)
                    drawRect(
                        Brush.linearGradient(listOf(Color.Transparent, Color.White, Color.Transparent), Offset(x, 0f), Offset(x + band, h)),
                        blendMode = BlendMode.SrcAtop
                    )
                }
            }
    )
}

/**
 * Kartu komentar owner: latar ungu tipis + garis tepi ungu-putih + satu kilau lambat.
 * Saat komentar baru saja dikirim ([fresh]) ada efek satu kali: cahaya ungu di sekeliling + beberapa bintang yang memudar.
 */
@Composable
fun Modifier.ownerCard(fresh: Boolean): Modifier {
    DisposableEffect(Unit) { LvClock.users++; onDispose { LvClock.users-- } }
    val boost = remember { Animatable(if (fresh) 1f else 0f) }
    LaunchedEffect(fresh) { if (fresh) { boost.snapTo(1f); boost.animateTo(0f, tween(2000)) } }
    return this
        .drawBehind {
            val b = boost.value
            if (b > 0.01f) {
                val g = 7.dp.toPx() * b
                drawRoundRect(OwnerPurple.copy(alpha = 0.32f * b), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(8.dp.toPx()))
            }
        }
        .clip(R6)
        .background(Brush.horizontalGradient(listOf(OwnerPurple.copy(alpha = 0.20f), OwnerPurple.copy(alpha = 0.06f))))
        .border(1.dp, Brush.linearGradient(listOf(OwnerPurple.copy(alpha = 0.8f), Color.White.copy(alpha = 0.55f), OwnerPurple.copy(alpha = 0.8f))), R6)
        .drawBehind {
            val w = size.width; val h = size.height
            val k = cycle(LvClock.ms, 7000) / 0.25f // kilau lambat: sekali per 7 detik
            if (k < 1f) {
                val fade = minOf(1f, k * 6f, (1f - k) * 6f)
                val band = w * 0.4f
                val x = -band + k * (w + band * 2)
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f * fade), Color.Transparent), Offset(x, 0f), Offset(x + band, h)))
            }
            val b = boost.value
            if (b > 0.01f) {
                val r = 7.dp.toPx() * b
                drawSpark(w * 0.10f, h * 0.18f, r, b)
                drawSpark(w * 0.88f, h * 0.30f, r * 1.2f, b)
                drawSpark(w * 0.55f, h * 0.85f, r * 0.9f, b)
            }
        }
        .padding(10.dp)
}

private fun DrawScope.drawSpark(cx: Float, cy: Float, r: Float, alpha: Float) {
    drawPath(sparkle(cx, cy, r), Color.White.copy(alpha = alpha.coerceIn(0f, 1f)))
}
