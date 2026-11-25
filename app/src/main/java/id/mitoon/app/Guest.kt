package id.mitoon.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

object GuestGate {
    var prompt by mutableStateOf<String?>(null); private set
    var signIn by mutableStateOf(false); private set
    fun ask(message: String) { prompt = message }
    fun openUpgrade() { prompt = null; signIn = true }
    fun dismiss() { prompt = null; signIn = false }
}

const val GUEST_COMMENT = "Masuk dengan Google dulu untuk berkomentar."
const val GUEST_REPLY = "Masuk dengan Google dulu untuk membalas komentar."
const val GUEST_VOTE = "Masuk dengan Google dulu untuk memberi like."
const val GUEST_NOTIF = "Masuk dengan Google dulu untuk melihat notifikasi."

@Composable
fun GuestGateHost() {
    val ctx = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(GuestGate.signIn) {
        if (!GuestGate.signIn) return@LaunchedEffect
        try { googleSignIn(ctx, null, true); Auth.pendingEntrance = false }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { err = e.googleError() }
        finally { GuestGate.dismiss() }
    }
    GuestGate.prompt?.let { msg ->
        AlertDialog(
            onDismissRequest = { GuestGate.dismiss() }, containerColor = CardBg, shape = RoundedCornerShape(20.dp),
            title = { Text("Perlu masuk", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("$msg\n\nBookmark, riwayat, dan unduhanmu tetap tersimpan di perangkat ini.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = { TextButton({ GuestGate.openUpgrade() }) { Text("Masuk dengan Google", color = Color.White, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ GuestGate.dismiss() }) { Text("Nanti", color = Muted) } }
        )
    }
    err?.let { msg ->
        AlertDialog(
            onDismissRequest = { err = null }, containerColor = CardBg, shape = RoundedCornerShape(20.dp),
            text = { Text(msg, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = { TextButton({ err = null }) { Text("OK", color = Color.White, fontWeight = FontWeight.Bold) } }
        )
    }
}
