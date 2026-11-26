package id.mitoon.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ------------------------------------------------------------------ model (cocok dengan /v1/premium/*)
@Serializable data class PlanInfo(val id: String = "", val label: String = "", val months: Int = 0, val price: Int = 0, val saving: Int = 0, val perMonth: Int = 0)
@Serializable data class MethodInfo(val id: String = "", val name: String = "", val group: String = "", val min: Int = 0)
@Serializable data class PremiumCatalog(val enabled: Boolean = true, val xpMultiplier: Double = 1.5, val plans: List<PlanInfo> = emptyList(), val methods: List<MethodInfo> = emptyList())
@Serializable data class PremiumPay(val kind: String = "", val qr: String? = null, val redirectUrl: String? = null, val account: String? = null, val bankName: String? = null, val bank: String? = null)
@Serializable data class PremiumOrder(
    val id: String = "", val plan: String = "", val months: Int = 0, val method: String = "", val amount: Int = 0, val total: Int = 0, val fee: Int = 0,
    /** pending | paid | expired | failed */
    val status: String = "pending", val createdAt: Long = 0, val expiresAt: Long = 0, val paidAt: Long = 0, val pay: PremiumPay = PremiumPay()
)
@Serializable data class PremiumOrderReq(val plan: String, val method: String)
/** `me` terisi saat pesanan sudah lunas: akun terbaru dengan Premium aktif. */
@Serializable data class PremiumOrderRes(val data: PremiumOrder, val me: AuthUser? = null)
@Serializable data class LatestOrder(val order: PremiumOrder? = null)
@Serializable data class LatestOrderRes(val data: LatestOrder = LatestOrder(), val me: AuthUser? = null)
/** Tema/frame "" = kembali ke bawaan. Hanya field yang diisi yang diubah. */
@Serializable data class StyleReq(val theme: String? = null, val frame: String? = null, val headerBlur: Int? = null, val headerPos: Int? = null)

const val GUEST_PREMIUM = "Masuk dengan Google dulu untuk membeli Premium."

private val Gold = Color(0xFFFFC53D)
private val GoldTint = Color(0x14FFC53D)
private val PremiumShape = RoundedCornerShape(16.dp)

// Paket cadangan bila server belum terjangkau (server tetap yang menentukan harga saat pesanan dibuat).
private val FallbackPlans = listOf(
    PlanInfo("1m", "1 Bulan", 1, 7000, 0, 7000), PlanInfo("3m", "3 Bulan", 3, 18000, 3000, 6000), PlanInfo("12m", "12 Bulan", 12, 60000, 24000, 5000)
)
private val FallbackMethods = listOf(
    MethodInfo("qris", "QRIS", "qris", 1000), MethodInfo("gopay", "GoPay", "ewallet", 1000), MethodInfo("dana", "DANA", "ewallet", 1000),
    MethodInfo("shopeepay_idr", "ShopeePay", "ewallet", 1000), MethodInfo("linkaja", "LinkAja", "ewallet", 1000),
    MethodInfo("bca", "BCA VA", "bank", 10000), MethodInfo("bri", "BRI VA", "bank", 10000), MethodInfo("bni", "BNI VA", "bank", 10000), MethodInfo("mandiri", "Mandiri VA", "bank", 10000)
)

object PremiumCatalogState {
    var catalog by mutableStateOf(PremiumCatalog(plans = FallbackPlans, methods = FallbackMethods))
        private set

    suspend fun refresh() {
        try {
            val c = Net.api.premiumPlans().data
            if (c.plans.isNotEmpty() && c.methods.isNotEmpty()) catalog = c
        } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }
}

fun rupiah(n: Int): String = "Rp" + NumberFormat.getInstance(Locale("id", "ID")).format(n)
private fun premDate(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale("id", "ID")).format(Date(ms))

/** "Aktif sampai 12 Des 2026" / "Aktif" (pemberian permanen) / null bila tidak Premium. */
fun premiumStatusText(u: AuthUser?): String? = when {
    u == null || !u.premium -> null
    u.premiumUntil > 0 -> "Aktif sampai ${premDate(u.premiumUntil)}"
    else -> "Aktif"
}

// ------------------------------------------------------------------ tema profil & frame avatar (id harus sama dengan server)
class ProfileTheme(val name: String, val a: Color, val b: Color)
val ProfileThemes: Map<String, ProfileTheme> = linkedMapOf(
    "ocean" to ProfileTheme("Ocean", Color(0xFF4FC3F7), Color(0xFF1565C0)),
    "sakura" to ProfileTheme("Sakura", Color(0xFFFF9DB8), Color(0xFFB03060)),
    "aurora" to ProfileTheme("Aurora", Color(0xFF7CFFC4), Color(0xFF5B6CFF)),
    "sunset" to ProfileTheme("Sunset", Color(0xFFFFB074), Color(0xFFE0436B)),
    "gold" to ProfileTheme("Gold", Color(0xFFFFE08A), Color(0xFFB8791A)),
    "violet" to ProfileTheme("Violet", Color(0xFFC4B0FF), Color(0xFF5A3FD0))
)

/** Warna pertama = warna terakhir supaya gradasi melingkar tanpa sambungan. */
class AvatarFrame(val name: String, val colors: List<Color>)
val AvatarFrames: Map<String, AvatarFrame> = linkedMapOf(
    "gold" to AvatarFrame("Emas", listOf(Color(0xFFFFE08A), Color(0xFFFFB02E), Color(0xFFB8791A), Color(0xFFFFE08A))),
    "neon" to AvatarFrame("Neon", listOf(Color(0xFF00F0FF), Color(0xFF7A5CFF), Color(0xFFFF3DF2), Color(0xFF00F0FF))),
    "sakura" to AvatarFrame("Sakura", listOf(Color(0xFFFFC1D6), Color(0xFFFF8FB1), Color(0xFFFFD9E6), Color(0xFFFFC1D6))),
    "flame" to AvatarFrame("Api", listOf(Color(0xFFFFD23F), Color(0xFFFF7A18), Color(0xFFFF3D3D), Color(0xFFFFD23F))),
    "ice" to AvatarFrame("Es", listOf(Color(0xFFE0F7FF), Color(0xFF7CD7FF), Color(0xFF4A90FF), Color(0xFFE0F7FF))),
    "emerald" to AvatarFrame("Zamrud", listOf(Color(0xFFA8FFB5), Color(0xFF2EC27E), Color(0xFF0F7A55), Color(0xFFA8FFB5)))
)

private fun DrawScope.frameRing(colors: List<Color>, w: Float) {
    val r = (size.minDimension - w) / 2f
    val brush = Brush.sweepGradient(colors, Offset(size.width / 2f, size.height / 2f))
    drawCircle(brush, r, style = Stroke(w * 1.6f), alpha = 0.18f) // cahaya tipis di luar cincin
    drawCircle(brush, r, style = Stroke(w))
}

/** Foto profil + frame Premium (bila ada). Tanpa frame = [Avatar] biasa, ukuran & tata letak tidak berubah. */
@Composable
fun FramedAvatar(userId: Long, username: String, version: Int, size: Dp, frame: String, modifier: Modifier = Modifier) {
    val f = AvatarFrames[frame]
    if (f == null) { Avatar(userId, username, version, size, modifier); return }
    val ring = (size.value * 0.055f).coerceAtLeast(2f).dp
    val inner = size - (ring * 1.7f) * 2
    Box(modifier.size(size), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { frameRing(f.colors, ring.toPx()) }
        Avatar(userId, username, version, inner)
    }
}

private fun headerUrl(userId: Long, v: Int) = "${BuildConfig.API_BASE_URL}v1/users/$userId/header?v=$v"

/**
 * Latar header profil: gambar pilihan (blur + posisi), diberi warna tema; memudar ke [fade] di bawah.
 * Tanpa tema dan tanpa gambar = kosong (tidak menggambar apa pun). Blur memakai Modifier.blur (Android 12+; di bawahnya gambar tampil tajam).
 */
@Composable
fun ProfileBackdrop(userId: Long, theme: String, headerV: Int, blur: Int, pos: Int, height: Dp, modifier: Modifier = Modifier, fade: Color = CardBg) {
    val t = ProfileThemes[theme]
    if (t == null && headerV <= 0) return
    val ctx = LocalContext.current
    Box(modifier.fillMaxWidth().height(height).clipToBounds()) {
        if (headerV > 0) {
            val bias = pos.coerceIn(0, 100) / 50f - 1f // satu slider: hanya sumbu yang berlebih (atas-bawah / kiri-kanan) yang bergeser
            val req = remember(userId, headerV) { ImageRequest.Builder(ctx).data(headerUrl(userId, headerV)).memoryCacheKey("hdr:$userId:$headerV").build() }
            AsyncImage(req, null, Modifier.fillMaxSize().then(if (blur > 0) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier),
                contentScale = ContentScale.Crop, alignment = BiasAlignment(bias, bias))
            Box(Modifier.fillMaxSize().background(Color(0x4D000000))) // peredam supaya teks tetap terbaca
        }
        if (t != null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.a.copy(if (headerV > 0) 0.22f else 0.42f), t.b.copy(if (headerV > 0) 0.08f else 0.16f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, fade))))
    }
}

// ------------------------------------------------------------------ kartu "Go Premium" di Home
@Composable
fun PremiumPromo(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = premiumStatusText(Auth.user)
    val from = PremiumCatalogState.catalog.plans.minOfOrNull { it.price } ?: 7000
    Row(
        modifier.fillMaxWidth().clip(R6).background(CardBg)
            .border(1.dp, Brush.linearGradient(listOf(Color(0x66FFE08A), Color(0x26FFB02E))), R6)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x22FFB02E)), Alignment.Center) { PremiumBadge(size = 22.dp) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(if (status != null) "Premium aktif" else "Go Premium", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(status ?: "Mulai ${rupiah(from)} · EXP lebih besar, frame & tema eksklusif", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ic.Right, null, tint = Muted)
    }
}

// ------------------------------------------------------------------ halaman pembelian
private val Perks = listOf(
    "Bonus EXP lebih besar" to "Semua perolehan EXP dikali %s (dibulatkan ke atas). Batas harian tetap sama.",
    "Badge Premium" to "Mahkota emas di samping username: profil, komentar, chat, dan ranking.",
    "Foto profil GIF" to "Pakai GIF animasi sebagai foto profil.",
    "Tema & frame avatar eksklusif" to "Warna latar profil dan bingkai foto yang tidak dimiliki yang lain.",
    "Background header profil" to "Pasang gambarmu sendiri, atur blur dan posisinya."
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PremiumScreen(onBack: () -> Unit, onStyle: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cat = PremiumCatalogState.catalog
    val user = Auth.user
    var planId by rememberSaveable { mutableStateOf("3m") }
    var methodId by rememberSaveable { mutableStateOf("qris") }
    var order by remember { mutableStateOf<PremiumOrder?>(null) }
    var resume by remember { mutableStateOf<PremiumOrder?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        PremiumCatalogState.refresh()
        if (!Auth.isGuest) {
            try {
                val r = Net.api.premiumLatest()
                r.me?.let { Auth.update(it) }
                resume = r.data.order?.takeIf { it.status == "pending" }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    val plan = cat.plans.firstOrNull { it.id == planId } ?: cat.plans.firstOrNull()
    val methods = cat.methods.filter { plan == null || plan.price >= it.min } // VA bank minimal Rp10.000
    val method = methods.firstOrNull { it.id == methodId } ?: methods.firstOrNull()

    fun pay() {
        if (Auth.isGuest) { GuestGate.ask(GUEST_PREMIUM); return }
        val p = plan ?: return
        val m = method ?: return
        busy = true; err = null
        scope.launch {
            try { order = Net.api.premiumCreate(PremiumOrderReq(p.id, m.id)).data; resume = null }
            catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.friendly() } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Premium", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            // ---- hero
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0x22FFB02E)), Alignment.Center) { PremiumBadge(size = 34.dp) }
                Text("MiToon Premium", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 12.dp))
                Text("Dukung MiToon dan buka fitur eksklusif.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                premiumStatusText(user)?.let { s ->
                    Text(s, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(50)).background(Gold).padding(horizontal = 12.dp, vertical = 5.dp))
                    TextButton(onStyle) { Text("Atur tampilan profil", color = Color.White, fontWeight = FontWeight.SemiBold) }
                }
            }

            // ---- fitur
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(PremiumShape).border(1.dp, CardLine, PremiumShape).background(CardBg).padding(vertical = 6.dp, horizontal = 14.dp)) {
                val mult = String.format(Locale("id", "ID"), "×%.1f", cat.xpMultiplier)
                Perks.forEachIndexed { i, (title, desc) ->
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Ic.Check, null, tint = Gold, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(if (i == 0) desc.format(mult) else desc, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }

            resume?.let { r ->
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).clip(R6).background(GoldTint).border(1.dp, Color(0x55FFC53D), R6).clickable { order = r; resume = null }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pembayaran belum selesai", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("${rupiah(r.total)} · ${cat.methods.firstOrNull { it.id == r.method }?.name ?: r.method}", color = Muted, fontSize = 12.sp)
                    }
                    Text("Lanjutkan", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // ---- paket
            Text(if (user?.premium == true) "Perpanjang" else "Pilih paket", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cat.plans.forEach { p -> PlanCard(p, p.id == plan?.id, onClick = { planId = p.id }) }
            }

            // ---- metode
            Text("Bayar dengan", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                methods.forEach { m -> Chip(m.name, m.id == method?.id) { methodId = m.id } }
            }

            err?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).border(1.dp, Color.White, R6).padding(10.dp)) }

            Button({ pay() }, Modifier.fillMaxWidth().padding(top = 22.dp).height(50.dp), enabled = !busy && cat.enabled && plan != null && method != null, shape = R6,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), Color.Black, strokeWidth = 2.dp)
                else Text(
                    when {
                        !cat.enabled -> "Pembelian sedang dinonaktifkan"
                        Auth.isGuest -> "Masuk untuk membeli"
                        else -> (if (user?.premium == true) "Perpanjang · " else "Bayar · ") + rupiah(plan?.price ?: 0)
                    }, fontWeight = FontWeight.Bold
                )
            }
            Text("Pembayaran diproses lewat Sociabuzz. Premium aktif otomatis setelah pembayaran terverifikasi, dan perpanjangan menambah masa aktif yang tersisa.",
                color = Muted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }

    order?.let { o -> PaymentSheet(o, onClose = { order = null }, onStyle = { order = null; onStyle() }) }
}

@Composable
private fun PlanCard(p: PlanInfo, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) GoldTint else CardBg).border(if (selected) 1.5.dp else 1.dp, if (selected) Gold else CardLine, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) Gold else Muted, CircleShape), Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Gold))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (p.saving > 0) Text("Hemat ${rupiah(p.saving)}", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(Gold).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            if (p.months > 1) Text("${rupiah(p.perMonth)}/bulan", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text(rupiah(p.price), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

// ------------------------------------------------------------------ pembayaran (QR / VA / e-wallet) + polling status
private fun openLink(ctx: Context, url: String) {
    val u = runCatching { Uri.parse(url) }.getOrNull() ?: return
    if (u.scheme?.lowercase() in setOf("file", "content", "javascript", "intent")) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun leftText(expiresAt: Long, now: Long): String {
    val s = ((expiresAt - now) / 1000).coerceAtLeast(0)
    return if (s >= 3600) "Berlaku sampai ${SimpleDateFormat("d MMM, HH.mm", Locale("id", "ID")).format(Date(expiresAt))}" else "Bayar dalam %d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSheet(initial: PremiumOrder, onClose: () -> Unit, onStyle: () -> Unit) {
    var o by remember(initial.id) { mutableStateOf(initial) }
    var netErr by remember { mutableStateOf(false) }
    // Tanya server tiap 4 detik selama menunggu; server memeriksa Sociabuzz & mengaktifkan Premium begitu terverifikasi.
    LaunchedEffect(initial.id) {
        while (o.status == "pending") {
            delay(4000)
            try {
                val r = Net.api.premiumOrderStatus(o.id)
                o = r.data
                r.me?.let { Auth.update(it) }
                netErr = false
            } catch (e: CancellationException) { throw e } catch (_: Exception) { netErr = true }
        }
    }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), containerColor = CardBg, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (o.status) {
                "paid" -> {
                    Box(Modifier.padding(top = 8.dp).size(64.dp).clip(CircleShape).background(Color(0x22FFB02E)), Alignment.Center) { PremiumBadge(size = 34.dp) }
                    Text("Premium aktif!", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 12.dp))
                    Text(premiumStatusText(Auth.user) ?: "Terima kasih sudah mendukung MiToon.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    Button(onStyle, Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp), shape = R6, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)) { Text("Atur tampilan profil", fontWeight = FontWeight.Bold) }
                    TextButton(onClose) { Text("Selesai", color = Muted) }
                }
                "pending" -> PendingBody(o, netErr, onClose)
                else -> {
                    Text(if (o.status == "expired") "Pembayaran kedaluwarsa" else "Pembayaran gagal", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text("Tidak ada dana yang diproses. Silakan buat pembayaran baru.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    Button(onClose, Modifier.fillMaxWidth().padding(top = 18.dp).height(48.dp), shape = R6, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Tutup", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun PendingBody(o: PremiumOrder, netErr: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val cat = PremiumCatalogState.catalog
    val methodName = cat.methods.firstOrNull { it.id == o.method }?.name ?: o.method
    val planLabel = cat.plans.firstOrNull { it.id == o.plan }?.label ?: "${o.months} Bulan"

    Text("Selesaikan pembayaran", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    Text("Premium $planLabel · $methodName", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    Text(rupiah(o.total), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp))
    if (o.fee > 0) Text("Termasuk biaya layanan ${rupiah(o.fee)}", color = Muted, fontSize = 12.sp)
    Text(leftText(o.expiresAt, now), color = Gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))

    val pay = o.pay
    when {
        pay.kind == "bank" && !pay.account.isNullOrBlank() -> {
            Text("Nomor Virtual Account${pay.bankName?.let { " $it" } ?: ""}", color = Muted, fontSize = 13.sp)
            Text(pay.account, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton({ clip.setText(AnnotatedString(pay.account)) }, Modifier.padding(top = 12.dp), shape = R6) { Text("Salin nomor", color = Color.White) }
            Text("Transfer sesuai nominal lewat m-banking / ATM.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        !pay.qr.isNullOrBlank() -> {
            QrCode(pay.qr, Modifier.size(230.dp))
            Text(if (pay.kind == "qris") "Scan dengan e-wallet atau m-banking apa pun." else "Scan, atau buka aplikasinya di bawah.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            if (!pay.redirectUrl.isNullOrBlank()) Button({ openLink(ctx, pay.redirectUrl) }, Modifier.fillMaxWidth().padding(top = 14.dp).height(46.dp), shape = R6, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Buka $methodName", fontWeight = FontWeight.Bold) }
        }
        !pay.redirectUrl.isNullOrBlank() -> {
            Button({ openLink(ctx, pay.redirectUrl) }, Modifier.fillMaxWidth().height(48.dp), shape = R6, colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)) { Text("Buka $methodName untuk membayar", fontWeight = FontWeight.Bold) }
            Text("Setelah membayar, kembali ke sini. Status diperbarui otomatis.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        else -> Text("Petunjuk pembayaran untuk metode ini belum tersedia. Tutup lalu pilih QRIS.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }

    Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), Muted, strokeWidth = 2.dp)
        Text(if (netErr) "  Koneksi bermasalah, mencoba lagi…" else "  Menunggu pembayaran… Premium aktif otomatis.", color = Muted, fontSize = 12.sp)
    }
    TextButton(onClose) { Text("Tutup, bayar nanti", color = Muted) }
}

/** QR dari teks QRIS (digambar sendiri dari matriks ZXing; latar putih supaya terbaca di tema gelap). */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) {
        runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)) }.getOrNull()
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
        if (m != null) Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / m.width
            for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

// ------------------------------------------------------------------ tampilan profil Premium (tema, frame, background header)
@Composable
fun PremiumStyleScreen(onBack: () -> Unit, onPremium: () -> Unit) {
    val user = Auth.user
    if (user == null) { Box(Modifier.fillMaxSize().background(Bg)); return }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val on = user.premium
    var notice by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var blur by remember(user.headerBlur) { mutableFloatStateOf(user.headerBlur.toFloat()) }
    var pos by remember(user.headerPos) { mutableFloatStateOf(user.headerPos.toFloat()) }
    LaunchedEffect(notice) { if (notice?.first == true) { delay(2500); notice = null } }

    /** Optimistik: tampil seketika, server menyusul; gagal -> nilai lama kembali + pesan. */
    fun save(req: StyleReq, apply: (AuthUser) -> AuthUser, undo: (AuthUser) -> AuthUser) {
        if (!on) { onPremium(); return }
        Auth.setting(apply, undo, { Net.api.updateStyle(req).data }) { e -> if (e != null) notice = false to e.friendly() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; notice = null
        scope.launch {
            try {
                val bytes = encodeHeaderJpeg(ctx, uri)
                if (bytes == null) notice = false to "Gambar tidak bisa dipakai. Coba gambar lain."
                else { Auth.update(Net.api.putHeader(bytes.toRequestBody("image/jpeg".toMediaType())).data); notice = true to "Background diperbarui" }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = false to e.friendly() } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        SubTopBar("Tampilan Premium", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 16.dp)) {
            if (!on) Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(R6).background(GoldTint).border(1.dp, Color(0x55FFC53D), R6).clickable(onClick = onPremium).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PremiumBadge(size = 22.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Khusus Premium", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Tema, frame, dan background tampil lagi saat Premium aktif.", color = Muted, fontSize = 12.sp)
                }
                Text("Lihat", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            // ---- pratinjau
            Box(Modifier.fillMaxWidth().clip(PremiumShape).border(1.dp, CardLine, PremiumShape).background(CardBg)) {
                ProfileBackdrop(user.id, user.theme, user.headerV, blur.roundToInt(), pos.roundToInt(), 150.dp)
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FramedAvatar(user.id, user.name, user.avatarV, 88.dp, user.frame)
                    Text(user.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text("@${user.username}", color = Muted, fontSize = 13.sp)
                }
            }
            notice?.let { (ok, text) ->
                Text(text, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, if (ok) CardLine else Color.White, R6).padding(10.dp))
            }

            // ---- tema
            StyleLabel("Tema profil")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item("none") { ThemeSwatch("", user.theme.isEmpty()) { val old = user.theme; save(StyleReq(theme = ""), { it.copy(theme = "") }, { it.copy(theme = old) }) } }
                items(ProfileThemes.keys.toList(), key = { it }) { id ->
                    ThemeSwatch(id, user.theme == id) { val old = user.theme; save(StyleReq(theme = id), { it.copy(theme = id) }, { it.copy(theme = old) }) }
                }
            }

            // ---- frame
            StyleLabel("Frame avatar")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item("none") { FrameSwatch("", user.frame.isEmpty()) { val old = user.frame; save(StyleReq(frame = ""), { it.copy(frame = "") }, { it.copy(frame = old) }) } }
                items(AvatarFrames.keys.toList(), key = { it }) { id ->
                    FrameSwatch(id, user.frame == id) { val old = user.frame; save(StyleReq(frame = id), { it.copy(frame = id) }, { it.copy(frame = old) }) }
                }
            }

            // ---- background header
            StyleLabel("Background header profil")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ if (on) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else onPremium() }, Modifier.weight(1f).height(44.dp), enabled = !busy, shape = R6) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), Color.White, strokeWidth = 2.dp) else Text(if (user.headerV > 0) "Ganti gambar" else "Pilih gambar", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                if (user.headerV > 0) OutlinedButton({
                    val old = user
                    Auth.setting({ it.copy(headerV = 0) }, { it.copy(headerV = old.headerV) }, { Net.api.deleteHeader().data }) { e -> if (e != null) notice = false to e.friendly() }
                }, Modifier.height(44.dp), shape = R6) { Text("Hapus", color = Color.White) }
            }
            val hasHeader = user.headerV > 0
            // Pratinjau mengikuti geseran; nilainya disimpan lewat tombol di bawah.
            SliderRow("Blur", blur, 0f..25f, hasHeader && on, "${blur.roundToInt()}") { blur = it }
            SliderRow("Posisi", pos, 0f..100f, hasHeader && on, "${pos.roundToInt()}%") { pos = it }
            if (android.os.Build.VERSION.SDK_INT < 31) Text("Efek blur membutuhkan Android 12 ke atas.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (hasHeader && on) Button({
                val b = blur.roundToInt(); val p = pos.roundToInt()
                if (b != user.headerBlur || p != user.headerPos) save(StyleReq(headerBlur = b, headerPos = p), { it.copy(headerBlur = b, headerPos = p) }, { it.copy(headerBlur = user.headerBlur, headerPos = user.headerPos) })
            }, Modifier.fillMaxWidth().padding(top = 12.dp).height(46.dp), shape = R6, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Simpan blur & posisi", fontWeight = FontWeight.Bold) }
            Text("Gambar diperkecil otomatis. Tema, frame, dan background terlihat oleh orang lain di profilmu.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun StyleLabel(text: String) = Text(text, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, shown: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        Slider(value, onChange, Modifier.weight(1f), enabled = enabled, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0xFF3A3C45)))
        Text(shown, color = Color.White, fontSize = 13.sp, modifier = Modifier.widthIn(min = 40.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun ThemeSwatch(id: String, selected: Boolean, onClick: () -> Unit) {
    val t = ProfileThemes[id]
    Column(Modifier.width(60.dp).clip(R6).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(if (t != null) listOf(t.a, t.b) else listOf(Color(0xFF2A2C32), Color(0xFF2A2C32))))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else CardLine, CircleShape), Alignment.Center) {
            if (t == null) Text("–", color = Muted, fontSize = 18.sp)
        }
        Text(t?.name ?: "Tanpa", color = if (selected) Color.White else Muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FrameSwatch(id: String, selected: Boolean, onClick: () -> Unit) {
    val f = AvatarFrames[id]
    Column(Modifier.width(60.dp).clip(R6).clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).then(if (selected) Modifier.border(1.5.dp, Color.White, CircleShape) else Modifier), Alignment.Center) {
            if (f != null) Canvas(Modifier.fillMaxSize()) { frameRing(f.colors, 3.dp.toPx()) }
            else Box(Modifier.fillMaxSize().clip(CircleShape).border(1.dp, CardLine, CircleShape))
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF2A2C32)))
        }
        Text(f?.name ?: "Tanpa", color = if (selected) Color.White else Muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
            modifier = Modifier.padding(top = 4.dp))
    }
}
