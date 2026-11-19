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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
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
    val page = pager.currentPage

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MiToon", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f))
            Text("v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}", color = Color(0xFF6B6F7B), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 0) { p ->
            val off = { (pager.currentPage - p) + pager.currentPageOffsetFraction }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - abs(off()).coerceIn(0f, 1f) * 0.7f }) {
                when (p) {
                    0 -> IntroPage(off)
                    1 -> FeaturesPage(off)
                    else -> LoginPage(busy, msg, keep, { keep = it }) { google() }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(pages) { i ->
                    val w by animateDpAsState(if (page == i) 24.dp else 8.dp, tween(200), label = "dot")
                    Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(if (page == i) Color.White else Color(0xFF3A3C45)))
                }
            }
            if (page < pages - 1) Box(Modifier.height(46.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).clickable { scope.launch { pager.animateScrollToPage(page + 1) } }.padding(horizontal = 26.dp), Alignment.Center) {
                Text(if (page == 0) "Mulai" else "Lanjut", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private val Panel = Color(0xFF1A1B20)

private fun Modifier.halftone(color: Color = Color(0xFF2B2C33), step: Dp = 12.dp) = drawWithCache {
    val s = step.toPx(); val pts = ArrayList<Offset>(); var row = 0; var y = s / 2
    while (y < size.height) {
        var x = if (row % 2 == 0) s / 2 else s
        while (x < size.width) { if (x / size.width + (1f - y / size.height) < 1.15f) pts.add(Offset(x, y)); x += s }
        y += s * 0.8f; row++
    }
    val sw = 2.6.dp.toPx()
    onDrawBehind { drawPoints(pts, PointMode.Points, color, sw, StrokeCap.Round) }
}

@Composable
private fun MascotGif(res: Int, height: Dp, ratio: Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val req = remember(res) { ImageRequest.Builder(ctx).data(res).memoryCacheKey("gif_$res").build() }
    AsyncImage(req, "Mascot MiToon", modifier.height(height).aspectRatio(ratio), contentScale = ContentScale.Fit)
}

@Composable
private fun MascotPanel(height: Dp, mascot: Dp, tilt: Float, off: () -> Float, modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(height).graphicsLayer { translationX = off() * size.width * 0.3f; rotationZ = tilt }.background(Panel).halftone().border(3.dp, Color.White)) {
        MascotGif(R.raw.mascot_welcome, mascot, 405f / 512f, Modifier.align(Alignment.BottomCenter).offset(y = (-6).dp))
    }

@Composable
private fun Heading(text: String) = Text(text, color = Color.White, fontSize = 38.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)

@Composable
private fun IntroPage(off: () -> Float) =
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Box {
            MascotPanel(270.dp, 290.dp, -2f, off)
            Text("Halo!", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = 16.dp).graphicsLayer { rotationZ = 4f }.clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(30.dp))
        Heading("BACA KOMIK\nTANPA RIBET.")
        Text("Komik & novel dari berbagai sumber, dalam satu aplikasi buatan komunitas Indonesia.", color = Muted, fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 12.dp, end = 12.dp))
    }

@Composable
private fun FeaturesPage(off: () -> Float) =
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Heading("APA SAJA\nYANG ADA?")
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FeaturePanel("01", "CARI", "Jelajahi komik dari berbagai sumber dan genre.", -1.5f, 0.2f, off, Modifier.weight(1f))
            FeaturePanel("02", "SIMPAN", "Pustaka pribadi, lanjut dari chapter terakhir.", 1.5f, 0.3f, off, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        FeaturePanel("03", "NGOBROL", "Komentar di tiap chapter dan notifikasi kabar terbaru.", -0.8f, 0.4f, off, Modifier.fillMaxWidth())
    }

@Composable
private fun FeaturePanel(no: String, word: String, desc: String, tilt: Float, speed: Float, off: () -> Float, modifier: Modifier) =
    Column(modifier.graphicsLayer { translationX = off() * size.width * speed; rotationZ = tilt }.background(Panel).halftone().border(2.dp, Color.White).padding(14.dp)) {
        Text(no, color = Color(0xFF8A8E9A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(word, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        Text(desc, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
    }

@Composable
private fun LoginPage(busy: Boolean, msg: String?, keep: Boolean, onKeep: (Boolean) -> Unit, onGoogle: () -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        MascotPanel(170.dp, 190.dp, 1.5f, { 0f })
        Spacer(Modifier.height(26.dp))
        Heading("MULAI BACA\nSEKARANG.")
        Text("Masuk dengan akun Google untuk menyimpan pustaka dan progres bacamu.", color = Muted, fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp, end = 12.dp))
        msg?.let { Text(it, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).border(2.dp, Color.White).padding(12.dp)) }
        Button(
            onGoogle, Modifier.fillMaxWidth().height(56.dp), enabled = !busy, shape = RoundedCornerShape(12.dp),
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
        Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { onKeep(!keep) }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(keep, { onKeep(it) }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Muted))
            Text("Ingat saya", color = Color.White, fontSize = 14.sp)
        }
    }
