package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val user = Auth.user
    if (user == null) { Box(Modifier.fillMaxSize().background(Bg)); return }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    LaunchedEffect(notice) { if (notice?.first == true) { delay(3000); notice = null } }

    fun setReadingHidden(hidden: Boolean) {
        // Saklar berpindah seketika; server menyusul di latar belakang (gagal -> saklar kembali + pesan error).
        notice = true to (if (hidden) "\"Sedang dibaca\" disembunyikan dari profil publik" else "\"Sedang dibaca\" tampil di profil publik")
        Auth.setting({ it.copy(readingHidden = hidden) }, { it.copy(readingHidden = !hidden) }, { Net.api.updateProfile(ProfileReq(readingHidden = hidden)).data }) { err ->
            if (err != null) notice = false to err.friendly()
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Privasi", onBack)
        Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 32.dp)) {
                Text("Profil publik", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                HorizontalDivider(color = Line, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sembunyikan \"Sedang dibaca\"", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Daftar komik yang kamu baca tidak tampil di profilmu. Progres bacamu tetap tersimpan dan muncul lagi bila dimatikan.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Switch(user.readingHidden, { setReadingHidden(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                }
                HorizontalDivider(color = Line)
                notice?.let { (ok, text) ->
                    Text(text, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).border(1.dp, if (ok) Line else Color.White, R6).padding(10.dp))
                }
            }
        }
    }
}
