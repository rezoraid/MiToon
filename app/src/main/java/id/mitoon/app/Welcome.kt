package id.mitoon.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private class Spark(val x: Float, val speed: Int, val r: Float, val phase: Float)
private val sparks = List(18) { i -> Spark(((i * 37) % 100) / 100f, 1 + i % 3, 1.2f + (i % 4) * 0.7f, (i * 0.137f) % 1f) }
private const val TAU = (2 * PI).toFloat()

/**
 * Layar loading setelah login berhasil: mascot melompat masuk dengan pegas lalu melayang pelan,
 * di belakangnya cincin putus-putus berputar berlawanan arah + cahaya berdenyut + titik orbit,
 * partikel bintang naik perlahan, lalu nama pengguna, titik "menyiapkan", dan bilah progres.
 * Semua animasi dibaca di fase draw/layer (tanpa recompose). Selesai -> [onDone] masuk ke aplikasi.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val intro = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    val bar = remember { Animatable(0f) }
    val inf = rememberInfiniteTransition(label = "welcome")
    // satu jam 0..1 per 2,4 dtk; semua gerak berulang memakai kelipatan bulatnya supaya mulus tanpa loncatan
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "t")
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { intro.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) }
            launch { delay(420); text.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
            launch { bar.animateTo(1f, tween(2200, easing = FastOutSlowInEasing)) }
        }
        delay(180)
        onDone()
    }
    val name = Auth.user?.name.orEmpty()
    Box(Modifier.fillMaxSize().background(Bg).drawBehind {
        // cahaya lembut di tengah + partikel bintang yang naik
        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.42f), radius = size.width * 0.9f))
        sparks.forEach { s ->
            val p = (t * s.speed + s.phase) % 1f
            val x = s.x * size.width + sin((p + s.phase) * TAU) * 12.dp.toPx()
            drawCircle(Color.White.copy(alpha = sin(p * PI.toFloat()) * 0.5f), s.r.dp.toPx(), Offset(x, size.height * (1f - p)))
        }
    }, Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(300.dp).drawBehind {
                val c = Offset(size.width / 2, size.height / 2)
                val pulse = 0.5f + 0.5f * sin(t * 2 * TAU)
                val appear = intro.value.coerceIn(0f, 1f)
                // cahaya berdenyut di belakang mascot
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = (0.14f + 0.08f * pulse) * appear), Color.Transparent), c, 150.dp.toPx()), 150.dp.toPx(), c)
                // cincin dalam: putus-putus, searah jarum jam
                rotate(t * 360f, c) {
                    drawCircle(Color.White.copy(alpha = 0.5f * appear), 112.dp.toPx(), c,
                        style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 13.dp.toPx()))))
                }
                // cincin luar: tipis, berlawanan arah
                rotate(-t * 720f, c) {
                    drawCircle(Color.White.copy(alpha = 0.22f * appear), 140.dp.toPx(), c,
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 9.dp.toPx()))))
                }
                // dua titik orbit
                for (k in 0..1) {
                    val a = t * TAU + k * PI.toFloat()
                    val o = Offset(c.x + cos(a) * 112.dp.toPx(), c.y + sin(a) * 112.dp.toPx())
                    drawCircle(Color.White.copy(alpha = 0.25f * appear), 7.dp.toPx(), o)
                    drawCircle(Color.White.copy(alpha = appear), 3.5.dp.toPx(), o)
                }
                // bayangan lembut di bawah kaki, mengecil saat mascot naik
                val bob = sin(t * TAU)
                val w = (96.dp.toPx()) * (1f - 0.12f * bob) * appear
                drawOval(Color.White.copy(alpha = 0.10f * appear), Offset(c.x - w / 2, c.y + 112.dp.toPx()), Size(w, 10.dp.toPx()))
            }, Alignment.Center) {
                Image(painterResource(R.drawable.mascot), "Mascot MiToon", Modifier.height(190.dp).graphicsLayer {
                    val v = intro.value
                    val s = 0.3f + 0.7f * v
                    scaleX = s; scaleY = s
                    alpha = v.coerceIn(0f, 1f)
                    translationY = (1f - v) * 70.dp.toPx() - sin(t * TAU) * 7.dp.toPx() // masuk dari bawah, lalu melayang
                    rotationZ = sin(t * TAU) * 2.5f
                }, contentScale = ContentScale.Fit)
            }
            Column(Modifier.graphicsLayer { alpha = text.value; translationY = (1f - text.value) * 24.dp.toPx() }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Selamat datang", color = Muted, fontSize = 14.sp)
                if (name.isNotBlank()) Text(name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp))
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Menyiapkan aplikasi", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(28.dp, 6.dp).drawBehind {
                        for (i in 0..2) {
                            val a = 0.25f + 0.75f * (0.5f + 0.5f * sin((t * 2f - i * 0.25f) * TAU))
                            drawCircle(Color.White.copy(alpha = a), 2.5.dp.toPx(), Offset(3.dp.toPx() + i * 11.dp.toPx(), size.height / 2))
                        }
                    })
                }
            }
        }
        // bilah progres: isi memanjang dengan kilau yang bergerak di atasnya
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp).width(160.dp).height(4.dp).clip(R6).background(Line)) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = bar.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(Color.White)
                .drawBehind {
                    val x = (t * 1.6f % 1f) * size.width * 1.4f - size.width * 0.2f
                    drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFB8BBC6), Color.Transparent), x - 24.dp.toPx(), x + 24.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
                })
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
