package id.mitoon.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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


private val R16 = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)

/** Halaman akun: kartu profil (foto bulat, nama, status Owner/Premium, peran + level, XP) lalu menu dalam kartu berkelompok + Keluar. */
@Composable
fun ProfileScreen(onBack: () -> Unit, onEdit: () -> Unit, onHistory: () -> Unit, onAccount: () -> Unit, onRoles: () -> Unit = {}, onAdmin: () -> Unit = {}, onPrivacy: () -> Unit = {}, onStyle: () -> Unit = {}) {
    val user = Auth.user
    if (user == null) { VisitorProfile(onBack); return }
    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Akun", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // ---- kartu profil
            Box(Modifier.fillMaxWidth().clip(R16).border(1.dp, CardLine, R16).background(CardBg).clickable(onClick = onEdit)) {
              ProfileBackdrop(user.theme, androidx.compose.ui.unit.Dp.Unspecified, Modifier.matchParentSize(), fade = null)
              Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FramedAvatar(user.id, user.name, user.avatarV, 72.dp, user.frame)
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
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoleTag(user.level, iconSize = 22.dp)
                    LevelChip(user.level, Modifier, size = 11)
                }
                if (user.bio.isNotBlank()) Text(user.bio, color = Color(0xFFD7D9DE), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                XpBar(user, Modifier.padding(top = 14.dp))
                ProfileStreak(Modifier.padding(top = 14.dp))
              }
            }

            Spacer(Modifier.height(14.dp))
            MenuGroup {
                if (user.premium) {
                    PremiumMenuRow(user, onStyle)
                    MenuDivider()
                }
                MenuRow(Ic.Person, "Pengaturan Profil", onEdit)
                MenuDivider()
                MenuRow(Ic.Lock, "Privasi", onPrivacy)
                MenuDivider()
                MenuRow(Ic.Star, "Peran & Lencana", onRoles)
                MenuDivider()
                MenuRow(Ic.List, "Riwayat Aktivitas", onHistory)
                MenuDivider()
                MenuRow(Ic.Gear, "Pengaturan Akun", onAccount)
                MenuDivider()
                LanguageRow()
                if (user.isOwner) { MenuDivider(); MenuRow(Ic.Tool, "Panel Admin", onAdmin) }
            }
            Spacer(Modifier.height(14.dp))
            MenuGroup { MenuRow(Ic.Logout, "Keluar") { Auth.logout() } }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PremiumMenuRow(user: AuthUser, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), Alignment.Center) { PremiumBadge(size = 22.dp) }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text("MiToon Premium", color = Color.White, fontSize = 16.sp)
            premiumStatusText(user)?.let { Text(it, color = Color(0xFFFFC53D), fontSize = 12.sp) }
        }
        Icon(Ic.Right, null, tint = Muted, modifier = Modifier.size(22.dp))
    }
}

/** Baris streak membaca di kartu profil: api (menyala bila sudah membaca hari ini) + keterangan singkat. */
@Composable
private fun ProfileStreak(modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { while (true) { Streak.refreshDay(); kotlinx.coroutines.delay(60_000) } }
    val n = Streak.current
    val hot = Streak.hot
    Row(modifier.fillMaxWidth().clip(R6).background(Bg).border(1.dp, Line, R6).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Flame(36.dp, hot)
        Column(Modifier.padding(start = 10.dp)) {
            Text(if (n > 0) "Streak membaca $n hari" else "Belum ada streak", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                when { hot -> "Sudah membaca hari ini"; n > 0 -> "Baca hari ini supaya apimu tidak padam"; else -> "Baca satu chapter untuk menyalakan api" },
                color = Muted, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun VisitorProfile(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Akun", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Column(Modifier.fillMaxWidth().clip(R16).border(1.dp, CardLine, R16).background(CardBg).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Line), Alignment.Center) {
                        Icon(Ic.Person, null, tint = Muted, modifier = Modifier.size(34.dp))
                    }
                    Column(Modifier.padding(start = 16.dp)) {
                        Text("Pengunjung", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Belum masuk", color = Muted, fontSize = 13.sp)
                    }
                }
                Text("Masuk dengan Google untuk berkomentar, memberi like, punya profil, mengumpulkan XP, dan menyinkronkan pustaka lintas perangkat. Pustaka di perangkat ini ikut terbawa.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 14.dp))
                Button({ GuestGate.openUpgrade() }, Modifier.padding(top = 14.dp).fillMaxWidth().height(48.dp), shape = R6,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1F1F))) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_google), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Masuk dengan Google", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            MenuGroup { LanguageRow() }
        }
    }
}

@Composable
private fun MenuGroup(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(R16).border(1.dp, CardLine, R16).background(CardBg), content = content)

@Composable
private fun MenuDivider() = HorizontalDivider(color = Line, modifier = Modifier.padding(start = 54.dp))

@Composable
fun SubTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Ic.Left, "Kembali", tint = Color.White) }
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }
    HorizontalDivider(color = Line)
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 16.dp))
        Icon(Ic.Right, null, tint = Muted, modifier = Modifier.size(22.dp))
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
            onDismissRequest = { if (!busy) confirm = false }, containerColor = CardBg, shape = RoundedCornerShape(20.dp),
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
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 16.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), Alignment.Center) { Text("文A", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text("Bahasa", color = Color.White, fontSize = 16.sp)
            Text(current, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ic.Right, null, tint = Muted, modifier = Modifier.size(22.dp))
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
