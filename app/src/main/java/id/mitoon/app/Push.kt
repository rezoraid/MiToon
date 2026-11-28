package id.mitoon.app

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.security.MessageDigest

/**
 * Push realtime lewat FCM. Setiap komik di bookmark = satu topic `s_<sha1(seriesId)[0..24]>`; backend cukup mengirim satu pesan
 * ke topic itu saat chapter baru terdeteksi. Tanpa login pun jalan. Tanpa google-services.json, semuanya dilewati (pengecekan berkala tetap jalan).
 */
object Push {
    private lateinit var appCtx: Context
    private lateinit var prefs: SharedPreferences
    private var ready = false

    fun topic(seriesId: String): String =
        "s_" + MessageDigest.getInstance("SHA-1").digest(seriesId.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = appCtx.getSharedPreferences("push", Context.MODE_PRIVATE)
        ready = runCatching { FirebaseApp.getApps(appCtx).isNotEmpty() || FirebaseApp.initializeApp(appCtx) != null }.getOrDefault(false)
        if (ready) {
            if (!prefs.getBoolean("all", false)) runCatching { FirebaseMessaging.getInstance().subscribeToTopic("all").addOnSuccessListener { prefs.edit().putBoolean("all", true).apply() } }
            sync(Library.entries.values.filter { it.bookmarked }.map { it.id }.toSet())
        }
    }

    @Synchronized
    fun sync(bookmarked: Set<String>) {
        if (!ready) return
        val want = bookmarked.associateBy { topic(it) }
        val have = prefs.getStringSet("topics", emptySet()).orEmpty().toSet()
        if (want.keys == have) return
        val fm = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return
        val done = have.toMutableSet()
        (want.keys - have).forEach { t -> fm.subscribeToTopic(t).addOnSuccessListener { done.add(t); save(done) } }
        (have - want.keys).forEach { t -> fm.unsubscribeFromTopic(t).addOnSuccessListener { done.remove(t); save(done) } }
    }

    /**
     * Topic pribadi `u_<id>` untuk notifikasi khusus akun (mis. masuk Top 3 mingguan). Dipanggil tiap status login berubah:
     * login = subscribe, logout / ganti akun = unsubscribe topic lama. Tanpa login (null) tidak ada topic.
     */
    @Synchronized
    fun syncUser(id: Long?) {
        if (!ready) return
        val want = id?.takeIf { it > 0 }?.let { "u_$it" }
        val have = prefs.getString("utopic", null)
        if (want == have) return
        val fm = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return
        if (have != null) fm.unsubscribeFromTopic(have).addOnSuccessListener { if (prefs.getString("utopic", null) == have) prefs.edit().remove("utopic").apply() }
        if (want != null) fm.subscribeToTopic(want).addOnSuccessListener { prefs.edit().putString("utopic", want).apply() }
    }

    @Synchronized private fun save(s: Set<String>) { prefs.edit().putStringSet("topics", s.toSet()).apply() }
}

class MiToonMessaging : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        if (d["type"] == "chatreply") { Updates.onChatReply(applicationContext, d["name"].orEmpty(), d["body"].orEmpty(), d["msgId"]?.toLongOrNull() ?: 0L); return }
        if (d["type"] == "toprank") { Updates.onTopRank(applicationContext, d["rank"]?.toIntOrNull() ?: 0, d["weekXp"]?.toIntOrNull() ?: 0); return }
        if (d["type"] == "broadcast") { Updates.onBroadcast(applicationContext, d["title"].orEmpty(), d["body"].orEmpty(), d["seriesId"]); return }
        val sid = d["seriesId"] ?: return
        Updates.onPush(applicationContext, sid, d["title"] ?: m.notification?.title.orEmpty(), d["chapter"].orEmpty(), d["chapterId"].orEmpty())
    }

    override fun onNewToken(token: String) {}
}
