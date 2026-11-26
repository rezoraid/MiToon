package id.mitoon.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

internal class Flair(val tier: Int, val pw: Float, val colors: List<Color>)

internal fun flairPower(level: Int): Float = if (level < 50) 0f else 0.25f + 0.75f * (ln(level / 50f) / ln(100f)).coerceIn(0f, 1f)

private val Tau = (2.0 * PI).toFloat()
private val Ink = Color(0xFF1A1424)

private val FeatherP = Path().apply { moveTo(0f, 0f); cubicTo(0.15f, -0.3f, 0.65f, -0.32f, 1f, 0f); cubicTo(0.7f, 0.1f, 0.3f, 0.2f, 0f, 0f); close() }
private val LeafP = Path().apply { moveTo(0f, 0f); cubicTo(0.25f, -0.3f, 0.75f, -0.28f, 1f, 0f); cubicTo(0.75f, 0.28f, 0.25f, 0.3f, 0f, 0f); close() }
private val VeinP = Path().apply { moveTo(0.06f, -0.01f); lineTo(0.9f, -0.02f) }
private val FlameP = Path().apply { moveTo(-0.5f, 0f); cubicTo(-0.62f, -0.4f, -0.12f, -0.55f, 0f, -1f); cubicTo(0.12f, -0.55f, 0.62f, -0.4f, 0.5f, 0f); close() }
private val CrystalP = Path().apply { moveTo(-0.5f, 0f); lineTo(-0.38f, -0.72f); lineTo(0f, -1f); lineTo(0.38f, -0.72f); lineTo(0.5f, 0f); close() }
private val CrystalFacet = Path().apply { moveTo(0f, -1f); lineTo(0.38f, -0.72f); lineTo(0.5f, 0f); lineTo(0f, 0f); close() }
private val CrownP = Path().apply { moveTo(-0.5f, 0f); lineTo(-0.5f, -0.55f); lineTo(-0.25f, -0.3f); lineTo(0f, -0.85f); lineTo(0.25f, -0.3f); lineTo(0.5f, -0.55f); lineTo(0.5f, 0f); close() }

private fun DrawScope.put(p: Path, x: Float, y: Float, sx: Float, sy: Float, deg: Float, fill: Brush, line: Color, lw: Float, vein: Color? = null) =
    withTransform({ translate(x, y); rotate(deg, Offset.Zero); scale(sx, sy, Offset.Zero) }) {
        val k = 2f / (sx + sy)
        drawPath(p, fill)
        if (lw > 0f) drawPath(p, SolidColor(line), style = Stroke(lw * k, join = StrokeJoin.Round))
        if (vein != null) drawPath(VeinP, SolidColor(vein), 0.55f, Stroke(lw * 0.8f * k, cap = StrokeCap.Round))
    }

internal fun DrawScope.drawFlair(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    when (f.tier) {
        5 -> flames(f, x0, y0, w, h, u, ms)
        6 -> laurel(f, x0, y0, w, h, u, ms)
        7 -> crystals(f, x0, y0, w, h, u)
        8 -> wings(f, x0, y0, w, h, u, ms)
        9 -> { wings(f, x0, y0, w, h, u, ms); halo(f, x0, y0, w, h, u, ms) }
        else -> { wings(f, x0, y0, w, h, u, ms); crown(f, x0, y0, w, h, u) }
    }
}

private fun DrawScope.flames(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val n = 3 + (pw * 2).toInt()
    val hot = f.colors.last(); val pale = f.colors.first()
    val line = lerp(hot, Color.Black, 0.55f)
    val big = u * (0.95f + 0.4f * pw)
    val body = Brush.verticalGradient(listOf(hot, lerp(hot, pale, 0.6f), pale), -1f, 0f)
    val core = SolidColor(Color(0xFFFFF1D6))
    val baseY = y0 + h * 0.45f
    for (i in 0 until n) {
        val t = i / (n - 1f)
        val hh = big * (0.65f + 0.35f * (1f - abs(t - 0.5f) * 2f))
        val deg = (t - 0.5f) * 16f + 4f * sin(Tau * cycle(ms + i * 500L, 2600))
        val x = x0 + w * (0.2f + 0.6f * t)
        put(FlameP, x, baseY, hh * 0.62f, hh, deg, body, line, u * 0.08f)
        put(FlameP, x, baseY, hh * 0.26f, hh * 0.5f, deg, core, line, 0f)
    }
}

private fun DrawScope.laurel(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw; val cols = f.colors
    val r = h / 2f
    val n = 5 + (pw * 2).toInt()
    val len = u * (0.85f + 0.35f * pw)
    val fill = Brush.horizontalGradient(listOf(cols[2], cols[1], cols[0]), 0f, 1f)
    val line = lerp(cols[2], Color.Black, 0.5f)
    val vein = lerp(cols[0], Color.White, 0.4f)
    val cx = x0 + w / 2f; val cy = y0 + r
    fun side() {
        val c = Offset(x0 + r, cy)
        for (j in 0 until n) {
            val fr = j / (n - 1f)
            val a0 = 110f + fr * 140f
            val ang = a0 * (PI.toFloat() / 180f)
            val l = len * (0.7f + 0.3f * sin(PI.toFloat() * fr))
            val deg = a0 + (if (j % 2 == 0) 40f else 80f) + 3f * sin(Tau * cycle(ms, 4200) - fr * 1.2f)
            put(LeafP, c.x + r * 0.95f * cos(ang), c.y + r * 0.95f * sin(ang), l, l, deg, fill, line, u * 0.07f, vein)
        }
    }
    side()
    scale(-1f, 1f, Offset(cx, cy)) { side() }
}

private fun DrawScope.crystals(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float) {
    val cols = f.colors
    val big = u * (1.0f + 0.45f * f.pw)
    val body = Brush.verticalGradient(listOf(cols[0], cols[1], cols[2]), -1f, 0f)
    val facet = SolidColor(Color.Black.copy(alpha = 0.22f))
    val line = lerp(cols[2], Color.Black, 0.55f)
    fun cr(x: Float, y: Float, hh: Float, deg: Float) {
        put(CrystalP, x, y, hh * 0.5f, hh, deg, body, line, u * 0.07f)
        put(CrystalFacet, x, y, hh * 0.5f, hh, deg, facet, line, 0f)
    }
    for (i in 0 until 3) {
        val t = i / 2f
        cr(x0 + w * (0.22f + 0.56f * t), y0 + h * 0.5f, big * (0.65f + 0.35f * (1f - abs(t - 0.5f) * 2f)), (t - 0.5f) * 30f)
    }
    cr(x0 + h * 0.1f, y0 + h * 0.55f, big * 0.5f, -70f)
    cr(x0 + w - h * 0.1f, y0 + h * 0.55f, big * 0.5f, 70f)
}

private fun DrawScope.wings(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val t = f.tier; val pw = f.pw; val cols = f.colors
    val nF = when (t) { 8 -> 4; 9 -> 5; else -> 6 }
    val lMax = u * when (t) { 8 -> 1.2f + 0.4f * pw; 9 -> 1.6f + 0.4f * pw; else -> 2.0f + 0.4f * pw }
    val cx = x0 + w / 2f
    val ax = x0 + h * 0.5f; val ay = y0 + h * 0.5f
    val lw = u * 0.07f
    val ph = Tau * cycle(ms, 3600)
    fun wing() {
        for (row in 0..1) for (i in nF - 1 downTo 0) {
            val fr = i / (nF - 1f)
            val ang = 238f - fr * 66f + 5f * sin(ph - fr * 0.8f - row * 0.4f) + row * 6f
            val l = lMax * (1f - 0.38f * fr) * (if (row == 0) 1f else 0.55f)
            val c = if (t >= 10) cycleColor(cols, cycle(ms, 14000) + fr * 0.2f) else cols[1]
            val base = if (row == 0) c else lerp(c, Color.White, 0.22f)
            val fill = Brush.horizontalGradient(listOf(lerp(base, Color.Black, 0.35f), base, lerp(base, Color.White, 0.4f)), 0f, 1f)
            put(FeatherP, ax, ay, l, l, ang, fill, lerp(c, Color.Black, 0.6f), lw, Color.White)
        }
    }
    wing()
    scale(-1f, 1f, Offset(cx, ay)) { wing() }
}

private fun DrawScope.halo(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val cols = f.colors
    val rx = h * 0.7f; val ry = u * 0.2f
    val cx = x0 + w / 2f; val cy = y0 - u * 0.15f + sin(Tau * cycle(ms, 3600)) * u * 0.04f
    drawOval(lerp(cols[2], Color.Black, 0.5f), Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry), style = Stroke(u * 0.2f))
    drawOval(Brush.horizontalGradient(listOf(cols[1], cols[0], cols[1]), cx - rx, cx + rx), Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry), style = Stroke(u * 0.11f))
}

private fun DrawScope.crown(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float) {
    val cx = x0 + w / 2f
    val cw = u * 1.05f; val ch = u * 0.85f
    val by = y0 + h * 0.2f
    put(CrownP, cx, by, cw, ch, 0f, Brush.horizontalGradient(f.colors + f.colors.first(), -0.5f, 0.5f), Ink, u * 0.07f)
    for ((k, dx) in listOf(-0.5f, 0f, 0.5f).withIndex()) drawCircle(Color.White, u * 0.06f, Offset(cx + dx * cw, by + (if (k == 1) -0.85f else -0.55f) * ch))
}
