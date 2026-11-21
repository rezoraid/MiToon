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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    val verified: Boolean = false,
    /** true jika akun owner (email di OWNER_EMAILS) — bisa akses panel admin */
    val isOwner: Boolean = false,
    /** Premium Member; ditetapkan owner dari panel admin */
    val premium: Boolean = false,
    /** centang verified disembunyikan dari tampilan publik oleh pemiliknya */
    val verifiedHidden: Boolean = false,
    val readingHidden: Boolean = false,
    /** akun tamu: komentar, like, profil, dan sinkronisasi dibatasi sampai daftar */
    val guest: Boolean = false,
    /** dibisukan sampai (epoch ms); 0 = tidak. Selama dibisukan tidak bisa berkomentar (server menolak). */
    val mutedUntil: Long = 0, val muteReason: String = ""
) { val name: String get() = displayName.ifBlank { if (guest) "Tamu" else username } }
@Serializable data class AuthRes(val token: String, val user: AuthUser)
@Serializable data class GuestLimit(val key: String = "", val label: String = "")
@Serializable data class GuestInfo(val enabled: Boolean = false, val limits: List<GuestLimit> = emptyList())
@Serializable data class AuthConfig(val google: String? = null, val guest: GuestInfo? = null)
@Serializable data class LoginReq(val identifier: String, val password: String)
@Serializable data class RegisterReq(val username: String, val email: String, val password: String)
@Serializable data class GoogleReq(val idToken: String)
@Serializable data class ProfileReq(val displayName: String? = null, val username: String? = null, val bio: String? = null, val verifiedHidden: Boolean? = null, val readingHidden: Boolean? = null)

/** Sesi login. Token disimpan hanya bila "Ingat saya" dicentang; kalau tidak, hilang saat aplikasi ditutup. */
object Auth {
    private lateinit var prefs: SharedPreferences
    var token by mutableStateOf<String?>(null); private set
    var user by mutableStateOf<AuthUser?>(null); private set
    var menuOpen by mutableStateOf(false)
    val isGuest: Boolean get() = user?.guest == true
    /** Penanda satu kali: aplikasi baru saja dibuka setelah login -> animasi masuk. */
    var pendingEntrance = false
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
            level = prefs.getInt("lv", 1).coerceAtLeast(1), levelXp = prefs.getInt("lx", 0), levelNeed = prefs.getInt("ln", 40), verified = prefs.getBoolean("vf", false), isOwner = prefs.getBoolean("io", false), premium = prefs.getBoolean("pm", false), verifiedHidden = prefs.getBoolean("vh", false), readingHidden = prefs.getBoolean("rh", false), guest = prefs.getBoolean("gs", false)
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
        pendingEntrance = true
        Xp.event("daily")
        Library.scheduleSync()
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

    /** Ubah lokal saja (layar + disk) tanpa menyentuh waktu cooldown username; dasar untuk perubahan optimistik. */
    fun patchLocal(f: (AuthUser) -> AuthUser) {
        val u = f(user ?: return)
        user = u
        if (prefs.contains("t")) prefs.edit().putString("n", u.displayName).putString("b", u.bio).putInt("v", u.avatarV).also { it.putXp(u) }.apply()
    }

    private val settingsLock = kotlinx.coroutines.sync.Mutex()
    private var settingsPending = 0

    /**
     * Pengaturan yang "pasti berhasil" (toggle, foto, nama, bio): [apply] langsung tampil di layar, server dihubungi di latar belakang
     * secara berurutan (ketukan beruntun tidak saling tabrak; yang terakhir menang). Gagal -> [undo] mengembalikan nilai lama dan [onDone] menerima errornya.
     */
    fun setting(apply: (AuthUser) -> AuthUser, undo: (AuthUser) -> AuthUser, call: suspend () -> AuthUser, onDone: (Throwable?) -> Unit = {}) {
        patchLocal(apply)
        settingsPending++
        kotlinx.coroutines.MainScope().launch {
            var err: Throwable? = null
            var fresh: AuthUser? = null
            try { fresh = settingsLock.withLock { call() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { err = e; patchLocal(undo) }
            finally { settingsPending-- }
            // Samakan dengan server hanya bila tidak ada perubahan lain yang masih dalam antrean (hindari saklar "melompat balik").
            if (err == null && settingsPending == 0) fresh?.let { update(it) }
            onDone(err)
        }
    }

    private fun SharedPreferences.Editor.putXp(u: AuthUser) { putInt("xp", u.xp); putInt("lv", u.level); putInt("lx", u.levelXp); putInt("ln", u.levelNeed); putBoolean("vf", u.verified); putBoolean("io", u.isOwner); putBoolean("pm", u.premium); putBoolean("vh", u.verifiedHidden); putBoolean("rh", u.readingHidden); putBoolean("gs", u.guest) }

    private fun unlockAt(u: AuthUser): Long = if (u.usernameCooldownSec > 0) System.currentTimeMillis() + u.usernameCooldownSec * 1000 else 0L

    fun logout() {
        token = null; user = null; menuOpen = false; roleUnlocked = null; usernameUnlockAt = 0L; Xp.reset(); Library.onLogout()
        prefs.edit().clear().apply()
    }

    /** URL foto profil (null = belum punya). Parameter v = versi, supaya cache gambar ikut berganti saat foto diubah. */
    fun avatarUrl(userId: Long, version: Int): String? =
        if (version > 0 && userId > 0) "${BuildConfig.API_BASE_URL}v1/users/$userId/avatar?v=$version" else null

    /** Saat aplikasi dibuka: token ditolak server (401) -> keluar. Gagal jaringan = tetap masuk (tetap bisa baca offline). */
    suspend fun validate() {
        if (token == null) return
        try { update(Net.api.me().data); Xp.event("daily"); Library.scheduleSync() } catch (e: HttpException) { if (e.code() == 401 || e.code() == 403) logout() /* 403 = akun diblokir */ } catch (_: Exception) {}
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
