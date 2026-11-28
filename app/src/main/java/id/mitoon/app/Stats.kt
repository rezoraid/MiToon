package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val STATS_DAY_MS = 86_400_000L

/** Ringkasan dari Library (lokal): chapter & komik dibaca, aktivitas 7/30 hari terakhir (berdasar waktu baca terakhir tiap komik), dan komik paling banyak dibaca. */
private class ReadStats(val chapters: Int, val series: Int, val bookmarks: Int, val last7: Int, val last30: Int, val top: List<LibEntry>) {
    companion object {
        fun from(all: Collection<LibEntry>, now: Long): ReadStats {
            val read = all.filter { it.readAt > 0 }
            return ReadStats(
                chapters = all.sumOf { it.read.size },
                series = read.size,
                bookmarks = all.count { it.bookmarked },
                last7 = read.count { it.readAt > now - 7 * STATS_DAY_MS },
                last30 = read.count { it.readAt > now - 30 * STATS_DAY_MS },
                top = read.filter { it.read.isNotEmpty() }.sortedByDescending { it.read.size }.take(3)
            )
        }
    }
}

private val Num = java.text.NumberFormat.getIntegerInstance(java.util.Locale("id", "ID"))

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(R6).background(CardBg).border(1.dp, Line, R6).padding(14.dp)) {
        Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Statistik Membaca: streak + pengingat, angka ringkas, dan 3 komik paling banyak dibaca. */
@Composable
fun ReadingStatsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { while (true) { Streak.refreshDay(); delay(60_000) } }
    val entries = Library.entries
    val st = remember(entries) { ReadStats.from(entries.values, System.currentTimeMillis()) }
    val n = Streak.current
    val hot = Streak.hot
    val user = Auth.user
    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Statistik Membaca", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Streak + pengingat
            Column(Modifier.fillMaxWidth().clip(R6).background(CardBg).border(1.dp, Line, R6).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Flame(44.dp, hot)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(if (n > 0) "Streak $n hari" else "Belum ada streak", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            when { hot -> "Sudah membaca hari ini"; n > 0 -> "Baca hari ini supaya apimu tidak padam"; else -> "Baca satu chapter untuk menyalakan api" },
                            color = Muted, fontSize = 12.sp
                        )
                    }
                }
                if (!Auth.isGuest) {
                    HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Pengingat streak", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Notifikasi pukul 20.00 WIB bila streak-mu belum diselamatkan hari ini", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        Switch(
                            StreakReminder.enabled,
                            { on -> StreakReminder.setEnabled(ctx, on); if (on) Updates.ensurePermission(ctx) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color.White,
                                uncheckedThumbColor = Muted, uncheckedTrackColor = Bg, uncheckedBorderColor = Line)
                        )
                    }
                }
            }
            // Angka ringkas
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Num.format(st.chapters), "Chapter dibaca", Modifier.weight(1f))
                StatTile(Num.format(st.series), "Komik dibaca", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Num.format(st.last7), "Komik dibaca 7 hari terakhir", Modifier.weight(1f))
                StatTile(Num.format(st.last30), "Komik dibaca 30 hari terakhir", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Num.format(st.bookmarks), "Bookmark", Modifier.weight(1f))
                if (user != null) StatTile("Lv ${user.level}", "${Num.format(user.xp)} XP", Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            }
            // Paling banyak dibaca
            if (st.top.isNotEmpty()) {
                Text("Paling banyak dibaca", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                st.top.forEachIndexed { i, e ->
                    Row(Modifier.fillMaxWidth().clip(R6).background(CardBg).border(1.dp, Line, R6).clickable { onOpen(e.id) }.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = Muted, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(18.dp))
                        Img(e.cover, e.provider, Modifier.width(44.dp).aspectRatio(0.5f).clip(RoundedCornerShape(2.dp)), maxW = 240)
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${Num.format(e.read.size)} chapter dibaca", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            } else Text("Mulai membaca untuk melihat statistikmu di sini.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
