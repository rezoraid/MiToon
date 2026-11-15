package id.mitoon.app

import androidx.compose.foundation.*
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
private fun ChapterSkeleton() = Column {
    repeat(6) { Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(66.dp).clip(R6).background(CardBg)) }
}

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
        val r = runCatching { Net.api.detail(id).data }
        r.getOrNull()?.let { Net.detailCache[id] = it }
        if (r.isSuccess || value == null) value = r
    }
    // Info dasar dari kartu yang diklik / pustaka -> header detail muncul seketika, tanpa menunggu server.
    val pv = remember(id) { Net.previews[id] ?: Library.entries[id]?.toSeries() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var shown by rememberSaveable { mutableIntStateOf(6) }
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
                    d == null -> item { ChapterSkeleton() }
                    chapters.isEmpty() -> item { Text("Belum ada chapter.", color = Muted, modifier = Modifier.padding(vertical = 16.dp)) }
                    else -> {
                        items(chapters.take(shown), key = { it.id }) { c ->
                            val read = entry?.read?.contains(c.id) == true
                            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(R6).background(CardBg).border(1.dp, Line, R6)
                                .clickable { open(c) }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Text("Chapter ${c.number}", color = if (read) Muted else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                if (c.title.isNotBlank() && !c.title.equals("Chapter ${c.number}", true))
                                    Text(c.title, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                relTime(c.date)?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                            }
                        }
                        // Sekali tekan = tampilkan SEMUA chapter (daftar tetap lazy, jadi ringan).
                        if (shown < chapters.size) item {
                            Box(Modifier.fillMaxWidth().height(46.dp).border(1.dp, Line, R6).clip(R6).clickable { shown = Int.MAX_VALUE }, Alignment.Center) {
                                Text("Lainnya (${chapters.size - shown})  ⌄", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            } else item {
                when {
                    d != null -> Text(d.synopsis.ifBlank { "Belum ada sinopsis." }, color = Color(0xFFD0D3DA), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 16.dp))
                    failed -> RetryBox("Gagal memuat detail") { tick++ }
                    else -> Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(4) { Box(Modifier.fillMaxWidth(if (it == 3) 0.6f else 1f).height(14.dp).clip(R6).background(CardBg)) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ BACA CHAPTER (scroll vertikal, tap = tampil/sembunyi bar)
@Composable
fun ReaderScreen(seriesId: String, chapterId: String, onBack: () -> Unit, onChapter: (String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val res by produceState<Result<Content>?>(null, chapterId, tick) { value = null; value = runCatching { Net.api.chapter(chapterId).data } }
    val c = res?.getOrNull()
    var bars by remember { mutableStateOf(true) }
    LaunchedEffect(c) { if (c != null) Library.advance(seriesId, chapterId, c.number) }
    Box(Modifier.fillMaxSize()) {
        when {
            res == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            c == null -> Failed { tick++ }
            c.images.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Chapter belum tersedia", color = Muted) }
            else -> LazyColumn(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { bars = !bars }) {
                items(c.images, key = { it }) { url -> Img(url, "", Modifier.fillMaxWidth().heightIn(min = 240.dp), ContentScale.FillWidth, c.imageHeaders) }
                item { NavButtons(c, onChapter) }
            }
        }
        if (bars) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(CardBg)) { TopBar(c?.number?.let { "Chapter $it" } ?: "Baca", onBack) }
            if (c != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(CardBg)) { NavButtons(c, onChapter) }
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
