package id.mitoon.app

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
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
import kotlinx.serialization.builtins.ListSerializer

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
@Serializable data class NotifPage(val seenAt: Long = 0, val infoSeenAt: Long = 0, val items: List<NotifItem> = emptyList())
@Serializable data class NotifCount(val count: Int = 0, val notif: Int = 0, val info: Int = 0)

/** Belum dibaca untuk pil merah di navigasi bawah: `notif` = balasan & like (tab Aktivitas), `info` = pengumuman admin (tab Informasi). */
object NotifState {
    var notif by mutableIntStateOf(0)
    var info by mutableIntStateOf(0)
    val unread: Int get() = notif + info
    fun clear() { notif = 0; info = 0 }
    suspend fun refresh() {
        runCatching { Net.unread() }.onSuccess {
            // Server lama hanya mengirim `count` total: tampilkan saja sebagai total.
            if (it.notif + it.info == 0 && it.count > 0) { notif = it.count; info = 0 } else { notif = it.notif; info = it.info }
        }
    }
}

private val Red = Color(0xFFE5484D)
private val Unread = Color(0xFF20222A)
internal val Soft = Color(0xFFD7D9DE)

@Composable
fun RedPill(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.clip(RoundedCornerShape(50)).background(Red).padding(horizontal = 6.dp, vertical = 1.dp), Alignment.Center) {
        Text(if (count > 99) "99+" else "$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

private val noticeSer = ListSerializer(Notice.serializer())

/** Halaman "Notifikasi" (lonceng di header kanan atas): dua tab — Aktivitas (balasan & like komentarmu) dan Informasi (pengumuman admin). */
@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpen: (CommentOrigin) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<NotifPage?>(null) }
    var notices by remember { mutableStateOf(Net.readSnap("info-notices", noticeSer)) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var viewed by remember { mutableStateOf(setOf<Int>()) } // tab yang sudah dibuka pada kunjungan ini (pilnya hilang)

    LaunchedEffect(reload) {
        err = null
        try {
            page = Net.api.notifications().data
        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (page == null) err = e.friendly() }
    }
    LaunchedEffect(reload) {
        try {
            val r = Net.api.announcements(50).data
            notices = r; Net.writeSnap("info-notices", noticeSer, r)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    // Membuka sebuah tab = tab itu dianggap dibaca (pil merah hilang); sorotan "baru" di daftar tetap sampai keluar layar.
    LaunchedEffect(tab, page != null) {
        if (page == null) return@LaunchedEffect
        viewed = viewed + tab
        runCatching { Net.api.notifRead(if (tab == 0) "notif" else "info") }
        NotifState.refresh()
    }

    val newActivity = if (0 in viewed) 0 else page?.items?.count { it.unread } ?: 0
    val infoSeen = page?.infoSeenAt ?: Long.MAX_VALUE
    val newInfo = if (1 in viewed) 0 else notices?.count { it.createdAt > infoSeen } ?: 0

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar("Notifikasi", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            NotifTab("Aktivitas", tab == 0, newActivity, Modifier.weight(1f)) { tab = 0 }
            NotifTab("Informasi", tab == 1, newInfo, Modifier.weight(1f)) { tab = 1 }
        }
        HorizontalDivider(color = Line)
        if (tab == 0) ActivityList(page, err, onRetry = { reload++ }, onOpen) else InfoList(notices, page?.infoSeenAt ?: Long.MAX_VALUE)
    }
}

@Composable
private fun NotifTab(label: String, selected: Boolean, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (selected) Color.White else Muted, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            RedPill(count, Modifier.padding(start = 6.dp))
        }
        Box(Modifier.fillMaxWidth(0.5f).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) Color.White else Color.Transparent))
    }
}

// ---------------------------------------------------------------- Aktivitas
@Composable
private fun ActivityList(page: NotifPage?, err: String?, onRetry: () -> Unit, onOpen: (CommentOrigin) -> Unit) {
    val openUser = LocalOpenUser.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    val rows = page?.items
    when {
        rows == null && err != null -> Retry(err, onRetry)
        rows == null -> Loading()
        rows.isEmpty() -> Empty("Belum ada aktivitas.\nBalasan dan like di komentarmu akan muncul di sini.")
        else -> {
            val fresh = rows.filter { it.unread }
            val older = rows.filterNot { it.unread }
            fun open(n: NotifItem) {
                if (opening) return
                opening = true
                scope.launch {
                    val o = runCatching { CommentHistory.resolve(n.target) }.getOrNull()
                    if (o == null) Toast.makeText(ctx, "Komentar tidak bisa dibuka (seri mungkin sudah dihapus)", Toast.LENGTH_SHORT).show()
                    else {
                        // Titip komentar yang dituju; seri/chapter yang terbuka akan menggulir ke sana dan menyorotnya.
                        if (n.commentId > 0) CommentFocus.set(n.target, n.commentId, n.parentId)
                        onOpen(o)
                    }
                    opening = false
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (fresh.isNotEmpty()) {
                    item(key = "h-new") { SectionLabel("Baru") }
                    items(fresh, key = { it.id }) { n -> ActivityRow(n, onAvatar = { openUser(n.actor.id) }) { open(n) } }
                }
                if (older.isNotEmpty()) {
                    item(key = "h-old") { SectionLabel(if (fresh.isEmpty()) "Terbaru" else "Sebelumnya") }
                    items(older, key = { it.id }) { n -> ActivityRow(n, onAvatar = { openUser(n.actor.id) }) { open(n) } }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp))

@Composable
private fun ActivityRow(n: NotifItem, onAvatar: () -> Unit, onClick: () -> Unit) {
    val like = n.kind == "like"
    // Asal komentar (judul seri + chapter) dicari pelan-pelan; baris sudah tampil lengkap sebelum itu selesai.
    val origin by produceState(CommentHistory.originOf(n.target), n.target) {
        if (value == null) value = runCatching { CommentHistory.resolve(n.target) }.getOrNull()
    }
    val o = origin
    Column {
        Row(
            Modifier.fillMaxWidth().background(if (n.unread) Unread else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.size(48.dp)) {
                Avatar(n.actor.id, n.actor.username, n.actor.avatarV, 44.dp, Modifier.clickable(onClick = onAvatar), CircleShape)
                if (like) Box(
                    Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(Red).border(2.dp, if (n.unread) Unread else Bg, CircleShape),
                    Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_thumb_up_filled), null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(n.actor.name) }
                    append(if (like) " menyukai komentarmu" else " membalas komentarmu")
                }, color = Soft, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (n.text.isNotBlank()) Row(Modifier.padding(top = 6.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(CardLine))
                    Text(n.text, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (n.unread) Box(Modifier.padding(end = 6.dp).size(7.dp).clip(CircleShape).background(Red))
                    val where = o?.let { it.title.ifBlank { "Seri" } + (it.chapterNumber?.let { c -> " · Ch. $c" } ?: "") }
                    Text(listOfNotNull(agoText(n.at), where).joinToString("  ·  "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (o != null) Img(o.cover, o.provider, Modifier.width(38.dp).height(54.dp).clip(R6))
        }
        HorizontalDivider(color = Line, modifier = Modifier.padding(start = 76.dp))
    }
}

// ---------------------------------------------------------------- Informasi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoList(notices: List<Notice>?, seenAt: Long) {
    var open by remember { mutableStateOf<Notice?>(null) }
    when {
        notices == null -> Loading()
        notices.isEmpty() -> Empty("Belum ada informasi.\nPengumuman dari admin akan muncul di sini.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(notices, key = { it.id }) { n ->
                NoticeRow(n, unread = n.createdAt > seenAt) { open = n }
                HorizontalDivider(color = Line, modifier = Modifier.padding(start = 76.dp))
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    open?.let { n ->
        val uri = LocalUriHandler.current
        ModalBottomSheet({ open = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp), containerColor = CardBg) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(plainText(n.title), color = Color.White, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.ExtraBold)
                if (n.createdAt > 0) Text(dateText(n.createdAt), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                if (n.body.isNotBlank()) Text(boldText(n.body), color = Soft, fontSize = 14.sp, lineHeight = 22.sp)
                if (!n.url.isNullOrBlank()) Button({ runCatching { uri.openUri(n.url) } }, Modifier.fillMaxWidth().padding(top = 20.dp), shape = R6,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text("Buka tautan", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Satu baris informasi. Dipakai tab Informasi dan pratinjau di panel admin. */
@Composable
fun NoticeRow(n: Notice, unread: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(if (unread) Unread else Color.Transparent).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF2A2C32)), Alignment.Center) {
            Icon(Icons.Default.Info, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(plainText(n.title), color = Color.White, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (n.body.isNotBlank()) Text(plainText(n.body), color = Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (unread) Box(Modifier.padding(end = 6.dp).size(7.dp).clip(CircleShape).background(Red))
                val whenText = if (n.createdAt <= 0) "Baru saja" else if (System.currentTimeMillis() - n.createdAt < 7 * 86_400_000L) agoText(n.createdAt) else dateText(n.createdAt)
                Text(whenText + if (!n.url.isNullOrBlank()) "  ·  Ada tautan" else "", color = Muted, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** Teks admin kadang berformat markdown ringan (**tebal**, # judul); untuk cuplikan satu-dua baris semua penanda dibuang. */
internal fun plainText(s: String) = s.replace("**", "").replace("`", "").replace(Regex("(?m)^#+\\s*"), "").replace(Regex("\\s*\\n\\s*"), " ").trim()

internal fun boldText(s: String) = buildAnnotatedString {
    s.replace(Regex("(?m)^#+\\s*"), "").split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(part) } else append(part)
    }
}

internal fun dateText(ms: Long): String = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("id")).format(java.util.Date(ms))

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), Alignment.Center) { MascotLoading(size = 120.dp) }

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
    Text(text, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Retry(msg: String, onRetry: () -> Unit) = Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
    Text(msg, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    TextButton(onRetry) { Text("Coba lagi", color = Color.White, fontWeight = FontWeight.Bold) }
}
