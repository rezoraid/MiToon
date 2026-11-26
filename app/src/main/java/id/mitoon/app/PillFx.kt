package id.mitoon.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin

/**
 * Hiasan yang "keluar" dari pill level, mulai Lv. 50. Satu tema per peran, makin tinggi level makin besar, rapat, dan terang:
 *  - Series Master (50)   api kecil menyala di atas pill + percikan naik
 *  - Sensei (100)         karangan daun emas di kedua ujung
 *  - Curator (500)        kristal zamrud tumbuh dari atas dan sisi
 *  - Legend (1000)        sepasang sayap safir
 *  - Mythic (3000)        sayap ametis + lingkaran cahaya + bintang mengorbit
 *  - Eternal (5000)       mahkota prisma + sayap pelangi + bintang mengorbit
 * Semua digambar di belakang pill (jadi tampak tumbuh dari baliknya) dan memakai jam bersama [LvClock].
 */
internal class Flair(val tier: Int, val pw: Float, val colors: List<Color>)

/** Ruang tambahan di sekeliling pill, dalam kelipatan ukuran teks (u). */
internal class FlairPad(val x: Float, val top: Float)

/** 0 di bawah Lv. 50 (tidak ada hiasan); Lv. 50 = 0,25 (tipis) naik mulus ke 1,0 di Lv. 5000. */
internal fun flairPower(level: Int): Float =
    if (level < 50) 0f else 0.25f + 0.75f * (ln(level / 50f) / ln(100f)).coerceIn(0f, 1f)

internal fun flairPad(tier: Int, pw: Float): FlairPad = when (tier) {
    5 -> FlairPad(0.15f, (0.5f + 0.6f * pw) * 0.65f)
    6 -> FlairPad(0.2f + (0.45f + 0.45f * pw) * 0.9f, 0.1f)
    7 -> FlairPad(0.15f + 0.3f * pw, (0.55f + 0.7f * pw) * 0.65f)
    8 -> FlairPad((0.9f + 0.9f * pw) * 0.85f, 0.15f)
    9 -> FlairPad((1.1f + 1.0f * pw) * 0.85f, 0.6f)
    else -> FlairPad((1.2f + 1.2f * pw) * 0.85f, 0.8f)
}

private val Tau = (2.0 * PI).toFloat()

// Bentuk dasar bernilai satuan; diskalakan saat digambar supaya tidak membuat Path baru tiap frame.
/** Bulu/daun: pangkal di (0,0), ujung di (1,0). */
private val FeatherP = Path().apply {
    moveTo(0f, 0f); cubicTo(0.25f, -0.55f, 0.8f, -0.5f, 1f, 0f); cubicTo(0.8f, 0.4f, 0.25f, 0.5f, 0f, 0f); close()
}
/** Lidah api: pangkal di (0,0), ujung di (0,-1). */
private val FlameP = Path().apply {
    moveTo(-0.5f, 0f); cubicTo(-0.62f, -0.4f, -0.12f, -0.55f, 0f, -1f); cubicTo(0.12f, -0.55f, 0.62f, -0.4f, 0.5f, 0f); close()
}
private val CrystalP = Path().apply {
    moveTo(-0.5f, 0f); lineTo(-0.38f, -0.72f); lineTo(0f, -1f); lineTo(0.38f, -0.72f); lineTo(0.5f, 0f); close()
}
private val CrystalFacet = Path().apply {
    moveTo(0f, -1f); lineTo(0.38f, -0.72f); lineTo(0.5f, 0f); lineTo(0f, 0f); close()
}
/** Kilau empat arah. */
private val SparkP = Path().apply {
    moveTo(0f, -1f); cubicTo(0.05f, -0.3f, 0.3f, -0.05f, 1f, 0f); cubicTo(0.3f, 0.05f, 0.05f, 0.3f, 0f, 1f)
    cubicTo(-0.05f, 0.3f, -0.3f, 0.05f, -1f, 0f); cubicTo(-0.3f, -0.05f, -0.05f, -0.3f, 0f, -1f); close()
}
/** Mahkota: alas di y=0, lebar -0,5..0,5. */
private val CrownP = Path().apply {
    moveTo(-0.5f, 0f); lineTo(-0.5f, -0.55f); lineTo(-0.25f, -0.3f); lineTo(0f, -0.85f); lineTo(0.25f, -0.3f); lineTo(0.5f, -0.55f); lineTo(0.5f, 0f); close()
}

private fun DrawScope.put(p: Path, x: Float, y: Float, sx: Float, sy: Float, deg: Float, b: Brush, a: Float) =
    withTransform({ translate(x, y); rotate(deg, Offset.Zero); scale(sx, sy, Offset.Zero) }) { drawPath(p, b, a) }

private fun DrawScope.spark(x: Float, y: Float, r: Float, c: Color, a: Float) = put(SparkP, x, y, r, r, 0f, SolidColor(c), a)

/** Gambar hiasan di sekitar pill yang menempati (x0, y0, w, h); [u] = ukuran teks dalam px. */
internal fun DrawScope.drawFlair(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    when (f.tier) {
        5 -> flames(f, x0, y0, w, h, u, ms)
        6 -> laurel(f, x0, y0, w, h, u, ms)
        7 -> crystals(f, x0, y0, w, h, u, ms)
        else -> {
            wings(f, x0, y0, w, h, u, ms)
            if (f.tier == 9) halo(f, x0, y0, w, h, u, ms) else if (f.tier >= 10) crown(f, x0, y0, w, h, u, ms)
            if (f.tier >= 9) orbiters(f, x0, y0, w, h, u, ms)
        }
    }
}

private fun DrawScope.flames(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val n = 3 + (pw * 3).toInt()
    val hot = f.colors.last(); val pale = f.colors.first()
    val a = 0.4f + 0.55f * pw
    val big = u * (0.5f + 0.6f * pw)
    val body = Brush.verticalGradient(listOf(hot.copy(alpha = 0f), hot, pale), -1f, 0f)
    val core = SolidColor(Color(0xFFFFF4C9))
    val baseY = y0 + h * 0.28f
    for (i in 0 until n) {
        val t = i / (n - 1f)
        val mid = 1f - abs(t - 0.5f) * 1.2f
        val flick = 0.78f + 0.22f * sin(Tau * cycle(ms + i * 413L, 900 + i * 170))
        val hh = big * (0.55f + 0.45f * mid) * flick
        val x = x0 + w * (0.14f + 0.72f * t)
        put(FlameP, x, baseY, hh * 0.62f, hh, (t - 0.5f) * 14f, body, a)
        put(FlameP, x, baseY, hh * 0.3f, hh * 0.6f, (t - 0.5f) * 14f, core, a * 0.85f)
    }
    for (k in 0 until 2 + (pw * 3).toInt()) { // percikan yang naik lalu padam
        val ph = cycle(ms + k * 977L, 2600 + k * 310)
        val sx = x0 + w * (0.12f + 0.76f * hash01(k, 11))
        val sy = y0 - u * (0.1f + ph * (0.7f + 0.8f * pw))
        spark(sx, sy, u * 0.12f * (1f - ph * 0.5f), pale, (1f - ph) * (0.4f + 0.6f * pw))
    }
}

private fun DrawScope.laurel(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val r = h / 2f
    val n = 3 + (pw * 4).toInt()
    val len = u * (0.45f + 0.45f * pw)
    val a = 0.4f + 0.55f * pw
    val gold = Brush.horizontalGradient(listOf(Color(0xFFB8903A), Color(0xFFE8C972), Color(0xFFFFF1C2)), 0f, 1f)
    val cx = x0 + w / 2f; val cy = y0 + r
    fun side() {
        val c = Offset(x0 + r, cy)
        for (j in 0 until n) {
            val fr = if (n == 1) 0.5f else j / (n - 1f)
            val ang = (110f + fr * 140f) * (PI.toFloat() / 180f)
            val dir = 110f + fr * 140f + (if (j % 2 == 0) 40f else 80f)
            val l = len * (0.7f + 0.3f * sin(PI.toFloat() * fr))
            val sh = 0.75f + 0.25f * sin(Tau * cycle(ms + j * 300L, 2400))
            val ox = c.x + r * 0.9f * kotlin.math.cos(ang); val oy = c.y + r * 0.9f * sin(ang)
            put(FeatherP, ox, oy, l, l * 0.55f, dir, gold, a * sh)
        }
    }
    side()
    scale(-1f, 1f, Offset(cx, cy)) { side() }
}

private fun DrawScope.crystals(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val n = 3 + (pw * 2).toInt()
    val big = u * (0.55f + 0.7f * pw)
    val a = 0.45f + 0.5f * pw
    val body = Brush.verticalGradient(listOf(f.colors[0], f.colors[1], f.colors[2]), -1f, 0f)
    val facet = SolidColor(Color.Black.copy(alpha = 0.28f))
    for (i in 0 until n) {
        val t = i / (n - 1f)
        val mid = 1f - abs(t - 0.5f) * 1.1f
        val hh = big * (0.5f + 0.5f * mid)
        val x = x0 + w * (0.2f + 0.6f * t)
        val tilt = (t - 0.5f) * 34f
        put(CrystalP, x, y0 + h * 0.3f, hh * 0.5f, hh, tilt, body, a)
        put(CrystalFacet, x, y0 + h * 0.3f, hh * 0.5f, hh, tilt, facet, a)
    }
    for (s in 0..1) { // pecahan kecil di kedua ujung
        val sx = if (s == 0) x0 + h * 0.12f else x0 + w - h * 0.12f
        val deg = if (s == 0) -75f else 75f
        put(CrystalP, sx, y0 + h * 0.5f, big * 0.2f, big * 0.5f, deg, body, a)
        put(CrystalFacet, sx, y0 + h * 0.5f, big * 0.2f, big * 0.5f, deg, facet, a)
    }
    val g = cycle(ms, 2800) // kilau sesekali di ujung kristal
    if (g < 0.35f) {
        val k = sin(PI.toFloat() * g / 0.35f)
        spark(x0 + w * (0.3f + 0.4f * hash01((ms / 2800).toInt(), 3)), y0 - big * 0.6f, u * 0.22f * k, Color.White, k * a)
    }
}

private fun DrawScope.wings(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val t = f.tier
    val nF = when (t) { 8 -> if (pw > 0.7f) 4 else 3; 9 -> 5; else -> 6 }
    val lMax = u * when (t) { 8 -> 0.9f + 0.9f * pw; 9 -> 1.1f + 1.0f * pw; else -> 1.2f + 1.2f * pw }
    val a = 0.4f + 0.55f * pw
    val cx = x0 + w / 2f
    val ax = x0 + h * 0.45f; val ay = y0 + h * 0.45f
    val cols = f.colors
    if (t >= 9) {
        val gc = if (t >= 10) cycleColor(cols, cycle(ms, 7000)) else cols[cols.size / 2]
        val rad = lMax * 0.9f
        for (side in 0..1) {
            val c = Offset(if (side == 0) ax else 2f * cx - ax, ay)
            drawCircle(Brush.radialGradient(listOf(gc.copy(alpha = 0.32f * pw), Color.Transparent), c, rad), rad, c)
        }
    }
    fun wing() {
        for (i in nF - 1 downTo 0) {
            val fr = if (nF == 1) 0f else i / (nF - 1f)
            val flap = 3.5f * sin(Tau * cycle(ms, 2600) + fr * 0.9f)
            val ang = 232f - fr * 58f + flap
            val l = lMax * (1f - 0.38f * fr)
            val c = if (t >= 10) cycleColor(cols, cycle(ms, 7000) + fr * 0.25f) else cols[1]
            val root = if (t >= 10) lerp(c, Color.Black, 0.35f) else cols.last()
            val tip = lerp(c, Color.White, 0.55f)
            val b = Brush.horizontalGradient(listOf(root, c, tip), 0f, 1f)
            put(FeatherP, ax, ay, l * 0.92f, l * 0.55f, ang + 7f, b, a * 0.6f) // lapis belakang, lebih pendek
            put(FeatherP, ax, ay, l, l * 0.6f, ang, b, a)
        }
    }
    wing()
    scale(-1f, 1f, Offset(cx, ay)) { wing() }
}

private fun DrawScope.halo(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val bob = sin(Tau * cycle(ms, 3200)) * u * 0.05f
    val rx = h * 0.62f; val ry = u * 0.16f
    val cx = x0 + w / 2f; val cy = y0 - u * 0.2f + bob
    val b = Brush.horizontalGradient(listOf(f.colors[0], Color.White, f.colors[1]), cx - rx, cx + rx)
    drawOval(b, Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry), alpha = 0.5f + 0.45f * pw, style = Stroke(u * 0.1f))
}

private fun DrawScope.crown(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val cx = x0 + w / 2f
    val cw = u * 1.0f; val ch = u * 0.8f
    val prism = Brush.horizontalGradient(f.colors + f.colors.first(), -0.5f, 0.5f)
    put(CrownP, cx, y0 + h * 0.12f, cw, ch, 0f, prism, 0.6f + 0.4f * pw)
    for ((k, dx) in listOf(-0.5f, 0f, 0.5f).withIndex()) { // permata di ujung mahkota
        val tw = 0.5f + 0.5f * sin(Tau * cycle(ms + k * 600L, 1800))
        val dy = if (k == 1) -0.85f else -0.55f
        drawCircle(Color.White.copy(alpha = 0.5f + 0.5f * tw), u * 0.07f, Offset(cx + dx * cw, y0 + h * 0.12f + dy * ch))
    }
}

/** Kilau kecil yang mengitari pill (sebagian tertutup pill, jadi tampak berputar di belakangnya). */
private fun DrawScope.orbiters(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val n = if (f.tier >= 10) 4 else 2
    val lMax = u * (if (f.tier >= 10) 1.2f + 1.2f * f.pw else 1.1f + 1.0f * f.pw)
    val cx = x0 + w / 2f; val cy = y0 + h / 2f
    for (k in 0 until n) {
        val ang = cycle(ms, 5200 + k * 700) * Tau + k * 1.7f
        val px = cx + (w / 2f + lMax * 0.55f) * kotlin.math.cos(ang)
        val py = cy + (h * 0.9f + u * 0.2f) * sin(ang)
        val tw = 0.5f + 0.5f * sin(Tau * cycle(ms + k * 400L, 1500))
        val c = if (f.tier >= 10) cycleColor(f.colors, cycle(ms, 7000) + k * 0.2f) else Color.White
        spark(px, py, u * 0.14f * (0.6f + 0.4f * tw), c, 0.5f + 0.5f * tw)
    }
}
