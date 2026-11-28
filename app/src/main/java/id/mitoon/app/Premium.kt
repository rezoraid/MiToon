package id.mitoon.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
/** Tema/frame "" = kembali ke bawaan. Hanya field yang diisi yang diubah. */
@Serializable data class StyleReq(val theme: String? = null, val frame: String? = null)

/** "Aktif sampai 12 Des 2026" / "Aktif" (pemberian permanen) / null bila tidak Premium. */
fun premiumStatusText(u: AuthUser?): String? = when {
    u == null || !u.premium -> null
    u.premiumUntil > 0 -> "Aktif sampai ${premDate(u.premiumUntil)}"
    else -> "Aktif"
}

private val Gold = Color(0xFFFFC53D)
private val GoldTint = Color(0x14FFC53D)
private val PremiumShape = RoundedCornerShape(16.dp)

private fun premDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale("id", "ID")).format(Date(ms))

// ------------------------------------------------------------------ tema profil & frame avatar (id harus sama dengan server)
class ProfileTheme(val name: String, val a: Color, val b: Color)
val ProfileThemes: Map<String, ProfileTheme> = linkedMapOf(
    "ocean" to ProfileTheme("Ocean", Color(0xFF4FC3F7), Color(0xFF1565C0)),
    "sakura" to ProfileTheme("Sakura", Color(0xFFFF9DB8), Color(0xFFB03060)),
    "aurora" to ProfileTheme("Aurora", Color(0xFF7CFFC4), Color(0xFF5B6CFF)),
    "sunset" to ProfileTheme("Sunset", Color(0xFFFFB074), Color(0xFFE0436B)),
    "gold" to ProfileTheme("Gold", Color(0xFFFFE08A), Color(0xFFB8791A)),
    "violet" to ProfileTheme("Violet", Color(0xFFC4B0FF), Color(0xFF5A3FD0))
)

/** Warna pertama = warna terakhir supaya gradasi melingkar tanpa sambungan. */
class AvatarFrame(val name: String, val colors: List<Color>)
val AvatarFrames: Map<String, AvatarFrame> = linkedMapOf(
    "gold" to AvatarFrame("Emas", listOf(Color(0xFFFFE08A), Color(0xFFFFB02E), Color(0xFFB8791A), Color(0xFFFFE08A))),
    "neon" to AvatarFrame("Neon", listOf(Color(0xFF00F0FF), Color(0xFF7A5CFF), Color(0xFFFF3DF2), Color(0xFF00F0FF))),
    "sakura" to AvatarFrame("Sakura", listOf(Color(0xFFFFC1D6), Color(0xFFFF8FB1), Color(0xFFFFD9E6), Color(0xFFFFC1D6))),
    "flame" to AvatarFrame("Api", listOf(Color(0xFFFFD23F), Color(0xFFFF7A18), Color(0xFFFF3D3D), Color(0xFFFFD23F))),
    "ice" to AvatarFrame("Es", listOf(Color(0xFFE0F7FF), Color(0xFF7CD7FF), Color(0xFF4A90FF), Color(0xFFE0F7FF))),
    "emerald" to AvatarFrame("Zamrud", listOf(Color(0xFFA8FFB5), Color(0xFF2EC27E), Color(0xFF0F7A55), Color(0xFFA8FFB5)))
)

private fun DrawScope.frameRing(colors: List<Color>, w: Float) {
    val r = (size.minDimension - w) / 2f
    val brush = Brush.sweepGradient(colors, Offset(size.width / 2f, size.height / 2f))
    drawCircle(brush, r, style = Stroke(w * 1.6f), alpha = 0.18f) // cahaya tipis di luar cincin
    drawCircle(brush, r, style = Stroke(w))
}

/** Foto profil + frame Premium (bila ada). Tanpa frame = [Avatar] biasa, ukuran & tata letak tidak berubah. */
@Composable
fun FramedAvatar(userId: Long, username: String, version: Int, size: Dp, frame: String, modifier: Modifier = Modifier) {
    val f = AvatarFrames[frame]
    if (f == null) { Avatar(userId, username, version, size, modifier); return }
    val ring = (size.value * 0.055f).coerceAtLeast(2f).dp
    val inner = size - (ring * 1.7f) * 2
    Box(modifier.size(size), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { frameRing(f.colors, ring.toPx()) }
        Avatar(userId, username, version, inner)
    }
}

/** Latar header profil berwarna tema; memudar ke [fade] di bawah. Tanpa tema = kosong. */
@Composable
fun ProfileBackdrop(theme: String, height: Dp, modifier: Modifier = Modifier, fade: Color? = CardBg) {
    val t = ProfileThemes[theme] ?: return
    Box(modifier.then(if (height == Dp.Unspecified) Modifier else Modifier.fillMaxWidth().height(height)).clipToBounds()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.a.copy(0.42f), t.b.copy(0.16f)))))
        if (fade != null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, fade))))
    }
}

@Composable
fun PremiumStyleScreen(onBack: () -> Unit) {
    val user = Auth.user
    if (user == null) { Box(Modifier.fillMaxSize().background(Bg)); return }
    val on = user.premium
    var notice by remember { mutableStateOf<String?>(null) }

    fun save(req: StyleReq, apply: (AuthUser) -> AuthUser, undo: (AuthUser) -> AuthUser) {
        if (!on) { notice = "Tema dan frame khusus akun Premium."; return }
        notice = null
        Auth.setting(apply, undo, { Net.api.updateStyle(req).data }) { e -> if (e != null) notice = e.friendly() }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Tampilan Premium", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 16.dp)) {
            if (!on) Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(R6).background(GoldTint).border(1.dp, Color(0x55FFC53D), R6).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PremiumBadge(size = 22.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Khusus Premium", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Status Premium diberikan oleh admin. Tema dan frame tampil lagi saat Premium aktif.", color = Muted, fontSize = 12.sp)
                }
            }

            Box(Modifier.fillMaxWidth().clip(PremiumShape).border(1.dp, CardLine, PremiumShape).background(CardBg)) {
                ProfileBackdrop(user.theme, Dp.Unspecified, Modifier.matchParentSize(), fade = null)
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FramedAvatar(user.id, user.name, user.avatarV, 88.dp, user.frame)
                    Text(user.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text("@${user.username}", color = Muted, fontSize = 13.sp)
                }
            }
            notice?.let { text ->
                Text(text, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, Color.White, R6).padding(10.dp))
            }

            StyleLabel("Tema profil")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item("none") { ThemeSwatch("", user.theme.isEmpty()) { val old = user.theme; save(StyleReq(theme = ""), { it.copy(theme = "") }, { it.copy(theme = old) }) } }
                items(ProfileThemes.keys.toList(), key = { it }) { id ->
                    ThemeSwatch(id, user.theme == id) { val old = user.theme; save(StyleReq(theme = id), { it.copy(theme = id) }, { it.copy(theme = old) }) }
                }
            }

            StyleLabel("Frame avatar")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item("none") { FrameSwatch("", user.frame.isEmpty()) { val old = user.frame; save(StyleReq(frame = ""), { it.copy(frame = "") }, { it.copy(frame = old) }) } }
                items(AvatarFrames.keys.toList(), key = { it }) { id ->
                    FrameSwatch(id, user.frame == id) { val old = user.frame; save(StyleReq(frame = id), { it.copy(frame = id) }, { it.copy(frame = old) }) }
                }
            }
            Text("Tema dan frame terlihat oleh orang lain di profilmu.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun StyleLabel(text: String) = Text(text, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))

@Composable
private fun ThemeSwatch(id: String, selected: Boolean, onClick: () -> Unit) {
    val t = ProfileThemes[id]
    Column(Modifier.width(60.dp).clip(R6).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(if (t != null) listOf(t.a, t.b) else listOf(Color(0xFF2A2C32), Color(0xFF2A2C32))))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else CardLine, CircleShape), Alignment.Center) {
            if (t == null) Text("–", color = Muted, fontSize = 18.sp)
        }
        Text(t?.name ?: "Tanpa", color = if (selected) Color.White else Muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FrameSwatch(id: String, selected: Boolean, onClick: () -> Unit) {
    val f = AvatarFrames[id]
    Column(Modifier.width(60.dp).clip(R6).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).then(if (selected) Modifier.border(1.5.dp, Color.White, CircleShape) else Modifier), Alignment.Center) {
            if (f != null) Canvas(Modifier.fillMaxSize()) { frameRing(f.colors, 3.dp.toPx()) }
            else Box(Modifier.fillMaxSize().clip(CircleShape).border(1.dp, CardLine, CircleShape))
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF2A2C32)))
        }
        Text(f?.name ?: "Tanpa", color = if (selected) Color.White else Muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
            modifier = Modifier.padding(top = 4.dp))
    }
}
