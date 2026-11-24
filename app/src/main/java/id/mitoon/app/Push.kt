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
        if (ready) sync(Library.entries.values.filter { it.bookmarked }.map { it.id }.toSet())
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

    @Synchronized private fun save(s: Set<String>) { prefs.edit().putStringSet("topics", s.toSet()).apply() }
}

class MiToonMessaging : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        val sid = d["seriesId"] ?: return
        Updates.onPush(applicationContext, sid, d["title"] ?: m.notification?.title.orEmpty(), d["chapter"].orEmpty(), d["chapterId"].orEmpty())
    }

    override fun onNewToken(token: String) {}
}
