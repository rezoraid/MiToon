package id.mitoon.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

val Bg = Color(0xFF17181C)
val CardBg = Color(0xFF1E1F25)
val Line = Color(0xFF2C2E36)
val Accent = Color.White          // aksen = putih (tema hitam-putih)
val OnAccent = Color.Black        // teks/ikon di atas permukaan Accent
val Star = Color.White
val Muted = Color(0xFF9EA2AD)
val R6 = RoundedCornerShape(3.dp) // nama lama; semua tombol/chip/kartu memakai sudut kecil agar tampil kotak
private const val COVER_RATIO = 0.5f // rasio kartu mengikuti Siikomik (1:2)
private val Dim = Color(0x99000000)

/** Urbanist (TTF statis per weight, dibuat dari font variable yang diberikan; berfungsi di semua API >= 24). */
val Urbanist = FontFamily(
    Font(R.font.urbanist_regular, FontWeight.Normal),
    Font(R.font.urbanist_medium, FontWeight.Medium),
    Font(R.font.urbanist_semibold, FontWeight.SemiBold),
    Font(R.font.urbanist_bold, FontWeight.Bold),
    Font(R.font.urbanist_extrabold, FontWeight.ExtraBold)
)

/** Semua gaya teks Material memakai Urbanist, sehingga Text/Button/TextField/NavigationBar ikut otomatis. */
private val AppTypography = with(Typography()) {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Urbanist), displayMedium = displayMedium.copy(fontFamily = Urbanist),
        displaySmall = displaySmall.copy(fontFamily = Urbanist),
        headlineLarge = headlineLarge.copy(fontFamily = Urbanist), headlineMedium = headlineMedium.copy(fontFamily = Urbanist),
        headlineSmall = headlineSmall.copy(fontFamily = Urbanist),
        titleLarge = titleLarge.copy(fontFamily = Urbanist), titleMedium = titleMedium.copy(fontFamily = Urbanist),
        titleSmall = titleSmall.copy(fontFamily = Urbanist),
        bodyLarge = bodyLarge.copy(fontFamily = Urbanist), bodyMedium = bodyMedium.copy(fontFamily = Urbanist),
        bodySmall = bodySmall.copy(fontFamily = Urbanist),
        labelLarge = labelLarge.copy(fontFamily = Urbanist), labelMedium = labelMedium.copy(fontFamily = Urbanist),
        labelSmall = labelSmall.copy(fontFamily = Urbanist)
    )
}

@Composable
fun MiToonTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Accent, onPrimary = OnAccent, background = Bg, surface = Bg, onBackground = Color.White, onSurface = Color.White),
    typography = AppTypography,
    content = content
)

@Composable
fun Img(url: String, provider: String, modifier: Modifier, scale: ContentScale = ContentScale.Crop, extra: Map<String, String>? = null) {
    val ctx = LocalContext.current
    val h = extra ?: Net.hdr[provider].orEmpty()
    val req = remember(url, h) { ImageRequest.Builder(ctx).data(url).apply { h.forEach { (k, v) -> addHeader(k, v) } }.build() }
    AsyncImage(model = req, contentDescription = null, modifier = modifier.background(CardBg), contentScale = scale)
}

@Composable
fun SeriesCard(s: Series, up: Boolean, sub: String? = null, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(COVER_RATIO).clip(R6).clickable(onClick = onClick)) {
        Img(s.cover, s.provider, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.38f)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000)))))
        s.rating?.takeIf { it > 0 }?.let {
            Row(Modifier.align(Alignment.TopStart).padding(5.dp).background(Dim, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("★", color = Star, fontSize = 11.sp); Spacer(Modifier.width(3.dp))
                Text("%.1f".format(it), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (up) Text("UP", color = OnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).background(Accent, RoundedCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.title, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, color = Muted, fontSize = 10.sp, maxLines = 1) }
        }
    }
}

fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
fun SectionTitle(text: String, onMore: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp).then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onMore != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
    }

@Composable
fun Header(onSearch: () -> Unit, onBack: (() -> Unit)? = null) = Column {
    Row(Modifier.fillMaxWidth().background(CardBg).height(54.dp).padding(start = if (onBack == null) 14.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) }
        Image(painterResource(R.drawable.logo), "MiToon", Modifier.size(34.dp).clip(R6))
        Spacer(Modifier.width(8.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = Color.White)) { append("Mi") }
            withStyle(SpanStyle(color = Muted)) { append("Toon") }
        }, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        IconButton(onSearch) { Icon(Icons.Default.Search, "Cari", tint = Muted, modifier = Modifier.size(26.dp)) }
        IconButton({}) { Icon(Icons.Default.Person, "Profil", tint = Muted, modifier = Modifier.size(26.dp)) }
    }
    HorizontalDivider(color = Line, thickness = 1.dp)
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) =
    Text(text, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) OnAccent else Muted, maxLines = 1,
        modifier = Modifier.clip(R6).then(if (on) Modifier.background(Accent) else Modifier.border(1.dp, Color(0xFF3A3C45), R6))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))

@Composable
fun Pills(items: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) =
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(items, key = { it.first }) { (k, t) -> Chip(t, selected == k) { onSelect(k) } }
    }

@Composable
fun CategoryTabs(genres: List<Genre>, selected: String?, onSelect: (String?) -> Unit) =
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Chip("Semua", selected == null) { onSelect(null) } }
        items(genres, key = { it.slug }) { g -> Chip(g.name, selected == g.slug) { onSelect(g.slug) } }
    }

private val tones = listOf(Triple(Color(0xFF3A3C45), Color.White, Color(0xFF1E1F25)), Triple(Color(0xFF4A4D57), Color(0xFFD0D3DA), Color(0xFF17181C)))

@Composable
private fun GenreTag(name: String) {
    val (border, fg, bg) = tones[kotlin.math.abs(name.hashCode()) % tones.size]
    Text("● $name", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.background(bg, RoundedCornerShape(2.dp)).border(1.dp, border, RoundedCornerShape(2.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
fun Spotlight(items: List<Series>, onOpen: (Series) -> Unit) {
    val pager = rememberPagerState { items.size }
    LaunchedEffect(items.size) {
        while (items.size > 1) { delay(5000); pager.animateScrollToPage((pager.currentPage + 1) % items.size) }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(R6)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }) { i ->
            val s = items[i]
            Box(Modifier.fillMaxSize().clickable { onOpen(s) }) {
                Img(s.cover, s.provider, Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000)))))
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text("Hot", color = OnAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Accent, R6).padding(horizontal = 10.dp, vertical = 4.dp))
                    Text(s.title, color = Color.White, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { s.genres.take(3).forEach { GenreTag(it) } }
                }
            }
        }
        Text("${pager.currentPage + 1} | ${items.size}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Dim, RoundedCornerShape(2.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun Notices(items: List<Notice>) {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(3).forEach { n ->
            Row(Modifier.fillMaxWidth().clip(R6).background(CardBg).border(1.dp, Line, R6)
                .clickable(enabled = n.url != null) { runCatching { uri.openUri(n.url!!) } }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("📢", fontSize = 22.sp)
                Column {
                    Text(n.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.body.isNotBlank()) Text(n.body, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Grid 3 kolom (jarak 7dp seperti referensi) + infinite scroll. `header` = item full-width di atas grid. */
@Composable
fun SeriesGrid(vm: PagedVm, onOpen: (Series) -> Unit, upCount: Int = 0, modifier: Modifier = Modifier, header: LazyGridScope.() -> Unit = {}) {
    val st = rememberLazyGridState()
    val near by remember { derivedStateOf { val l = st.layoutInfo; (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 15 } }
    LaunchedEffect(near, vm.items.size) { if (near) vm.more() }
    LazyVerticalGrid(GridCells.Fixed(3), modifier, st, PaddingValues(12.dp, 0.dp, 12.dp, 16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        header()
        if (vm.items.isEmpty() && vm.loading) items(9) { Box(Modifier.aspectRatio(COVER_RATIO).background(CardBg, R6)) }
        itemsIndexed(vm.items, key = { _, s -> s.id }) { i, s -> SeriesCard(s, i < upCount) { onOpen(s) } }
        if (vm.loading && vm.items.isNotEmpty()) full {
            Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), Accent, strokeWidth = 2.dp) }
        }
        vm.error?.let { e -> full {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(e, color = Muted, textAlign = TextAlign.Center)
                TextButton(vm::retry) { Text("Coba lagi", color = Accent) }
            }
        } }
    }
}
