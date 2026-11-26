package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
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
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Profil pengguna lain (`GET v1/users/:id`): tanpa email atau data akun. */
@Serializable data class PublicUser(
    val id: Long = 0, val username: String = "", val displayName: String = "", val bio: String = "", val avatarV: Int = 0,
    val xp: Long = 0, val level: Int = 1, val joinedAt: Long = 0, val commentCount: Int = 0,
    val verified: Boolean = false, val isOwner: Boolean = false, val premium: Boolean = false, val readingHidden: Boolean = false,
    /** Tampilan profil Premium (kosong = bawaan) */
    val theme: String = "", val frame: String = "", val headerV: Int = 0, val headerBlur: Int = 0, val headerPos: Int = 50,
    /** Streak membaca; null bila pemiliknya menyembunyikan aktivitas bacanya. */
    val streak: StreakRes? = null
) { val name: String get() = displayName.ifBlank { username } }

/** Halaman profil orang lain: foto, nama, level, ringkasan, lalu daftar komik yang dibaca + chapter terakhirnya (ketuk untuk melanjutkan baca). */
@Composable
fun UserProfileScreen(userId: Long, onBack: () -> Unit, onOpen: (ReadItem) -> Unit) {
    var user by remember(userId) { mutableStateOf<PublicUser?>(null) }
    var userErr by remember(userId) { mutableStateOf<String?>(null) }
    var items by remember(userId) { mutableStateOf<List<ReadItem>>(emptyList()) }
    var hasMore by remember(userId) { mutableStateOf(false) }
    var loading by remember(userId) { mutableStateOf(true) }
    var listErr by remember(userId) { mutableStateOf(false) }
    var reload by remember(userId) { mutableIntStateOf(0) }

    suspend fun loadReading(before: Long?) {
        loading = true; listErr = false
        try {
            val p = Net.api.userReading(userId, before = before).data
            items = if (before == null) p.items else (items + p.items).distinctBy { it.seriesId }
            hasMore = p.hasMore && p.items.isNotEmpty()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { listErr = true } finally { loading = false }
    }
    LaunchedEffect(userId, reload) {
        userErr = null
        try { user = Net.api.user(userId).data } catch (e: CancellationException) { throw e } catch (e: Exception) { if (user == null) userErr = e.friendly() }
        if (user?.readingHidden == true) { items = emptyList(); hasMore = false; loading = false } else loadReading(null)
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar(user?.name ?: "Profil", onBack)
        val u = user
        when {
            u == null && userErr != null -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(userErr!!, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                TextButton({ reload++ }) { Text("Coba lagi", color = Color.White, fontWeight = FontWeight.Bold) }
            }
            u == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), Color.White, strokeWidth = 2.dp) }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                item("head") {
                    val styled = u.premium && (u.theme.isNotEmpty() || u.headerV > 0)
                    Box(Modifier.fillMaxWidth().then(if (styled) Modifier.padding(top = 12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) else Modifier)) {
                    if (styled) ProfileBackdrop(u.id, u.theme, u.headerV, u.headerBlur, u.headerPos, 230.dp, fade = Bg)
                    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FramedAvatar(u.id, u.name, u.avatarV, 104.dp, if (u.premium) u.frame else "")
                        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(u.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (u.verified) VerifiedBadge(Modifier.padding(start = 6.dp), 20.dp)
                        }
                        Text("@${u.username}", color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        StatusLabels(u.isOwner, u.premium, Modifier.padding(top = 8.dp), iconSize = 20.dp, textSize = 13)
                        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LevelChip(u.level, size = 12)
                            RoleTag(u.level, Modifier, iconSize = 24.dp)
                        }
                        if (u.bio.isNotBlank()) Text(u.bio, color = Color(0xFFD7D9DE), fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 14.dp, start = 16.dp, end = 16.dp))
                        // Ringkasan sejajar: setiap kolom sama lebar; streak (api kecil + angka) hanya tampil bila ada, tanpa kapsul besar.
                        val streak = u.streak?.takeIf { it.current > 0 }
                        Row(Modifier.padding(top = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (streak != null) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Flame(22.dp, streak.readToday)
                                        Text("${streak.current}", color = if (streak.readToday) Color(0xFFFFB02E) else Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                    Text("Streak", color = Muted, fontSize = 12.sp)
                                }
                                StatDivider()
                            }
                            Stat("${u.xp}", "Total XP", Modifier.weight(1f))
                            StatDivider()
                            Stat("${u.commentCount}", "Komentar", Modifier.weight(1f))
                            if (u.joinedAt > 0) {
                                StatDivider()
                                Stat(remember(u.joinedAt) { SimpleDateFormat("MMM yyyy", Locale("id", "ID")).format(Date(u.joinedAt)) }, "Bergabung", Modifier.weight(1f))
                            }
                        }
                    }
                    }
                    HorizontalDivider(color = Line)
                    Text("Sedang dibaca", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                }
                if (items.isEmpty()) item("empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), Alignment.Center) {
                        when {
                            loading -> CircularProgressIndicator(Modifier.size(22.dp), Color.White, strokeWidth = 2.dp)
                            u.readingHidden -> Text(if (u.id == Auth.user?.id) "Daftar bacaanmu disembunyikan. Atur di menu Privasi." else "Daftar bacaan disembunyikan oleh pemilik akun.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                            listErr -> TextButton({ reload++ }) { Text("Gagal memuat daftar baca. Coba lagi", color = Muted) }
                            else -> Text("Belum ada komik yang dibaca.", color = Muted, fontSize = 14.sp)
                        }
                    }
                }
                items(items, key = { it.seriesId }) { r -> ReadRow(r, onOpen); HorizontalDivider(color = Line) }
                if (hasMore) item("more") {
                    LaunchedEffect(items.size) { loadReading(items.lastOrNull()?.readAt) }
                    Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), Color.White, strokeWidth = 2.dp) }
                }
                item("end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Satu komik yang dibaca: cover, judul, chapter terakhir, jumlah chapter dibaca, dan kapan. */
@Composable
private fun ReadRow(r: ReadItem, onOpen: (ReadItem) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(r) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Img(r.cover, r.provider, Modifier.width(44.dp).height(60.dp).clip(R6), maxW = 200)
        Column(Modifier.weight(1f)) {
            Text(r.title.ifBlank { "Seri" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.chapterNumber?.let { "Terakhir: Chapter $it" } ?: "Pernah dibuka", color = Color(0xFFD7D9DE), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Text(buildString { if (r.chaptersRead > 0) append("${r.chaptersRead} chapter dibaca · "); append(agoText(r.readAt)) },
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun StatDivider() = Box(Modifier.width(1.dp).height(28.dp).background(Line))

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    Text(label, color = Muted, fontSize = 12.sp)
}
