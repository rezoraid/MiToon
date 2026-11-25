package id.mitoon.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class AuthUser(
    val id: Long = 0, val username: String = "", val avatarV: Int = 0,
    /** Nama tampilan bebas; kosong = pakai username. */
    val displayName: String = "",
    /** Bio singkat (maks. 150 karakter, 3 baris); kosong = belum diisi. */
    val bio: String = "",
    /** Sisa detik sebelum username boleh diganti lagi (saat respons dibuat). 0 = boleh sekarang. */
    val usernameCooldownSec: Long = 0,
    /** XP total, level, XP terkumpul di level ini, dan XP yang dibutuhkan untuk naik level (dari server). */
    val xp: Long = 0, val level: Int = 1, val levelXp: Int = 0, val levelNeed: Int = 40,
    /** Lencana centang biru; ditetapkan server (mis. akun owner), tidak bisa diubah dari aplikasi. */
    val verified: Boolean = false,
    /** true jika akun owner (email di OWNER_EMAILS) — bisa akses panel admin */
    val isOwner: Boolean = false,
    /** Premium Member; ditetapkan owner dari panel admin */
    val premium: Boolean = false,
    /** centang verified disembunyikan dari tampilan publik oleh pemiliknya */
    val verifiedHidden: Boolean = false,
    val readingHidden: Boolean = false,
    /** dibisukan sampai (epoch ms); 0 = tidak. Selama dibisukan tidak bisa berkomentar (server menolak). */
    val mutedUntil: Long = 0, val muteReason: String = ""
) { val name: String get() = displayName.ifBlank { username } }
@Serializable data class AuthRes(val token: String, val user: AuthUser)
@Serializable data class AuthConfig(val google: String? = null)
@Serializable data class GoogleReq(val idToken: String)
@Serializable data class ProfileReq(val displayName: String? = null, val username: String? = null, val bio: String? = null, val verifiedHidden: Boolean? = null, val readingHidden: Boolean? = null)

/** Token login dienkripsi AES-256-GCM dengan kunci Android Keystore (tidak bisa diekstrak) sebelum ditulis ke SharedPreferences. */
private object Vault {
    private const val ALIAS = "mitoon_session"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun isSealed(v: String) = v.startsWith(PREFIX)

    fun seal(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrNull()

    fun unseal(sealed: String): String? = runCatching {
        val b = Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

/** Sesi login. Token disimpan hanya bila "Ingat saya" dicentang; kalau tidak, hilang saat aplikasi ditutup. */
object Auth {
    private lateinit var prefs: SharedPreferences
    var token by mutableStateOf<String?>(null); private set
    var user by mutableStateOf<AuthUser?>(null); private set
    var menuOpen by mutableStateOf(false)
    /** Mode kunjungan: aplikasi dipakai tanpa login (tanpa sesi dan tanpa akun di server); semua data hanya di perangkat ini. */
    var visitor by mutableStateOf(false); private set
    /** true selama belum login (mode kunjungan). */
    val isGuest: Boolean get() = token == null
    /** Penanda satu kali: aplikasi baru saja dibuka setelah login -> animasi masuk. */
    var pendingEntrance = false
    /** Peran baru yang baru saja terbuka (level naik melewati ambang); ditampilkan sekali lalu dikosongkan. */
    var roleUnlocked by mutableStateOf<Role?>(null)
    /** Kapan (epoch ms, jam perangkat) username boleh diganti lagi; 0 = sekarang boleh. */
    var usernameUnlockAt by mutableLongStateOf(0L); private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
        val stored = prefs.getString("t", null)
        token = when {
            stored == null -> null
            Vault.isSealed(stored) -> Vault.unseal(stored)
            else -> stored.also { legacy -> Vault.seal(legacy)?.let { prefs.edit().putString("t", it).apply() } } // migrasi token lama (teks biasa) -> terenkripsi
        }
        if (stored != null && token == null) prefs.edit().clear().apply() // kunci Keystore hilang/rusak: sesi tak bisa dipulihkan, minta masuk lagi
        visitor = token == null && prefs.getBoolean("visitor", false)
        user = if (token != null) AuthUser(
            id = prefs.getLong("i", 0), username = prefs.getString("u", "").orEmpty(), avatarV = prefs.getInt("v", 0),
            displayName = prefs.getString("n", "").orEmpty(), bio = prefs.getString("b", "").orEmpty(), xp = if (prefs.contains("xpl")) prefs.getLong("xpl", 0L) else prefs.getInt("xp", 0).toLong(),
            level = prefs.getInt("lv", 1).coerceAtLeast(1), levelXp = prefs.getInt("lx", 0), levelNeed = prefs.getInt("ln", 40), verified = prefs.getBoolean("vf", false), isOwner = prefs.getBoolean("io", false), premium = prefs.getBoolean("pm", false), verifiedHidden = prefs.getBoolean("vh", false), readingHidden = prefs.getBoolean("rh", false)
        ) else null
        usernameUnlockAt = if (token != null) prefs.getLong("x", 0) else 0L
    }

    fun startVisitor() {
        visitor = true
        pendingEntrance = true
        prefs.edit().putBoolean("visitor", true).apply()
    }

    fun save(r: AuthRes, remember: Boolean) {
        visitor = false
        token = r.token
        user = r.user
        usernameUnlockAt = unlockAt(r.user)
        val sealed = if (remember) Vault.seal(r.token) else null
        prefs.edit().clear().apply {
            if (sealed != null) { putString("t", sealed); putString("u", r.user.username); putLong("i", r.user.id); putInt("v", r.user.avatarV); putString("n", r.user.displayName); putString("b", r.user.bio); putLong("x", usernameUnlockAt); putXp(r.user) }
        }.apply()
        pendingEntrance = true
        Streak.clear()
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

    private fun SharedPreferences.Editor.putXp(u: AuthUser) { putLong("xpl", u.xp); remove("xp"); putInt("lv", u.level); putInt("lx", u.levelXp); putInt("ln", u.levelNeed); putBoolean("vf", u.verified); putBoolean("io", u.isOwner); putBoolean("pm", u.premium); putBoolean("vh", u.verifiedHidden); putBoolean("rh", u.readingHidden) }

    private fun unlockAt(u: AuthUser): Long = if (u.usernameCooldownSec > 0) System.currentTimeMillis() + u.usernameCooldownSec * 1000 else 0L

    fun logout() {
        visitor = false; token = null; user = null; menuOpen = false; roleUnlocked = null; usernameUnlockAt = 0L; Xp.reset(); Streak.clear(); ChatState.clear(); Library.onLogout()
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
