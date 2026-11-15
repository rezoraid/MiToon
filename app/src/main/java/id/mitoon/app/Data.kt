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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File

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

    private var genreCache: List<Genre>? = null
    suspend fun genres(): List<Genre> = genreCache ?: api.genres().data.also { genreCache = it }

    fun init(ctx: Context) {
        // Backend mengirim ETag + Cache-Control: cache HTTP 20 MB mencegah request berulang.
        client = OkHttpClient.Builder().cache(Cache(File(ctx.cacheDir, "http"), 20L * 1024 * 1024)).build()
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
