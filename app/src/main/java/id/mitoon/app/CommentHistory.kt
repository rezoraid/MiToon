package id.mitoon.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Di mana komentar ditulis: seri (chapterId == null) atau chapter tertentu. */
@Serializable data class CommentOrigin(
    val seriesId: String, val title: String, val cover: String, val provider: String,
    val chapterId: String? = null, val chapterNumber: String? = null
)

@Serializable data class MyComment(
    val id: Long, val userId: Long, val body: String, val createdAt: Long, val isReply: Boolean, val origin: CommentOrigin
)

/**
 * Riwayat komentar yang pernah kita kirim. Server belum menyediakan daftar "komentar saya",
 * jadi dicatat di perangkat ini (per akun) setiap kali komentar/balasan berhasil terkirim.
 */
object CommentHistory {
    private const val MAX = 300
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences
    private var all by mutableStateOf<List<MyComment>>(emptyList())

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("comment_history", Context.MODE_PRIVATE)
        all = runCatching { json.decodeFromString<List<MyComment>>(prefs.getString("v", "[]")!!) }.getOrDefault(emptyList())
    }

    /** Milik akun yang sedang masuk, terbaru di atas. */
    val mine: List<MyComment> get() { val uid = Auth.user?.id ?: return emptyList(); return all.filter { it.userId == uid } }

    private fun save(list: List<MyComment>) {
        all = list
        prefs.edit().putString("v", json.encodeToString(list)).apply()
    }

    fun add(c: Comment, o: CommentOrigin) {
        val uid = Auth.user?.id ?: return
        val m = MyComment(c.id, uid, c.body, if (c.createdAt > 0) c.createdAt else System.currentTimeMillis(), c.parentId != null, o)
        save((listOf(m) + all.filter { it.id != c.id }).sortedByDescending { it.createdAt }.take(MAX))
    }

    fun remove(id: Long) { if (all.any { it.id == id }) save(all.filter { it.id != id }) }
}

private fun agoText(ms: Long): String {
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

/** Halaman "Riwayat komentar": tiap baris menunjukkan seri/chapter tempat kita berkomentar; ketuk untuk membukanya. */
@Composable
fun CommentHistoryScreen(onBack: () -> Unit, onOpen: (MyComment) -> Unit) {
    val items = CommentHistory.mine
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) }
            Text("Riwayat komentar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        HorizontalDivider(color = Line)
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                Text("Belum ada komentar.\nKomentar yang kamu kirim dari aplikasi ini akan tercatat di sini.",
                    color = Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            items(items, key = { it.id }) { m ->
                val o = m.origin
                Row(Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Img(o.cover, o.provider, Modifier.width(44.dp).height(60.dp).clip(R6))
                    Column(Modifier.weight(1f)) {
                        Text(o.title.ifBlank { "Seri" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val where = (o.chapterNumber?.let { "Chapter $it" } ?: "Halaman seri") + (if (m.isReply) " · Balasan" else "")
                        Text("$where · ${agoText(m.createdAt)}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                        Text(m.body, color = Color(0xFFD7D9DE), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                HorizontalDivider(color = Line)
            }
        }
    }
}
