package id.mitoon.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Satu entri = satu seri yang di-bookmark dan/atau pernah dibaca (riwayat). */
@Serializable
data class LibEntry(
    val id: String, val title: String, val cover: String, val provider: String,
    val bookmarked: Boolean = false, val lastChapterId: String? = null, val lastChapterNumber: String? = null,
    val readAt: Long = 0, val read: List<String> = emptyList()
) {
    fun toSeries() = Series(id = id, provider = provider, title = title, cover = cover)
}

/** Satu komik yang dibaca seseorang (`GET v1/users/:id/reading`), diurutkan dari yang terbaru dibaca. */
@Serializable data class ReadItem(
    val seriesId: String, val title: String = "", val cover: String = "", val provider: String = "",
    val chapterId: String? = null, val chapterNumber: String? = null, val chaptersRead: Int = 0, val readAt: Long = 0
)
@Serializable data class ReadPage(val items: List<ReadItem> = emptyList(), val hasMore: Boolean = false)
/** Laporan progres baca ke server (`PUT v1/reading`): satu baris per seri, ditimpa dengan chapter terakhir. */
@Serializable data class ReadReq(
    val seriesId: String, val title: String, val cover: String, val provider: String,
    val chapterId: String, val chapterNumber: String, val chaptersRead: Int
)

/** Library lokal di perangkat (backend belum punya akun/sinkronisasi). */
object Library {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var prefs: SharedPreferences
    var entries by mutableStateOf<Map<String, LibEntry>>(emptyMap()); private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
        entries = runCatching { json.decodeFromString<List<LibEntry>>(prefs.getString("v", "[]")!!).associateBy { it.id } }.getOrDefault(emptyMap())
    }

    private fun put(e: LibEntry) {
        entries = if (!e.bookmarked && e.readAt == 0L) entries - e.id else entries + (e.id to e)
        prefs.edit().putString("v", json.encodeToString(entries.values.toList())).apply()
    }

    /** Kirim progres baca terakhir supaya tampil di profil publik. Gagal = diam saja (membaca tidak boleh terganggu). */
    private fun sync(e: LibEntry) {
        val cid = e.lastChapterId ?: return
        val num = e.lastChapterNumber ?: return
        if (Auth.token == null) return
        MainScope().launch {
            try { Net.api.reportRead(ReadReq(e.id, e.title, e.cover, e.provider, cid, num, e.read.size)) }
            catch (ex: CancellationException) { throw ex } catch (_: Exception) {}
        }
    }

    fun toggleBookmark(d: Detail) { toggleBookmark(d.id, d.title, d.cover, d.provider) }

    fun toggleBookmark(id: String, title: String, cover: String, provider: String) {
        val e = entries[id] ?: LibEntry(id, title, cover, provider)
        put(e.copy(bookmarked = !e.bookmarked))
        if (!e.bookmarked) Xp.event("bookmark", id) // baru ditambahkan ke pustaka (server: sekali per seri)
    }

    /** Dipanggil saat chapter dibuka dari halaman detail. */
    fun open(d: Detail, c: Chap) {
        val e = entries[d.id] ?: LibEntry(d.id, d.title, d.cover, d.provider)
        val n = e.copy(title = d.title, cover = d.cover, lastChapterId = c.id, lastChapterNumber = c.number,
            readAt = System.currentTimeMillis(), read = (e.read + c.id).distinct())
        put(n); sync(n)
    }

    /** Dipanggil reader saat chapter berhasil dimuat (termasuk lewat tombol sebelumnya/berikutnya). */
    fun advance(seriesId: String, chapterId: String, number: String) {
        val e = entries[seriesId] ?: return
        val n = e.copy(lastChapterId = chapterId, lastChapterNumber = number,
            readAt = System.currentTimeMillis(), read = (e.read + chapterId).distinct())
        put(n); sync(n)
    }

    fun removeHistory(id: String) {
        val e = entries[id] ?: return
        put(e.copy(lastChapterId = null, lastChapterNumber = null, readAt = 0, read = emptyList()))
    }
}
