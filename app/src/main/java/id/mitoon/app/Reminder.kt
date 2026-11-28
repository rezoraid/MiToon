package id.mitoon.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

private const val REM_WIB_MS = 7 * 3_600_000L
private const val REM_DAY_MS = 86_400_000L

/** Aplikasi sedang tampil (ada Activity yang started)? Dipakai agar notifikasi tidak muncul untuk hal yang sedang dilihat. */
object Fg {
    @Volatile var started = 0
    val on: Boolean get() = started > 0
}

/**
 * Pengingat streak: sekali sehari pukul 20.00 WIB (streak dihitung per hari WIB), hanya bila streak masih ada tetapi hari ini belum membaca.
 * Berjalan lokal lewat WorkManager (tanpa backend); angka terbaru diambil dari server bila bisa, kalau gagal memakai data di perangkat.
 */
object StreakReminder {
    private const val WORK = "streak-reminder"
    private const val HOUR_WIB = 20
    private const val NOTIF_ID = 3000
    private lateinit var prefs: SharedPreferences

    /** Pengaturan pengguna; bawaan hidup. */
    var enabled by mutableStateOf(true); private set

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("reminder", Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("on", true)
    }

    private fun delayToNextMs(): Long {
        val wibNow = System.currentTimeMillis() + REM_WIB_MS
        var target = wibNow - wibNow % REM_DAY_MS + HOUR_WIB * 3_600_000L
        if (target <= wibNow) target += REM_DAY_MS
        return target - wibNow
    }

    private fun enqueue(ctx: Context, policy: ExistingPeriodicWorkPolicy) {
        val req = PeriodicWorkRequestBuilder<StreakReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayToNextMs(), TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, policy, req)
    }

    /** Dipanggil saat aplikasi dibuka: jadwal yang sudah ada dibiarkan (KEEP), bila mati dipastikan tidak ada jadwal. */
    fun schedule(ctx: Context) {
        if (enabled) enqueue(ctx.applicationContext, ExistingPeriodicWorkPolicy.KEEP)
        else WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        prefs.edit().putBoolean("on", on).apply()
        if (on) enqueue(ctx.applicationContext, ExistingPeriodicWorkPolicy.UPDATE)
        else WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK)
    }

    suspend fun run(ctx: Context) {
        if (!enabled || Auth.token == null || Auth.isGuest) return
        try { Streak.set(Net.api.streak().data) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        Streak.refreshDay()
        val n = Streak.current
        if (n <= 0 || Streak.hot) return
        val day = (System.currentTimeMillis() + REM_WIB_MS) / REM_DAY_MS
        if (prefs.getLong("last", -1L) == day) return
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = "Streak $n harimu menunggu. Baca satu chapter sebelum tengah malam (WIB) supaya tidak putus."
        val notif = NotificationCompat.Builder(ctx, Updates.SOCIAL).setSmallIcon(R.drawable.ic_notif).setAutoCancel(true).setContentIntent(pi)
            .setContentTitle("Apimu belum menyala hari ini").setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).build()
        if (runCatching { nm.notify(NOTIF_ID, notif) }.isSuccess) prefs.edit().putLong("last", day).apply()
    }
}

class StreakReminderWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        StreakReminder.run(applicationContext)
        return Result.success()
    }
}
