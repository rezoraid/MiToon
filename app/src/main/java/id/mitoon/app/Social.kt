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
@Serializable data class CommentUser(val id: Long = 0, val username: String = "", val avatarV: Int = 0)
@Serializable data class Comment(val id: Long, val body: String, val createdAt: Long = 0, val user: CommentUser = CommentUser())
@Serializable data class CommentPage(val total: Int = 0, val items: List<Comment> = emptyList())
@Serializable data class CommentReq(val target: String, val body: String)
@Serializable data class Ok(val ok: Boolean = true)

// ---------------------------------------------------------------- potongan UI kecil
/** Foto profil persegi; tanpa foto = huruf pertama username. */
@Composable
fun Avatar(userId: Long, username: String, version: Int, size: Dp, modifier: Modifier = Modifier) {
    val url = Auth.avatarUrl(userId, version)
    Box(modifier.size(size).clip(R6).background(Color(0xFF2A2C32)), Alignment.Center) {
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

/**
 * Satu "feed" komentar per target ("s:<id seri>" / "c:<id chapter>"). Hidup di luar layar (cache memori + snapshot disk),
 * jadi bisa diisi diam-diam sebelum tab/sheet dibuka -> saat dibuka datanya sudah ada dan langsung tampil.
 */
class CommentFeed(val target: String) {
    var rows by mutableStateOf<List<Comment>>(emptyList()); private set
    var total by mutableIntStateOf(0); private set
    var loading by mutableStateOf(true); private set       // true hanya selama belum ada data sama sekali
    var error by mutableStateOf<String?>(null); private set
    var loadingMore by mutableStateOf(false); private set
    var moreError by mutableStateOf(false); private set
    var posting by mutableStateOf(false); private set
    var sendError by mutableStateOf<String?>(null)
    private var fetchedAt = 0L
    private var busy = false
    private var diskRead = false

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
                    Net.readComments(target)?.let { if (rows.isEmpty()) { rows = it.items; total = it.total; loading = false } }
                }
                val p = Net.api.comments(target, limit = PAGE).data
                // Halaman lama yang sudah dimuat user (scroll ke bawah) dipertahankan supaya daftar tidak melompat.
                val tail = if (p.items.size >= PAGE) rows.filter { it.id < p.items.last().id } else emptyList()
                val merged = p.items + tail
                if (merged != rows) rows = merged
                if (total != p.total) total = p.total
                fetchedAt = System.currentTimeMillis(); error = null
                Net.storeComments(target, CommentPage(p.total, p.items))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (rows.isEmpty()) error = e.friendly() }
            finally { loading = false; busy = false }
        }
    }

    fun loadMore() {
        if (loadingMore || loading || rows.isEmpty() || rows.size >= total) return
        loadingMore = true; moreError = false
        MainScope().launch {
            try {
                val p = Net.api.comments(target, limit = PAGE, before = rows.lastOrNull()?.id).data
                val seen = rows.mapTo(HashSet()) { it.id }
                val fresh = p.items.filter { it.id !in seen }
                rows = rows + fresh
                total = if (fresh.isEmpty()) rows.size else p.total // tidak ada lanjutan -> hentikan pemuat
            } catch (e: CancellationException) { throw e } catch (_: Exception) { moreError = true } finally { loadingMore = false }
        }
    }

    fun send(body: String, onSent: () -> Unit) {
        if (body.isBlank() || posting) return
        posting = true; sendError = null
        MainScope().launch {
            try {
                val c = Net.api.addComment(CommentReq(target, body.trim())).data
                rows = listOf(c) + rows; total += 1; onSent()
                Net.storeComments(target, CommentPage(total, rows.take(PAGE)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { sendError = e.friendly() } finally { posting = false }
        }
    }

    fun delete(c: Comment) {
        MainScope().launch {
            try {
                Net.api.deleteComment(c.id)
                rows = rows.filter { it.id != c.id }; total = maxOf(0, total - 1)
                Net.storeComments(target, CommentPage(total, rows.take(PAGE)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { sendError = e.friendly() }
        }
    }
}

private const val PAGE = 20

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
private fun CommentRow(c: Comment, mine: Boolean, onDelete: () -> Unit) {
    val whenText = remember(c.createdAt) { ago(c.createdAt) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(c.user.id, c.user.username, c.user.avatarV, 38.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.user.username, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  ·  $whenText", color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            Text(c.body, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 3.dp))
            if (mine) Text("Hapus", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp).clip(R6).clickable(onClick = onDelete).padding(vertical = 4.dp, horizontal = 2.dp))
        }
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

/** Kolom tulis komentar (dipakai di tab detail seri dan di sheet chapter). */
@Composable
fun CommentComposer(feed: CommentFeed, modifier: Modifier = Modifier) {
    var text by rememberSaveable(feed.target) { mutableStateOf("") }
    Column(modifier) {
        feed.sendError?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { if (it.length <= 1000) text = it }, Modifier.weight(1f), maxLines = 4, shape = R6,
                placeholder = { Text("Tulis komentar…", color = Muted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF4A4C53),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                    focusedContainerColor = Bg, unfocusedContainerColor = Bg))
            Button({ feed.send(text) { text = "" } }, Modifier.height(56.dp), enabled = text.isNotBlank() && !feed.posting, shape = R6,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF3A3C42), disabledContentColor = Muted)) {
                if (feed.posting) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                else Text("Kirim", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Isi daftar komentar sebagai item LazyColumn (satu scroll, tanpa list bersarang) - dipakai tab detail dan sheet. */
fun LazyListScope.commentItems(feed: CommentFeed) {
    val rows = feed.rows
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
            items(rows, key = { it.id }, contentType = { "row" }) { c -> CommentRow(c, c.user.id == Auth.user?.id) { feed.delete(c) } }
            if (rows.size < feed.total) item("c-more", contentType = "more") {
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
fun CommentsSheet(target: String, title: String, onDismiss: () -> Unit) {
    val feed = remember(target) { Comments.of(target) }
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
private suspend fun prepareAvatar(ctx: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
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

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val user = Auth.user
    if (user == null) { Box(Modifier.fillMaxSize().background(Bg)); return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun upload(uri: Uri) {
        busy = true; msg = null
        scope.launch {
            try {
                val bytes = prepareAvatar(ctx, uri)
                Auth.update(Net.api.putAvatar(bytes.toRequestBody("image/jpeg".toMediaType())).data)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { msg = e.friendly() } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) upload(uri) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(CardBg).height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) }
            Text("Akun", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        HorizontalDivider(color = Line)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Avatar(user.id, user.username, user.avatarV, 120.dp)
                if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
            }
            Text(user.username, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 14.dp))
            Text("Pustaka ${Library.entries.values.count { it.bookmarked }}  ·  Dibaca ${Library.entries.values.count { it.readAt > 0 }}",
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))

            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f).height(46.dp),
                    enabled = !busy, shape = R6, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black,
                        disabledContainerColor = Color(0xFF55585F), disabledContentColor = Color.Black)) { Text("Ganti foto", fontWeight = FontWeight.Bold) }
                if (user.avatarV > 0) OutlinedButton({
                    busy = true; msg = null
                    scope.launch {
                        try { Auth.update(Net.api.deleteAvatar().data) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { msg = e.friendly() } finally { busy = false }
                    }
                }, Modifier.weight(1f).height(46.dp), enabled = !busy, shape = R6, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4A4C53)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Hapus foto", fontWeight = FontWeight.Bold) }
            }
            msg?.let {
                Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, Color.White, R6).padding(10.dp))
            }

            Spacer(Modifier.height(28.dp))
            OutlinedButton({ Auth.logout() }, Modifier.fillMaxWidth().height(46.dp), shape = R6,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4A4C53)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Text("Keluar", fontWeight = FontWeight.Bold)
            }
        }
    }
}
