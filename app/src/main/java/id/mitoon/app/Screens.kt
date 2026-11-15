package id.mitoon.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.graphics.Color
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
        PagedVm { p -> when (s) { "sorotan" -> Net.api.latest(p).data; "populer" -> Net.api.popular(p).data; else -> Net.api.byGenre(s, p).data } }
    }
    val tabs = listOf("sorotan" to "Sorotan", "populer" to "Populer") + hv.genres.map { it.slug to it.name }
    Column(Modifier.fillMaxSize()) {
        Header(onSearch)
        SeriesGrid(feed, onOpen, upCount = if (s == "sorotan") 9 else 0, modifier = Modifier.weight(1f)) {
            full { Pills(tabs, s) { sel = it } }
            if (s == "sorotan") {
                if (hv.spotlight.isNotEmpty()) full { Spotlight(hv.spotlight, onOpen) }
                if (hv.notices.isNotEmpty()) full { Column(Modifier.padding(top = 12.dp)) { Notices(hv.notices) } }
            }
            full { if (s == "sorotan") SectionTitle("Update Komik", onMore) else SectionTitle(tabs.firstOrNull { it.first == s }?.second ?: "") }
        }
    }
}

// ------------------------------------------------------------------ EXPLORE (jelajah + filter)
private suspend fun explore(p: Int, g: String?, sort: String, type: String?, status: String?): List<Series> = when {
    g != null -> Net.api.byGenre(g, p, sort = sort, type = type, status = status).data
    sort == "popular" -> Net.api.popular(p).data
    else -> Net.api.latest(p, type = type).data
}

@Composable
private fun OptionRow(label: String, options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) {
    Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(options, key = { it.second }) { (v, t) -> Chip(t, selected == v) { onSelect(v) } }
    }
}

@Composable
fun ExploreScreen(onOpen: (Series) -> Unit, onSearch: () -> Unit) {
    val genres by produceState(emptyList<Genre>()) { value = runCatching { Net.genres().sortedByDescending { it.count } }.getOrDefault(emptyList()) }
    var g by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf("latest") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    val (gs, ss, ts, st) = listOf(g, sort, type, status)
    val vm = keyedVm("ex-$gs-$ss-$ts-$st") { PagedVm { p -> explore(p, gs, ss!!, ts, st) } }
    val sorts = buildList<Pair<String?, String>> {
        add("latest" to "Terbaru"); add("popular" to "Populer")
        if (gs != null) { add("rating" to "Rating"); add("az" to "A-Z") }
    }
    val types = buildList<Pair<String?, String>> {
        add(null to "Semua"); add("manga" to "Manga")
        if (gs != null) { add("manhwa" to "Manhwa"); add("manhua" to "Manhua") }
        add("comic" to "Komik"); add("novel" to "Novel")
    }
    val statuses = listOf<Pair<String?, String>>(null to "Semua", "ongoing" to "Ongoing", "completed" to "Tamat", "hiatus" to "Hiatus")
    Column(Modifier.fillMaxSize()) {
        Header(onSearch)
        SeriesGrid(vm, onOpen, modifier = Modifier.weight(1f)) {
            full {
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(R6).background(CardBg).border(1.dp, Line, R6).clickable(onClick = onSearch).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = Muted); Spacer(Modifier.width(8.dp)); Text("Cari komik / novel", color = Muted, fontSize = 14.sp)
                }
            }
            full { CategoryTabs(genres, gs) { g = it; if (it == null && (sort == "rating" || sort == "az")) sort = "latest"; if (it == null) status = null } }
            full {
                Column {
                    OptionRow("Urutkan", sorts, ss) { sort = it ?: "latest" }
                    if (!(gs == null && ss == "popular")) OptionRow("Format", types, ts) { type = it }
                    if (gs != null) OptionRow("Status", statuses, st) { status = it }
                }
            }
            full { Spacer(Modifier.height(4.dp)) }
        }
    }
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
                        Img(e.cover, e.provider, Modifier.width(56.dp).aspectRatio(0.5f).clip(RoundedCornerShape(4.dp)))
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
