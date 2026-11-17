package id.mitoon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

// ---------------------------------------------------------------- model
@Serializable data class CommentUser(val id: Long = 0, val username: String = "", val avatarV: Int = 0, val displayName: String = "", val level: Int = 1, val verified: Boolean = false, val isOwner: Boolean = false) {
    val name: String get() = displayName.ifBlank { username }
}
@Serializable data class Comment(
    val id: Long, val body: String, val createdAt: Long = 0, val user: CommentUser = CommentUser(),
    /** id komentar teratas bila ini balasan; null = komentar teratas */
    val parentId: Long? = null,
    /** nama yang dibalas (hanya saat membalas sebuah balasan) */
    val replyTo: String = "",
    val likes: Int = 0, val dislikes: Int = 0,
    /** suara kita: 1 = like, -1 = dislike, 0 = belum */
    val myVote: Int = 0,
    val replyCount: Int = 0,
    /** komentar owner yang disematkan di atas ("top komentar"); diisi server di halaman pertama */
    val pinned: Boolean = false
)
@Serializable data class CommentPage(val total: Int = 0, val items: List<Comment> = emptyList(), val hasMore: Boolean = false)
@Serializable data class CommentReq(val target: String, val body: String, val parentId: Long? = null)
@Serializable data class CommentRes(val data: Comment, val me: AuthUser? = null)
@Serializable data class VoteReq(val value: Int)
@Serializable data class VoteRes(val likes: Int = 0, val dislikes: Int = 0, val myVote: Int = 0)

// ---------------------------------------------------------------- potongan UI kecil
/** Dipanggil saat nama/foto penulis komentar diketuk (id pengguna); diisi oleh navigasi aplikasi. */
val LocalOpenUser = androidx.compose.runtime.staticCompositionLocalOf<(Long) -> Unit> { {} }

/** Foto profil persegi; tanpa foto = huruf pertama username. */
@Composable
fun Avatar(userId: Long, username: String, version: Int, size: Dp, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = R6) {
    val url = Auth.avatarUrl(userId, version)
    Box(modifier.size(size).clip(shape).background(Color(0xFF2A2C32)), Alignment.Center) {
        if (url != null) Img(url, "", Modifier.fillMaxSize())
        else Text(username.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

/** Ikon balon komentar (tidak ada di paket ikon inti). */
@Composable
fun CommentIcon(tint: Color = Color.White, dp: Dp = 22.dp) = Canvas(Modifier.size(dp)) {
    val w = this.size.width
    val h = this.size.height
    val p = Path().apply {
        moveTo(w * 0.14f, h * 0.16f); lineTo(w * 0.86f, h * 0.16f); lineTo(w * 0.86f, h * 0.68f)
        lineTo(w * 0.46f, h * 0.68f); lineTo(w * 0.26f, h * 0.88f); lineTo(w * 0.26f, h * 0.68f)
        lineTo(w * 0.14f, h * 0.68f); close()
    }
    drawPath(p, tint, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
}

private fun ago(ms: Long): String {
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

/** Balasan satu komentar teratas (dimuat bertahap, lama → baru). */
data class ReplyThread(
    val items: List<Comment> = emptyList(), val expanded: Boolean = false, val loading: Boolean = false,
    val loaded: Boolean = false, val hasMore: Boolean = false, val error: Boolean = false,
    /** id balasan terakhir yang diambil dari server (penanda halaman berikutnya) */
    val cursor: Long = 0
)

/**
 * Satu "feed" komentar per target ("s:<id seri>" / "c:<id chapter>"). Hidup di luar layar (cache memori + snapshot disk),
 * jadi bisa diisi diam-diam sebelum tab/sheet dibuka -> saat dibuka datanya sudah ada dan langsung tampil.
 * Berisi komentar teratas (`rows`), balasan per komentar (`threads`), like/dislike, dan status "sedang membalas".
 */
class CommentFeed(val target: String) {
    /** Seri/chapter pemilik feed ini; diisi layar yang menampilkannya, dipakai untuk riwayat komentar. */
    var origin: CommentOrigin? = null
    var rows by mutableStateOf<List<Comment>>(emptyList()); private set
    var total by mutableIntStateOf(0); private set
    var hasMore by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set       // true hanya selama belum ada data sama sekali
    var error by mutableStateOf<String?>(null); private set
    var loadingMore by mutableStateOf(false); private set
    var moreError by mutableStateOf(false); private set
    var posting by mutableStateOf(false); private set
    var sendError by mutableStateOf<String?>(null)
    /** id komentar owner yang baru dikirim: memicu efek spesial sekali di baris itu */
    var freshId by mutableLongStateOf(0L); private set
    var threads by mutableStateOf<Map<Long, ReplyThread>>(emptyMap()); private set
    var replyingTo by mutableStateOf<Comment?>(null); private set
    private var fetchedAt = 0L
    private var busy = false
    private var diskRead = false
    private val voting = HashSet<Long>()

    private fun snapshot() = Net.storeComments(target, CommentPage(total, rows.take(PAGE), hasMore || rows.size > PAGE))

    /** Ambil halaman pertama. Data lama tetap tampil selama menunggu; UI hanya berubah bila isinya memang beda. */
    fun refresh(force: Boolean = false) {
        if (busy) return
        if (!force && rows.isNotEmpty() && System.currentTimeMillis() - fetchedAt < 30_000) return
        busy = true
        if (rows.isEmpty()) { loading = true; error = null }
        MainScope().launch {
            try {
                if (rows.isEmpty() && !diskRead) {
                    diskRead = true
                    Net.readComments(target)?.let { if (rows.isEmpty()) { rows = it.items; total = it.total; hasMore = it.hasMore; loading = false } }
                }
                val p = Net.api.comments(target, limit = PAGE).data
                // Halaman lama yang sudah dimuat user (scroll ke bawah) dipertahankan supaya daftar tidak melompat.
                val lastNormal = p.items.lastOrNull { !it.pinned }?.id
                val tail = if (p.hasMore && lastNormal != null) rows.filter { it.id < lastNormal && !it.pinned } else emptyList()
                val merged = (p.items + tail).distinctBy { it.id }
                if (merged != rows) rows = merged
                if (total != p.total) total = p.total
                hasMore = if (tail.isNotEmpty()) hasMore else p.hasMore
                fetchedAt = System.currentTimeMillis(); error = null
                snapshot()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (rows.isEmpty()) error = e.friendly() }
            finally { loading = false; busy = false }
        }
    }

    fun loadMore() {
        if (loadingMore || loading || rows.isEmpty() || !hasMore) return
        loadingMore = true; moreError = false
        MainScope().launch {
            try {
                val p = Net.api.comments(target, limit = PAGE, before = rows.lastOrNull { !it.pinned }?.id).data
                val seen = rows.mapTo(HashSet()) { it.id }
                val fresh = p.items.filter { it.id !in seen }
                rows = rows + fresh
                total = p.total
                hasMore = p.hasMore && fresh.isNotEmpty() // tidak ada lanjutan -> hentikan pemuat
            } catch (e: CancellationException) { throw e } catch (_: Exception) { moreError = true } finally { loadingMore = false }
        }
    }

    // ------------------------------------------------------------ kirim, balas, hapus
    fun startReply(c: Comment) { replyingTo = c }
    fun cancelReply() { replyingTo = null }

    fun send(body: String, onSent: () -> Unit) {
        if (body.isBlank() || posting) return
        posting = true; sendError = null
        val reply = replyingTo
        MainScope().launch {
            try {
                val res = Net.api.addComment(CommentReq(target, body.trim(), reply?.id))
                val c = res.data
                res.me?.let { Auth.update(it) } // XP/level terbaru
                origin?.let { CommentHistory.remember(target, it) }
                val root = c.parentId
                if (root == null) {
                    if (c.user.isOwner) { rows = pinOwner(c, rows); freshId = c.id }
                    else { val top = rows.takeWhile { it.pinned }; rows = top + c + rows.drop(top.size) } // di bawah komentar owner yang disematkan
                }
                else {
                    rows = rows.map { if (it.id == root) it.copy(replyCount = it.replyCount + 1) else it }
                    val t = threads[root] ?: ReplyThread()
                    if (t.loaded) setThread(root) { it.copy(items = (it.items + c).distinctBy { x -> x.id }.sortedBy { x -> x.id }, expanded = true) }
                    else { setThread(root) { it.copy(expanded = true) }; loadReplies(root) } // belum pernah dimuat: ambil dari server (sudah memuat balasan baru)
                }
                total += 1; replyingTo = null; onSent(); snapshot()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { sendError = e.friendly() } finally { posting = false }
        }
    }

    fun delete(c: Comment) {
        MainScope().launch {
            try {
                Net.api.deleteComment(c.id)
                val root = c.parentId
                if (root == null) {
                    rows = rows.filter { it.id != c.id }; threads = threads - c.id
                    total = maxOf(0, total - 1 - c.replyCount)
                } else {
                    setThread(root) { it.copy(items = it.items.filter { x -> x.id != c.id }) }
                    rows = rows.map { if (it.id == root) it.copy(replyCount = maxOf(0, it.replyCount - 1)) else it }
                    total = maxOf(0, total - 1)
                }
                if (replyingTo?.id == c.id) replyingTo = null
                snapshot()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { sendError = e.friendly() }
        }
    }

    // ------------------------------------------------------------ balasan
    private fun setThread(id: Long, f: (ReplyThread) -> ReplyThread) { threads = threads + (id to f(threads[id] ?: ReplyThread())) }

    /** Buka/tutup balasan; pertama kali dibuka diambil dari server. */
    fun toggleReplies(c: Comment) {
        val t = threads[c.id]
        if (t == null || !t.loaded) { setThread(c.id) { it.copy(expanded = true) }; loadReplies(c.id) }
        else setThread(c.id) { it.copy(expanded = !it.expanded) }
    }

    fun loadReplies(root: Long) {
        val t = threads[root] ?: ReplyThread()
        if (t.loading) return
        setThread(root) { it.copy(loading = true, error = false) }
        MainScope().launch {
            try {
                val p = Net.api.replies(root, after = t.cursor.takeIf { it > 0 }).data
                setThread(root) { cur ->
                    cur.copy(items = (cur.items + p.items).distinctBy { it.id }.sortedBy { it.id }, loaded = true, hasMore = p.hasMore,
                        loading = false, cursor = p.items.lastOrNull()?.id ?: cur.cursor)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { setThread(root) { it.copy(loading = false, error = true) } }
        }
    }

    // ------------------------------------------------------------ like / dislike
    private fun Comment.withVote(v: Int) = copy(
        likes = likes - (if (myVote == 1) 1 else 0) + (if (v == 1) 1 else 0),
        dislikes = dislikes - (if (myVote == -1) 1 else 0) + (if (v == -1) 1 else 0),
        myVote = v
    )

    private fun patch(id: Long, f: (Comment) -> Comment) {
        if (rows.any { it.id == id }) rows = rows.map { if (it.id == id) f(it) else it }
        else threads = threads.mapValues { (_, t) -> if (t.items.any { it.id == id }) t.copy(items = t.items.map { x -> if (x.id == id) f(x) else x }) else t }
    }

    /** Ketuk like/dislike: tampil seketika (optimistik), lalu disamakan dengan angka dari server; ketuk lagi = batal. */
    fun vote(c: Comment, tap: Int) {
        if (Auth.token == null || !voting.add(c.id)) return
        val next = if (c.myVote == tap) 0 else tap
        patch(c.id) { it.withVote(next) }
        MainScope().launch {
            try {
                val r = Net.api.voteComment(c.id, VoteReq(next)).data
                patch(c.id) { it.copy(likes = r.likes, dislikes = r.dislikes, myVote = r.myVote) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                patch(c.id) { it.withVote(c.myVote) }; sendError = e.friendly()
            } finally { voting.remove(c.id) }
        }
    }
}

private const val PAGE = 20
/** Sama dengan MAX_PINNED di server: komentar owner terbaru yang disematkan di puncak daftar. */
private const val MAX_PINNED = 3

/** Sematkan komentar owner baru di paling atas; yang melewati batas turun ke urutan waktu biasa. */
private fun pinOwner(c: Comment, rows: List<Comment>): List<Comment> {
    val owners = (listOf(c) + rows.filter { it.pinned }).sortedByDescending { it.id }
    val top = owners.take(MAX_PINNED).map { it.copy(pinned = true) }
    val demoted = owners.drop(MAX_PINNED).map { it.copy(pinned = false) }
    val rest = (demoted + rows.filter { !it.pinned }).sortedByDescending { it.id }
    return top + rest
}

/** Daftar feed yang pernah dibuka/di-prefetch (maks 24 target, yang paling lama tak dipakai dibuang). */
object Comments {
    private val map = object : LinkedHashMap<String, CommentFeed>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CommentFeed>?) = size > 24
    }
    fun of(target: String): CommentFeed = map.getOrPut(target) { CommentFeed(target) }
    /** Isi diam-diam di belakang layar (1 request kecil), dipanggil beberapa saat setelah halaman tampil. */
    fun prefetch(target: String) = of(target).refresh()
}


@Composable
private fun CommentRow(c: Comment, mine: Boolean, feed: CommentFeed, isReply: Boolean = false) {
    val whenText = remember(c.createdAt) { ago(c.createdAt) }
    val openUser = LocalOpenUser.current
    val openAuthor: () -> Unit = { if (c.user.id > 0) openUser(c.user.id) }
    val owner = c.user.isOwner
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).then(if (owner) Modifier.ownerCard(feed.freshId == c.id) else Modifier), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(c.user.id, c.user.name, c.user.avatarV, if (isReply) 30.dp else 38.dp, Modifier.clickable(onClick = openAuthor))
        Column(Modifier.weight(1f)) {
            if (c.pinned) OwnerText("TOP KOMENTAR · OWNER", Modifier.padding(bottom = 3.dp), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (owner) OwnerText(c.user.name, Modifier.weight(1f, fill = false).clickable(onClick = openAuthor), fontSize = 13.sp)
                else Text(c.user.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).clickable(onClick = openAuthor))
                StatusBadges(c.user.verified, c.user.isOwner, size = 14.dp)
                RoleBadge(roleFor(c.user.level), 16.dp, Modifier.padding(start = 6.dp))
                LevelChip(c.user.level, Modifier.padding(start = 4.dp), size = 9)
                Text("  ·  $whenText", color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            if (c.replyTo.isNotBlank()) Text("Membalas ${c.replyTo}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(c.body, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 3.dp))
            // Baris aksi: like · dislike · balas · (hapus). Digeser kiri sebesar padding chip supaya ikon sejajar dengan teks di atasnya.
            Row(Modifier.offset(x = (-8).dp).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                VoteChip(up = true, active = c.myVote == 1, count = c.likes) { feed.vote(c, 1) }
                VoteChip(up = false, active = c.myVote == -1, count = c.dislikes) { feed.vote(c, -1) }
                ActionChip({ Icon(painterResource(R.drawable.ic_reply), null, tint = Muted, modifier = Modifier.size(16.dp)) }, "Balas") { feed.startReply(c) }
                if (mine) ActionChip(null, "Hapus") { feed.delete(c) }
            }
        }
    }
}

/** Tombol like/dislike: ikon garis = belum, ikon penuh + putih = sudah dipilih; angka tampil bila > 0. */
@Composable
private fun VoteChip(up: Boolean, active: Boolean, count: Int, onClick: () -> Unit) {
    val tint = if (active) Color.White else Muted
    Row(Modifier.clip(R6).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(if (active) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up), if (up) "Suka" else "Tidak suka", tint = tint,
            modifier = Modifier.size(17.dp).rotate(if (up) 0f else 180f))
        if (count > 0) Text("$count", color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun ActionChip(icon: (@Composable () -> Unit)?, label: String, onClick: () -> Unit) {
    Row(Modifier.clip(R6).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.invoke()
        Text(label, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = if (icon != null) 5.dp else 0.dp))
    }
}

/** "— Lihat 3 balasan" / "— Sembunyikan balasan", sejajar dengan teks komentar. */
@Composable
private fun ReplyToggle(c: Comment, t: ReplyThread?, feed: CommentFeed) {
    val expanded = t != null && t.expanded
    val count = maxOf(c.replyCount, t?.items?.size ?: 0)
    Row(Modifier.padding(start = 42.dp).clip(R6).clickable { feed.toggleReplies(c) }.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(22.dp).height(1.dp).background(Color(0xFF4A4C53)))
        Text(if (expanded) "  Sembunyikan balasan" else "  Lihat $count balasan", color = Color(0xFFD7D9DE), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Kerangka statis selama komentar belum ada (tanpa animasi -> tidak ada kedutan, tinggi sudah mirip aslinya). */
@Composable
private fun CommentSkeleton() = Column {
    repeat(3) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).background(Color(0xFF2A2C32), R6))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(110.dp).height(12.dp).background(Color(0xFF2A2C32), R6))
                Box(Modifier.fillMaxWidth(0.8f).height(12.dp).background(Color(0xFF24262C), R6))
            }
        }
    }
}

/** Kolom tulis komentar (dipakai di tab detail seri dan di sheet chapter). Saat membalas, muncul bilah "Membalas …". */
@Composable
fun CommentComposer(feed: CommentFeed, modifier: Modifier = Modifier) {
    var text by rememberSaveable(feed.target) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val reply = feed.replyingTo
    LaunchedEffect(reply?.id) { if (reply != null) runCatching { focus.requestFocus() } }
    Column(modifier) {
        if (reply != null) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(R6).background(Color(0xFF24262C)).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_reply), null, tint = Muted, modifier = Modifier.size(14.dp))
            Text("  Membalas ${reply.user.name}", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton({ feed.cancelReply() }, Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Batal membalas", tint = Muted, modifier = Modifier.size(16.dp)) }
        }
        val mutedUntil = Auth.user?.mutedUntil ?: 0L
        if (mutedUntil > System.currentTimeMillis()) Text("Kamu dibisukan ${muteUntilText(mutedUntil)} dan tidak bisa berkomentar.", color = Color(0xFFFFB86B), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        feed.sendError?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { if (it.length <= 1000) text = it }, Modifier.weight(1f).focusRequester(focus), maxLines = 4, shape = R6,
                placeholder = { Text(if (reply != null) "Tulis balasan…" else "Tulis komentar…", color = Muted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF4A4C53),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                    focusedContainerColor = Bg, unfocusedContainerColor = Bg))
            Button({ feed.send(text) { text = "" } }, Modifier.height(56.dp), enabled = text.isNotBlank() && !feed.posting, shape = R6,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF3A3C42), disabledContentColor = Muted)) {
                if (feed.posting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                else Text(if (reply != null) "Balas" else "Kirim", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Isi daftar komentar sebagai item LazyColumn (satu scroll, tanpa list bersarang) - dipakai tab detail dan sheet. */
fun LazyListScope.commentItems(feed: CommentFeed) {
    val rows = feed.rows
    val meId = Auth.user?.id
    when {
        rows.isEmpty() && feed.error != null -> item("c-err", contentType = "msg") {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(feed.error.orEmpty(), color = Muted, fontSize = 14.sp)
                Text("Coba lagi", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp).clip(R6)
                    .border(1.dp, Color.White, R6).clickable { feed.refresh(force = true) }.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        rows.isEmpty() && feed.loading -> item("c-load", contentType = "skeleton") { CommentSkeleton() }
        rows.isEmpty() -> item("c-empty", contentType = "msg") {
            Text("Belum ada komentar.\nJadilah yang pertama!", color = Muted, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        else -> {
            rows.forEach { c ->
                item("c${c.id}", contentType = "row") { CommentRow(c, c.user.id == meId, feed) }
                val t = feed.threads[c.id]
                if (c.replyCount > 0 || (t != null && t.items.isNotEmpty())) item("t${c.id}", contentType = "toggle") { ReplyToggle(c, t, feed) }
                if (t != null && t.expanded) {
                    t.items.forEach { r ->
                        item("r${r.id}", contentType = "reply") { Box(Modifier.padding(start = 50.dp)) { CommentRow(r, r.user.id == meId, feed, isReply = true) } }
                    }
                    if (t.loading || t.error || t.hasMore) item("m${c.id}", contentType = "rmore") {
                        Box(Modifier.padding(start = 50.dp).fillMaxWidth().padding(vertical = 6.dp), Alignment.CenterStart) {
                            when {
                                t.loading -> CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), color = Muted, strokeWidth = 2.dp)
                                t.error -> Text("Gagal memuat • ketuk untuk ulangi", color = Muted, fontSize = 13.sp, modifier = Modifier.clip(R6).clickable { feed.loadReplies(c.id) }.padding(8.dp))
                                else -> Text("Lihat balasan lainnya", color = Color(0xFFD7D9DE), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(R6).clickable { feed.loadReplies(c.id) }.padding(8.dp))
                            }
                        }
                    }
                }
            }
            if (feed.hasMore) item("c-more", contentType = "more") {
                LaunchedEffect(rows.size, feed.moreError) { if (!feed.moreError) feed.loadMore() }
                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                    if (feed.moreError) Text("Gagal memuat • ketuk untuk ulangi", color = Muted, fontSize = 13.sp,
                        modifier = Modifier.clip(R6).clickable { feed.loadMore() }.padding(8.dp))
                    else CircularProgressIndicator(Modifier.size(20.dp), color = Muted, strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Sheet komentar (dipakai pembaca chapter): daftar langsung dari feed yang sudah di-prefetch, kolom tulis menempel di bawah. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(target: String, title: String, origin: CommentOrigin? = null, onDismiss: () -> Unit) {
    val feed = remember(target) { Comments.of(target) }
    LaunchedEffect(feed, origin) { if (origin != null) feed.origin = origin }
    LaunchedEffect(target) { feed.refresh() }

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp), containerColor = CardBg) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                Text("Komentar (${feed.total})", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (title.isNotBlank()) Text(title, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = Line)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp)) { commentItems(feed) }
            HorizontalDivider(color = Line)
            CommentComposer(feed, Modifier.padding(12.dp))
        }
    }
}

// ---------------------------------------------------------------- profil
/** Foto pilihan -> potong persegi tengah, putar sesuai EXIF, kecilkan 384px, JPEG (±30–60 KB). */
suspend fun prepareAvatar(ctx: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 1536 || bounds.outHeight / sample > 1536) sample *= 2
    val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: throw IllegalStateException("Gambar tidak bisa dibaca")
    val orientation = runCatching {
        cr.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }
    }.getOrNull() ?: 1
    val m = Matrix()
    when (orientation) { 6 -> m.postRotate(90f); 3 -> m.postRotate(180f); 8 -> m.postRotate(270f) }
    val side = minOf(decoded.width, decoded.height)
    val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side, m, true)
    val out = Bitmap.createScaledBitmap(square, 384, 384, true)
    ByteArrayOutputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
}

/** "sampai 20 Nov 2026 14.05" atau "tanpa batas waktu" untuk bisu permanen. */
fun muteUntilText(ms: Long): String =
    if (ms > 32_503_680_000_000L) "tanpa batas waktu"
    else "sampai " + java.text.SimpleDateFormat("d MMM yyyy HH.mm", java.util.Locale("id")).format(java.util.Date(ms))
