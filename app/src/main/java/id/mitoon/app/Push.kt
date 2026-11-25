package id.mitoon.app

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.security.MessageDigest

/**
 * Push realtime lewat FCM.
 *  - komik di bookmark → topic `s_<sha1(seriesId)[0..24]>` (chapter baru)
 *  - topic `all` → broadcast admin
 *  - user login → topic `u_<userId>` (balasan komentar)
 * Tanpa google-services.json, semuanya dilewati.
 */
object Push {
    private lateinit var appCtx: Context
    private lateinit var prefs: SharedPreferences
    private var ready = false

    fun topic(seriesId: String): String =
        "s_" + MessageDigest.getInstance("SHA-1").digest(seriesId.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    fun userTopic(userId: Long): String = "u_$userId"

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = appCtx.getSharedPreferences("push", Context.MODE_PRIVATE)
        ready = runCatching { FirebaseApp.getApps(appCtx).isNotEmpty() || FirebaseApp.initializeApp(appCtx) != null }.getOrDefault(false)
        if (ready) {
            if (!prefs.getBoolean("all", false)) runCatching { FirebaseMessaging.getInstance().subscribeToTopic("all").addOnSuccessListener { prefs.edit().putBoolean("all", true).apply() } }
            sync(Library.entries.values.filter { it.bookmarked }.map { it.id }.toSet())
            // subscribe topic user bila sudah login
            Auth.user?.id?.takeIf { it > 0 }?.let { syncUser(it) }
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

    /** Subscribe topic `u_<id>` saat login; unsubscribe topic user lama saat ganti akun / logout. */
    @Synchronized
    fun syncUser(userId: Long?) {
        if (!ready) return
        val fm = runCatching { FirebaseMessaging.getInstance() }.getOrNull() ?: return
        val prev = prefs.getLong("uid", 0L)
        val next = userId?.takeIf { it > 0 } ?: 0L
        if (prev == next) return
        if (prev > 0) runCatching { fm.unsubscribeFromTopic(userTopic(prev)) }
        if (next > 0) runCatching { fm.subscribeToTopic(userTopic(next)) }
        prefs.edit().putLong("uid", next).apply()
    }

    @Synchronized private fun save(s: Set<String>) { prefs.edit().putStringSet("topics", s.toSet()).apply() }
}

class MiToonMessaging : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        when (d["type"]) {
            "broadcast" -> Updates.onBroadcast(applicationContext, d["title"].orEmpty(), d["body"].orEmpty(), d["seriesId"])
            "reply" -> Updates.onReply(
                applicationContext,
                d["title"].orEmpty(),
                d["body"].orEmpty(),
                d["target"].orEmpty(),
                d["commentId"]?.toLongOrNull() ?: 0L,
                d["parentId"]?.toLongOrNull()
            )
            else -> {
                val sid = d["seriesId"] ?: return
                Updates.onPush(applicationContext, sid, d["title"] ?: m.notification?.title.orEmpty(), d["chapter"].orEmpty(), d["chapterId"].orEmpty())
            }
        }
    }

    override fun onNewToken(token: String) {}
}
