package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val id: Long = 0, val username: String = "", val displayName: String = "", val avatarV: Int = 0,
    val xp: Int = 0, val level: Int = 1, val joinedAt: Long = 0, val commentCount: Int = 0
) { val name: String get() = displayName.ifBlank { username } }

/** Halaman profil orang lain: foto, nama, level, ringkasan, lalu komentar-komentarnya (ketuk untuk membuka seri/chapter-nya). */
@Composable
fun UserProfileScreen(userId: Long, onBack: () -> Unit, onOpen: (CommentOrigin) -> Unit) {
    var user by remember(userId) { mutableStateOf<PublicUser?>(null) }
    var userErr by remember(userId) { mutableStateOf<String?>(null) }
    var items by remember(userId) { mutableStateOf<List<MyComment>>(emptyList()) }
    var hasMore by remember(userId) { mutableStateOf(false) }
    var loading by remember(userId) { mutableStateOf(true) }
    var listErr by remember(userId) { mutableStateOf(false) }
    var reload by remember(userId) { mutableIntStateOf(0) }

    suspend fun loadComments(before: Long?) {
        loading = true; listErr = false
        try {
            val p = Net.api.userComments(userId, before = before).data
            items = if (before == null) p.items else (items + p.items).distinctBy { it.id }
            hasMore = p.hasMore && p.items.isNotEmpty()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { listErr = true } finally { loading = false }
    }
    LaunchedEffect(userId, reload) {
        userErr = null
        try { user = Net.api.user(userId).data } catch (e: CancellationException) { throw e } catch (e: Exception) { if (user == null) userErr = e.friendly() }
        loadComments(null)
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
                    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(u.id, u.name, u.avatarV, 104.dp, shape = CircleShape)
                        Text(u.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                        Text("@${u.username}", color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            LevelChip(u.level, size = 12)
                            Text("  ${rankTitle(u.level)}", color = Color(0xFFD7D9DE), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Stat("${u.xp}", "Total XP")
                            Box(Modifier.padding(horizontal = 22.dp).width(1.dp).height(28.dp).background(Line))
                            Stat("${u.commentCount}", "Komentar")
                            if (u.joinedAt > 0) {
                                Box(Modifier.padding(horizontal = 22.dp).width(1.dp).height(28.dp).background(Line))
                                Stat(remember(u.joinedAt) { SimpleDateFormat("MMM yyyy", Locale("id", "ID")).format(Date(u.joinedAt)) }, "Bergabung")
                            }
                        }
                    }
                    HorizontalDivider(color = Line)
                    Text("Komentar", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                }
                if (items.isEmpty()) item("empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), Alignment.Center) {
                        when {
                            loading -> CircularProgressIndicator(Modifier.size(22.dp), Color.White, strokeWidth = 2.dp)
                            listErr -> TextButton({ reload++ }) { Text("Gagal memuat komentar. Coba lagi", color = Muted) }
                            else -> Text("Belum ada komentar.", color = Muted, fontSize = 14.sp)
                        }
                    }
                }
                items(items, key = { it.id }) { m -> HistoryCommentRow(m, onOpen); HorizontalDivider(color = Line) }
                if (hasMore) item("more") {
                    LaunchedEffect(items.size) { loadComments(items.lastOrNull()?.id) }
                    Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), Color.White, strokeWidth = 2.dp) }
                }
                item("end") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    Text(label, color = Muted, fontSize = 12.sp)
}
