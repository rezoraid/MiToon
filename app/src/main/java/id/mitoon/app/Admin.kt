package id.mitoon.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val R6 = RoundedCornerShape(12.dp)

@Composable
fun AdminScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Ringkasan", "Komik", "User", "Pengumuman")
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = Color.White) }
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
            3 -> AdminAnnounceTab()
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
            Spacer(Modifier.height(16.dp))
            TextButton({ load() }) { Icon(Icons.Filled.Refresh, null, tint = Accent); Spacer(Modifier.width(6.dp)); Text("Refresh", color = Accent) }
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
            IconButton({ load(1) }) { Icon(Icons.Filled.Search, null, tint = Accent) }
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
    fun delete() {
        scope.launch {
            runCatching { Net.api.adminDeleteSeries(id) }
                .onSuccess { onBack() }
                .onFailure { msg = it.friendly() }
        }
    }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
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
                s?.chapters?.take(30)?.forEach { ch ->
                    Text("• ${ch.numberText} ${ch.title}".trim(), color = Muted, fontSize = 12.sp)
                }
                if ((s?.chapters?.size ?: 0) > 30) Text("… dan ${(s?.chapters?.size ?: 0) - 30} lainnya", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(16.dp))
                TextButton({ delete() }) {
                    Icon(Icons.Filled.Delete, null, tint = Color(0xFFFF6B6B))
                    Spacer(Modifier.width(6.dp))
                    Text("Hapus seri dari DB", color = Color(0xFFFF6B6B))
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, single: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
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

@Composable
private fun AdminUsersTab() {
    var q by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var data by remember { mutableStateOf<AdminUsersPage?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load(p: Int = 1) {
        loading = true; err = null; page = p
        scope.launch {
            runCatching { Net.api.adminUsers(q = q.ifBlank { null }, page = p).data }
                .onSuccess { data = it; loading = false }
                .onFailure { err = it.friendly(); loading = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                q, { q = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Cari user…", color = Muted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = CardLine, focusedTextColor = Color.White, unfocusedTextColor = Color.White)
            )
            IconButton({ load(1) }) { Icon(Icons.Filled.Search, null, tint = Accent) }
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
        else if (err != null) Text(err!!, color = Color(0xFFFF6B6B), modifier = Modifier.padding(16.dp))
        else {
            Text("Total: ${data?.total ?: 0}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(data?.items.orEmpty(), key = { it.id }) { u ->
                    var verified by remember(u.id) { mutableStateOf(u.verified) }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(u.displayName.ifBlank { u.username }, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("@${u.username} · XP ${u.xp} · ${u.commentCount} komentar", color = Muted, fontSize = 12.sp)
                            if (u.email != null) Text(u.email, color = Muted, fontSize = 11.sp)
                        }
                        Switch(
                            verified,
                            {
                                verified = it
                                scope.launch {
                                    runCatching { Net.api.adminSetVerified(u.id, AdminVerifiedReq(it)).data }
                                        .onFailure { verified = !it; /* revert on fail */ }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                        )
                        Text(if (verified) "✓" else "", color = Accent, modifier = Modifier.padding(start = 4.dp))
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
private fun AdminAnnounceTab() {
    var list by remember { mutableStateOf<List<Notice>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch {
            runCatching { Net.api.adminAnnouncements().data }
                .onSuccess { list = it; err = null }
                .onFailure { err = it.friendly() }
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Buat pengumuman", color = Color.White, fontWeight = FontWeight.Bold)
        Field("Judul", title) { title = it }
        Field("Isi", body, single = false) { body = it }
        Field("URL (opsional)", url) { url = it }
        Button(
            {
                scope.launch {
                    runCatching {
                        Net.api.adminCreateAnnouncement(AdminAnnounceReq(title.trim(), body.ifBlank { null }, url.ifBlank { null })).data
                    }.onSuccess { title = ""; body = ""; url = ""; load() }
                        .onFailure { err = it.friendly() }
                }
            },
            enabled = title.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text("Kirim", color = Color.Black, fontWeight = FontWeight.Bold) }
        if (err != null) Text(err!!, color = Color(0xFFFF6B6B))
        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 8.dp))
        Text("Aktif", color = Color.White, fontWeight = FontWeight.Bold)
        list.forEach { n ->
            Row(
                Modifier.fillMaxWidth().clip(R6).border(1.dp, CardLine, R6).background(CardBg).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(n.title, color = Color.White, fontWeight = FontWeight.Bold)
                    if (n.body.isNotBlank()) Text(n.body, color = Muted, fontSize = 12.sp)
                }
                IconButton({
                    scope.launch {
                        if (n.id > 0) runCatching { Net.api.adminDeleteAnnouncement(n.id) }.onSuccess { load() }
                    }
                }) { Icon(Icons.Filled.Delete, null, tint = Color(0xFFFF6B6B)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
