package id.mitoon.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Di mana komentar ditulis: seri (chapterId == null) atau chapter tertentu. */
@Serializable data class CommentOrigin(
    val seriesId: String, val title: String, val cover: String, val provider: String,
    val chapterId: String? = null, val chapterNumber: String? = null
)

/** Item riwayat dari server (`GET v1/comments/mine`); `target` = "s:<id seri>" atau "c:<id chapter>". */
@Serializable data class MyComment(
    val id: Long, val body: String = "", val createdAt: Long = 0, val parentId: Long? = null,
    val replyTo: String = "", val likes: Int = 0, val dislikes: Int = 0, val target: String = ""
)
@Serializable data class MyCommentPage(val total: Int = 0, val items: List<MyComment> = emptyList(), val hasMore: Boolean = false)

/**
 * Server hanya menyimpan `target`; judul/cover seri dicari di sini: dari cache, atau diambil sekali dari API lalu disimpan
 * (di perangkat) supaya riwayat berikutnya tampil langsung. Cache ini tidak berisi data akun.
 */
object CommentHistory {
    private const val MAX = 400
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences
    private val origins = LinkedHashMap<String, CommentOrigin>()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("comment_origins", Context.MODE_PRIVATE)
        runCatching { json.decodeFromString<Map<String, CommentOrigin>>(prefs.getString("v", "{}")!!) }.getOrNull()?.let { origins.putAll(it) }
    }

    fun originOf(target: String): CommentOrigin? = origins[target]

    /** Dicatat saat komentar terkirim (asal sudah diketahui, tak perlu request lagi). */
    fun remember(target: String, o: CommentOrigin) {
        origins.remove(target); origins[target] = o
        while (origins.size > MAX) origins.remove(origins.keys.first())
        prefs.edit().putString("v", json.encodeToString(origins.toMap())).apply()
    }

    fun clear() { origins.clear(); prefs.edit().clear().apply() }

    private suspend fun seriesOrigin(id: String, chapterId: String? = null, chapterNumber: String? = null): CommentOrigin? {
        val d = Net.detailCache[id] ?: Net.detail(id)
        return CommentOrigin(id, d.title, d.cover, d.provider, chapterId, chapterNumber)
    }

    /** Cari asal komentar dari `target`; null bila gagal (mis. offline atau seri sudah hilang). */
    suspend fun resolve(target: String): CommentOrigin? {
        origins[target]?.let { return it }
        val o = when {
            target.startsWith("s:") -> seriesOrigin(target.drop(2))
            target.startsWith("c:") -> {
                val cid = target.drop(2)
                val c = Net.cachedChapter(cid) ?: Net.chapter(cid)
                c.seriesId?.let { seriesOrigin(it, cid, c.number) }
            }
            else -> null
        } ?: return null
        remember(target, o)
        return o
    }
}

fun agoText(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "baru saja"
        s < 3600 -> "${s / 60} mnt lalu"
        s < 86400 -> "${s / 3600} jam lalu"
        s < 86400L * 30 -> "${s / 86400} hari lalu"
        s < 86400L * 365 -> "${s / (86400L * 30)} bln lalu"
        else -> "${s / (86400L * 365)} thn lalu"
    }
}

/** Riwayat Aktivitas: tab Komentar (di mana kita berkomentar) dan tab Baca (chapter yang dibaca). */
@Composable
fun ActivityHistoryScreen(onBack: () -> Unit, onOpenComment: (CommentOrigin) -> Unit, onOpenRead: (LibEntry) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Ic.Left, "Kembali", tint = Accent) }
            Text("Riwayat Aktivitas", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Komentar", "Baca").forEachIndexed { i, t ->
                Column(Modifier.weight(1f).clickable { tab = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t, color = if (tab == i) Color.White else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == i) Color.White else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = Line)
        if (tab == 0) CommentList(onOpenComment) else ReadList(onOpenRead)
    }
}

@Composable
private fun EmptyNote(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
    Text(text, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** Satu baris komentar + tempatnya (seri/chapter); judul & cover dicari lewat `CommentHistory.resolve`. Dipakai riwayat sendiri dan profil orang lain. */
@Composable
fun HistoryCommentRow(m: MyComment, onOpen: (CommentOrigin) -> Unit) {
    val origin by produceState(CommentHistory.originOf(m.target), m.target) {
        if (value == null) value = runCatching { CommentHistory.resolve(m.target) }.getOrNull()
    }
    val o = origin
    Row(Modifier.fillMaxWidth().clickable(enabled = o != null) { o?.let(onOpen) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (o != null) Img(o.cover, o.provider, Modifier.width(44.dp).height(60.dp).clip(R6), maxW = 200)
        else Box(Modifier.width(44.dp).height(60.dp).clip(R6).background(CardBg))
        Column(Modifier.weight(1f)) {
            Text(o?.title?.ifBlank { "Seri" } ?: "Memuat…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = (if (m.target.startsWith("c:")) o?.chapterNumber?.let { "Chapter $it" } ?: "Chapter" else "Halaman seri") + (if (m.parentId != null) " · Balasan" else "")
            Text("$where · ${agoText(m.createdAt)}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            Text(m.body, color = Color(0xFFD7D9DE), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun CommentList(onOpen: (CommentOrigin) -> Unit) {
    var items by remember { mutableStateOf<List<MyComment>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    suspend fun load(before: Long?) {
        loading = true; error = null
        try {
            val p = Net.api.myComments(before = before).data
            items = if (before == null) p.items else (items + p.items).distinctBy { it.id }
            hasMore = p.hasMore && p.items.isNotEmpty()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.friendly() } finally { loading = false }
    }
    LaunchedEffect(reload) { load(null) }

    when {
        items.isEmpty() && loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), Color.White, strokeWidth = 2.dp) }
        items.isEmpty() && error != null -> Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(error!!, color = Muted, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton({ reload++ }) { Text("Coba lagi", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        items.isEmpty() -> EmptyNote("Belum ada komentar.")
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(items, key = { it.id }) { m -> HistoryCommentRow(m, onOpen); HorizontalDivider(color = Line) }
            if (hasMore) item("more") {
                LaunchedEffect(items.size) { load(items.lastOrNull()?.id) }
                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), Color.White, strokeWidth = 2.dp) }
            }
        }
    }
}

@Composable
private fun ReadList(onOpen: (LibEntry) -> Unit) {
    val items = Library.entries.values.filter { it.readAt > 0 }.sortedByDescending { it.readAt }
    if (items.isEmpty()) { EmptyNote("Belum ada chapter yang dibaca."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(items, key = { it.id }) { e ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Img(e.cover, e.provider, Modifier.width(44.dp).height(60.dp).clip(R6), maxW = 200)
                Column(Modifier.weight(1f)) {
                    Text(e.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.lastChapterNumber?.let { "Terakhir: Chapter $it" } ?: "Pernah dibuka", color = Color(0xFFD7D9DE), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("${e.read.size} chapter dibaca · ${agoText(e.readAt)}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            HorizontalDivider(color = Line)
        }
    }
}
