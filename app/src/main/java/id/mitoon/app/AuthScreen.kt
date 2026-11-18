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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Layar pembuka + login (hanya Google), tiga halaman vertikal yang digeser ke atas:
 * 1) WELCOME + mascot, 2) ringkasan fitur, 3) tombol Masuk dengan Google.
 * Tombol Google selalu tampil langsung (tidak menunggu respons server).
 */
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
        VerticalPager(pager, Modifier.fillMaxSize()) { p ->
            when (p) {
                0 -> WelcomePage()
                1 -> FeaturesPage()
                else -> LoginPage(busy, msg, keep, { keep = it }) { google() }
            }
        }
        // versi di atas, seperti layar pembuka umumnya
        Text("v${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(pages) { i ->
                val on = pager.currentPage == i
                val w by animateDpAsState(if (on) 22.dp else 6.dp, tween(220), label = "dot")
                Box(Modifier.width(w).height(6.dp).clip(CircleShape).background(if (on) Color(0xFFAAA9FF) else Color(0xFF4A4C57)))
            }
        }
        // petunjuk "Geser ke atas" (hilang di halaman login)
        if (!last) SwipeHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp)) {
            scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        }
    }
}

/** GIF mascot (background transparan) dari res/raw; decoder GIF sudah dipasang di ImageLoader aplikasi. */
@Composable
private fun MascotGif(res: Int, height: Dp, ratio: Float) {
    val ctx = LocalContext.current
    val req = remember(res) { ImageRequest.Builder(ctx).data(res).memoryCacheKey("gif_$res").build() }
    AsyncImage(req, "Mascot MiToon", Modifier.height(height).aspectRatio(ratio), contentScale = ContentScale.Fit)
}

@Composable
private fun WelcomePage() {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.size(270.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF34365B), Color(0xFF202130), Color.Transparent))))
            MascotGif(R.raw.mascot_welcome, 250.dp, 405f / 512f)
        }
        Text("SELAMAT DATANG", color = Color(0xFFAAA9FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp)
        Text("Dunia cerita,\nada di genggaman.", color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text("Temukan manga dan novel favoritmu, lalu nikmati setiap chapter dengan nyaman.", color = Muted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, start = 10.dp, end = 10.dp, bottom = 92.dp))
    }
}

@Composable
private fun FeaturesPage() {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text("PENGALAMAN MEMBACA", color = Color(0xFFAAA9FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Semua yang kamu\nbutuhkan.", color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
        FeatureCard(Icons.Default.Search, "Cari & jelajahi", "Temukan manga dan novel dari berbagai sumber.", Color(0xFF8B70FF))
        FeatureCard(Icons.Default.Favorite, "Pustaka pribadi", "Simpan favorit dan lanjutkan dari chapter terakhir.", Color(0xFF398DF5))
        FeatureCard(Icons.Default.Notifications, "Komentar & notifikasi", "Ikuti obrolan dan kabar terbaru dari komunitas.", Color(0xFFEF9A45))
        Text("Dirancang untuk membaca lebih nyaman.", color = Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp, bottom = 92.dp))
    }
}

@Composable
private fun FeatureCard(icon: ImageVector, title: String, desc: String, tint: Color) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF22232B)).border(1.dp, Color(0xFF343540), RoundedCornerShape(20.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(tint.copy(alpha = .18f)), Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp)) }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Text("›", color = Color(0xFF777987), fontSize = 24.sp)
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, desc: String) = FeatureCard(icon, title, desc, Accent)

@Composable
private fun LoginPage(busy: Boolean, msg: String?, keep: Boolean, onKeep: (Boolean) -> Unit, onGoogle: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(220.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF38385E), Color(0xFF202130), Color.Transparent))))
            MascotGif(R.raw.mascot_welcome, 190.dp, 405f / 512f)
        }
        Text("SATU LANGKAH LAGI", color = Color(0xFFAAA9FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("Mulai membaca", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 9.dp))
        Text("Masuk untuk menyimpan koleksi dan melanjutkan cerita favoritmu di MiToon.", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp, start = 8.dp, end = 8.dp))
        msg?.let { Text(it, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).border(1.dp, Color(0xFF555762), RoundedCornerShape(14.dp)).padding(12.dp)) }
        Button(onGoogle, Modifier.fillMaxWidth().height(56.dp), enabled = !busy, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F), disabledContainerColor = Color.White, disabledContentColor = Color(0xFF1F1F1F))) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFF1F1F1F), strokeWidth = 2.dp) else {
                Image(painterResource(R.drawable.ic_google), null, Modifier.size(21.dp)); Spacer(Modifier.width(10.dp)); Text("Masuk dengan Google", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.padding(top = 12.dp, bottom = 26.dp).clip(RoundedCornerShape(14.dp)).clickable { onKeep(!keep) }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(keep, { onKeep(it) }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Muted))
            Text("Ingat saya", color = Muted, fontSize = 13.sp)
        }
    }
}

/** Chevron yang naik-turun pelan + teks "Geser ke atas"; ketuk = ke halaman berikutnya. */
@Composable
private fun SwipeHint(modifier: Modifier, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "hint")
    val dy by inf.animateFloat(0f, -8f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dy")
    Column(modifier.clip(R6).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.KeyboardArrowUp, null, tint = Color(0xFF6B6F7B), modifier = Modifier.size(34.dp).offset(y = dy.dp))
        Text("Geser ke atas", color = Color(0xFF6B6F7B), fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}
