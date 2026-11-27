package id.mitoon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

@Serializable data class StickerRef(val id: Long)

val LocalOpenPremium = staticCompositionLocalOf<() -> Unit> { {} }

private const val STICKER_MAX_BYTES = 400 * 1024

object Stickers {
    private val re = Regex("^\\[\\[stk:(\\d{1,9})\\]\\]$")
    var global by mutableStateOf<List<Long>>(emptyList()); private set
    var mine by mutableStateOf<List<Long>>(emptyList()); private set
    var globalLoaded by mutableStateOf(false); private set

    fun id(body: String): Long? = re.matchEntire(body.trim())?.groupValues?.get(1)?.toLongOrNull()
    fun token(id: Long) = "[[stk:$id]]"
    fun label(body: String) = if (id(body) != null) "Stiker" else body
    fun url(id: Long) = "${BuildConfig.API_BASE_URL}v1/stickers/$id/image"

    suspend fun loadGlobal() { runCatching { Net.api.stickersGlobal().data }.onSuccess { global = it.map { s -> s.id }; globalLoaded = true } }
    suspend fun loadMine() { runCatching { Net.api.stickersMine().data }.onSuccess { mine = it.map { s -> s.id } } }

    suspend fun addMine(bytes: ByteArray, mime: String) { Net.api.addMySticker(bytes.toRequestBody(mime.toMediaType())); loadMine() }
    suspend fun deleteMine(id: Long) { Net.api.deleteMySticker(id); mine = mine.filter { it != id } }
    suspend fun adminAdd(bytes: ByteArray, mime: String) { Net.api.adminAddSticker(bytes.toRequestBody(mime.toMediaType())); loadGlobal() }
    suspend fun adminDelete(id: Long) { Net.api.adminDeleteSticker(id); global = global.filter { it != id } }
}

@Suppress("DEPRECATION")
suspend fun prepareSticker(ctx: Context, uri: Uri): Pair<ByteArray, String>? = withContext(Dispatchers.Default) {
    val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
    if (raw.size > 6 && String(raw, 0, 4, Charsets.ISO_8859_1) == "GIF8") return@withContext if (raw.size <= STICKER_MAX_BYTES) raw to "image/gif" else null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    val src = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
    var result: Pair<ByteArray, String>? = null
    try {
        for (side in intArrayOf(512, 384, 256)) {
            val k = (side.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
            val bmp = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true) else src
            val png = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            val webp = if (png.size <= STICKER_MAX_BYTES) null else ByteArrayOutputStream().use {
                bmp.compress(if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 80, it); it.toByteArray()
            }
            if (bmp !== src) bmp.recycle()
            result = when {
                png.size <= STICKER_MAX_BYTES -> png to "image/png"
                webp != null && webp.size <= STICKER_MAX_BYTES -> webp to "image/webp"
                else -> null
            }
            if (result != null) break
        }
    } finally { src.recycle() }
    result
}

@Composable
fun StickerImage(id: Long, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val req = remember(id) { ImageRequest.Builder(ctx).data(Stickers.url(id)).memoryCacheKey("stk:$id").build() }
    AsyncImage(req, null, modifier.size(size), contentScale = ContentScale.Fit)
}

@Composable
fun StickerButton(onPick: (Long) -> Unit, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp)) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.size(56.dp).clip(shape).background(Color(0xFF2A2C32)).clickable { open = true }, Alignment.Center) {
        Icon(Ic.Sticker, "Stiker", tint = Color.White, modifier = Modifier.size(26.dp))
    }
    if (open) StickerSheet({ open = false }) { id -> open = false; onPick(id) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StickerSheet(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val openPremium = LocalOpenPremium.current
    val premium = Auth.user?.premium == true
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var delId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { Stickers.loadGlobal(); if (!Auth.isGuest) Stickers.loadMine() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val p = prepareSticker(ctx, uri)
                if (p == null) toast(ctx, "Gambar tidak bisa dipakai (maks. 400 KB)") else Stickers.addMine(p.first, p.second)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(ctx, e.friendly()) } finally { busy = false }
        }
    }
    val addMine = {
        when {
            Auth.isGuest -> GuestGate.ask(GUEST_CHAT)
            !premium -> { onDismiss(); openPremium() }
            else -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), containerColor = CardBg, contentWindowInsets = { WindowInsets(0) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.62f)) {
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Global", "My Sticker").forEachIndexed { i, t ->
                    Text(t, color = if (tab == i) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (tab == i) Color.White else Color(0xFF2A2C32)).clickable { tab = i }.padding(horizontal = 18.dp, vertical = 8.dp))
                }
            }
            HorizontalDivider(color = Line)
            val list = if (tab == 0) Stickers.global else Stickers.mine
            when {
                tab == 0 && list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    if (Stickers.globalLoaded) Text("Belum ada stiker global.", color = Muted, fontSize = 14.sp) else CircularProgressIndicator(Modifier.size(24.dp), Color.White, strokeWidth = 2.dp)
                }
                else -> LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == 1) item(key = "add") {
                        Box(Modifier.aspectRatio(1f).clip(R6).border(1.dp, CardLine, R6).clickable(enabled = !busy) { addMine() }, Alignment.Center) {
                            if (busy) CircularProgressIndicator(Modifier.size(22.dp), Color.White, strokeWidth = 2.dp)
                            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("+", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light)
                                Text(if (premium) "Tambah" else "Premium", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                    items(list, key = { it }) { id ->
                        Box(Modifier.aspectRatio(1f).clip(R6).background(Color(0xFF24262C)).combinedClickable(onClick = { onPick(id) }, onLongClick = { if (tab == 1) delId = id }).padding(6.dp)) {
                            StickerImage(id, 200.dp, Modifier.fillMaxSize())
                        }
                    }
                    if (tab == 1) item(key = "hint", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                        Text(if (premium) "Tekan lama stiker untuk menghapus. Maks. 30 stiker, 400 KB per gambar." else "Menambah stiker sendiri khusus Premium.",
                            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
            }
        }
    }
    delId?.let { id ->
        AlertDialog(
            onDismissRequest = { delId = null }, containerColor = CardBg, shape = RoundedCornerShape(20.dp),
            title = { Text("Hapus stiker?", color = Color.White, fontWeight = FontWeight.Bold) },
            confirmButton = { TextButton({ delId = null; scope.launch { runCatching { Stickers.deleteMine(id) }.onFailure { toast(ctx, it.friendly()) } } }) { Text("Hapus", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ delId = null }) { Text("Batal", color = Muted) } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdminStickerTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var delId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { Stickers.loadGlobal() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            var ok = 0
            try {
                for (u in uris) {
                    val p = prepareSticker(ctx, u)
                    if (p == null) { toast(ctx, "Satu gambar dilewati (tidak bisa dipakai / lebih dari 400 KB)"); continue }
                    try { Stickers.adminAdd(p.first, p.second); ok++ } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(ctx, e.friendly()); break }
                }
                if (ok > 0) toast(ctx, "$ok stiker ditambahkan")
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Stiker global (${Stickers.global.size})", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), Color.White, strokeWidth = 2.dp) else Text("Tambah", color = Accent, fontWeight = FontWeight.Bold)
            }
        }
        Text("Tekan lama stiker untuk menghapus. PNG/WebP/JPEG/GIF, maks. 400 KB.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Stickers.global, key = { it }) { id ->
                Box(Modifier.aspectRatio(1f).clip(R6).background(Color(0xFF24262C)).combinedClickable(onClick = {}, onLongClick = { delId = id }).padding(6.dp)) {
                    StickerImage(id, 200.dp, Modifier.fillMaxSize())
                }
            }
        }
    }
    delId?.let { id ->
        AlertDialog(
            onDismissRequest = { delId = null }, containerColor = CardBg, shape = RoundedCornerShape(20.dp),
            title = { Text("Hapus stiker global?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Stiker hilang dari semua pengguna; yang sudah terkirim tampil kosong.", color = Muted) },
            confirmButton = { TextButton({ delId = null; scope.launch { runCatching { Stickers.adminDelete(id) }.onFailure { toast(ctx, it.friendly()) } } }) { Text("Hapus", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ delId = null }) { Text("Batal", color = Muted) } }
        )
    }
}
