package id.mitoon.app

import androidx.compose.ui.geometry.Offset
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
private val PetalLine = Color(0xFFA83A62)
private val Pink = Color(0xFFFF8FB1)
private val PinkPale = Color(0xFFFFE9F0)
private val Gold = listOf(Color(0xFFFFF1B8), Color(0xFFF2C14E), Color(0xFFB98322))
private val Steel = listOf(Color(0xFFF2F6FF), Color(0xFFAFC0DA), Color(0xFF5E6E8C))

private val FeatherP = Path().apply { moveTo(0f, 0f); cubicTo(0.15f, -0.3f, 0.65f, -0.32f, 1f, 0f); cubicTo(0.7f, 0.1f, 0.3f, 0.2f, 0f, 0f); close() }
private val LeafP = Path().apply { moveTo(0f, 0f); cubicTo(0.25f, -0.3f, 0.75f, -0.28f, 1f, 0f); cubicTo(0.75f, 0.28f, 0.25f, 0.3f, 0f, 0f); close() }
private val VeinP = Path().apply { moveTo(0.06f, -0.01f); lineTo(0.9f, -0.02f) }
private val FlameP = Path().apply { moveTo(-0.5f, 0f); cubicTo(-0.62f, -0.4f, -0.12f, -0.55f, 0f, -1f); cubicTo(0.12f, -0.55f, 0.62f, -0.4f, 0.5f, 0f); close() }
private val CrystalP = Path().apply { moveTo(-0.5f, 0f); lineTo(-0.38f, -0.72f); lineTo(0f, -1f); lineTo(0.38f, -0.72f); lineTo(0.5f, 0f); close() }
private val CrystalFacet = Path().apply { moveTo(0f, -1f); lineTo(0.38f, -0.72f); lineTo(0.5f, 0f); lineTo(0f, 0f); close() }
private val CrownP = Path().apply { moveTo(-0.5f, 0f); lineTo(-0.5f, -0.55f); lineTo(-0.25f, -0.3f); lineTo(0f, -0.85f); lineTo(0.25f, -0.3f); lineTo(0.5f, -0.55f); lineTo(0.5f, 0f); close() }
private val SparkleP = Path().apply { moveTo(0f, -1f); quadraticTo(0.1f, -0.1f, 1f, 0f); quadraticTo(0.1f, 0.1f, 0f, 1f); quadraticTo(-0.1f, 0.1f, -1f, 0f); quadraticTo(-0.1f, -0.1f, 0f, -1f); close() }
private val PetalP = Path().apply { moveTo(0f, 0f); cubicTo(0.25f, -0.55f, 0.95f, -0.5f, 1f, -0.14f); lineTo(0.86f, 0f); lineTo(1f, 0.14f); cubicTo(0.95f, 0.5f, 0.25f, 0.55f, 0f, 0f); close() }
private val BladeP = Path().apply { moveTo(0f, -0.09f); cubicTo(0.4f, -0.13f, 0.8f, -0.13f, 1f, 0f); cubicTo(0.75f, 0.04f, 0.4f, 0.09f, 0f, 0.09f); close() }
private val GuardP = Path().apply { moveTo(0.1f, -0.2f); lineTo(0.17f, -0.2f); lineTo(0.17f, 0.2f); lineTo(0.1f, 0.2f); close() }
private val CrestP = Path().apply { moveTo(-1f, -0.6f); cubicTo(-0.95f, 0.1f, -0.45f, 0.35f, 0f, 0.35f); cubicTo(0.45f, 0.35f, 0.95f, 0.1f, 1f, -0.6f); cubicTo(0.62f, -0.08f, 0.32f, 0.02f, 0f, 0.02f); cubicTo(-0.32f, 0.02f, -0.62f, -0.08f, -1f, -0.6f); close() }
private val PlumeP = Path().apply { moveTo(0f, 0f); cubicTo(0.3f, -0.35f, 0.6f, 0.3f, 1f, 0f); cubicTo(0.65f, 0.42f, 0.3f, -0.12f, 0f, 0f); close() }

private fun DrawScope.put(p: Path, x: Float, y: Float, sx: Float, sy: Float, deg: Float, fill: Brush, line: Color, lw: Float, vein: Color? = null, al: Float = 1f) =
    withTransform({ translate(x, y); rotate(deg, Offset.Zero); scale(sx, sy, Offset.Zero) }) {
        val k = 2f / (sx + sy)
        drawPath(p, fill, al)
        if (lw > 0f) drawPath(p, SolidColor(line), al, Stroke(lw * k, join = StrokeJoin.Round))
        if (vein != null) drawPath(VeinP, SolidColor(vein), 0.55f * al, Stroke(lw * 0.8f * k, cap = StrokeCap.Round))
    }

internal fun DrawScope.glint(x: Float, y: Float, r: Float, fill: Color, line: Color) = put(SparkleP, x, y, r, r, 0f, SolidColor(fill), line, r * 0.16f)

private fun DrawScope.sparkle(x: Float, y: Float, r: Float, fill: Color, line: Color, ms: Long, seed: Int) {
    val tw = 0.5f - 0.5f * cos(Tau * cycle(ms + seed * 700L, 2400 + seed * 350))
    val k = 0.7f + 0.3f * tw
    put(SparkleP, x, y, r * k, r * k, 0f, SolidColor(fill), line, r * k * 0.16f)
}

private fun DrawScope.blossom(x: Float, y: Float, r: Float, rot: Float) {
    val fill = Brush.horizontalGradient(listOf(Pink, PinkPale), 0f, 1f)
    for (k in 0 until 5) put(PetalP, x, y, r, r, rot + k * 72f, fill, PetalLine, r * 0.1f)
    drawCircle(Color(0xFFFFD36B), r * 0.2f, Offset(x, y))
    drawCircle(PetalLine, r * 0.2f, Offset(x, y), style = Stroke(r * 0.06f))
}

internal fun DrawScope.flairBack(f: Flair, w: Float, h: Float, u: Float, ms: Long) {
    when (f.tier) {
        0 -> sprout(f, h, u, ms)
        3 -> blades(w, h, u)
        4 -> fallingPetals(w, h, u, ms, 3)
        2 -> fallingPetals(w, h, u, ms, 2)
        5 -> { flames(f, w, h, u, ms); embers(w, u, ms) }
        6 -> { crest(w, h, u); laurel(f, w, h, u, ms) }
        7 -> crystals(f, w, h, u)
        8 -> wings(f, w, h, u, ms)
        9 -> { wings(f, w, h, u, ms); plumes(f, w, h, u, ms); phoenixCrest(w, h, u, ms) }
        10 -> { wings(f, w, h, u, ms); crown(f, w, h, u); fallingPetals(w, h, u, ms, 2) }
    }
}

internal fun DrawScope.flairFront(f: Flair, w: Float, h: Float, u: Float, ms: Long) {
    val cols = f.colors
    when (f.tier) {
        1 -> { sparkle(w - u * 0.35f, h * 0.0f, u * 0.85f, Color(0xFFE6FBFF), Color(0xFF14509A), ms, 0); sparkle(u * 0.3f, h * 0.95f, u * 0.55f, Color.White, Color(0xFF14509A), ms, 1); sparkle(w * 0.45f, -u * 0.05f, u * 0.4f, Color(0xFFBFF3FF), Color(0xFF14509A), ms, 2) }
        2 -> { blossom(w - u * 0.25f, h * 0.08f, u * 0.85f, 12f); blossom(u * 0.2f, h * 0.95f, u * 0.5f, -25f) }
        4 -> { blossom(u * 0.3f, h * 0.1f, u * 0.78f, -10f); blossom(w - u * 0.2f, h * 0.92f, u * 0.62f, 20f) }
        5 -> sparkle(w - u * 0.3f, h * 0.06f, u * 0.5f, Color(0xFFFFF1D6), Color(0xFF8A3A12), ms, 0)
        6 -> sparkle(w - u * 0.3f, h * 0.05f, u * 0.6f, Gold[0], Color(0xFF6A4A12), ms, 0)
        7 -> { sparkle(w - u * 0.3f, h * 0.05f, u * 0.55f, Color.White, lerp(cols[2], Color.Black, 0.5f), ms, 0); sparkle(u * 0.3f, h * 0.95f, u * 0.4f, Color(0xFFD9FFFA), lerp(cols[2], Color.Black, 0.5f), ms, 1) }
        8 -> { sparkle(w / 2f, -u * 0.1f, u * 0.5f, Color.White, lerp(cols[2], Color.Black, 0.4f), ms, 0); sparkle(w - u * 0.3f, h * 0.95f, u * 0.38f, Color(0xFFDCE4FF), lerp(cols[2], Color.Black, 0.4f), ms, 2) }
        9 -> { sparkle(u * 0.3f, h * 0.06f, u * 0.5f, Color(0xFFFFE9B0), Color(0xFF5A1630), ms, 0); sparkle(w - u * 0.3f, h * 0.94f, u * 0.45f, Color.White, Color(0xFF5A1630), ms, 1) }
        10 -> for (i in 0 until 3) sparkle(
            if (i == 0) u * 0.3f else if (i == 1) w - u * 0.3f else w * 0.5f,
            if (i == 2) h * 1.0f else h * 0.08f,
            u * (if (i == 2) 0.38f else 0.55f), lerp(cycleColor(cols, cycle(ms, 9000) + i * 0.25f), Color.White, 0.6f), Ink, ms, i)
    }
}

private fun DrawScope.sprout(f: Flair, h: Float, u: Float, ms: Long) {
    val fill = Brush.horizontalGradient(listOf(f.colors[1], Color(0xFF8FE3A8), Color(0xFFD9FFE4)), 0f, 1f)
    val line = Color(0xFF1F5A3A)
    val sw = 3f * sin(Tau * cycle(ms, 3400))
    put(LeafP, h * 0.62f, h * 0.3f, u * 1.3f, u * 1.3f, -128f + sw, fill, line, u * 0.1f, Color.White)
    put(LeafP, h * 0.62f, h * 0.3f, u * 1.5f, u * 1.5f, -58f - sw, fill, line, u * 0.1f, Color.White)
    put(LeafP, h * 1.5f, h * 0.2f, u * 1.0f, u * 1.0f, -50f + sw, fill, line, u * 0.1f, Color.White)
}

private fun DrawScope.blades(w: Float, h: Float, u: Float) {
    val steel = Brush.verticalGradient(listOf(Steel[0], Steel[1], Steel[2]), -0.12f, 0.12f)
    val line = Color(0xFF1B2233)
    val gold = SolidColor(Color(0xFFE6B84A))
    val len = u * 2.2f; val a = 56f; val cy = h / 2f
    for (s in intArrayOf(-1, 1)) {
        val dl = 180f - s * a; val dr = s * a
        put(BladeP, h * 0.55f, cy, len, len * 0.95f, dl, steel, line, u * 0.08f)
        put(GuardP, h * 0.55f, cy, len, len, dl, gold, line, u * 0.05f)
        put(BladeP, w - h * 0.55f, cy, len, len * 0.95f, dr, steel, line, u * 0.08f)
        put(GuardP, w - h * 0.55f, cy, len, len, dr, gold, line, u * 0.05f)
    }
}

private fun DrawScope.fallingPetals(w: Float, h: Float, u: Float, ms: Long, n: Int) {
    val fill = Brush.horizontalGradient(listOf(Pink, PinkPale), 0f, 1f)
    for (i in 0 until n) {
        val p = cycle(ms + i * 1700L, 5400 + i * 700)
        val x = w * (0.12f + 0.76f * hash01(i, 21)) + u * 0.5f * sin(Tau * p * 1.5f + i)
        val y = -u * 0.7f + (h + u * 1.4f) * p
        put(PetalP, x, y, u * 0.42f, u * 0.42f, 360f * p * 1.3f + i * 60f, fill, PetalLine, u * 0.05f, null, minOf(1f, p * 8f, (1f - p) * 8f))
    }
}

private fun DrawScope.flames(f: Flair, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val n = 4 + (pw * 2).toInt()
    val hot = f.colors.last(); val pale = f.colors.first()
    val tip = lerp(hot, Color(0xFFFF4D6D), 0.4f)
    val line = lerp(hot, Color.Black, 0.6f)
    val big = u * (2.2f + 0.3f * pw)
    val body = Brush.verticalGradient(listOf(tip, lerp(hot, pale, 0.5f), pale), -1f, 0f)
    val core = SolidColor(Color(0xFFFFF1D6))
    val baseY = h * 0.6f
    for (i in 0 until n) {
        val t = i / (n - 1f)
        val hh = big * (0.65f + 0.35f * (1f - abs(t - 0.5f) * 2f))
        val deg = (t - 0.5f) * 16f + 4f * sin(Tau * cycle(ms + i * 500L, 2600))
        val x = w * (0.14f + 0.72f * t)
        put(FlameP, x, baseY, hh * 0.5f, hh, deg, body, line, u * 0.1f)
        put(FlameP, x, baseY, hh * 0.21f, hh * 0.5f, deg, core, line, 0f)
    }
}

private fun DrawScope.embers(w: Float, u: Float, ms: Long) {
    for (i in 0 until 3) {
        val p = cycle(ms + i * 900L, 2700)
        val a = (1f - p) * (1f - p)
        drawCircle(Color(0xFFFFE2A8).copy(alpha = a), u * 0.075f * (1f - 0.5f * p), Offset(w * (0.2f + 0.6f * hash01(i, 5)) + sin(Tau * p + i) * u * 0.2f, -u * 0.1f - u * 0.9f * p))
    }
}

private fun DrawScope.crest(w: Float, h: Float, u: Float) {
    val cx = w / 2f
    val g = Brush.verticalGradient(listOf(Gold[0], Gold[1], Gold[2]), -0.6f, 0.35f)
    put(CrestP, cx, h * 0.12f, u * 1.35f, u * 1.5f, 0f, g, Color(0xFF4A3410), u * 0.09f)
    drawCircle(Color(0xFFE23B3B), u * 0.2f, Offset(cx, h * 0.12f - u * 0.4f))
    drawCircle(Color(0xFF4A1010), u * 0.2f, Offset(cx, h * 0.12f - u * 0.4f), style = Stroke(u * 0.06f))
}

private fun DrawScope.laurel(f: Flair, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw; val cols = f.colors
    val r = h / 2f
    val n = 5 + (pw * 2).toInt()
    val len = u * (0.85f + 0.35f * pw)
    val fill = Brush.horizontalGradient(listOf(cols[2], cols[1], cols[0]), 0f, 1f)
    val line = lerp(cols[2], Color.Black, 0.55f)
    val vein = lerp(cols[0], Color.White, 0.4f)
    val cx = w / 2f
    fun side() {
        for (j in 0 until n) {
            val fr = j / (n - 1f)
            val a0 = 110f + fr * 140f
            val ang = a0 * (PI.toFloat() / 180f)
            val l = len * (0.7f + 0.3f * sin(PI.toFloat() * fr))
            val deg = a0 + (if (j % 2 == 0) 40f else 80f) + 3f * sin(Tau * cycle(ms, 4200) - fr * 1.2f)
            put(LeafP, r + r * 0.95f * cos(ang), r + r * 0.95f * sin(ang), l, l, deg, fill, line, u * 0.09f, vein)
        }
    }
    side()
    scale(-1f, 1f, Offset(cx, r)) { side() }
}

private fun DrawScope.crystals(f: Flair, w: Float, h: Float, u: Float) {
    val cols = f.colors
    val big = u * (1.55f + 0.4f * f.pw)
    val body = Brush.verticalGradient(listOf(cols[0], cols[1], cols[2]), -1f, 0f)
    val facet = SolidColor(Color.Black.copy(alpha = 0.22f))
    val line = lerp(cols[2], Color.Black, 0.6f)
    fun cr(x: Float, y: Float, hh: Float, deg: Float) {
        put(CrystalP, x, y, hh * 0.5f, hh, deg, body, line, u * 0.09f)
        put(CrystalFacet, x, y, hh * 0.5f, hh, deg, facet, line, 0f)
    }
    for (i in 0 until 3) {
        val t = i / 2f
        cr(w * (0.18f + 0.64f * t), h * 0.6f, big * (0.65f + 0.35f * (1f - abs(t - 0.5f) * 2f)), (t - 0.5f) * 30f)
    }
    cr(h * 0.1f, h * 0.6f, big * 0.55f, -68f)
    cr(w - h * 0.1f, h * 0.6f, big * 0.55f, 68f)
}

private fun DrawScope.wings(f: Flair, w: Float, h: Float, u: Float, ms: Long) {
    val t = f.tier; val pw = f.pw; val cols = f.colors
    val nF = when (t) { 8 -> 4; 9 -> 5; else -> 6 }
    val lMax = u * when (t) { 8 -> 1.8f + 0.4f * pw; 9 -> 2.15f + 0.4f * pw; else -> 2.5f + 0.25f * pw }
    val cx = w / 2f
    val ax = h * 0.5f; val ay = h * 0.55f
    val lw = u * 0.1f
    val ph = Tau * cycle(ms, 3600)
    fun wing() {
        for (row in 0..1) for (i in nF - 1 downTo 0) {
            val fr = i / (nF - 1f)
            val ang = 240f - fr * 70f + 5f * sin(ph - fr * 0.8f - row * 0.4f) + row * 6f
            val l = lMax * (1f - 0.38f * fr) * (if (row == 0) 1f else 0.55f)
            val c = when (t) { 10 -> cycleColor(cols, cycle(ms, 14000) + fr * 0.2f); 9 -> lerp(cols[1], Color(0xFFFFB347), fr * 0.75f); else -> cols[1] }
            val base = if (row == 0) c else lerp(c, Color.White, 0.22f)
            val fill = Brush.horizontalGradient(listOf(lerp(base, Color.Black, 0.35f), base, lerp(base, Color.White, 0.4f)), 0f, 1f)
            put(FeatherP, ax, ay, l, l, ang, fill, lerp(c, Color.Black, 0.65f), lw, Color.White)
        }
    }
    wing()
    scale(-1f, 1f, Offset(cx, ay)) { wing() }
}

private fun DrawScope.plumes(f: Flair, w: Float, h: Float, u: Float, ms: Long) {
    val cols = f.colors
    val g = Brush.horizontalGradient(listOf(cols[2], cols[1], Color(0xFFFFD36B)), 0f, 1f)
    val line = lerp(cols[2], Color.Black, 0.6f)
    val sw = 5f * sin(Tau * cycle(ms, 3000))
    for ((deg, len) in listOf(112f to 1.9f, 138f to 2.4f)) {
        put(PlumeP, h * 0.55f, h * 0.5f, u * len, u * len, deg + sw, g, line, u * 0.09f)
        put(PlumeP, w - h * 0.55f, h * 0.5f, u * len, u * len, 180f - deg - sw, g, line, u * 0.09f)
    }
}

private fun DrawScope.phoenixCrest(w: Float, h: Float, u: Float, ms: Long) {
    val body = Brush.verticalGradient(listOf(Color(0xFFFF5C8A), Color(0xFFFFB347), Color(0xFFFFF0B8)), -1f, 0f)
    for (k in -1..1) {
        val hh = u * (if (k == 0) 1.8f else 1.3f)
        put(FlameP, w / 2f + k * u * 0.6f, h * 0.45f, hh * 0.55f, hh, k * 18f + 5f * sin(Tau * cycle(ms + k * 400L, 2200)), body, Color(0xFF5A1630), u * 0.08f)
    }
}

private fun DrawScope.crown(f: Flair, w: Float, h: Float, u: Float) {
    val cx = w / 2f
    val cw = u * 1.05f; val ch = u * 0.85f
    val by = h * 0.2f
    put(CrownP, cx, by, cw, ch, 0f, Brush.horizontalGradient(f.colors + f.colors.first(), -0.5f, 0.5f), Ink, u * 0.09f)
    for ((k, dx) in listOf(-0.5f, 0f, 0.5f).withIndex()) drawCircle(Color.White, u * 0.07f, Offset(cx + dx * cw, by + (if (k == 1) -0.85f else -0.55f) * ch))
}
