package id.mitoon.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
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
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
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
        listOf("Chapter", "Detail").forEachIndexed { i, t ->
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
    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), Accent, strokeWidth = 2.dp) }

@Composable
private fun RetryBox(text: String, onRetry: () -> Unit) =
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Muted); TextButton(onRetry) { Text("Coba lagi", color = Accent) }
    }

@Composable
fun DetailScreen(id: String, onBack: () -> Unit, onSearch: () -> Unit, onOpenSeries: (String) -> Unit, onChapter: (seriesId: String, chapterId: String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    // Detail yang pernah dibuka langsung tampil dari cache memori; data terbaru tetap diambil di belakang layar.
    val res by produceState<Result<Detail>?>(Net.detailCache[id]?.let { Result.success(it) }, id, tick) {
        if (tick > 0) value = null
        // Belum ada di memori -> coba disk dulu (tampil instan, juga saat offline), lalu segarkan dari server.
        if (value == null) Net.loadDetailDisk(id)?.let { value = Result.success(it) }
        val r = runCatching { Net.api.detail(id).data }
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

    Column(Modifier.fillMaxSize()) {
        Header(onSearch, onBack)
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)) {
            item { Img(cover, provider, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))) }
            item { Column {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(if (useResume) "Lanjutkan Ch. ${entry?.lastChapterNumber.orEmpty()}" else "Baca awal", true, Modifier.weight(1f),
                        enabled = useResume || first != null) {
                        if (useResume) { if (resume != null) open(resume) else resumeId?.let { onChapter(id, it) } } else first?.let { open(it) }
                    }
                    ActionButton("Baca akhir", true, Modifier.weight(1f), enabled = last != null) { last?.let { open(it) } }
                }
                ActionButton(if (saved) "Tersimpan di pustaka" else "Tambah pustaka", false, Modifier.fillMaxWidth().padding(top = 10.dp),
                    enabled = title.isNotBlank()) { Library.toggleBookmark(id, title, cover, provider) }
            } }
            item { Column {
                Text(title, color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
                if (genres.isNotEmpty()) Text(genres.joinToString("  ·  "), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                rating?.takeIf { it > 0 }?.let { r ->
                    val five = if (r > 5) r / 2 else r
                    Info("Rating") {
                        Text(buildAnnotatedString {
                            for (i in 1..5) withStyle(SpanStyle(color = if (i <= Math.round(five)) Star else Color(0xFF55585F))) { append("★ ") }
                            withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append(" %.1f".format(r)) }
                        }, fontSize = 15.sp)
                    }
                }
                type?.let { Info("Type") { Text(it.replaceFirstChar { c -> c.uppercase() }, color = Color.White, fontSize = 14.sp) } }
                d?.status?.let { st ->
                    Info("Status") { Text(when (st) { "ongoing" -> "OnGoing"; "completed" -> "Tamat"; "hiatus" -> "Hiatus"; else -> st }, color = Color.White, fontSize = 14.sp) }
                }
                if (d != null && d.author.isNotBlank()) Info("Penulis") { Text(d.author, color = Color.White, fontSize = 14.sp) }
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
            } else item {
                when {
                    d != null -> Text(d.synopsis.ifBlank { "Belum ada sinopsis." }, color = Color(0xFFD0D3DA), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 16.dp))
                    failed -> RetryBox("Gagal memuat detail") { tick++ }
                    else -> LazyLoader()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ BACA CHAPTER
// Scroll vertikal. Tap = tampil/sembunyi bar. Bar atas: kembali + judul + daftar chapter.
// Bar bawah: slider halaman, lalu  <  pengaturan  putar(auto-scroll)  info seri  >.
private val Pill = Color(0xFF202125)
private val speeds = listOf("Lambat" to 70, "Sedang" to 130, "Cepat" to 230) // dp per detik
private var readSpeed by mutableIntStateOf(1)

@Composable
fun ReaderScreen(seriesId: String, chapterId: String, onBack: () -> Unit, onSeries: () -> Unit, onChapter: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val res by produceState<Result<Content>?>(null, chapterId, tick) { value = null; value = runCatching { Net.api.chapter(chapterId).data } }
    val c = res?.getOrNull()
    var bars by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(c) { if (c != null) Library.advance(seriesId, chapterId, c.number) }

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
                ?: runCatching { Net.api.detail(seriesId).data }.getOrNull()?.also { Net.storeDetail(seriesId, it) }
        }
    }

    val total = c?.images?.size ?: 0
    val page = (listState.firstVisibleItemIndex + 1).coerceIn(1, maxOf(total, 1))

    Box(Modifier.fillMaxSize()) {
        when {
            res == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            c == null -> Failed { tick++ }
            c.images.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Chapter belum tersedia", color = Muted) }
            else -> LazyColumn(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { bars = !bars }, listState) {
                items(c.images, key = { it }) { url -> Img(url, "", Modifier.fillMaxWidth().heightIn(min = 240.dp), ContentScale.FillWidth, c.imageHeaders) }
                item { NavButtons(c, onChapter) }
            }
        }
        AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), fadeIn(), fadeOut()) {
            TopPill(c?.number?.let { "Chapter $it" } ?: "Baca", onBack) { showList = true }
        }
        if (c != null && total > 0) AnimatedVisibility(bars, Modifier.align(Alignment.BottomCenter), fadeIn(), fadeOut()) {
            BottomPill(page, total, { i -> scope.launch { listState.scrollToItem(i) } }, playing, c.prev != null, c.next != null,
                onPrev = { c.prev?.let(onChapter) }, onNext = { c.next?.let(onChapter) },
                onSettings = { showSettings = true }, onPlay = { playing = !playing }, onInfo = onSeries)
        }
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
        containerColor = CardBg, titleContentColor = Color.White, textContentColor = Color.White
    )
}

@Composable
private fun Modifier.noTap(): Modifier = clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

@Composable
private fun TopPill(title: String, onBack: () -> Unit, onMenu: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).height(52.dp).shadow(10.dp, CircleShape).background(Pill, CircleShape).noTap(),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) }
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp))
        IconButton(onMenu) { Icon(Icons.Default.Menu, "Daftar chapter", tint = Color.White) }
    }

@Composable
private fun BarIcon(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.size(48.dp).alpha(if (enabled) 1f else 0.35f).clip(CircleShape).clickable(enabled = enabled, onClick = onClick), Alignment.Center) { content() }

@Composable
private fun PauseIcon() = Canvas(Modifier.size(24.dp)) {
    val w = size.width; val h = size.height; val r = CornerRadius(3.dp.toPx())
    drawRoundRect(Color.White, Offset(w * 0.18f, h * 0.12f), Size(w * 0.24f, h * 0.76f), r)
    drawRoundRect(Color.White, Offset(w * 0.58f, h * 0.12f), Size(w * 0.24f, h * 0.76f), r)
}

@Composable
private fun BottomPill(
    page: Int, total: Int, onSeek: (Int) -> Unit, playing: Boolean, canPrev: Boolean, canNext: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onSettings: () -> Unit, onPlay: () -> Unit, onInfo: () -> Unit
) {
    val shape = RoundedCornerShape(30.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).shadow(10.dp, shape).background(Pill, shape).noTap().padding(top = 10.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$page", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 22.dp))
            PageSlider(page, total, onSeek, Modifier.weight(1f).padding(horizontal = 10.dp))
            Text("$total", color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BarIcon(canPrev, onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Chapter sebelumnya", tint = Color.White, modifier = Modifier.size(30.dp)) }
            BarIcon(true, onSettings) { Icon(Icons.Default.Settings, "Pengaturan", tint = Color.White) }
            Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF3A3C42)).clickable(onClick = onPlay), Alignment.Center) {
                if (playing) PauseIcon() else Icon(Icons.Default.PlayArrow, "Auto-scroll", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            BarIcon(true, onInfo) { Icon(Icons.Default.Info, "Info seri", tint = Color.White) }
            BarIcon(canNext, onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Chapter berikutnya", tint = Color.White, modifier = Modifier.size(30.dp)) }
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
        val th = 8.dp.toPx(); val cy = size.height / 2
        drawRoundRect(Color(0xFF4A4C53), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        val x = (size.width * frac).coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
        drawRoundRect(Color(0xFF9EA2AD), Offset(0f, cy - th / 2), Size(x, th), CornerRadius(th / 2))
        drawRoundRect(Color.White, Offset(x - 2.dp.toPx(), cy - 12.dp.toPx()), Size(4.dp.toPx(), 24.dp.toPx()), CornerRadius(2.dp.toPx()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(detail: Detail?, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = CardBg, contentColor = Color.White) {
        Text("Daftar chapter", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        val chapters = detail?.chapters.orEmpty()
        if (chapters.isEmpty()) Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
            if (detail == null) CircularProgressIndicator(Modifier.size(24.dp), Accent, strokeWidth = 2.dp) else Text("Belum ada chapter.", color = Muted)
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

@Composable
private fun NavButtons(c: Content, onChapter: (String) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent, disabledContainerColor = Line, disabledContentColor = Muted)
        Button({ c.prev?.let(onChapter) }, Modifier.weight(1f).height(40.dp), enabled = c.prev != null, colors = colors, shape = R6) { Text("Sebelumnya", fontWeight = FontWeight.Bold) }
        Button({ c.next?.let(onChapter) }, Modifier.weight(1f).height(40.dp), enabled = c.next != null, colors = colors, shape = R6) { Text("Berikutnya", fontWeight = FontWeight.Bold) }
    }
