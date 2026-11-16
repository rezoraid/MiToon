package id.mitoon.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.ImageLoader
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
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
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
    fun <T> readSnap(key: String, ser: KSerializer<T>): T? =
        runCatching { hashed(snapDir, key).takeIf { it.exists() }?.let { detailJson.decodeFromString(ser, it.readText()) } }.getOrNull()
    fun <T> writeSnap(key: String, ser: KSerializer<T>, value: T) {
        MainScope().launch(Dispatchers.IO) {
            runCatching {
                hashed(snapDir, key).writeText(detailJson.encodeToString(ser, value))
                snapDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(80)?.forEach { it.delete() }
            }
        }
    }

    /** Baca dari disk (thread IO). null bila belum ada / rusak. */
    suspend fun loadDetailDisk(id: String): Detail? = withContext(Dispatchers.IO) {
        runCatching { detailFile(id).takeIf { it.exists() }?.let { detailJson.decodeFromString(Detail.serializer(), it.readText()) } }.getOrNull()
    }

    /** Simpan ke memori (panggil dari thread utama) lalu ke disk di background; maksimal 60 seri disimpan. */
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
        // Backend mengirim ETag + Cache-Control: cache HTTP 20 MB mencegah request berulang.
        // Dispatcher default hanya 5 request paralel per host -> gambar antre. Dinaikkan supaya cover/halaman chapter lebih cepat.
        val dispatcher = Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 }
        client = OkHttpClient.Builder().cache(Cache(File(ctx.cacheDir, "http"), 20L * 1024 * 1024)).dispatcher(dispatcher).build()
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
        MainScope().launch { runCatching { Net.hdr = Net.api.providers().data.associate { it.key to it.imageHeaders } } }
    }

    override fun newImageLoader() = ImageLoader.Builder(this)
        .okHttpClient(Net.client.newBuilder().cache(null).build())
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(150L * 1024 * 1024).build() }
        .allowRgb565(true).crossfade(false).build()
}
