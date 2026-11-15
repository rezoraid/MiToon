package id.mitoon.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    fun toggleBookmark(d: Detail) { toggleBookmark(d.id, d.title, d.cover, d.provider) }

    fun toggleBookmark(id: String, title: String, cover: String, provider: String) {
        val e = entries[id] ?: LibEntry(id, title, cover, provider)
        put(e.copy(bookmarked = !e.bookmarked))
    }

    /** Dipanggil saat chapter dibuka dari halaman detail. */
    fun open(d: Detail, c: Chap) {
        val e = entries[d.id] ?: LibEntry(d.id, d.title, d.cover, d.provider)
        put(e.copy(title = d.title, cover = d.cover, lastChapterId = c.id, lastChapterNumber = c.number,
            readAt = System.currentTimeMillis(), read = (e.read + c.id).distinct()))
    }

    /** Dipanggil reader saat chapter berhasil dimuat (termasuk lewat tombol sebelumnya/berikutnya). */
    fun advance(seriesId: String, chapterId: String, number: String) {
        val e = entries[seriesId] ?: return
        put(e.copy(lastChapterId = chapterId, lastChapterNumber = number,
            readAt = System.currentTimeMillis(), read = (e.read + chapterId).distinct()))
    }

    fun removeHistory(id: String) {
        val e = entries[id] ?: return
        put(e.copy(lastChapterId = null, lastChapterNumber = null, readAt = 0, read = emptyList()))
    }
}
