package id.mitoon.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
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

/**
 * Hiasan yang "keluar" dari pill level, mulai Lv. 50. Satu tema per peran, makin tinggi level makin besar, rapat, dan terang:
 *  - Series Master (50)   api kecil menyala di atas pill + percikan naik
 *  - Sensei (100)         karangan daun emas di kedua ujung
 *  - Curator (500)        aurora zamrud + sinar cahaya berputar + kristal
 *  - Legend (1000)        planet safir bercincin + komet mengorbit + bulan sabit + sinar berputar
 *  - Mythic (3000)        galaksi spiral ametis berputar + lingkaran cahaya + bulan + bintang jatuh
 *  - Eternal (5000)       kosmos prisma: sinar pelangi berputar, galaksi, dua cincin komet, sayap cahaya, mahkota
 * Semua digambar di belakang pill (jadi tampak tumbuh dari baliknya) dan memakai jam bersama [LvClock].
 */
internal class Flair(val tier: Int, val pw: Float, val colors: List<Color>)

/** Ruang tambahan di sekeliling pill, dalam kelipatan ukuran teks (u). */
internal class FlairPad(val x: Float, val top: Float)

/** 0 di bawah Lv. 50 (tidak ada hiasan); Lv. 50 = 0,25 (tipis) naik mulus ke 1,0 di Lv. 5000. */
internal fun flairPower(level: Int): Float =
    if (level < 50) 0f else 0.25f + 0.75f * (ln(level / 50f) / ln(100f)).coerceIn(0f, 1f)

internal fun flairPad(tier: Int, pw: Float): FlairPad = when (tier) {
    5 -> FlairPad(0.15f, (0.9f + 0.9f * pw) * 0.7f)
    6 -> FlairPad(0.2f + (0.7f + 0.7f * pw) * 0.9f, 0.15f)
    7 -> FlairPad(0.6f + 0.6f * pw, (1.2f + 1.1f * pw) * 0.8f)
    8 -> FlairPad((1.3f + 1.2f * pw) * 0.9f, (0.8f + 0.5f * pw) * 0.8f)
    9 -> FlairPad((1.7f + 1.4f * pw) * 0.9f, (1.0f + 0.6f * pw) * 0.8f)
    else -> FlairPad((2.2f + 1.6f * pw) * 0.9f, (1.3f + 0.6f * pw) * 0.8f)
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
        else -> {
            space(f, x0, y0, w, h, u, ms)
            if (f.tier >= 10) { wings(f, x0, y0, w, h, u, ms); crown(f, x0, y0, w, h, u, ms) } else if (f.tier == 9) halo(f, x0, y0, w, h, u, ms)
            if (f.tier >= 9) orbiters(f, x0, y0, w, h, u, ms)
        }
    }
}

private fun DrawScope.flames(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw
    val n = 3 + (pw * 3).toInt()
    val hot = f.colors.last(); val pale = f.colors.first()
    val a = 0.4f + 0.55f * pw
    val big = u * (0.9f + 0.9f * pw)
    val body = Brush.verticalGradient(listOf(hot.copy(alpha = 0f), hot, pale), -1f, 0f)
    val core = SolidColor(Color(0xFFFFF4C9))
    val baseY = y0 + h * 0.15f
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
    val n = 4 + (pw * 4).toInt()
    val len = u * (0.7f + 0.7f * pw)
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
    val big = u * (1.1f + 1.1f * pw)
    val a = 0.5f + 0.5f * pw
    val body = Brush.verticalGradient(listOf(f.colors[0], f.colors[1], f.colors[2]), -1f, 0f)
    val facet = SolidColor(Color.Black.copy(alpha = 0.28f))
    for (i in 0 until n) {
        val t = i / (n - 1f)
        val mid = 1f - abs(t - 0.5f) * 1.1f
        val hh = big * (0.5f + 0.5f * mid)
        val x = x0 + w * (0.2f + 0.6f * t)
        val tilt = (t - 0.5f) * 34f
        put(CrystalP, x, y0 + h * 0.45f, hh * 0.5f, hh, tilt, body, a)
        put(CrystalFacet, x, y0 + h * 0.45f, hh * 0.5f, hh, tilt, facet, a)
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

// ------------------------------------------------------------------ adegan luar angkasa (Lv. 500+)

/** Sinar tipis: pangkal di (0,0), ujung di (1,0). */
private val RayP = Path().apply { moveTo(0f, 0f); lineTo(1f, -0.07f); lineTo(1f, 0.07f); close() }

/** Bulan sabit satuan (membuka ke kanan). */
private val CrescentP: Path = run {
    val a = Path().apply { addOval(Rect(-1f, -1f, 1f, 1f)) }
    val b = Path().apply { addOval(Rect(-0.35f, -1.15f, 1.55f, 0.85f)) }
    Path.combine(PathOperation.Difference, a, b)
}

/** Sinar cahaya berputar dari pusat pill; sinar bergantian panjang-pendek. */
private fun DrawScope.rays(c: Offset, len: Float, n: Int, color: (Int) -> Color, spin: Float, a: Float, thick: Float) {
    for (i in 0 until n) {
        val col = color(i)
        val b = Brush.horizontalGradient(listOf(col.copy(alpha = 0.9f), col.copy(alpha = 0f)), 0f, 1f)
        val l = len * (if (i % 2 == 0) 1f else 0.62f)
        put(RayP, c.x, c.y, l, l * thick, spin + i * 360f / n, b, a)
    }
}

/** Cincin orbit miring dengan komet berekor yang mengelilinginya. */
private fun DrawScope.orbitRing(c: Offset, rx: Float, ry: Float, tilt: Float, col: Color, ms: Long, period: Int, a: Float, sw: Float) {
    withTransform({ rotate(tilt, c) }) {
        drawOval(col.copy(alpha = 0.3f * a), Offset(c.x - rx, c.y - ry), Size(2f * rx, 2f * ry), style = Stroke(sw))
        val head = cycle(ms, period) * Tau
        for (k in 0 until 10) {
            val th = head - k * 0.11f
            drawCircle(Color.White.copy(alpha = (1f - k / 10f) * a), sw * (1.9f - k * 0.12f), Offset(c.x + rx * cos(th), c.y + ry * sin(th)))
        }
    }
}

/** Galaksi spiral gepeng: cahaya inti, [arms] lengan bintang yang berputar, inti terang. */
private fun DrawScope.galaxy(c: Offset, rx: Float, ry: Float, cols: List<Color>, ms: Long, period: Int, a: Float, arms: Int, dots: Int, dot: Float, color: (Int) -> Color) {
    val rot = cycle(ms, period) * Tau
    val flat = ry / rx
    withTransform({ scale(1f, flat, c) }) {
        drawCircle(Brush.radialGradient(listOf(cols[0].copy(alpha = 0.7f * a), cols[cols.size / 2].copy(alpha = 0.32f * a), Color.Transparent), c, rx), rx, c)
    }
    for (arm in 0 until arms) for (j in 1..dots) {
        val fr = j.toFloat() / dots
        val ang = j * 0.26f + rot + arm * Tau / arms
        val x = c.x + rx * fr * cos(ang); val y = c.y + rx * fr * sin(ang) * flat
        drawCircle(color(j + arm).copy(alpha = (0.95f - 0.6f * fr) * a), maxOf(0.6f, dot * (1.3f - fr)), Offset(x, y))
    }
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * a), Color.Transparent), c, ry * 0.8f), ry * 0.8f, c)
}

/** Planet bercincin: cincin belakang, bola, lalu cincin depan; kilau berjalan di cincin depan. */
private fun DrawScope.planet(c: Offset, r: Float, cols: List<Color>, ring: Color, ms: Long, a: Float) {
    val rw = r * 2.1f; val rh = r * 0.55f
    val tl = Offset(c.x - rw, c.y - rh); val sz = Size(2f * rw, 2f * rh)
    withTransform({ rotate(-18f, c) }) { drawArc(ring.copy(alpha = 0.6f * a), 180f, 180f, false, tl, sz, style = Stroke(r * 0.12f)) }
    drawCircle(Brush.radialGradient(listOf(lerp(cols.first(), Color.White, 0.5f), cols[1], cols.last()), Offset(c.x - r * 0.35f, c.y - r * 0.35f), r * 1.5f), r, c, alpha = a)
    withTransform({ rotate(-18f, c) }) {
        drawArc(ring.copy(alpha = 0.95f * a), 0f, 180f, false, tl, sz, style = Stroke(r * 0.16f))
        val th = cycle(ms, 3200) * Tau
        if (sin(th) > 0f) drawCircle(Color.White.copy(alpha = a), r * 0.12f, Offset(c.x + rw * cos(th), c.y + rh * sin(th)))
    }
}

/** Pita aurora bergelombang di atas pill. */
private fun DrawScope.aurora(x0: Float, y0: Float, w: Float, h: Float, u: Float, cols: List<Color>, ms: Long, a: Float) {
    val base = y0 + h * 0.3f
    for (k in 0 until 2) {
        val p = Path()
        val left = x0 - u * 0.4f; val span = w + u * 0.8f
        p.moveTo(left, base)
        for (i in 0..14) {
            val t = i / 14f
            val y = base - u * (0.9f + 0.4f * k) - u * (0.55f + 0.15f * k) * sin(Tau * (t * 1.3f + cycle(ms, 5200 + k * 1700)) + k * 1.7f) * sin(PI.toFloat() * t)
            p.lineTo(left + span * t, y)
        }
        p.lineTo(left + span, base); p.close()
        drawPath(p, Brush.verticalGradient(listOf(cols[k % cols.size].copy(alpha = 0f), cols[(k + 1) % cols.size].copy(alpha = 0.5f)), base - u * 2.2f, base), alpha = a)
    }
}

private fun DrawScope.moon(x: Float, y: Float, r: Float, deg: Float, a: Float) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4C9).copy(alpha = 0.4f * a), Color.Transparent), Offset(x, y), r * 2.2f), r * 2.2f, Offset(x, y))
    put(CrescentP, x, y, r, r, deg, SolidColor(Color(0xFFFFF4C9)), a)
}

/** Bintang jatuh yang melintas diagonal sesekali. */
private fun DrawScope.meteor(x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long, period: Int, offset: Int, col: Color, a: Float) {
    val p = cycle(ms + offset, period)
    if (p > 0.25f) return
    val k = p / 0.25f
    val fade = minOf(1f, k * 5f, (1f - k) * 5f)
    val head = Offset(x0 - u * 0.5f + k * (w + u * 1.5f), y0 - u * 1.2f + k * (h + u * 2.0f))
    val tail = Offset(head.x - u * 2.2f, head.y - u * 1.1f)
    drawLine(Brush.linearGradient(listOf(Color.Transparent, col.copy(alpha = a * fade)), tail, head), tail, head, u * 0.12f, StrokeCap.Round)
}

/** Bintang kecil berkelip tersebar di seluruh area hiasan. */
private fun DrawScope.scatter(x0: Float, y0: Float, w: Float, h: Float, ex: Float, et: Float, u: Float, ms: Long, n: Int, a: Float, col: Color) {
    for (i in 0 until n) {
        val x = x0 - ex + (w + 2f * ex) * hash01(i, 21)
        val y = y0 - et + (h + et + u * 0.3f) * hash01(i, 22)
        val tw = 0.5f + 0.5f * sin(Tau * cycle(ms + (hash01(i, 23) * 2000f).toInt(), 1400 + (hash01(i, 24) * 1200f).toInt()))
        spark(x, y, u * (0.07f + 0.1f * hash01(i, 25)) * (0.5f + 0.5f * tw), if (i % 3 == 0) col else Color.White, a * tw)
    }
}

private fun DrawScope.space(f: Flair, x0: Float, y0: Float, w: Float, h: Float, u: Float, ms: Long) {
    val pw = f.pw; val t = f.tier; val cols = f.colors
    val pad = flairPad(t, pw)
    val ex = pad.x * u; val et = pad.top * u
    val c = Offset(x0 + w / 2f, y0 + h / 2f)
    val a = 0.5f + 0.5f * pw
    val reach = w / 2f + ex
    val prism: (Int) -> Color = { i -> if (t >= 10) cycleColor(cols, cycle(ms, 7000) + i * 0.09f) else cols[i % cols.size] }
    when (t) {
        7 -> { // aurora zamrud
            aurora(x0, y0, w, h, u, cols, ms, a)
            rays(c, reach, 8, prism, cycle(ms, 16000) * 360f, 0.7f * a, 2.2f)
            crystals(f, x0, y0, w, h, u, ms)
        }
        8 -> { // planet safir
            rays(c, reach, 8, prism, -cycle(ms, 14000) * 360f, 0.6f * a, 1.8f)
            orbitRing(c, w / 2f + ex * 0.8f, h * 0.8f + et * 0.35f, -8f, cols[0], ms, 4800, a, u * 0.07f)
            planet(Offset(x0 + w + ex * 0.2f, y0 + h * 0.3f), u * (0.55f + 0.45f * pw), cols, cols[0], ms, a)
            moon(x0 - ex * 0.3f, y0 - et * 0.4f, u * 0.35f, 20f, a)
        }
        9 -> { // galaksi ametis
            galaxy(c, w / 2f + ex, h / 2f + et * 0.9f, cols, ms, 18000, a, 3, 40, u * 0.17f, prism)
            rays(c, reach, 8, prism, cycle(ms, 12000) * 360f, 0.6f * a, 1.8f)
            orbitRing(c, w / 2f + ex * 0.7f, h * 0.85f + et * 0.4f, 10f, cols[0], ms, 5200, a, u * 0.06f)
            moon(x0 + w + ex * 0.3f, y0 - et * 0.35f, u * 0.4f, -25f, a)
        }
        else -> { // kosmos prisma
            rays(c, reach * 1.05f, 12, prism, cycle(ms, 20000) * 360f, 0.8f * a, 2.2f)
            galaxy(c, w / 2f + ex, h / 2f + et * 0.9f, cols, ms, 16000, a, 3, 40, u * 0.17f, prism)
            orbitRing(c, w / 2f + ex * 0.8f, h * 0.9f + et * 0.4f, -12f, prism(0), ms, 4200, a, u * 0.06f)
            orbitRing(c, w / 2f + ex * 0.6f, h * 0.7f + et * 0.3f, 14f, prism(3), ms, 6100, a, u * 0.05f)
            meteor(x0, y0, w, h, u, ms, 5200, 2600, Color.White, a)
        }
    }
    meteor(x0, y0, w, h, u, ms, 4300, 900, cols[0], a)
    scatter(x0, y0, w, h, ex, et, u, ms, 4 + (pw * 6f).toInt() + (t - 7) * 2, a, cols[0])
}
