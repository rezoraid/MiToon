package id.mitoon.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

private val UsernameChars = Regex("^[A-Za-z0-9_.]{3,20}$")
private val Outline = Color(0xFF4A4C53)

/** "6 hari 4 jam", "5 jam 12 menit", "12 menit 30 detik", "40 detik". */
private fun cooldownText(ms: Long): String {
    val s = (ms + 999) / 1000
    val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return when {
        d > 0 -> if (h > 0) "$d hari $h jam" else "$d hari"
        h > 0 -> "$h jam $m menit"
        m > 0 -> "$m menit $sec detik"
        else -> "$sec detik"
    }
}

/** Pengaturan Profil: foto, nama (nickname), dan username. */
@Composable
fun ProfileEditScreen(onBack: () -> Unit) {
    val user = Auth.user
    if (user == null) { Box(Modifier.fillMaxSize().background(Bg)); return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }     // upload/hapus foto -> spinner di foto
    var saving by remember { mutableStateOf(false) }   // simpan nama/username -> spinner di tombol dialog
    var notice by remember { mutableStateOf<Pair<Boolean, String>?>(null) }   // (berhasil?, teks)
    var photoMenu by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var editBio by remember { mutableStateOf(false) }
    var editUser by remember { mutableStateOf(false) }
    var pendingUsername by remember { mutableStateOf<String?>(null) }          // menunggu konfirmasi
    var dialogErr by remember { mutableStateOf<String?>(null) }

    // Sisa cooldown username: dihitung dari jam perangkat, diperbarui tiap detik (saat < 1 jam) atau tiap 30 detik.
    val unlockAt = Auth.usernameUnlockAt
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(unlockAt) {
        while (true) {
            now = System.currentTimeMillis()
            val left = unlockAt - now
            if (left <= 0) break
            delay(if (left < 3_600_000) 1000 else 30_000)
        }
    }
    val leftMs = (unlockAt - now).coerceAtLeast(0)
    val locked = leftMs > 0

    LaunchedEffect(notice) { if (notice?.first == true) { delay(3000); notice = null } }

    fun upload(uri: Uri) {
        busy = true; notice = null
        scope.launch {
            try {
                val bytes = prepareAvatar(ctx, uri)
                Auth.update(Net.api.putAvatar(bytes.toRequestBody("image/jpeg".toMediaType())).data)
                notice = true to "Foto profil diperbarui"
            } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = false to e.friendly() } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) upload(uri) }

    fun removePhoto() {
        busy = true; notice = null
        scope.launch {
            try { Auth.update(Net.api.deleteAvatar().data); notice = true to "Foto profil dihapus" }
            catch (e: CancellationException) { throw e } catch (e: Exception) { notice = false to e.friendly() } finally { busy = false }
        }
    }

    /** Simpan nama / username. Berhasil -> tutup dialog; gagal -> pesan tampil di dalam dialog. */
    fun save(req: ProfileReq, okMsg: String, onOk: () -> Unit) {
        saving = true; dialogErr = null
        scope.launch {
            try {
                Auth.update(Net.api.updateProfile(req).data)
                notice = true to okMsg
                onOk()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                dialogErr = e.friendly()
                // 429 = cooldown masih jalan menurut server (mis. diganti dari perangkat lain): selaraskan waktu lokal.
                if (e is HttpException && e.code() == 429) runCatching { Auth.update(Net.api.me().data) }
            } finally { saving = false }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) { // inset sudah diberikan Scaffold; jangan dobel
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) }
            Text("Pengaturan Profil", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        HorizontalDivider(color = Line)

        Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ---- foto: selalu di tengah; ketuk untuk ganti/hapus
                Box(Modifier.size(120.dp).clip(CircleShape).clickable(enabled = !busy) { photoMenu = true }, Alignment.Center) {
                    Avatar(user.id, user.name, user.avatarV, 120.dp, shape = CircleShape)
                    if (busy) Box(Modifier.fillMaxSize().background(Color(0x99000000)), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp)
                    }
                }
                TextButton({ photoMenu = true }, enabled = !busy) { Text("Ubah foto", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }

                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(user.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false))
                    StatusBadges(user.verified, user.isOwner, size = 22.dp)
                }
                Text("@${user.username}", color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))

                notice?.let { (ok, text) ->
                    Text(text, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp).border(1.dp, if (ok) Outline else Color.White, R6).padding(10.dp))
                }

                // ---- info akun
                Spacer(Modifier.height(28.dp))
                HorizontalDivider(color = Line)
                InfoRow("Nama", user.name, null, false, onClick = { dialogErr = null; editName = true })
                HorizontalDivider(color = Line)
                InfoRow("Bio", user.bio.ifBlank { "Belum diisi" }, "Cerita singkat tentangmu (maks. 150 karakter)", false, onClick = { dialogErr = null; editBio = true }, maxLines = 3)
                HorizontalDivider(color = Line)
                InfoRow("Username", "@${user.username}",
                    if (locked) "Bisa diganti lagi dalam ${cooldownText(leftMs)}" else "Bisa diganti sekali setiap 7 hari",
                    locked, onClick = { dialogErr = null; editUser = true })
                HorizontalDivider(color = Line)
            }
        }
    }

    // ---------------------------------------------------------------- dialog foto
    if (photoMenu) AlertDialog(
        onDismissRequest = { photoMenu = false }, containerColor = CardBg, shape = R6,
        title = { Text("Foto profil", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                MenuItem("Pilih dari galeri") { photoMenu = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                if (user.avatarV > 0) MenuItem("Hapus foto") { photoMenu = false; removePhoto() }
            }
        },
        confirmButton = {}, dismissButton = { TextButton({ photoMenu = false }) { Text("Batal", color = Muted) } }
    )

    // ---------------------------------------------------------------- dialog nama
    if (editName) {
        var v by remember { mutableStateOf(user.displayName.ifBlank { user.username }) }
        AlertDialog(
            onDismissRequest = { if (!saving) editName = false }, containerColor = CardBg, shape = R6,
            title = { Text("Ubah nama", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(v, { if (it.length <= 30) v = it }, singleLine = true, shape = R6, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                    Text("${v.length}/30  ·  Kosongkan untuk memakai username.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    dialogErr?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                }
            },
            confirmButton = {
                TextButton({ save(ProfileReq(displayName = v.trim()), "Nama diperbarui") { editName = false } }, enabled = !saving && v.trim() != user.name) {
                    if (saving) BtnSpinner() else Text("Simpan", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton({ editName = false }, enabled = !saving) { Text("Batal", color = Muted) } }
        )
    }

    // ---------------------------------------------------------------- dialog bio
    if (editBio) {
        var v by remember { mutableStateOf(user.bio) }
        AlertDialog(
            onDismissRequest = { if (!saving) editBio = false }, containerColor = CardBg, shape = R6,
            title = { Text("Ubah bio", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        v, { if (it.length <= 150 && it.count { c -> c == '\n' } <= 2) v = it },
                        minLines = 3, maxLines = 5, shape = R6, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                        placeholder = { Text("Tulis sesuatu tentangmu…", color = Muted) }
                    )
                    Text("${v.length}/150  ·  Maksimal 3 baris. Kosongkan untuk menghapus.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    dialogErr?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                }
            },
            confirmButton = {
                TextButton({ save(ProfileReq(bio = v.trim()), "Bio diperbarui") { editBio = false } }, enabled = !saving && v.trim() != user.bio) {
                    if (saving) BtnSpinner() else Text("Simpan", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton({ editBio = false }, enabled = !saving) { Text("Batal", color = Muted) } }
        )
    }

    // ---------------------------------------------------------------- dialog username (+ konfirmasi)
    if (editUser) {
        var v by remember { mutableStateOf(user.username) }
        val valid = UsernameChars.matches(v)
        AlertDialog(
            onDismissRequest = { if (!saving) editUser = false }, containerColor = CardBg, shape = R6,
            title = { Text("Ubah username", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        v, { v = it.filter { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' }.take(20) },
                        singleLine = true, enabled = !locked, shape = R6, prefix = { Text("@", color = Muted) },
                        isError = v.isNotEmpty() && !valid, modifier = Modifier.fillMaxWidth(), colors = fieldColors()
                    )
                    if (locked) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = Muted, modifier = Modifier.size(15.dp))
                        Text("  Bisa diganti lagi dalam ${cooldownText(leftMs)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    } else Text("3–20 karakter: huruf, angka, titik, atau garis bawah. Setelah diganti, username terkunci selama 7 hari.",
                        color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
                    dialogErr?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                }
            },
            confirmButton = {
                TextButton({ dialogErr = null; pendingUsername = v }, enabled = !saving && !locked && valid && v != user.username) {
                    if (saving) BtnSpinner() else Text("Lanjut", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton({ editUser = false }, enabled = !saving) { Text("Batal", color = Muted) } }
        )
    }
    pendingUsername?.let { newName ->
        AlertDialog(
            onDismissRequest = { if (!saving) pendingUsername = null }, containerColor = CardBg, shape = R6,
            title = { Text("Ganti username?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text("Username berubah dari @${user.username} menjadi @$newName.\n\n@${user.username} bisa dipakai orang lain, dan kamu baru bisa mengganti username lagi setelah 7 hari.",
                    color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            },
            confirmButton = {
                TextButton({
                    pendingUsername = null // konfirmasi ditutup; gagal -> pesan tampil di dialog username, berhasil -> dialog username ikut tutup
                    save(ProfileReq(username = newName), "Username diganti menjadi @$newName") { editUser = false }
                }, enabled = !saving) { if (saving) BtnSpinner() else Text("Ya, ganti", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton({ pendingUsername = null }, enabled = !saving) { Text("Batal", color = Muted) } }
        )
    }
}

/** Baris info rata kiri-kanan: label kecil di atas, nilai di bawah, ikon pensil di kanan (gembok bila terkunci). */
/** Spinner kecil pengganti teks tombol saat menyimpan. */
@Composable
private fun BtnSpinner() = CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)

@Composable
private fun InfoRow(label: String, value: String, hint: String?, locked: Boolean, onClick: () -> Unit, maxLines: Int = 1) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Muted, fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (hint != null) Text(hint, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(if (locked) Icons.Filled.Lock else Icons.Filled.Edit, null, tint = Muted, modifier = Modifier.padding(start = 12.dp).size(18.dp))
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) =
    Text(text, color = Color.White, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clip(R6).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp))

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White, unfocusedTextColor = Color.White, disabledTextColor = Muted,
    focusedBorderColor = Color.White, unfocusedBorderColor = Outline, disabledBorderColor = Line, errorBorderColor = Color.White,
    cursorColor = Color.White, errorCursorColor = Color.White, errorTextColor = Color.White
)
