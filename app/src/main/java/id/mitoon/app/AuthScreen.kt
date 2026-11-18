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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.offset
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
        // versi kecil di pojok atas
        Text(
            "v${BuildConfig.VERSION_NAME}",
            color = Color(0xFF5A5D68),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 18.dp)
        )
        // indikator halaman di kanan: kapsul iOS (aktif memanjang)
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(pages) { i ->
                val on = pager.currentPage == i
                val h by animateDpAsState(if (on) 20.dp else 6.dp, tween(240), label = "dot")
                Box(
                    Modifier
                        .width(5.dp)
                        .height(h)
                        .clip(CircleShape)
                        .background(if (on) Color.White else Color(0xFF3A3C45))
                )
            }
        }
        if (!last) SwipeHint(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
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
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MascotGif(R.raw.mascot_welcome, 210.dp, 405f / 512f)
        Spacer(Modifier.height(20.dp))
        Text(
            "Selamat datang",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            maxLines = 1
        )
        Text(
            "Platform baca komik & novel\nuntuk komunitas Indonesia.",
            color = Muted,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun FeaturesPage() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Semua dalam\nsatu aplikasi",
            color = Color.White,
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp
        )
        Spacer(Modifier.height(28.dp))
        Feature(Icons.Default.Search, Color(0xFF0A84FF), "Cari & jelajahi", "Temukan komik dari berbagai sumber, lengkap dengan genre.")
        Feature(Icons.Default.Favorite, Color(0xFFFF453A), "Pustaka pribadi", "Simpan favoritmu dan lanjutkan baca dari chapter terakhir.")
        Feature(Icons.Default.Notifications, Color(0xFF30D158), "Komentar & notifikasi", "Ngobrol di tiap komik dan terima kabar terbaru.")
    }
}

@Composable
private fun Feature(icon: ImageVector, tint: Color, title: String, desc: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF22242B))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint.copy(alpha = 0.18f)),
            Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                desc,
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun LoginPage(busy: Boolean, msg: String?, keep: Boolean, onKeep: (Boolean) -> Unit, onGoogle: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MascotGif(R.raw.mascot_welcome, 140.dp, 405f / 512f)
        Spacer(Modifier.height(16.dp))
        Text(
            "Mulai membaca",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp
        )
        Text(
            "Masuk dengan akun Google untuk mulai\nmembaca komik favoritmu.",
            color = Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(28.dp))

        msg?.let {
            Text(
                it,
                color = Color(0xFFFF453A),
                fontSize = 13.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22FF453A))
                    .padding(12.dp)
            )
        }

        Button(
            onClick = onGoogle,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .height(52.dp),
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF1F1F1F),
                disabledContainerColor = Color(0xFFE8E8E8),
                disabledContentColor = Color(0xFF1F1F1F)
            ),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFF1F1F1F), strokeWidth = 2.dp)
            } else {
                Image(painterResource(R.drawable.ic_google), null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Masuk dengan Google", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(
            Modifier
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onKeep(!keep) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = keep,
                onCheckedChange = onKeep,
                colors = CheckboxDefaults.colors(
                    checkedColor = Color.White,
                    checkmarkColor = Color.Black,
                    uncheckedColor = Muted
                )
            )
            Text("Ingat saya di perangkat ini", color = Color.White, fontSize = 14.sp)
        }
    }
}

/** Chevron naik-turun + teks; ketuk = halaman berikutnya. */
@Composable
private fun SwipeHint(modifier: Modifier, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "hint")
    val dy by inf.animateFloat(
        0f, -6f,
        infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dy"
    )
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.KeyboardArrowUp,
            null,
            tint = Color(0xFF8A8E99),
            modifier = Modifier.size(26.dp).offset(y = dy.dp)
        )
        Text("Geser ke atas", color = Color(0xFF8A8E99), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
