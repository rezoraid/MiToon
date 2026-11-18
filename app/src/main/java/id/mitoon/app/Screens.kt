package id.mitoon.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, action: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) } else Spacer(Modifier.width(16.dp))
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        action()
    }

// ------------------------------------------------------------------ HOME
@Composable
fun HomeScreen(onOpen: (Series) -> Unit, onSearch: () -> Unit, onMore: () -> Unit) {
    val hv: HomeVm = viewModel()
    var sel by rememberSaveable { mutableStateOf("sorotan") }
    val s = sel
    val feed = keyedVm("home-$s") {
        PagedVm("home-$s") { p -> when (s) { "sorotan" -> Net.latest(p); "populer" -> Net.popular(p); else -> Net.api.byGenre(s, p).data } }
    }
    val tabs = listOf("sorotan" to "Sorotan", "populer" to "Populer") + hv.genres.map { it.slug to it.name }
    Column(Modifier.fillMaxSize()) {
        Header(onSearch)
        SeriesGrid(feed, onOpen, upCount = if (s == "sorotan") 9 else 0, modifier = Modifier.weight(1f)) {
            full { Pills(tabs, s) { sel = it } }
            if (s == "sorotan") {
                if (hv.spotlight.isNotEmpty()) full { Spotlight(hv.spotlight, onOpen) }
            }
            full { if (s == "sorotan") SectionTitle("Update Komik", onMore) else SectionTitle(tabs.firstOrNull { it.first == s }?.second ?: "") }
        }
    }
}

// ------------------------------------------------------------------ EXPLORE (jelajah + satu tombol filter)
private suspend fun explore(p: Int, g: String?, sort: String, type: String?): List<Series> = when {
    g != null -> Net.api.byGenre(g, p, sort = sort, type = type).data
    sort == "popular" -> Net.popular(p)
    else -> Net.latest(p, type = type)
}

private val typeNames = mapOf("manga" to "Manga", "manhwa" to "Manhwa", "manhua" to "Manhua", "comic" to "Komik", "novel" to "Novel")

@Composable
private fun FilterIcon(tint: Color) = Canvas(Modifier.size(22.dp)) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.08f, h * 0.14f); lineTo(w * 0.92f, h * 0.14f); lineTo(w * 0.60f, h * 0.52f)
        lineTo(w * 0.60f, h * 0.88f); lineTo(w * 0.40f, h * 0.78f); lineTo(w * 0.40f, h * 0.52f); close()
    }
    drawPath(p, tint, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Hanya genre dasar yang umum dipakai (bukan puluhan genre). Genre aktif tetap tampil walau di luar daftar. */
private val basicKeys = listOf("action", "adventure", "comedy", "drama", "fantasy", "horror", "isekai", "martialarts", "mystery", "romance", "schoollife", "scifi", "sliceoflife", "supernatural")
private fun basicGenres(all: List<Genre>, active: String?): List<Genre> {
    fun key(g: Genre) = g.slug.filter { it.isLetterOrDigit() }.lowercase()
    val picked = basicKeys.mapNotNull { k -> all.firstOrNull { key(it) == k } }
    val base = if (picked.size >= 4) picked else all.take(14) // slug server beda dari dugaan -> pakai 14 genre terbanyak
    val extra = all.firstOrNull { it.slug == active && it !in base }
    return if (extra != null) base + extra else base
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Text(title, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Satu panel untuk semua filter: urutan (terbaru / sedang populer), format, dan genre. Diterapkan sekaligus lewat tombol "Terapkan". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(genres: List<Genre>, g: String?, sort: String, type: String?, onDismiss: () -> Unit, onApply: (String?, String, String?) -> Unit) {
    var dg by remember { mutableStateOf(g) }
    var ds by remember { mutableStateOf(sort) }
    var dt by remember { mutableStateOf(type) }
    val formatOn = !(dg == null && ds == "popular") // /popular tanpa genre tidak mendukung filter format
    val types = buildList<Pair<String?, String>> {
        add(null to "Semua"); add("manga" to "Manga")
        if (dg != null) { add("manhwa" to "Manhwa"); add("manhua" to "Manhua") }
        add("comic" to "Komik"); add("novel" to "Novel")
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp), containerColor = CardBg, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Filter", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton({ dg = null; ds = "latest"; dt = null }) { Text("Reset", color = Muted) }
            }
            FilterSection("Urutkan") {
                Chip("Terbaru", ds == "latest") { ds = "latest" }
                Chip("Sedang populer", ds == "popular") { ds = "popular" }
            }
            if (formatOn) FilterSection("Format") { types.forEach { (v, t) -> Chip(t, dt == v) { dt = v } } }
            FilterSection("Genre") {
                Chip("Semua", dg == null) { dg = null }
                basicGenres(genres, dg).forEach { gn -> Chip(gn.name, dg == gn.slug) { dg = gn.slug } }
            }
            Button({ onApply(dg, ds, dt.takeIf { formatOn && (dg != null || (dt != "manhwa" && dt != "manhua")) }) }, Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp).height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent), shape = R6) {
                Text("Terapkan", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ExploreScreen(onOpen: (Series) -> Unit, onSearch: () -> Unit) {
    val genres by produceState(Net.genresSnap().sortedByDescending { it.count }) {
        runCatching { Net.genres().sortedByDescending { it.count } }.onSuccess { value = it }
    }
    var g by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf("latest") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    // Genre ditekan dari halaman detail -> langsung tampilkan seri bergenre itu.
    val req = ExploreRequest.genre
    LaunchedEffect(req) { if (req != null) { g = req; sort = "latest"; type = null; ExploreRequest.genre = null } }
    val gs = g; val ss = sort; val ts = type
    val vm = keyedVm("ex-$gs-$ss-$ts") {
        PagedVm("ex-$gs-$ss-$ts") { p -> explore(p, gs, ss, ts) }
    }
    // Filter yang sedang aktif tampil sebagai chip kecil; sekali tekan = hapus filter itu.
    val active = buildList<Pair<String, () -> Unit>> {
        if (gs != null) add((genres.firstOrNull { it.slug == gs }?.name ?: gs) to { g = null })
        if (ss == "popular") add("Sedang populer" to { sort = "latest" })
        if (ts != null && !(gs == null && ss == "popular")) add((typeNames[ts] ?: ts) to { type = null })
    }
    Column(Modifier.fillMaxSize()) {
        Header(onSearch)
        SeriesGrid(vm, onOpen, modifier = Modifier.weight(1f)) {
            full {
                Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).height(46.dp).clip(R6).background(CardBg).border(1.dp, Line, R6).clickable(onClick = onSearch).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = Muted); Spacer(Modifier.width(8.dp)); Text("Cari komik / novel", color = Muted, fontSize = 14.sp)
                    }
                    val on = active.isNotEmpty()
                    Box(Modifier.size(46.dp).clip(R6).background(CardBg).border(1.dp, if (on) Accent else Line, R6).clickable { sheet = true }, Alignment.Center) {
                        FilterIcon(if (on) Accent else Muted)
                        if (on) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(7.dp).background(Accent))
                    }
                }
            }
            if (active.isNotEmpty()) full {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 12.dp)) {
                    items(active, key = { it.first }) { (label, remove) -> Chip("$label  ✕", true, remove) }
                }
            }
            full { Spacer(Modifier.height(12.dp)) }
        }
    }
    if (sheet) FilterSheet(genres, gs, ss, ts, onDismiss = { sheet = false }) { ng, ns, nt -> g = ng; sort = ns; type = nt; sheet = false }
}

// ------------------------------------------------------------------ SEARCH
@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (Series) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf("") }
    val kb = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) }
            OutlinedTextField(q, { q = it }, Modifier.weight(1f).padding(end = 12.dp), singleLine = true,
                placeholder = { Text("Cari komik / novel", color = Muted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { sent = q.trim(); kb?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = CardBg, cursorColor = Accent))
        }
        if (sent.isNotBlank()) {
            val vm = keyedVm("search-$sent") { PagedVm { p -> Net.api.search(sent, p).data } }
            SeriesGrid(vm, onOpen, modifier = Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------------ LIBRARY (bookmark + riwayat, lokal)
private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60000
    return when { m < 1 -> "Baru saja"; m < 60 -> "$m mnt lalu"; m < 1440 -> "${m / 60} jam lalu"; else -> "${m / 1440} hari lalu" }
}

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { Text(text, color = Muted, fontSize = 14.sp) }

@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onContinue: (LibEntry) -> Unit, onSearch: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val all = Library.entries.values
    Column(Modifier.fillMaxSize()) {
        Header(onSearch)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Bookmark", tab == 0) { tab = 0 }; Chip("Riwayat", tab == 1) { tab = 1 }
        }
        if (tab == 0) {
            val list = all.filter { it.bookmarked }.sortedByDescending { it.readAt }
            if (list.isEmpty()) Empty("Belum ada bookmark. Buka detail komik lalu tekan Bookmark.")
            else LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                gridItems(list, key = { it.id }) { e -> SeriesCard(e.toSeries(), false, sub = e.lastChapterNumber?.let { "Ch. $it" }) { onOpen(e.id) } }
            }
        } else {
            val list = all.filter { it.readAt > 0 }.sortedByDescending { it.readAt }
            if (list.isEmpty()) Empty("Belum ada riwayat baca.")
            else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().clip(R6).background(CardBg).border(1.dp, Line, R6).clickable { onContinue(e) }.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Img(e.cover, e.provider, Modifier.width(56.dp).aspectRatio(0.5f).clip(RoundedCornerShape(2.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Chapter ${e.lastChapterNumber.orEmpty()}", color = Accent, fontSize = 12.sp)
                            Text(ago(e.readAt), color = Muted, fontSize = 11.sp)
                        }
                        Text("Hapus", color = Muted, fontSize = 12.sp, modifier = Modifier.clickable { Library.removeHistory(e.id) }.padding(8.dp))
                    }
                }
            }
        }
    }
}
