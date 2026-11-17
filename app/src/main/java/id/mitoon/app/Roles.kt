package id.mitoon.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
enum class RoleGlyph { Sprout, Book, Shield, Trophy, Star, Flame, Crown, Gem, Sun, Bolt, Infinity }

/**
 * Peran (gelar) pengguna. Tiap kali level mencapai [minLevel], peran baru terbuka.
 * [colors] = warna lencana (gradasi); [sweep] = gradasi memutar (khusus peran tertinggi).
 */
class Role(val title: String, val minLevel: Int, val glyph: RoleGlyph, val colors: List<Color>, val desc: String, val sweep: Boolean = false)

/** Urut naik menurut level. Ambang sama dengan tema bingkai level di [LevelChip]. */
val Roles: List<Role> = listOf(
    Role("Pemula", 1, RoleGlyph.Sprout, listOf(Color(0xFF8EE3A0), Color(0xFF2FA865)), "Awal perjalananmu di MiToon."),
    Role("Pembaca", 5, RoleGlyph.Book, listOf(Color(0xFF7FE0F5), Color(0xFF1E9BC4)), "Mulai rajin membuka chapter."),
    Role("Ksatria", 10, RoleGlyph.Shield, listOf(Color(0xFFF0B27A), Color(0xFFB5651D)), "Penjaga rak komik favoritmu."),
    Role("Juara", 20, RoleGlyph.Trophy, listOf(Color(0xFFA9B4FF), Color(0xFF5B63E6)), "Konsisten naik peringkat."),
    Role("Legenda", 35, RoleGlyph.Star, listOf(Color(0xFFFFD98A), Color(0xFFFF9F43)), "Namamu mulai dikenal pembaca lain."),
    Role("Mitos", 50, RoleGlyph.Flame, listOf(Color(0xFFFFA48A), Color(0xFFFF4D6A)), "Semangat membacamu menyala."),
    Role("Maha", 100, RoleGlyph.Crown, listOf(Color(0xFFFFF1C2), Color(0xFFE8C972), Color(0xFFB8903A)), "Tingkat emas. Bingkai level berwarna emas."),
    Role("Maestro", 500, RoleGlyph.Gem, listOf(Color(0xFFB6F5E0), Color(0xFF3FD1A1), Color(0xFF1E8F6B)), "Tingkat zamrud. Bingkai level berkilau."),
    Role("Agung", 1000, RoleGlyph.Sun, listOf(Color(0xFFC4E0FF), Color(0xFF5AA9FF), Color(0xFF3B6FE0)), "Tingkat safir. Bingkai level berputar pelan."),
    Role("Dewa", 3000, RoleGlyph.Bolt, listOf(Color(0xFFFFC2F0), Color(0xFFB58CFF), Color(0xFF7A4DE0)), "Tingkat ametis. Bingkai level berdenyut."),
    Role("Abadi", 5000, RoleGlyph.Infinity, listOf(Color(0xFFFF8AD8), Color(0xFFFFD98A), Color(0xFF8AFFD1), Color(0xFF8AC8FF), Color(0xFFC79BFF)), "Tingkat tertinggi. Warna prisma.", sweep = true)
)

fun roleFor(level: Int): Role = Roles.last { level >= it.minLevel }
fun nextRole(level: Int): Role? = Roles.firstOrNull { it.minLevel > level }

/** Gelar berdasarkan level (nama peran). */
fun rankTitle(level: Int): String = roleFor(level).title

private val Ink = Color(0xFF17181C)
private val LockedInk = Color(0xFF4A4C53)

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
        RoleGlyph.Sprout -> {
            drawPath(Path().apply { moveTo(0.5f, 0.9f); lineTo(0.5f, 0.46f) }, b, style = bold)
            drawPath(Path().apply { moveTo(0.5f, 0.58f); quadraticTo(0.12f, 0.62f, 0.14f, 0.24f); quadraticTo(0.5f, 0.24f, 0.5f, 0.58f); close() }, b)
            drawPath(Path().apply { moveTo(0.5f, 0.46f); quadraticTo(0.5f, 0.12f, 0.88f, 0.14f); quadraticTo(0.88f, 0.46f, 0.5f, 0.46f); close() }, b)
        }
        RoleGlyph.Book -> {
            drawPath(Path().apply { moveTo(0.5f, 0.3f); quadraticTo(0.3f, 0.18f, 0.08f, 0.24f); lineTo(0.08f, 0.78f); quadraticTo(0.3f, 0.72f, 0.5f, 0.84f); close() }, b)
            drawPath(Path().apply { moveTo(0.5f, 0.3f); quadraticTo(0.7f, 0.18f, 0.92f, 0.24f); lineTo(0.92f, 0.78f); quadraticTo(0.7f, 0.72f, 0.5f, 0.84f); close() }, b)
            drawPath(Path().apply { moveTo(0.5f, 0.32f); lineTo(0.5f, 0.84f) }, SolidColor(ink), style = thin)
        }
        RoleGlyph.Shield -> {
            drawPath(Path().apply {
                moveTo(0.5f, 0.08f); lineTo(0.86f, 0.22f); lineTo(0.86f, 0.5f)
                quadraticTo(0.86f, 0.78f, 0.5f, 0.93f); quadraticTo(0.14f, 0.78f, 0.14f, 0.5f); lineTo(0.14f, 0.22f); close()
            }, b)
            drawPath(Path().apply { moveTo(0.33f, 0.44f); lineTo(0.5f, 0.6f); lineTo(0.67f, 0.44f) }, SolidColor(ink), style = bold)
        }
        RoleGlyph.Trophy -> {
            drawPath(Path().apply {
                moveTo(0.28f, 0.12f); lineTo(0.72f, 0.12f); lineTo(0.72f, 0.36f)
                quadraticTo(0.72f, 0.6f, 0.5f, 0.62f); quadraticTo(0.28f, 0.6f, 0.28f, 0.36f); close()
            }, b)
            drawPath(Path().apply { moveTo(0.28f, 0.2f); cubicTo(0.03f, 0.16f, 0.05f, 0.52f, 0.31f, 0.5f) }, b, style = thin)
            drawPath(Path().apply { moveTo(0.72f, 0.2f); cubicTo(0.97f, 0.16f, 0.95f, 0.52f, 0.69f, 0.5f) }, b, style = thin)
            drawRect(b, Offset(0.45f, 0.6f), Size(0.1f, 0.16f))
            drawRoundRect(b, Offset(0.29f, 0.76f), Size(0.42f, 0.11f), CornerRadius(0.03f))
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
        RoleGlyph.Crown -> {
            drawPath(poly(0.12f, 0.74f, 0.08f, 0.3f, 0.32f, 0.5f, 0.5f, 0.2f, 0.68f, 0.5f, 0.92f, 0.3f, 0.88f, 0.74f), b)
            drawRoundRect(b, Offset(0.12f, 0.79f), Size(0.76f, 0.11f), CornerRadius(0.03f))
            for (c in listOf(Offset(0.08f, 0.28f), Offset(0.5f, 0.18f), Offset(0.92f, 0.28f))) drawCircle(b, 0.05f, c)
        }
        RoleGlyph.Gem -> {
            drawPath(poly(0.27f, 0.18f, 0.73f, 0.18f, 0.94f, 0.42f, 0.5f, 0.9f, 0.06f, 0.42f), b)
            val facet = SolidColor(ink)
            drawPath(Path().apply { moveTo(0.06f, 0.42f); lineTo(0.94f, 0.42f) }, facet, style = thin)
            drawPath(Path().apply { moveTo(0.27f, 0.18f); lineTo(0.38f, 0.42f); lineTo(0.5f, 0.9f) }, facet, style = thin)
            drawPath(Path().apply { moveTo(0.73f, 0.18f); lineTo(0.62f, 0.42f); lineTo(0.5f, 0.9f) }, facet, style = thin)
        }
        RoleGlyph.Sun -> {
            drawCircle(b, 0.2f, Offset(0.5f, 0.5f))
            for (i in 0 until 8) {
                val a = i * PI / 4.0
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                drawPath(Path().apply { moveTo(0.5f + 0.31f * c, 0.5f + 0.31f * s); lineTo(0.5f + 0.45f * c, 0.5f + 0.45f * s) }, b, style = Stroke(0.08f, cap = StrokeCap.Round))
            }
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
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2f, s / 2f)
        val rim = lerp(role.colors.last(), Color.Black, 0.3f)
        val medal: Brush = when {
            locked -> SolidColor(Color(0xFF2C2E35))
            role.sweep -> Brush.sweepGradient(role.colors + role.colors.first(), c)
            else -> Brush.linearGradient(role.colors, Offset(0f, 0f), Offset(s, s))
        }
        drawCircle(if (locked) Color(0xFF3A3C44) else if (role.sweep) Color(0xFF3A2F5C) else rim, s / 2f)
        drawCircle(medal, s / 2f - s * 0.06f)
        if (!locked) drawCircle( // kilau lembut di bagian atas supaya terlihat seperti medali
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), 0f, s * 0.6f),
            s / 2f - s * 0.06f
        )
        val g = s * 0.56f; val o = (s - g) / 2f
        val glyphFill: Brush = SolidColor(if (locked) Color(0xFF4A4C53) else if (role.sweep) Color(0xFF1A1A24) else Color.White)
        val ink = if (locked) Color(0xFF2C2E35) else if (role.sweep) Color.White else lerp(role.colors.last(), Color.Black, 0.25f)
        withTransform({ translate(o, o); scale(g, g, Offset.Zero) }) { glyph(role.glyph, glyphFill, ink) }
    }
}

/** Ikon peran + nama peran, satu baris. Owner menimpa judul menjadi "Asal" dengan teks ungu berkilau. */
@Composable
fun RoleTag(level: Int, modifier: Modifier = Modifier, iconSize: Dp = 22.dp, textSize: Int = 13, isOwner: Boolean = false) {
    if (isOwner) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            OwnerBadge(size = iconSize)
            Text("Asal", color = Color(0xFFC4B0FF), fontSize = textSize.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        }
        return
    }
    val r = roleFor(level)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RoleBadge(r, iconSize)
        Text(r.title, color = Color(0xFFD7D9DE), fontSize = textSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
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
                if (user.isOwner) {
                    Column(Modifier.fillMaxWidth().clip(R6).border(1.dp, Color(0xFF7B6AA8), R6).background(CardBg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        OwnerBadge(size = 72.dp)
                        Text("Asal", color = Color(0xFFC4B0FF), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 12.dp))
                        Text("Mark pendiri · sumber MiToon", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        LevelChip(user.level, Modifier.padding(top = 10.dp), size = 12)
                        XpBar(user, Modifier.padding(top = 14.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Column(Modifier.fillMaxWidth().clip(R6).border(1.dp, Color(0xFF3A3C44), R6).background(CardBg).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RoleBadge(cur, 84.dp)
                    Text(cur.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 12.dp))
                    LevelChip(user.level, Modifier.padding(top = 8.dp), size = 12)
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
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoleBadge(r, 46.dp, locked = !open)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.title, color = if (open) Color.White else Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (r === cur) Text("  ·  Saat ini", color = r.colors.first(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(r.desc, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (open) Text("Lv. ${r.minLevel}", color = Color(0xFFD7D9DE), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = Muted, modifier = Modifier.size(14.dp))
                        Text(" Lv. ${r.minLevel}", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
        onDismissRequest = onClose, containerColor = CardBg, shape = R6,
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
