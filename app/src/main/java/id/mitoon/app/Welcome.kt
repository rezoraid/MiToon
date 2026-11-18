package id.mitoon.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sambutan sesaat setelah login Google: logo membesar dengan pegas, cincin cahaya memudar keluar, lalu nama muncul.
 * Semua animasi dibaca di fase draw/layer (tanpa recompose), jadi tetap mulus. Selesai -> [onDone] masuk ke aplikasi.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val logo = remember { Animatable(0f) }
    val ring = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val bar = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { logo.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
            launch { delay(120); ring.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
            launch { delay(350); text.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
            launch { delay(200); bar.animateTo(1f, tween(1000, easing = LinearEasing)) }
        }
        delay(150)
        onDone()
    }
    val name = Auth.user?.name.orEmpty()
    Box(Modifier.fillMaxSize().background(Bg), Alignment.Center) {
        Box(Modifier.size(260.dp).drawBehind {
            val p = ring.value
            if (p > 0f) drawCircle(Color.White.copy(alpha = (1f - p) * 0.35f), radius = 48.dp.toPx() + 100.dp.toPx() * p, center = Offset(size.width / 2, size.height / 2), style = Stroke(2.dp.toPx()))
        })
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo), "MiToon", Modifier.size(96.dp).graphicsLayer {
                val v = logo.value
                scaleX = 0.5f + 0.5f * v; scaleY = 0.5f + 0.5f * v; alpha = v.coerceIn(0f, 1f)
            }.clip(R6))
            Column(Modifier.padding(top = 28.dp).graphicsLayer { alpha = text.value; translationY = (1f - text.value) * 24.dp.toPx() }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Selamat datang", color = Muted, fontSize = 14.sp)
                if (name.isNotBlank()) Text(name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp))
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).width(120.dp).height(3.dp).clip(R6).background(Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth().graphicsLayer { scaleX = bar.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(Color.White))
        }
    }
}

/** Isi aplikasi masuk dengan zoom lembut bila baru datang dari layar sambutan. */
@Composable
fun AppEntrance(content: @Composable () -> Unit) {
    val animate = remember { Auth.pendingEntrance.also { Auth.pendingEntrance = false } }
    val s = remember { Animatable(if (animate) 0.94f else 1f) }
    LaunchedEffect(Unit) { if (animate) s.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxSize().graphicsLayer { scaleX = s.value; scaleY = s.value }) { content() }
}
