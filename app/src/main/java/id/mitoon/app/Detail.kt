package id.mitoon.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
private fun Failed(onRetry: () -> Unit) = Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
    Text("Gagal memuat", color = Muted); TextButton(onRetry) { Text("Coba lagi", color = Accent) }
}

private fun relTime(iso: String?): String? {
    if (iso == null || iso.length < 19) return null
    val t = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso.substring(0, 19))!!.time
    }.getOrNull() ?: return null
    val m = (System.currentTimeMillis() - t) / 60000
    return when {
        m < 1 -> "Baru saja"; m < 60 -> "$m menit lalu"; m < 1440 -> "${m / 60} jam lalu"; m < 10080 -> "${m / 1440} hari lalu"
        m < 43200 -> "${m / 10080} minggu lalu"; m < 525600 -> "${m / 43200} bulan lalu"; else -> "${m / 525600} tahun lalu"
    }
}

@Composable
private fun ActionButton(text: String, filled: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Box(modifier.alpha(if (enabled) 1f else 0.45f).height(40.dp).clip(R6).then(if (filled) Modifier.background(Accent) else Modifier.border(1.5.dp, Accent, R6)).clickable(enabled = enabled, onClick = onClick),
        Alignment.Center) {
        Text(text, color = if (filled) OnAccent else Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }

@Composable
private fun Info(label: String, content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.width(80.dp)); content()
    }

@Composable
private fun DetailTabs(tab: Int, onTab: (Int) -> Unit) = Column(Modifier.padding(top = 20.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf("Chapter", "Komentar").forEachIndexed { i, t ->
            Column(Modifier.width(IntrinsicSize.Max).clickable { onTab(i) }) {
                Text(t, fontSize = 16.sp, color = if (i == tab) Color.White else Muted, fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp))
                Box(Modifier.height(3.dp).fillMaxWidth().background(if (i == tab) Accent else Color.Transparent))
            }
        }
    }
    HorizontalDivider(color = Line, thickness = 1.dp)
}

// ------------------------------------------------------------------ DETAIL KOMIK (mengikuti layout Siikomik)
@Composable
private fun LazyLoader() =
    MascotLoading(Modifier.fillMaxWidth().padding(vertical = 20.dp), 96.dp, "Memuat chapter…")

@Composable
private fun RetryBox(text: String, onRetry: () -> Unit) =
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Muted); TextButton(onRetry) { Text("Coba lagi", color = Accent) }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit, onSearch: () -> Unit, onGenre: (String) -> Unit, onChapter: (seriesId: String, chapterId: String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    // Detail yang pernah dibuka langsung tampil dari cache memori; data terbaru tetap diambil di belakang layar.
    val res by produceState<Result<Detail>?>(Net.detailCache[id]?.let { Result.success(it) }, id, tick) {
        if (tick > 0) value = null
        // Belum ada di memori -> coba disk dulu (tampil instan, juga saat offline), lalu segarkan dari server.
        if (value == null) Net.loadDetailDisk(id)?.let { value = Result.success(it) }
        val r = runCatching { Net.detail(id) }
        r.getOrNull()?.let { Net.storeDetail(id, it) }
        if (r.isSuccess || value == null) value = r
    }
    // Info dasar dari kartu yang diklik / pustaka -> header detail muncul seketika, tanpa menunggu server.
    val pv = remember(id) { Net.previews[id] ?: Library.entries[id]?.toSeries() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    val d = res?.getOrNull()
    val failed = res != null && d == null
    val entry = Library.entries[id]

    val title = d?.title?.takeIf { it.isNotBlank() } ?: pv?.title.orEmpty()
    val cover = d?.cover?.takeIf { it.isNotBlank() } ?: pv?.cover.orEmpty()
    val provider = d?.provider?.takeIf { it.isNotBlank() } ?: pv?.provider.orEmpty()
    val genres = d?.genres?.takeIf { it.isNotEmpty() } ?: pv?.genres.orEmpty()
    val rating = d?.rating ?: pv?.rating
    val type = d?.type ?: pv?.type

    val chapters = remember(d, newestFirst) { d?.let { if (newestFirst) it.chapters else it.chapters.asReversed() }.orEmpty() }
    val resumeId = entry?.lastChapterId
    val resume = d?.chapters?.firstOrNull { it.id == resumeId }
    val first = d?.chapters?.lastOrNull()
    val last = d?.chapters?.firstOrNull()
    val saved = entry?.bookmarked == true
    val useResume = resumeId != null && (d == null || resume != null)
    fun open(c: Chap) { d?.let { Library.open(it, c) }; onChapter(id, c.id) }
    // Chapter yang kemungkinan dibuka ("Lanjutkan" / "Baca awal") disiapkan lebih dulu supaya terbuka instan.
    LaunchedEffect(d) { if (d != null) (resumeId ?: first?.id)?.let { Net.prefetchChapter(it) } }
    // Komentar seri disiapkan diam-diam sesaat setelah halaman tampil (1 request kecil, tidak berebut dengan cover/chapter),
    // jadi saat tab Komentar dibuka isinya sudah ada. Membuka tab hanya menyegarkan bila data > 30 dtk.
    val feed = remember(id) { Comments.of("s:$id") }
    LaunchedEffect(id) { delay(700); feed.refresh() }
    LaunchedEffect(d) { d?.let { feed.origin = CommentOrigin(id, it.title, it.cover, it.provider) } }
    LaunchedEffect(tab) { if (tab == 1) feed.refresh() }
    // Datang dari notifikasi komentar: buka tab Komentar, cari komentarnya, gulir dan sorot.
    val listState = rememberLazyListState()
    LaunchedEffect(id) { CommentFocus.take("s:$id")?.let { (cid, pid) -> tab = 1; feed.locate(cid, pid) } }
    ScrollToFocusedComment(feed, listState, tab == 1)
    var synopsisOpen by rememberSaveable(id) { mutableStateOf(false) }
    var synopsisLong by remember(id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header(onSearch, onBack)
        LazyColumn(Modifier.imePadding(), listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)) {
            // Kepala: cover kecil (2:3) di kiri, judul + info ringkas di kanan -> muat di layar HP tanpa scroll panjang.
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Img(cover, provider, Modifier.width(140.dp).aspectRatio(2f / 3f).clip(R6))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        rating?.takeIf { it > 0 }?.let { r ->
                            val five = if (r > 5) r / 2 else r
                            Text(buildAnnotatedString {
                                for (i in 1..5) withStyle(SpanStyle(color = if (i <= Math.round(five)) Star else Color(0xFF55585F))) { append("★") }
                                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append("  %.1f".format(r)) }
                            }, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                        val meta = listOfNotNull(
                            type?.replaceFirstChar { c -> c.uppercase() },
                            d?.status?.let { st -> when (st) { "ongoing" -> "OnGoing"; "completed" -> "Tamat"; "hiatus" -> "Hiatus"; else -> st } }
                        ).joinToString("  ·  ")
                        if (meta.isNotEmpty()) Text(meta, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        if (d != null && d.author.isNotBlank()) Text(d.author, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            // Detail (sinopsis) tepat di bawah cover, di atas genre. Ringkas 4 baris; ketuk untuk membuka penuh.
            if (d == null && !failed) item("synopsis-skel") {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().height(12.dp).background(Color(0xFF24262C), R6))
                    Box(Modifier.fillMaxWidth().height(12.dp).background(Color(0xFF24262C), R6))
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).background(Color(0xFF24262C), R6))
                }
            } else if (d != null && d.synopsis.isNotBlank()) item("synopsis") {
                Column(Modifier.padding(top = 14.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (synopsisLong || synopsisOpen) synopsisOpen = !synopsisOpen }) {
                    Text(d.synopsis, color = Color(0xFFD0D3DA), fontSize = 14.sp, lineHeight = 21.sp,
                        maxLines = if (synopsisOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!synopsisOpen) synopsisLong = it.hasVisualOverflow })
                    if (synopsisLong || synopsisOpen) Text(if (synopsisOpen) "Tutup" else "Selengkapnya", color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (genres.isNotEmpty()) item {
                FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    genres.forEach { gn -> GenreBox(gn) { onGenre(genreSlug(gn)) } }
                }
            }
            item { Column {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(if (useResume) "Lanjutkan Ch. ${entry?.lastChapterNumber.orEmpty()}" else "Baca awal", true, Modifier.weight(1f),
                        enabled = useResume || first != null) {
                        if (useResume) { if (resume != null) open(resume) else resumeId?.let { onChapter(id, it) } } else first?.let { open(it) }
                    }
                    ActionButton("Baca akhir", true, Modifier.weight(1f), enabled = last != null) { last?.let { open(it) } }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(if (saved) "Tersimpan" else "Tambah pustaka", false, Modifier.fillMaxWidth(),
                        enabled = title.isNotBlank()) { Library.toggleBookmark(id, title, cover, provider) }
                }
            } }
            item { Column {
                DetailTabs(tab) { tab = it }
            } }
            if (tab == 0) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (d != null) "Chapters: ${d.chapters.size}" else "Chapters", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (d != null) Text(if (newestFirst) "Terlama ⇅" else "Terbaru ⇅", color = Muted, fontSize = 14.sp,
                            modifier = Modifier.clickable { newestFirst = !newestFirst }.padding(8.dp))
                    }
                }
                when {
                    d == null && failed -> item { RetryBox("Gagal memuat chapter") { tick++ } }
                    d == null -> item { LazyLoader() }
                    chapters.isEmpty() -> item { Text("Belum ada chapter.", color = Muted, modifier = Modifier.padding(vertical = 16.dp)) }
                    else -> {
                        items(chapters, key = { it.id }) { c ->
                            val read = entry?.read?.contains(c.id) == true
                            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(R6).background(CardBg).border(1.dp, Line, R6)
                                .clickable { open(c) }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Text("Chapter ${c.number}", color = if (read) Muted else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                if (c.title.isNotBlank() && !c.title.equals("Chapter ${c.number}", true))
                                    Text(c.title, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                relTime(c.date)?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                            }
                        }
                    }
                }
            } else {
                item("composer") { CommentComposer(feed, Modifier.padding(top = 16.dp)) }
                item("comment-head", contentType = "head") {
                    Text("Komentar (${feed.total})", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                }
                commentItems(feed)
            }
        }
    }
}

// ------------------------------------------------------------------ BACA CHAPTER
// Scroll vertikal. Tap = tampil/sembunyi bar. Bar atas: kembali + judul + daftar chapter.
// Bar bawah (rata, menempel ke tepi): slider halaman, lalu Prev | Atur | Putar | Seri | Next (ikon + label).
private val Pill = Color(0xFF202125)
private val speeds = listOf("Lambat" to 70, "Sedang" to 130, "Cepat" to 230) // dp per detik
private var readSpeed by mutableIntStateOf(1)

@Composable
fun ReaderScreen(seriesId: String, chapterId: String, onBack: () -> Unit, onSeries: () -> Unit, onChapter: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    // Chapter yang pernah dibuka / sudah di-prefetch tampil seketika; versi terbaru diambil diam-diam (UI hanya berubah bila isinya beda).
    val res by produceState<Result<Content>?>(Net.cachedChapter(chapterId)?.let { Result.success(it) }, chapterId, tick) {
        if (tick > 0) value = null
        val r = runCatching { Net.chapter(chapterId) }
        r.getOrNull()?.let { Net.storeChapter(chapterId, it) }
        if (r.isSuccess) { if (value?.getOrNull() != r.getOrNull()) value = r } else if (value == null) value = r
    }
    val c = res?.getOrNull()
    var bars by remember { mutableStateOf(false) } // mulai bersih: hanya gambar; tap untuk menampilkan bar
    var playing by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(CommentFocus.pending("c:$chapterId")) } // dari notifikasi komentar: sheet langsung terbuka
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Layar penuh: status bar & tombol navigasi sistem disembunyikan selama membaca (geser dari tepi untuk memunculkan sementara).
    val view = LocalView.current
    DisposableEffect(view) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(c) { if (c != null) Library.advance(seriesId, chapterId, c.number) }
    // XP baca: dihitung setelah chapter benar-benar dibuka beberapa detik (bukan sekadar lewat).
    LaunchedEffect(chapterId, c != null) { if (c != null) { delay(8000); Xp.event("read", chapterId) } }
    // Selagi membaca: ambil chapter berikutnya + sebelumnya, unduh gambar di depan posisi baca.
    LaunchedEffect(c?.next) { c?.next?.let { Net.prefetchChapter(it) } }
    LaunchedEffect(c?.prev) { c?.prev?.let { Net.prefetchChapter(it) } }
    // Komentar chapter disiapkan diam-diam setelah gambar mulai tampil -> sheet Komentar terbuka langsung berisi.
    LaunchedEffect(chapterId, c != null) { if (c != null) { delay(1200); Comments.prefetch("c:$chapterId") } }
    val preloaded = remember(chapterId) { mutableIntStateOf(0) }
    val firstVisible = listState.firstVisibleItemIndex
    // Segera unduh 10 gambar pertama begitu chapter siap (bukan tunggu scroll).
    LaunchedEffect(c) {
        val cc = c ?: return@LaunchedEffect
        val to = minOf(10, cc.images.size)
        if (to > 0) { Net.preloadImages(cc.images.subList(0, to), cc.imageHeaders); preloaded.intValue = to }
    }
    LaunchedEffect(c, firstVisible) {
        val cc = c ?: return@LaunchedEffect
        val from = maxOf(preloaded.intValue, firstVisible + 2)
        val to = minOf(firstVisible + 10, cc.images.size)
        if (from < to) { Net.preloadImages(cc.images.subList(from, to), cc.imageHeaders); preloaded.intValue = to }
    }

    // Auto-scroll halus per frame; berhenti sendiri di akhir chapter.
    LaunchedEffect(playing, readSpeed) {
        if (!playing) return@LaunchedEffect
        val pxPerSec = with(density) { speeds[readSpeed].second.dp.toPx() }
        var last = withFrameNanos { it }
        var stuck = 0
        while (true) {
            val now = withFrameNanos { it }
            val consumed = listState.scrollBy(pxPerSec * (now - last) / 1_000_000_000f)
            last = now
            if (consumed == 0f) { if (++stuck > 10) { playing = false; break } } else stuck = 0
        }
    }

    // Daftar chapter untuk menu: dari cache (memori/disk) dulu, baru server; hanya diambil saat menu dibuka.
    val detail by produceState<Detail?>(Net.detailCache[seriesId], seriesId, showList) {
        if (value == null && showList) {
            value = Net.loadDetailDisk(seriesId)
                ?: runCatching { Net.detail(seriesId) }.getOrNull()?.also { Net.storeDetail(seriesId, it) }
        }
    }

    val seriesTitle = (detail?.title ?: Library.entries[seriesId]?.title).orEmpty()
    val total = c?.images?.size ?: 0
    val page = (listState.firstVisibleItemIndex + 1).coerceIn(1, maxOf(total, 1))
    val atEnd = total > 0 && !listState.canScrollForward
    LaunchedEffect(atEnd) { if (atEnd) bars = true }

    Box(Modifier.fillMaxSize()) {
        when {
            res == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { MascotLoading(size = 150.dp, label = "Menyiapkan chapter…") }
            c == null -> Failed { tick++ }
            c.images.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Chapter belum tersedia", color = Muted) }
            else -> LazyColumn(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { bars = !bars }, listState) {
                items(c.images, key = { it }) { url -> Img(url, "", Modifier.fillMaxWidth().heightIn(min = 240.dp), ContentScale.FillWidth, c.imageHeaders) }
            }
        }
        AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), fadeIn(tween(90)), fadeOut(tween(90))) {
            ReaderTopBar(c?.number?.let { "Chapter $it" } ?: "Baca", seriesTitle, onBack, onComments = { showComments = true }, onSettings = { showSettings = true }) { showList = true }
        }
        if (c != null && total > 0) AnimatedVisibility(bars, Modifier.align(Alignment.BottomCenter), fadeIn(tween(90)), fadeOut(tween(90))) {
            BottomBar(page, total, { i -> scope.launch { listState.scrollToItem(i) } }, playing, c.prev != null, c.next != null,
                onPrev = { c.prev?.let(onChapter) }, onNext = { c.next?.let(onChapter) },
                onPlay = { playing = !playing; if (playing) bars = false })
        }
    }

    if (showComments) {
        val lib = Library.entries[seriesId]
        val origin = detail?.let { CommentOrigin(seriesId, it.title, it.cover, it.provider, chapterId, c?.number) }
            ?: lib?.let { CommentOrigin(seriesId, it.title, it.cover, it.provider, chapterId, c?.number) }
        CommentsSheet("c:$chapterId", c?.number?.let { "Chapter $it" }.orEmpty(), origin) { showComments = false }
    }
    if (showList) ChapterSheet(detail, chapterId, onDismiss = { showList = false }) { id -> showList = false; if (id != chapterId) onChapter(id) }
    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        confirmButton = { TextButton({ showSettings = false }) { Text("Selesai", color = Accent) } },
        title = { Text("Pengaturan baca", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Kecepatan auto-scroll", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { speeds.forEachIndexed { i, (name, _) -> Chip(name, readSpeed == i) { readSpeed = i } } }
            }
        },
        shape = R6, containerColor = CardBg, titleContentColor = Color.White, textContentColor = Color.White
    )
}

@Composable
private fun Modifier.noTap(): Modifier = clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

/** Bar atas: judul seri (kecil) + chapter aktif, lalu aksi sekunder (komentar, pengaturan, daftar chapter) di kanan. */
@Composable
private fun ReaderTopBar(title: String, subtitle: String, onBack: () -> Unit, onComments: () -> Unit, onSettings: () -> Unit, onMenu: () -> Unit) =
    Column(Modifier.fillMaxWidth().background(Pill).noTap()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(true, onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp), verticalArrangement = Arrangement.Center) {
                if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BarIcon(true, onComments) { CommentIcon(Color.White, 22.dp) }
            BarIcon(true, onSettings) { Icon(Icons.Default.Settings, "Pengaturan baca", tint = Color.White, modifier = Modifier.size(22.dp)) }
            BarIcon(true, onMenu) { Icon(Icons.Default.Menu, "Daftar chapter", tint = Color.White) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }

@Composable
private fun BarIcon(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.size(48.dp).alpha(if (enabled) 1f else 0.35f).clip(R6).clickable(enabled = enabled, onClick = onClick), Alignment.Center) { content() }

@Composable
private fun PauseIcon(color: Color = Color.White) = Canvas(Modifier.size(20.dp)) {
    val w = size.width; val h = size.height; val r = CornerRadius(1.dp.toPx())
    drawRoundRect(color, Offset(w * 0.18f, h * 0.12f), Size(w * 0.24f, h * 0.76f), r)
    drawRoundRect(color, Offset(w * 0.58f, h * 0.12f), Size(w * 0.24f, h * 0.76f), r)
}

/** Tombol chapter sebelumnya/berikutnya: `primary` = terisi putih (arah maju), lainnya = garis tipis. Nonaktif bila tidak ada chapter. */
@Composable
private fun RowScope.ChapterButton(label: String, enabled: Boolean, primary: Boolean, forward: Boolean, onClick: () -> Unit) {
    val fg = if (primary) OnAccent else Color.White
    val icon = if (forward) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft
    Row(Modifier.weight(1f).height(44.dp).alpha(if (enabled) 1f else 0.35f).clip(R6)
        .then(if (primary) Modifier.background(Accent) else Modifier.border(1.dp, Color(0xFF4A4C53), R6))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (!forward) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (forward) Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
    }
}

/** Bar bawah: posisi halaman + slider, lalu satu baris kontrol: Sebelumnya | auto-scroll | Berikutnya. */
@Composable
private fun BottomBar(
    page: Int, total: Int, onSeek: (Int) -> Unit, playing: Boolean, canPrev: Boolean, canNext: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onPlay: () -> Unit
) {
    Column(Modifier.fillMaxWidth().background(Pill).noTap()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append("$page") }
                withStyle(SpanStyle(color = Muted)) { append(" / $total") }
            }, fontSize = 13.sp, modifier = Modifier.widthIn(min = 52.dp))
            PageSlider(page, total, onSeek, Modifier.weight(1f).padding(horizontal = 8.dp))
            Text("${if (total > 0) page * 100 / total else 0}%", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 36.dp), textAlign = TextAlign.End)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChapterButton("Sebelumnya", canPrev, primary = false, forward = false, onClick = onPrev)
            Box(Modifier.size(44.dp).clip(R6).then(if (playing) Modifier.background(Color.White) else Modifier.border(1.dp, Color(0xFF4A4C53), R6)).clickable(onClick = onPlay), Alignment.Center) {
                if (playing) PauseIcon(Color.Black) else Icon(Icons.Default.PlayArrow, "Auto-scroll", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            ChapterButton("Berikutnya", canNext, primary = true, forward = true, onClick = onNext)
        }
    }
}

/** Slider halaman: tap atau geser untuk lompat ke gambar ke-N. */
@Composable
private fun PageSlider(page: Int, total: Int, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
    val seek by rememberUpdatedState(onSeek)
    val frac = if (total > 1) (page - 1).toFloat() / (total - 1) else 0f
    fun at(x: Float, w: Int) = if (total > 1 && w > 0) ((x / w).coerceIn(0f, 1f) * (total - 1)).roundToInt() else 0
    Canvas(
        modifier.height(32.dp)
            .pointerInput(total) { detectTapGestures { o -> seek(at(o.x, size.width)) } }
            .pointerInput(total) {
                detectHorizontalDragGestures(onDragStart = { o -> seek(at(o.x, size.width)) }) { change, _ ->
                    change.consume(); seek(at(change.position.x, size.width))
                }
            }
    ) {
        val th = 3.dp.toPx(); val cy = size.height / 2; val r = 7.dp.toPx()
        val x = (size.width * frac).coerceIn(r, size.width - r)
        drawRoundRect(Color(0xFF3A3C44), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        drawRoundRect(Color.White, Offset(0f, cy - th / 2), Size(x, th), CornerRadius(th / 2))
        drawCircle(Color.White.copy(alpha = 0.18f), r + 4.dp.toPx(), Offset(x, cy))
        drawCircle(Color.White, r, Offset(x, cy))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(detail: Detail?, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp), containerColor = CardBg, contentColor = Color.White) {
        Text("Daftar chapter", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        val chapters = detail?.chapters.orEmpty()
        if (chapters.isEmpty()) Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
            if (detail == null) MascotLoading(size = 90.dp) else Text("Belum ada chapter.", color = Muted)
        } else {
            val st = rememberLazyListState(initialFirstVisibleItemIndex = chapters.indexOfFirst { it.id == current }.coerceAtLeast(0))
            LazyColumn(Modifier.heightIn(max = 480.dp), st, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                items(chapters, key = { it.id }) { ch ->
                    val on = ch.id == current
                    Text("Chapter ${ch.number}", color = if (on) OnAccent else Color.White, fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(R6).then(if (on) Modifier.background(Accent) else Modifier.background(Bg))
                            .clickable { onPick(ch.id) }.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
        }
    }
}
