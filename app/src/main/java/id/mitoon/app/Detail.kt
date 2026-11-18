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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.animation.core.animateFloatAsState
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
// Navigasi baca ala iOS: bar atas + bawah floating glass, settings sheet, auto-next chapter.
private val Pill = Color(0xE6202128)
private val Glass = Color(0xCC2A2C34)
private val speeds = listOf("Lambat" to 70, "Sedang" to 130, "Cepat" to 230) // dp per detik
private val delayOptions = listOf("Langsung" to 0, "3 dtk" to 3, "5 dtk" to 5)
private var readSpeed by mutableIntStateOf(1)
private var autoNextChapter by mutableStateOf(true)
private var autoNextDelayIdx by mutableIntStateOf(1) // 0=langsung, 1=3s, 2=5s

@Composable
fun ReaderScreen(seriesId: String, chapterId: String, onBack: () -> Unit, onSeries: () -> Unit, onChapter: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val res by produceState<Result<Content>?>(Net.cachedChapter(chapterId)?.let { Result.success(it) }, chapterId, tick) {
        if (tick > 0) value = null
        val r = runCatching { Net.chapter(chapterId) }
        r.getOrNull()?.let { Net.storeChapter(chapterId, it) }
        if (r.isSuccess) { if (value?.getOrNull() != r.getOrNull()) value = r } else if (value == null) value = r
    }
    val c = res?.getOrNull()
    var bars by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(CommentFocus.pending("c:$chapterId")) }
    var showAutoNext by remember { mutableStateOf(false) }
    var autoNextCountdown by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
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
    LaunchedEffect(chapterId, c != null) { if (c != null) { delay(8000); Xp.event("read", chapterId) } }
    LaunchedEffect(c?.next) { c?.next?.let { Net.prefetchChapter(it) } }
    LaunchedEffect(c?.prev) { c?.prev?.let { Net.prefetchChapter(it) } }
    LaunchedEffect(chapterId, c != null) { if (c != null) { delay(1200); Comments.prefetch("c:$chapterId") } }
    val preloaded = remember(chapterId) { mutableIntStateOf(0) }
    val firstVisible = listState.firstVisibleItemIndex
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

    // Auto-scroll halus; di akhir chapter: biarkan playing tetap true bila auto-next aktif (lanjut chapter).
    LaunchedEffect(playing, readSpeed, chapterId) {
        if (!playing) return@LaunchedEffect
        val pxPerSec = with(density) { speeds[readSpeed].second.dp.toPx() }
        var last = withFrameNanos { it }
        var stuck = 0
        while (true) {
            val now = withFrameNanos { it }
            val consumed = listState.scrollBy(pxPerSec * (now - last) / 1_000_000_000f)
            last = now
            if (consumed == 0f) {
                if (++stuck > 10) {
                    // Akhir chapter: hentikan play hanya jika auto-next mati / tidak ada next
                    if (!autoNextChapter || c?.next == null) playing = false
                    break
                }
            } else stuck = 0
        }
    }

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

    // Saat sampai akhir chapter: tampilkan bar + kartu auto-next (jika diaktifkan & ada chapter berikutnya).
    LaunchedEffect(atEnd, chapterId) {
        if (!atEnd) {
            showAutoNext = false
            return@LaunchedEffect
        }
        bars = true
        if (!autoNextChapter || c?.next == null) {
            showAutoNext = false
            return@LaunchedEffect
        }
        val sec = delayOptions[autoNextDelayIdx].second
        if (sec <= 0) {
            // Langsung lanjut tanpa kartu
            showAutoNext = false
            c.next?.let(onChapter)
            return@LaunchedEffect
        }
        showAutoNext = true
        autoNextCountdown = sec
        while (autoNextCountdown > 0) {
            delay(1000)
            autoNextCountdown--
        }
        if (showAutoNext) {
            showAutoNext = false
            c.next?.let(onChapter)
        }
    }

    // Reset UI auto-next saat ganti chapter; play tetap jalan jika user sedang auto-scroll.
    LaunchedEffect(chapterId) {
        showAutoNext = false
        bars = false
    }

    Box(Modifier.fillMaxSize()) {
        when {
            res == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { MascotLoading(size = 150.dp, label = "Menyiapkan chapter…") }
            c == null -> Failed { tick++ }
            c.images.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Chapter belum tersedia", color = Muted) }
            else -> LazyColumn(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (showAutoNext) return@clickable
                    bars = !bars
                },
                listState
            ) {
                items(c.images, key = { it }) { url ->
                    Img(url, "", Modifier.fillMaxWidth().heightIn(min = 240.dp), ContentScale.FillWidth, c.imageHeaders)
                }
            }
        }

        // Top bar floating glass
        AnimatedVisibility(bars && !showAutoNext, Modifier.align(Alignment.TopCenter), fadeIn(tween(120)), fadeOut(tween(100))) {
            ReaderTopBar(
                chapterLabel = c?.number?.let { "Chapter $it" } ?: "Baca",
                seriesTitle = seriesTitle,
                onBack = onBack,
                onComments = { showComments = true },
                onSettings = { showSettings = true },
                onTitle = { showList = true }
            )
        }

        // Bottom bar floating glass
        if (c != null && total > 0) {
            AnimatedVisibility(bars && !showAutoNext, Modifier.align(Alignment.BottomCenter), fadeIn(tween(120)), fadeOut(tween(100))) {
                ReaderBottomBar(
                    page = page,
                    total = total,
                    onSeek = { i -> scope.launch { listState.scrollToItem(i) } },
                    playing = playing,
                    canPrev = c.prev != null,
                    canNext = c.next != null,
                    onPrev = { c.prev?.let(onChapter) },
                    onNext = { c.next?.let(onChapter) },
                    onPlay = {
                        playing = !playing
                        if (playing) bars = false
                    }
                )
            }
        }

        // Kartu auto-next di akhir chapter
        AnimatedVisibility(
            showAutoNext && c?.next != null,
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 28.dp),
            fadeIn(tween(180)),
            fadeOut(tween(120))
        ) {
            AutoNextCard(
                currentNumber = c?.number,
                countdown = autoNextCountdown,
                totalSec = delayOptions[autoNextDelayIdx].second.coerceAtLeast(1),
                onCancel = { showAutoNext = false },
                onNow = {
                    showAutoNext = false
                    c?.next?.let(onChapter)
                }
            )
        }
    }

    if (showComments) {
        val lib = Library.entries[seriesId]
        val origin = detail?.let { CommentOrigin(seriesId, it.title, it.cover, it.provider, chapterId, c?.number) }
            ?: lib?.let { CommentOrigin(seriesId, it.title, it.cover, it.provider, chapterId, c?.number) }
        CommentsSheet("c:$chapterId", c?.number?.let { "Chapter $it" }.orEmpty(), origin) { showComments = false }
    }
    if (showList) ChapterSheet(detail, chapterId, onDismiss = { showList = false }) { id ->
        showList = false
        if (id != chapterId) onChapter(id)
    }
    if (showSettings) ReaderSettingsSheet(onDismiss = { showSettings = false })
}

@Composable
private fun Modifier.noTap(): Modifier = clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

private val Capsule = RoundedCornerShape(50)

/** Bar atas floating: back lingkaran + kapsul judul (ketuk = daftar chapter) + kapsul aksi komentar/pengaturan. */
@Composable
private fun ReaderTopBar(
    chapterLabel: String,
    seriesTitle: String,
    onBack: () -> Unit,
    onComments: () -> Unit,
    onSettings: () -> Unit,
    onTitle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .noTap(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back circle
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Glass)
                .clickable(onClick = onBack),
            Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        // Title capsule
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(Capsule)
                .background(Glass)
                .clickable(onClick = onTitle)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (seriesTitle.isNotBlank()) {
                    Text(
                        seriesTitle,
                        color = Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    chapterLabel,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        // Actions capsule
        Row(
            Modifier
                .height(40.dp)
                .clip(Capsule)
                .background(Glass)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onComments), Alignment.Center) {
                CommentIcon(Color.White, 20.dp)
            }
            Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onSettings), Alignment.Center) {
                Icon(Icons.Default.Settings, "Pengaturan baca", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun PauseIcon(color: Color = Color.White) = Canvas(Modifier.size(20.dp)) {
    val w = size.width; val h = size.height; val r = CornerRadius(1.5.dp.toPx())
    drawRoundRect(color, Offset(w * 0.18f, h * 0.12f), Size(w * 0.24f, h * 0.76f), r)
    drawRoundRect(color, Offset(w * 0.58f, h * 0.12f), Size(w * 0.24f, h * 0.76f), r)
}

/** Bar bawah floating glass: progress + Ch.prev | play besar | Ch.next. */
@Composable
private fun ReaderBottomBar(
    page: Int,
    total: Int,
    onSeek: (Int) -> Unit,
    playing: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPlay: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Pill)
            .noTap()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append("$page") }
                    withStyle(SpanStyle(color = Muted)) { append(" / $total") }
                },
                fontSize = 13.sp,
                modifier = Modifier.widthIn(min = 48.dp)
            )
            PageSlider(page, total, onSeek, Modifier.weight(1f).padding(horizontal = 10.dp))
            Text(
                "${if (total > 0) page * 100 / total else 0}%",
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.widthIn(min = 34.dp),
                textAlign = TextAlign.End
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Prev chapter
            Box(
                Modifier
                    .weight(1f)
                    .alpha(if (canPrev) 1f else 0.35f)
                    .clip(Capsule)
                    .clickable(enabled = canPrev, onClick = onPrev)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("‹ Prev", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            // Play
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (playing) Color.White else Color(0xFF3A3C44))
                    .clickable(onClick = onPlay),
                Alignment.Center
            ) {
                if (playing) PauseIcon(Color.Black)
                else Icon(Icons.Default.PlayArrow, "Auto-scroll", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            // Next chapter
            Box(
                Modifier
                    .weight(1f)
                    .alpha(if (canNext) 1f else 0.35f)
                    .clip(Capsule)
                    .clickable(enabled = canNext, onClick = onNext)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text("Next ›", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** Slider halaman: tap / geser. */
@Composable
private fun PageSlider(page: Int, total: Int, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
    val seek by rememberUpdatedState(onSeek)
    val frac = if (total > 1) (page - 1).toFloat() / (total - 1) else 0f
    fun at(x: Float, w: Int) = if (total > 1 && w > 0) ((x / w).coerceIn(0f, 1f) * (total - 1)).roundToInt() else 0
    Canvas(
        modifier
            .height(28.dp)
            .pointerInput(total) { detectTapGestures { o -> seek(at(o.x, size.width)) } }
            .pointerInput(total) {
                detectHorizontalDragGestures(onDragStart = { o -> seek(at(o.x, size.width)) }) { change, _ ->
                    change.consume()
                    seek(at(change.position.x, size.width))
                }
            }
    ) {
        val th = 3.5.dp.toPx()
        val cy = size.height / 2
        val r = 7.dp.toPx()
        val x = (size.width * frac).coerceIn(r, size.width - r)
        drawRoundRect(Color(0xFF3A3C44), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        drawRoundRect(Color.White, Offset(0f, cy - th / 2), Size(x, th), CornerRadius(th / 2))
        drawCircle(Color.White.copy(alpha = 0.2f), r + 3.dp.toPx(), Offset(x, cy))
        drawCircle(Color.White, r, Offset(x, cy))
    }
}

/** Kartu glass auto-next di akhir chapter (countdown + Batal / Lanjut sekarang). */
@Composable
private fun AutoNextCard(
    currentNumber: String?,
    countdown: Int,
    totalSec: Int,
    onCancel: () -> Unit,
    onNow: () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (totalSec > 0) countdown.toFloat() / totalSec else 0f,
        animationSpec = tween(900),
        label = "cd"
    )
    val nextLabel = currentNumber?.toDoubleOrNull()?.let { "Chapter ${(it + 1).toInt()}" } ?: "chapter berikutnya"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Pill)
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .noTap()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 3.dp.toPx()
                    drawCircle(Color(0xFF3A3C44), style = Stroke(stroke))
                    drawArc(
                        Color.White,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                Text("$countdown", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    currentNumber?.let { "Chapter $it selesai" } ?: "Chapter selesai",
                    color = Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Lanjut ke $nextLabel",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(Capsule)
                    .background(Color(0xFF3A3C44))
                    .clickable(onClick = onCancel),
                Alignment.Center
            ) {
                Text("Batal", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(Capsule)
                    .background(Color.White)
                    .clickable(onClick = onNow),
                Alignment.Center
            ) {
                Text("Lanjut sekarang", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Sheet pengaturan baca ala iOS Settings (setengah layar). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = Color(0xFF1C1D22),
        contentColor = Color.White,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(Capsule)
                    .background(Color(0xFF4A4C53))
            )
        }
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Pengaturan baca", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2C34))
                        .clickable(onClick = onDismiss),
                    Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Tutup", tint = Muted, modifier = Modifier.size(18.dp))
                }
            }

            // Kecepatan auto-scroll
            SettingsGroup {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(Color(0xFF0A84FF)),
                        Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Kecepatan auto-scroll", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
                SegmentedControl(
                    items = speeds.map { it.first },
                    selected = readSpeed,
                    onSelect = { readSpeed = it }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Lanjut chapter otomatis
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF30D158)),
                            Alignment.Center
                        ) {
                            Text("»", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Lanjut chapter otomatis", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    Switch(
                        checked = autoNextChapter,
                        onCheckedChange = { autoNextChapter = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF30D158),
                            uncheckedThumbColor = Color(0xFFD0D3DA),
                            uncheckedTrackColor = Color(0xFF3A3C44),
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
                if (autoNextChapter) {
                    Spacer(Modifier.height(14.dp))
                    Text("Jeda sebelum lanjut", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                    SegmentedControl(
                        items = delayOptions.map { it.first },
                        selected = autoNextDelayIdx,
                        onSelect = { autoNextDelayIdx = it }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Saat scroll sampai akhir, chapter berikutnya dibuka sendiri. Bisa dibatalkan sebelum hitungan habis.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF26282F))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun SegmentedControl(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xFF1A1B20))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(7.dp))
                    .then(if (on) Modifier.background(Color.White) else Modifier)
                    .clickable { onSelect(i) },
                Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) Color.Black else Muted,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(detail: Detail?, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = Color(0xFF1C1D22),
        contentColor = Color.White,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(Capsule)
                    .background(Color(0xFF4A4C53))
            )
        }
    ) {
        Text(
            "Daftar chapter",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
        )
        val chapters = detail?.chapters.orEmpty()
        if (chapters.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
                if (detail == null) MascotLoading(size = 90.dp) else Text("Belum ada chapter.", color = Muted)
            }
        } else {
            val st = rememberLazyListState(
                initialFirstVisibleItemIndex = chapters.indexOfFirst { it.id == current }.coerceAtLeast(0)
            )
            LazyColumn(
                Modifier.heightIn(max = 480.dp),
                st,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)
            ) {
                items(chapters, key = { it.id }) { ch ->
                    val on = ch.id == current
                    Text(
                        "Chapter ${ch.number}",
                        color = if (on) Color.Black else Color.White,
                        fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .then(if (on) Modifier.background(Color.White) else Modifier.background(Color(0xFF26282F)))
                            .clickable { onPick(ch.id) }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}
