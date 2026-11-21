@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.graphicsLayer
import android.os.Build
import android.view.View
import android.view.WindowInsetsController

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
    MascotLoading(Modifier.fillMaxWidth().padding(vertical = 20.dp), 96.dp)

@Composable
private fun RetryBox(text: String, onRetry: () -> Unit) =
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Muted); TextButton(onRetry) { Text("Coba lagi", color = Accent) }
    }

/** Ikon ambil ulang: panah melingkar bergaris tipis (selaras ikon komentar & pengaturan); berputar saat memuat, redup bila jatah harian habis. */
@Composable
private fun RefetchIcon(busy: Boolean, left: Int, size: Dp, modifier: Modifier = Modifier) {
    val spin = if (busy) {
        val t = rememberInfiniteTransition(label = "refetch")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "rot").value
    } else 0f
    val tint = if (left > 0 || busy) Color.White else Color(0xFF55585F)
    androidx.compose.foundation.Canvas(modifier.size(size).graphicsLayer { rotationZ = spin }) {
        val u = this.size.width / 24f
        val c = androidx.compose.ui.geometry.Offset(12f * u, 12f * u)
        val r = 7.2f * u
        val w = 1.9.dp.toPx()
        val st = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawArc(tint, -40f, 280f, false, androidx.compose.ui.geometry.Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = st)
        val a = Math.toRadians(240.0)
        val tip = androidx.compose.ui.geometry.Offset(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat())
        val back = kotlin.math.atan2(-kotlin.math.cos(a), kotlin.math.sin(a))
        for (d in doubleArrayOf(38.0, -38.0)) {
            val ang = back + Math.toRadians(d)
            drawLine(tint, tip, androidx.compose.ui.geometry.Offset(tip.x + kotlin.math.cos(ang).toFloat() * 4.8f * u, tip.y + kotlin.math.sin(ang).toFloat() * 4.8f * u), w, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Jumlah ReaderScreen yang sedang aktif; selama > 0 semua dialog/sheet ikut menyembunyikan bilah sistem. */
object Immersive { var readers = 0 }

/** Dipanggil di dalam sheet/dialog: jendelanya sendiri ikut menyembunyikan status & navigation bar, supaya tidak muncul pita warna & layar tidak bergeser. */
@Composable
fun KeepBarsHidden() {
    val v = LocalView.current
    DisposableEffect(v) {
        fun hide() {
            if (Immersive.readers <= 0) return
            val root = v.rootView
            if (Build.VERSION.SDK_INT >= 30) {
                root.windowInsetsController?.let {
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(android.view.WindowInsets.Type.systemBars())
                }
            } else {
                @Suppress("DEPRECATION")
                root.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
        }
        val l = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) = hide()
            override fun onViewDetachedFromWindow(x: View) {}
        }
        v.addOnAttachStateChangeListener(l)
        if (v.isAttachedToWindow) hide()
        onDispose { v.removeOnAttachStateChangeListener(l) }
    }
}


@Composable
private fun RefetchConfirm(what: String, left: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = CardBg, titleContentColor = Color.White, textContentColor = Muted,
    title = { KeepBarsHidden(); Text("Ambil ulang $what?") },
    text = { Text("Data diambil lagi dari sumber dan bisa memakan waktu. Batas ${Net.REFETCH_MAX}x per hari, sisa $left kali hari ini. Jangan dipakai berulang tanpa perlu.") },
    confirmButton = { TextButton(onConfirm) { Text("Ambil ulang", color = Color.White, fontWeight = FontWeight.Bold) } },
    dismissButton = { TextButton(onDismiss) { Text("Batal", color = Muted) } }
)

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
    var dlSheet by remember { mutableStateOf(false) }
    if (dlSheet && d != null) DownloadSheet(d, resumeId) { dlSheet = false }
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
                    Img(cover, provider, Modifier.width(140.dp).aspectRatio(2f / 3f).clip(R6), maxW = 480)
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
                        if (d != null && d.chapters.isNotEmpty()) Row(Modifier.clip(R6).clickable { dlSheet = true }.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ic.Download, null, tint = Muted, modifier = Modifier.size(16.dp))
                            Text("Unduh", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 5.dp))
                        }
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
                            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(R6).background(CardBg).border(1.dp, Line, R6)
                                .clickable { open(c) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text("Chapter ${c.number}", color = if (read) Muted else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    if (c.title.isNotBlank() && !c.title.equals("Chapter ${c.number}", true))
                                        Text(c.title, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                    relTime(c.date)?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                                }
                                DownloadAction(id, title, cover, provider, c)
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

private val Glass = Color(0xE61C1D22)
private val GlassLine = Color(0x1AFFFFFF)
private val SheetBg = Color(0xFF1C1D21)
private val SheetCard = Color(0xFF26272C)
private val SheetChip = Color(0xFF34353B)
private val Blue = Color(0xFF0A84FF)
private val Green = Color(0xFF30D158)
private val speeds = listOf("Lambat" to 70, "Sedang" to 130, "Cepat" to 230)
private val pauses = listOf("Langsung" to 0, "3 dtk" to 3, "5 dtk" to 5)
private var resumePlay = false

object ReaderPrefs {
    private var prefs: SharedPreferences? = null
    var speed by mutableIntStateOf(1); private set
    var pause by mutableIntStateOf(1); private set
    var autoNext by mutableStateOf(true); private set
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE).also { prefs = it }
        speed = p.getInt("s", 1).coerceIn(0, 2); pause = p.getInt("p", 1).coerceIn(0, 2); autoNext = p.getBoolean("a", true)
    }
    @JvmName("applySpeed") fun setSpeed(i: Int) { speed = i; prefs?.edit()?.putInt("s", i)?.apply() }
    @JvmName("applyPause") fun setPause(i: Int) { pause = i; prefs?.edit()?.putInt("p", i)?.apply() }
    @JvmName("applyAutoNext") fun setAutoNext(v: Boolean) { autoNext = v; prefs?.edit()?.putBoolean("a", v)?.apply() }
}

@Composable
fun ReaderScreen(seriesId: String, chapterId: String, onBack: () -> Unit, onSeries: () -> Unit, onChapter: (String) -> Unit) {
    ReaderPrefs.init(LocalContext.current.applicationContext)
    var tick by remember { mutableIntStateOf(0) }
    // Chapter yang sudah diunduh dibaca dari file lokal (tanpa internet); selain itu dari server seperti biasa.
    val local = remember(chapterId) { Downloads.content(chapterId) }
    val res by produceState<Result<Content>?>((local ?: Net.cachedChapter(chapterId))?.let { Result.success(it) }, chapterId, tick) {
        if (local != null) return@produceState
        if (tick > 0) value = null
        val r = runCatching { Net.chapter(chapterId) }
        r.getOrNull()?.let { Net.storeChapter(chapterId, it) }
        if (r.isSuccess) { if (value?.getOrNull() != r.getOrNull()) value = r } else if (value == null) value = r
    }
    val ctx = LocalContext.current
    var fresh by remember(chapterId) { mutableStateOf<Content?>(null) }
    var refetching by remember(chapterId) { mutableStateOf(false) }
    var reloadNonce by remember(chapterId) { mutableIntStateOf(0) }
    var left by remember(chapterId) { mutableIntStateOf(Net.refetchLeft("c:$chapterId")) }
    val c = fresh ?: res?.getOrNull()
    var bars by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(resumePlay.also { resumePlay = false }) }
    var showList by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(CommentFocus.pending("c:$chapterId")) }
    var leaving by remember(chapterId) { mutableStateOf(false) }
    var sawNotEnd by remember(chapterId) { mutableStateOf(false) }
    var dismissed by remember(chapterId) { mutableStateOf(false) }
    var countdown by remember(chapterId) { mutableStateOf<Int?>(null) }
    val ring = remember(chapterId) { Animatable(1f) }
    var lastSec by remember(chapterId) { mutableIntStateOf(0) }
    countdown?.let { if (it != lastSec) lastSec = it }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Geser jari ke atas (baca lanjut) -> bar tersembunyi; geser ke bawah (balik) -> bar muncul lagi.
    val barsByScroll = remember {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (acc * available.y < 0f) acc = 0f
                acc += available.y
                if (acc < -48f) { bars = false; acc = 0f } else if (acc > 48f) { bars = true; acc = 0f }
                return Offset.Zero
            }
        }
    }
    var confirm by remember(chapterId) { mutableStateOf(false) }
    var autoTried by remember(chapterId) { mutableStateOf(false) }
    val doRefetch: () -> Unit = {
        refetching = true
        scope.launch {
            val r = try { runCatching { Net.refetchChapter(chapterId) } } finally { refetching = false }
            left = Net.refetchLeft("c:$chapterId")
            r.getOrNull()?.let { fresh = it; reloadNonce++; toast(ctx, "Diperbarui: ${it.images.size} halaman (sisa $left hari ini)") } ?: toast(ctx, "Gagal mengambil ulang")
        }
    }
    val refetch = {
        if (!refetching) {
            if (left <= 0) toast(ctx, "Batas ambil ulang hari ini habis (${Net.REFETCH_MAX}x per hari)") else confirm = true
        }
    }
    if (confirm) RefetchConfirm("chapter", left, { confirm = false; doRefetch() }, { confirm = false })
    val density = LocalDensity.current
    val onChapterNow by rememberUpdatedState(onChapter)
    val imgHeaders = remember(c?.imageHeaders, seriesId, Net.hdr) { Net.hdr[seriesId.substringBefore(':')].orEmpty() + c?.imageHeaders.orEmpty() }
    val nextNow by rememberUpdatedState(c?.next)
    val advance = { id: String, keep: Boolean -> if (!leaving) { leaving = true; resumePlay = keep; onChapterNow(id) } }
    val view = LocalView.current
    DisposableEffect(view) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        Immersive.readers++
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            Immersive.readers--
            view.post { if (Immersive.readers <= 0) ctl?.show(WindowInsetsCompat.Type.systemBars()) }
        }
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
        if (cc.images.firstOrNull()?.startsWith("file:") == true) return@LaunchedEffect // gambar lokal tidak perlu diunduh dulu
        val to = minOf(10, cc.images.size)
        if (to > 0) { Net.preloadImages(cc.images.subList(0, to), imgHeaders); preloaded.intValue = to }
    }
    LaunchedEffect(c, firstVisible) {
        val cc = c ?: return@LaunchedEffect
        if (cc.images.firstOrNull()?.startsWith("file:") == true) return@LaunchedEffect
        val from = maxOf(preloaded.intValue, firstVisible + 2)
        val to = minOf(firstVisible + 10, cc.images.size)
        if (from < to) { Net.preloadImages(cc.images.subList(from, to), imgHeaders); preloaded.intValue = to }
    }
    LaunchedEffect(playing, ReaderPrefs.speed, c != null) {
        if (!playing || c == null) return@LaunchedEffect
        val pxPerSec = with(density) { speeds[ReaderPrefs.speed].second.dp.toPx() }
        var last = withFrameNanos { it }
        var stuck = 0
        while (true) {
            val now = withFrameNanos { it }
            val consumed = listState.scrollBy(pxPerSec * (now - last) / 1_000_000_000f)
            last = now
            if (consumed == 0f) {
                if (++stuck > 10) {
                    val n = nextNow
                    if (ReaderPrefs.autoNext && n != null) advance(n, true) else playing = false
                    break
                }
            } else stuck = 0
        }
    }
    val detail by produceState<Detail?>(Net.detailCache[seriesId], seriesId, c != null) {
        if (value == null && c != null) value = Net.loadDetailDisk(seriesId) ?: runCatching { Net.detail(seriesId) }.getOrNull()?.also { Net.storeDetail(seriesId, it) }
    }
    val seriesTitle = (detail?.title ?: Library.entries[seriesId]?.title).orEmpty()
    val chapters = detail?.chapters.orEmpty()
    val prevNum = chapters.firstOrNull { it.id == c?.prev }?.number
    val nextNum = chapters.firstOrNull { it.id == c?.next }?.number
    val total = c?.images?.size ?: 0
    val page = (listState.firstVisibleItemIndex + 1).coerceIn(1, maxOf(total, 1))
    val atEnd = total > 0 && !listState.canScrollForward
    LaunchedEffect(atEnd) { if (atEnd) bars = true }
    LaunchedEffect(atEnd, total) { if (total > 0 && !atEnd) { sawNotEnd = true; dismissed = false } }
    LaunchedEffect(atEnd, sawNotEnd, dismissed, playing, leaving, c?.next, ReaderPrefs.autoNext, ReaderPrefs.pause) {
        val n = c?.next
        if (!atEnd || !sawNotEnd || dismissed || playing || leaving || n == null || !ReaderPrefs.autoNext) { countdown = null; return@LaunchedEffect }
        val sec = pauses[ReaderPrefs.pause].second
        if (sec == 0) { advance(n, false); return@LaunchedEffect }
        ring.snapTo(1f)
        launch { ring.animateTo(0f, tween(sec * 1000, easing = LinearEasing)) }
        for (s in sec downTo 1) { countdown = s; delay(1000) }
        countdown = null
        advance(n, false)
    }

    Box(Modifier.fillMaxSize()) {
        when {
            res == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { MascotLoading(size = 150.dp) }
            c == null -> Failed { tick++ }
            c.images.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Chapter belum tersedia", color = Muted) }
            else -> LazyColumn(Modifier.fillMaxSize().nestedScroll(barsByScroll).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { bars = !bars }, listState) {
                items(c.images, key = { it }) { url ->
                    ReaderPage(url, imgHeaders, reloadNonce) {
                        if (url == c.images.first() && !autoTried && left > 0 && local == null && !refetching) { autoTried = true; doRefetch() }
                    }
                }
            }
        }
        AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), fadeIn(tween(120)), fadeOut(tween(120))) {
            ReaderTopBar(seriesTitle, c?.number?.let { "Chapter $it" } ?: "Baca", onBack, { showList = true }, refetching, left, refetch)
        }
        if (c != null && total > 0) AnimatedVisibility(bars && countdown == null, Modifier.align(Alignment.BottomCenter), fadeIn(tween(120)), fadeOut(tween(120))) {
            BottomBar(page, total, { i -> scope.launch { listState.scrollToItem(i) } }, playing, prevNum?.let { "Ch. $it" } ?: "Sebelum", nextNum?.let { "Ch. $it" } ?: "Lanjut", c.prev != null, c.next != null,
                onPrev = { c.prev?.let { advance(it, false) } }, onNext = { c.next?.let { advance(it, false) } },
                onPlay = { playing = !playing; if (playing) bars = false }, onSettings = { showSettings = true }, onComments = { showComments = true })
        }
        AnimatedVisibility(countdown != null, Modifier.align(Alignment.BottomCenter), slideInVertically(tween(220)) { it } + fadeIn(tween(220)), slideOutVertically(tween(160)) { it } + fadeOut(tween(160))) {
            NextCard(c?.number.orEmpty(), nextNum, lastSec, ring.value, onCancel = { dismissed = true }, onNow = { c?.next?.let { advance(it, false) } })
        }
    }

    if (showComments) {
        val lib = Library.entries[seriesId]
        val origin = detail?.let { CommentOrigin(seriesId, it.title, it.cover, it.provider, chapterId, c?.number) }
            ?: lib?.let { CommentOrigin(seriesId, it.title, it.cover, it.provider, chapterId, c?.number) }
        CommentsSheet("c:$chapterId", c?.number?.let { "Chapter $it" }.orEmpty(), origin) { showComments = false }
    }
    if (showList) ChapterSheet(detail, chapterId, onDismiss = { showList = false }) { id -> showList = false; if (id != chapterId) onChapter(id) }
    if (showSettings) SettingsSheet { showSettings = false }
}

@Composable
private fun ReaderPage(url: String, headers: Map<String, String>, nonce: Int, onDead: () -> Unit) {
    var dead by remember(url, nonce) { mutableStateOf(false) }
    var round by remember(url, nonce) { mutableIntStateOf(0) }
    val dead0 by rememberUpdatedState(onDead)
    key(nonce, round) {
        Box(Modifier.fillMaxWidth()) {
            Img(url, "", Modifier.fillMaxWidth().heightIn(min = 240.dp), ContentScale.FillWidth, headers, onFail = { dead = true; dead0() })
            if (dead) Column(Modifier.matchParentSize().clickable { dead = false; round++ }, Arrangement.Center, Alignment.CenterHorizontally) {
                Text("Gagal memuat gambar", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Ketuk untuk coba lagi", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Modifier.noTap(): Modifier = clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}

private fun Modifier.glass(shape: Shape = CircleShape) = clip(shape).background(Glass).border(1.dp, GlassLine, shape)

@Composable
private fun ReaderTopBar(series: String, chapter: String, onBack: () -> Unit, onTitle: () -> Unit, refetching: Boolean, left: Int, onRefetch: () -> Unit) =
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).glass().clickable(onClick = onBack), Alignment.Center) { Icon(Ic.Left, "Kembali", tint = Color.White, modifier = Modifier.size(26.dp)) }
        Row(Modifier.weight(1f).height(48.dp).glass().clickable(onClick = onTitle).padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                if (series.isNotBlank()) Text(series, color = Muted, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chapter, color = Color.White, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Ic.Down, "Daftar chapter", tint = Muted, modifier = Modifier.size(22.dp).padding(start = 2.dp))
        }
        Box(Modifier.size(48.dp).glass().clickable(onClick = onRefetch), Alignment.Center) { RefetchIcon(refetching, left, 22.dp) }
    }

@Composable
private fun PauseIcon(color: Color = Color.White) = Canvas(Modifier.size(22.dp)) {
    val w = size.width; val h = size.height; val r = CornerRadius(1.5.dp.toPx())
    drawRoundRect(color, Offset(w * 0.18f, h * 0.12f), Size(w * 0.24f, h * 0.76f), r)
    drawRoundRect(color, Offset(w * 0.58f, h * 0.12f), Size(w * 0.24f, h * 0.76f), r)
}

@Composable
private fun SlidersIcon(tint: Color, dp: Dp) = Canvas(Modifier.size(dp)) {
    val w = size.width; val h = size.height; val sw = 2.dp.toPx(); val r = w * 0.11f
    listOf(0.32f to 0.64f, 0.68f to 0.36f).forEach { (y, x) ->
        val cy = h * y
        drawLine(tint, Offset(w * 0.08f, cy), Offset(w * x - r * 1.7f, cy), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * x + r * 1.7f, cy), Offset(w * 0.92f, cy), sw, StrokeCap.Round)
        drawCircle(tint, r, Offset(w * x, cy), style = Stroke(sw))
    }
}

@Composable
private fun LineIcon(tint: Color, dp: Dp, vararg paths: FloatArray) = Canvas(Modifier.size(dp)) {
    val st = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    paths.forEach { p -> drawPath(Path().apply { moveTo(p[0] * size.width, p[1] * size.height); for (i in 2 until p.size step 2) lineTo(p[i] * size.width, p[i + 1] * size.height) }, tint, style = st) }
}

@Composable
private fun BarButton(label: String, enabled: Boolean = true, onClick: () -> Unit, icon: @Composable () -> Unit) =
    Column(Modifier.widthIn(min = 56.dp).alpha(if (enabled) 1f else 0.35f).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp), Alignment.Center) { icon() }
        Text(label, color = Muted, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

@Composable
private fun BottomBar(
    page: Int, total: Int, onSeek: (Int) -> Unit, playing: Boolean, prevLabel: String, nextLabel: String, canPrev: Boolean, canNext: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onPlay: () -> Unit, onSettings: () -> Unit, onComments: () -> Unit
) {
    val shape = RoundedCornerShape(30.dp)
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).glass(shape).noTap().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)) {
        // Baris 1: posisi halaman + slider (lebih tinggi supaya mudah digeser)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append("$page") }
                withStyle(SpanStyle(color = Muted)) { append(" / $total") }
            }, fontSize = 13.sp, modifier = Modifier.widthIn(min = 56.dp))
            PageSlider(page, total, onSeek, Modifier.weight(1f).padding(horizontal = 10.dp))
            Text("${if (total > 0) page * 100 / total else 0}%", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 40.dp), textAlign = TextAlign.End)
        }
        // Baris 2: 5 tombol sama jarak. Kiri-kanan = pindah chapter, tengah = auto-scroll.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            BarButton(prevLabel, canPrev, onPrev) { Icon(Ic.Left, "Chapter sebelumnya", tint = Color.White, modifier = Modifier.size(26.dp)) }
            BarButton("Atur", true, onSettings) { SlidersIcon(Color.White, 26.dp) }
            Box(Modifier.padding(horizontal = 2.dp).size(64.dp).clip(CircleShape).background(Color.White).clickable(onClick = onPlay), Alignment.Center) {
                if (playing) PauseIcon(Color.Black) else Icon(Ic.Play, "Auto-scroll", tint = Color.Black, modifier = Modifier.size(32.dp))
            }
            BarButton("Komentar", true, onComments) { CommentIcon(Color.White, 26.dp) }
            BarButton(nextLabel, canNext, onNext) { Icon(Ic.Right, "Chapter berikutnya", tint = Color.White, modifier = Modifier.size(26.dp)) }
        }
    }
}

@Composable
private fun NextCard(current: String, nextNum: String?, seconds: Int, progress: Float, onCancel: () -> Unit, onNow: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).glass(shape).noTap().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp), Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 4.dp.toPx(); val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
                    drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, tl, sz, style = Stroke(sw))
                    drawArc(Color.White, -90f, 360f * progress, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
                Text("$seconds", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(if (current.isNotBlank()) "Chapter $current selesai" else "Chapter selesai", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(nextNum?.let { "Lanjut ke Chapter $it" } ?: "Lanjut ke chapter berikutnya", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(0.8f).height(50.dp).clip(CircleShape).background(SheetChip).clickable(onClick = onCancel), Alignment.Center) { Text("Batal", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Box(Modifier.weight(1.2f).height(50.dp).clip(CircleShape).background(Color.White).clickable(onClick = onNow), Alignment.Center) { Text("Lanjut sekarang", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PageSlider(page: Int, total: Int, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
    val seek by rememberUpdatedState(onSeek)
    val frac = if (total > 1) (page - 1).toFloat() / (total - 1) else 0f
    fun at(x: Float, w: Int) = if (total > 1 && w > 0) ((x / w).coerceIn(0f, 1f) * (total - 1)).roundToInt() else 0
    Canvas(
        modifier.height(40.dp)
            .pointerInput(total) { detectTapGestures { o -> seek(at(o.x, size.width)) } }
            .pointerInput(total) {
                detectHorizontalDragGestures(onDragStart = { o -> seek(at(o.x, size.width)) }) { change, _ ->
                    change.consume(); seek(at(change.position.x, size.width))
                }
            }
    ) {
        val th = 4.dp.toPx(); val cy = size.height / 2; val r = 8.dp.toPx()
        val x = (size.width * frac).coerceIn(r, size.width - r)
        drawRoundRect(Color(0x33FFFFFF), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        drawRoundRect(Color.White, Offset(0f, cy - th / 2), Size(x, th), CornerRadius(th / 2))
        drawCircle(Color.White, r, Offset(x, cy))
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(12.dp)).background(SheetBg).padding(3.dp)) {
        options.forEachIndexed { i, t ->
            val on = i == selected
            Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).then(if (on) Modifier.background(Color.White) else Modifier).clickable(enabled = enabled) { onSelect(i) }, Alignment.Center) {
                Text(t, color = if (on) Color.Black else Color(0xFFD7D9DF), fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SheetCard).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)

@Composable
private fun IconTile(color: Color, icon: @Composable () -> Unit) = Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(color), Alignment.Center) { icon() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = SheetBg, contentColor = Color.White) {
        KeepBarsHidden()
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pengaturan baca", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(38.dp).clip(CircleShape).background(SheetChip).clickable(onClick = onDismiss), Alignment.Center) { Icon(Ic.Close, "Tutup", tint = Color(0xFFD7D9DF), modifier = Modifier.size(20.dp)) }
            }
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Blue) { LineIcon(Color.White, 20.dp, floatArrayOf(0.5f, 0.2f, 0.5f, 0.8f), floatArrayOf(0.28f, 0.58f, 0.5f, 0.8f, 0.72f, 0.58f)) }
                    Text("Kecepatan auto-scroll", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
                }
                Segmented(speeds.map { it.first }, ReaderPrefs.speed, onSelect = ReaderPrefs::setSpeed)
            }
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Green) { LineIcon(Color.White, 20.dp, floatArrayOf(0.26f, 0.2f, 0.5f, 0.44f, 0.74f, 0.2f), floatArrayOf(0.26f, 0.5f, 0.5f, 0.74f, 0.74f, 0.5f)) }
                    Text("Lanjut chapter otomatis", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    Switch(ReaderPrefs.autoNext, ReaderPrefs::setAutoNext, colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Green, checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF45464C), uncheckedBorderColor = Color.Transparent))
                }
                Text("Jeda sebelum lanjut", color = Color(0xFFD7D9DF), fontSize = 15.sp, modifier = Modifier.alpha(if (ReaderPrefs.autoNext) 1f else 0.4f))
                Segmented(pauses.map { it.first }, ReaderPrefs.pause, ReaderPrefs.autoNext, ReaderPrefs::setPause)
            }
            Text("Saat scroll sampai akhir, chapter berikutnya dibuka sendiri. Bisa dibatalkan sebelum hitungan habis.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(detail: Detail?, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = SheetBg, contentColor = Color.White) {
        KeepBarsHidden()
        Text("Daftar chapter", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp))
        val chapters = detail?.chapters.orEmpty()
        if (chapters.isEmpty()) Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
            if (detail == null) MascotLoading(size = 90.dp) else Text("Belum ada chapter.", color = Muted)
        } else {
            val st = rememberLazyListState(initialFirstVisibleItemIndex = chapters.indexOfFirst { it.id == current }.coerceAtLeast(0))
            LazyColumn(Modifier.heightIn(max = 480.dp), st, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                items(chapters, key = { it.id }) { ch ->
                    val on = ch.id == current
                    Text("Chapter ${ch.number}", color = if (on) OnAccent else Color.White, fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Accent else SheetCard)
                            .clickable { onPick(ch.id) }.padding(horizontal = 16.dp, vertical = 13.dp))
                }
            }
        }
    }
}
