package id.mitoon.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import retrofit2.HttpException

// ---------------------------------------------------------------- model
/** Kutipan pesan yang dibalas; disimpan server sebagai cuplikan sehingga tetap ada walau pesan aslinya sudah dipangkas. */
@Serializable data class ChatReply(val id: Long, val userName: String = "", val body: String = "", val userId: Long = 0)
@Serializable data class ChatMsg(val id: Long, val body: String, val createdAt: Long = 0, val user: CommentUser = CommentUser(), val replyTo: ChatReply? = null)
@Serializable data class ChatReq(val body: String, val replyTo: Long? = null)
@Serializable data class ChatReportReq(val reason: String = "")

/** Server hanya menyimpan (dan mengirim) sebanyak ini; klien ikut memotong tampilan ke jumlah yang sama. */
const val CHAT_LIMIT = 50
private const val CHAT_MAX_LEN = 500
private const val CHAT_POLL_MS = 4_000L
private const val CHAT_PUSH_POLL_MS = 15_000L
private const val REFRESH_TIMEOUT_MS = 10_000L
private const val GROUP_GAP_MS = 5 * 60_000L

const val GUEST_CHAT = "Masuk dengan Google dulu untuk ikut ngobrol."

// ---------------------------------------------------------------- state
object ChatState {
    /** Pesan dari server, urut lama -> baru, maksimal [CHAT_LIMIT]. */
    var rows by mutableStateOf<List<ChatMsg>>(emptyList()); private set
    /** Pesan milik kita yang sedang dikirim (belum punya id server). */
    var pending by mutableStateOf<List<ChatMsg>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var sendError by mutableStateOf<String?>(null)
    /** Pesan yang sedang dibalas (diisi lewat geser ke kanan); dikosongkan setelah terkirim atau ditutup. */
    var replying by mutableStateOf<ChatMsg?>(null)

    private var seq = 0L
    private var prefs: SharedPreferences? = null
    var seenId by mutableLongStateOf(-1L); private set

    private val deleted = HashSet<Long>()
    private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val cacheSer = kotlinx.serialization.builtins.ListSerializer(ChatMsg.serializer())

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("mitoon_chat", Context.MODE_PRIVATE)
        seenId = prefs?.getLong("seen", -1L) ?: -1L
        val cached = runCatching { cacheJson.decodeFromString(cacheSer, prefs?.getString("rows", null).orEmpty()) }.getOrNull().orEmpty()
        if (cached.isNotEmpty()) { rows = trim(cached); loaded = true }
    }

    private fun persist() {
        val tail = rows.takeLast(20)
        prefs?.edit()?.putString("rows", runCatching { cacheJson.encodeToString(cacheSer, tail) }.getOrDefault(""))?.apply()
    }

    fun markSeen() {
        val n = rows.maxOfOrNull { it.id } ?: return
        if (n > seenId) { seenId = n; prefs?.edit()?.putLong("seen", n)?.apply() }
    }

    val unread: Int get() {
        if (seenId < 0) return 0
        val me = Auth.user?.id
        return rows.count { it.id > seenId && it.user.id != me }
    }

    private fun trim(l: List<ChatMsg>) = l.distinctBy { it.id }.sortedBy { it.id }.takeLast(CHAT_LIMIT)

    /** Ambil [CHAT_LIMIT] pesan terakhir. Server yang berwenang: pesan yang sudah dihapus/dipangkas di sana ikut hilang di sini. */
    suspend fun refresh() {
        try {
            val fresh = kotlinx.coroutines.withTimeout(REFRESH_TIMEOUT_MS) { Net.api.chat().data }
            rows = trim(fresh.filter { it.id !in deleted })
            deleted.retainAll(fresh.map { it.id }.toSet())
            error = null
            if (seenId < 0) markSeen()
            persist()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (rows.isEmpty()) error = "Tidak ada koneksi internet"
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (rows.isEmpty()) error = e.friendly()
        } finally { loaded = true }
    }

    private fun me(): CommentUser {
        val u = Auth.user ?: return CommentUser()
        return CommentUser(u.id, u.username, u.avatarV, u.displayName, u.level, u.verified && !u.verifiedHidden, u.isOwner, u.premium)
    }

    fun send(text: String, onFail: (String) -> Unit) {
        val body = text.trim()
        if (body.isEmpty()) return
        val target = replying?.takeIf { it.id > 0 }
        val ref = target?.let { ChatReply(it.id, it.user.name, Stickers.label(it.body).replace(Regex("\\s+"), " ").take(120), it.user.id) }
        val tmp = ChatMsg(id = -(++seq), body = body, createdAt = System.currentTimeMillis(), user = me(), replyTo = ref)
        replying = null
        sendError = null
        pending = pending + tmp
        MainScope().launch {
            try {
                val res = Net.api.sendChat(ChatReq(body, ref?.id)).data
                rows = trim(rows + res)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                sendError = e.friendly()
                if (replying == null && !(e is HttpException && e.code() == 404)) replying = target
                onFail(body)
            } finally { pending = pending.filter { it.id != tmp.id } }
        }
    }

    fun delete(m: ChatMsg) {
        if (m.id <= 0) return
        val before = rows
        deleted += m.id
        rows = rows.filter { it.id != m.id }
        persist()
        MainScope().launch {
            try { Net.api.deleteChat(m.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is HttpException && e.code() == 404) return@launch
                deleted -= m.id
                rows = trim(before.filter { it.id !in deleted } + rows)
                sendError = e.friendly()
                persist()
            }
        }
    }

    /** Laporkan pesan orang lain ke admin. */
    fun report(m: ChatMsg, onDone: (String) -> Unit) {
        if (m.id <= 0) return
        MainScope().launch {
            try { Net.api.reportChat(m.id, ChatReportReq()); onDone("Laporan terkirim. Terima kasih!") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onDone(if (e is HttpException && e.code() == 404) "Pesan sudah tidak ada" else e.friendly()) }
        }
    }

    fun clear() {
        rows = emptyList(); pending = emptyList(); replying = null; loaded = false; error = null; sendError = null
        deleted.clear(); seenId = -1L; prefs?.edit()?.remove("seen")?.remove("rows")?.apply()
    }
}

// ---------------------------------------------------------------- banner di Beranda
private val chatNamePalette = listOf(
    Color(0xFFFF8A80), Color(0xFFFFB86B), Color(0xFFFFD54F), Color(0xFF9CCC65), Color(0xFF4DD0A8),
    Color(0xFF4FC3F7), Color(0xFF7C9CFF), Color(0xFFB388FF), Color(0xFFF48FB1), Color(0xFFA1887F)
)
/** Warna khas untuk diri sendiri: dipakai di nama dan di kutipan saat pesan kita dibalas orang lain. */
val ChatOwnColor = Color(0xFFFF5C8A)

/** Satu warna per user, dipakai sama persis untuk nama di gelembung dan garis/nama di kutipan balasan. */
fun chatNameColor(userId: Long): Color {
    if (userId > 0 && Auth.user?.id == userId) return ChatOwnColor
    return chatNamePalette[((userId * 2654435761L) ushr 8).toInt().and(0x7fffffff) % chatNamePalette.size]
}

/** Cari id pengirim pesan yang dibalas: dari data balasan, lalu dari pesan yang sudah dimuat, terakhir lewat nama. */
fun chatReplyUserId(r: ChatReply): Long {
    if (r.userId > 0) return r.userId
    val all = ChatState.rows + ChatState.pending
    all.firstOrNull { it.id == r.id }?.let { return it.user.id }
    return all.firstOrNull { it.user.name == r.userName && it.user.id > 0 }?.user?.id ?: 0L
}

@Composable
private fun ChatTile(size: androidx.compose.ui.unit.Dp) {
    val r = RoundedCornerShape(size * 0.32f)
    Box(Modifier.size(size).clip(r).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFF3FA9F5)))).border(1.dp, Color(0x33FFFFFF), r), Alignment.Center) {
        Icon(Ic.Comment, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** Baris ringkas Public Chat di Beranda: pesan terbaru dan jumlah pesan yang belum dibaca. */
@Composable
fun ChatPromo(onClick: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { while (true) { ChatState.refresh(); delay(CHAT_PUSH_POLL_MS) } }
    val last = ChatState.rows.lastOrNull()
    val unread = ChatState.unread
    val online = ChatState.loaded && ChatState.error == null && last != null
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(CardBg).border(1.dp, Line, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp)) {
            ChatTile(44.dp)
            if (online) Box(Modifier.align(Alignment.BottomEnd).size(14.dp).background(CardBg, CircleShape).padding(2.5.dp).background(Color(0xFF30D158), CircleShape))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Public Chat", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            if (last != null) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(last.user.id, last.user.name, last.user.avatarV, 18.dp)
                androidx.compose.material3.Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = chatNameColor(last.user.id), fontWeight = FontWeight.Bold)) { append(last.user.name) }
                        append(": ${Stickers.label(last.body).replace(Regex("\\s+"), " ")}")
                    },
                    color = if (unread > 0) Color(0xFFE2E4EA) else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp)
                )
            } else Text(if (ChatState.error != null) "Ketuk untuk membuka obrolan" else "Belum ada pesan, sapa duluan!", color = Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
        }
        if (unread > 0) Box(
            Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).clip(CircleShape).background(Color(0xFFE5484D)).padding(horizontal = 6.dp),
            Alignment.Center
        ) { Text(if (unread > 99) "99+" else "$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
        else Icon(Ic.Right, null, tint = Muted, modifier = Modifier.size(16.dp))
    }
}

// ---------------------------------------------------------------- layar
@Composable
fun ChatScreen(onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Polling selama layar ini tampil (berhenti otomatis saat pindah tab).
    LaunchedEffect(Unit) { while (true) { ChatState.refresh(); delay(CHAT_POLL_MS) } }

    val chrono = remember(ChatState.rows, ChatState.pending) { ChatState.rows + ChatState.pending } // lama -> baru
    val newestId = chrono.lastOrNull()?.id
    val lastIsMine = chrono.lastOrNull()?.user?.id == Auth.user?.id && Auth.user != null

    // Daftar dibalik (reverseLayout): indeks 0 = pesan terbaru, menempel di bawah dekat kolom tulis.
    LaunchedEffect(newestId) {
        if (newestId != null && (listState.firstVisibleItemIndex <= 1 || lastIsMine)) listState.animateScrollToItem(0)
    }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
    LaunchedEffect(newestId) { ChatState.markSeen() }

    Column(Modifier.fillMaxSize()) {
        // header
        Row(Modifier.fillMaxWidth().background(CardBg).height(58.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Ic.Left, "Kembali", tint = Color.White) }
            Column(Modifier.padding(start = 4.dp).weight(1f)) {
                Text("Public Chat", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text("Ngobrol bareng semua pembaca", color = Muted, fontSize = 11.sp, maxLines = 1)
            }
            ChatTile(38.dp)
        }
        HorizontalDivider(color = Line, thickness = 1.dp)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatBackdrop()
            when {
                !ChatState.loaded && chrono.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                chrono.isEmpty() && ChatState.error != null ->
                    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(ChatState.error.orEmpty(), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Text("Coba lagi", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White)
                                .clickable { scope.launch { ChatState.refresh() } }.padding(horizontal = 20.dp, vertical = 9.dp))
                    }
                chrono.isEmpty() ->
                    Text("Belum ada pesan. Jadi yang pertama menyapa!", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp))
                else -> {
                    val shown = remember(chrono) {
                        chrono.mapIndexed { i, m ->
                            val prev = chrono.getOrNull(i - 1)
                            val head = prev == null || prev.user.id != m.user.id || m.createdAt - prev.createdAt > GROUP_GAP_MS
                            m to head
                        }.asReversed()
                    }
                    LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                        items(shown, key = { it.first.id }) { (m, head) ->
                            Box(Modifier.animateItem()) {
                                ChatBubble(m, head, mine = Auth.user?.id == m.user.id && m.user.id > 0,
                                    onReply = { if (Auth.isGuest) GuestGate.ask(GUEST_CHAT) else ChatState.replying = it })
                            }
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(showJump, Modifier.align(Alignment.BottomEnd).padding(14.dp), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).clickable { scope.launch { listState.animateScrollToItem(0) } }, Alignment.Center) {
                    Icon(Ic.Down, "Ke pesan terbaru", tint = Color.Black, modifier = Modifier.size(22.dp))
                }
            }
        }
        HorizontalDivider(color = Line, thickness = 1.dp)
        ChatComposer(Modifier.background(CardBg).imePadding().padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

@Composable
private fun ChatBubble(m: ChatMsg, head: Boolean, mine: Boolean, onReply: (ChatMsg) -> Unit) {
    val openUser = LocalOpenUser.current
    val sending = m.id <= 0
    val whenText = remember(m.createdAt) { clock(m.createdAt) }
    var menu by remember { mutableStateOf(false) }
    val canDelete = !sending && (mine || Auth.user?.isOwner == true)
    val press = Modifier.pointerInput(m.id, sending) { if (!sending) detectTapGestures(onLongPress = { menu = true }) }
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val isSticker = remember(m.body) { Stickers.id(m.body) != null }
    val menuUi: @Composable () -> Unit = {
        DropdownMenu(menu, { menu = false }) {
            if (!isSticker) DropdownMenuItem({ Text("Salin") }, {
                menu = false
                clip.setText(AnnotatedString(m.body))
                android.widget.Toast.makeText(ctx, "Pesan disalin", android.widget.Toast.LENGTH_SHORT).show()
            })
            if (!mine && !sending) DropdownMenuItem({ Text("Laporkan") }, {
                menu = false
                if (Auth.isGuest) GuestGate.ask("Masuk dengan Google dulu untuk melaporkan pesan.")
                else ChatState.report(m) { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
            })
            if (canDelete) DropdownMenuItem({ Text("Hapus") }, { menu = false; ChatState.delete(m) })
        }
    }

    // Geser ke kanan untuk membalas: pesan ikut bergeser, ikon balas muncul di kiri, lepas setelah melewati ambang.
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val shift = remember { Animatable(0f) }
    val triggerPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    val maxPx = triggerPx * 1.4f
    val swipe = if (sending) Modifier else Modifier.pointerInput(m.id) {
        var cur = 0f
        var fired = false
        detectHorizontalDragGestures(
            onDragStart = { cur = 0f; fired = false },
            onHorizontalDrag = { change, dx ->
                change.consume()
                cur = (cur + dx * 0.6f).coerceIn(0f, maxPx)
                scope.launch { shift.snapTo(cur) }
                if (!fired && cur >= triggerPx) { fired = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            },
            onDragEnd = { if (fired) onReply(m); scope.launch { shift.animateTo(0f, spring()) } },
            onDragCancel = { scope.launch { shift.animateTo(0f, spring()) } }
        )
    }

    Box(Modifier.fillMaxWidth().padding(top = if (head) 8.dp else 2.dp).then(swipe)) {
        val p = (shift.value / triggerPx).coerceIn(0f, 1f)
        Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(30.dp).alpha(p).clip(RoundedCornerShape(10.dp)).background(Line), Alignment.Center) {
            Icon(Ic.Reply, "Balas", tint = Color.White, modifier = Modifier.size(15.dp))
        }
        Column(Modifier.fillMaxWidth().offset { IntOffset(shift.value.roundToInt(), 0) }, horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (mine) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f, fill = false)) {
                        BubbleBody(m, mine = true, head = head, whenText = if (sending) "…" else whenText, modifier = Modifier.then(press), onUser = { if (m.user.id > 0) openUser(m.user.id) })
                        menuUi()
                    }
                    if (head) Avatar(m.user.id, m.user.name, m.user.avatarV, 32.dp, Modifier.clickable { if (m.user.id > 0) openUser(m.user.id) })
                    else Spacer(Modifier.width(32.dp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    if (head) Avatar(m.user.id, m.user.name, m.user.avatarV, 32.dp, Modifier.clickable { if (m.user.id > 0) openUser(m.user.id) })
                    else Spacer(Modifier.width(32.dp))
                    Box(Modifier.weight(1f, fill = false)) {
                        BubbleBody(m, mine = false, head = head, whenText = whenText, modifier = Modifier.then(press), onUser = { if (m.user.id > 0) openUser(m.user.id) })
                        menuUi()
                    }
                }
            }
        }
    }
}

/**
 * Gelembung ala WhatsApp iOS: rapat, sudut membulat, sudut atas yang menghadap pengirim lancip pada pesan pertama dalam kelompok,
 * dan jam menempel di ujung baris terakhir (bukan baris tersendiri) supaya pesan pendek tetap pendek.
 */
@Composable
private fun BubbleBody(m: ChatMsg, mine: Boolean, head: Boolean, whenText: String, modifier: Modifier = Modifier, onUser: () -> Unit = {}) {
    val big = 16.dp
    val tip = if (head) 6.dp else big
    val shape = if (mine) RoundedCornerShape(big, tip, big, big) else RoundedCornerShape(tip, big, big, big)
    val fg = Color.White
    val timeColor = if (mine) Color(0xFFB4B7C2) else Muted
    val sending = m.id <= 0
    val bg = if (mine) (if (sending) Color(0xB33B3F4C) else Color(0xFF3B3F4C)) else Color(0xFF23252C)
    Column(modifier.widthIn(min = 64.dp, max = 290.dp).width(androidx.compose.foundation.layout.IntrinsicSize.Max).background(bg, shape).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
        if (head) {
            // baris 1: nama + centang verified (kalau ada)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.user.name, color = chatNameColor(m.user.id), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clickable(onClick = onUser))
                if (m.user.verified || m.user.isOwner) VerifiedBadge(Modifier.padding(start = 5.dp), 14.dp)
            }
            // baris 2: badge-badge (owner, premium, peran, level)
            Row(Modifier.padding(top = 6.dp, bottom = 8.dp).heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.user.isOwner) OwnerBadge(Modifier, 15.dp)
                if (m.user.premium) PremiumBadge(Modifier, 15.dp)
                RoleBadge(roleFor(m.user.level), 18.dp)
                LevelChip(m.user.level, size = 9)
            }
        }
        m.replyTo?.let { ReplyQuote(it) }
        val sid = remember(m.body) { Stickers.id(m.body) }
        if (sid != null) StickerImage(sid, 128.dp, Modifier.padding(vertical = 2.dp)) else androidx.compose.material3.Text(linkified(m.body), color = fg, fontSize = 14.5.sp, lineHeight = 20.sp)
        Text(whenText, color = timeColor, fontSize = 10.sp, maxLines = 1, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
    }
}

/** Kutipan pesan yang dibalas: garis + nama berwarna sama dengan nama pengirimnya; pesan kita sendiri pakai warna khas. */
@Composable
private fun ReplyQuote(r: ChatReply) {
    val uid = chatReplyUserId(r)
    val mine = uid > 0 && Auth.user?.id == uid
    val c = if (uid > 0) chatNameColor(uid) else Muted
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.14f))
        .height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c))
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)) {
            Text(if (mine) "Kamu" else r.userName.ifBlank { "Pengguna" }, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Stickers.label(r.body), color = Color(0xFFD0D3DB), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChatComposer(modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(modifier) {
        if (Auth.isGuest) {
            Text("Masuk dengan Google untuk ikut ngobrol", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF4A4C53), RoundedCornerShape(16.dp))
                    .clickable { GuestGate.ask(GUEST_CHAT) }.padding(14.dp))
            return@Column
        }
        val mutedUntil = Auth.user?.mutedUntil ?: 0L
        val muted = mutedUntil > System.currentTimeMillis()
        if (muted) Text("Kamu dibisukan ${muteUntilText(mutedUntil)} dan tidak bisa mengirim pesan.", color = Color(0xFFFFB86B), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        ChatState.sendError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        val target = ChatState.replying
        LaunchedEffect(target?.id) { if (target != null) runCatching { focus.requestFocus() } }
        if (target != null) {
            val tc = chatNameColor(target.user.id)
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(tc.copy(alpha = 0.12f))
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(tc))
                Column(Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                    Text(if (Auth.user?.id == target.user.id) "Membalas dirimu" else "Membalas ${target.user.name}", color = tc, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Stickers.label(target.body).replace('\n', ' '), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ ChatState.replying = null }, Modifier.size(36.dp)) { Icon(Ic.Close, "Batal membalas", tint = Muted, modifier = Modifier.size(16.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StickerButton({ id -> ChatState.send(Stickers.token(id)) {} }, Modifier.padding(bottom = 4.dp), bare = true)
            OutlinedTextField(text, { if (it.length <= CHAT_MAX_LEN) text = it }, Modifier.weight(1f).focusRequester(focus), maxLines = 4, shape = RoundedCornerShape(16.dp), enabled = !muted,
                placeholder = { Text("Tulis pesan…", color = Muted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6A6D78), unfocusedBorderColor = Color(0xFF3A3C44),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                    focusedContainerColor = Bg, unfocusedContainerColor = Bg))
            val ready = text.isNotBlank() && !muted
            IconButton({ val t = text; text = ""; ChatState.send(t) { failed -> if (text.isBlank()) text = failed } }, Modifier.padding(bottom = 4.dp).size(48.dp), enabled = ready) {
                Icon(Ic.Right, "Kirim", tint = if (ready) Color.White else Color(0xFF5A5D68), modifier = Modifier.size(26.dp))
            }
        }
    }
}

private fun clock(ms: Long): String {
    if (ms <= 0) return ""
    val f = java.text.SimpleDateFormat("HH.mm", java.util.Locale("id"))
    val d = java.util.Date(ms)
    val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).let { it.format(d) == it.format(java.util.Date()) }
    return if (sameDay) f.format(d) else java.text.SimpleDateFormat("d MMM HH.mm", java.util.Locale("id")).format(d)
}

/**
 * Latar otomatis: gradasi tipis + pola ikon (hati, bintang, komentar, piala, dll.) yang digambar berulang dengan
 * posisi selang-seling dan rotasi acak tetap. Semua dibuat lewat kode, tanpa gambar, dan sangat samar agar teks tetap terbaca.
 */
@Composable
private fun ChatBackdrop() {
    val icons = listOf(Ic.Heart, Ic.Star, Ic.Comment, Ic.Trophy, Ic.Bell, Ic.Search, Ic.Play, Ic.Check, Ic.Home, Ic.Person)
    val painters = icons.map { androidx.compose.ui.graphics.vector.rememberVectorPainter(it) }
    val tint = androidx.compose.ui.graphics.ColorFilter.tint(Color(0x0DFFFFFF))
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF14151A), Color(0xFF191A21), Color(0xFF14151A))))
        val cell = 92.dp.toPx()
        val icon = 24.dp.toPx()
        val cols = (size.width / cell).toInt() + 2
        val rows = (size.height / cell).toInt() + 2
        for (r in 0 until rows) for (c in 0 until cols) {
            val k = r * 31 + c * 17
            val pick = (k * 7 + r) % painters.size
            val x = c * cell + if (r % 2 == 0) 0f else cell / 2f
            val y = r * cell
            val rot = ((k * 53) % 60 - 30).toFloat()
            withTransform({
                translate(x + cell / 2f - icon / 2f, y + cell / 2f - icon / 2f)
                rotate(rot, androidx.compose.ui.geometry.Offset(icon / 2f, icon / 2f))
            }) {
                with(painters[pick]) { draw(androidx.compose.ui.geometry.Size(icon, icon), colorFilter = tint) }
            }
        }
    }
}

private val URL_RE = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"]+)""")
private val LINK_COLOR = Color(0xFF6CB6FF)

/** Ubah URL di dalam pesan menjadi tautan yang bisa diketuk (dibuka di browser). Tanda baca di ujung URL tidak ikut. */
@Composable
private fun linkified(text: String): AnnotatedString {
    val uri = LocalUriHandler.current
    return remember(text) {
        if (!URL_RE.containsMatchIn(text)) return@remember AnnotatedString(text)
        buildAnnotatedString {
            var last = 0
            for (m in URL_RE.findAll(text)) {
                var raw = m.value
                while (raw.isNotEmpty() && raw.last() in ".,;:!?)]}'\"") raw = raw.dropLast(1)
                if (raw.length < 4) continue
                val start = m.range.first
                append(text.substring(last, start))
                val url = if (raw.startsWith("www.", true)) "https://$raw" else raw
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline))) { runCatching { uri.openUri(url) } }) { append(raw) }
                last = start + raw.length
            }
            append(text.substring(last))
        }
    }
}
