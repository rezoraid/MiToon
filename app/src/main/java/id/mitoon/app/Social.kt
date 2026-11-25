package id.mitoon.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import retrofit2.HttpException

// ---------------------------------------------------------------- model
@Serializable data class CommentUser(val id: Long = 0, val username: String = "", val avatarV: Int = 0, val displayName: String = "", val level: Int = 1, val verified: Boolean = false, val isOwner: Boolean = false, val premium: Boolean = false) {
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
    val replyCount: Int = 0
)
@Serializable data class CommentPage(val total: Int = 0, val items: List<Comment> = emptyList(), val hasMore: Boolean = false)
@Serializable data class CommentReq(val target: String, val body: String, val parentId: Long? = null)
@Serializable data class CommentRes(val data: Comment, val me: AuthUser? = null)
@Serializable data class VoteReq(val value: Int)
@Serializable data class VoteRes(val likes: Int = 0, val dislikes: Int = 0, val myVote: Int = 0)

// ---------------------------------------------------------------- potongan UI kecil
/** Dipanggil saat nama/foto penulis komentar diketuk (id pengguna); diisi oleh navigasi aplikasi. */
val LocalOpenUser = androidx.compose.runtime.staticCompositionLocalOf<(Long) -> Unit> { {} }

/** Foto profil bulat; tanpa foto = huruf pertama username. */
@Composable
fun Avatar(userId: Long, username: String, version: Int, size: Dp, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.CircleShape) {
    val url = Auth.avatarUrl(userId, version)
    Box(modifier.size(size).clip(shape).background(Color(0xFF2A2C32)), Alignment.Center) {
        if (url != null) Img(url, "", Modifier.fillMaxSize())
        else Text(username.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

/** Ikon balon komentar: balon bulat (bukan kotak) dengan ekor kecil & tiga titik. */
@Composable
fun CommentIcon(tint: Color = Color.White, dp: Dp = 22.dp) = Icon(Ic.Comment, null, Modifier.size(dp), tint = tint)

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
    var threads by mutableStateOf<Map<Long, ReplyThread>>(emptyMap()); private set
    var replyingTo by mutableStateOf<Comment?>(null); private set
    /** Komentar yang sedang disorot (datang dari notifikasi) dan kunci item-nya di daftar; hilang sendiri beberapa detik kemudian. */
    var focusId by mutableLongStateOf(0L); private set
    var focusKey by mutableStateOf<String?>(null); private set
    private var fetchedAt = 0L
    private var busy = false
    private var diskRead = false
    private val voting = HashSet<Long>()

    /** Komentar sementara (belum dikonfirmasi server) tidak ikut disimpan ke disk. */
    private fun snapshot() { val real = rows.filter { !isTemp(it.id) }; Net.storeComments(target, CommentPage(total, real.take(PAGE), hasMore || real.size > PAGE)) }

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
                val tail = if (p.hasMore) rows.filter { it.id < p.items.last().id } else emptyList()
                val merged = rows.filter { isTemp(it.id) } + p.items + tail // komentar yang sedang terkirim tetap tampil
                if (merged != rows) rows = merged
                if (total != p.total) total = p.total
                hasMore = if (tail.isNotEmpty()) hasMore else p.hasMore
                fetchedAt = System.currentTimeMillis(); error = null
                snapshot()
                prefetchRest()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (rows.isEmpty()) error = e.friendly() }
            finally { loading = false; busy = false }
        }
    }

    /** Satu halaman berikutnya; false bila gagal. Dipakai pemuat latar belakang dan `loadMore`. */
    private suspend fun fetchMore(): Boolean = try {
        val p = Net.api.comments(target, limit = PAGE, before = rows.lastOrNull { !isTemp(it.id) }?.id).data
        val seen = rows.mapTo(HashSet()) { it.id }
        val fresh = p.items.filter { it.id !in seen }
        rows = rows + fresh
        total = p.total
        hasMore = p.hasMore && fresh.isNotEmpty() // tidak ada lanjutan -> hentikan pemuat
        true
    } catch (e: CancellationException) { throw e } catch (_: Exception) { moreError = true; false }

    /** Sisa komentar diambil diam-diam sampai habis (maks. 5 halaman) supaya daftar tidak memuat-muat saat digulir. */
    private fun prefetchRest() {
        if (loadingMore || !hasMore) return
        loadingMore = true; moreError = false
        MainScope().launch {
            try { var g = 0; while (hasMore && g++ < 5) if (!fetchMore()) break } finally { loadingMore = false }
        }
    }

    fun loadMore() {
        if (loadingMore || loading || rows.isEmpty() || !hasMore) return
        loadingMore = true; moreError = false
        MainScope().launch { try { fetchMore() } finally { loadingMore = false } }
    }

    /**
     * Cari satu komentar tertentu: muat halaman sampai komentar teratasnya ketemu, buka balasannya bila yang dicari adalah balasan
     * (`parentId` = id komentar teratas), lalu sorot. Gagal/komentar sudah dihapus -> diam saja, daftar tetap normal.
     */
    fun locate(id: Long, parentId: Long?) {
        if (id <= 0) return
        val root = parentId ?: id
        MainScope().launch {
            try {
                if (rows.isEmpty()) {
                    refresh(force = true)
                    var w = 0
                    while (busy && w++ < 200) delay(50)
                }
                loadingMore = true // cegah pemuat otomatis di dasar daftar ikut berjalan
                try {
                    var guard = 0
                    while (rows.none { it.id == root } && guard++ < 25) {
                        val last = rows.lastOrNull() ?: break
                        if (!hasMore || last.id < root) break
                        val p = Net.api.comments(target, limit = PAGE, before = last.id).data
                        val seen = rows.mapTo(HashSet()) { it.id }
                        val fresh = p.items.filter { it.id !in seen }
                        rows = rows + fresh; total = p.total
                        hasMore = p.hasMore && fresh.isNotEmpty()
                    }
                } finally { loadingMore = false }
                if (rows.none { it.id == root }) return@launch
                if (parentId != null) {
                    setThread(root) { it.copy(expanded = true) }
                    var guard = 0
                    while (guard++ < 25) {
                        val t = threads[root] ?: ReplyThread()
                        if (t.items.any { it.id == id }) break
                        if (t.loaded && !t.hasMore) break
                        val p = Net.api.replies(root, after = t.cursor.takeIf { it > 0 }).data
                        setThread(root) { cur ->
                            cur.copy(items = (cur.items + p.items).distinctBy { it.id }.sortedBy { it.id }, loaded = true, hasMore = p.hasMore,
                                loading = false, cursor = p.items.lastOrNull()?.id ?: cur.cursor)
                        }
                        if (p.items.isEmpty()) break
                    }
                    if (threads[root]?.items?.any { it.id == id } != true) return@launch
                }
                focusId = id
                focusKey = if (parentId != null) "r$id" else "c$id"
                delay(3500)
                if (focusId == id) { focusId = 0; focusKey = null }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------ kirim, balas, hapus
    fun startReply(c: Comment) { if (Auth.isGuest) GuestGate.ask(GUEST_REPLY) else if (!isTemp(c.id)) replyingTo = c }
    fun cancelReply() { replyingTo = null }

    private var tempSeq = 0L
    private var pendingSends = 0

    private fun meAsCommentUser(): CommentUser {
        val u = Auth.user ?: return CommentUser()
        return CommentUser(u.id, u.username, u.avatarV, u.displayName, u.level, u.verified && !u.verifiedHidden, u.isOwner, u.premium)
    }

    private fun addLocal(c: Comment) {
        val root = c.parentId
        if (root == null) rows = listOf(c) + rows
        else {
            rows = rows.map { if (it.id == root) it.copy(replyCount = it.replyCount + 1) else it }
            setThread(root) { it.copy(items = (it.items + c).distinctBy { x -> x.id }.sortedBy { x -> x.id }, expanded = true) }
        }
        total += 1
    }

    private fun removeLocal(c: Comment) {
        val root = c.parentId
        if (root == null) rows = rows.filter { it.id != c.id }
        else {
            setThread(root) { it.copy(items = it.items.filter { x -> x.id != c.id }) }
            rows = rows.map { if (it.id == root) it.copy(replyCount = maxOf(0, it.replyCount - 1)) else it }
        }
        total = maxOf(0, total - 1)
    }

    /** Ganti komentar sementara dengan versi server (id asli). */
    private fun swapLocal(tmp: Comment, c: Comment) {
        val root = c.parentId
        if (root == null) {
            rows = when {
                rows.any { it.id == tmp.id } -> rows.map { if (it.id == tmp.id) c else it }.distinctBy { it.id }
                rows.any { it.id == c.id } -> rows
                else -> listOf(c) + rows
            }
        } else {
            setThread(root) { t -> t.copy(items = (t.items.filter { it.id != tmp.id } + c).distinctBy { it.id }.sortedBy { it.id }) }
        }
    }

    /**
     * Kirim: komentar langsung tampil & kolom input langsung kosong (tanpa menunggu server). Server mengonfirmasi di latar belakang;
     * gagal -> komentar ditarik lagi dan teksnya dikembalikan lewat [onFail] supaya tidak hilang.
     */
    fun send(body: String, onFail: (String) -> Unit = {}, onSent: () -> Unit) {
        val text = body.trim()
        if (text.isEmpty()) return
        val reply = replyingTo
        if (reply != null && isTemp(reply.id)) return
        val root = reply?.let { it.parentId ?: it.id }
        val tmp = Comment(
            id = TEMP_BASE + (++tempSeq), body = text, createdAt = System.currentTimeMillis(), user = meAsCommentUser(),
            parentId = root, replyTo = if (reply != null && reply.parentId != null) reply.user.name else ""
        )
        sendError = null
        pendingSends++; posting = true
        addLocal(tmp)
        if (root != null && threads[root]?.loaded != true) loadReplies(root) // balasan lama ikut dimuat di latar belakang
        replyingTo = null
        onSent()
        MainScope().launch {
            try {
                val res = Net.api.addComment(CommentReq(target, text, reply?.id))
                res.me?.let { Auth.update(it) } // XP/level terbaru
                origin?.let { CommentHistory.remember(target, it) }
                swapLocal(tmp, res.data)
                snapshot()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                removeLocal(tmp)
                if (replyingTo == null) replyingTo = reply
                sendError = e.friendly()
                onFail(text)
            } finally { pendingSends--; posting = pendingSends > 0 }
        }
    }

    /** Hapus: hilang dari daftar seketika; bila server menolak, komentar dikembalikan ke tempatnya. */
    fun delete(c: Comment) {
        if (isTemp(c.id)) return
        val root = c.parentId
        val oldThread = threads[c.id]
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
        MainScope().launch {
            try { Net.api.deleteComment(c.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is HttpException && e.code() == 404) return@launch // sudah terhapus di server: hasilnya sama
                if (root == null) {
                    rows = (rows + c).distinctBy { it.id }.sortedByDescending { it.id }
                    oldThread?.let { threads = threads + (c.id to it) }
                    total += 1 + c.replyCount
                } else {
                    setThread(root) { it.copy(items = (it.items + c).distinctBy { x -> x.id }.sortedBy { x -> x.id }) }
                    rows = rows.map { if (it.id == root) it.copy(replyCount = it.replyCount + 1) else it }
                    total += 1
                }
                sendError = e.friendly(); snapshot()
            }
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
        if (Auth.isGuest) { GuestGate.ask(GUEST_VOTE); return }
        if (Auth.token == null || isTemp(c.id) || !voting.add(c.id)) return
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

private const val PAGE = 50
/** id komentar yang belum dikonfirmasi server (jauh di atas id asli, jadi selalu tampil paling baru). */
private const val TEMP_BASE = Long.MAX_VALUE / 2
private fun isTemp(id: Long) = id >= TEMP_BASE

/** Titipan dari layar Notifikasi: komentar mana yang harus dicari dan disorot setelah seri/chapter-nya terbuka. Dipakai sekali. */
object CommentFocus {
    private var target = ""
    private var id = 0L
    private var parentId: Long? = null
    fun set(t: String, commentId: Long, parent: Long?) { target = t; id = commentId; parentId = parent }
    fun pending(t: String) = id > 0 && target == t
    fun take(t: String): Pair<Long, Long?>? {
        if (!pending(t)) return null
        val r = id to parentId
        target = ""; id = 0L; parentId = null
        return r
    }
}

/** Registri feed komentar per target ("s:<id seri>" / "c:<id chapter>"); feed disimpan di memori (LRU) supaya bisa di-prefetch. */
object Comments {
    private const val MAX = 24
    private val feeds = object : LinkedHashMap<String, CommentFeed>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CommentFeed>?) = size > MAX
    }

    @Synchronized
    fun of(target: String): CommentFeed = feeds.getOrPut(target) { CommentFeed(target) }

    /** Isi diam-diam sebelum sheet/tab dibuka. */
    fun prefetch(target: String) = of(target).refresh()
}


@Composable
private fun CommentRow(c: Comment, mine: Boolean, feed: CommentFeed, isReply: Boolean = false) {
    val whenText = remember(c.createdAt) { ago(c.createdAt) }
    val openUser = LocalOpenUser.current
    val openAuthor: () -> Unit = { if (c.user.id > 0) openUser(c.user.id) }
    val bg by animateColorAsState(if (feed.focusId == c.id) Color(0x24FFFFFF) else Color.Transparent, tween(400), label = "focus")
    Row(Modifier.fillMaxWidth().background(bg, R6).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(c.user.id, c.user.name, c.user.avatarV, if (isReply) 30.dp else 38.dp, Modifier.clickable(onClick = openAuthor))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.user.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).clickable(onClick = openAuthor))
                StatusBadges(c.user.verified, c.user.isOwner, c.user.premium, size = 14.dp)
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

/** Kerangka selama komentar dimuat: denyut alpha halus yang dibaca di fase layer (tanpa recompose). */
@Composable
private fun CommentSkeleton() {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val a by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Column(Modifier.graphicsLayer { alpha = a }) {
        repeat(5) { i ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF2A2C32)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(if (i % 2 == 0) 110.dp else 80.dp).height(12.dp).clip(R6).background(Color(0xFF2A2C32)))
                    Box(Modifier.fillMaxWidth(if (i % 3 == 0) 0.9f else 0.7f).height(12.dp).clip(R6).background(Color(0xFF24262C)))
                    if (i % 2 == 0) Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(R6).background(Color(0xFF24262C)))
                }
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
        if (Auth.isGuest) {
            Text("Masuk dengan Google untuk berkomentar", color = Muted, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clip(R6).border(1.dp, Color(0xFF4A4C53), R6).clickable { GuestGate.ask(GUEST_COMMENT) }.padding(16.dp))
            return@Column
        }
        if (reply != null) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(R6).background(Color(0xFF24262C)).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_reply), null, tint = Muted, modifier = Modifier.size(14.dp))
            Text("  Membalas ${reply.user.name}", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton({ feed.cancelReply() }, Modifier.size(36.dp)) { Icon(Ic.Close, "Batal membalas", tint = Muted, modifier = Modifier.size(16.dp)) }
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
            Button({ feed.send(text, { failed -> if (text.isBlank()) text = failed }) { text = "" } }, Modifier.height(56.dp), enabled = text.isNotBlank(), shape = R6,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF3A3C42), disabledContentColor = Muted)) {
                Text(if (reply != null) "Balas" else "Kirim", fontWeight = FontWeight.Bold)
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
                item("c${c.id}", contentType = "row") { Box(Modifier.animateItem()) { CommentRow(c, c.user.id == meId, feed) } }
                val t = feed.threads[c.id]
                if (c.replyCount > 0 || (t != null && t.items.isNotEmpty())) item("t${c.id}", contentType = "toggle") { Box(Modifier.animateItem()) { ReplyToggle(c, t, feed) } }
                if (t != null && t.expanded) {
                    t.items.forEach { r ->
                        item("r${r.id}", contentType = "reply") { Box(Modifier.animateItem().padding(start = 50.dp)) { CommentRow(r, r.user.id == meId, feed, isReply = true) } }
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

/** Kunci item yang dikeluarkan `commentItems`, berurutan. Harus selalu selaras dengan fungsi itu (dipakai untuk mencari posisi scroll). */
fun commentKeys(feed: CommentFeed): List<String> {
    val rows = feed.rows
    if (rows.isEmpty()) return listOf(if (feed.error != null) "c-err" else if (feed.loading) "c-load" else "c-empty")
    val out = ArrayList<String>()
    rows.forEach { c ->
        out += "c${c.id}"
        val t = feed.threads[c.id]
        if (c.replyCount > 0 || (t != null && t.items.isNotEmpty())) out += "t${c.id}"
        if (t != null && t.expanded) {
            t.items.forEach { out += "r${it.id}" }
            if (t.loading || t.error || t.hasMore) out += "m${c.id}"
        }
    }
    if (feed.hasMore) out += "c-more"
    return out
}

/** Gulir ke komentar yang disorot. `commentItems` selalu item terakhir di daftar, jadi posisi awalnya = jumlah item − jumlah kunci komentar. */
@Composable
fun ScrollToFocusedComment(feed: CommentFeed, state: LazyListState, active: Boolean = true) {
    val key = feed.focusKey
    LaunchedEffect(key, active) {
        if (key == null || !active) return@LaunchedEffect
        withFrameNanos { }; withFrameNanos { } // beri waktu daftar tersusun ulang dengan item yang baru dimuat
        val keys = commentKeys(feed)
        val i = keys.indexOf(key)
        if (i < 0) return@LaunchedEffect
        val total = snapshotFlow { state.layoutInfo.totalItemsCount }.first { it >= keys.size }
        state.animateScrollToItem((total - keys.size + i).coerceAtLeast(0))
    }
}

/** Sheet komentar (dipakai pembaca chapter): daftar langsung dari feed yang sudah di-prefetch, kolom tulis menempel di bawah. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(target: String, title: String, origin: CommentOrigin? = null, onDismiss: () -> Unit) {
    val feed = remember(target) { Comments.of(target) }
    LaunchedEffect(feed, origin) { if (origin != null) feed.origin = origin }
    LaunchedEffect(target) { feed.refresh() }
    val listState = rememberLazyListState()
    LaunchedEffect(target) { CommentFocus.take(target)?.let { (cid, pid) -> feed.locate(cid, pid) } }
    ScrollToFocusedComment(feed, listState)

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), containerColor = CardBg, contentWindowInsets = { WindowInsets(0) }) {
        KeepBarsHidden()
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                Text("Komentar (${feed.total})", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (title.isNotBlank()) Text(title, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = Line)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), listState, contentPadding = PaddingValues(horizontal = 16.dp)) { commentItems(feed) }
            HorizontalDivider(color = Line)
            CommentComposer(feed, Modifier.padding(12.dp))
        }
    }
}

// ---------------------------------------------------------------- profil
/** "sampai 20 Nov 2026 14.05" atau "tanpa batas waktu" untuk bisu permanen. */
fun muteUntilText(ms: Long): String =
    if (ms > 32_503_680_000_000L) "tanpa batas waktu"
    else "sampai " + java.text.SimpleDateFormat("d MMM yyyy HH.mm", java.util.Locale("id")).format(java.util.Date(ms))
