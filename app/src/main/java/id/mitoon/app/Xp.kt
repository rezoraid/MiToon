package id.mitoon.app

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
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

/** Kurva level, identik dengan server (be/src/core/xp.ts): biaya naik dari L ke L+1 = 20 + 5k + k²/8 dengan k = L-1. */
object Levels {
    const val MAX = 10000
    fun cost(level: Int): Long { val k = (level - 1).coerceAtLeast(0).toLong(); return 20L + 5L * k + k * k / 8L }
    /** cum[L] = total XP minimum untuk mencapai level L. */
    private val cum = LongArray(MAX + 1).also { for (l in 2..MAX) it[l] = it[l - 1] + cost(l - 1) }
    val XP_MAX: Long = cum[MAX]
    fun xpFor(level: Int): Long = cum[level.coerceIn(1, MAX)]
    fun of(xp: Long): Int {
        val x = xp.coerceAtLeast(0L)
        var lo = 1; var hi = MAX
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (cum[mid] <= x) lo = mid else hi = mid - 1 }
        return lo
    }
}

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
 * Tema pill level bergaya kosmik, satu per peran (lihat [Roles]). Tier rendah: pill gradien berwarna dengan kilau yang lewat;
 * makin tinggi makin "luar angkasa": bintang berkelip -> nebula -> bintang jatuh -> komet -> galaksi spiral (Mythic, Eternal).
 */
private class PillTheme(
    val fill: List<Color>, val space: Boolean, val rim: List<Color>, val text: Color, val glow: Color,
    /** putaran bingkai (ms), 0 = diam */ val spinMs: Int, val aura: Float,
    val stars: Int, val nebula: Int, val shoot: Boolean, val comets: Int, val spiral: Boolean,
    /** hiasan yang keluar dari pill (null di bawah Lv. 50) */ val flair: Flair?
)

private val PillStars = intArrayOf(0, 1, 2, 3, 4, 5, 8, 10, 12, 15, 20)
private val PillNebula = intArrayOf(0, 0, 0, 1, 1, 1, 2, 2, 3, 3, 3)
private val PillSpin = intArrayOf(0, 0, 0, 0, 0, 0, 10000, 8000, 7000, 6000, 4500)

private fun pillTheme(level: Int): PillTheme {
    val i = roleIndex(level)
    val cols = Roles[i].colors
    val dark = cols.last()
    val mid = cols[cols.size / 2]
    val space = i >= 6
    val deep = Color(0xFF070712)
    val fill = when {
        i == 10 -> listOf(Color(0xFF1B0F4D), Color(0xFF3A1470), Color(0xFF0E2A5C))
        space -> listOf(lerp(dark, deep, 0.78f), lerp(dark, Color(0xFF0B0B1C), 0.5f), lerp(dark, deep, 0.78f))
        else -> listOf(lerp(dark, Color.Black, 0.3f), lerp(mid, Color.White, 0.08f))
    }
    return PillTheme(
        fill = fill, space = space, rim = cols + cols.first(),
        text = if (space) lerp(cols.first(), Color.White, 0.75f) else Color.White,
        glow = mid, spinMs = PillSpin[i], aura = if (i >= 4) 0.3f + 0.07f * (i - 4) else 0f,
        stars = PillStars[i], nebula = PillNebula[i], shoot = i >= 6, comets = when { i >= 10 -> 2; i >= 9 -> 1; else -> 0 }, spiral = i >= 10,
        flair = if (i >= 5) Flair(i, flairPower(level), cols) else null
    )
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

/** Lencana kecil "Lv. 12": pill kosmik dengan tema sesuai peran (lihat [LvPill]). */
@Composable
fun LevelChip(level: Int, modifier: Modifier = Modifier, size: Int = 10) = LvPill(level, "Lv. $level", modifier, size)

/**
 * Pill level bergaya kosmik. [level] menentukan tema (peran); [text] bebas ("Lv. 25", "Lv. 50 – 149", dst.).
 * Semua efek digambar dari [LvClock] di fase draw, jadi tidak memicu recompose maupun relayout.
 */
@Composable
fun LvPill(level: Int, text: String, modifier: Modifier = Modifier, size: Int = 10) {
    val t = remember(level) { pillTheme(level) }
    DisposableEffect(Unit) { LvClock.users++; onDispose { LvClock.users-- } }
    val base = LocalTextStyle.current
    val unit = size
    val pad = t.flair?.let { flairPad(it.tier, it.pw) }
    val padX = ((pad?.x ?: 0f) * size).dp; val padT = ((pad?.top ?: 0f) * size).dp
    val shadow = if (t.space) Shadow(t.glow.copy(alpha = 0.95f), Offset.Zero, 7f) else Shadow(Color(0x66000000), Offset(0f, 1.5f), 3f)
    Text(text, color = t.text, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, style = base.copy(shadow = shadow),
        modifier = modifier
            .drawBehind { // hiasan di belakang pill: api, daun, kristal, sayap (mulai Lv. 50)
                val fl = t.flair ?: return@drawBehind
                val u = unit.dp.toPx()
                drawFlair(fl, padX.toPx(), padT.toPx(), this.size.width - 2 * padX.toPx(), this.size.height - padT.toPx(), u, LvClock.ms)
            }
            .padding(start = padX, end = padX, top = padT)
            .drawBehind {
                if (t.aura > 0f) { // aura: cahaya membulat di luar pill, berdenyut pelan
                    val ms = LvClock.ms
                    val pulse = 0.65f + 0.35f * (0.5f - 0.5f * cos(TAU * cycle(ms, 3000)))
                    val gc = if (t.rim.size > 4) cycleColor(t.rim, cycle(ms, 7000)) else t.glow
                    for (n in 1..3) {
                        val g = n * 2.3.dp.toPx()
                        drawRoundRect(gc.copy(alpha = (t.aura * pulse * 0.2f / n).coerceAtMost(0.5f)), Offset(-g, -g),
                            Size(this.size.width + 2 * g, this.size.height + 2 * g), CornerRadius(this.size.height / 2f + g))
                    }
                }
            }
            .clip(RoundedCornerShape(50))
            .drawBehind {
                val w = this.size.width; val h = this.size.height
                val c = Offset(w / 2f, h / 2f)
                val r = h / 2f
                val ms = LvClock.ms
                val b = if (t.space) 1.3.dp.toPx() else 1.dp.toPx()
                val d = maxOf(w, h)
                val iw = w - 2 * b; val ih = h - 2 * b
                if (t.space) { // bingkai warna berputar, lalu isi "luar angkasa" di atasnya menyisakan garis tepi
                    rotate(if (t.spinMs > 0) cycle(ms, t.spinMs) * 360f else 0f, c) {
                        drawRect(Brush.sweepGradient(t.rim, c), Offset(c.x - d, c.y - d), Size(d * 2, d * 2))
                    }
                    drawRoundRect(Brush.horizontalGradient(t.fill), Offset(b, b), Size(iw, ih), CornerRadius(r - b))
                } else {
                    drawRoundRect(Brush.horizontalGradient(t.fill), cornerRadius = CornerRadius(r))
                }
                clipRect(b, b, w - b, h - b) {
                    if (t.nebula > 0) nebula(ms, iw, ih, t.rim, t.nebula, 0.16f + 0.06f * t.nebula, Offset(b, b))
                    if (t.spiral) spiralGalaxy(ms, w, h, t.rim, 0.42f)
                    if (t.stars > 0) starfield(t.stars, ms, iw, ih, level % 7 + 3, if (t.space) t.text else Color.White, 0.8.dp.toPx(), Offset(b, b))
                    if (t.shoot) shootingStar(ms + level * 131L, w, h, 4300, 900)
                    // kilap kaca di separuh atas
                    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (t.space) 0.1f else 0.28f), Color.Transparent), 0f, h * 0.62f))
                    // kilau lembut yang lewat dari kiri ke kanan; fase tiap level berbeda supaya tidak serempak
                    val p = cycle(ms + level * 397L, 3600)
                    if (p < 0.5f) {
                        val k = p / 0.5f
                        val fade = minOf(1f, k * 5f, (1f - k) * 5f)
                        val band = h * 2.2f
                        val x = -band + k * (w + band * 2)
                        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.5f * fade), Color.Transparent), Offset(x, 0f), Offset(x + band, h)))
                    }
                }
                if (!t.space) drawRoundRect(Color.White.copy(alpha = 0.28f), cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx()))
                if (t.comets > 0) orbitComet(ms, w, h, 4200, Color.White, 1.1.dp.toPx())
                if (t.comets > 1) orbitComet(ms, w, h, 6100, t.glow, 0.9.dp.toPx(), 0.5f)
            }
            .padding(horizontal = (size * 0.9f).dp, vertical = (size * 0.24f).dp))
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
