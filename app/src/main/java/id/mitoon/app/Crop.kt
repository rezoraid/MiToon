package id.mitoon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max


/** Decode foto galeri (ukuran dikecilkan ≤ 2048 px, orientasi EXIF dikoreksi). */
private suspend fun loadCropSource(ctx: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
    val decoded = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: throw IllegalStateException("Gambar tidak bisa dibaca")
    val orientation = runCatching {
        cr.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }
    }.getOrNull() ?: 1
    val m = Matrix()
    when (orientation) { 6 -> m.postRotate(90f); 3 -> m.postRotate(180f); 8 -> m.postRotate(270f) }
    if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
}

/** Potong area bingkai (rasio 2:1) dari gambar sumber -> JPEG 1080x540. */
private suspend fun renderCrop(src: Bitmap, scale: Float, ox: Float, oy: Float, fw: Float, fh: Float, outW: Int, outH: Int, maxBytes: Int): ByteArray = withContext(Dispatchers.Default) {
    val srcL = (src.width * scale / 2f - fw / 2f - ox) / scale
    val srcT = (src.height * scale / 2f - fh / 2f - oy) / scale
    val rect = Rect(srcL.toInt(), srcT.toInt(), (srcL + fw / scale).toInt().coerceAtMost(src.width), (srcT + fh / scale).toInt().coerceAtMost(src.height))
    rect.left = rect.left.coerceAtLeast(0); rect.top = rect.top.coerceAtLeast(0)
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(src, rect, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    var q = 84
    var bytes: ByteArray
    do {
        bytes = ByteArrayOutputStream().use { out.compress(Bitmap.CompressFormat.JPEG, q, it); it.toByteArray() }
        q -= 10
    } while (bytes.size > maxBytes && q >= 40)
    out.recycle()
    bytes
}

/** Editor foto profil: bingkai bulat (hasil persegi 384 px, ditampilkan bulat di app). */
@Composable
fun AvatarCropDialog(uri: Uri, onCancel: () -> Unit, onDone: (ByteArray) -> Unit) =
    ImageCropDialog(uri, "Atur foto profil", 1f, 384, 384, true, 280 * 1024, onCancel, onDone)

/**
 * Editor potong gambar: foto dari galeri, cubit untuk zoom, geser untuk posisi, slider untuk zoom halus.
 * Area bingkai adalah hasil akhir yang disimpan.
 */
@Composable
private fun ImageCropDialog(uri: Uri, title: String, aspect: Float, outW: Int, outH: Int, circle: Boolean, maxBytes: Int, onCancel: () -> Unit, onDone: (ByteArray) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var src by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var loadErr by remember(uri) { mutableStateOf(false) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var off by remember(uri) { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(uri) { runCatching { loadCropSource(ctx, uri) }.onSuccess { src = it }.onFailure { loadErr = true } }

    fun baseScale(b: Bitmap) = max(frame.width / b.width.toFloat(), frame.height / b.height.toFloat())
    fun clamp(b: Bitmap, z: Float, o: Offset): Offset {
        val s = baseScale(b) * z
        val mx = (b.width * s - frame.width).coerceAtLeast(0f) / 2f
        val my = (b.height * s - frame.height).coerceAtLeast(0f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        KeepBarsHidden()
        Column(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)).statusBarsPadding().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Text("Cubit untuk zoom, geser untuk mengatur posisi.", color = Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 16.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                val b = src
                when {
                    loadErr -> Text("Gambar tidak bisa dibuka. Pilih foto lain.", color = Muted, textAlign = TextAlign.Center)
                    b == null -> CircularProgressIndicator(Modifier.size(28.dp), Color.White, strokeWidth = 2.dp)
                    else -> Box(
                        Modifier.fillMaxWidth(if (circle) 0.86f else 1f).aspectRatio(aspect).then(if (circle) Modifier else Modifier.border(1.dp, Color.White, R6)).clipToBounds().background(Color.Black)
                            .onSizeChanged { frame = it }
                            .pointerInput(b, frame) {
                                detectTransformGestures { _, pan, zoomChange, _ ->
                                    if (frame.width == 0) return@detectTransformGestures
                                    val z = (zoom * zoomChange).coerceIn(1f, 6f)
                                    zoom = z
                                    off = clamp(b, z, off + pan)
                                }
                            }
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            if (frame.width == 0) return@Canvas
                            val s = baseScale(b) * zoom
                            val w = b.width * s; val h = b.height * s
                            val o = clamp(b, zoom, off)
                            drawImage(b.asImageBitmap(), dstOffset = IntOffset((size.width / 2f + o.x - w / 2f).toInt(), (size.height / 2f + o.y - h / 2f).toInt()),
                                dstSize = IntSize(w.toInt() + 1, h.toInt() + 1), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
                            if (circle) {
                                val ring = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
                                clipPath(ring, androidx.compose.ui.graphics.ClipOp.Difference) { drawRect(Color(0xB3000000)) }
                                drawCircle(Color.White, size.minDimension / 2f - 1f, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom", color = Muted, fontSize = 13.sp)
                Slider(zoom, { z -> zoom = z; src?.let { off = clamp(it, z, off) } }, Modifier.weight(1f).padding(horizontal = 12.dp), valueRange = 1f..6f,
                    enabled = src != null && !saving, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0xFF3A3C45)))
                Text(String.format("%.1fx", zoom), color = Color.White, fontSize = 13.sp, modifier = Modifier.widthIn(min = 36.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onCancel, Modifier.weight(1f).height(48.dp), enabled = !saving, shape = R6) { Text("Batal", color = Color.White) }
                Button({
                    val b = src ?: return@Button
                    saving = true
                    scope.launch {
                        val o = clamp(b, zoom, off)
                        val bytes = runCatching { renderCrop(b, baseScale(b) * zoom, o.x, o.y, frame.width.toFloat(), frame.height.toFloat(), outW, outH, maxBytes) }.getOrNull()
                        saving = false
                        if (bytes != null) onDone(bytes) else loadErr = true
                    }
                }, Modifier.weight(1f).height(48.dp), enabled = src != null && frame.width > 0 && !saving, shape = R6,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), Color.Black, strokeWidth = 2.dp) else Text("Simpan", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Gambar background header: lebar diperkecil (≤ 1080 px, tanpa dipotong; posisi diatur di app) lalu JPEG ≤ [maxBytes]. null = gagal / terlalu besar. */
suspend fun encodeHeaderJpeg(ctx: Context, uri: Uri, maxBytes: Int = 440 * 1024): ByteArray? = withContext(Dispatchers.Default) {
    val src = runCatching { loadCropSource(ctx, uri) }.getOrNull() ?: return@withContext null
    try {
        for (w in intArrayOf(1080, 800, 640)) {
            val scale = (w.toFloat() / src.width).coerceAtMost(1f)
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
            var q = 82
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it); it.toByteArray() }
                q -= 8
            } while (bytes.size > maxBytes && q >= 42)
            if (bmp !== src) bmp.recycle()
            if (bytes.size <= maxBytes) return@withContext bytes
        }
        null
    } finally { src.recycle() }
}
