package id.mitoon.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Layar login: hanya Google. Tombol selalu tampil langsung (tidak menunggu respons server). */
@Composable
fun AuthScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keep by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    // Client ID diambil di latar belakang agar ketukan pertama terasa instan; bila belum siap, diambil saat tombol ditekan.
    val config by produceState<AuthConfig?>(null) { value = runCatching { Net.api.authConfig().data }.getOrNull() }

    fun google() {
        if (busy) return
        msg = null; busy = true
        scope.launch {
            try {
                val clientId = config?.google ?: Net.api.authConfig().data.google
                    ?: throw IllegalStateException("Masuk dengan Google belum diaktifkan di server")
                val act = ctx.findActivity() ?: throw IllegalStateException("Activity tidak ditemukan")
                val idToken = googleIdToken(act, clientId)
                Auth.save(Net.api.googleLogin(GoogleReq(idToken)).data, keep)
            } catch (e: CancellationException) { throw e }
            catch (e: GetCredentialCancellationException) { /* dibatalkan pengguna */ }
            catch (e: GetCredentialException) { msg = "Masuk dengan Google gagal. Pastikan ada akun Google di perangkat. (${e.message ?: e.type})" }
            catch (e: Exception) { msg = e.friendly() } finally { busy = false }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding(), Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(painterResource(R.drawable.logo), "MiToon", Modifier.size(96.dp).clip(R6))
            Text("MiToon", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 18.dp))
            Text(
                "Masuk dengan akun Google untuk mulai membaca komik favoritmu.",
                color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
            )

            Spacer(Modifier.height(40.dp))

            msg?.let {
                Text(it, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).border(1.dp, Color.White, R6).padding(10.dp))
            }

            Button(
                { google() }, Modifier.fillMaxWidth().height(52.dp), enabled = !busy, shape = R6,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White, contentColor = Color(0xFF1F1F1F),
                    disabledContainerColor = Color.White, disabledContentColor = Color(0xFF1F1F1F)
                ),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFF1F1F1F), strokeWidth = 2.dp)
                else {
                    Image(painterResource(R.drawable.ic_google), null, Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Masuk dengan Google", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                Modifier.padding(top = 10.dp).clip(R6).clickable { keep = !keep }.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(keep, { keep = it }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black, uncheckedColor = Muted))
                Text("Ingat saya", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}
