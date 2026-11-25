package id.mitoon.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ HOME: gaya "cover-first" (poster besar, minim teks)
private val HomeShade = Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000)))
private val GlassBg = Color(0x73101014)
private val GlassLine = Color(0x59FFFFFF)
private val PosterShape = RoundedCornerShape(16.dp)
private const val POSTER_RATIO = 0.68f
private val POSTER_W = 126.dp

private val HomeLogo = buildAnnotatedString {
    withStyle(SpanStyle(color = Color.White)) { append("Mi") }
    withStyle(SpanStyle(color = Color(0xFFD7D9DE))) { append("Toon") }
}

/** Cover dengan perbaikan otomatis (sama seperti SeriesCard): cover kosong/gagal diambil ulang dari server. */
@Composable
private fun CoverFixed(s: Series, modifier: Modifier, maxW: Int = 420, align: Alignment = Alignment.Center) {
    var bad by remember(s.id, s.cover) { mutableStateOf(false) }
    var round by remember(s.id, s.cover) { mutableIntStateOf(0) }
    val fixed by remember(s.id) { derivedStateOf { Net.coverFix[s.id] } }
    val url = fixed ?: s.cover
    LaunchedEffect(fixed) { if (fixed != null) bad = false }
    LaunchedEffect(s.id, url.isBlank(), bad, round) {
        if (!(url.isBlank() || bad)) return@LaunchedEffect
        if (round > 0) delay(2500L * round)
        val found = Net.fixCover(s.id, force = bad, bad = url.takeIf { bad && it.isNotBlank() })
        if (!found && round < 4) { round++; bad = false }
    }
    androidx.compose.runtime.key(round) {
        Img(url, s.provider, modifier, retries = 2, onFail = { bad = true; Net.dropCoverFix(s.id, url) }, maxW = maxW, align = align)
    }
}

private fun chapterLabel(raw: String?): String? {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty() || t.length > 12) return null
    return if (t.first().isDigit()) "Ch. $t" else t
}

@Composable
private fun SmallChip(text: String, modifier: Modifier = Modifier, bg: Color = Color(0x990A0A0E), fg: Color = Color.White) =
    Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
        modifier = modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp))

/** Banner raksasa dari tepi atas layar: header & genre melayang di atasnya, judul + tombol Baca / Pustaka di bawah. */
@Composable
fun HomeHero(items: List<Series>, tabs: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit,
             onOpen: (Series) -> Unit, onSearch: () -> Unit) {
    val pager = rememberPagerState { items.size.coerceAtLeast(1) }
    LaunchedEffect(items.size) {
        while (items.size > 1) { delay(5000); pager.animateScrollToPage((pager.currentPage + 1) % items.size) }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(0.74f).background(Bg)) {
        if (items.isEmpty()) Box(Modifier.fillMaxSize().background(CardBg))
        else HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { items[it].id }) { i ->
            val s = items[i]
            Box(Modifier.fillMaxSize().clickable { onOpen(s) }) { CoverFixed(s, Modifier.fillMaxSize(), maxW = 900, align = Alignment.TopCenter) }
        }
        // gradasi: atas (supaya ikon terbaca) dan bawah (menyatu dengan latar)
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Color(0xA6000000), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxSize(0.62f).background(Brush.verticalGradient(listOf(Color.Transparent, Bg.copy(alpha = 0.85f), Bg))))

        Column(Modifier.statusBarsPadding()) {
            HomeTopBar(onSearch)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                itemsIndexed(tabs, key = { _, t -> t.first }) { _, (k, name) ->
                    val on = selected == k
                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = if (on) Color.Black else Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .then(if (on) Modifier.background(Color.White) else Modifier.background(GlassBg).border(BorderStroke(1.dp, GlassLine), RoundedCornerShape(12.dp)))
                            .clickable { onSelect(k) }.padding(horizontal = 14.dp, vertical = 7.dp))
                }
            }
        }

        val s = items.getOrNull(pager.currentPage)
        if (s != null) Column(Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.genres.isNotEmpty()) Text(s.genres.take(3).joinToString(" · ").uppercase(), color = Color(0xFFD7D9DE), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.title, color = Color.White, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
            val saved = Library.entries[s.id]?.bookmarked == true
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(15.dp)).background(Color.White).clickable { onOpen(s) }.padding(horizontal = 26.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Ic.Play, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Text("Baca", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
                Row(Modifier.clip(RoundedCornerShape(15.dp)).background(Color(0x33FFFFFF))
                    .clickable { Library.toggleBookmark(s.id, s.title, s.cover, s.provider) }
                    .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (saved) Ic.HeartOn else Ic.Heart, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(if (saved) "Tersimpan" else "Pustaka", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            if (items.size > 1) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(items.size) { i ->
                    val on = i == pager.currentPage
                    Box(Modifier.height(5.dp).width(if (on) 18.dp else 5.dp).clip(RoundedCornerShape(5.dp)).background(if (on) Color.White else Color(0xFF4A4C53)))
                }
            }
        }
    }
}

/** Header tanpa kotak: logo di kiri, ikon polos di kanan (chat, cari, notifikasi, akun). */
@Composable
private fun HomeTopBar(onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo), "MiToon", Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)))
        Text(HomeLogo, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
        val openChat = LocalOpenChat.current
        IconButton(openChat) { Icon(Ic.Comment, "Public Chat", tint = Color.White, modifier = Modifier.size(25.dp)) }
        IconButton(onSearch) { Icon(Ic.Search, "Cari", tint = Color.White, modifier = Modifier.size(25.dp)) }
        val openNotif = LocalOpenNotif.current
        IconButton(openNotif) {
            val n = NotifState.unread
            if (n > 0) BadgedBox({ Badge(containerColor = Color(0xFFE5484D), contentColor = Color.White) { Text(if (n > 99) "99+" else "$n") } }) {
                Icon(Ic.Bell, "Notifikasi", tint = Color.White, modifier = Modifier.size(25.dp))
            } else Icon(Ic.Bell, "Notifikasi", tint = Color.White, modifier = Modifier.size(25.dp))
        }
        IconButton({ Auth.menuOpen = true }) {
            val u = Auth.user
            if (u != null && u.avatarV > 0) Avatar(u.id, u.username, u.avatarV, 30.dp)
            else Icon(Ic.Person, "Akun", tint = Color.White, modifier = Modifier.size(25.dp))
        }
    }
}

@Composable
fun HomeSectionTitle(text: String, onMore: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 28.dp, bottom = 12.dp)
        .then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        if (onMore != null) Icon(Ic.Right, null, tint = Muted, modifier = Modifier.size(20.dp))
    }

/** Kartu lanjut baca: lanskap lebar, judul + chapter terakhir dibaca, tombol putar untuk langsung membuka bacaan. */
@Composable
fun ContinueRow(onResume: (String, String) -> Unit) {
    val list = remember(Library.entries) {
        Library.entries.values.filter { it.lastChapterId != null && !it.cleared && it.readAt > 0 }.sortedByDescending { it.readAt }.take(10)
    }
    if (list.isEmpty()) return
    HomeSectionTitle("Lanjut Baca")
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(list, key = { _, e -> e.id }) { _, e ->
            Box(Modifier.width(250.dp).height(140.dp).clip(RoundedCornerShape(16.dp)).clickable { onResume(e.id, e.lastChapterId!!) }) {
                CoverFixed(e.toSeries(), Modifier.fillMaxSize(), maxW = 600, align = Alignment.TopCenter)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxSize(0.7f).background(HomeShade))
                Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp, end = 60.dp)) {
                    Text(e.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    chapterLabel(e.lastChapterNumber)?.let { SmallChip(it, Modifier.padding(top = 4.dp)) }
                }
                Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color.White), Alignment.Center) {
                    Icon(Ic.Play, "Lanjut baca", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Top 10: angka besar berkontur di samping poster, seperti rak peringkat streaming. */
@Composable
fun TopTenRow(vm: PagedVm, onOpen: (Series) -> Unit) {
    val top = vm.items.take(10)
    if (top.isEmpty()) return
    HomeSectionTitle("Top 10 Minggu Ini")
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(top, key = { _, s -> s.id }) { i, s ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${i + 1}", style = TextStyle(fontSize = 92.sp, lineHeight = 84.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = (-6).sp,
                    drawStyle = Stroke(width = 5f, join = StrokeJoin.Round)), modifier = Modifier.padding(end = if (i >= 9) 4.dp else 0.dp))
                Box(Modifier.offset(x = (-14).dp).width(104.dp).aspectRatio(POSTER_RATIO).clip(RoundedCornerShape(14.dp)).clickable { onOpen(s) }) {
                    CoverFixed(s, Modifier.fillMaxSize(), maxW = 360)
                }
            }
        }
    }
}

/** Rak poster besar tanpa judul (cover yang bicara); chip rating di pojok kiri atas, chapter terbaru di bawah, pita BARU opsional. */
@Composable
fun PosterRow(vm: PagedVm, onOpen: (Series) -> Unit, showRating: Boolean = false, showChapter: Boolean = false, newCount: Int = 0) {
    val st = rememberLazyListState()
    val near by remember { derivedStateOf { val l = st.layoutInfo; (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 6 } }
    LaunchedEffect(near, vm.items.size) { if (near) vm.more() }
    LazyRow(state = st, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.items.isEmpty() && vm.loading) items3()
        itemsIndexed(vm.items, key = { _, s -> s.id }) { i, s ->
            Box(Modifier.width(POSTER_W).aspectRatio(POSTER_RATIO).clip(PosterShape).clickable { onOpen(s) }) {
                CoverFixed(s, Modifier.fillMaxSize(), maxW = 420)
                if (showChapter) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxSize(0.3f).background(HomeShade))
                s.rating?.takeIf { showRating && it > 0 }?.let { r ->
                    SmallChip("★ " + "%.1f".format(r), Modifier.align(Alignment.TopStart).padding(8.dp))
                }
                if (i < newCount) Text("BARU", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.TopEnd).background(Color.White, RoundedCornerShape(bottomStart = 12.dp)).padding(horizontal = 9.dp, vertical = 4.dp))
                if (showChapter) chapterLabel(s.latestChapter)?.let { c ->
                    Text(c, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().background(Color(0xA60A0A0E), RoundedCornerShape(10.dp)).padding(vertical = 5.dp))
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items3() = items(3) {
    Box(Modifier.width(POSTER_W).aspectRatio(POSTER_RATIO).background(CardBg, PosterShape))
}
