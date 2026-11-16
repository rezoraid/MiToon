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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
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
import retrofit2.http.POST
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
@Serializable data class Notice(val title: String = "", val body: String = "", val url: String? = null)

interface Api {
    @GET("v1/latest") suspend fun latest(@Query("page") page: Int = 1, @Query("limit") limit: Int = 12, @Query("type") type: String? = null): Env<List<Series>>
    @GET("v1/popular") suspend fun popular(@Query("page") page: Int = 1, @Query("limit") limit: Int = 12): Env<List<Series>>
    @GET("v1/search") suspend fun search(@Query("q") q: String, @Query("page") page: Int = 1, @Query("limit") limit: Int = 12): Env<List<Series>>
    @GET("v1/genres") suspend fun genres(): Env<List<Genre>>
    @GET("v1/genres/{slug}/series") suspend fun byGenre(@Path("slug") slug: String, @Query("page") page: Int = 1, @Query("limit") limit: Int = 12,
        @Query("sort") sort: String? = null, @Query("type") type: String? = null, @Query("status") status: String? = null): Env<List<Series>>
    @GET("v1/series/{id}") suspend fun detail(@Path("id") id: String): Env<Detail>
    @GET("v1/chapters/{id}") suspend fun chapter(@Path("id") id: String): Env<Content>
    @GET("v1/providers") suspend fun providers(): Env<List<ProviderInfo>>
    @GET("v1/announcements") suspend fun announcements(): Env<List<Notice>>
    @GET("v1/auth/config") suspend fun authConfig(): Env<AuthConfig>
    @POST("v1/auth/login") suspend fun login(@Body b: LoginReq): Env<AuthRes>
    @POST("v1/auth/register") suspend fun register(@Body b: RegisterReq): Env<AuthRes>
    @POST("v1/auth/google") suspend fun googleLogin(@Body b: GoogleReq): Env<AuthRes>
    @GET("v1/auth/me") suspend fun me(): Env<AuthUser>
    @PUT("v1/auth/me/avatar") suspend fun putAvatar(@Body jpeg: RequestBody): Env<AuthUser>
    @DELETE("v1/auth/me/avatar") suspend fun deleteAvatar(): Env<AuthUser>
    @PUT("v1/auth/me/profile") suspend fun updateProfile(@Body b: ProfileReq): Env<AuthUser>
    @POST("v1/auth/me/xp/read") suspend fun awardReadXp(@Body b: ReadXpReq): Env<AuthUser>
    @GET("v1/comments") suspend fun comments(@Query("target") target: String, @Query("limit") limit: Int = 20, @Query("before") before: Long? = null): Env<CommentPage>
    @POST("v1/comments") suspend fun addComment(@Body b: CommentReq): Env<Comment>
    @POST("v1/comments/{id}/reaction") suspend fun reactComment(@Path("id") id: Long, @Body b: ReactionReq): Env<ReactionRes>
    @DELETE("v1/comments/{id}") suspend fun deleteComment(@Path("id") id: Long): Env<Ok>
}

object Net {
    lateinit var client: OkHttpClient
    lateinit var api: Api
    /** Header gambar per provider (referer), diisi sekali dari /v1/providers. */
    var hdr by mutableStateOf<Map<String, Map<String, String>>>(emptyMap())

    private fun <V> lru(max: Int): MutableMap<String, V> = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > max
    }
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

    /** Cover seri di daftar diunduh ke disk begitu daftar tiba -> saat di-scroll, gambar muncul tanpa jeda. */
    fun preloadCovers(list: List<Series>) {
        list.filter { it.cover.isNotBlank() }.groupBy { it.provider }.forEach { (p, items) -> preloadImages(items.take(30).map { it.cover }, hdr[p].orEmpty()) }
    }

    /** Ambil chapter + 4 gambar pertamanya lebih dulu (dipanggil untuk chapter berikutnya / chapter yang kemungkinan dibuka). */
    fun prefetchChapter(id: String) {
        if (chapterCache.containsKey(id)) return
        MainScope().launch {
            runCatching { api.chapter(id).data }.onSuccess { storeChapter(id, it); preloadImages(it.images.take(4), it.imageHeaders) }
        }
    }

    /** Baca dari disk (thread IO). null bila belum ada / rusak. */
    suspend fun loadDetailDisk(id: String): Detail? = withContext(Dispatchers.IO) {
        runCatching { detailFile(id).takeIf { it.exists() }?.let { detailJson.decodeFromString(Detail.serializer(), it.readText()) } }.getOrNull()
    }

    /** Simpan ke memori (panggil dari thread utama) lalu ke disk di background; maksimal 60 seri disimpan. */
    private val warming = HashSet<String>()
    /** Ambil detail (daftar chapter) di belakang layar supaya saat kartu ditekan sudah ada di cache -> terbuka instan. */
    fun prefetchDetail(id: String) {
        if (detailCache.containsKey(id) || !warming.add(id)) return
        MainScope().launch {
            val d = loadDetailDisk(id) ?: runCatching { api.detail(id).data }.getOrNull()
            if (d != null) { detailCache[id] = d; storeDetail(id, d) }
            warming.remove(id)
        }
    }

    fun storeDetail(id: String, d: Detail) {
        detailCache[id] = d
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
    suspend fun genres(): List<Genre> = genreCache ?: api.genres().data.also { genreCache = it; writeSnap("genres", genreSer, it) }

    fun init(ctx: Context) {
        detailDir = File(ctx.cacheDir, "detail").also { it.mkdirs() }
        snapDir = File(ctx.cacheDir, "snap").also { it.mkdirs() }
        chapterDir = File(ctx.cacheDir, "chapters").also { it.mkdirs() }
        commentDir = File(ctx.cacheDir, "comments").also { it.mkdirs() }
        appCtx = ctx.applicationContext
        // Backend mengirim ETag + Cache-Control: cache HTTP 20 MB mencegah request berulang.
        // Dispatcher default hanya 5 request paralel per host -> gambar antre. Dinaikkan supaya cover/halaman chapter lebih cepat.
        val dispatcher = Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 }
        val apiHost = BuildConfig.API_BASE_URL.toHttpUrl().host
        client = OkHttpClient.Builder()
            .cache(Cache(File(ctx.cacheDir, "http"), 20L * 1024 * 1024)).dispatcher(dispatcher)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES)) // koneksi TLS dipakai ulang -> request berikutnya tanpa handshake
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(true)
            .addInterceptor { chain -> // GET yang gagal sesaat (sinyal goyang) diulang sekali, langsung
                val req = chain.request()
                try { chain.proceed(req) } catch (e: java.io.IOException) { if (req.method == "GET") chain.proceed(req) else throw e }
            }
            .addInterceptor { chain -> // token login hanya dikirim ke server API, tidak pernah ke host gambar
                val req = chain.request()
                val t = Auth.token
                chain.proceed(if (t != null && req.url.host == apiHost) req.newBuilder().header("Authorization", "Bearer $t").build() else req)
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
        Auth.init(this)
        // "Lanjutkan" instan: chapter terakhir dibaca + detail 3 seri terakhir disiapkan sejak aplikasi dibuka.
        val recent = Library.entries.values.filter { it.readAt > 0 }.sortedByDescending { it.readAt }
        recent.firstOrNull()?.lastChapterId?.let { Net.prefetchChapter(it) }
        recent.take(3).forEach { Net.prefetchDetail(it.id) }
        MainScope().launch { runCatching { Net.hdr = Net.api.providers().data.associate { it.key to it.imageHeaders } } }
        MainScope().launch { Auth.validate() }
    }

    override fun newImageLoader() = ImageLoader.Builder(this)
        .okHttpClient(Net.client.newBuilder().cache(null).build())
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .respectCacheHeaders(false) // gambar yang sudah ada di disk dipakai langsung, tanpa cek ulang ke server
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(150L * 1024 * 1024).build() }
        .allowRgb565(true).crossfade(false).build()
}
