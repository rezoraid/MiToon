package id.mitoon.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

@Serializable data class AuthUser(val id: Long = 0, val username: String = "", val avatarV: Int = 0)
@Serializable data class AuthRes(val token: String, val user: AuthUser)
@Serializable data class AuthConfig(val google: String? = null)
@Serializable data class LoginReq(val identifier: String, val password: String)
@Serializable data class RegisterReq(val username: String, val email: String, val password: String)
@Serializable data class GoogleReq(val idToken: String)

/** Sesi login. Token disimpan hanya bila "Ingat saya" dicentang; kalau tidak, hilang saat aplikasi ditutup. */
object Auth {
    private lateinit var prefs: SharedPreferences
    var token by mutableStateOf<String?>(null); private set
    var user by mutableStateOf<AuthUser?>(null); private set
    var menuOpen by mutableStateOf(false)

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        token = prefs.getString("t", null)
        user = if (token != null) AuthUser(prefs.getLong("i", 0), prefs.getString("u", "").orEmpty(), prefs.getInt("v", 0)) else null
    }

    fun save(r: AuthRes, remember: Boolean) {
        token = r.token
        user = r.user
        prefs.edit().clear().apply {
            if (remember) { putString("t", r.token); putString("u", r.user.username); putLong("i", r.user.id); putInt("v", r.user.avatarV) }
        }.apply()
    }

    /** Data akun terbaru (mis. setelah ganti foto) tanpa mengubah token. */
    fun update(u: AuthUser) {
        user = u
        if (prefs.contains("t")) prefs.edit().putString("u", u.username).putLong("i", u.id).putInt("v", u.avatarV).apply()
    }

    fun logout() {
        token = null; user = null; menuOpen = false
        prefs.edit().clear().apply()
    }

    /** URL foto profil (null = belum punya). Parameter v = versi, supaya cache gambar ikut berganti saat foto diubah. */
    fun avatarUrl(userId: Long, version: Int): String? =
        if (version > 0 && userId > 0) "${BuildConfig.API_BASE_URL}v1/users/$userId/avatar?v=$version" else null

    /** Saat aplikasi dibuka: token ditolak server (401) -> keluar. Gagal jaringan = tetap masuk (tetap bisa baca offline). */
    suspend fun validate() {
        if (token == null) return
        try { update(Net.api.me().data) } catch (e: HttpException) { if (e.code() == 401) logout() } catch (_: Exception) {}
    }
}

private val errJson = Json { ignoreUnknownKeys = true }

/** Pesan error dari server ({"error":{"message"}}) atau pesan jaringan, siap tampil ke pengguna. */
fun Throwable.friendly(): String = when (this) {
    is HttpException -> runCatching {
        errJson.parseToJsonElement(response()?.errorBody()?.string().orEmpty()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
    }.getOrNull() ?: "Gagal (kode ${code()})"
    is java.io.IOException -> "Tidak ada koneksi internet"
    else -> message ?: "Terjadi kesalahan"
}

/** Google Sign-In (Credential Manager) -> ID token untuk diverifikasi backend. */
suspend fun googleIdToken(activity: Activity, serverClientId: String): String {
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
        .build()
    val cred = CredentialManager.create(activity).getCredential(activity, request).credential
    if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        return GoogleIdTokenCredential.createFrom(cred.data).idToken
    }
    throw IllegalStateException("Kredensial Google tidak dikenali")
}
