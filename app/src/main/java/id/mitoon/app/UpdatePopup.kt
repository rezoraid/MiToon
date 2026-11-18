package id.mitoon.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

private fun verParts(v: String) = Regex("\\d+").findAll(v).map { it.value.toIntOrNull() ?: 0 }.toList()

/** true bila [remote] ("v2.6.0", "2.6") lebih baru daripada [current]. */
internal fun isNewerVersion(remote: String, current: String): Boolean {
    val a = verParts(remote)
    val b = verParts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * Popup informasi di tengah layar (pengumuman admin dengan `popup = true`), mis. info pembaruan aplikasi.
 * - Tampil sekali per pengumuman; ditutup dengan tombol X, tombol batal, atau Back (tidak muncul lagi).
 * - Pengumuman berversi: otomatis tidak tampil bila versi aplikasi sudah >= versi itu. Mengetuk tombol utama membuka tautan
 *   tapi belum "menutup selamanya" -> muncul lagi di peluncuran berikutnya sampai aplikasi diperbarui.
 */
@Composable
fun NoticePopupHost() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("mitoon_popup", Context.MODE_PRIVATE) }
    val uri = LocalUriHandler.current
    var show by remember { mutableStateOf<Notice?>(null) }
    LaunchedEffect(Auth.user?.id) {
        val list = runCatching { Net.api.announcements(20, "popup").data }.getOrNull() ?: return@LaunchedEffect
        val seen = prefs.getStringSet("dismissed", emptySet()) ?: emptySet()
        show = list.firstOrNull { n ->
            n.popup && n.id.toString() !in seen && (n.version.isNullOrBlank() || isNewerVersion(n.version, BuildConfig.VERSION_NAME))
        }
    }
    show?.let { n ->
        fun close(persist: Boolean = true) {
            if (persist) prefs.edit().putStringSet("dismissed", (prefs.getStringSet("dismissed", emptySet()) ?: emptySet()) + n.id.toString()).apply()
            show = null
        }
        NoticePopupDialog(
            n, onClose = { close() },
            onPrimary = { if (!n.url.isNullOrBlank()) runCatching { uri.openUri(n.url) }; close(persist = n.version.isNullOrBlank()) },
            onSecondary = { close() }
        )
    }
}

/** Dialog layar penuh berisi [NoticeCard] di tengah (dipakai popup aplikasi dan tombol "Uji popup" di panel admin). */
@Composable
fun NoticePopupDialog(n: Notice, onClose: () -> Unit, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 22.dp), Alignment.Center) { NoticeCard(n, onClose, onPrimary, onSecondary) }
    }
}

/** Kartu popup: gambar (opsional) + label + judul + isi + tombol utama/batal yang teksnya diatur admin. Dipakai juga untuk pratinjau di panel admin. */
@Composable
fun NoticeCard(n: Notice, onClose: () -> Unit, onPrimary: () -> Unit, onSecondary: () -> Unit, modifier: Modifier = Modifier) {
    val isUpdate = !n.version.isNullOrBlank()
    val hasLink = !n.url.isNullOrBlank()
    var imgOk by remember(n.imageUrl) { mutableStateOf(true) }
    val showImg = !n.imageUrl.isNullOrBlank() && imgOk
    val shape = RoundedCornerShape(12.dp)
    val primary = n.buttonText?.takeIf { it.isNotBlank() } ?: if (hasLink) (if (isUpdate) "Perbarui sekarang" else "Buka tautan") else "Mengerti"
    val secondary = n.cancelText?.takeIf { it.isNotBlank() } ?: if (hasLink) "Nanti saja" else null

    Box(modifier.widthIn(max = 400.dp).fillMaxWidth().clip(shape).border(1.dp, CardLine, shape).background(CardBg)) {
        Column {
            if (showImg) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF2A2C32))) {
                AsyncImage(n.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { imgOk = false })
                // pudar ke warna kartu agar gambar menyatu dengan teks di bawahnya
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp).background(Brush.verticalGradient(listOf(Color.Transparent, CardBg))))
            }
            Column(Modifier.padding(horizontal = 20.dp).padding(top = if (showImg) 4.dp else 22.dp, bottom = 16.dp)) {
                Text(
                    if (isUpdate) "PEMBARUAN  ·  v${n.version!!.removePrefix("v").removePrefix("V")}" else "INFORMASI",
                    color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF2A2C32)).padding(horizontal = 10.dp, vertical = 4.dp)
                )
                Text(plainText(n.title), color = Color.White, fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp))
                if (n.body.isNotBlank()) Box(Modifier.padding(top = 8.dp).heightIn(max = 210.dp).verticalScroll(rememberScrollState())) {
                    Text(boldText(n.body.replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")), color = Soft, fontSize = 14.sp, lineHeight = 21.sp)
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onPrimary, Modifier.fillMaxWidth().height(48.dp), shape = R6,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
                ) { Text(primary, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1) }
                if (secondary != null) TextButton(onSecondary, Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(secondary, color = Muted, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
        // tombol X di pojok kanan atas
        Box(
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onClose),
            Alignment.Center
        ) { Icon(Icons.Default.Close, "Tutup", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}
