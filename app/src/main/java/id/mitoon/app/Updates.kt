package id.mitoon.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class UpdateWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        Updates.check(applicationContext, notify = true)
        return Result.success()
    }
}

object Updates {
    private const val CHANNEL = "updates"
    private lateinit var prefs: SharedPreferences
    private val mutex = Mutex()
    private var lastRun = 0L

    var fresh by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var pendingOpen by mutableStateOf<String?>(null)
    var askNotif: (() -> Unit)? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        fresh = load()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chapter baru", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Pemberitahuan chapter baru dari komik di bookmark"
        })
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("updates", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun load(): Map<String, Int> =
        prefs.all.mapNotNull { (k, v) -> if (k.startsWith("n:") && v is Int && v > 0) k.removePrefix("n:") to v else null }.toMap()

    fun ensurePermission(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean("asked", false)) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("asked", true).apply()
        askNotif?.invoke()
    }

    /** Dipanggil saat halaman komik di bookmark dibuka: semua chapter yang ada dianggap sudah dilihat. */
    fun markSeen(id: String, d: Detail) {
        val newest = d.chapters.firstOrNull()?.id ?: return
        prefs.edit().putString("s:$id", newest).putString("l:$id", newest).remove("n:$id").apply()
        fresh = fresh - id
    }

    /** Dipanggil saat chapter komik dibuka. */
    fun clear(id: String) {
        if (!::prefs.isInitialized) return
        val l = prefs.getString("l:$id", null)
        prefs.edit().apply { if (l != null) putString("s:$id", l); remove("n:$id") }.apply()
        if (id in fresh) fresh = fresh - id
    }

    suspend fun checkIfStale(ctx: Context) { if (System.currentTimeMillis() - lastRun > 30 * 60_000L) check(ctx, notify = false) }

    suspend fun check(ctx: Context, notify: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            lastRun = System.currentTimeMillis()
            val found = ArrayList<Pair<LibEntry, String>>()
            for (e in Library.entries.values.filter { it.bookmarked }.take(40)) {
                val d = try { Net.api.detailFresh(e.id, 0).data } catch (c: CancellationException) { throw c } catch (_: Exception) { continue }
                val newest = d.chapters.firstOrNull() ?: continue
                val base = prefs.getString("s:${e.id}", null)
                if (base == null) { prefs.edit().putString("s:${e.id}", newest.id).putString("l:${e.id}", newest.id).apply(); continue }
                val idx = d.chapters.indexOfFirst { it.id == base }
                val count = if (idx > 0) idx else if (idx < 0 && newest.id != base) 1 else 0
                val ed = prefs.edit().putString("l:${e.id}", newest.id)
                if (count > 0) ed.putInt("n:${e.id}", count) else ed.remove("n:${e.id}")
                if (count > 0 && prefs.getString("t:${e.id}", null) != newest.id) {
                    ed.putString("t:${e.id}", newest.id)
                    found += e to newest.number
                }
                ed.apply()
            }
            fresh = load()
            if (notify && found.isNotEmpty()) notifyNew(ctx, found)
        }
    }

    /** Push FCM masuk: tampilkan notifikasi + badge, dan catat supaya pengecekan berkala tidak mengulang notifikasi yang sama. */
    fun onPush(ctx: Context, seriesId: String, title: String, number: String, chapterId: String) {
        if (!::prefs.isInitialized) init(ctx)
        val dup = chapterId.isNotBlank() && prefs.getString("t:$seriesId", null) == chapterId
        if (!dup) {
            val ed = prefs.edit()
            if (chapterId.isNotBlank()) ed.putString("t:$seriesId", chapterId)
            ed.putInt("n:$seriesId", prefs.getInt("n:$seriesId", 0) + 1).apply()
            fresh = load()
        }
        val nm = NotificationManagerCompat.from(ctx)
        if (dup || !nm.areNotificationsEnabled()) return
        val open = Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP; putExtra("series", seriesId) }
        val pi = PendingIntent.getActivity(ctx, seriesId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notif).setAutoCancel(true).setContentIntent(pi)
            .setContentTitle(title.ifBlank { "Chapter baru" }).setContentText(if (number.isBlank()) "Chapter baru sudah tersedia" else "Chapter $number sudah tersedia").build()
        runCatching { nm.notify(seriesId.hashCode(), n) }
    }

    private fun notifyNew(ctx: Context, found: List<Pair<LibEntry, String>>) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val first = found.first().first
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (found.size == 1) putExtra("series", first.id)
        }
        val pi = PendingIntent.getActivity(ctx, found.size, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notif).setAutoCancel(true).setContentIntent(pi)
        if (found.size == 1) b.setContentTitle(first.title).setContentText("Chapter ${found[0].second} sudah tersedia")
        else {
            b.setContentTitle("${found.size} komik punya chapter baru").setContentText(found.joinToString(", ") { it.first.title })
            b.setStyle(NotificationCompat.InboxStyle().also { s -> found.take(6).forEach { (e, n) -> s.addLine("${e.title} - Ch. $n") } })
        }
        runCatching { nm.notify(1001, b.build()) }
    }
}
