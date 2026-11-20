package id.mitoon.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Satu entri = satu seri yang di-bookmark dan/atau pernah dibaca (riwayat). */
@Serializable
data class LibEntry(
    val id: String, val title: String, val cover: String, val provider: String,
    val bookmarked: Boolean = false, val lastChapterId: String? = null, val lastChapterNumber: String? = null,
    val readAt: Long = 0, val read: List<String> = emptyList(),
    val cleared: Boolean = false, val updatedAt: Long = 0
) {
    val live get() = bookmarked || readAt > 0
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

@Serializable data class LibSyncReq(val since: Long, val entries: List<LibEntry>)
@Serializable data class LibSyncRes(val now: Long = 0, val hasMore: Boolean = false, val entries: List<LibEntry> = emptyList())

/** Pustaka (bookmark + chapter dibaca): disimpan lokal dan disinkronkan ke akun supaya sama di semua perangkat. */
object Library {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var prefs: SharedPreferences
    private val scope = MainScope()
    private val mutex = Mutex()
    private var job: Job? = null
    private var all: Map<String, LibEntry> = emptyMap()
    private var owner = 0L
    private var cursor = 0L
    private var lastPush = 0L
    var entries by mutableStateOf<Map<String, LibEntry>>(emptyMap()); private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
        all = runCatching { json.decodeFromString<List<LibEntry>>(prefs.getString("v", "[]")!!).associateBy { it.id } }.getOrDefault(emptyMap())
        owner = prefs.getLong("owner", 0); cursor = prefs.getLong("cursor", 0); lastPush = prefs.getLong("push", 0)
        entries = all.filterValues { it.live }
    }

    private fun persist() {
        prefs.edit().putString("v", json.encodeToString(all.values.toList())).putLong("owner", owner).putLong("cursor", cursor).putLong("push", lastPush).apply()
    }

    private fun publish() { entries = all.filterValues { it.live } }

    private fun put(e: LibEntry) {
        all = all + (e.id to e.copy(updatedAt = System.currentTimeMillis()))
        publish(); persist(); scheduleSync()
    }

    private fun merge(a: LibEntry, b: LibEntry): LibEntry {
        val (older, newer) = if (a.updatedAt > b.updatedAt) b to a else a to b
        val reset = newer.cleared
        val latest = if (reset) newer else if (older.readAt > newer.readAt) older else newer
        return LibEntry(
            id = newer.id, title = newer.title.ifBlank { older.title }, cover = newer.cover.ifBlank { older.cover }, provider = newer.provider,
            bookmarked = newer.bookmarked, lastChapterId = latest.lastChapterId, lastChapterNumber = latest.lastChapterNumber,
            readAt = if (reset) newer.readAt else maxOf(older.readAt, newer.readAt),
            read = if (reset) newer.read else (older.read + newer.read).distinct().takeLast(3000),
            cleared = reset, updatedAt = maxOf(older.updatedAt, newer.updatedAt)
        )
    }

    fun scheduleSync() {
        if (Auth.token == null) return
        job?.cancel()
        job = scope.launch { delay(2500); syncNow() }
    }

    /** Dipanggil setelah login / aplikasi dibuka. Gagal = diam saja; dicoba lagi pada perubahan berikutnya. */
    suspend fun syncNow() = mutex.withLock {
        if (Auth.token == null) return@withLock
        val uid = Auth.user?.id ?: return@withLock
        if (owner != uid) {
            if (owner != 0L) all = emptyMap()
            owner = uid; cursor = 0; lastPush = 0
            publish(); persist()
        }
        val started = System.currentTimeMillis()
        try {
            var pending = all.values.filter { lastPush == 0L || it.updatedAt > lastPush }
            var since = cursor
            var more: Boolean
            do {
                val batch = pending.take(200); pending = pending.drop(200)
                val r = Net.api.librarySync(LibSyncReq(since, batch)).data
                var next = all
                for (s in r.entries) {
                    val l = next[s.id]
                    val m = if (l == null) s else merge(l, s)
                    if (m != l) next = next + (m.id to m)
                }
                if (next !== all) { all = next; publish() }
                since = r.now
                more = r.hasMore || pending.isNotEmpty()
            } while (more)
            cursor = since; lastPush = started
            all = all.filterValues { it.live || it.updatedAt > started }
            publish(); persist()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { persist() }
    }

    /** Kirim progres baca terakhir supaya tampil di profil publik. Gagal = diam saja (membaca tidak boleh terganggu). */
    private fun sync(e: LibEntry) {
        val cid = e.lastChapterId ?: return
        val num = e.lastChapterNumber ?: return
        if (Auth.token == null) return
        scope.launch {
            try { Net.api.reportRead(ReadReq(e.id, e.title, e.cover, e.provider, cid, num, e.read.size)) }
            catch (ex: CancellationException) { throw ex } catch (_: Exception) {}
        }
    }

    fun onLogout() {
        job?.cancel()
        all = emptyMap(); owner = 0; cursor = 0; lastPush = 0
        publish(); persist()
    }

    fun toggleBookmark(d: Detail) { toggleBookmark(d.id, d.title, d.cover, d.provider) }

    fun toggleBookmark(id: String, title: String, cover: String, provider: String) {
        val e = all[id] ?: LibEntry(id, title, cover, provider)
        put(e.copy(bookmarked = !e.bookmarked))
        if (!e.bookmarked) Xp.event("bookmark", id)
    }

    fun open(d: Detail, c: Chap) {
        val e = all[d.id] ?: LibEntry(d.id, d.title, d.cover, d.provider)
        val n = e.copy(title = d.title, cover = d.cover, lastChapterId = c.id, lastChapterNumber = c.number,
            readAt = System.currentTimeMillis(), read = (e.read + c.id).distinct(), cleared = false)
        put(n); sync(n)
    }

    fun advance(seriesId: String, chapterId: String, number: String) {
        val e = entries[seriesId] ?: return
        val n = e.copy(lastChapterId = chapterId, lastChapterNumber = number,
            readAt = System.currentTimeMillis(), read = (e.read + chapterId).distinct(), cleared = false)
        put(n); sync(n)
    }

    fun removeHistory(id: String) {
        val e = all[id] ?: return
        put(e.copy(lastChapterId = null, lastChapterNumber = null, readAt = 0, read = emptyList(), cleared = true))
    }
}
