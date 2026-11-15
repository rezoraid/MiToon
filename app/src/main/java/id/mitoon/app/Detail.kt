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
private fun ActionButton(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(modifier.height(40.dp).clip(R6).then(if (filled) Modifier.background(Accent) else Modifier.border(1.5.dp, Accent, R6)).clickable(onClick = onClick),
        Alignment.Center) {
        Text(text, color = if (filled) Color.White else Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
fun DetailScreen(id: String, onBack: () -> Unit, onSearch: () -> Unit, onOpenSeries: (String) -> Unit, onChapter: (seriesId: String, chapterId: String) -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val res by produceState<Result<Detail>?>(null, id, tick) { value = null; value = runCatching { Net.api.detail(id).data } }
    val rec by produceState<List<Series>>(emptyList(), id) {
        value = runCatching { Net.api.popular(limit = 7).data.filter { it.id != id }.take(6) }.getOrDefault(emptyList())
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var shown by rememberSaveable { mutableIntStateOf(6) }
    val d = res?.getOrNull()
    val entry = Library.entries[id]
    Column(Modifier.fillMaxSize()) {
        Header(onSearch, onBack)
        when {
            res == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
            d == null -> Failed { tick++ }
            else -> {
                val chapters = remember(d, newestFirst) { if (newestFirst) d.chapters else d.chapters.asReversed() }
                val resume = d.chapters.firstOrNull { it.id == entry?.lastChapterId }
                val first = d.chapters.lastOrNull()
                val last = d.chapters.firstOrNull()
                val saved = entry?.bookmarked == true
                fun open(c: Chap) { Library.open(d, c); onChapter(d.id, c.id) }
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)) {
                    item { Img(d.cover, d.provider, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))) }
                    item { Column {
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val start = resume ?: first
                            ActionButton(if (resume != null) "Lanjutkan Ch. ${resume.number}" else "Baca awal", true, Modifier.weight(1f)) { start?.let(::open) }
                            ActionButton("Baca akhir", true, Modifier.weight(1f)) { last?.let(::open) }
                        }
                        ActionButton(if (saved) "Tersimpan di pustaka" else "Tambah pustaka", false, Modifier.fillMaxWidth().padding(top = 10.dp)) { Library.toggleBookmark(d) }
                    } }
                    item { Column {
                        Text(d.title, color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
                        if (d.genres.isNotEmpty()) Text(d.genres.joinToString("  ·  "), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                        d.rating?.takeIf { it > 0 }?.let { r ->
                            val five = if (r > 5) r / 2 else r
                            Info("Rating") {
                                Text(buildAnnotatedString {
                                    for (i in 1..5) withStyle(SpanStyle(color = if (i <= Math.round(five)) Star else Color(0xFF55585F))) { append("★ ") }
                                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) { append(" %.1f".format(r)) }
                                }, fontSize = 15.sp)
                            }
                        }
                        d.type?.let { Info("Type") { Text(it.replaceFirstChar { c -> c.uppercase() }, color = Color.White, fontSize = 14.sp) } }
                        d.status?.let { st ->
                            Info("Status") { Text(when (st) { "ongoing" -> "OnGoing"; "completed" -> "Tamat"; "hiatus" -> "Hiatus"; else -> st }, color = Color.White, fontSize = 14.sp) }
                        }
                        if (d.author.isNotBlank()) Info("Penulis") { Text(d.author, color = Color.White, fontSize = 14.sp) }
                        DetailTabs(tab) { tab = it }
                    } }
                    if (tab == 0) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Chapters: ${d.chapters.size}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(if (newestFirst) "Terlama ⇅" else "Terbaru ⇅", color = Muted, fontSize = 14.sp,
                                    modifier = Modifier.clickable { newestFirst = !newestFirst }.padding(8.dp))
                            }
                        }
                        if (chapters.isEmpty()) item { Text("Belum ada chapter.", color = Muted, modifier = Modifier.padding(vertical = 16.dp)) }
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
                        if (shown < chapters.size) item {
                            Box(Modifier.fillMaxWidth().height(46.dp).border(1.dp, Line, R6).clip(R6).clickable { shown += 20 }, Alignment.Center) {
                                Text("Lainnya  ⌄", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    } else item {
                        Text(d.synopsis.ifBlank { "Belum ada sinopsis." }, color = Color(0xFFD0D3DA), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 16.dp))
                    }
                    if (rec.isNotEmpty()) item { Column {
                        SectionTitle("Rekomendasi")
                        rec.chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                row.forEach { s -> Box(Modifier.weight(1f)) { SeriesCard(s, false) { onOpenSeries(s.id) } } }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } }
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
        val colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White, disabledContainerColor = Line, disabledContentColor = Muted)
        Button({ c.prev?.let(onChapter) }, Modifier.weight(1f).height(40.dp), enabled = c.prev != null, colors = colors, shape = R6) { Text("Sebelumnya", fontWeight = FontWeight.Bold) }
        Button({ c.next?.let(onChapter) }, Modifier.weight(1f).height(40.dp), enabled = c.next != null, colors = colors, shape = R6) { Text("Berikutnya", fontWeight = FontWeight.Bold) }
    }
    
