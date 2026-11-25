package id.mitoon.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch


@Composable
fun AdminScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Ringkasan", "Komik", "User", "Komentar", "Chat", "Informasi", "Push")
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Ic.Left, "Kembali", tint = Color.White) }
            Text("Panel Admin", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
        HorizontalDivider(color = Line)
        ScrollableTabRow(tab, containerColor = CardBg, contentColor = Accent, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) })
            }
        }
        when (tab) {
            0 -> AdminStatsTab()
            1 -> AdminSeriesTab()
            2 -> AdminUsersTab()
            3 -> AdminCommentsTab()
            4 -> AdminChatTab()
            5 -> AdminAnnounceTab()
            6 -> AdminPushTab()
        }
    }
}

@Composable
private fun AdminStatsTab() {
    var stats by remember { mutableStateOf<AdminStats?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun load() {
        loading = true; err = null
        scope.launch {
            runCatching { Net.api.adminStats().data }
                .onSuccess { stats = it; loading = false }
                .onFailure { err = it.friendly(); loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = Accent)
        else if (err != null) Text(err!!, color = Color(0xFFFF6B6B))
        else stats?.let { s ->
            Text("Kontrol penuh database & konten", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Seri", s.series.toString(), Modifier.weight(1f))
                StatCard("Chapter", s.chapters.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("User", s.users.toString(), Modifier.weight(1f))
                StatCard("Komentar", s.comments.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Genre", s.genres.toString(), Modifier.weight(1f))
                StatCard("Views 7h", s.views_7d.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Diblokir", s.banned.toString(), Modifier.weight(1f))
                StatCard("Dibisukan", s.muted.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            TextButton({ load() }) { Icon(Ic.Refresh, null, tint = Accent); Spacer(Modifier.width(6.dp)); Text("Refresh", color = Accent) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, mod: Modifier) {
    Column(mod.clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(14.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AdminSeriesTab() {
    var q by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var data by remember { mutableStateOf<AdminSeriesPage?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var editId by remember { mutableStateOf<Long?>(null) }
    fun load(p: Int = 1) {
        loading = true; err = null; page = p
        scope.launch {
            runCatching { Net.api.adminSeries(q = q.ifBlank { null }, page = p).data }
                .onSuccess { data = it; loading = false }
                .onFailure { err = it.friendly(); loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    if (editId != null) {
        AdminSeriesEditScreen(editId!!, { editId = null; load(page) })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Cari judul / ref…", color = Muted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = CardLine, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
            )
            IconButton({ load(1) }) { Icon(Ic.Search, null, tint = Accent) }
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        else if (err != null) Text(err!!, color = Color(0xFFFF6B6B), modifier = Modifier.padding(16.dp))
        else {
            val items = data?.items.orEmpty()
            Text("Total: ${data?.total ?: 0}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(items, key = { it.id }) { s ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(R6).border(1.dp, CardLine, R6).background(CardBg)
                            .clickable { editId = s.id }.padding(12.dp)
                    ) {
                        Text(s.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text("${s.provider} · ${s.status ?: "?"} · ${s.chapterN} ch", color = Muted, fontSize = 12.sp)
                        if (s.genres.isNotEmpty()) Text(s.genres.joinToString(", "), color = Accent, fontSize = 11.sp, maxLines = 1)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ if (page > 1) load(page - 1) }, enabled = page > 1) { Text("Prev", color = Accent) }
                        Text("Hal $page", color = Muted)
                        TextButton({ if (data?.hasMore == true) load(page + 1) }, enabled = data?.hasMore == true) { Text("Next", color = Accent) }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminSeriesEditScreen(id: Long, onBack: () -> Unit) {
    var s by remember { mutableStateOf<AdminSeries?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var fixing by remember { mutableStateOf(false) }
    var fixLog by remember { mutableStateOf<String?>(null) }
    val broken = remember { mutableStateListOf<Long>() }
    var title by remember { mutableStateOf("") }
    var synopsis by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var genres by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    fun load() {
        loading = true
        scope.launch {
            runCatching { Net.api.adminSeriesDetail(id).data }
                .onSuccess {
                    s = it
                    title = it.title; synopsis = it.synopsis; author = it.author; artist = it.artist
                    cover = it.cover; status = it.status ?: ""; type = it.type ?: ""
                    genres = it.genres.joinToString(", "); year = it.year?.toString() ?: ""
                    loading = false
                }
                .onFailure { err = it.friendly(); loading = false }
        }
    }
    LaunchedEffect(id) { load() }
    fun save() {
        saving = true; msg = null
        scope.launch {
            val patch = AdminSeriesPatch(
                title = title.ifBlank { null },
                synopsis = synopsis,
                author = author,
                artist = artist,
                cover = cover.ifBlank { null },
                status = status.ifBlank { null },
                type = type.ifBlank { null },
                year = year.toIntOrNull(),
                genres = genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }
            )
            runCatching { Net.api.adminUpdateSeries(id, patch).data }
                .onSuccess { s = it; msg = "Tersimpan"; saving = false }
                .onFailure { msg = it.friendly(); saving = false }
        }
    }
    fun fixAll() {
        val list = s?.chapters?.filter { !it.fixed } ?: return
        if (list.isEmpty()) { fixLog = "Semua chapter sudah tersimpan"; return }
        fixing = true; broken.clear()
        scope.launch {
            var ok = 0
            var done = 0
            val queue = java.util.concurrent.ConcurrentLinkedQueue(list)
            fixLog = "Memeriksa 0/${list.size}…"
            List(2) {
                launch {
                    while (true) {
                        val ch = queue.poll() ?: break
                        val r = runCatching { Net.slowApi.adminFixChapter(ch.id).data }.getOrNull()
                        if (r?.status == "ok") {
                            ok++
                            s = s?.let { cur -> cur.copy(chapters = cur.chapters.map { if (it.id == ch.id) it.copy(fixed = true) else it }) }
                        } else broken.add(ch.id)
                        done++
                        fixLog = "Memeriksa $done/${list.size} · tersimpan $ok · masih eror ${broken.size}"
                    }
                }
            }.forEach { it.join() }
            fixLog = "Selesai: $ok tersimpan ke DB & cache, ${broken.size} masih eror"
            fixing = false
        }
    }
    fun delete() {
        scope.launch {
            runCatching { Net.api.adminDeleteSeries(id) }
                .onSuccess { onBack() }
                .onFailure { msg = it.friendly() }
        }
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Ic.Left, null, tint = Color.White) }
            Text("Edit Komik", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton({ save() }, enabled = !saving) { Text(if (saving) "…" else "Simpan", color = Accent, fontWeight = FontWeight.Bold) }
        }
        HorizontalDivider(color = Line)
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        else if (err != null) Text(err!!, color = Color(0xFFFF6B6B), modifier = Modifier.padding(16.dp))
        else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (msg != null) Text(msg!!, color = if (msg == "Tersimpan") Color(0xFF6BCB77) else Color(0xFFFF6B6B))
                Text("ID: ${s?.unifiedId} · DB #${s?.id}", color = Muted, fontSize = 12.sp)
                Field("Judul", title) { title = it }
                Field("Sinopsis", synopsis, single = false) { synopsis = it }
                Field("Author", author) { author = it }
                Field("Artist", artist) { artist = it }
                Field("Cover URL", cover) { cover = it }
                Field("Status (ongoing/completed/hiatus)", status) { status = it }
                Field("Type", type) { type = it }
                Field("Tahun", year) { year = it }
                Field("Genre (pisah koma)", genres) { genres = it }
                Spacer(Modifier.height(8.dp))
                Text("Chapter (${s?.chapters?.size ?: 0})", color = Color.White, fontWeight = FontWeight.Bold)
                TextButton({ fixAll() }, enabled = !fixing) {
                    if (fixing) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Icon(Ic.Refresh, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (fixing) "Memperbaiki…" else "Perbaiki chapter eror", color = Accent, fontWeight = FontWeight.Bold)
                }
                fixLog?.let { Text(it, color = Muted, fontSize = 12.sp) }
                s?.chapters?.take(30)?.forEach { ch ->
                    val mark = if (ch.fixed) "✓ " else if (ch.id in broken) "✗ " else "• "
                    Text("$mark${ch.numberText} ${ch.title}".trim(), color = if (ch.fixed) Color(0xFF6BCB77) else if (ch.id in broken) Color(0xFFFF6B6B) else Muted, fontSize = 12.sp)
                }
                if ((s?.chapters?.size ?: 0) > 30) Text("… dan ${(s?.chapters?.size ?: 0) - 30} lainnya", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(16.dp))
                TextButton({ delete() }) {
                    Icon(Ic.Trash, null, tint = Color(0xFFFF6B6B))
                    Spacer(Modifier.width(6.dp))
                    Text("Hapus seri dari DB", color = Color(0xFFFF6B6B))
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, single: Boolean = true, supporting: String? = null, isError: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        modifier = Modifier.fillMaxWidth(),
        singleLine = single,
        minLines = if (single) 1 else 3,
        maxLines = if (single) 1 else 8,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = CardLine,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLabelColor = Accent, unfocusedLabelColor = Muted
        )
    )
}

private val UserFilters = listOf("" to "Semua", "banned" to "Diblokir", "muted" to "Dibisukan", "verified" to "Verified", "premium" to "Premium", "avatar" to "Berfoto", "owner" to "Owner")
private val RedText = Color(0xFFFF6B6B)
private val AmberText = Color(0xFFFFB86B)

/** Dialog konfirmasi aksi berbahaya. */
@Composable
private fun AdminConfirm(title: String, text: String, confirm: String, onOk: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel, containerColor = CardBg, shape = RoundedCornerShape(20.dp),
        title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
        text = { Text(text, color = Muted) },
        confirmButton = { TextButton(onOk) { Text(confirm, color = RedText, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onCancel) { Text("Batal", color = Muted) } }
    )
}

/** Dialog alasan (+ pilihan lama bisu bila [durations] diisi). onSubmit(alasan, hari?) — hari null = permanen. */
@Composable
private fun AdminReasonDialog(title: String, confirm: String, durations: List<Pair<String, Int?>>?, onSubmit: (String, Int?) -> Unit, onCancel: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    var pick by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onCancel, containerColor = CardBg, shape = RoundedCornerShape(20.dp),
        title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (durations != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    durations.forEachIndexed { i, d ->
                        AdminChip(d.first, pick == i, Modifier.weight(1f)) { pick = i }
                    }
                }
                Field("Alasan (opsional)", reason) { if (it.length <= 200) reason = it }
            }
        },
        confirmButton = { TextButton({ onSubmit(reason.trim(), durations?.getOrNull(pick)?.second) }) { Text(confirm, color = RedText, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onCancel) { Text("Batal", color = Muted) } }
    )
}

@Composable
private fun AdminChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.clip(R6).then(if (on) Modifier.background(Accent) else Modifier.border(1.dp, CardLine, R6))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
private fun ActionRow(label: String, color: Color = Color.White, onClick: () -> Unit) {
    Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(R6).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp))
}

private sealed interface UserDialog {
    data class Ban(val u: AdminUser) : UserDialog
    data class Mute(val u: AdminUser) : UserDialog
    data class Photo(val u: AdminUser) : UserDialog
    data class Xp(val u: AdminUser) : UserDialog
    data class Chat(val u: AdminUser) : UserDialog
    data class Comments(val u: AdminUser) : UserDialog
    data class Delete(val u: AdminUser) : UserDialog
    data class Logout(val u: AdminUser) : UserDialog
    data object LogoutAll : UserDialog
}

private fun commentTargetLabel(t: String): String = when {
    t.startsWith("c:") -> "Chapter · ${t.removePrefix("c:").substringAfterLast(':').take(24)}"
    t.startsWith("s:") -> "Komik · ${t.removePrefix("s:").substringAfterLast(':').take(24)}"
    else -> t.take(32)
}

/** Semua komentar dari semua pengguna (terbaru dulu), dengan pencarian dan hapus per komentar. */
@Composable
private fun AdminCommentsTab() {
    val openUser = LocalOpenUser.current
    var q by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<AdminComment>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var infoBad by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<AdminComment?>(null) }
    val scope = rememberCoroutineScope()
    fun load(p: Int = 1) {
        loading = true; err = null
        scope.launch {
            runCatching { Net.api.adminComments(q = q.trim().ifBlank { null }, page = p).data }
                .onSuccess { r -> items = if (p == 1) r.items else items + r.items; total = r.total; hasMore = r.hasMore; page = p }
                .onFailure { err = it.friendly() }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    delete?.let { c ->
        AdminConfirm("Hapus komentar?", "Komentar @${c.username} dihapus permanen" + if (c.parentId == null) " beserta balasannya." else ".", "Hapus", {
            delete = null
            scope.launch {
                runCatching { Net.api.adminDeleteComment(c.id) }
                    .onSuccess { load(1); info = "Komentar dihapus"; infoBad = false }
                    .onFailure { info = it.friendly(); infoBad = true }
            }
        }, { delete = null })
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Cari isi komentar / username…", color = Muted) }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = CardLine, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
            )
            IconButton({ load(1) }) { Icon(Ic.Search, null, tint = Accent) }
        }
        info?.let { Text(it, color = if (infoBad) RedText else Color(0xFF6BCB77), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        err?.let { Text(it, color = RedText, modifier = Modifier.padding(16.dp)) }
        Text("Total: $total komentar", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(items, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(12.dp), verticalAlignment = Alignment.Top) {
                    Avatar(c.userId, c.username, c.avatarV, 38.dp, Modifier.clickable { openUser(c.userId) })
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(c.displayName.ifBlank { c.username } + "  @${c.username}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(c.body, color = Color(0xFFD7D8DD), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                        Text((if (c.parentId != null) "Balasan · " else "") + commentTargetLabel(c.target) + " · " + agoText(c.createdAt), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton({ delete = c }, Modifier.size(36.dp)) { Icon(Ic.Trash, "Hapus", tint = RedText) }
                }
            }
            item("more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 2.dp)
                        hasMore -> TextButton({ load(page + 1) }) { Text("Muat lagi", color = Accent) }
                        items.isEmpty() && err == null -> Text("Belum ada komentar.", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminUsersTab() {
    val openUser = LocalOpenUser.current
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var data by remember { mutableStateOf<AdminUsersPage?>(null) }
    var items by remember { mutableStateOf<List<AdminUser>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var infoBad by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<UserDialog?>(null) }
    val scope = rememberCoroutineScope()
    fun load(p: Int = 1) {
        loading = true; err = null; page = p
        scope.launch {
            runCatching { Net.api.adminUsers(q = q.ifBlank { null }, filter = filter.ifBlank { null }, sort = sort.ifBlank { null }, page = p).data }
                .onSuccess { data = it; items = it.items; loading = false }
                .onFailure { err = it.friendly(); loading = false }
        }
    }
    /** Jalankan satu aksi moderasi; hasilnya (user terbaru) menggantikan barisnya tanpa memuat ulang daftar. */
    fun act(done: String, call: suspend () -> AdminUser) {
        if (busy) return
        busy = true; info = null
        scope.launch {
            runCatching { call() }
                .onSuccess { n -> items = items.map { if (it.id == n.id) n else it }; info = done; infoBad = false }
                .onFailure { info = it.friendly(); infoBad = true }
            busy = false
        }
    }
    LaunchedEffect(filter, sort) { load() }

    dialog?.let { d ->
        val close = { dialog = null }
        when (d) {
            is UserDialog.Ban -> AdminReasonDialog("Blokir @${d.u.username}?", "Blokir", null, { r, _ ->
                close(); act("@${d.u.username} diblokir") { Net.api.adminModerateUser(d.u.id, AdminUserPatch(banned = true, banReason = r.ifBlank { null })).data }
            }, close)
            is UserDialog.Mute -> AdminReasonDialog("Bisukan @${d.u.username}?", "Bisukan",
                listOf("1 hari" to 1, "7 hari" to 7, "30 hari" to 30, "Permanen" to null), { r, days ->
                close(); act("@${d.u.username} dibisukan") { Net.api.adminModerateUser(d.u.id, AdminUserPatch(muted = true, muteDays = days, muteReason = r.ifBlank { null })).data }
            }, close)
            is UserDialog.Xp -> AdminXpDialog(d.u, { patch ->
                close(); act("Level/EXP @${d.u.username} diperbarui") { Net.api.adminSetXp(d.u.id, patch).data }
            }, close)
            is UserDialog.Chat -> AdminConfirm("Hapus semua chat user?", "Semua pesan Global Chat @${d.u.username} dihapus.", "Hapus", {
                close(); act("Chat @${d.u.username} dihapus") { Net.api.adminDeleteUserChat(d.u.id); d.u }
            }, close)
            is UserDialog.Photo -> AdminConfirm("Hapus foto profil?", "Foto @${d.u.username} dihapus dan kembali ke inisial.", "Hapus", {
                close(); act("Foto @${d.u.username} dihapus") { Net.api.adminClearUserAvatar(d.u.id).data }
            }, close)
            is UserDialog.Comments -> AdminConfirm("Hapus semua komentar?", "Semua komentar dan balasan @${d.u.username} (${d.u.commentCount}) dihapus permanen.", "Hapus semua", {
                close(); act("Komentar @${d.u.username} dihapus") { Net.api.adminDeleteUserComments(d.u.id); d.u.copy(commentCount = 0) }
            }, close)
            is UserDialog.Logout -> AdminConfirm("Paksa keluar?", "@${d.u.username} akan keluar dari semua perangkat dan harus masuk lagi.", "Keluarkan", {
                close()
                if (!busy) {
                    busy = true; info = null
                    scope.launch {
                        runCatching { Net.api.adminLogoutUser(d.u.id) }
                            .onSuccess { info = "@${d.u.username} dikeluarkan dari semua perangkat"; infoBad = false }
                            .onFailure { info = it.friendly(); infoBad = true }
                        busy = false
                    }
                }
            }, close)
            is UserDialog.LogoutAll -> AdminConfirm("Keluarkan semua pengguna?", "Semua akun (kecuali akun admin yang sedang dipakai) keluar dari semua perangkat dan harus masuk lagi.", "Keluarkan semua", {
                close()
                if (!busy) {
                    busy = true; info = null
                    scope.launch {
                        runCatching { Net.api.adminLogoutAll().data }
                            .onSuccess { info = "${it.revoked} akun dikeluarkan dari semua perangkat"; infoBad = false }
                            .onFailure { info = it.friendly(); infoBad = true }
                        busy = false
                    }
                }
            }, close)
            is UserDialog.Delete -> AdminConfirm("Hapus akun?", "Akun @${d.u.username} beserta komentar, XP, dan datanya dihapus permanen. Tidak bisa dibatalkan.", "Hapus akun", {
                close()
                busy = true
                scope.launch {
                    runCatching { Net.api.adminDeleteUser(d.u.id) }
                        .onSuccess { items = items.filter { it.id != d.u.id }; info = "Akun @${d.u.username} dihapus"; infoBad = false; openId = null }
                        .onFailure { info = it.friendly(); infoBad = true }
                    busy = false
                }
            }, close)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Cari nama / username / email…", color = Muted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = CardLine, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
            )
            IconButton({ load(1) }) { Icon(Ic.Search, null, tint = Accent) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UserFilters.forEach { (key, label) -> AdminChip(label, filter == key) { filter = key } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Urutkan:", color = Muted, fontSize = 12.sp)
            AdminChip("Terbaru", sort == "") { sort = "" }
            AdminChip("EXP tertinggi", sort == "xp") { sort = "xp" }
        }
        TextButton({ dialog = UserDialog.LogoutAll }, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Keluarkan semua pengguna (logout semua sesi)", color = AmberText, fontSize = 13.sp) }
        info?.let { Text(it, color = if (infoBad) RedText else Color(0xFF6BCB77), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        else if (err != null) Text(err!!, color = RedText, modifier = Modifier.padding(16.dp))
        else {
            Text("Total: ${data?.total ?: 0}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(items, key = { it.id }) { u ->
                    val open = openId == u.id
                    val mutedNow = u.mutedUntil > System.currentTimeMillis()
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(R6).border(1.dp, if (u.banned) RedText.copy(alpha = 0.6f) else CardLine, R6).background(CardBg)) {
                        Row(Modifier.fillMaxWidth().clickable { openId = if (open) null else u.id }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u.id, u.username, u.avatarV, 44.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(u.displayName.ifBlank { u.username }, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    StatusBadges(u.verified, u.isOwner, u.premium, size = 14.dp)
                                }
                                Text("@${u.username} · Lv ${u.level} · XP ${u.xp} · ${u.commentCount} komentar", color = Muted, fontSize = 12.sp)
                                if (u.email != null) Text(u.email, color = Muted, fontSize = 11.sp)
                                if (u.banned) Text("DIBLOKIR" + if (u.banReason.isNotBlank()) ": ${u.banReason}" else "", color = RedText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                if (mutedNow) Text("DIBISUKAN ${muteUntilText(u.mutedUntil)}" + if (u.muteReason.isNotBlank()) " · ${u.muteReason}" else "", color = AmberText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (open) {
                            HorizontalDivider(color = Line)
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Centang verified", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Switch(u.verified, { v -> act(if (v) "Verified diberikan" else "Verified dicabut") { Net.api.adminModerateUser(u.id, AdminUserPatch(verified = v)).data } },
                                        enabled = !busy && !u.isOwner, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                                }
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Premium Member", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Switch(u.premium, { v -> act(if (v) "Premium diberikan" else "Premium dicabut") { Net.api.adminModerateUser(u.id, AdminUserPatch(premium = v)).data } },
                                        enabled = !busy, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
                                }
                                ActionRow("Lihat profil") { openUser(u.id) }
                                ActionRow("Atur level / EXP (Lv ${u.level} · ${u.xp} XP)", Accent) { dialog = UserDialog.Xp(u) }
                                if (u.hasAvatar) ActionRow("Hapus foto profil") { dialog = UserDialog.Photo(u) }
                                if (u.displayName.isNotBlank()) ActionRow("Reset nama tampilan") { act("Nama @${u.username} direset") { Net.api.adminModerateUser(u.id, AdminUserPatch(clearName = true)).data } }
                                if (u.bio.isNotBlank()) ActionRow("Hapus bio") { act("Bio @${u.username} dihapus") { Net.api.adminModerateUser(u.id, AdminUserPatch(clearBio = true)).data } }
                                if (!u.isOwner) {
                                    if (mutedNow) ActionRow("Buka bisu", AmberText) { act("@${u.username} boleh berkomentar lagi") { Net.api.adminModerateUser(u.id, AdminUserPatch(muted = false)).data } }
                                    else ActionRow("Bisukan (tidak bisa komentar)", AmberText) { dialog = UserDialog.Mute(u) }
                                    if (u.banned) ActionRow("Buka blokir", Color(0xFF6BCB77)) { act("Blokir @${u.username} dibuka") { Net.api.adminModerateUser(u.id, AdminUserPatch(banned = false)).data } }
                                    else ActionRow("Blokir akun", RedText) { dialog = UserDialog.Ban(u) }
                                    ActionRow("Hapus semua pesan chat", RedText) { dialog = UserDialog.Chat(u) }
                                    if (u.commentCount > 0) ActionRow("Hapus semua komentar", RedText) { dialog = UserDialog.Comments(u) }
                                    ActionRow("Paksa keluar dari semua perangkat", AmberText) { dialog = UserDialog.Logout(u) }
                                    ActionRow("Hapus akun permanen", RedText) { dialog = UserDialog.Delete(u) }
                                }
                                Text("Bergabung: ${u.createdAt?.take(10) ?: "-"} · ID ${u.id}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ if (page > 1) load(page - 1) }, enabled = page > 1) { Text("Prev", color = Accent) }
                        Text("Hal $page", color = Muted)
                        TextButton({ if (data?.hasMore == true) load(page + 1) }, enabled = data?.hasMore == true) { Text("Next", color = Accent) }
                    }
                }
            }
        }
    }
}

private const val INFO_TITLE_MAX = 120
private const val INFO_BODY_MAX = 1500

/** Lama berlaku informasi: label -> jumlah hari (0 = tanpa batas). */
private val InfoLifetimes = listOf("Selamanya" to 0, "1 hari" to 1, "7 hari" to 7, "30 hari" to 30)

private fun isoUtc(ms: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

@Composable
private fun LifetimeChips(days: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Berlaku", color = Muted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoLifetimes.forEach { (label, d) ->
                val on = days == d
                Text(label, color = if (on) OnAccent else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(R6).background(if (on) Accent else Color.Transparent).border(1.dp, if (on) Accent else CardLine, R6)
                        .clickable { onPick(d) }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun PublishButton(enabled: Boolean, sending: Boolean, done: Boolean, label: String, onClick: () -> Unit) {
    Button(
        onClick, Modifier.fillMaxWidth().height(46.dp), enabled = enabled, shape = R6,
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent, disabledContainerColor = CardLine, disabledContentColor = Muted)
    ) {
        if (sending) CircularProgressIndicator(Modifier.size(18.dp), OnAccent, strokeWidth = 2.dp)
        else Text(if (done) "Terkirim ✓" else label, fontWeight = FontWeight.Bold)
    }
}

/** Satu baris di daftar aktif (Informasi): judul, cuplikan isi, meta, tombol hapus. */
@Composable
private fun AdminNoticeRow(n: Notice, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(plainText(n.title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (n.body.isNotBlank()) Text(plainText(n.body), color = Muted, fontSize = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val meta = (if (n.createdAt > 0) "Terbit ${dateText(n.createdAt)}" else "Terbit") + "  ·  " + (n.expiresAt?.let { "Berakhir ${dateText(it)}" } ?: "Tanpa batas waktu")
            Text(meta, color = Muted, fontSize = 11.sp)
        }
        IconButton(onDelete) { Icon(Ic.Trash, "Hapus", tint = RedText) }
    }
}

private val HttpRx = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

/** Tab Informasi: pengumuman biasa (Notifikasi › Informasi). */
@Composable
private fun AdminAnnounceTab() {
    var list by remember { mutableStateOf<List<Notice>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Notice?>(null) }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            runCatching { Net.api.adminAnnouncements().data }
                .onSuccess { list = it; err = null }
                .onFailure { err = it.friendly() }
        }
    }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(done) { if (done) { kotlinx.coroutines.delay(2500); done = false } }

    val urlBad = url.isNotBlank() && !HttpRx.matches(url.trim())
    val valid = title.isNotBlank() && !urlBad && !sending

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth().clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Informasi baru", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text("Muncul di Notifikasi › Informasi untuk semua akun, dengan pil merah bagi yang belum membaca. Tidak tampil di Home.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Field("Judul", title, supporting = "${title.length}/$INFO_TITLE_MAX") { title = it.take(INFO_TITLE_MAX) }
            Field("Isi (opsional)", body, single = false, supporting = "${body.length}/$INFO_BODY_MAX · **tebal** didukung") { body = it.take(INFO_BODY_MAX) }
            Field("Tautan (opsional)", url, supporting = if (urlBad) "Harus diawali http:// atau https://" else null, isError = urlBad) { url = it }
            LifetimeChips(days) { days = it }
            if (title.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pratinjau", color = Muted, fontSize = 12.sp)
                Column(Modifier.fillMaxWidth().clip(R6).background(Bg)) {
                    NoticeRow(Notice(title = title.trim(), body = body.trim(), url = url.trim().ifBlank { null }), unread = true)
                }
            }
            PublishButton(valid, sending, done, "Terbitkan") {
                sending = true; err = null
                scope.launch {
                    val exp = if (days > 0) isoUtc(System.currentTimeMillis() + days * 86_400_000L) else null
                    runCatching { Net.api.adminCreateAnnouncement(AdminAnnounceReq(title.trim(), body.trim().ifBlank { null }, url.trim().ifBlank { null }, exp)).data }
                        .onSuccess { title = ""; body = ""; url = ""; days = 0; done = true; load() }
                        .onFailure { err = it.friendly() }
                    sending = false
                }
            }
            if (err != null) Text(err!!, color = RedText, fontSize = 13.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aktif${list?.let { " (${it.size})" }.orEmpty()}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val items = list
            when {
                items == null && err == null -> Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), Accent, strokeWidth = 2.dp) }
                items.isNullOrEmpty() -> Text("Belum ada informasi aktif.", color = Muted, fontSize = 13.sp)
                else -> items.forEach { n -> AdminNoticeRow(n) { delete = n } }
            }
        }
    }
    delete?.let { n ->
        AdminConfirm("Hapus informasi?", "\"${plainText(n.title)}\" akan hilang dari tab Informasi semua akun.", "Hapus", {
            delete = null
            scope.launch { if (n.id > 0) runCatching { Net.api.adminDeleteAnnouncement(n.id) }.onSuccess { load() }.onFailure { err = it.friendly() } }
        }, { delete = null })
    }
}

/** Tab Push: hidup/mati notifikasi, kirim notifikasi manual ke semua perangkat, dan riwayat. */
@Composable
private fun AdminPushTab() {
    var data by remember { mutableStateOf<AdminPush?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var seriesId by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { Net.api.adminPush().data }.onSuccess { data = it; err = null }.onFailure { err = it.friendly() } }
    LaunchedEffect(done) { if (done) { kotlinx.coroutines.delay(2500); done = false } }
    fun save(p: AdminPushPatch) {
        scope.launch { runCatching { Net.api.adminPushSave(p).data }.onSuccess { data = it; err = null }.onFailure { err = it.friendly() } }
    }
    val d = data
    val idBad = seriesId.isNotBlank() && !Regex("^[A-Za-z]+:\\S+$").matches(seriesId.trim())
    val ready = d != null && d.configured && d.settings.enabled
    val valid = title.isNotBlank() && !idBad && !sending && ready

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth().clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pengaturan", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            if (d == null) {
                if (err == null) Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), Accent, strokeWidth = 2.dp) }
            } else {
                if (!d.configured) Text("FIREBASE_SERVICE_ACCOUNT belum diisi di server, jadi push belum aktif.", color = AmberText, fontSize = 12.sp, lineHeight = 17.sp)
                PushSwitch("Push aktif", "Saklar utama. Mati = tidak ada notifikasi push sama sekali.", d.settings.enabled) { save(AdminPushPatch(enabled = it)) }
                PushSwitch("Notifikasi chapter baru", "Kirim ke pembaca yang mem-bookmark komiknya.", d.settings.newChapter, d.settings.enabled) { save(AdminPushPatch(newChapter = it)) }
            }
        }

        Column(Modifier.fillMaxWidth().clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Kirim notifikasi", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text("Dikirim ke semua perangkat yang memasang aplikasi. Tautan komik (opsional) membuka halaman komik itu saat diketuk.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Field("Judul", title, supporting = "${title.length}/65") { title = it.take(65) }
            Field("Isi (opsional)", body, single = false, supporting = "${body.length}/240") { body = it.take(240) }
            Field("ID komik (opsional)", seriesId, supporting = if (idBad) "Format: provider:id, mis. lumos:solo-leveling" else null, isError = idBad) { seriesId = it }
            PublishButton(valid, sending, done, "Kirim ke semua") { confirm = true }
            if (err != null) Text(err!!, color = RedText, fontSize = 13.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Riwayat", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val log = d?.log.orEmpty()
            if (log.isEmpty()) Text("Belum ada pengiriman manual atau kegagalan.", color = Muted, fontSize = 13.sp)
            log.forEach { l ->
                Column(Modifier.fillMaxWidth().clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(l.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (l.body.isNotBlank()) Text(l.body, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    val st = if (l.ok) "Terkirim" else "Gagal: ${l.error.orEmpty()}"
                    Text("${if (l.kind == "broadcast") "Manual" else "Chapter baru"} · $st · ${agoText(l.createdAt)}", color = if (l.ok) Muted else RedText, fontSize = 11.sp)
                }
            }
        }
    }
    if (confirm) AdminConfirm("Kirim ke semua perangkat?", "\"${title.trim()}\" akan langsung muncul sebagai notifikasi di semua pengguna. Tidak bisa ditarik kembali.", "Kirim", {
        confirm = false; sending = true; err = null
        scope.launch {
            runCatching { Net.api.adminPushSend(AdminPushSend(title.trim(), body.trim().ifBlank { null }, seriesId.trim().ifBlank { null })).data }
                .onSuccess { title = ""; body = ""; seriesId = ""; done = true; data = data?.copy(log = it.log) }
                .onFailure { err = it.friendly() }
            sending = false
        }
    }, { confirm = false })
}

@Composable
private fun PushSwitch(label: String, hint: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Switch(on, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent, uncheckedThumbColor = Color.White, uncheckedTrackColor = CardLine))
    }
}


// ---------------------------------------------------------------- level / EXP

/** Batas XP = XP minimum Lv. 10.000 (sama dengan server). */
private const val ADMIN_XP_MAX = 333_933_529_974L
private fun adminLevelCost(level: Int): Long = 40L + 15L * (level - 1) + (level - 1).toLong() * (level - 1)
private fun adminXpForLevel(level: Int): Long { var t = 0L; for (l in 1 until level.coerceIn(1, 10000)) t += adminLevelCost(l); return t }
private fun adminLevelOf(xp: Long): Int { var lv = 1; var left = xp.coerceAtLeast(0); while (left >= adminLevelCost(lv) && lv < 10000) { left -= adminLevelCost(lv); lv++ }; return lv }

/** Dialog atur level / EXP: pilih mode, isi angka, lihat pratinjau hasil, lalu terapkan. */
@Composable
private fun AdminXpDialog(u: AdminUser, onApply: (AdminXpPatch) -> Unit, onCancel: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) } // 0 level, 1 EXP total, 2 tambah/kurangi
    var text by remember { mutableStateOf("") }
    val n = text.toLongOrNull()
    val result: Long? = when (mode) {
        0 -> n?.takeIf { it in 1..10000 }?.let { adminXpForLevel(it.toInt()) }
        1 -> n?.takeIf { it in 0..ADMIN_XP_MAX }
        else -> n?.takeIf { it in -ADMIN_XP_MAX..ADMIN_XP_MAX }?.let { (u.xp + it).coerceIn(0L, ADMIN_XP_MAX) }
    }
    AlertDialog(
        onDismissRequest = onCancel, containerColor = CardBg, shape = RoundedCornerShape(20.dp),
        title = { Text("Level & EXP @${u.username}", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sekarang: Level ${u.level} · ${u.xp} XP", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AdminChip("Level", mode == 0) { mode = 0; text = "" }
                    AdminChip("EXP total", mode == 1) { mode = 1; text = "" }
                    AdminChip("+/- EXP", mode == 2) { mode = 2; text = "" }
                }
                OutlinedTextField(
                    text, { v -> text = v.filter { it.isDigit() || (it == '-' && mode == 2 && v.indexOf('-') == 0) }.take(15) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(when (mode) { 0 -> "Level baru (1–10000)"; 1 -> "EXP total baru"; else -> "Tambah / kurangi (mis. 500 atau -200)" }, color = Muted) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = CardLine, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                if (result != null) Text("Hasil: Level ${adminLevelOf(result)} · $result XP", color = Color(0xFF6BCB77), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else if (text.isNotEmpty()) Text("Angka tidak valid", color = RedText, fontSize = 12.sp)
                Text("Level dihitung dari total EXP; mengubah level berarti mengatur EXP ke batas bawah level itu. Papan peringkat mingguan tidak berubah.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
        },
        confirmButton = {
            TextButton({
                n?.let { v -> onApply(when (mode) { 0 -> AdminXpPatch(level = v.toInt()); 1 -> AdminXpPatch(xp = v); else -> AdminXpPatch(delta = v) }) }
            }, enabled = result != null) { Text("Terapkan", color = Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onCancel) { Text("Batal", color = Muted) } }
    )
}

// ---------------------------------------------------------------- Global Chat

@Composable
private fun AdminChatTab() {
    val openUser = LocalOpenUser.current
    var items by remember { mutableStateOf<List<ChatMsg>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        loading = true; err = null
        scope.launch {
            runCatching { Net.api.chat().data }
                .onSuccess { items = it.reversed(); loading = false }
                .onFailure { err = it.friendly(); loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    if (confirmAll) AdminConfirm("Kosongkan Global Chat?", "Semua pesan di Global Chat dihapus permanen.", "Hapus semua", {
        confirmAll = false; busy = true
        scope.launch {
            runCatching { Net.api.adminClearChat().data }
                .onSuccess { items = emptyList(); info = "${it.deleted} pesan dihapus" }
                .onFailure { info = it.friendly() }
            busy = false
        }
    }, { confirmAll = false })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Global Chat (${items.size} pesan)", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton({ load() }, enabled = !loading) { Text("Muat ulang", color = Accent) }
            TextButton({ confirmAll = true }, enabled = !busy && items.isNotEmpty()) { Text("Kosongkan", color = RedText) }
        }
        info?.let { Text(it, color = Color(0xFF6BCB77), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        else if (err != null) Text(err!!, color = RedText, modifier = Modifier.padding(16.dp))
        else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(items, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(10.dp), verticalAlignment = Alignment.Top) {
                    Avatar(m.user.id, m.user.name, m.user.avatarV, 36.dp, Modifier.clickable { openUser(m.user.id) })
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text("${m.user.name} · Lv ${m.user.level}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(m.body, color = Color.White, fontSize = 14.sp)
                    }
                    TextButton({
                        scope.launch {
                            runCatching { Net.api.deleteChat(m.id) }
                                .onSuccess { items = items.filter { it.id != m.id }; info = "Pesan dihapus" }
                                .onFailure { info = it.friendly() }
                        }
                    }) { Text("Hapus", color = RedText) }
                }
            }
        }
    }
}
