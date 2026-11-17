package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val CardLine = Color(0xFF3A3C44)

/** Halaman akun: kartu profil + daftar menu (Pengaturan Profil, Riwayat Aktivitas, Pengaturan Akun) + Keluar. */
@Composable
fun ProfileScreen(onBack: () -> Unit, onEdit: () -> Unit, onHistory: () -> Unit, onAccount: () -> Unit) {
    val user = Auth.user
    if (user == null) { Box(Modifier.fillMaxSize().background(Bg)); return }
    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Akun", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // ---- kartu profil
            Column(Modifier.fillMaxWidth().clip(R6).border(1.dp, CardLine, R6).background(CardBg).clickable(onClick = onEdit).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user.id, user.name, user.avatarV, 64.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(rankTitle(user.level), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            LevelChip(user.level, Modifier.padding(start = 8.dp), size = 11)
                        }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(user.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(Icons.Filled.Edit, null, tint = Muted, modifier = Modifier.padding(start = 8.dp).size(16.dp))
                        }
                        Text("@${user.username}", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(24.dp))
                }
                XpBar(user, Modifier.padding(top = 14.dp))
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Line)
            MenuRow(Icons.Filled.Person, "Pengaturan Profil", onEdit)
            MenuRow(Icons.AutoMirrored.Filled.List, "Riwayat Aktivitas", onHistory)
            MenuRow(Icons.Filled.Settings, "Pengaturan Akun", onAccount)
            HorizontalDivider(color = Line)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), Alignment.Center) {
                TextButton({ Auth.logout() }) { Text("Keluar", color = Muted, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }
}

@Composable
fun SubTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) }
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 16.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(22.dp))
    }
}

/** Pengaturan Akun: hapus akun (butuh mengetik HAPUS sebagai konfirmasi). */
@Composable
fun AccountSettingsScreen(onBack: () -> Unit) {
    val user = Auth.user
    if (user == null) { Box(Modifier.fillMaxSize().background(Bg)); return }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Pengaturan Akun", onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().clickable { err = null; confirm = true }.padding(vertical = 16.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Delete, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("Hapus Akun", color = Color.White, fontSize = 16.sp)
                    Text("Akun, XP, dan komentarmu dihapus permanen.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted, modifier = Modifier.size(22.dp))
            }
            HorizontalDivider(color = Line)
        }
    }

    if (confirm) {
        var v by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) confirm = false }, containerColor = CardBg, shape = R6,
            title = { Text("Hapus akun?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Akun @${user.username} beserta XP dan datanya akan dihapus permanen dan tidak bisa dikembalikan.\n\nKetik HAPUS untuk melanjutkan.",
                        color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                    OutlinedTextField(v, { v = it }, singleLine = true, shape = R6, enabled = !busy, colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    err?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                }
            },
            confirmButton = {
                TextButton({
                    busy = true; err = null
                    scope.launch {
                        try {
                            Net.api.deleteAccount()
                            CommentHistory.clear(user.id)
                            Auth.logout()
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.friendly() } finally { busy = false }
                    }
                }, enabled = !busy && v.trim().equals("HAPUS", ignoreCase = true)) { Text("Hapus", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton({ confirm = false }, enabled = !busy) { Text("Batal", color = Muted) } }
        )
    }
}
