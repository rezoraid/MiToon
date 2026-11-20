package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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


private val R16 = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

/** Halaman akun: kartu profil (foto bulat, nama, status Owner/Premium, peran + level, XP) lalu menu dalam kartu berkelompok + Keluar. */
@Composable
fun ProfileScreen(onBack: () -> Unit, onEdit: () -> Unit, onHistory: () -> Unit, onAccount: () -> Unit, onRoles: () -> Unit = {}, onAdmin: () -> Unit = {}) {
    val user = Auth.user
    if (user == null) { Box(Modifier.fillMaxSize().background(Bg)); return }
    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Akun", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // ---- kartu profil
            Column(Modifier.fillMaxWidth().clip(R16).background(CardBg).clickable(onClick = onEdit).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user.id, user.name, user.avatarV, 72.dp, shape = androidx.compose.foundation.shape.CircleShape)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(user.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (user.verified && !user.verifiedHidden) VerifiedBadge(Modifier.padding(start = 6.dp), 18.dp)
                        }
                        Text("@${user.username}", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusLabels(user.isOwner, user.premium, Modifier.padding(top = 6.dp), iconSize = 18.dp, textSize = 13, center = false)
                    }
                    Icon(Ic.Right, null, tint = Muted, modifier = Modifier.size(24.dp))
                }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoleTag(user.level, iconSize = 22.dp)
                    LevelChip(user.level, Modifier.padding(start = 8.dp), size = 11)
                }
                if (user.bio.isNotBlank()) Text(user.bio, color = Color(0xFFD7D9DE), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                XpBar(user, Modifier.padding(top = 14.dp))
            }

            Spacer(Modifier.height(14.dp))
            MenuGroup {
                MenuRow(Ic.Person, "Pengaturan Profil", Accent, onEdit)
                MenuDivider()
                MenuRow(Ic.Star, "Peran & Lencana", Color(0xFFFF9F0A), onRoles)
                MenuDivider()
                MenuRow(Ic.List, "Riwayat Aktivitas", IosGreen, onHistory)
                MenuDivider()
                MenuRow(Ic.Gear, "Pengaturan Akun", Muted, onAccount)
                MenuDivider()
                LanguageRow()
                if (user.isOwner) { MenuDivider(); MenuRow(Ic.Tool, "Panel Admin", Color(0xFFBF5AF2), onAdmin) }
            }
            Spacer(Modifier.height(14.dp))
            MenuGroup { MenuRow(Ic.Logout, "Keluar", IosRed) { Auth.logout() } }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MenuGroup(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(R16).background(CardBg), content = content)

@Composable
private fun MenuDivider() = HorizontalDivider(color = Line, thickness = 0.5.dp, modifier = Modifier.padding(start = 60.dp))

@Composable
fun SubTopBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp)) {
        IconButton(onBack, Modifier.align(Alignment.CenterStart)) { Icon(Ic.Left, "Kembali", tint = Accent) }
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = 52.dp))
    }
    HorizontalDivider(color = Line, thickness = 0.5.dp)
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(color, RoundedCornerShape(7.dp)), Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f).padding(start = 14.dp))
        Icon(Ic.Right, null, tint = Color(0xFF636366), modifier = Modifier.size(16.dp))
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
                Icon(Ic.Trash, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("Hapus Akun", color = Color.White, fontSize = 16.sp)
                    Text("Akun, XP, dan komentarmu dihapus permanen.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(Ic.Right, null, tint = Muted, modifier = Modifier.size(22.dp))
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
                            Net.api.deleteAccount("HAPUS")
                            CommentHistory.clear()
                            Auth.logout()
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.friendly() } finally { busy = false }
                    }
                }, enabled = !busy && v.trim().equals("HAPUS", ignoreCase = true)) { Text("Hapus", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton({ confirm = false }, enabled = !busy) { Text("Batal", color = Muted) } }
        )
    }
}

@Composable
private fun LanguageRow() {
    var open by remember { mutableStateOf(false) }
    val current = Lang.options.firstOrNull { it.code == Lang.choice }?.label ?: tr("Ikuti bahasa perangkat")
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 10.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Color(0xFF5E5CE6), RoundedCornerShape(7.dp)), Alignment.Center) { Text("文A", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Bahasa", color = Color.White, fontSize = 17.sp)
            Text(current, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ic.Right, null, tint = Color(0xFF636366), modifier = Modifier.size(16.dp))
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Pilih bahasa") },
        text = {
            Column {
                LangChoice("Ikuti bahasa perangkat", Lang.choice.isEmpty()) { Lang.set(""); open = false }
                Lang.options.forEach { o -> LangChoice(o.label, Lang.choice == o.code) { Lang.set(o.code); open = false } }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text("Tutup") } }
    )
}

@Composable
private fun LangChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
    }
}
