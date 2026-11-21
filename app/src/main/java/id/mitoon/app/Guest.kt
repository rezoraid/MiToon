package id.mitoon.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object GuestGate {
    var prompt by mutableStateOf<String?>(null); private set
    var upgrade by mutableStateOf(false); private set
    fun ask(message: String) { prompt = message }
    fun openUpgrade() { prompt = null; upgrade = true }
    fun dismiss() { prompt = null; upgrade = false }
}

const val GUEST_COMMENT = "Daftar atau masuk dulu untuk berkomentar."
const val GUEST_REPLY = "Daftar atau masuk dulu untuk membalas komentar."
const val GUEST_VOTE = "Daftar atau masuk dulu untuk memberi like."

@Composable
fun GuestGateHost() {
    GuestGate.prompt?.let { msg ->
        AlertDialog(
            onDismissRequest = { GuestGate.dismiss() }, containerColor = CardBg, shape = R6,
            title = { Text("Khusus akun terdaftar", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("$msg\n\nBookmark, riwayat, dan unduhanmu tetap tersimpan di perangkat ini.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = { TextButton({ GuestGate.openUpgrade() }) { Text("Daftar / Masuk", color = Color.White, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ GuestGate.dismiss() }) { Text("Nanti", color = Muted) } }
        )
    }
    if (GuestGate.upgrade) UpgradeDialog { GuestGate.dismiss() }
}

@Composable
private fun UpgradeDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val ready = username.isNotBlank() && email.isNotBlank() && password.length >= 8

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() }, containerColor = CardBg, shape = R6,
        title = { Text("Daftar akun", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Datamu sebagai tamu ikut terbawa ke akun baru.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(username, { username = it.filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '_' || c == '.' }.take(20) }, singleLine = true, enabled = !busy, shape = R6, label = { Text("Username") }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it.trim() }, singleLine = true, enabled = !busy, shape = R6, label = { Text("Email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it.take(128) }, singleLine = true, enabled = !busy, shape = R6, label = { Text("Password (min. 8 karakter)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), colors = fieldColors(), modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = Color.White, fontSize = 13.sp) }
                OutlinedButton({
                    busy = true; err = null
                    scope.launch {
                        try { googleSignIn(ctx, null, true); onClose() }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.googleError() } finally { busy = false }
                    }
                }, Modifier.fillMaxWidth(), enabled = !busy, shape = R6) { Text("Masuk dengan Google", color = Color.White) }
            }
        },
        confirmButton = {
            TextButton({
                busy = true; err = null
                scope.launch {
                    try {
                        Auth.save(Net.api.guestUpgrade(RegisterReq(username.trim(), email.trim(), password)).data, true)
                        Auth.pendingEntrance = false
                        onClose()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.friendly() } finally { busy = false }
                }
            }, enabled = ready && !busy) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text("Daftar", color = Color.White, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton({ onClose() }, enabled = !busy) { Text("Batal", color = Muted) } }
    )
}
