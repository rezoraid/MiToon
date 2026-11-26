package id.mitoon.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Bentuk ikon tiap peran. Semua digambar sendiri di Canvas (tanpa paket ikon tambahan). */
enum class RoleGlyph { Page, Book, Bookmark, Panels, Flame, Stack, Pen, Gem, Star, Bolt, Infinity }

/**
 * Peran (gelar) pengguna. Tiap kali level mencapai [minLevel], peran baru terbuka.
 * [colors] = warna lencana (gradasi); [sweep] = gradasi memutar (khusus peran tertinggi).
 */
class Role(val title: String, val minLevel: Int, val glyph: RoleGlyph, val colors: List<Color>, val desc: String, val sweep: Boolean = false)

/** Urut naik menurut level. Ambang sama dengan tema bingkai level di [LevelChip]. */
val Roles: List<Role> = listOf(
    Role("Newbie", 1, RoleGlyph.Page, listOf(Color(0xFFBFDFCB), Color(0xFF5E9C7A)), "Baru membuka halaman pertama."),
    Role("Page Turner", 5, RoleGlyph.Book, listOf(Color(0xFFB4DCE6), Color(0xFF3F8FA6)), "Mulai rajin membuka chapter."),
    Role("Bookworm", 10, RoleGlyph.Bookmark, listOf(Color(0xFFE6C9A8), Color(0xFF9C6B45)), "Selalu menyimpan tempat bacamu."),
    Role("Panel Hunter", 20, RoleGlyph.Panels, listOf(Color(0xFFABC0E0), Color(0xFF4F6A9A)), "Tidak melewatkan satu panel pun."),
    Role("Binge Reader", 35, RoleGlyph.Flame, listOf(Color(0xFFF4B3C0), Color(0xFFD1506E)), "Maraton baca adalah keahlianmu."),
    Role("Series Master", 50, RoleGlyph.Stack, listOf(Color(0xFFF8D2A8), Color(0xFFE0773F)), "Rak seri demi rak kamu tamatkan. Api kecil menyala di atas lencana levelmu."),
    Role("Sensei", 100, RoleGlyph.Pen, listOf(Color(0xFFF6E8C6), Color(0xFFD4AC62), Color(0xFF9A7430)), "Tingkat emas. Karangan daun tipis di kedua sisi lencana levelmu."),
    Role("Curator", 500, RoleGlyph.Gem, listOf(Color(0xFFA6EDE4), Color(0xFF2FB5B0), Color(0xFF16787F)), "Tingkat zamrud. Cahaya toska lembut dan kristal kecil menghiasi lencana levelmu."),
    Role("Legend", 1000, RoleGlyph.Star, listOf(Color(0xFFC3CFFF), Color(0xFF6D86F2), Color(0xFF3A47B8)), "Tingkat nila. Planet mungil dan cincin orbit tipis dengan komet kecil."),
    Role("Mythic", 3000, RoleGlyph.Bolt, listOf(Color(0xFFEBC4FF), Color(0xFFB06CF2), Color(0xFF6A34B8)), "Tingkat anggrek. Cincin orbit, bulan sabit, dan kilau halus."),
    Role("Eternal", 5000, RoleGlyph.Infinity, listOf(Color(0xFFF7A8C4), Color(0xFFF3D39A), Color(0xFF9EE8CC), Color(0xFF9CC6F2), Color(0xFFC9A6F5)), "Tingkat tertinggi. Warna prisma yang berganti pelan, dua cincin orbit, dan mahkota kecil.", sweep = true)
)

fun roleFor(level: Int): Role = Roles.last { level >= it.minLevel }
fun roleIndex(level: Int): Int = Roles.indexOfLast { level >= it.minLevel }.coerceAtLeast(0)
fun nextRole(level: Int): Role? = Roles.firstOrNull { it.minLevel > level }

private val Ink = Color(0xFF17181C)

private fun poly(vararg p: Float): Path = Path().apply {
    moveTo(p[0], p[1]); var i = 2
    while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
    close()
}

/** Gambar satu bentuk di ruang 0..1 (pemanggil sudah men-skala). */
private fun DrawScope.glyph(g: RoleGlyph, b: Brush, ink: Color) {
    val bold = Stroke(0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(0.05f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (g) {
        RoleGlyph.Page -> {
            drawPath(Path().apply { moveTo(0.2f, 0.08f); lineTo(0.64f, 0.08f); lineTo(0.82f, 0.26f); lineTo(0.82f, 0.92f); lineTo(0.2f, 0.92f); close() }, b)
            drawPath(Path().apply { moveTo(0.64f, 0.08f); lineTo(0.64f, 0.26f); lineTo(0.82f, 0.26f) }, SolidColor(ink), style = thin)
            drawPath(Path().apply { moveTo(0.32f, 0.48f); lineTo(0.7f, 0.48f); moveTo(0.32f, 0.62f); lineTo(0.7f, 0.62f); moveTo(0.32f, 0.76f); lineTo(0.56f, 0.76f) }, SolidColor(ink), style = thin)
        }
        RoleGlyph.Book -> {
            drawPath(Path().apply { moveTo(0.5f, 0.3f); quadraticTo(0.3f, 0.18f, 0.08f, 0.24f); lineTo(0.08f, 0.78f); quadraticTo(0.3f, 0.72f, 0.5f, 0.84f); close() }, b)
            drawPath(Path().apply { moveTo(0.5f, 0.3f); quadraticTo(0.7f, 0.18f, 0.92f, 0.24f); lineTo(0.92f, 0.78f); quadraticTo(0.7f, 0.72f, 0.5f, 0.84f); close() }, b)
            drawPath(Path().apply { moveTo(0.5f, 0.32f); lineTo(0.5f, 0.84f) }, SolidColor(ink), style = thin)
        }
        RoleGlyph.Star -> {
            val p = Path()
            for (i in 0 until 10) {
                val a = (-90.0 + i * 36.0) * PI / 180.0
                val r = if (i % 2 == 0) 0.47f else 0.2f
                val x = 0.5f + r * cos(a).toFloat(); val y = 0.54f + r * sin(a).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, b)
            drawPath(p, b, style = Stroke(0.05f, join = StrokeJoin.Round))
        }
        RoleGlyph.Bookmark -> {
            drawPath(Path().apply { moveTo(0.24f, 0.08f); lineTo(0.76f, 0.08f); lineTo(0.76f, 0.92f); lineTo(0.5f, 0.7f); lineTo(0.24f, 0.92f); close() }, b)
            drawPath(Path().apply { moveTo(0.36f, 0.24f); lineTo(0.64f, 0.24f) }, SolidColor(ink), style = thin)
        }
        RoleGlyph.Panels -> {
            drawRoundRect(b, Offset(0.08f, 0.1f), Size(0.5f, 0.38f), CornerRadius(0.04f))
            drawRoundRect(b, Offset(0.64f, 0.1f), Size(0.28f, 0.38f), CornerRadius(0.04f))
            drawRoundRect(b, Offset(0.08f, 0.54f), Size(0.28f, 0.36f), CornerRadius(0.04f))
            drawRoundRect(b, Offset(0.42f, 0.54f), Size(0.5f, 0.36f), CornerRadius(0.04f))
        }
        RoleGlyph.Flame -> {
            drawPath(Path().apply {
                moveTo(0.5f, 0.06f)
                cubicTo(0.52f, 0.28f, 0.8f, 0.38f, 0.8f, 0.64f); cubicTo(0.8f, 0.82f, 0.66f, 0.94f, 0.5f, 0.94f)
                cubicTo(0.34f, 0.94f, 0.2f, 0.82f, 0.2f, 0.64f); cubicTo(0.2f, 0.5f, 0.28f, 0.4f, 0.34f, 0.32f)
                cubicTo(0.36f, 0.42f, 0.42f, 0.48f, 0.46f, 0.48f); cubicTo(0.44f, 0.34f, 0.46f, 0.18f, 0.5f, 0.06f); close()
            }, b)
            drawPath(Path().apply {
                moveTo(0.5f, 0.58f); cubicTo(0.63f, 0.68f, 0.65f, 0.79f, 0.5f, 0.87f); cubicTo(0.35f, 0.79f, 0.37f, 0.68f, 0.5f, 0.58f); close()
            }, SolidColor(ink))
        }
        RoleGlyph.Stack -> {
            drawRoundRect(b, Offset(0.1f, 0.14f), Size(0.7f, 0.2f), CornerRadius(0.04f))
            drawRoundRect(b, Offset(0.2f, 0.4f), Size(0.7f, 0.2f), CornerRadius(0.04f))
            drawRoundRect(b, Offset(0.12f, 0.66f), Size(0.76f, 0.2f), CornerRadius(0.04f))
            drawPath(Path().apply { moveTo(0.18f, 0.24f); lineTo(0.5f, 0.24f); moveTo(0.28f, 0.5f); lineTo(0.6f, 0.5f); moveTo(0.2f, 0.76f); lineTo(0.52f, 0.76f) }, SolidColor(ink), style = thin)
        }
        RoleGlyph.Pen -> {
            drawPath(Path().apply { moveTo(0.5f, 0.06f); lineTo(0.78f, 0.4f); lineTo(0.62f, 0.76f); lineTo(0.38f, 0.76f); lineTo(0.22f, 0.4f); close() }, b)
            drawPath(Path().apply { moveTo(0.5f, 0.3f); lineTo(0.5f, 0.56f) }, SolidColor(ink), style = thin)
            drawCircle(SolidColor(ink), 0.045f, Offset(0.5f, 0.62f))
            drawRoundRect(b, Offset(0.34f, 0.81f), Size(0.32f, 0.11f), CornerRadius(0.03f))
        }
        RoleGlyph.Gem -> {
            drawPath(poly(0.27f, 0.18f, 0.73f, 0.18f, 0.94f, 0.42f, 0.5f, 0.9f, 0.06f, 0.42f), b)
            val facet = SolidColor(ink)
            drawPath(Path().apply { moveTo(0.06f, 0.42f); lineTo(0.94f, 0.42f) }, facet, style = thin)
            drawPath(Path().apply { moveTo(0.27f, 0.18f); lineTo(0.38f, 0.42f); lineTo(0.5f, 0.9f) }, facet, style = thin)
            drawPath(Path().apply { moveTo(0.73f, 0.18f); lineTo(0.62f, 0.42f); lineTo(0.5f, 0.9f) }, facet, style = thin)
        }
        RoleGlyph.Bolt -> drawPath(poly(0.58f, 0.05f, 0.2f, 0.55f, 0.45f, 0.55f, 0.36f, 0.95f, 0.8f, 0.4f, 0.55f, 0.4f), b)
        RoleGlyph.Infinity -> {
            val p = Path()
            for (i in 0..72) {
                val t = i / 72.0 * 2 * PI
                val d = 1.0 + sin(t) * sin(t)
                val x = (0.5 + 0.42 * cos(t) / d).toFloat()
                val y = (0.5 + 0.6 * sin(t) * cos(t) / d).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, b, style = Stroke(0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * Lencana peran: medali bulat berwarna penuh (gradasi warna peran), bingkai lebih gelap, ikon putih di tengah.
 * [locked] = peran belum terbuka (abu-abu, tanpa warna).
 */
@Composable
fun RoleBadge(role: Role, size: Dp = 28.dp, modifier: Modifier = Modifier, locked: Boolean = false) {
    // Peran Sensei ke atas di ukuran besar hidup: halo berdenyut, bintang berkelip, percikan mengorbit (makin tinggi makin ramai);
    // Eternal juga memutar warna prismanya. Digambar dari jam animasi bersama [LvClock].
    val tier = Roles.indexOf(role)
    val live = false
    DisposableEffect(live) { if (live) LvClock.users++; onDispose { if (live) LvClock.users-- } }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        val rim = lerp(role.colors.last(), Color.Black, 0.3f)
        val ms = LvClock.ms
        val medal: Brush = when {
            locked -> SolidColor(Color(0xFF2C2E35))
            role.sweep -> Brush.sweepGradient(role.colors + role.colors.first(), c)
            else -> Brush.linearGradient(role.colors, Offset(0f, 0f), Offset(s, s))
        }
        if (live) {
            val pulse = 0.5f - 0.5f * cos(2f * PI.toFloat() * cycle(ms, 3200))
            val halo = if (role.sweep) cycleColor(role.colors, cycle(ms, 6500)) else role.colors[role.colors.size / 2]
            val hr = s * (0.56f + 0.06f * (tier - 6) / 4f + 0.03f * pulse)
            drawCircle(Brush.radialGradient(listOf(halo.copy(alpha = 0.22f + 0.16f * pulse), halo.copy(alpha = 0.07f), Color.Transparent), c, hr), hr, c)
            if (tier >= 8) nebula(ms, s, s, role.colors, 2, 0.12f)
        }
        drawCircle(if (locked) Color(0xFF3A3C44) else if (role.sweep) Color(0xFF3A2F5C) else rim, s / 2f)
        if (live && role.sweep) rotate(cycle(ms, 9000) * 360f, c) { drawCircle(medal, s / 2f - s * 0.06f) }
        else drawCircle(medal, s / 2f - s * 0.06f)
        if (!locked) drawCircle( // kilau lembut di bagian atas supaya terlihat seperti medali
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), 0f, s * 0.6f),
            s / 2f - s * 0.06f
        )
        val g = s * 0.56f; val o = (s - g) / 2f
        val glyphFill: Brush = SolidColor(if (locked) Color(0xFF4A4C53) else if (role.sweep) Color(0xFF1A1A24) else Color.White)
        val ink = if (locked) Color(0xFF2C2E35) else if (role.sweep) Color.White else lerp(role.colors.last(), Color.Black, 0.25f)
        withTransform({ translate(o, o); scale(g, g, Offset.Zero) }) { glyph(role.glyph, glyphFill, ink) }
        if (live) {
            // bintang berkelip di sekitar medali, percikan mengorbit tepi
            starfield(2 + (tier - 6) / 2, ms, s * 1.3f, s * 1.3f, tier, Color.White, s * 0.028f, Offset(-s * 0.15f, -s * 0.15f))
            val n = 1 + (tier - 6) / 4
            for (k in 0 until n) {
                val ang = cycle(ms, 4600 + k * 1300) * 2f * PI.toFloat() + k * 2.4f
                for (j in 0 until 6) {
                    val a = ang - j * 0.09f
                    drawCircle(Color.White.copy(alpha = (1f - j / 6f) * 0.9f), s * 0.028f * (1f - j * 0.1f), Offset(c.x + s * 0.5f * cos(a), c.y + s * 0.5f * sin(a)))
                }
            }
        }
    }
}

/** Ikon peran + nama peran, satu baris. */
@Composable
fun RoleTag(level: Int, modifier: Modifier = Modifier, iconSize: Dp = 22.dp, textSize: Int = 13) {
    val r = roleFor(level)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RoleBadge(r, iconSize)
        Text(r.title, color = Color(0xFFD7D9DE), fontSize = textSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 7.dp))
    }
}

/** Daftar semua peran: yang sudah terbuka berwarna, sisanya terkunci dengan syarat level. */
@Composable
fun RolesScreen(onBack: () -> Unit) {
    val user = Auth.user
    if (user == null) { Box(Modifier.fillMaxSize().background(Bg)); return }
    val cur = roleFor(user.level)
    val next = nextRole(user.level)
    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Peran & Lencana", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item("now") {
                Column(Modifier.fillMaxWidth().clip(R6).border(1.dp, Color(0xFF3A3C44), R6).background(CardBg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RoleBadge(cur, 84.dp)
                    Text(cur.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 12.dp))
                    LevelChip(user.level, Modifier.padding(top = 14.dp), size = 12)
                    Text(
                        if (next != null) "Peran berikutnya: ${next.title} di Lv. ${next.minLevel} (${next.minLevel - user.level} level lagi)" else "Kamu sudah mencapai peran tertinggi.",
                        color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp)
                    )
                    XpBar(user, Modifier.padding(top = 14.dp))
                }
                Text("Semua peran", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
            }
            items(Roles, key = { it.title }) { r ->
                val open = user.level >= r.minLevel
                val idx = Roles.indexOf(r)
                val max = Roles.getOrNull(idx + 1)?.minLevel?.minus(1)
                val range = if (max == null) "Lv. ${r.minLevel}+" else "Lv. ${r.minLevel} – $max"
                Row(Modifier.fillMaxWidth().then(if (r === cur) Modifier.clip(R6).background(CardBg) else Modifier).padding(horizontal = if (r === cur) 8.dp else 0.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RoleBadge(r, 46.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(r.title, color = r.colors.first(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        LvPill(r.minLevel, range, Modifier.padding(top = 9.dp, bottom = 3.dp), size = 11)
                        Text(r.desc, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    when {
                        r === cur -> Text("Sekarang", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                        open -> Text("✓ Tercapai", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                        else -> Text("${"%,d".format(java.util.Locale("id"), r.minLevel - user.level)} level lagi", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                HorizontalDivider(color = Line)
            }
            item("end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Muncul sekali saat level naik melewati ambang peran baru. */
@Composable
fun RoleUnlockDialog(role: Role, onClose: () -> Unit, onOpen: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose, containerColor = CardBg, shape = RoundedCornerShape(20.dp),
        title = { Text("Peran baru terbuka!", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                RoleBadge(role, 88.dp)
                Text(role.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 12.dp))
                Text("Tercapai di Lv. ${role.minLevel}. ${role.desc}", color = Muted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onOpen) { Text("Lihat semua peran", color = Color.White, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClose) { Text("Tutup", color = Muted) } }
    )
}
