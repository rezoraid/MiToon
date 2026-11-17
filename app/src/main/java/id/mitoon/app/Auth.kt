package id.mitoon.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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

@Serializable data class AuthUser(
    val id: Long = 0, val username: String = "", val avatarV: Int = 0,
    /** Nama tampilan bebas; kosong = pakai username. */
    val displayName: String = "",
    /** Bio singkat (maks. 150 karakter, 3 baris); kosong = belum diisi. */
    val bio: String = "",
    /** Sisa detik sebelum username boleh diganti lagi (saat respons dibuat). 0 = boleh sekarang. */
    val usernameCooldownSec: Long = 0,
    /** XP total, level, XP terkumpul di level ini, dan XP yang dibutuhkan untuk naik level (dari server). */
    val xp: Int = 0, val level: Int = 1, val levelXp: Int = 0, val levelNeed: Int = 40,
    /** Lencana centang biru; ditetapkan server (mis. akun owner), tidak bisa diubah dari aplikasi. */
    val verified: Boolean = false
) { val name: String get() = displayName.ifBlank { username } }
@Serializable data class AuthRes(val token: String, val user: AuthUser)
@Serializable data class AuthConfig(val google: String? = null)
@Serializable data class LoginReq(val identifier: String, val password: String)
@Serializable data class RegisterReq(val username: String, val email: String, val password: String)
@Serializable data class GoogleReq(val idToken: String)
@Serializable data class ProfileReq(val displayName: String? = null, val username: String? = null, val bio: String? = null)

/** Sesi login. Token disimpan hanya bila "Ingat saya" dicentang; kalau tidak, hilang saat aplikasi ditutup. */
object Auth {
    private lateinit var prefs: SharedPreferences
    var token by mutableStateOf<String?>(null); private set
    var user by mutableStateOf<AuthUser?>(null); private set
    var menuOpen by mutableStateOf(false)
    /** Peran baru yang baru saja terbuka (level naik melewati ambang); ditampilkan sekali lalu dikosongkan. */
    var roleUnlocked by mutableStateOf<Role?>(null)
    /** Kapan (epoch ms, jam perangkat) username boleh diganti lagi; 0 = sekarang boleh. */
    var usernameUnlockAt by mutableLongStateOf(0L); private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        token = prefs.getString("t", null)
        user = if (token != null) AuthUser(
            id = prefs.getLong("i", 0), username = prefs.getString("u", "").orEmpty(), avatarV = prefs.getInt("v", 0),
            displayName = prefs.getString("n", "").orEmpty(), bio = prefs.getString("b", "").orEmpty(), xp = prefs.getInt("xp", 0),
            level = prefs.getInt("lv", 1).coerceAtLeast(1), levelXp = prefs.getInt("lx", 0), levelNeed = prefs.getInt("ln", 40), verified = prefs.getBoolean("vf", false)
        ) else null
        usernameUnlockAt = if (token != null) prefs.getLong("x", 0) else 0L
    }

    fun save(r: AuthRes, remember: Boolean) {
        token = r.token
        user = r.user
        usernameUnlockAt = unlockAt(r.user)
        prefs.edit().clear().apply {
            if (remember) { putString("t", r.token); putString("u", r.user.username); putLong("i", r.user.id); putInt("v", r.user.avatarV); putString("n", r.user.displayName); putString("b", r.user.bio); putLong("x", usernameUnlockAt); putXp(r.user) }
        }.apply()
        Xp.event("daily")
    }

    /** Data akun terbaru (mis. setelah ganti foto) tanpa mengubah token. */
    fun update(u: AuthUser) {
        val old = user
        // Level naik melewati ambang peran -> tandai agar dialog "Peran baru terbuka" tampil.
        if (old != null && old.id == u.id && roleFor(u.level).minLevel > roleFor(old.level).minLevel) roleUnlocked = roleFor(u.level)
        user = u
        usernameUnlockAt = unlockAt(u)
        if (prefs.contains("t")) prefs.edit().putString("u", u.username).putLong("i", u.id).putInt("v", u.avatarV).putString("n", u.displayName).putString("b", u.bio).putLong("x", usernameUnlockAt).also { it.putXp(u) }.apply()
    }

    private fun SharedPreferences.Editor.putXp(u: AuthUser) { putInt("xp", u.xp); putInt("lv", u.level); putInt("lx", u.levelXp); putInt("ln", u.levelNeed); putBoolean("vf", u.verified) }

    private fun unlockAt(u: AuthUser): Long = if (u.usernameCooldownSec > 0) System.currentTimeMillis() + u.usernameCooldownSec * 1000 else 0L

    fun logout() {
        token = null; user = null; menuOpen = false; roleUnlocked = null; usernameUnlockAt = 0L; Xp.reset()
        prefs.edit().clear().apply()
    }

    /** URL foto profil (null = belum punya). Parameter v = versi, supaya cache gambar ikut berganti saat foto diubah. */
    fun avatarUrl(userId: Long, version: Int): String? =
        if (version > 0 && userId > 0) "${BuildConfig.API_BASE_URL}v1/users/$userId/avatar?v=$version" else null

    /** Saat aplikasi dibuka: token ditolak server (401) -> keluar. Gagal jaringan = tetap masuk (tetap bisa baca offline). */
    suspend fun validate() {
        if (token == null) return
        try { update(Net.api.me().data); Xp.event("daily") } catch (e: HttpException) { if (e.code() == 401) logout() } catch (_: Exception) {}
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
