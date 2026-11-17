package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data class NotifActor(val id: Long = 0, val username: String = "", val displayName: String = "", val avatarV: Int = 0) {
    val name: String get() = displayName.ifBlank { username }
}
/** kind: "reply" (membalas komentarmu) atau "like" (menyukai komentarmu). */
@Serializable data class NotifItem(
    val id: String = "", val kind: String = "reply", val at: Long = 0, val unread: Boolean = false,
    val target: String = "", val text: String = "", val actor: NotifActor = NotifActor()
)
@Serializable data class NotifPage(val seenAt: Long = 0, val items: List<NotifItem> = emptyList())
@Serializable data class NotifCount(val count: Int = 0)

/** Jumlah belum dibaca (notifikasi + pengumuman) untuk pil merah di navigasi bawah. */
object NotifState {
    var unread by mutableIntStateOf(0)
    suspend fun refresh() { runCatching { Net.api.notifUnread().data.count }.onSuccess { unread = it } }
}

private val Red = Color(0xFFE5484D)
private val Card16 = RoundedCornerShape(12.dp)

@Composable
fun RedPill(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.clip(RoundedCornerShape(50)).background(Red).padding(horizontal = 6.dp, vertical = 1.dp), Alignment.Center) {
        Text(if (count > 99) "99+" else "$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Tab bawah "Notifikasi": dua bagian — Notifikasi (balasan & like komentarmu) dan Informasi (pengumuman dari admin untuk semua akun). */
@Composable
fun NotificationsScreen(onOpen: (CommentOrigin) -> Unit) {
    val openUser = LocalOpenUser.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<NotifPage?>(null) }
    var info by remember { mutableStateOf<List<Notice>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var infoErr by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        err = null; infoErr = null
        coroutineScope {
            launch {
                try { info = Net.api.announcements(30).data }
                catch (e: CancellationException) { throw e } catch (e: Exception) { if (info == null) infoErr = e.friendly() }
            }
            launch {
                try {
                    page = Net.api.notifications().data
                    runCatching { Net.api.notifRead() }.onSuccess { NotifState.unread = 0 } // dibaca = pil merah hilang; tanda "baru" di daftar ini tetap
                } catch (e: CancellationException) { throw e } catch (e: Exception) { if (page == null) err = e.friendly() }
            }
        }
    }

    val seen = page?.seenAt
    val notifNew = page?.items?.count { it.unread } ?: 0
    val infoNew = if (seen == null) 0 else info?.count { it.createdAt > seen } ?: 0

    Column(Modifier.fillMaxSize().background(Bg)) {
        Text("Notifikasi", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        Row(Modifier.fillMaxWidth()) {
            TabLabel("Notifikasi", notifNew, tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabLabel("Informasi", infoNew, tab == 1, Modifier.weight(1f)) { tab = 1 }
        }
        HorizontalDivider(color = Line)
        if (tab == 0) {
            val rows = page?.items
            when {
                rows == null && err != null -> Retry(err!!) { reload++ }
                rows == null -> Loading()
                rows.isEmpty() -> Empty("Belum ada notifikasi.\nBalasan dan like di komentarmu akan muncul di sini.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { n ->
                        NotifRow(n, onAvatar = { openUser(n.actor.id) }) { scope.launch { runCatching { CommentHistory.resolve(n.target) }.getOrNull()?.let(onOpen) } }
                        HorizontalDivider(color = Line)
                    }
                }
            }
        } else {
            val list = info
            when {
                list == null && infoErr != null -> Retry(infoErr!!) { reload++ }
                list == null -> Loading()
                list.isEmpty() -> Empty("Belum ada informasi dari admin.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.id }) { n -> InfoCard(n, unread = seen != null && n.createdAt > seen) }
                }
            }
        }
    }
}

@Composable
private fun TabLabel(label: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (selected) Color.White else Muted, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            RedPill(count, Modifier.padding(start = 6.dp))
        }
        Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.6f).height(2.dp).background(if (selected) Color.White else Color.Transparent))
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
private fun InfoCard(n: Notice, unread: Boolean) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().clip(Card16).border(1.dp, if (unread) Red.copy(alpha = 0.6f) else CardLine, Card16).background(CardBg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DARI ADMIN", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (n.createdAt > 0) Text(agoText(n.createdAt), color = Muted, fontSize = 11.sp)
            if (unread) Box(Modifier.padding(start = 8.dp).size(9.dp).clip(CircleShape).background(Red))
        }
        Text(n.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        if (n.body.isNotBlank()) Text(n.body, color = Color(0xFFD7D9DE), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
        if (!n.url.isNullOrBlank()) Text("Buka tautan", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp).clip(R6).border(1.dp, Color.White, R6).clickable { runCatching { uri.openUri(n.url) } }.padding(horizontal = 14.dp, vertical = 7.dp))
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
