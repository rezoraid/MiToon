package id.mitoon.app

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import retrofit2.HttpException

// ---------------------------------------------------------------- model
@Serializable data class ChatMsg(val id: Long, val body: String, val createdAt: Long = 0, val user: CommentUser = CommentUser())
@Serializable data class ChatReq(val body: String)

/** Server hanya menyimpan (dan mengirim) sebanyak ini; klien ikut memotong tampilan ke jumlah yang sama. */
const val CHAT_LIMIT = 50
private const val CHAT_MAX_LEN = 500
private const val CHAT_POLL_MS = 4_000L
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

    private var seq = 0L

    private fun trim(l: List<ChatMsg>) = l.distinctBy { it.id }.sortedBy { it.id }.takeLast(CHAT_LIMIT)

    /** Ambil [CHAT_LIMIT] pesan terakhir. Server yang berwenang: pesan yang sudah dihapus/dipangkas di sana ikut hilang di sini. */
    suspend fun refresh() {
        try {
            rows = trim(Net.api.chat().data)
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (!loaded) error = e.friendly()
        } finally { loaded = true }
    }

    private fun me(): CommentUser {
        val u = Auth.user ?: return CommentUser()
        return CommentUser(u.id, u.username, u.avatarV, u.displayName, u.level, u.verified && !u.verifiedHidden, u.isOwner, u.premium)
    }

    fun send(text: String, onFail: (String) -> Unit) {
        val body = text.trim()
        if (body.isEmpty()) return
        val tmp = ChatMsg(id = -(++seq), body = body, createdAt = System.currentTimeMillis(), user = me())
        sendError = null
        pending = pending + tmp
        MainScope().launch {
            try {
                val res = Net.api.sendChat(ChatReq(body)).data
                rows = trim(rows + res)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                sendError = e.friendly()
                onFail(body)
            } finally { pending = pending.filter { it.id != tmp.id } }
        }
    }

    fun delete(m: ChatMsg) {
        if (m.id <= 0) return
        val before = rows
        rows = rows.filter { it.id != m.id }
        MainScope().launch {
            try { Net.api.deleteChat(m.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is HttpException && e.code() == 404) return@launch // sudah hilang di server
                rows = trim(before + rows)
                sendError = e.friendly()
            }
        }
    }

    fun clear() { rows = emptyList(); pending = emptyList(); loaded = false; error = null; sendError = null }
}

// ---------------------------------------------------------------- layar
@Composable
fun ChatScreen() {
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

    Column(Modifier.fillMaxSize()) {
        // header
        Row(Modifier.fillMaxWidth().background(CardBg).height(54.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x1FFFFFFF)), Alignment.Center) {
                Icon(Ic.Comment, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("Global Chat", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text("Menampilkan $CHAT_LIMIT pesan terakhir", color = Muted, fontSize = 11.sp, maxLines = 1)
            }
        }
        HorizontalDivider(color = Line, thickness = 1.dp)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !ChatState.loaded && chrono.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                chrono.isEmpty() && ChatState.error != null ->
                    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(ChatState.error.orEmpty(), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Text("Coba lagi", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(CircleShape).background(Color.White)
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
                            ChatBubble(m, head, mine = Auth.user?.id == m.user.id && m.user.id > 0)
                        }
                    }
                }
            }
            AnimatedVisibility(showJump, Modifier.align(Alignment.BottomEnd).padding(14.dp), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White).clickable { scope.launch { listState.animateScrollToItem(0) } }, Alignment.Center) {
                    Icon(Ic.Down, "Ke pesan terbaru", tint = Color.Black, modifier = Modifier.size(22.dp))
                }
            }
        }
        HorizontalDivider(color = Line, thickness = 1.dp)
        ChatComposer(Modifier.background(CardBg).imePadding().padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

@Composable
private fun ChatBubble(m: ChatMsg, head: Boolean, mine: Boolean) {
    val openUser = LocalOpenUser.current
    val sending = m.id <= 0
    val whenText = remember(m.createdAt) { clock(m.createdAt) }
    var menu by remember { mutableStateOf(false) }
    val canDelete = !sending && (mine || Auth.user?.isOwner == true)
    val press = Modifier.pointerInput(m.id, canDelete) { if (canDelete) detectTapGestures(onLongPress = { menu = true }) }

    Column(Modifier.fillMaxWidth().padding(top = if (head) 10.dp else 3.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (mine) {
            if (head) Text(whenText, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp, bottom = 3.dp))
            Box {
                Text(m.body, color = Color.Black, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.widthIn(max = 300.dp).then(press).clip(RoundedCornerShape(18.dp, if (head) 4.dp else 18.dp, 18.dp, 18.dp))
                        .background(if (sending) Color(0xCCFFFFFF) else Color.White).padding(horizontal = 14.dp, vertical = 9.dp))
                DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("Hapus") }, { menu = false; ChatState.delete(m) }) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (head) Avatar(m.user.id, m.user.name, m.user.avatarV, 36.dp, Modifier.clickable { if (m.user.id > 0) openUser(m.user.id) })
                else Spacer(Modifier.width(36.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    if (head) Row(Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.user.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).clickable { if (m.user.id > 0) openUser(m.user.id) })
                        StatusBadges(m.user.verified, m.user.isOwner, m.user.premium, size = 14.dp)
                        RoleBadge(roleFor(m.user.level), 16.dp, Modifier.padding(start = 6.dp))
                        LevelChip(m.user.level, Modifier.padding(start = 4.dp), size = 9)
                        Text("  ·  $whenText", color = Muted, fontSize = 11.sp, maxLines = 1)
                    }
                    Box {
                        Text(m.body, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp,
                            modifier = Modifier.widthIn(max = 300.dp).then(press).clip(RoundedCornerShape(if (head) 4.dp else 18.dp, 18.dp, 18.dp, 18.dp))
                                .background(CardBg).border(1.dp, Line, RoundedCornerShape(if (head) 4.dp else 18.dp, 18.dp, 18.dp, 18.dp))
                                .padding(horizontal = 14.dp, vertical = 9.dp))
                        DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("Hapus") }, { menu = false; ChatState.delete(m) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(modifier) {
        if (Auth.isGuest) {
            Text("Masuk dengan Google untuk ikut ngobrol", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Color(0xFF4A4C53), RoundedCornerShape(14.dp))
                    .clickable { GuestGate.ask(GUEST_CHAT) }.padding(14.dp))
            return@Column
        }
        val mutedUntil = Auth.user?.mutedUntil ?: 0L
        val muted = mutedUntil > System.currentTimeMillis()
        if (muted) Text("Kamu dibisukan ${muteUntilText(mutedUntil)} dan tidak bisa mengirim pesan.", color = Color(0xFFFFB86B), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        ChatState.sendError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { if (it.length <= CHAT_MAX_LEN) text = it }, Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(22.dp), enabled = !muted,
                placeholder = { Text("Tulis pesan…", color = Muted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF4A4C53),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                    focusedContainerColor = Bg, unfocusedContainerColor = Bg))
            val ready = text.isNotBlank() && !muted
            IconButton({ val t = text; text = ""; ChatState.send(t) { failed -> if (text.isBlank()) text = failed } }, Modifier.size(56.dp).clip(CircleShape)
                .background(if (ready) Color.White else Color(0xFF3A3C42)), enabled = ready) {
                Icon(Ic.Right, "Kirim", tint = if (ready) Color.Black else Muted, modifier = Modifier.size(24.dp))
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
