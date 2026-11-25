package id.mitoon.app

import androidx.compose.foundation.Canvas
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

/** Tema lencana level tinggi: warna bingkai (sapuan), isi, teks, cahaya; spinMs = putaran bingkai (0 = diam). */
private class LvTheme(
    val border: List<Color>, val fillTop: Color, val fillBottom: Color, val text: Color, val glow: Color,
    val spinMs: Int, val pulse: Boolean, val shimmer: Boolean
)
private val LvGold = LvTheme(listOf(Color(0xFFE8C972), Color(0xFFFFF1C2), Color(0xFFB8903A)), Color(0xFF2B2414), Color(0xFF1F1B10), Color(0xFFF3DDA0), Color(0xFFE8C972), 0, false, true)
private val LvEmerald = LvTheme(listOf(Color(0xFF3FD1A1), Color(0xFFB6F5E0), Color(0xFF1E8F6B)), Color(0xFF10261F), Color(0xFF0C1B16), Color(0xFF9BEBCF), Color(0xFF3FD1A1), 0, false, true)
private val LvSapphire = LvTheme(listOf(Color(0xFF5AA9FF), Color(0xFFC4E0FF), Color(0xFF3B6FE0)), Color(0xFF111E33), Color(0xFF0C1526), Color(0xFFB5D7FF), Color(0xFF5AA9FF), 9000, false, true)
private val LvAmethyst = LvTheme(listOf(Color(0xFFB58CFF), Color(0xFFFFC2F0), Color(0xFF7A4DE0)), Color(0xFF1E1533), Color(0xFF150F25), Color(0xFFDCC8FF), Color(0xFFB58CFF), 7000, true, true)
private val LvPrism = LvTheme(listOf(Color(0xFFFF8AD8), Color(0xFFFFD98A), Color(0xFF8AFFD1), Color(0xFF8AC8FF), Color(0xFFC79BFF)), Color(0xFF1A1A24), Color(0xFF111118), Color.White, Color(0xFFC79BFF), 5500, true, true)

private fun lvTheme(level: Int): LvTheme? = when {
    level >= 5000 -> LvPrism
    level >= 3000 -> LvAmethyst
    level >= 1000 -> LvSapphire
    level >= 500 -> LvEmerald
    level >= 100 -> LvGold
    else -> null
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

/**
 * Lencana kecil "Lv. 12". Level 100/500/1000/3000/5000 punya tema sendiri (emas, zamrud, safir, ametis, prisma):
 * bingkai gradasi + kilau tipis; makin tinggi makin hidup (bingkai berputar pelan, cahaya berdenyut halus). Semua digambar
 * di fase draw dari [LvClock], jadi tidak memicu recompose maupun relayout.
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
    Text("Lv. $level", color = t.text, fontSize = size.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier
            .drawBehind {
                if (t.pulse) { // cahaya tipis di sekeliling kotak; gelombang sinus supaya naik-turun mulus
                    val p = cycle(LvClock.ms, 3600)
                    val a = 0.05f + 0.05f * (0.5f - 0.5f * cos(2f * PI.toFloat() * p))
                    val g1 = 4.dp.toPx(); val g2 = 2.dp.toPx()
                    drawRoundRect(t.glow.copy(alpha = a), Offset(-g1, -g1), Size(this.size.width + 2 * g1, this.size.height + 2 * g1), CornerRadius(7.dp.toPx()))
                    drawRoundRect(t.glow.copy(alpha = a * 1.3f), Offset(-g2, -g2), Size(this.size.width + 2 * g2, this.size.height + 2 * g2), CornerRadius(5.dp.toPx()))
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
                    drawRect(Brush.sweepGradient(t.border + t.border.first(), c), Offset(c.x - d, c.y - d), Size(d * 2, d * 2))
                }
                drawRoundRect(Brush.verticalGradient(listOf(t.fillTop, t.fillBottom)), Offset(b, b), Size(w - 2 * b, h - 2 * b), CornerRadius(2.dp.toPx()))
                val sheen = cycle(ms, 5000)
                if (t.shimmer && sheen < 0.3f) { // kilau lewat sesekali; memudar di kedua ujung agar tidak muncul/hilang tiba-tiba
                    val k = sheen / 0.3f
                    val fade = minOf(1f, k * 6f, (1f - k) * 6f)
                    val band = h * 2.4f
                    val x = -band + k * (w + band * 2)
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.2f * fade), Color.Transparent), Offset(x, 0f), Offset(x + band, h)),
                        Offset(b, b), Size(w - 2 * b, h - 2 * b))
                }
            }
            .padding(horizontal = 5.dp, vertical = 1.dp))
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
