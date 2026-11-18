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
        Text("v${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
        // indikator halaman di kanan: halaman aktif memanjang
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(pages) { i ->
                val on = pager.currentPage == i
                val h by animateDpAsState(if (on) 22.dp else 6.dp, tween(220), label = "dot")
                Box(Modifier.width(6.dp).height(h).clip(CircleShape).background(if (on) Accent else Color(0xFF4A4C57)))
            }
        }
        // petunjuk "Geser ke atas" (hilang di halaman login)
        if (!last) SwipeHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)) {
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
        MascotGif(R.raw.mascot_welcome, 230.dp, 405f / 512f)
        Spacer(Modifier.height(8.dp))
        Text("WELCOME", color = Color.White, fontSize = 58.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, maxLines = 1)
        Text("Platform baca komik & novel\nuntuk komunitas Indonesia.", color = Muted, fontSize = 20.sp, lineHeight = 28.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun FeaturesPage() {
    Column(Modifier.fillMaxSize().padding(horizontal = 36.dp), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center) {
        Text("Semua dalam\nsatu aplikasi", color = Color.White, fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        Spacer(Modifier.height(28.dp))
        Feature(Icons.Default.Search, "Cari & jelajahi", "Temukan komik dari berbagai sumber, lengkap dengan genre.")
        Feature(Icons.Default.Favorite, "Pustaka pribadi", "Simpan favoritmu dan lanjutkan baca dari chapter terakhir.")
        Feature(Icons.Default.Notifications, "Komentar & notifikasi", "Ngobrol di tiap komik dan terima kabar terbaru.")
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, desc: String) {
    Row(Modifier.padding(bottom = 22.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(44.dp).clip(R6).background(CardBg), Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun LoginPage(busy: Boolean, msg: String?, keep: Boolean, onKeep: (Boolean) -> Unit, onGoogle: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        MascotGif(R.raw.mascot_welcome, 150.dp, 405f / 512f)
        Text("Mulai membaca", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Masuk dengan akun Google untuk mulai membaca komik favoritmu.",
            color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
        )
        Spacer(Modifier.height(32.dp))

        msg?.let {
            Text(it, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).border(1.dp, Color.White, R6).padding(10.dp))
        }

        Button(
            onGoogle, Modifier.widthIn(max = 420.dp).fillMaxWidth().height(54.dp), enabled = !busy, shape = R6,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White, contentColor = Color(0xFF1F1F1F),
                disabledContainerColor = Color.White, disabledContentColor = Color(0xFF1F1F1F)
            ),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFF1F1F1F), strokeWidth = 2.dp)
            else {
                Image(painterResource(R.drawable.ic_google), null, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Masuk dengan Google", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(
            Modifier.padding(top = 10.dp).clip(R6).clickable { onKeep(!keep) }.padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(keep, { onKeep(it) }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Muted))
            Text("Ingat saya", color = Color.White, fontSize = 14.sp)
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
