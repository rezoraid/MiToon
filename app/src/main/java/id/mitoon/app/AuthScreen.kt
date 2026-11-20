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
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
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
            try {
                val clientId = config?.google ?: Net.api.authConfig().data.google
                    ?: throw IllegalStateException("Masuk dengan Google belum diaktifkan di server")
                val act = ctx.findActivity() ?: throw IllegalStateException("Activity tidak ditemukan")
                val idToken = googleIdToken(act, clientId)
                Auth.save(Net.api.googleLogin(GoogleReq(idToken)).data, keep)
            } catch (e: CancellationException) { throw e }
            catch (e: GetCredentialCancellationException) { /* dibatalkan pengguna */ }
            catch (e: GetCredentialException) { msg = "Masuk dengan Google gagal. Pastikan ada akun Google di perangkat. (${e.message ?: e.type})" }
            catch (e: Exception) { msg = e.friendly() } finally { busy = false }
        }
    }

    val pages = 3
    val pager = rememberPagerState(pageCount = { pages })
    val last = pager.currentPage == pages - 1

    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Box(Modifier.align(Alignment.TopCenter).offset(y = (-140).dp).size(460.dp).background(Brush.radialGradient(listOf(Color(0x22FFFFFF), Color.Transparent)), CircleShape))
        VerticalPager(pager, Modifier.fillMaxSize()) { p ->
            Box(Modifier.fillMaxSize().graphicsLayer {
                val o = abs((pager.currentPage - p) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                alpha = 1f - o; scaleX = 1f - 0.06f * o; scaleY = scaleX
            }) {
                when (p) {
                    0 -> WelcomePage()
                    1 -> FeaturesPage()
                    else -> LoginPage(busy, msg, keep, { keep = it }) { google() }
                }
            }
        }
        Text("v${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}", color = Color(0xFF8A8E9A), fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(CircleShape).background(CardBg).padding(horizontal = 12.dp, vertical = 5.dp))
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(pages) { i ->
                val on = pager.currentPage == i
                val h by animateDpAsState(if (on) 20.dp else 6.dp, tween(220), label = "dot")
                Box(Modifier.width(5.dp).height(h).clip(CircleShape).background(if (on) Color.White else Color(0x40FFFFFF)))
            }
        }
        if (!last) SwipeHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
    }
}

@Composable
private fun MascotGif(res: Int, height: Dp, ratio: Float) {
    val ctx = LocalContext.current
    val req = remember(res) { ImageRequest.Builder(ctx).data(res).memoryCacheKey("gif_$res").build() }
    AsyncImage(req, "Mascot MiToon", Modifier.height(height).aspectRatio(ratio), contentScale = ContentScale.Fit)
}

@Composable
private fun Mascot(height: Dp) = Box(Modifier.size(height + 40.dp), Alignment.Center) {
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x1FFFFFFF), Color.Transparent)), CircleShape))
    MascotGif(R.raw.mascot_welcome, height, 405f / 512f)
}

@Composable
private fun WelcomePage() =
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Mascot(210.dp)
        Spacer(Modifier.height(10.dp))
        Text("Selamat datang di", color = Muted, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text("MiToon", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, maxLines = 1)
        Text("Platform baca komik & novel untuk komunitas Indonesia.", color = Muted, fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, start = 12.dp, end = 12.dp))
    }

@Composable
private fun FeaturesPage() =
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text("Semua dalam\nsatu aplikasi", color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg)) {
            Feature(Ic.Search, Color(0xFF0A84FF), "Cari & jelajahi", "Temukan komik dari berbagai sumber, lengkap dengan genre.")
            Box(Modifier.padding(start = 74.dp).fillMaxWidth().height(0.5.dp).background(Line))
            Feature(Ic.Heart, Color(0xFFFF375F), "Pustaka pribadi", "Simpan favoritmu dan lanjutkan baca dari chapter terakhir.")
            Box(Modifier.padding(start = 74.dp).fillMaxWidth().height(0.5.dp).background(Line))
            Feature(Ic.Bell, Color(0xFFFF9F0A), "Komentar & notifikasi", "Ngobrol di tiap komik dan terima kabar terbaru.")
        }
    }

@Composable
private fun Feature(icon: ImageVector, tint: Color, title: String, desc: String) =
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(tint), Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }

@Composable
private fun LoginPage(busy: Boolean, msg: String?, keep: Boolean, onKeep: (Boolean) -> Unit, onGoogle: () -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Mascot(130.dp)
        Text("Mulai membaca", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, modifier = Modifier.padding(top = 4.dp))
        Text("Masuk dengan akun Google untuk mulai membaca komik favoritmu.", color = Muted, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp, start = 12.dp, end = 12.dp))
        Spacer(Modifier.height(28.dp))
        msg?.let {
            Text(it, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x33FF453A)).padding(14.dp))
        }
        Button(
            onGoogle, Modifier.widthIn(max = 420.dp).fillMaxWidth().height(56.dp), enabled = !busy, shape = RoundedCornerShape(16.dp),
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
        Row(Modifier.padding(top = 14.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).clickable { onKeep(!keep) }.padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ingat saya", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Switch(keep, onKeep, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF30D158), checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF45464C), uncheckedBorderColor = Color.Transparent))
        }
    }

@Composable
private fun SwipeHint(modifier: Modifier, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "hint")
    val dy by inf.animateFloat(0f, -6f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dy")
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Ic.Up, null, tint = Color(0xFF8A8E9A), modifier = Modifier.size(28.dp).offset(y = dy.dp))
        Text("Geser ke atas", color = Color(0xFF8A8E9A), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
