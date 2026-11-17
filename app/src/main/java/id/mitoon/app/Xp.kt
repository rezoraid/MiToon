package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data class XpReq(val type: String, val ref: String? = null)
@Serializable data class XpRes(val gained: Int = 0, val user: AuthUser = AuthUser())

/** Pemberitahuan singkat saat XP bertambah. `id` membuat toast yang sama beruntun tetap dianggap baru. */
class XpFlash(val gained: Int, val level: Int, val levelUp: Boolean, val id: Long)

/**
 * Lapor aktivitas ke server untuk XP. Server yang menentukan: tiap chapter/seri hanya dihitung sekali dan ada batas harian.
 * Di sini hanya mencegah request berulang dalam satu sesi dan menelan semua error (XP tidak boleh mengganggu membaca).
 */
object Xp {
    private val sent = HashSet<String>()

    /** Ganti akun/keluar: catatan "sudah dikirim" milik akun sebelumnya dibuang. */
    fun reset() = sent.clear()

    fun event(type: String, ref: String? = null) {
        if (Auth.token == null) return
        // Bonus harian: kunci per hari WIB supaya aplikasi yang terbuka berhari-hari tetap melapor tiap hari baru.
        val key = if (type == "daily") "daily|${(System.currentTimeMillis() + 7 * 3_600_000L) / 86_400_000L}" else "$type|$ref"
        if (!sent.add(key)) return
        MainScope().launch {
            try {
                val r = Net.api.xpEvent(XpReq(type, ref)).data
                if (r.gained > 0) Auth.update(r.user)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { sent.remove(key) }
        }
    }
}

/** Gelar berdasarkan level. */
fun rankTitle(level: Int): String = when {
    level >= 50 -> "Mitos"
    level >= 35 -> "Legenda"
    level >= 20 -> "Juara"
    level >= 10 -> "Ksatria"
    level >= 5 -> "Pembaca"
    else -> "Pemula"
}

/** Lencana kecil "Lv. 12". */
@Composable
fun LevelChip(level: Int, modifier: Modifier = Modifier, size: Int = 10) {
    Text("Lv. $level", color = Color(0xFFD7D9DE), fontSize = size.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.clip(R6).border(1.dp, Color(0xFF4A4C53), R6).padding(horizontal = 5.dp, vertical = 1.dp))
}

/** Bilah progres XP menuju level berikutnya + keterangan sisa XP. */
@Composable
fun XpBar(user: AuthUser, modifier: Modifier = Modifier) {
    val frac = (user.levelXp.toFloat() / user.levelNeed.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(R6).background(Color(0xFF2A2C32))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color.White))
        }
        Text("${user.levelXp} / ${user.levelNeed} XP  ·  ${user.levelNeed - user.levelXp} XP lagi ke Lv. ${user.level + 1}",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Toast "+10 XP" / "Naik level!" di atas layar; tidak menangkap sentuhan. */
@Composable
fun XpToast(modifier: Modifier = Modifier) {
    val f = Auth.xpFlash
    LaunchedEffect(f?.id) { if (f != null) { delay(if (f.levelUp) 3200 else 2000); Auth.clearFlash() } }
    if (f == null) return
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp), Alignment.TopCenter) {
        Row(Modifier.clip(R6).background(Color.White).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("+${f.gained} XP", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            if (f.levelUp) Text("   Naik ke Lv. ${f.level}!", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
