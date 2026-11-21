package id.mitoon.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable data class RankEntry(
    val rank: Int = 0, val id: Long = 0, val username: String = "", val displayName: String = "", val avatarV: Int = 0,
    val level: Int = 1, val verified: Boolean = false, val isOwner: Boolean = false, val premium: Boolean = false, val weekXp: Int = 0
) { val name: String get() = displayName.ifBlank { username } }
@Serializable data class RankMe(val rank: Int = 0, val weekXp: Int = 0)
/** `GET v1/xp/leaderboard`: papan XP minggu berjalan. `resetAt`/`now` = jam server (epoch ms) supaya hitung mundur tidak bergantung jam perangkat. */
@Serializable data class RankBoard(
    val weekStart: Long = 0, val resetAt: Long = 0, val now: Long = 0, val total: Int = 0,
    val items: List<RankEntry> = emptyList(), val me: RankMe? = null
)

private val Gold = Color(0xFFE8C972)
private val Silver = Color(0xFFC4C8D2)
private val Bronze = Color(0xFFC98B5B)
private val R14 = RoundedCornerShape(14.dp)
private fun medal(rank: Int) = when (rank) { 1 -> Gold; 2 -> Silver; else -> Bronze }
private fun num(n: Int): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale("id", "ID")).format(n)

/** "3 hari 4 jam" / "5 jam 12 menit" / "9 menit" — dua satuan terbesar saja supaya ringkas. */
private fun countdown(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(0)
    val d = min / 1440; val h = (min % 1440) / 60; val m = min % 60
    return when { d > 0 -> "$d hari $h jam"; h > 0 -> "$h jam $m menit"; else -> "${maxOf(m, 1)} menit" }
}

/** Tab Ranking: papan peringkat XP mingguan (Senin 00.00 WIB → Senin berikutnya). */
@Composable
fun LeaderboardScreen(onSearch: () -> Unit) {
    val uid = Auth.user?.id
    // Urutan sumber tampil instan: memori (sudah dihangatkan saat app dibuka, lengkap dengan posisi akun ini) -> disk (tanpa `me`) -> server.
    val mem = Net.rankBoard(Auth.token)
    var board by remember { mutableStateOf(mem ?: Net.readSnap("rank-week", RankBoard.serializer())?.takeIf { it.resetAt > System.currentTimeMillis() }) }
    var synced by remember { mutableStateOf(mem != null) }
    var failed by remember { mutableStateOf(false) }
    var skew by remember { mutableLongStateOf(0L) }
    var attempt by remember { mutableIntStateOf(0) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(attempt) {
        failed = false
        while (true) {
            try {
                val r = Net.leaderboard()
                board = r; synced = true; skew = r.now - System.currentTimeMillis(); failed = false
            } catch (e: CancellationException) { throw e } catch (_: Exception) { if (board == null) failed = true }
            delay(60_000)
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); clock = System.currentTimeMillis() } }
    val openUser = LocalOpenUser.current
    val b = board
    val meId = uid

    Column(Modifier.fillMaxSize()) {
        Header(onSearch)
        if (b == null) {
            if (failed) Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("Gagal memuat peringkat", color = Muted)
                TextButton({ attempt++ }) { Text("Coba lagi", color = Accent) }
            } else Box(Modifier.fillMaxSize(), Alignment.Center) { MascotLoading(size = 120.dp) }
        } else {
            val podium = b.items.size >= 3
            val rest = if (podium) b.items.drop(3) else b.items
            val topXp = (b.items.firstOrNull()?.weekXp ?: 1).coerceAtLeast(1)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
                item("head") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Peringkat", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                                Text("XP mingguan", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            val left = b.resetAt - (clock + skew)
                            Row(Modifier.clip(RoundedCornerShape(50)).background(CardBg).border(1.dp, CardLine, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(if (left > 0) Color(0xFF6BD08A) else Gold))
                                Text(if (left > 0) "Reset ${countdown(left)}" else "Segera direset", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, modifier = Modifier.padding(start = 7.dp))
                            }
                        }
                        Text("XP dari membaca chapter, berkomentar, dan membuka aplikasi tiap hari. Reset setiap Senin 00.00 WIB.",
                            color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                item("me") { MeCard(b, synced, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) }
                if (b.items.isEmpty()) item("empty") {
                    Text("Belum ada yang mengumpulkan XP minggu ini. Jadilah yang pertama.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp))
                }
                if (podium) item("podium") { Podium(b.items.take(3), meId, openUser, Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp)) }
                if (rest.isNotEmpty()) item("rows") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).fillMaxWidth().clip(R14).background(CardBg).border(1.dp, Line, R14)) {
                        rest.forEachIndexed { i, e ->
                            RankRow(e, e.id == meId, topXp, openUser)
                            if (i < rest.lastIndex) HorizontalDivider(Modifier.padding(start = 64.dp), color = Line)
                        }
                    }
                }
                if (b.total > b.items.size) item("foot") {
                    Text("Menampilkan ${b.items.size} teratas dari ${b.total} pembaca", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 4.dp) {
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val f by animateFloatAsState(if (go) fraction.coerceIn(0f, 1f) else 0f, tween(600), label = "bar")
    Box(modifier.height(height).clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(color))
    }
}

/** Ringkasan posisi akun sendiri; tetap tampil walau di luar daftar teratas. */
@Composable
private fun MeCard(b: RankBoard, synced: Boolean, modifier: Modifier) {
    val me = b.me
    val shell = modifier.fillMaxWidth().clip(R14).background(Brush.linearGradient(listOf(Color(0xFF2A2B33), CardBg))).border(1.dp, CardLine, R14).padding(16.dp)
    if (!synced) {
        Column(shell) {
            Text("Peringkatmu", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Box(Modifier.padding(top = 8.dp).width(110.dp).height(30.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF)))
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)))
        }
        return
    }
    if (me == null) {
        Column(shell) {
            Text("Peringkatmu", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("Belum masuk papan minggu ini", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text("Baca satu chapter atau tulis komentar untuk mulai mengumpulkan XP.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        }
        return
    }
    val above = b.items.firstOrNull { it.rank == me.rank - 1 }
    Column(shell) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Peringkatmu", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                    Text("#${me.rank}", color = if (me.rank <= 3) medal(me.rank) else Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Text("dari ${num(b.total)}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(num(me.weekXp), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("XP minggu ini", color = Muted, fontSize = 12.sp)
            }
        }
        if (above != null) {
            Bar(me.weekXp.toFloat() / above.weekXp.coerceAtLeast(1), Color.White, Modifier.padding(top = 12.dp).fillMaxWidth())
            Text("${num(above.weekXp - me.weekXp)} XP lagi untuk naik ke peringkat ${above.rank}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        } else if (me.rank == 1) {
            Text("Kamu memimpin minggu ini", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Tiga teratas: urutan tampil 2-1-3, pedestal bertingkat bergradasi, mahkota untuk juara 1. */
@Composable
private fun Podium(top: List<RankEntry>, meId: Long?, onOpen: (Long) -> Unit, modifier: Modifier) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        listOf(top[1], top[0], top[2]).forEach { PodiumSlot(it, it.id == meId, onOpen, Modifier.weight(1f)) }
    }

@Composable
private fun Crown(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(width = 26.dp, height = 18.dp)) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.04f, h * 0.92f); lineTo(w * 0.0f, h * 0.22f); lineTo(w * 0.27f, h * 0.55f); lineTo(w * 0.5f, h * 0.0f)
        lineTo(w * 0.73f, h * 0.55f); lineTo(w * 1.0f, h * 0.22f); lineTo(w * 0.96f, h * 0.92f); close()
    }
    drawPath(p, color)
}

@Composable
private fun PodiumSlot(e: RankEntry, mine: Boolean, onOpen: (Long) -> Unit, modifier: Modifier) {
    val c = medal(e.rank)
    val first = e.rank == 1
    val avatar = if (first) 68.dp else 56.dp
    val base = when (e.rank) { 1 -> 84.dp; 2 -> 62.dp; else -> 48.dp }
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen(e.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
        if (first) Crown(c, Modifier.padding(bottom = 4.dp))
        Box(contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.drawBehind {
                drawCircle(Brush.radialGradient(listOf(c.copy(alpha = if (first) 0.35f else 0.22f), Color.Transparent), center = center, radius = size.minDimension * 0.9f), radius = size.minDimension * 0.9f)
            }.border(if (first) 3.dp else 2.dp, c, CircleShape).padding(3.dp)) { Avatar(e.id, e.username, e.avatarV, avatar) }
            Box(Modifier.offset(y = 9.dp).size(20.dp).clip(CircleShape).background(c), Alignment.Center) {
                Text("${e.rank}", color = Color(0xFF17181C), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(if (mine) "${e.name} (kamu)" else e.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            StatusBadges(e.verified, e.isOwner, e.premium, size = 13.dp)
        }
        LevelChip(e.level, Modifier.padding(top = 4.dp))
        Text("${num(e.weekXp)} XP", color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(base).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.30f), CardBg)))) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(c))
            Text("${e.rank}", color = c.copy(alpha = 0.9f), fontSize = if (first) 30.sp else 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun RankRow(e: RankEntry, mine: Boolean, topXp: Int, onOpen: (Long) -> Unit) =
    Row(Modifier.fillMaxWidth().background(if (mine) Color(0x1AFFFFFF) else Color.Transparent).clickable { onOpen(e.id) }.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${e.rank}", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(32.dp))
        Avatar(e.id, e.username, e.avatarV, 40.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (mine) "${e.name} (kamu)" else e.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                StatusBadges(e.verified, e.isOwner, e.premium, size = 14.dp)
            }
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                LevelChip(e.level)
                Bar(e.weekXp.toFloat() / topXp, if (mine) Color.White else Color(0xFF7C8090), Modifier.padding(start = 8.dp).weight(1f), 3.dp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(num(e.weekXp), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("XP", color = Muted, fontSize = 11.sp)
        }
    }
