package id.mitoon.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.sin

/** Streak membaca dari server: [current] hari berturut-turut; [readToday] = sudah membaca hari ini (WIB). */
@Serializable data class StreakRes(val current: Int = 0, val readToday: Boolean = false)

private const val WIB_MS = 7 * 3_600_000L
private const val DAY_MS = 86_400_000L
private fun wibDay(): Long = (System.currentTimeMillis() + WIB_MS) / DAY_MS

/**
 * Streak membaca (api di header Beranda & profil). Angka resmi datang dari server lewat respons XP (`daily` saat app dibuka, `read` saat membaca);
 * di sini disimpan di perangkat bersama hari terakhir membaca, sehingga tampilan tetap benar tanpa jaringan dan ikut berganti saat tengah malam:
 *  - hari ini sudah membaca  -> api menyala (hot)
 *  - baru kemarin            -> angka masih tampil, api padam (baca hari ini untuk menyelamatkannya)
 *  - lebih lama              -> 0
 */
object Streak {
    private var prefs: SharedPreferences? = null
    private var count by mutableIntStateOf(0)
    /** Hari (WIB, hitungan sejak epoch) terakhir kali membaca; -1 = tidak ada streak. */
    private var lastDay by mutableLongStateOf(-1L)
    private var today by mutableLongStateOf(wibDay())

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("mitoon_streak", Context.MODE_PRIVATE)
        count = prefs?.getInt("c", 0) ?: 0
        lastDay = prefs?.getLong("d", -1L) ?: -1L
        today = wibDay()
    }

    /** Dipanggil berkala oleh UI supaya status berganti sendiri saat lewat tengah malam WIB. */
    fun refreshDay() { today = wibDay() }

    val current: Int get() = if (lastDay >= 0 && today - lastDay <= 1) count else 0
    /** Api menyala: sudah membaca hari ini. */
    val hot: Boolean get() = current > 0 && today == lastDay

    fun set(r: StreakRes) {
        val t = wibDay()
        today = t
        count = r.current.coerceAtLeast(0)
        lastDay = if (count == 0) -1L else if (r.readToday) t else t - 1
        prefs?.edit()?.putInt("c", count)?.putLong("d", lastDay)?.apply()
    }

    fun clear() {
        count = 0; lastDay = -1L
        prefs?.edit()?.clear()?.apply()
    }
}

// ------------------------------------------------------------------ api

private val FlameOuter = listOf(Color(0xFFFF9A2E), Color(0xFFFF4D1C))
private val FlameInner = listOf(Color(0xFFFFE27A), Color(0xFFFFB02E))
private val FlameOffOuter = listOf(Color(0xFF50525B), Color(0xFF3A3C44))
private val FlameOffInner = listOf(Color(0xFF656873), Color(0xFF50525B))

/** Badan api (satuan 0..1), diskalakan terhadap titik tengah-bawah; [sw] -1..1 = ayunan ujung, [h] = tinggi (1 = penuh). */
private fun DrawScope.flamePath(sw: Float, h: Float, k: Float): Path {
    val s = size.minDimension
    val tx = 0.50f + 0.07f * sw
    val ty = 0.03f + (1f - h) * 0.25f
    fun x(v: Float) = s * (0.5f + (v - 0.5f) * k)
    fun y(v: Float) = s * (0.98f + (v - 0.98f) * k)
    return Path().apply {
        moveTo(x(0.50f), y(0.98f))
        cubicTo(x(0.20f), y(0.98f), x(0.09f), y(0.70f), x(0.27f), y(0.46f))
        cubicTo(x(0.28f), y(0.55f), x(0.34f), y(0.58f), x(0.40f), y(0.55f))
        cubicTo(x(0.36f), y(0.34f), x(tx - 0.10f), y(0.18f), x(tx), y(ty))
        cubicTo(x(tx + 0.10f), y(0.20f), x(0.72f), y(0.34f), x(0.75f), y(0.48f))
        cubicTo(x(0.86f), y(0.56f), x(0.92f), y(0.74f), x(0.80f), y(0.88f))
        cubicTo(x(0.72f), y(0.96f), x(0.62f), y(0.98f), x(0.50f), y(0.98f))
        close()
    }
}

/**
 * Ikon api. [lit] = menyala: ujung api meliuk, tinggi berdenyut, ada cahaya dan percikan (ukuran besar); semuanya dihitung dari satu
 * waktu siklus yang berulang mulus dan dibaca di fase draw, jadi tidak memicu recompose. Padam = api abu-abu diam.
 */
@Composable
fun Flame(size: Dp, lit: Boolean, modifier: Modifier = Modifier) {
    val phase = if (lit) rememberInfiniteTransition(label = "flame").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "t"
    ) else null
    Canvas(modifier.size(size)) {
        val t = phase?.value ?: 0f
        val s = this.size.minDimension
        val tau = (2 * PI).toFloat()
        if (!lit) {
            drawPath(flamePath(0f, 0.97f, 1f), Brush.verticalGradient(FlameOffOuter, 0f, s))
            drawPath(flamePath(0f, 0.97f, 0.52f), Brush.verticalGradient(FlameOffInner, s * 0.5f, s))
            return@Canvas
        }
        val sway = 0.65f * sin(tau * t) + 0.35f * sin(tau * 3 * t + 0.8f)
        val tall = 0.94f + 0.06f * sin(tau * 2 * t + 1.3f)
        val swayIn = 0.65f * sin(tau * 2 * t + 2.1f) + 0.35f * sin(tau * t + 0.4f)
        val tallIn = 0.92f + 0.08f * sin(tau * 3 * t + 0.2f)
        // cahaya lembut di belakang
        val glow = 0.20f + 0.06f * sin(tau * 2 * t)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFF7A18).copy(alpha = glow), Color.Transparent), Offset(s * 0.5f, s * 0.66f), s * 0.62f), s * 0.62f, Offset(s * 0.5f, s * 0.66f))
        drawPath(flamePath(sway, tall, 1f), Brush.verticalGradient(FlameOuter, 0f, s))
        drawPath(flamePath(swayIn, tallIn, 0.55f), Brush.verticalGradient(FlameInner, s * 0.45f, s))
        // percikan kecil naik dan memudar (hanya bila cukup besar agar ikon kecil tetap bersih)
        if (s > 40.dp.toPx()) for (i in 0..2) {
            val p = (t + i / 3f) % 1f
            val a = (1f - p).coerceIn(0f, 1f) * minOf(1f, p * 6f)
            val cx = s * (0.5f + 0.22f * sin(tau * (p * 1.5f + i * 0.33f)))
            val cy = s * (0.62f - 0.62f * p)
            drawCircle(Color(0xFFFFC53D).copy(alpha = a * 0.9f), s * (0.03f + 0.025f * (1f - p)), Offset(cx, cy))
        }
    }
}

/** Api + angka streak; ditekan untuk melihat penjelasan. Hanya untuk yang sudah masuk (streak tersimpan di akun). */
@Composable
fun StreakChip(modifier: Modifier = Modifier) {
    if (Auth.user == null) return
    LaunchedEffect(Unit) { while (true) { Streak.refreshDay(); delay(60_000) } }
    val n = Streak.current
    val hot = Streak.hot
    var open by remember { mutableStateOf(false) }
    Row(modifier.clip(R6).clickable { open = true }.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Flame(24.dp, hot)
        Text("$n", color = if (hot) Color(0xFFFFB02E) else Muted, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 2.dp))
    }
    if (open) StreakDialog(n, hot) { open = false }
}

@Composable
private fun StreakDialog(n: Int, hot: Boolean, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = CardBg, shape = R6,
    title = {
        Column(Modifier.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Flame(84.dp, hot)
            Text(if (n > 0) "Streak membaca $n hari" else "Streak membaca", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, textAlign = TextAlign.Center)
        }
    },
    text = {
        Text(
            when {
                hot -> "Kamu sudah membaca hari ini. Kembali besok untuk melanjutkan streak!"
                n > 0 -> "Baca satu chapter hari ini sebelum tengah malam (WIB) supaya apimu tidak padam."
                else -> "Baca satu chapter hari ini untuk menyalakan api. Setiap hari berturut-turut menambah streak-mu."
            },
            color = Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
        )
    },
    confirmButton = { TextButton(onDismiss) { Text("Tutup", color = Color.White, fontWeight = FontWeight.Bold) } }
)
