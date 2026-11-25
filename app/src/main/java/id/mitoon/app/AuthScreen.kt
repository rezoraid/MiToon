package id.mitoon.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.sin
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

internal suspend fun googleSignIn(ctx: Context, clientId: String?, keep: Boolean) {
    val id = clientId ?: Net.api.authConfig().data.google ?: throw IllegalStateException("Masuk dengan Google belum diaktifkan di server")
    val act = ctx.findActivity() ?: throw IllegalStateException("Activity tidak ditemukan")
    Auth.save(Net.api.googleLogin(GoogleReq(googleIdToken(act, id))).data, keep)
}

internal fun Throwable.googleError(): String? = when (this) {
    is GetCredentialCancellationException -> null
    is GetCredentialException -> "Masuk dengan Google gagal. Pastikan ada akun Google di perangkat. (${message ?: type})"
    else -> friendly()
}

private val Ink = Color(0xFF0C0D11)
private val Violet = Color(0xFF7C5CFF)
private val Cyan = Color(0xFF3CD6FF)
private val Rose = Color(0xFFFF4D8D)

/** Interpolasi tiga titik (halaman 0, 1, 2) dari posisi pager yang kontinu. */
private fun seg(t: Float, a: Float, b: Float, c: Float) = if (t <= 1f) lerp(a, b, t) else lerp(b, c, (t - 1f).coerceAtMost(1f))

/** Muncul bertahap: item ke-[i] dari [n] baru terlihat penuh setelah item sebelumnya. [v] = keterlihatan halaman 0..1 (ikut gestur geser). */
private fun Modifier.reveal(v: () -> Float, i: Int, n: Int = 4): Modifier = graphicsLayer {
    val a = ((v() - 0.1f * i) / (1f - 0.1f * (n - 1))).coerceIn(0f, 1f)
    alpha = a
    translationY = (1f - a) * 36.dp.toPx()
}

@Composable
fun AuthScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keep by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    // Client ID diambil di latar belakang agar ketukan pertama terasa instan; bila belum siap, diambil saat tombol ditekan.
    val config by produceState<AuthConfig?>(null) { value = runCatching { Net.api.authConfig().data }.getOrNull() }

    fun google() {
        if (busy) return
        msg = null; busy = true
        scope.launch {
            try { googleSignIn(ctx, config?.google, keep) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { msg = e.googleError() } finally { busy = false }
        }
    }

    val pages = 3
    val pager = rememberPagerState(pageCount = { pages })
    // Posisi kontinu (mis. 0.4 = 40% menuju halaman berikutnya). Hanya dibaca di fase draw/layer, jadi geseran tidak memicu recompose.
    val pos: () -> Float = { (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (pages - 1).toFloat()) }
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    fun go(page: Int, ms: Int = 760) { scope.launch { pager.animateScrollToPage(page, animationSpec = tween(ms, easing = FastOutSlowInEasing)) } }
    val onLast by remember { derivedStateOf { pager.currentPage == pages - 1 } }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Aurora(pos)
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            // Maskot satu-satunya yang berpindah antar tahap: besar di tahap 1, mengecil & naik ke atas di tahap 2-3.
            val base = minOf(230.dp, maxHeight * 0.30f)
            val topY = listOf(72.dp, 58.dp, 58.dp)
            val hs = listOf(base, minOf(104.dp, base), minOf(118.dp, base))
            val inset = { p: Int -> topY[p] + hs[p] + 16.dp } // ruang di atas isi tiap halaman

            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { p ->
                val vis: () -> Float = { minOf(1f - abs(p - pos()).coerceIn(0f, 1f), intro.value) }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    // parallax: isi bergeser lebih lambat dari pager (75% kecepatan) lalu memudar saat keluar
                    val d = p - (pager.currentPage + pager.currentPageOffsetFraction)
                    translationX = -d * size.width * 0.25f
                }) {
                    when (p) {
                        0 -> StageHello(inset(0), vis)
                        1 -> StageFeatures(inset(1), vis)
                        else -> StageLogin(inset(2), vis, busy, msg, keep, { keep = it }, { Auth.startVisitor() }) { google() }
                    }
                }
            }

            Box(Modifier.align(Alignment.TopCenter).graphicsLayer {
                val t = pos()
                val s = seg(t, 1f, hs[1] / base, hs[2] / base)
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
                translationY = seg(t, topY[0].toPx(), topY[1].toPx(), topY[2].toPx()) + (1f - intro.value) * 24.dp.toPx()
                alpha = intro.value
            }) { MascotGif(R.raw.mascot_welcome, base, 405f / 512f) }

            // progres tahap (gaya story) + Lewati
            val showSkip by remember { derivedStateOf { pager.currentPage < pages - 1 } }
            val skipA by animateFloatAsState(if (showSkip) 1f else 0f, tween(240), label = "skip")
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(pages) { i ->
                        Canvas(Modifier.weight(1f).height(4.dp)) {
                            val r = CornerRadius(size.height / 2)
                            drawRoundRect(Color(0x26FFFFFF), size = size, cornerRadius = r)
                            val fill = (pos() - i + 1f).coerceIn(0f, 1f)
                            if (fill > 0f) drawRoundRect(Color.White, size = Size(size.width * fill, size.height), cornerRadius = r)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("Lewati", color = Color(0xFFB9BCC6), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.graphicsLayer { alpha = skipA }.clip(CircleShape).clickable(enabled = showSkip) { go(pages - 1, 900) }.padding(horizontal = 12.dp, vertical = 8.dp))
            }

            // tombol Lanjut: memudar saat menuju halaman login (tombol login ada di halamannya sendiri)
            Button(
                { go(pager.currentPage + 1) }, Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 24.dp).widthIn(max = 420.dp).fillMaxWidth().height(56.dp)
                    .graphicsLayer { alpha = (1f - (pos() - 1f)).coerceIn(0f, 1f) * intro.value; translationY = (1f - intro.value) * 30.dp.toPx() },
                enabled = !onLast, shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black, disabledContainerColor = Color.White, disabledContentColor = Color.Black)
            ) {
                Text("Lanjut", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Ic.Right, null, Modifier.size(20.dp))
            }
        }
    }
}

/** Latar: tiga bola cahaya (ungu, cyan, merah muda) yang melayang pelan dan bergeser mengikuti tahap. */
@Composable
private fun Aurora(pos: () -> Float) {
    val drift = rememberInfiniteTransition(label = "aurora").animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart), label = "d")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val t = pos() / 2f
        val d = drift.value * 2f * PI.toFloat()
        fun blob(c: Color, cx: Float, cy: Float, r: Float, a: Float) =
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        blob(Violet, w * lerp(0.20f, 0.88f, t) + sin(d) * w * 0.05f, h * (0.20f + 0.03f * sin(d + 1f)) + h * 0.10f * t, w * 1.05f, 0.34f)
        blob(Cyan, w * lerp(0.95f, 0.10f, t) + sin(d + 2f) * w * 0.05f, h * lerp(0.58f, 0.30f, t) + sin(d) * h * 0.02f, w * 0.95f, 0.20f)
        blob(Rose, w * lerp(0.35f, 0.65f, t) + sin(d + 4f) * w * 0.06f, h * lerp(1.02f, 0.92f, t), w * 0.9f, 0.14f)
    }
}

@Composable
private fun MascotGif(res: Int, height: Dp, ratio: Float) {
    val ctx = LocalContext.current
    val req = remember(res) { ImageRequest.Builder(ctx).data(res).memoryCacheKey("gif_$res").build() }
    AsyncImage(req, "Mascot MiToon", Modifier.height(height).aspectRatio(ratio), contentScale = ContentScale.Fit)
}

// ------------------------------------------------------------------ tahap 1
@Composable
private fun StageHello(top: Dp, v: () -> Float) =
    Column(Modifier.fillMaxSize().padding(top = top, start = 28.dp, end = 28.dp, bottom = 112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SELAMAT DATANG DI", color = Color(0xFFB7A9FF), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = Modifier.reveal(v, 0))
        Text("MiToon", fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp, maxLines = 1,
            style = LocalTextStyle.current.copy(brush = Brush.linearGradient(listOf(Color.White, Color(0xFFD3C8FF), Color(0xFF9BE7FF)))),
            modifier = Modifier.reveal(v, 1))
        Text("Platform baca komik & novel untuk komunitas Indonesia.", color = Color(0xFFB9BCC6), fontSize = 17.sp, lineHeight = 25.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp).reveal(v, 2))
        Row(Modifier.padding(top = 22.dp).reveal(v, 3), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Manhwa", "Manga", "Manhua").forEach { Pill(it) }
        }
    }

@Composable
private fun Pill(text: String) =
    Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(Color(0x14FFFFFF)).border(1.dp, Color(0x26FFFFFF), CircleShape).padding(horizontal = 14.dp, vertical = 7.dp))

// ------------------------------------------------------------------ tahap 2
@Composable
private fun StageFeatures(top: Dp, v: () -> Float) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = top, start = 24.dp, end = 24.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Semua ada di sini", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).reveal(v, 0, 5))
        FeatureCard(Brush.linearGradient(listOf(Color(0xFF3B82FF), Color(0xFF3CD6FF))), "Cari & jelajahi", "Temukan komik dari berbagai sumber, lengkap dengan genre.", Modifier.reveal(v, 1, 5)) {
            Icon(Ic.Search, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        FeatureCard(Brush.linearGradient(listOf(Color(0xFFFF4D8D), Color(0xFFFF7A59))), "Pustaka pribadi", "Simpan favoritmu dan lanjutkan dari chapter terakhir.", Modifier.reveal(v, 2, 5)) {
            Icon(Ic.Heart, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        FeatureCard(Brush.linearGradient(listOf(Color(0xFF3A2410), Color(0xFF5A2E10))), "Streak & ranking", "Baca tiap hari, naikkan level, dan panjat papan peringkat.", Modifier.reveal(v, 3, 5)) {
            Flame(28.dp, true)
        }
        FeatureCard(Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFFB07CFF))), "Global Chat & komentar", "Ngobrol bareng pembaca lain, di tiap komik atau obrolan global.", Modifier.reveal(v, 4, 5)) {
            Icon(Ic.Comment, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }

@Composable
private fun FeatureCard(tile: Brush, title: String, desc: String, modifier: Modifier = Modifier, icon: @Composable () -> Unit) =
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0x12FFFFFF)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(tile), Alignment.Center) { icon() }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Color(0xFFB0B3BE), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }

// ------------------------------------------------------------------ tahap 3
@Composable
private fun StageLogin(top: Dp, v: () -> Float, busy: Boolean, msg: String?, keep: Boolean, onKeep: (Boolean) -> Unit, onGuest: () -> Unit, onGoogle: () -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = top, start = 24.dp, end = 24.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Mulai membaca", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, modifier = Modifier.reveal(v, 0, 5))
        Text("Masuk dengan akun Google untuk mulai membaca komik favoritmu.", color = Color(0xFFB9BCC6), fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp,
            modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp).reveal(v, 1, 5))
        Spacer(Modifier.height(26.dp))
        msg?.let {
            Text(it, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33FF453A)).padding(14.dp))
        }
        Button(
            onGoogle, Modifier.widthIn(max = 420.dp).fillMaxWidth().height(56.dp).reveal(v, 2, 5), enabled = !busy, shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F), disabledContainerColor = Color.White, disabledContentColor = Color(0xFF1F1F1F)),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFF1F1F1F), strokeWidth = 2.dp)
            else {
                Image(painterResource(R.drawable.ic_google), null, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Masuk dengan Google", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(onGuest, Modifier.padding(top = 12.dp).widthIn(max = 420.dp).fillMaxWidth().height(52.dp).reveal(v, 3, 5), enabled = !busy, shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))) {
            Text("Lanjut tanpa masuk", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.padding(top = 14.dp).widthIn(max = 420.dp).fillMaxWidth().reveal(v, 4, 5).clip(RoundedCornerShape(18.dp)).background(Color(0x12FFFFFF)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(18.dp))
            .clickable { onKeep(!keep) }.padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ingat saya", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Switch(keep, onKeep, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF30D158), checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF45464C), uncheckedBorderColor = Color.Transparent))
        }
        Text("v${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}", color = Color(0xFF6E717C), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 22.dp))
    }
