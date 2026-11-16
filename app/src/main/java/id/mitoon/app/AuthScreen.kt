package id.mitoon.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val FieldLine = Color(0xFF4A4C53)

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Aturan sama dengan server (pesan lebih cepat tanpa round-trip). */
private fun registerError(username: String, email: String, password: String): String? = when {
    !Regex("[A-Za-z0-9_.]{3,20}").matches(username.trim()) -> "Username 3–20 karakter: huruf, angka, titik, atau garis bawah"
    !email.trim().contains(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")) -> "Format email tidak valid"
    password.length < 8 -> "Password minimal 8 karakter"
    else -> null
}

@Composable
private fun AuthField(
    label: String, value: String, onChange: (String) -> Unit, hint: String, icon: ImageVector,
    password: Boolean = false, shown: Boolean = false, onToggle: () -> Unit = {},
    keyboard: KeyboardType = KeyboardType.Text, ime: ImeAction = ImeAction.Next, onDone: () -> Unit = {}
) = Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
    Text(label.uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    val trailing: (@Composable () -> Unit)? = if (password) ({
        Text(if (shown) "Sembunyi" else "Lihat", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(R6).clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 8.dp))
    }) else null
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, shape = R6,
        placeholder = { Text(hint, color = Muted) },
        leadingIcon = { Icon(icon, null, tint = Muted) },
        trailingIcon = trailing,
        visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ime),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White, unfocusedBorderColor = FieldLine,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
            focusedContainerColor = Bg, unfocusedContainerColor = Bg
        )
    )
}

/** Layar login wajib sebelum masuk aplikasi: password atau Google. */
@Composable
fun AuthScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var register by rememberSaveable { mutableStateOf(false) }
    var identifier by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var keep by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    // Tombol Google hanya tampil bila server mengaktifkannya (GOOGLE_CLIENT_ID terisi), tanpa build ulang APK.
    val config by produceState<AuthConfig?>(null) { value = runCatching { Net.api.authConfig().data }.getOrNull() }

    fun submit() {
        if (busy) return
        msg = if (register) registerError(username, email, password)
        else if (identifier.isBlank() || password.isEmpty()) "Isi username/email dan password" else null
        if (msg != null) return
        busy = true
        scope.launch {
            try {
                val r = if (register) Net.api.register(RegisterReq(username.trim(), email.trim(), password)).data
                else Net.api.login(LoginReq(identifier.trim(), password)).data
                Auth.save(r, keep)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { msg = e.friendly() } finally { busy = false }
        }
    }

    fun google(clientId: String) {
        if (busy) return
        msg = null; busy = true
        scope.launch {
            try {
                val act = ctx.findActivity() ?: throw IllegalStateException("Activity tidak ditemukan")
                val idToken = googleIdToken(act, clientId)
                Auth.save(Net.api.googleLogin(GoogleReq(idToken)).data, keep)
            } catch (e: CancellationException) { throw e }
            catch (e: GetCredentialCancellationException) { /* dibatalkan pengguna */ }
            catch (e: GetCredentialException) { msg = "Masuk dengan Google gagal. Pastikan ada akun Google di perangkat. (${e.message ?: e.type})" }
            catch (e: Exception) { msg = e.friendly() } finally { busy = false }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding(), Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp)) {
            Column(Modifier.fillMaxWidth().background(CardBg, R6).border(1.dp, Line, R6).padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.logo), "MiToon", Modifier.size(64.dp).clip(R6))
                Text(if (register) "Daftar" else "Masuk", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                    Text(if (register) "Sudah punya akun? " else "Belum punya akun MiToon? ", color = Muted, fontSize = 14.sp)
                    Text(if (register) "Masuk." else "Daftar sekarang.", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { register = !register; msg = null })
                }

                if (register) {
                    AuthField("Username", username, { username = it }, "Username", Icons.Default.Person)
                    AuthField("Email", email, { email = it }, "Email", Icons.Default.Email, keyboard = KeyboardType.Email)
                } else {
                    AuthField("Username atau email", identifier, { identifier = it }, "Username atau email", Icons.Default.Person)
                }
                AuthField("Password", password, { password = it }, "Kata sandi", Icons.Default.Lock,
                    password = true, shown = shown, onToggle = { shown = !shown }, ime = ImeAction.Done, onDone = { submit() })

                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Muted))
                    Text("Ingat saya", color = Color.White, fontSize = 14.sp)
                }

                msg?.let {
                    Text(it, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, Color.White, R6).padding(10.dp))
                }

                Button({ submit() }, Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp), enabled = !busy, shape = R6,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black,
                        disabledContainerColor = Color(0xFF55585F), disabledContentColor = Color.Black)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    else Text(if (register) "Daftar" else "Masuk", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                val googleId = config?.google
                if (googleId != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f), color = Line)
                        Text("  atau  ", color = Muted, fontSize = 13.sp)
                        HorizontalDivider(Modifier.weight(1f), color = Line)
                    }
                    OutlinedButton({ google(googleId) }, Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp), enabled = !busy, shape = R6,
                        border = BorderStroke(1.dp, FieldLine),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, containerColor = Bg)) {
                        Text("Masuk dengan Google", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
