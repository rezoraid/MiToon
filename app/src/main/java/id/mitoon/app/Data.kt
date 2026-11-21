package id.mitoon.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.ImageLoader
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PUT
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.security.MessageDigest

@Serializable data class Env<T>(val data: T)
@Serializable data class Series(
    val id: String, val provider: String = "", val title: String = "", val cover: String = "",
    val type: String? = null, val rating: Double? = null, val latestChapter: String? = null,
    val genres: List<String> = emptyList()
)
@Serializable data class Genre(val slug: String, val name: String, val count: Int = 0)
@Serializable data class Chap(val id: String, val number: String = "", val title: String = "", val date: String? = null)
@Serializable data class Detail(
    val id: String, val provider: String = "", val title: String = "", val cover: String = "",
    val type: String? = null, val status: String? = null, val rating: Double? = null, val genres: List<String> = emptyList(),
    val synopsis: String = "", val author: String = "", val chapters: List<Chap> = emptyList()
)
@Serializable data class Content(
    val seriesId: String? = null, val number: String = "", val images: List<String> = emptyList(), val prev: String? = null,
    val next: String? = null, val imageHeaders: Map<String, String> = emptyMap()
)
@Serializable data class ProviderInfo(val key: String, val imageHeaders: Map<String, String> = emptyMap())
// Endpoint opsional (belum ada di backend): bagian UI otomatis disembunyikan bila kosong/gagal.
@Serializable data class Notice(
    val id: Long = 0, val title: String = "", val body: String = "", val url: String? = null, val createdAt: Long = 0, val expiresAt: Long? = null
)

@Serializable data class AdminStats(val series: Int = 0, val chapters: Int = 0, val users: Int = 0, val comments: Int = 0, val genres: Int = 0, val banned: Int = 0, val muted: Int = 0, val views_7d: Int = 0)
@Serializable data class AdminSeriesPage(val total: Int = 0, val items: List<AdminSeries> = emptyList(), val page: Int = 1, val limit: Int = 30, val hasMore: Boolean = false)
@Serializable data class AdminSeries(
    val id: Long = 0, val provider: String = "", val ref: String = "", val unifiedId: String = "",
    val title: String = "", val cover: String = "", val url: String = "", val type: String? = null, val status: String? = null,
    val synopsis: String = "", val author: String = "", val artist: String = "", val year: Int? = null, val rating: Double? = null,
    val chapterCount: Int? = null, val latestChapter: String? = null, val chapterN: Int = 0, val genres: List<String> = emptyList(),
    val genreSlugs: List<String> = emptyList(),
    val detailFetchedAt: String? = null, val firstSeenAt: String? = null, val lastSeenAt: String? = null, val updatedAt: String? = null,
    val chapters: List<AdminChapter> = emptyList()
)
@Serializable data class AdminChapter(val id: Long = 0, val ref: String = "", val number: Double? = null, val numberText: String = "", val title: String = "", val releasedAt: String? = null)
@Serializable data class AdminSeriesPatch(
    val title: String? = null, val cover: String? = null, val url: String? = null, val type: String? = null, val status: String? = null,
    val synopsis: String? = null, val author: String? = null, val artist: String? = null, val year: Int? = null, val rating: Double? = null,
    val chapterCount: Int? = null, val latestChapter: String? = null, val genres: List<String>? = null
)
@Serializable data class AdminChapterPatch(val title: String? = null, val numberText: String? = null, val number: Double? = null)
@Serializable data class AdminUsersPage(val total: Int = 0, val items: List<AdminUser> = emptyList(), val page: Int = 1, val limit: Int = 30, val hasMore: Boolean = false)
@Serializable data class AdminComment(
    val id: Long = 0, val target: String = "", val body: String = "", val parentId: Long? = null, val createdAt: Long = 0,
    val userId: Long = 0, val username: String = "", val displayName: String = "", val avatarV: Int = 0
)
@Serializable data class AdminCommentsPage(val total: Int = 0, val items: List<AdminComment> = emptyList(), val page: Int = 1, val limit: Int = 30, val hasMore: Boolean = false)
@Serializable data class AdminUser(
    val id: Long = 0, val username: String = "", val email: String? = null, val displayName: String = "", val bio: String = "",
    val xp: Int = 0, val verified: Boolean = false, val avatarV: Int = 0, val hasAvatar: Boolean = false, val commentCount: Int = 0,
    val createdAt: String? = null, val isOwner: Boolean = false, val premium: Boolean = false,
    /** diblokir: tidak bisa masuk/memakai akun */
    val banned: Boolean = false, val banReason: String = "",
    /** dibisukan sampai (epoch ms); 0 = tidak dibisukan. Selama dibisukan tidak bisa berkomentar. */
    val mutedUntil: Long = 0, val muteReason: String = ""
)
/** Semua field opsional: hanya yang diisi yang diubah di server. `muted = true` tanpa `muteDays` = bisu permanen. */
@Serializable data class AdminUserPatch(
    val verified: Boolean? = null, val premium: Boolean? = null, val banned: Boolean? = null, val banReason: String? = null,
    val muted: Boolean? = null, val muteDays: Int? = null, val muteReason: String? = null,
    val clearBio: Boolean? = null, val clearName: Boolean? = null
)
@Serializable data class AdminDeletedCount(val deleted: Int = 0)
@Serializable data class AdminAnnounceReq(
    val title: String, val body: String? = null, val url: String? = null, val expiresAt: String? = null
)
@Serializable data class Ok(val ok: Boolean = true)

interface Api {
    @GET("v1/latest") suspend fun latest(@Query("page") page: Int = 1, @Query("limit") limit: Int = 12, @Query("type") type: String? = null): Env<List<Series>>
    @GET("v1/popular") suspend fun popular(@Query("page") page: Int = 1, @Query("limit") limit: Int = 12): Env<List<Series>>
    @GET("v1/search") suspend fun search(@Query("q") q: String, @Query("page") page: Int = 1, @Query("limit") limit: Int = 12): Env<List<Series>>
    @GET("v1/genres") suspend fun genres(): Env<List<Genre>>
    @GET("v1/genres/{slug}/series") suspend fun byGenre(@Path("slug") slug: String, @Query("page") page: Int = 1, @Query("limit") limit: Int = 12,
        @Query("sort") sort: String? = null, @Query("type") type: String? = null, @Query("status") status: String? = null): Env<List<Series>>
    @GET("v1/series/{id}") suspend fun detail(@Path("id") id: String): Env<Detail>
    @GET("v1/chapters/{id}") suspend fun chapter(@Path("id") id: String): Env<Content>
    @Headers("Cache-Control: no-cache") @GET("v1/series/{id}") suspend fun detailFresh(@Path("id") id: String, @Query("refresh") refresh: Int): Env<Detail>
    @Headers("Cache-Control: no-cache") @GET("v1/chapters/{id}") suspend fun chapterFresh(@Path("id") id: String, @Query("refresh") refresh: Int): Env<Content>
    @GET("v1/providers") suspend fun providers(): Env<List<ProviderInfo>>
    @GET("v1/announcements") suspend fun announcements(@Query("limit") limit: Int? = null): Env<List<Notice>>
    @GET("v1/notifications") suspend fun notifications(): Env<NotifPage>
    @GET("v1/notifications/unread") suspend fun notifUnread(): Env<NotifCount>
    @POST("v1/notifications/read") suspend fun notifRead(@Query("kind") kind: String? = null): Env<Ok>
    @GET("v1/auth/config") suspend fun authConfig(): Env<AuthConfig>
    @POST("v1/auth/login") suspend fun login(@Body b: LoginReq): Env<AuthRes>
    @POST("v1/auth/register") suspend fun register(@Body b: RegisterReq): Env<AuthRes>
    @POST("v1/auth/google") suspend fun googleLogin(@Body b: GoogleReq): Env<AuthRes>
    @POST("v1/auth/guest") suspend fun guestLogin(): Env<AuthRes>
    @POST("v1/auth/guest/upgrade") suspend fun guestUpgrade(@Body b: RegisterReq): Env<AuthRes>
    @GET("v1/auth/me") suspend fun me(): Env<AuthUser>
    @PATCH("v1/auth/me") suspend fun updateProfile(@Body b: ProfileReq): Env<AuthUser>
    @PUT("v1/auth/me/avatar") suspend fun putAvatar(@Body jpeg: RequestBody): Env<AuthUser>
    @DELETE("v1/auth/me/avatar") suspend fun deleteAvatar(): Env<AuthUser>
    @GET("v1/comments") suspend fun comments(@Query("target") target: String, @Query("limit") limit: Int = 20, @Query("before") before: Long? = null): Env<CommentPage>
    @GET("v1/comments/{id}/replies") suspend fun replies(@Path("id") id: Long, @Query("after") after: Long? = null, @Query("limit") limit: Int = 20): Env<CommentPage>
    @POST("v1/comments") suspend fun addComment(@Body b: CommentReq): CommentRes
    @PUT("v1/comments/{id}/vote") suspend fun voteComment(@Path("id") id: Long, @Body b: VoteReq): Env<VoteRes>
    @POST("v1/xp/event") suspend fun xpEvent(@Body b: XpReq): Env<XpRes>
    @GET("v1/xp/leaderboard") suspend fun leaderboard(@Query("limit") limit: Int = 50): Env<RankBoard>
    @DELETE("v1/auth/me") suspend fun deleteAccount(@Query("confirm") confirm: String): Env<Ok>
    @GET("v1/users/{id}") suspend fun user(@Path("id") id: Long): Env<PublicUser>
    @GET("v1/users/{id}/reading") suspend fun userReading(@Path("id") id: Long, @Query("limit") limit: Int = 30, @Query("before") before: Long? = null): Env<ReadPage>
    @POST("v1/library/sync") suspend fun librarySync(@Body b: LibSyncReq): Env<LibSyncRes>
    @PUT("v1/reading") suspend fun reportRead(@Body b: ReadReq): Env<Ok>
    @GET("v1/users/{id}/comments") suspend fun userComments(@Path("id") id: Long, @Query("limit") limit: Int = 20, @Query("before") before: Long? = null): Env<MyCommentPage>
    @GET("v1/comments/mine") suspend fun myComments(@Query("limit") limit: Int = 20, @Query("before") before: Long? = null): Env<MyCommentPage>
    @DELETE("v1/comments/{id}") suspend fun deleteComment(@Path("id") id: Long): Env<Ok>
    // ---- Admin (owner only) ----
    @GET("v1/admin/stats") suspend fun adminStats(): Env<AdminStats>
    @GET("v1/admin/series") suspend fun adminSeries(@Query("q") q: String? = null, @Query("provider") provider: String? = null, @Query("page") page: Int = 1, @Query("limit") limit: Int = 30): Env<AdminSeriesPage>
    @GET("v1/admin/series/{id}") suspend fun adminSeriesDetail(@Path("id") id: Long): Env<AdminSeries>
    @PATCH("v1/admin/series/{id}") suspend fun adminUpdateSeries(@Path("id") id: Long, @Body b: AdminSeriesPatch): Env<AdminSeries>
    @DELETE("v1/admin/series/{id}") suspend fun adminDeleteSeries(@Path("id") id: Long): Env<Ok>
    @PATCH("v1/admin/chapters/{id}") suspend fun adminUpdateChapter(@Path("id") id: Long, @Body b: AdminChapterPatch): Env<Ok>
    @DELETE("v1/admin/chapters/{id}") suspend fun adminDeleteChapter(@Path("id") id: Long): Env<Ok>
    @GET("v1/admin/users") suspend fun adminUsers(@Query("q") q: String? = null, @Query("filter") filter: String? = null, @Query("page") page: Int = 1, @Query("limit") limit: Int = 30): Env<AdminUsersPage>
    @PATCH("v1/admin/users/{id}") suspend fun adminModerateUser(@Path("id") id: Long, @Body b: AdminUserPatch): Env<AdminUser>
    @DELETE("v1/admin/users/{id}/avatar") suspend fun adminClearUserAvatar(@Path("id") id: Long): Env<AdminUser>
    @DELETE("v1/admin/users/{id}/comments") suspend fun adminDeleteUserComments(@Path("id") id: Long): Env<AdminDeletedCount>
    @DELETE("v1/admin/users/{id}") suspend fun adminDeleteUser(@Path("id") id: Long): Env<Ok>
    @GET("v1/admin/comments") suspend fun adminComments(@Query("q") q: String? = null, @Query("page") page: Int = 1, @Query("limit") limit: Int = 30): Env<AdminCommentsPage>
    @DELETE("v1/admin/comments/{id}") suspend fun adminDeleteComment(@Path("id") id: Long): Env<Ok>
    @GET("v1/admin/announcements") suspend fun adminAnnouncements(): Env<List<Notice>>
    @POST("v1/admin/announcements") suspend fun adminCreateAnnouncement(@Body b: AdminAnnounceReq): Env<Notice>
    @DELETE("v1/admin/announcements/{id}") suspend fun adminDeleteAnnouncement(@Path("id") id: Long): Env<Ok>
}

object Net {
    lateinit var client: OkHttpClient
    lateinit var api: Api
    /** Header gambar per provider (referer), diisi sekali dari /v1/providers. */
    var hdr by mutableStateOf<Map<String, Map<String, String>>>(emptyMap())

    private fun <V> lru(max: Int): MutableMap<String, V> = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > max
    }
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<Any?>>()
    private val bootAt = System.currentTimeMillis()

    /**
     * Permintaan identik yang sedang berjalan digabung jadi satu (Home + warm-up + prefetch + layar yang sama
     * dulu menembak URL yang sama 2-3x barengan). Lanjut walau pemanggil pertama dibatalkan; error diteruskan ke semua penunggu.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> shared(key: String, call: suspend () -> T): T {
        val d = inflight.computeIfAbsent(key) {
            netScope.async(start = CoroutineStart.LAZY) { try { call() } finally { inflight.remove(key) } }
        } as Deferred<T>
        d.start()
        return d.await()
    }

    suspend fun latest(page: Int = 1, limit: Int = 12, type: String? = null): List<Series> = shared("latest:$page:$limit:$type") { api.latest(page, limit, type).data }
    suspend fun popular(page: Int = 1, limit: Int = 12): List<Series> = shared("popular:$page:$limit") { api.popular(page, limit).data }
    suspend fun detail(id: String): Detail = shared("detail:$id") { api.detail(id).data }
    suspend fun chapter(id: String): Content = shared("chapter:$id") { api.chapter(id).data }
    suspend fun unread(): NotifCount = shared("unread") { api.notifUnread().data }

    // ---- cover: diperkecil & dikompres (WebP) lewat layanan resize gambar supaya unduhan jauh lebih ringan.
    // Bila host sumber gagal lewat layanan itu (mis. butuh referer), host dicatat dan berikutnya langsung diunduh apa adanya.
    private const val COVER_PROXY = "https://wsrv.nl/"
    private val thumbBlocked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun hostOf(url: String) = runCatching { java.net.URI(url.trim()).host.orEmpty() }.getOrDefault("")
    fun thumb(url: String, width: Int, q: Int = 72): String? {
        val u = url.trim()
        if (width <= 0 || !(u.startsWith("http://") || u.startsWith("https://")) || u.startsWith(COVER_PROXY) || u.endsWith(".gif", true)) return null
        val host = hostOf(u)
        if (host.isBlank() || host in thumbBlocked || u.startsWith(BuildConfig.API_BASE_URL)) return null
        return "$COVER_PROXY?url=${java.net.URLEncoder.encode(u, "UTF-8")}&w=$width&we=1&output=webp&q=$q"
    }
    fun thumbFailed(url: String) { hostOf(url).takeIf { it.isNotBlank() }?.let { thumbBlocked.add(it) } }

    // ---- ambil ulang manual (ikon refresh): maks. REFETCH_MAX kali per hari per komik / per chapter, supaya tidak membebani server ----
    const val REFETCH_MAX = 2
    private val refetchPrefs by lazy { appCtx.getSharedPreferences("refetch", Context.MODE_PRIVATE) }
    private fun dayKey() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    fun refetchUsed(key: String): Int {
        val (day, n) = (refetchPrefs.getString(key, null) ?: return 0).split('|').let { it[0] to it.getOrNull(1) }
        return if (day == dayKey()) n?.toIntOrNull() ?: 0 else 0
    }
    fun refetchLeft(key: String): Int = (REFETCH_MAX - refetchUsed(key)).coerceAtLeast(0)
    private fun markRefetch(key: String) {
        val today = dayKey()
        refetchPrefs.edit().apply {
            refetchPrefs.all.forEach { (k, v) -> if ((v as? String)?.substringBefore('|') != today) remove(k) }
            putString(key, "$today|${refetchUsed(key) + 1}")
        }.apply()
    }

    /** Ambil ulang detail + daftar chapter langsung dari sumbernya (melewati cache). Dihitung hanya bila berhasil. Gagal = exception. */
    suspend fun refetchDetail(id: String): Detail {
        val d = shared("detail!:$id") { api.detailFresh(id, 1).data }
        markRefetch("d:$id")
        withContext(Dispatchers.Main) { storeDetail(id, d) }
        return d
    }

    /** Ambil ulang gambar chapter dari sumbernya; gambar lama dibuang dari cache supaya yang rusak/diganti dimuat ulang. */
    suspend fun refetchChapter(id: String): Content {
        val c = shared("chapter!:$id") { api.chapterFresh(id, 1).data }
        markRefetch("c:$id")
        val old = (chapterCache[id] ?: readIn(chapterDir, id, Content.serializer()))?.images.orEmpty()
        withContext(Dispatchers.IO) {
            val loader = appCtx.imageLoader
            (old + c.images).distinct().forEach { u -> loader.diskCache?.remove(u); loader.memoryCache?.remove(MemoryCache.Key(u)) }
        }
        withContext(Dispatchers.Main) { storeChapter(id, c) }
        return c
    }

    @Volatile private var rankMem: Pair<String?, RankBoard>? = null
    /** Papan terakhir dari memori untuk sesi [token] (berisi `me`); null bila belum ada / akun berbeda / minggunya sudah lewat. */
    fun rankBoard(token: String?): RankBoard? = rankMem?.takeIf { it.first == token && it.second.resetAt > System.currentTimeMillis() }?.second
    suspend fun leaderboard(): RankBoard {
        val tk = Auth.token
        val r = shared("rank:$tk") { api.leaderboard().data }
        rankMem = tk to r
        writeSnap("rank-week", RankBoard.serializer(), r.copy(me = null))
        return r
    }
    /** Hangatkan papan peringkat begitu app buka (setelah layar utama jalan) supaya tab Ranking langsung terisi. */
    fun warmRanking() {
        MainScope().launch {
            delay(1200)
            if (Auth.token != null) runCatching { leaderboard() }
        }
    }

    /** Beberapa detik pertama setelah proses mulai = hanya untuk layar yang terlihat; prefetch spekulatif menunggu. */
    private const val QUIET_MS = 2500L

    /** Info dasar seri dari kartu yang diklik: dipakai untuk menampilkan halaman detail seketika. */
    val previews: MutableMap<String, Series> = lru(200)
    /** Detail lengkap yang pernah dimuat: buka ulang = langsung tampil. */
    val detailCache: MutableMap<String, Detail> = lru(40)

    // ---- cache detail di disk: bertahan setelah aplikasi ditutup, dipakai untuk tampil instan (data terbaru tetap diambil) ----
    private lateinit var detailDir: File
    private val detailJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private fun hashed(dir: File, key: String) =
        File(dir, MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    private fun detailFile(id: String) = hashed(detailDir, id)

    // ---- snapshot kecil (halaman 1 Home/Explore, genre, sorotan, pengumuman): dibaca SINKRON saat ViewModel dibuat,
    // jadi layar langsung terisi data terakhir sementara data terbaru diambil di belakang layar ----
    private lateinit var snapDir: File
    private lateinit var chapterDir: File
    private lateinit var commentDir: File
    private lateinit var appCtx: Context
    private fun <T> readIn(dir: File, key: String, ser: KSerializer<T>): T? =
        runCatching { hashed(dir, key).takeIf { it.exists() }?.let { detailJson.decodeFromString(ser, it.readText()) } }.getOrNull()
    private fun <T> writeIn(dir: File, key: String, ser: KSerializer<T>, value: T, keep: Int) {
        MainScope().launch(Dispatchers.IO) {
            runCatching {
                hashed(dir, key).writeText(detailJson.encodeToString(ser, value))
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
            }
        }
    }
    fun <T> readSnap(key: String, ser: KSerializer<T>): T? = readIn(snapDir, key, ser)
    fun <T> writeSnap(key: String, ser: KSerializer<T>, value: T) = writeIn(snapDir, key, ser, value, 80)

    // ---- chapter: daftar gambar disimpan di memori + disk (150 chapter terakhir) -> chapter yang pernah dibuka tampil seketika ----
    val chapterCache: MutableMap<String, Content> = lru(30)
    fun cachedChapter(id: String): Content? = chapterCache[id] ?: readIn(chapterDir, id, Content.serializer())?.also { chapterCache[id] = it }
    fun storeChapter(id: String, c: Content) { chapterCache[id] = c; writeIn(chapterDir, id, Content.serializer(), c, 150) }

    // ---- komentar halaman pertama per target (40 terakhir): tampil instan saat tab/sheet dibuka, dibaca di thread IO ----
    suspend fun readComments(target: String): CommentPage? = withContext(Dispatchers.IO) { readIn(commentDir, target, CommentPage.serializer()) }
    fun storeComments(target: String, p: CommentPage) = writeIn(commentDir, target, CommentPage.serializer(), p, 40)

    /** Unduh gambar ke cache disk di belakang layar (tanpa memakai memori) supaya saat di-scroll sudah siap. */
    fun preloadImages(urls: List<String>, headers: Map<String, String>) {
        val loader = appCtx.imageLoader
        urls.forEach { u ->
            loader.enqueue(ImageRequest.Builder(appCtx).data(u).apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .memoryCachePolicy(CachePolicy.DISABLED).size(1, 1).build())
        }
    }

    /**
     * Cover di LUAR layar diunduh ke disk sedikit demi sedikit (bukan 90 sekaligus) supaya tidak merebut koneksi
     * dari cover yang sedang terlihat. [skip] = jumlah item pertama yang sudah diminta langsung oleh UI.
     */
    fun preloadCovers(list: List<Series>, skip: Int = 0) {
        val todo = list.drop(skip).filter { it.cover.isNotBlank() }.take(12)
        if (todo.isEmpty()) return
        netScope.launch {
            val wait = QUIET_MS - (System.currentTimeMillis() - bootAt)
            if (wait > 0) delay(wait)
            todo.chunked(3).forEach { chunk ->
                chunk.groupBy { it.provider }.forEach { (p, items) -> preloadImages(items.map { it.cover }, hdr[p].orEmpty()) }
                delay(1500)
            }
        }
    }

    /** Ambil chapter + 8 gambar pertamanya lebih dulu (dipanggil untuk chapter berikutnya / chapter yang kemungkinan dibuka). */
    fun prefetchChapter(id: String) {
        if (Downloads.has(id)) return // sudah ada di perangkat
        if (chapterCache.containsKey(id)) {
            chapterCache[id]?.let { preloadImages(it.images.take(8), hdr[id.substringBefore(':')].orEmpty() + it.imageHeaders) }
            return
        }
        MainScope().launch {
            val wait = QUIET_MS - (System.currentTimeMillis() - bootAt)
            if (wait > 0) delay(wait)
            runCatching { chapter(id) }.onSuccess { storeChapter(id, it); preloadImages(it.images.take(8), hdr[id.substringBefore(':')].orEmpty() + it.imageHeaders) }
        }
    }

    /**
     * Hangatkan snapshot Home/Explore di background begitu app buka.
     * Setelah sekali jalan, buka Home berikutnya tampil dari disk tanpa spinner (meski belum pernah scroll).
     */
    fun warmHomeSnaps() {
        val ser = ListSerializer(Series.serializer())
        MainScope().launch {
            runCatching { latest(1) }.onSuccess {
                writeSnap("home-sorotan", ser, it)
                preloadCovers(it, skip = 9) // 9 pertama diminta langsung oleh grid Home
                it.take(6).forEach { s -> prefetchDetail(s.id) }
            }
        }
        MainScope().launch {
            runCatching { popular(1) }.onSuccess {
                writeSnap("home-populer", ser, it)
                writeSnap("home-spotlight", ser, it.take(6))
                preloadCovers(it, skip = 6) // 6 pertama = sorotan Home, diminta langsung oleh UI
                it.take(4).forEach { s -> prefetchDetail(s.id) }
            }
        }
        MainScope().launch { runCatching { genres() } }
        MainScope().launch {
            runCatching { api.announcements(50).data }.onSuccess {
                writeSnap("info-notices", ListSerializer(Notice.serializer()), it)
            }
        }
    }

    /** Baca dari disk (thread IO). null bila belum ada / rusak. */
    suspend fun loadDetailDisk(id: String): Detail? = withContext(Dispatchers.IO) {
        runCatching { detailFile(id).takeIf { it.exists() }?.let { detailJson.decodeFromString(Detail.serializer(), it.readText()) } }.getOrNull()
    }

    /** Simpan ke memori (panggil dari thread utama) lalu ke disk di background; maksimal 60 seri disimpan. */
    private val warming = HashSet<String>()
    private val prefetchGate = Semaphore(2)
    /** Ambil detail (daftar chapter) di belakang layar supaya saat kartu ditekan sudah ada di cache -> terbuka instan. Maks. 2 sekaligus, menunggu layar utama selesai dulu. */
    fun prefetchDetail(id: String) {
        if (detailCache.containsKey(id) || !warming.add(id)) return
        MainScope().launch {
            try {
                val wait = QUIET_MS - (System.currentTimeMillis() - bootAt)
                if (wait > 0) delay(wait)
                prefetchGate.withPermit {
                    if (detailCache.containsKey(id)) return@withPermit
                    val d = loadDetailDisk(id) ?: runCatching { detail(id) }.getOrNull()
                    if (d != null) { detailCache[id] = d; storeDetail(id, d) }
                }
            } finally { warming.remove(id) }
        }
    }

    /** Cover yang terbukti tampil di halaman detail (id -> URL). Dipakai kartu daftar bila cover dari daftar kosong/gagal dimuat. */
    val coverFix = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val coverGate = Semaphore(4)
    private val fixing = HashSet<String>()
    private val fixTried = HashMap<String, Long>()
    private fun validCover(u: String?) = u != null && (u.startsWith("http://") || u.startsWith("https://"))

    /**
     * Cari cover untuk kartu yang covernya kosong/gagal: cache -> disk -> server. Cache yang covernya kosong/rusak TIDAK dipercaya
     * (dulu langsung dipakai lalu berhenti -> kartu kosong selamanya). [force] = abaikan cache, ambil ulang dari server
     * (dipakai saat URL cover yang ada gagal dimuat). Dicoba ulang paling cepat tiap 20 dtk per seri.
     */
    suspend fun fixCover(id: String, force: Boolean = false, bad: String? = null) {
        if (!force && coverFix.containsKey(id)) return
        val now = System.currentTimeMillis()
        if (now - (fixTried[id] ?: 0L) < 20_000L) return
        if (!fixing.add(id)) return
        try {
            fixTried[id] = now
            coverGate.withPermit {
                var d = if (force) null else (detailCache[id] ?: loadDetailDisk(id))
                if (d == null || !validCover(d.cover) || d.cover == bad) {
                    d = try { detail(id).also { storeDetail(id, it) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { null }
                }
                d?.cover?.takeIf { validCover(it) && it != bad }?.let { coverFix[id] = it }
            }
        } finally { fixing.remove(id) }
    }

    /** URL cover di [coverFix] ternyata gagal dimuat -> buang supaya kartu bisa mencari ulang (bukan terkunci pada URL rusak). */
    fun dropCoverFix(id: String, url: String) { if (coverFix[id] == url) coverFix.remove(id) }

    fun storeDetail(id: String, d: Detail) {
        detailCache[id] = d
        d.cover.takeIf { validCover(it) }?.let { coverFix[id] = it }
        MainScope().launch(Dispatchers.IO) {
            runCatching {
                detailFile(id).writeText(detailJson.encodeToString(Detail.serializer(), d))
                detailDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
            }
        }
    }

    private var genreCache: List<Genre>? = null
    private val genreSer = ListSerializer(Genre.serializer())
    /** Genre terakhir yang tersimpan di disk (kosong bila belum pernah dimuat). */
    fun genresSnap(): List<Genre> = readSnap("genres", genreSer).orEmpty()
    suspend fun genres(): List<Genre> = genreCache ?: shared("genres") { api.genres().data }.also { genreCache = it; writeSnap("genres", genreSer, it) }

    private val hdrSer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))
    /** Header gambar (referer) per provider: disimpan di disk supaya cover langsung diminta dengan header benar sejak frame pertama. */
    suspend fun loadProviders() {
        val m = shared("providers") { api.providers().data }.associate { it.key to it.imageHeaders }
        hdr = m
        writeSnap("providers-hdr", hdrSer, m)
    }

    private val PUBLIC = listOf("v1/latest", "v1/popular", "v1/search", "v1/genres", "v1/series/", "v1/chapters/", "v1/providers", "v1/announcements")

    fun init(ctx: Context) {
        detailDir = File(ctx.cacheDir, "detail").also { it.mkdirs() }
        snapDir = File(ctx.cacheDir, "snap").also { it.mkdirs() }
        chapterDir = File(ctx.cacheDir, "chapters").also { it.mkdirs() }
        commentDir = File(ctx.cacheDir, "comments").also { it.mkdirs() }
        appCtx = ctx.applicationContext
        hdr = readSnap("providers-hdr", hdrSer).orEmpty()
        // Backend mengirim ETag + Cache-Control: cache HTTP lebih besar mencegah request berulang.
        // Dispatcher default hanya 5 request paralel per host -> gambar antre. Dinaikkan supaya cover/halaman chapter lebih cepat.
        val dispatcher = Dispatcher().apply { maxRequests = 96; maxRequestsPerHost = 24 }
        val apiUrl = BuildConfig.API_BASE_URL.toHttpUrl()
        val apiHost = apiUrl.host
        val basePath = apiUrl.encodedPath
        // Koneksi pertama ke server dibuat SATU kali: request lain menunggu sampai koneksi (TLS) siap lalu memakainya bersama (HTTP/2),
        // bukan 8-10 handshake TLS paralel saat aplikasi dibuka.
        val firstClaimed = AtomicBoolean(false)
        val connected = CountDownLatch(1)
        client = OkHttpClient.Builder()
            .cache(Cache(File(ctx.cacheDir, "http"), 40L * 1024 * 1024)).dispatcher(dispatcher)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES)) // koneksi TLS dipakai ulang -> request berikutnya tanpa handshake
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(true)
            .eventListenerFactory { _ ->
                object : EventListener() {
                    override fun connectionAcquired(call: okhttp3.Call, connection: okhttp3.Connection) { if (call.request().url.host == apiHost) connected.countDown() }
                    override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { if (call.request().url.host == apiHost) connected.countDown() }
                }
            }
            .addInterceptor { chain -> // GET yang gagal sesaat (sinyal goyang) diulang sekali, langsung
                val req = chain.request()
                try { chain.proceed(req) } catch (e: java.io.IOException) { if (req.method == "GET") chain.proceed(req) else throw e }
            }
            .addInterceptor { chain -> // token login hanya ke endpoint pribadi di server API (katalog publik tanpa token = bisa di-cache CDN)
                val req = chain.request()
                val t = Auth.token
                val rel = req.url.encodedPath.removePrefix(basePath)
                val priv = req.url.host == apiHost && PUBLIC.none { rel.startsWith(it) }
                chain.proceed(if (t != null && priv) req.newBuilder().header("Authorization", "Bearer $t").build() else req)
            }
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.host != apiHost) return@addInterceptor chain.proceed(req)
                if (firstClaimed.compareAndSet(false, true)) {
                    try { chain.proceed(req) } finally { connected.countDown() } // jawaban dari cache HTTP juga melepas antrean
                } else {
                    try { connected.await(3, TimeUnit.SECONDS) } catch (e: InterruptedException) { throw java.io.InterruptedIOException("dibatalkan") }
                    chain.proceed(req)
                }
            }.build()
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        api = Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(Api::class.java)
    }
}

class MiToonApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Net.init(this)
        Library.init(this)
        Downloads.init(this)
        Auth.init(this)
        CommentHistory.init(this)
        // Urutan penting: header gambar dulu (kecil, sekaligus membuka koneksi pertama), lalu data layar utama.
        // Prefetch spekulatif (detail/chapter lanjutan) menunggu beberapa detik di dalam Net supaya tidak berebut dengan layar yang terlihat.
        MainScope().launch { runCatching { Net.loadProviders() } }
        Net.warmHomeSnaps()
        MainScope().launch { Auth.validate() }
        Net.warmRanking()
        val recent = Library.entries.values.filter { it.readAt > 0 }.sortedByDescending { it.readAt }
        recent.firstOrNull()?.lastChapterId?.let { Net.prefetchChapter(it) }
        recent.take(3).forEach { Net.prefetchDetail(it.id) }
    }

    override fun newImageLoader() = ImageLoader.Builder(this)
        .okHttpClient(Net.client.newBuilder().cache(null)
            // Gambar punya antrean sendiri (maks. 8 paralel per host) dan timeout lebih longgar: puluhan cover paralel di jaringan lambat
            // saling berebut bandwidth sampai kena timeout 25 dtk -> kotak kosong.
            .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 8 })
            .readTimeout(40, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build())
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.30).build() }
        .respectCacheHeaders(false) // gambar yang sudah ada di disk dipakai langsung, tanpa cek ulang ke server
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(200L * 1024 * 1024).build() }
        .components {
            // GIF animasi (mascot loading): ImageDecoder di API 28+, GifDecoder di bawahnya.
            if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory())
        }
        .allowRgb565(true).crossfade(120).build()
}
