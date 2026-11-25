package id.mitoon.app

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.cos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data class XpReq(val type: String, val ref: String? = null)
@Serializable data class XpRes(val gained: Int = 0, val user: AuthUser = AuthUser(), val streak: StreakRes? = null)

/**
 * Lapor aktivitas ke server untuk XP. Server yang menentukan: tiap chapter/seri hanya dihitung sekali dan ada batas harian.
 * Di sini hanya mencegah request berulang dalam satu sesi dan menelan semua error (XP tidak boleh mengganggu membaca).
 */
object Xp {
    private val sent = HashSet<String>()

    /** Ganti akun/keluar: catatan "sudah dikirim" milik akun sebelumnya dibuang. */
    fun reset() = sent.clear()

    fun event(type: String, ref: String? = null) {
        if (Auth.token == null || Auth.isGuest) return
        // Bonus harian: kunci per hari WIB supaya aplikasi yang terbuka berhari-hari tetap melapor tiap hari baru.
        val key = if (type == "daily") "daily|${(System.currentTimeMillis() + 7 * 3_600_000L) / 86_400_000L}" else "$type|$ref"
        if (!sent.add(key)) return
        MainScope().launch {
            try {
                val r = Net.api.xpEvent(XpReq(type, ref)).data
                if (r.gained > 0) Auth.update(r.user)
                r.streak?.let { Streak.set(it) } // naik walau XP 0 (chapter lama / kuota harian habis)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { sent.remove(key) }
        }
    }
}

/**
 * Tema lencana level, satu per peran (lihat [Roles]); makin tinggi perannya makin megah: bingkai berwarna -> cahaya (aura) ->
 * kilau -> bintang berkelip -> nebula melayang -> bintang jatuh -> komet mengorbit -> galaksi spiral (Eternal).
 */
private class LvTheme(
    val border: List<Color>, val fillTop: Color, val fillBottom: Color, val text: Color, val glow: Color,
    /** putaran bingkai (ms), 0 = diam */ val spinMs: Int,
    /** kekuatan cahaya di sekeliling lencana 0..1 */ val aura: Float,
    val shimmer: Boolean, val stars: Int, val nebula: Int, val shoot: Boolean, val comets: Int, val spiral: Boolean,
    val padH: Int, val textGlow: Boolean
)

private fun lvTheme(level: Int): LvTheme? {
    val i = roleIndex(level)
    if (i == 0) return null
    val cols = Roles[i].colors
    val border = cols + cols.first()
    val dark = cols.last()
    val top = if (i == 10) Color(0xFF0B0724) else lerp(dark, Color(0xFF0B0B14), 0.84f)
    val bottom = if (i == 10) Color(0xFF04030E) else lerp(dark, Color.Black, 0.92f)
    val text = lerp(cols.first(), Color.White, if (i >= 8) 0.7f else 0.35f)
    val glow = cols[cols.size / 2]
    return when (i) {
        1 -> LvTheme(border, top, bottom, text, glow, 0, 0f, false, 0, 0, false, 0, false, 5, false)
        2 -> LvTheme(border, top, bottom, text, glow, 0, 0.25f, false, 0, 0, false, 0, false, 5, false)
        3 -> LvTheme(border, top, bottom, text, glow, 0, 0.3f, true, 0, 0, false, 0, false, 5, false)
        4 -> LvTheme(border, top, bottom, text, glow, 0, 0.35f, true, 2, 0, false, 0, false, 5, false)
        5 -> LvTheme(border, top, bottom, text, glow, 14000, 0.4f, true, 3, 0, false, 0, false, 5, false)
        6 -> LvTheme(border, top, bottom, text, glow, 10000, 0.5f, true, 4, 1, false, 0, false, 6, true)
        7 -> LvTheme(border, top, bottom, text, glow, 8000, 0.55f, true, 5, 1, true, 0, false, 6, true)
        8 -> LvTheme(border, top, bottom, text, glow, 7000, 0.65f, true, 7, 2, true, 1, false, 6, true)
        9 -> LvTheme(border, top, bottom, text, glow, 6000, 0.8f, true, 9, 2, true, 1, true, 7, true)
        else -> LvTheme(border, top, bottom, text, glow, 4500, 1f, true, 13, 3, true, 2, true, 8, true)
    }
}

/**
 * Jam animasi tunggal untuk semua LevelChip. Sebelumnya tiap chip punya 3 transisi tak-hingga sendiri, sehingga di daftar
 * komentar puluhan animasi jalan bersamaan dan terasa "kedut"/patah-patah. Sekarang hanya satu pengait frame, aktif
 * hanya selama ada chip bertema di layar, dan semua nilai dihitung dari waktu (tanpa lonjakan saat siklus berulang).
 */
object LvClock {
    var ms by mutableLongStateOf(0L); private set
    var users by mutableIntStateOf(0)

    @Composable
    fun Run() {
        LaunchedEffect(Unit) {
            snapshotFlow { users > 0 }.collectLatest { on ->
                if (on) while (true) withFrameNanos { ms = it / 1_000_000 }
            }
        }
    }
}

/** Posisi 0..1 dalam satu siklus berdurasi [period] ms. */
internal fun cycle(ms: Long, period: Int): Float = (ms % period).toFloat() / period

/** Warna yang berganti mulus di antara [colors] menurut posisi siklus [p] (0..1, berputar kembali ke awal). */
internal fun cycleColor(colors: List<Color>, p: Float): Color {
    val n = colors.size
    val x = (p - kotlin.math.floor(p)) * n
    val i = x.toInt() % n
    return lerp(colors[i], colors[(i + 1) % n], x - x.toInt())
}

/** Angka acak stabil 0..1 dari (i, k): posisi bintang tetap sama tiap frame, tanpa menyimpan state. */
internal fun hash01(i: Int, k: Int): Float {
    var x = (i * 374761393L + k * 668265263L) xor 0x5bd1e995L
    x = (x xor (x shr 13)) * 1274126177L
    return ((x xor (x shr 16)) and 0xFFFFL) / 65535f
}

private val TAU = (2.0 * PI).toFloat()

/** Bintang berkelip: tiap bintang punya periode & fase sendiri; yang paling terang diberi kilau silang. */
internal fun DrawScope.starfield(count: Int, ms: Long, w: Float, h: Float, seed: Int, tint: Color, maxR: Float, area: Offset = Offset.Zero) {
    for (i in 0 until count) {
        val x = area.x + w * (0.08f + 0.84f * hash01(i, seed))
        val y = area.y + h * (0.14f + 0.72f * hash01(i, seed + 1))
        val per = 1700 + (hash01(i, seed + 2) * 2300).toInt()
        val tw = 0.5f - 0.5f * cos(TAU * cycle(ms + (hash01(i, seed + 3) * per).toLong(), per))
        val r = maxR * (0.55f + 0.45f * hash01(i, seed + 4)) * (0.5f + 0.5f * tw)
        val c = if (i % 3 == 0) tint else Color.White
        drawCircle(c.copy(alpha = 0.2f + 0.8f * tw), r, Offset(x, y))
        if (tw > 0.82f && hash01(i, seed + 5) > 0.45f) {
            val l = r * 3.2f * tw; val sw = maxOf(r * 0.5f, 0.6f)
            drawLine(c.copy(alpha = 0.65f * tw), Offset(x - l, y), Offset(x + l, y), sw, StrokeCap.Round)
            drawLine(c.copy(alpha = 0.65f * tw), Offset(x, y - l), Offset(x, y + l), sw, StrokeCap.Round)
        }
    }
}

/** Awan nebula: beberapa bercak cahaya berwarna yang melayang pelan (lintasan elips berbeda-beda). */
internal fun DrawScope.nebula(ms: Long, w: Float, h: Float, colors: List<Color>, layers: Int, strength: Float, origin: Offset = Offset.Zero) {
    for (i in 0 until layers) {
        val ph = cycle(ms, 11000 + i * 4300) * TAU + i * 2.1f
        val cx = origin.x + w * (0.5f + 0.4f * cos(ph)); val cy = origin.y + h * (0.5f + 0.4f * sin(ph * 1.3f))
        val col = colors[i % colors.size]
        val rad = maxOf(w, h) * (0.55f + 0.1f * i)
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = strength), Color.Transparent), Offset(cx, cy), rad), rad, Offset(cx, cy))
    }
}

/** Galaksi spiral: dua lengan bintang berputar pelan, gepeng mengikuti bentuk lencana, inti bercahaya di tengah. */
internal fun DrawScope.spiralGalaxy(ms: Long, w: Float, h: Float, colors: List<Color>, flat: Float) {
    val cx = w / 2f; val cy = h / 2f
    val rot = cycle(ms, 16000) * TAU
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), colors[0].copy(alpha = 0.25f), Color.Transparent), Offset(cx, cy), h * 0.9f), h * 0.9f, Offset(cx, cy))
    for (arm in 0 until 2) for (j in 1..16) {
        val th = j * 0.42f
        val r = w * 0.032f * th
        val a = th + rot + arm * PI.toFloat()
        val x = cx + r * cos(a); val y = cy + r * sin(a) * flat
        val col = colors[(j + arm) % colors.size]
        drawCircle(col.copy(alpha = (0.85f - j * 0.04f).coerceAtLeast(0.15f)), maxOf(0.5f, h * 0.045f * (1.2f - j * 0.04f)), Offset(x, y))
    }
}

private fun perimeter(t: Float, w: Float, h: Float): Offset {
    val d = (t - kotlin.math.floor(t)) * 2f * (w + h)
    return when {
        d < w -> Offset(d, 0f)
        d < w + h -> Offset(w, d - w)
        d < 2f * w + h -> Offset(w - (d - w - h), h)
        else -> Offset(0f, h - (d - 2f * w - h))
    }
}

/** Komet kecil yang mengelilingi tepi lencana dengan ekor memudar. */
internal fun DrawScope.orbitComet(ms: Long, w: Float, h: Float, period: Int, color: Color, r: Float, offset: Float = 0f) {
    val head = cycle(ms, period) + offset
    for (k in 0 until 9) drawCircle(color.copy(alpha = (1f - k / 9f) * 0.95f), r * (1f - k * 0.07f), perimeter(head - k * 0.011f, w, h))
}

/** Bintang jatuh yang melintas diagonal sesekali (±1 detik tiap [period] ms). */
private fun DrawScope.shootingStar(ms: Long, w: Float, h: Float, period: Int, offset: Int) {
    val p = cycle(ms + offset, period)
    if (p > 0.22f) return
    val k = p / 0.22f
    val fade = minOf(1f, k * 5f, (1f - k) * 5f)
    val head = Offset(-0.2f * w + k * 1.5f * w, -0.1f * h + k * 1.2f * h)
    val tail = Offset(head.x - 0.45f * w, head.y - 0.55f * h)
    drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f * fade)), tail, head), tail, head, 1.3f.dp.toPx(), StrokeCap.Round)
}

/**
 * Lencana kecil "Lv. 12". Tiap peran punya tema sendiri dan makin tinggi makin megah (lihat [LvTheme]); puncaknya
 * (Eternal, Lv. 5000+) adalah galaksi mini: nebula berwarna melayang, galaksi spiral berputar, bintang berkelip,
 * bintang jatuh, dua komet mengorbit, bingkai prisma berputar, dan aura yang berdenyut. Semua digambar di fase draw dari
 * [LvClock], jadi tidak memicu recompose maupun relayout.
 */
@Composable
fun LevelChip(level: Int, modifier: Modifier = Modifier, size: Int = 10) {
    val t = lvTheme(level)
    if (t == null) {
        Text("Lv. $level", color = Color(0xFFD7D9DE), fontSize = size.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = modifier.clip(R6).border(1.dp, Color(0xFF4A4C53), R6).padding(horizontal = 5.dp, vertical = 1.dp))
        return
    }
    DisposableEffect(Unit) { LvClock.users++; onDispose { LvClock.users-- } }
    val base = LocalTextStyle.current
    Text("Lv. $level", color = t.text, fontSize = size.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        style = if (t.textGlow) base.copy(shadow = Shadow(t.glow.copy(alpha = 0.95f), Offset.Zero, 7f)) else base,
        modifier = modifier
            .drawBehind {
                if (t.aura > 0f) { // aura: lapisan cahaya membulat di luar kotak, denyut sinus; tema prisma berganti warna
                    val ms = LvClock.ms
                    val pulse = 0.65f + 0.35f * (0.5f - 0.5f * cos(TAU * cycle(ms, 3000)))
                    val gc = if (t.border.size > 4) cycleColor(t.border, cycle(ms, 7000)) else t.glow
                    for (n in 1..3) {
                        val g = n * 2.3.dp.toPx()
                        drawRoundRect(gc.copy(alpha = (t.aura * pulse * 0.2f / n).coerceAtMost(0.5f)), Offset(-g, -g),
                            Size(this.size.width + 2 * g, this.size.height + 2 * g), CornerRadius(6.dp.toPx() + g))
                    }
                }
            }
            .clip(R6)
            .drawBehind {
                val w = this.size.width; val h = this.size.height
                val c = Offset(w / 2f, h / 2f)
                val b = 1.dp.toPx()
                val d = maxOf(w, h)
                val ms = LvClock.ms
                // bingkai: sapuan warna (diputar bila tema bergerak), lalu isi gelap di atasnya menyisakan garis tepi
                rotate(if (t.spinMs > 0) cycle(ms, t.spinMs) * 360f else 0f, c) {
                    drawRect(Brush.sweepGradient(t.border, c), Offset(c.x - d, c.y - d), Size(d * 2, d * 2))
                }
                val iw = w - 2 * b; val ih = h - 2 * b
                drawRoundRect(Brush.verticalGradient(listOf(t.fillTop, t.fillBottom)), Offset(b, b), Size(iw, ih), CornerRadius(2.dp.toPx()))
                // isi dalam: nebula -> galaksi spiral -> bintang -> bintang jatuh (dipotong di dalam bingkai)
                clipRect(b, b, w - b, h - b) {
                    if (t.nebula > 0) nebula(ms, iw, ih, t.border, t.nebula, 0.1f + 0.07f * t.nebula, Offset(b, b))
                    if (t.spiral) spiralGalaxy(ms, w, h, t.border, 0.42f)
                    if (t.stars > 0) starfield(t.stars, ms, iw, ih, level % 7 + 3, t.text, 0.85.dp.toPx(), Offset(b, b))
                    if (t.shoot) shootingStar(ms, w, h, 4300, 900)
                    if (t.shoot && t.comets > 1) shootingStar(ms, w, h, 5900, 3100)
                }
                val sheen = cycle(ms, 5000)
                if (t.shimmer && sheen < 0.3f) { // kilau lewat sesekali; memudar di kedua ujung agar tidak muncul/hilang tiba-tiba
                    val k = sheen / 0.3f
                    val fade = minOf(1f, k * 6f, (1f - k) * 6f)
                    val band = h * 2.4f
                    val x = -band + k * (w + band * 2)
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f * fade), Color.Transparent), Offset(x, 0f), Offset(x + band, h)),
                        Offset(b, b), Size(iw, ih))
                }
                // komet mengorbit di atas bingkai
                if (t.comets > 0) orbitComet(ms, w, h, 4200, Color.White, 1.1.dp.toPx())
                if (t.comets > 1) orbitComet(ms, w, h, 6100, t.glow, 0.9.dp.toPx(), 0.5f)
            }
            .padding(horizontal = t.padH.dp, vertical = 1.dp))
}

/** Centang verifikasi (segel biru bergelombang + centang putih), digambar sendiri tanpa paket ikon tambahan. */
@Composable
fun VerifiedBadge(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        val seal = Brush.linearGradient(listOf(Color(0xFF5CB0FF), Color(0xFF2F7BFF)), Offset(0f, 0f), Offset(s, s))
        val sq = s * 0.74f
        for (deg in listOf(0f, 45f)) rotate(deg, c) { drawRoundRect(seal, Offset(c.x - sq / 2, c.y - sq / 2), Size(sq, sq), CornerRadius(sq * 0.28f)) }
        val p = Path().apply { moveTo(s * 0.31f, s * 0.52f); lineTo(s * 0.44f, s * 0.65f); lineTo(s * 0.70f, s * 0.36f) }
        drawPath(p, Color.White, style = Stroke(s * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Mahkota (bentuk sama untuk Owner dan Premium; beda warna). Digambar sendiri, tanpa paket ikon tambahan. */
private fun DrawScope.crown(fill: Brush) {
    val s = size.minDimension
    val body = Path().apply {
        moveTo(s * 0.10f, s * 0.74f); lineTo(s * 0.06f, s * 0.30f); lineTo(s * 0.32f, s * 0.50f); lineTo(s * 0.50f, s * 0.18f)
        lineTo(s * 0.68f, s * 0.50f); lineTo(s * 0.94f, s * 0.30f); lineTo(s * 0.90f, s * 0.74f); close()
    }
    drawPath(body, fill)
    drawRoundRect(fill, Offset(s * 0.10f, s * 0.79f), Size(s * 0.80f, s * 0.12f), CornerRadius(s * 0.03f))
    for (p in listOf(Offset(s * 0.06f, s * 0.27f), Offset(s * 0.50f, s * 0.15f), Offset(s * 0.94f, s * 0.27f))) drawCircle(fill, s * 0.06f, p)
}

/** Mahkota Owner MiToon: gradasi prisma ungu-biru-pink supaya beda dari mahkota emas Premium. */
@Composable
fun OwnerBadge(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        crown(Brush.linearGradient(listOf(Color(0xFFC4B0FF), Color(0xFF7EEBFF), Color(0xFFFFA0D8)), Offset(0f, 0f), Offset(s, s)))
    }
}

/** Mahkota emas Premium Member. */
@Composable
fun PremiumBadge(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        crown(Brush.linearGradient(listOf(Color(0xFFFFE08A), Color(0xFFFFB02E)), Offset(0f, 0f), Offset(s, s)))
    }
}

/** Ikon status kecil di samping nama (komentar, daftar admin): centang verified, mark Owner MiToon, mahkota Premium. Yang tidak punya = tidak tampil. */
@Composable
fun StatusBadges(verified: Boolean, isOwner: Boolean, premium: Boolean = false, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        // owner otomatis terverifikasi: centang biru yang sama seperti akun verified
        if (verified || isOwner) VerifiedBadge(Modifier.padding(start = 6.dp), size)
        if (isOwner) OwnerBadge(Modifier.padding(start = 4.dp), size)
        if (premium) PremiumBadge(Modifier.padding(start = 4.dp), size)
    }
}

/** Label status di halaman profil: "Owner MiToon" dan/atau "Premium Member" (ikon + teks). Tidak punya = tidak tampil. */
@Composable
fun StatusLabels(isOwner: Boolean, premium: Boolean, modifier: Modifier = Modifier, iconSize: Dp = 18.dp, textSize: Int = 13, center: Boolean = true) {
    if (!isOwner && !premium) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = if (center) androidx.compose.ui.Alignment.CenterHorizontally else androidx.compose.ui.Alignment.Start) {
        if (isOwner) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OwnerBadge(size = iconSize)
            Text("Owner MiToon", color = Color(0xFFC4B0FF), fontSize = textSize.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
        if (premium) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            PremiumBadge(size = iconSize)
            Text("Premium Member", color = Color(0xFFFFC53D), fontSize = textSize.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Bilah progres XP menuju level berikutnya + keterangan sisa XP. */
@Composable
fun XpBar(user: AuthUser, modifier: Modifier = Modifier) {
    val frac = (user.levelXp.toFloat() / user.levelNeed.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(R6).background(Color(0xFF2A2C32))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color.White))
        }
        Text("${user.levelXp} / ${user.levelNeed} XP  ·  ${user.levelNeed - user.levelXp} XP lagi ke Lv. ${user.level + 1}",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
