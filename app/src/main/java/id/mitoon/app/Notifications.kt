package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data class NotifActor(val id: Long = 0, val username: String = "", val displayName: String = "", val avatarV: Int = 0) {
    val name: String get() = displayName.ifBlank { username }
}
/**
 * kind: "reply" (membalas komentarmu) atau "like" (menyukai komentarmu).
 * commentId = komentar yang dimaksud (balasan baru / komentarmu yang di-like); parentId = id komentar teratas bila commentId itu sebuah balasan.
 * Dua field ini diisi server; kalau kosong (0), ketukan hanya membuka seri/chapter-nya.
 */
@Serializable data class NotifItem(
    val id: String = "", val kind: String = "reply", val at: Long = 0, val unread: Boolean = false,
    val target: String = "", val text: String = "", val actor: NotifActor = NotifActor(),
    val commentId: Long = 0, val parentId: Long? = null
)
@Serializable data class NotifPage(val seenAt: Long = 0, val items: List<NotifItem> = emptyList())
@Serializable data class NotifCount(val count: Int = 0)

/** Jumlah belum dibaca (notifikasi + pengumuman) untuk pil merah di navigasi bawah. */
object NotifState {
    var unread by mutableIntStateOf(0)
    suspend fun refresh() { runCatching { Net.api.notifUnread().data.count }.onSuccess { unread = it } }
}

private val Red = Color(0xFFE5484D)

@Composable
fun RedPill(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.clip(RoundedCornerShape(50)).background(Red).padding(horizontal = 6.dp, vertical = 1.dp), Alignment.Center) {
        Text(if (count > 99) "99+" else "$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Tab bawah "Notifikasi": balasan & like di komentarmu. Pengumuman admin tampil di Home, bukan di sini. */
@Composable
fun NotificationsScreen(onOpen: (CommentOrigin) -> Unit) {
    val openUser = LocalOpenUser.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<NotifPage?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        err = null
        try {
            page = Net.api.notifications().data
            runCatching { Net.api.notifRead() }.onSuccess { NotifState.unread = 0 } // dibaca = pil merah hilang; tanda "baru" di daftar ini tetap
        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (page == null) err = e.friendly() }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Text("Notifikasi", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        HorizontalDivider(color = Line)
        val rows = page?.items
        when {
            rows == null && err != null -> Retry(err!!) { reload++ }
            rows == null -> Loading()
            rows.isEmpty() -> Empty("Belum ada notifikasi.\nBalasan dan like di komentarmu akan muncul di sini.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { n ->
                    NotifRow(n, onAvatar = { openUser(n.actor.id) }) {
                        scope.launch {
                            val o = runCatching { CommentHistory.resolve(n.target) }.getOrNull() ?: return@launch
                            // Titip komentar yang dituju; seri/chapter yang terbuka akan menggulir ke sana dan menyorotnya.
                            if (n.commentId > 0) CommentFocus.set(n.target, n.commentId, n.parentId)
                            onOpen(o)
                        }
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
    }
}

@Composable
private fun NotifRow(n: NotifItem, onAvatar: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (n.unread) Color(0xFF20222A) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Avatar(n.actor.id, n.actor.username, n.actor.avatarV, 44.dp, Modifier.clickable(onClick = onAvatar), CircleShape)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(n.actor.name) }
                append(if (n.kind == "like") " menyukai komentarmu" else " membalas komentarmu")
            }, color = Color(0xFFD7D9DE), fontSize = 14.sp, lineHeight = 20.sp)
            if (n.text.isNotBlank()) Text("“${n.text}”", color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Text(agoText(n.at), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (n.unread) Box(Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape).background(Red))
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), Color.White, strokeWidth = 2.dp) }

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
    Text(text, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Retry(msg: String, onRetry: () -> Unit) = Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
    Text(msg, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    TextButton(onRetry) { Text("Coba lagi", color = Color.White, fontWeight = FontWeight.Bold) }
}
