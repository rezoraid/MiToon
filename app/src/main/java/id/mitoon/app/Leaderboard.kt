package id.mitoon.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    // Papan terakhir dari disk tampil instan (hanya bila minggunya belum lewat); `me` tidak disimpan supaya tidak bocor antar akun.
    var board by remember { mutableStateOf(Net.readSnap("rank-week", RankBoard.serializer())?.takeIf { it.resetAt > System.currentTimeMillis() }) }
    var failed by remember { mutableStateOf(false) }
    var skew by remember { mutableLongStateOf(0L) } // jam server - jam perangkat
    var attempt by remember { mutableIntStateOf(0) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(attempt) {
        failed = false
        while (true) {
            try {
                val r = Net.api.leaderboard().data
                board = r; skew = r.now - System.currentTimeMillis(); failed = false
                Net.writeSnap("rank-week", RankBoard.serializer(), r.copy(me = null))
            } catch (e: CancellationException) { throw e } catch (_: Exception) { if (board == null) failed = true }
            delay(60_000)
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); clock = System.currentTimeMillis() } }
    val openUser = LocalOpenUser.current
    val b = board
    val meId = Auth.user?.id

    Column(Modifier.fillMaxSize()) {
        Header(onSearch)
        if (b == null) {
            if (failed) Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("Gagal memuat peringkat", color = Muted)
                TextButton({ attempt++ }) { Text("Coba lagi", color = Accent) }
            } else Box(Modifier.fillMaxSize(), Alignment.Center) { MascotLoading(size = 120.dp, label = "Memuat peringkat…") }
        } else {
            run {
                val podium = b.items.size >= 3
                val rest = if (podium) b.items.drop(3) else b.items
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item("head") {
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Peringkat XP Mingguan", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                                val left = b.resetAt - (clock + skew)
                                Text(if (left > 0) "Reset dalam ${countdown(left)}" else "Segera direset", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                    modifier = Modifier.clip(R6).border(1.dp, CardLine, R6).padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            Text("XP dari membaca chapter, berkomentar, dan membuka aplikasi tiap hari. Papan di-reset setiap Senin 00.00 WIB.",
                                color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    item("me") { MeCard(b, Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp)) }
                    if (b.items.isEmpty()) item("empty") {
                        Text("Belum ada yang mengumpulkan XP minggu ini. Jadilah yang pertama.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp))
                    }
                    if (podium) item("podium") { Podium(b.items.take(3), openUser, Modifier.padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 8.dp)) }
                    if (rest.isNotEmpty()) item("rows-top") { HorizontalDivider(Modifier.padding(top = 16.dp), color = Line) }
                    itemsIndexed(rest, key = { _, e -> e.id }) { _, e ->
                        RankRow(e, e.id == meId, openUser)
                        HorizontalDivider(color = Line)
                    }
                    if (b.total > b.items.size) item("foot") {
                        Text("Menampilkan ${b.items.size} teratas dari ${b.total} pembaca", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                    }
                }
            }
        }
    }
}

/** Ringkasan posisi akun sendiri; tetap tampil walau di luar daftar teratas. */
@Composable
private fun MeCard(b: RankBoard, modifier: Modifier) {
    val me = b.me
    Row(modifier.fillMaxWidth().clip(R6).background(CardBg).border(1.dp, CardLine, R6).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (me == null) {
            Column(Modifier.weight(1f)) {
                Text("Peringkatmu", color = Muted, fontSize = 12.sp)
                Text("Belum masuk papan minggu ini", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                Text("Baca satu chapter atau tulis komentar untuk mulai mengumpulkan XP.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
            }
            return@Row
        }
        val above = b.items.firstOrNull { it.rank == me.rank - 1 }
        Column(Modifier.weight(1f)) {
            Text("Peringkatmu", color = Muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                Text("#${me.rank}", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text("dari ${b.total} pembaca", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, bottom = 5.dp))
            }
            if (above != null) Text("Selisih ${num(above.weekXp - me.weekXp)} XP ke peringkat ${above.rank}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            else if (me.rank == 1) Text("Kamu memimpin minggu ini", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(num(me.weekXp), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("XP minggu ini", color = Muted, fontSize = 12.sp)
        }
    }
}

/** Tiga teratas: urutan tampil 2-1-3, pedestal bertingkat, bingkai emas/perak/perunggu. */
@Composable
private fun Podium(top: List<RankEntry>, onOpen: (Long) -> Unit, modifier: Modifier) =
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        listOf(top[1], top[0], top[2]).forEach { PodiumSlot(it, onOpen, Modifier.weight(1f)) }
    }

@Composable
private fun PodiumSlot(e: RankEntry, onOpen: (Long) -> Unit, modifier: Modifier) {
    val c = medal(e.rank)
    val avatar = if (e.rank == 1) 64.dp else 52.dp
    val base = when (e.rank) { 1 -> 68.dp; 2 -> 52.dp; else -> 40.dp }
    Column(modifier.clickable { onOpen(e.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.border(2.dp, c, CircleShape).padding(3.dp)) { Avatar(e.id, e.username, e.avatarV, avatar) }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(e.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            StatusBadges(e.verified, e.isOwner, e.premium, size = 13.dp)
        }
        LevelChip(e.level, Modifier.padding(top = 4.dp))
        Text("${num(e.weekXp)} XP", color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Column(Modifier.padding(top = 8.dp).fillMaxWidth().height(base).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(CardBg)) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(c))
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("${e.rank}", color = c, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable
private fun RankRow(e: RankEntry, mine: Boolean, onOpen: (Long) -> Unit) =
    Row(Modifier.fillMaxWidth().background(if (mine) Color(0x14FFFFFF) else Color.Transparent).clickable { onOpen(e.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("${e.rank}", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.width(38.dp))
        Avatar(e.id, e.username, e.avatarV, 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (mine) "${e.name} (kamu)" else e.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                StatusBadges(e.verified, e.isOwner, e.premium, size = 14.dp)
            }
            LevelChip(e.level, Modifier.padding(top = 3.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(num(e.weekXp), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("XP", color = Muted, fontSize = 11.sp)
        }
    }
