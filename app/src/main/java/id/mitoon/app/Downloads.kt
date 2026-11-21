package id.mitoon.app

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Satu chapter yang sudah tersimpan di perangkat (dibaca dari `meta.json` di foldernya). */
@Serializable
data class DlMeta(
    val chapterId: String, val seriesId: String, val seriesTitle: String = "", val cover: String = "", val provider: String = "",
    val number: String = "", val title: String = "", val files: List<String> = emptyList(), val bytes: Long = 0, val savedAt: Long = 0,
    val prev: String? = null, val next: String? = null
)

enum class DlPhase { Queued, Running, Failed }

/** Status unduhan yang sedang berjalan / menunggu / gagal. `error` hanya terisi saat Failed. */
data class DlProgress(val done: Int = 0, val total: Int = 0, val phase: DlPhase = DlPhase.Queued, val error: String = "", val title: String = "", val number: String = "")

/** Galat unduhan dengan pesan yang sudah siap tampil (tidak lewat `friendly()`, yang menyamaratakan semua IOException jadi "tidak ada koneksi"). */
private class DlError(msg: String) : Exception(msg)

private class DlRequest(val id: String, val seriesId: String, val seriesTitle: String, val cover: String, val provider: String, val number: String, val title: String)

/**
 * Unduhan chapter untuk dibaca tanpa internet. Gambar disimpan di `filesDir/downloads/<sha1 id>/` (tidak ikut terhapus saat cache dibersihkan).
 * Antrean berjalan satu chapter sekali waktu (3 gambar paralel) dan baru dianggap selesai bila SEMUA halaman berhasil: ditulis ke folder `.tmp`
 * lalu di-rename, jadi chapter setengah jadi tidak pernah muncul sebagai "sudah diunduh".
 */
object Downloads {
    private lateinit var root: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<DlRequest>(Channel.UNLIMITED)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val metas = ConcurrentHashMap<String, DlMeta>()
    private val http by lazy { Net.client.newBuilder().cache(null).readTimeout(40, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build() }

    /** Semua chapter yang sudah tersimpan, terbaru diunduh dulu. */
    var items by mutableStateOf<List<DlMeta>>(emptyList()); private set
    /** Id chapter yang sudah tersimpan (untuk pengecekan cepat di daftar chapter). */
    var doneIds by mutableStateOf<Set<String>>(emptySet()); private set
    /** Antrean + yang berjalan + yang gagal, berurutan sesuai masuknya. */
    val active = mutableStateMapOf<String, DlProgress>()

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "downloads").also { it.mkdirs() }
        scope.launch {
            root.listFiles()?.forEach { d ->
                if (d.name.endsWith(".tmp")) d.deleteRecursively() // sisa unduhan yang terputus saat aplikasi mati
                else readMeta(d)?.let { metas[it.chapterId] = it }
            }
            publish()
            for (r in queue) process(r)
        }
    }

    private fun key(id: String) = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun dir(id: String) = File(root, key(id))
    private fun readMeta(d: File): DlMeta? = runCatching {
        val m = json.decodeFromString<DlMeta>(File(d, "meta.json").readText())
        m.takeIf { it.files.isNotEmpty() && it.files.all { f -> File(d, f).exists() } }
    }.getOrNull()

    private fun publish() {
        items = metas.values.sortedByDescending { it.savedAt }
        doneIds = metas.keys.toSet()
    }

    fun has(id: String) = metas.containsKey(id)

    val totalBytes get() = items.sumOf { it.bytes }

    /** Isi chapter dari penyimpanan lokal untuk ReaderScreen; null bila belum diunduh. */
    fun content(id: String): Content? {
        if (!::root.isInitialized) return null
        val m = metas[id] ?: readMeta(dir(id))?.also { metas[id] = it } ?: return null
        val d = dir(id)
        return Content(seriesId = m.seriesId, number = m.number, images = m.files.map { Uri.fromFile(File(d, it)).toString() }, prev = m.prev, next = m.next)
    }

    /** Masukkan chapter ke antrean. Yang sudah tersimpan atau sedang antre/berjalan dilewati; yang gagal dicoba lagi. Mengembalikan jumlah yang benar-benar masuk. */
    fun enqueue(seriesId: String, seriesTitle: String, cover: String, provider: String, chapters: List<Chap>): Int {
        var n = 0
        for (c in chapters) {
            if (metas.containsKey(c.id)) continue
            if (active[c.id]?.phase.let { it == DlPhase.Queued || it == DlPhase.Running }) continue
            cancelled.remove(c.id)
            active[c.id] = DlProgress(title = seriesTitle, number = c.number)
            queue.trySend(DlRequest(c.id, seriesId, seriesTitle, cover, provider, c.number, c.title))
            n++
        }
        return n
    }

    fun retry(id: String) {
        val p = active[id] ?: return
        val m = lastRequests[id] ?: return
        if (p.phase != DlPhase.Failed) return
        cancelled.remove(id)
        active[id] = DlProgress(title = p.title, number = p.number)
        queue.trySend(m)
    }

    fun cancel(id: String) {
        if (active.containsKey(id)) { cancelled.add(id); active.remove(id); lastRequests.remove(id) }
    }

    fun cancelAll() = active.keys.toList().forEach { cancel(it) }

    fun delete(id: String) {
        metas.remove(id); publish()
        scope.launch { dir(id).deleteRecursively() }
    }

    fun deleteSeries(seriesId: String) = metas.values.filter { it.seriesId == seriesId }.forEach { delete(it.chapterId) }

    fun deleteAll() { cancelAll(); items.forEach { delete(it.chapterId) } }

    private val lastRequests = ConcurrentHashMap<String, DlRequest>()

    private fun ext(url: String): String {
        val e = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (e in setOf("jpg", "jpeg", "png", "webp", "gif", "avif")) e else "jpg"
    }

    /** Unduh satu gambar (3x coba dengan jeda bertahap). Mengembalikan ukuran file. */
    private suspend fun fetch(url: String, headers: Map<String, String>, out: File, id: String): Long {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            if (id in cancelled) throw CancellationException("dibatalkan")
            try {
                val rq = Request.Builder().url(url.trim()).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                http.newCall(rq).execute().use { res ->
                    if (!res.isSuccessful) throw DlError("Server menolak (kode ${res.code})")
                    val type = res.header("Content-Type").orEmpty()
                    if (type.startsWith("text/")) throw DlError("Gambar tidak valid")
                    val body = res.body ?: throw DlError("Respons kosong")
                    out.outputStream().use { o -> body.byteStream().copyTo(o) }
                }
                if (out.length() <= 0L) throw DlError("File kosong")
                return out.length()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                last = e; out.delete()
                kotlinx.coroutines.delay(800L * (attempt + 1))
            }
        }
        throw last ?: DlError("Gagal mengunduh")
    }

    private suspend fun process(r: DlRequest) {
        if (r.id in cancelled) { cancelled.remove(r.id); return }
        if (metas.containsKey(r.id)) { active.remove(r.id); return }
        lastRequests[r.id] = r
        active[r.id] = DlProgress(phase = DlPhase.Running, title = r.seriesTitle, number = r.number)
        val tmp = File(root, key(r.id) + ".tmp")
        try {
            tmp.deleteRecursively(); tmp.mkdirs()
            val c = Net.chapter(r.id)
            if (c.images.isEmpty()) throw DlError("Chapter belum tersedia")
            val headers = c.imageHeaders.ifEmpty { Net.hdr[r.provider].orEmpty() }
            val names = c.images.mapIndexed { i, u -> "%04d.%s".format(i + 1, ext(u)) }
            val done = AtomicInteger(); val bytes = AtomicLong()
            val sem = Semaphore(3)
            active[r.id] = DlProgress(0, c.images.size, DlPhase.Running, title = r.seriesTitle, number = r.number)
            coroutineScope {
                c.images.mapIndexed { i, url ->
                    async {
                        sem.withPermit {
                            bytes.addAndGet(fetch(url, headers, File(tmp, names[i]), r.id))
                            if (r.id !in cancelled) active[r.id] = DlProgress(done.incrementAndGet(), c.images.size, DlPhase.Running, title = r.seriesTitle, number = r.number)
                        }
                    }
                }.awaitAll()
            }
            if (r.id in cancelled) throw CancellationException("dibatalkan")
            val meta = DlMeta(r.id, r.seriesId, r.seriesTitle, r.cover, r.provider, c.number.ifBlank { r.number }, r.title, names, bytes.get(), System.currentTimeMillis(), c.prev, c.next)
            File(tmp, "meta.json").writeText(json.encodeToString(meta))
            val dst = dir(r.id)
            dst.deleteRecursively()
            if (!tmp.renameTo(dst)) throw DlError("Gagal menyimpan chapter")
            metas[r.id] = meta; publish()
            active.remove(r.id); lastRequests.remove(r.id)
        } catch (e: CancellationException) {
            tmp.deleteRecursively()
            cancelled.remove(r.id); active.remove(r.id)
            currentCoroutineContext().ensureActive() // pembatalan unduhan tidak menghentikan pekerja; pembatalan pekerja sendiri tetap diteruskan
        } catch (e: Exception) {
            tmp.deleteRecursively()
            val msg = when {
                e is DlError -> e.message.orEmpty()
                e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true -> "Penyimpanan penuh"
                else -> e.friendly()
            }
            if (r.id !in cancelled) active[r.id] = DlProgress(phase = DlPhase.Failed, error = msg, title = r.seriesTitle, number = r.number)
            else { cancelled.remove(r.id); active.remove(r.id) }
        }
    }
}

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.US, b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, b / 1048576.0)
    b >= 1L shl 10 -> "${b / 1024} KB"
    else -> "$b B"
}

private fun chapterOrder(n: String) = n.toDoubleOrNull() ?: Double.MAX_VALUE

// ------------------------------------------------------------------ tombol di daftar chapter

/** Ikon status unduhan di ujung kanan baris chapter: unduh / progres (ketuk = batal) / selesai (ketuk = hapus) / gagal (ketuk = ulangi). */
@Composable
fun DownloadAction(seriesId: String, title: String, cover: String, provider: String, c: Chap) {
    val ctx = LocalContext.current
    val done = c.id in Downloads.doneIds
    val p = Downloads.active[c.id]
    var confirm by remember { mutableStateOf(false) }
    Box(Modifier.size(44.dp).clip(R6).clickable {
        when {
            done -> confirm = true
            p == null -> if (Downloads.enqueue(seriesId, title, cover, provider, listOf(c)) > 0) toast(ctx, "Chapter ${c.number} masuk antrean unduhan")
            p.phase == DlPhase.Failed -> Downloads.retry(c.id)
            else -> Downloads.cancel(c.id)
        }
    }, Alignment.Center) {
        when {
            done -> Icon(Ic.Check, "Sudah diunduh", tint = Accent, modifier = Modifier.size(22.dp))
            p == null -> Icon(Ic.Download, "Unduh", tint = Muted, modifier = Modifier.size(22.dp))
            p.phase == DlPhase.Failed -> Icon(Ic.Refresh, "Coba lagi", tint = Color(0xFFE5484D), modifier = Modifier.size(22.dp))
            p.phase == DlPhase.Queued || p.total == 0 -> CircularProgressIndicator(Modifier.size(20.dp), color = Muted, trackColor = Line, strokeWidth = 2.dp)
            else -> CircularProgressIndicator({ p.done.toFloat() / p.total.coerceAtLeast(1) }, Modifier.size(20.dp), color = Accent, trackColor = Line, strokeWidth = 2.dp)
        }
    }
    if (confirm) ConfirmDialog("Hapus unduhan?", "Chapter ${c.number} dihapus dari perangkat. Kamu tetap bisa membacanya online.", "Hapus",
        { confirm = false; Downloads.delete(c.id) }, { confirm = false })
}

@Composable
private fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = CardBg, titleContentColor = Color.White, textContentColor = Muted,
    title = { Text(title) }, text = { Text(text) },
    confirmButton = { TextButton(onConfirm) { Text(action, color = Color(0xFFE5484D), fontWeight = FontWeight.Bold) } },
    dismissButton = { TextButton(onDismiss) { Text("Batal", color = Muted) } }
)

// ------------------------------------------------------------------ pilihan unduh massal (halaman detail)

/** Bottom sheet "Unduh chapter": 5 / 10 chapter berikutnya setelah yang terakhir dibaca, atau semuanya. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(d: Detail, resumeId: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val asc = remember(d) { d.chapters.asReversed() }
    val from = remember(d, resumeId) { asc.indexOfFirst { it.id == resumeId } }
    val todo = asc.filter { it.id !in Downloads.doneIds && Downloads.active[it.id]?.phase.let { p -> p != DlPhase.Queued && p != DlPhase.Running } }
    val upcoming = remember(asc, from, Downloads.doneIds, todo.size) { asc.drop(from + 1).filter { c -> todo.any { it.id == c.id } } }
    val anchor = if (from >= 0) "Setelah Chapter ${asc[from].number} (terakhir dibaca)" else "Mulai dari chapter pertama"
    fun go(list: List<Chap>) {
        val n = Downloads.enqueue(d.id, d.title, d.cover, d.provider, list)
        toast(ctx, if (n > 0) "$n chapter masuk antrean unduhan" else "Semua chapter itu sudah diunduh")
        onDismiss()
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), containerColor = CardBg, contentColor = Color.White) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Unduh chapter", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Tersimpan di perangkat dan bisa dibaca tanpa internet.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            DlOption("5 chapter berikutnya", anchor, upcoming.isNotEmpty()) { go(upcoming.take(5)) }
            DlOption("10 chapter berikutnya", anchor, upcoming.isNotEmpty()) { go(upcoming.take(10)) }
            DlOption("Semua chapter", if (todo.isEmpty()) "Semua sudah diunduh" else "${todo.size} chapter belum diunduh", todo.isNotEmpty()) { go(todo) }
            Text("Unduhan berjalan satu chapter demi satu selama aplikasi terbuka.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun DlOption(title: String, desc: String, enabled: Boolean, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(R6).background(Bg).border(1.dp, Line, R6)
        .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Ic.Download, null, tint = if (enabled) Color.White else Line, modifier = Modifier.size(22.dp))
    }

// ------------------------------------------------------------------ tab Download

@Composable
fun DownloadsContent(onRead: (seriesId: String, chapterId: String) -> Unit, onSeries: (String) -> Unit) {
    val saved = Downloads.items
    // berjalan dulu, lalu menunggu, gagal paling bawah; di dalam kelompok urut judul + nomor chapter (peta status tidak menjaga urutan masuk)
    val queue = Downloads.active.entries.sortedWith(compareBy<Map.Entry<String, DlProgress>>({ when (it.value.phase) { DlPhase.Running -> 0; DlPhase.Queued -> 1; DlPhase.Failed -> 2 } }, { it.value.title }, { chapterOrder(it.value.number) }))
    val groups = remember(saved) { saved.groupBy { it.seriesId }.values.toList() }
    var open by rememberSaveable { mutableStateOf("") }
    var wipe by remember { mutableStateOf(false) }
    var dropSeries by remember { mutableStateOf<List<DlMeta>?>(null) }
    var dropChapter by remember { mutableStateOf<DlMeta?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (saved.isEmpty() && queue.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 36.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                Icon(Ic.Download, null, tint = Line, modifier = Modifier.size(56.dp))
                Text("Belum ada chapter yang diunduh", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
                Text("Buka halaman komik, lalu ketuk ikon unduh di samping chapter, atau pakai tombol Unduh untuk beberapa chapter sekaligus.",
                    color = Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 24.dp)) {
            item("summary") {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("${saved.size} chapter · ${fmtBytes(Downloads.totalBytes)}", color = Muted, fontSize = 13.sp)
                    }
                    if (saved.isNotEmpty()) Text("Hapus semua", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(R6).clickable { wipe = true }.padding(8.dp))
                }
            }
            if (queue.isNotEmpty()) {
                item("queue-head") { DlSectionLabel("Antrean (${queue.size})", if (queue.any { it.value.phase != DlPhase.Failed }) "Batalkan semua" else "Bersihkan") { Downloads.cancelAll() } }
                items(queue, key = { "q" + it.key }) { (id, p) -> QueueRow(id, p) }
            }
            if (groups.isNotEmpty()) {
                item("saved-head") { DlSectionLabel("Tersimpan", null) {} }
                items(groups, key = { it.first().seriesId }) { g ->
                    val head = g.first()
                    val expanded = open == head.seriesId
                    val chapters = remember(g) { g.sortedWith(compareBy<DlMeta>({ chapterOrder(it.number) }, { it.number })) }
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(R6).background(CardBg).border(1.dp, Line, R6)) {
                        Row(Modifier.fillMaxWidth().clickable { open = if (expanded) "" else head.seriesId }.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Img(head.cover, head.provider, Modifier.width(48.dp).aspectRatio(0.72f).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)), maxW = 240)
                            Column(Modifier.weight(1f)) {
                                Text(head.seriesTitle.ifBlank { "Tanpa judul" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${g.size} chapter · ${fmtBytes(g.sumOf { it.bytes })}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                            IconButton({ dropSeries = g }) { Icon(Ic.Trash, "Hapus semua chapter komik ini", tint = Muted, modifier = Modifier.size(22.dp)) }
                            Icon(if (expanded) Ic.Up else Ic.Down, null, tint = Muted, modifier = Modifier.size(20.dp))
                        }
                        if (expanded) {
                            HorizontalDivider(color = Line)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Buka halaman komik", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clip(R6).clickable { onSeries(head.seriesId) }.padding(vertical = 6.dp, horizontal = 4.dp))
                            }
                            chapters.forEach { m ->
                                HorizontalDivider(color = Line)
                                Row(Modifier.fillMaxWidth().clickable {
                                    // catat ke riwayat baca seperti membuka dari halaman detail (untuk "Lanjutkan" & profil)
                                    Library.open(Detail(id = m.seriesId, provider = m.provider, title = m.seriesTitle, cover = m.cover), Chap(m.chapterId, m.number))
                                    onRead(m.seriesId, m.chapterId)
                                }.padding(start = 14.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
                                        Text("Chapter ${m.number}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text("${m.files.size} halaman · ${fmtBytes(m.bytes)}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                                    }
                                    IconButton({ dropChapter = m }) { Icon(Ic.Trash, "Hapus", tint = Muted, modifier = Modifier.size(20.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (wipe) ConfirmDialog("Hapus semua unduhan?", "${saved.size} chapter (${fmtBytes(Downloads.totalBytes)}) dihapus dari perangkat. Antrean ikut dibatalkan.", "Hapus semua",
        { wipe = false; Downloads.deleteAll() }, { wipe = false })
    dropSeries?.let { g -> ConfirmDialog("Hapus unduhan komik ini?", "${g.size} chapter \"${g.first().seriesTitle}\" dihapus dari perangkat.", "Hapus",
        { dropSeries = null; Downloads.deleteSeries(g.first().seriesId) }, { dropSeries = null }) }
    dropChapter?.let { m -> ConfirmDialog("Hapus unduhan?", "Chapter ${m.number} dihapus dari perangkat.", "Hapus",
        { dropChapter = null; Downloads.delete(m.chapterId) }, { dropChapter = null }) }
}

@Composable
private fun DlSectionLabel(text: String, action: String?, onAction: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) Text(action, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(R6).clickable(onClick = onAction).padding(8.dp))
    }

@Composable
private fun QueueRow(id: String, p: DlProgress) {
    val failed = p.phase == DlPhase.Failed
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(R6).background(CardBg).border(1.dp, if (failed) Color(0x66E5484D) else Line, R6).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${p.title.ifBlank { "Komik" }} · Chapter ${p.number}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                failed -> Text(p.error.ifBlank { "Gagal mengunduh" }, color = Color(0xFFE5484D), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                p.phase == DlPhase.Queued || p.total == 0 -> Text(if (p.phase == DlPhase.Queued) "Menunggu antrean" else "Menyiapkan…", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                else -> {
                    Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(R6).background(Line)) {
                        Box(Modifier.fillMaxWidth((p.done.toFloat() / p.total).coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                    }
                    Text("${p.done} / ${p.total} halaman", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
        if (failed) TextButton({ Downloads.retry(id) }) { Text("Coba lagi", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        IconButton({ Downloads.cancel(id) }) { Icon(Ic.Close, "Batalkan", tint = Muted, modifier = Modifier.size(20.dp)) }
    }
}
