package id.mitoon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.imageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

private const val TILE_ROWS = 2048

private class Tile(val top: Int, val bottom: Int, val bmp: ImageBitmap)

private class Unsupported(msg: String) : Exception(msg)

private suspend fun <T> withFile(ctx: Context, url: String, headers: Map<String, String>, block: suspend (File) -> T): T {
    if (url.startsWith("file:")) return block(File(Uri.parse(url).path.orEmpty()))
    val key = url.trim()
    Net.ensureImage(key, headers)
    val snap = ctx.imageLoader.diskCache?.openSnapshot(key) ?: throw IOException("Gambar tidak tersimpan")
    return snap.use { block(it.data.toFile()) }
}

private suspend fun decodeTiles(file: File, screenW: Int, onSize: (Int, Int) -> Unit, onTile: (Tile) -> Unit) = withContext(Dispatchers.IO) {
    @Suppress("DEPRECATION")
    val made = runCatching { BitmapRegionDecoder.newInstance(file.path, false) }
    val d = made.getOrNull() ?: throw Unsupported(made.exceptionOrNull()?.message.orEmpty())
    try {
        val w = d.width; val h = d.height
        var sample = 1
        while (w / (sample * 2) >= screenW) sample *= 2
        val rows = TILE_ROWS * sample
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = if (h / sample > 10000) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888 }
        onSize(w, h)
        var y = 0
        while (y < h) {
            ensureActive()
            val end = minOf(h, y + rows)
            val b = d.decodeRegion(Rect(0, y, w, end), opts) ?: throw Unsupported("region")
            onTile(Tile(y, end, b.asImageBitmap()))
            y = end
        }
    } finally { d.recycle() }
}

@Composable
fun ReaderImage(url: String, headers: Map<String, String>, onFail: () -> Unit) {
    val ctx = LocalContext.current
    val screenW = ctx.resources.displayMetrics.widthPixels
    var attempt by remember(url) { mutableIntStateOf(0) }
    var fallback by remember(url) { mutableStateOf(false) }
    var dims by remember(url) { mutableStateOf<IntSize?>(null) }
    val tiles = remember(url) { mutableStateListOf<Tile>() }
    val fail by rememberUpdatedState(onFail)
    androidx.compose.runtime.LaunchedEffect(url, attempt) {
        if (fallback) return@LaunchedEffect
        tiles.clear(); dims = null
        try { withFile(ctx, url, headers) { f -> decodeTiles(f, screenW, { w, h -> dims = IntSize(w, h) }) { tiles.add(it) } } }
        catch (e: CancellationException) { throw e }
        catch (e: Unsupported) { fallback = true }
        catch (e: Throwable) { if (attempt < 4) { delay(1500L * (attempt + 1)); attempt++ } else fail() }
    }
    val d = dims
    when {
        fallback -> Img(url, "", Modifier.fillMaxWidth().heightIn(min = 240.dp), ContentScale.FillWidth, headers, onFail = onFail, maxH = 4096)
        d == null -> Box(Modifier.fillMaxWidth().height(240.dp).background(CardBg))
        else -> Canvas(Modifier.fillMaxWidth().aspectRatio(d.width.toFloat() / d.height).background(CardBg)) {
            val s = size.width / d.width
            val w = size.width.roundToInt()
            tiles.forEach { t ->
                val top = (t.top * s).roundToInt()
                drawImage(t.bmp, dstOffset = IntOffset(0, top), dstSize = IntSize(w, (t.bottom * s).roundToInt() - top), filterQuality = FilterQuality.Medium)
            }
        }
    }
}
