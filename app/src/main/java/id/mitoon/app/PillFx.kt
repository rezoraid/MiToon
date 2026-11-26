package id.mitoon.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.SolidColor
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

internal class FlairPad(val x: Float, val top: Float, val bottom: Float)

internal fun flairPower(level: Int): Float = if (level < 50) 0f else 0.25f + 0.75f * (ln(level / 50f) / ln(100f)).coerceIn(0f, 1f)

internal fun flairPad(tier: Int, pw: Float): FlairPad {
    val k = 0.8f + 0.2f * pw
    return when (tier) {
        5 -> FlairPad(0.1f, 0.6f * k, 0f)
        6 -> FlairPad(0.5f * k, 0.1f, 0.05f)
        7 -> FlairPad(0.4f * k, 0.55f * k, 0.15f)
        8 -> FlairPad(1.0f * k, 0.5f * k, 0.2f)
        9 -> FlairPad(1.3f * k, 0.6f * k, 0.2f)
        else -> FlairPad(1.6f * k, 0.8f * k, 0.25f)
    }
}

private val Tau = (2.0 * PI).toFloat()

private val FeatherP = Path().apply { moveTo(0f, 0f); cubicTo(0.25f, -0.55f, 0.8f, -0.5f, 1f, 0f); cubicTo(0.8f, 0.4f, 0.25f, 0.5f, 0f, 0f); close() }
private val FlameP = Path().apply { moveTo(-0.5f, 0f); cubicTo(-0.62f, -0.4f, -0.12f, -0.55f, 0f, -1f); cubicTo(0.12f, -0.55f, 0.62f, -0.4f, 0.5f, 0f); close() }
private val CrystalP = Path().apply { moveTo(-0.5f, 0f); lineTo(-0.38f, -0.72f); lineTo(0f, -1f); lineTo(0.38f, -0.72f); lineTo(0.5f, 0f); close() }
private val CrystalFacet = Path().apply { moveTo(0f, -1f); lineTo(0.38f, -0.72f); lineTo(0.5f, 0f); lineTo(0f, 0f); close() }
private val SparkP = Path().apply {
    moveTo(0f, -1f); cubicTo(0.05f, -0.3f, 0.3f, -0.05f, 1f, 0f); cubicTo(0.3f, 0.05f, 0.05f, 0.3f, 0f, 1f)
    cubicTo(-0.05f, 0.3f, -0.3f, 0.05f, -1f, 0f); cubicTo(-0.3f, -0.05f, -0.05f, -0.3f, 0f, -1f); close()
}
private val CrownP = Path().apply { moveTo(-0.5f, 0f); lineTo(-0.5f, -0.55f); lineTo(-0.25f, -0.3f); lineTo(0f, -0.85f); lineTo(0.25f, -0.3f); lineTo(0.5f, -0.55f); lineTo(0.5f, 0f); close() }
private val CrescentP: Path = run {
    val a = Path().apply { addOval(Rect(-1f, -1f, 1f, 1f)) }
    val b = Path().apply { addOval(Rect(-0.35f, -1.15f, 1.55f, 0.85f)) }
    Path.combine(PathOperation.Difference, a, b)
}

private fun wave(ms: Long, period: Int, off: Int = 0): Float = 0.5f - 0.5f * cos(Tau * cycle(ms + off, period))

private fun DrawScope.put(p: Path, x: Float, y: Float, sx: Float, sy: Float, deg: Float, b: Brush, a: Float) =
    withTransform({ translate(x, y); rotate(deg, Offset.Zero); scale(sx, sy, Offset.Zero) }) { drawPath(p, b, a) }

private fun DrawScope.spark(x: Float, y: Float, r: Float, c: Color, a: Float) = put(SparkP, x, y, r, r, 0f, SolidColor(c), a)

internal fun DrawScope.drawFlair(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) = when (f.tier) {
    5 -> flames(f, x0, y0, w, h, u, ms)
    6 -> laurel(f, x0, y0, w, h, u, ms)
    else -> space(f, x0, y0, w, h, u, ms)
}

private fun DrawScope.flames(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val n = 3 + (pw * 2).toInt()
    val hot = f.colors.last(); val pale = f.colors.first()
    val a = 0.45f + 0.35f * pw
    val big = u * (0.55f + 0.3f * pw)
    val body = Brush.verticalGradient(listOf(hot.copy(alpha = 0f), hot, pale), -1f, 0f)
    val core = SolidColor(Color(0xFFFFF4E0))
    val baseY = y0 + h * 0.2f
    for (i in 0 until n) {
        val t = i / (n - 1f)
        val hh = big * (0.6f + 0.4f * (1f - abs(t - 0.5f) * 1.4f)) * (0.85f + 0.15f * sin(Tau * cycle(ms + i * 413L, 1800 + i * 240)))
        val x = x0 + w * (0.2f + 0.6f * t)
        put(FlameP, x, baseY, hh * 0.6f, hh, (t - 0.5f) * 12f, body, a)
        put(FlameP, x, baseY, hh * 0.28f, hh * 0.55f, (t - 0.5f) * 12f, core, a * 0.7f)
    }
    for (k in 0 until 2) {
        val ph = cycle(ms + k * 1300L, 3400)
        spark(x0 + w * (0.25f + 0.5f * hash01(k, 11)), y0 - u * (0.1f + ph * 0.5f), u * 0.09f * (1f - ph * 0.5f), pale, (1f - ph) * 0.6f * a)
    }
}

private fun DrawScope.laurel(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val r = h / 2f
    val n = 3 + (pw * 3).toInt()
    val len = u * (0.5f + 0.3f * pw)
    val a = 0.5f + 0.35f * pw
    val gold = Brush.horizontalGradient(listOf(Color(0xFFA67C3A), Color(0xFFDDB96C), Color(0xFFF7E7B8)), 0f, 1f)
    val cx = x0 + w / 2f; val cy = y0 + r
    fun side() {
        val c = Offset(x0 + r, cy)
        for (j in 0 until n) {
            val fr = j / (n - 1f)
            val ang = (115f + fr * 130f) * (PI.toFloat() / 180f)
            val dir = 115f + fr * 130f + (if (j % 2 == 0) 40f else 75f)
            val l = len * (0.75f + 0.25f * sin(PI.toFloat() * fr))
            val sh = 0.85f + 0.15f * sin(Tau * cycle(ms + j * 300L, 3600))
            put(FeatherP, c.x + r * 0.9f * cos(ang), c.y + r * 0.9f * sin(ang), l, l * 0.5f, dir, gold, a * sh)
        }
    }
    side()
    scale(-1f, 1f, Offset(cx, cy)) { side() }
}

private fun DrawScope.crystals(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val big = u * (0.6f + 0.35f * pw)
    val a = 0.65f + 0.3f * pw
    val body = Brush.verticalGradient(listOf(f.colors[0], f.colors[1], f.colors[2]), -1f, 0f)
    val facet = SolidColor(Color.Black.copy(alpha = 0.25f))
    for (i in 0 until 3) {
        val t = i / 2f
        val hh = big * (0.6f + 0.4f * (1f - abs(t - 0.5f) * 2f))
        val x = x0 + w * (0.25f + 0.5f * t)
        val tilt = (t - 0.5f) * 26f
        put(CrystalP, x, y0 + h * 0.4f, hh * 0.5f, hh, tilt, body, a)
        put(CrystalFacet, x, y0 + h * 0.4f, hh * 0.5f, hh, tilt, facet, a)
    }
    val g = cycle(ms, 4200)
    if (g < 0.3f) {
        val k = sin(PI.toFloat() * g / 0.3f)
        spark(x0 + w * (0.3f + 0.4f * hash01((ms / 4200).toInt(), 3)), y0 - big * 0.45f, u * 0.16f * k, Color.White, k * a)
    }
}

private fun DrawScope.glow(c: Offset, rx: Float, ry: Float, col: Color, a: Float) =
    withTransform({ scale(1f, ry / rx, c) }) { drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), Color.Transparent), c, rx), rx, c) }

private fun DrawScope.orbitRing(c: Offset, rx: Float, ry: Float, tilt: Float, col: Color, ms: Long, period: Int, a: Float, sw: Float) =
    withTransform({ rotate(tilt, c) }) {
        drawOval(col.copy(alpha = 0.22f * a), Offset(c.x - rx, c.y - ry), Size(2f * rx, 2f * ry), style = Stroke(sw))
        val head = cycle(ms, period) * Tau
        for (k in 0 until 8) {
            val th = head - k * 0.1f
            drawCircle(Color.White.copy(alpha = (1f - k / 8f) * 0.85f * a), sw * (1.6f - k * 0.12f), Offset(c.x + rx * cos(th), c.y + ry * sin(th)))
        }
    }

private fun DrawScope.planet(c: Offset, r: Float, cols: List<Color>, ring: Color, a: Float) {
    val rw = r * 1.9f; val rh = r * 0.5f
    val tl = Offset(c.x - rw, c.y - rh); val sz = Size(2f * rw, 2f * rh)
    withTransform({ rotate(-18f, c) }) { drawArc(ring.copy(alpha = 0.5f * a), 180f, 180f, false, tl, sz, style = Stroke(r * 0.12f)) }
    drawCircle(Brush.radialGradient(listOf(lerp(cols.first(), Color.White, 0.4f), cols[1], cols.last()), Offset(c.x - r * 0.35f, c.y - r * 0.35f), r * 1.5f), r, c, alpha = a)
    withTransform({ rotate(-18f, c) }) { drawArc(ring.copy(alpha = 0.85f * a), 0f, 180f, false, tl, sz, style = Stroke(r * 0.15f)) }
}

private fun DrawScope.moon(x: Float, y: Float, r: Float, deg: Float, a: Float) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4C9).copy(alpha = 0.25f * a), Color.Transparent), Offset(x, y), r * 2f), r * 2f, Offset(x, y))
    put(CrescentP, x, y, r, r, deg, SolidColor(Color(0xFFFFF1D0)), a)
}

private fun DrawScope.crown(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val cx = x0 + w / 2f
    val cw = u * 0.8f; val ch = u * 0.6f
    val prism = Brush.horizontalGradient(f.colors + f.colors.first(), -0.5f, 0.5f)
    put(CrownP, cx, y0 + h * 0.15f, cw, ch, 0f, prism, 0.85f)
    for ((k, dx) in listOf(-0.5f, 0f, 0.5f).withIndex()) {
        val dy = if (k == 1) -0.85f else -0.55f
        drawCircle(Color.White.copy(alpha = 0.5f + 0.5f * wave(ms, 2400, k * 700)), u * 0.05f, Offset(cx + dx * cw, y0 + h * 0.15f + dy * ch))
    }
}

private fun DrawScope.wings(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val t = f.tier; val pw = f.pw; val cols = f.colors
    val nF = when (t) { 8 -> 3; 9 -> 4; else -> 5 }
    val lMax = u * when (t) { 8 -> 0.9f + 0.3f * pw; 9 -> 1.15f + 0.3f * pw; else -> 1.4f + 0.3f * pw }
    val a = 0.55f + 0.3f * pw
    val cx = x0 + w / 2f
    val ax = x0 + h * 0.5f; val ay = y0 + h * 0.5f
    val sweep = 4f * sin(Tau * cycle(ms, 3200))
    fun wing() {
        for (i in nF - 1 downTo 0) {
            val fr = if (nF == 1) 0f else i / (nF - 1f)
            val flap = 5f * sin(Tau * cycle(ms, 3200) - fr * 0.9f) + sweep
            val ang = 236f - fr * 62f + flap
            val l = lMax * (1f - 0.35f * fr) * (0.96f + 0.04f * sin(Tau * cycle(ms, 3200) - fr))
            val c = if (t >= 10) cycleColor(cols, cycle(ms, 12000) + fr * 0.22f) else cols[1]
            val root = lerp(c, Color.Black, 0.4f)
            val tip = lerp(c, Color.White, 0.6f)
            val b = Brush.horizontalGradient(listOf(root, c, tip), 0f, 1f)
            put(FeatherP, ax, ay, l * 0.88f, l * 0.5f, ang + 9f, b, a * 0.55f)
            put(FeatherP, ax, ay, l, l * 0.56f, ang, b, a)
        }
    }
    wing()
    scale(-1f, 1f, Offset(cx, ay)) { wing() }
}

private fun DrawScope.sparkles(x0: Float, y0: Float, w: Float, h: Float, ex: Float, et: Float, eb: Float, u: Float, ms: Long, n: Int, a: Float, col: (Int) -> Color) {
    for (i in 0 until n) {
        val x = x0 - ex * 0.8f + (w + ex * 1.6f) * hash01(i, 21)
        val y = y0 - et * 0.9f + (h + et * 0.9f + eb * 0.8f) * hash01(i, 22)
        val tw = wave(ms, 2200 + (hash01(i, 24) * 1600f).toInt(), (hash01(i, 23) * 2000f).toInt())
        spark(x, y, u * (0.05f + 0.06f * hash01(i, 25)) * (0.4f + 0.6f * tw), col(i), a * tw * 0.9f)
    }
}

private fun DrawScope.space(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw; val t = f.tier; val cols = f.colors
    val pad = flairPad(t, pw)
    val ex = pad.x * u; val et = pad.top * u; val eb = pad.bottom * u
    val c = Offset(x0 + w / 2f, y0 + h / 2f)
    val a = 0.6f + 0.4f * pw
    val prism: (Int) -> Color = { i -> if (t >= 10) cycleColor(cols, cycle(ms, 12000) + i * 0.12f) else cols[i % cols.size] }
    val rx = w / 2f + minOf(ex, u * 0.6f) * 0.8f
    if (t >= 8) wings(f, x0, y0, w, h, u, ms)
    glow(c, w / 2f + ex, h / 2f + (et + eb) / 2f, if (t >= 10) prism(0) else cols[1], 0.2f * a * (0.75f + 0.25f * wave(ms, 4200)))
    when (t) {
        7 -> crystals(f, x0, y0, w, h, u, ms)
        8 -> {
            orbitRing(c, rx, h / 2f + eb * 0.9f, -7f, cols[0], ms, 6000, a, u * 0.05f)
        }
        9 -> {
            orbitRing(c, rx, h / 2f + eb * 0.9f, 8f, cols[0], ms, 6500, a, u * 0.05f)
            moon(x0 + w * 0.8f, y0 - et * 0.45f, u * 0.22f, -25f, a)
        }
        else -> {
            orbitRing(c, rx, h / 2f + eb * 0.9f, -9f, prism(0), ms, 5600, a, u * 0.05f)
            orbitRing(c, rx * 0.97f, h / 2f + eb * 0.6f, 11f, prism(3), ms, 8200, a, u * 0.04f)
            crown(f, x0, y0, w, h, u, ms)
        }
    }
    sparkles(x0, y0, w, h, ex, et, eb, u, ms, 3 + (t - 7) + (pw * 2f).toInt(), a) { i -> if (i % 3 == 0) prism(i) else Color.White }
}
