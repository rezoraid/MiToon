package id.mitoon.app

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

private val seriesListSer = ListSerializer(Series.serializer())
private const val FRESH_MS = 90_000L

@Composable
inline fun <reified T : ViewModel> keyedVm(key: String, crossinline make: () -> T): T =
    viewModel(key = key, factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
    })

/**
 * Daftar berhalaman (infinite scroll) dengan dedup.
 * [snapKey] != null -> halaman 1 disimpan di disk: saat dibuka lagi langsung tampil dari snapshot (tanpa spinner),
 * lalu diganti data terbaru begitu server menjawab.
 */
class PagedVm(private val snapKey: String? = null, private val loader: suspend (Int) -> List<Series>) : ViewModel() {
    // Bila daftar ini baru dibuka (< 90 dtk) -> pulihkan penuh dari memori (termasuk halaman yang sudah digulir) dan JANGAN memuat ulang.
    private val mem = snapKey?.let { Net.pageMem[it] }?.takeIf { Net.isFresh("page:$snapKey", FRESH_MS) }
    var items by mutableStateOf(mem?.items ?: snapKey?.let { Net.readSnap(it, seriesListSer) }.orEmpty()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** true selama pull-to-refresh berjalan (indikator di UI). */
    var refreshing by mutableStateOf(false); private set
    private var page = mem?.page ?: 1
    private var end = mem?.end ?: false
    private var busy = false

    init { if (mem == null) more() }

    private fun saveMem() { if (snapKey != null && items.isNotEmpty()) Net.pageMem[snapKey] = Net.PageState(items, page, end) }

    fun more() {
        if (busy || end) return
        busy = true
        loading = items.isEmpty() || page > 1 // menyegarkan halaman 1 di atas snapshot tidak menampilkan spinner
        error = null
        viewModelScope.launch {
            runCatching { loader(page) }
                .onSuccess { r ->
                    if (page == 1) {
                        if (r.isEmpty()) end = true
                        else { items = r; page = 2; snapKey?.let { Net.writeSnap(it, seriesListSer, r); Net.markFresh("page:$it") }; r.take(3).forEach { Net.prefetchDetail(it.id) }; Net.preloadCovers(r, skip = 12) }
                    } else {
                        val seen = items.mapTo(HashSet()) { it.id }
                        val fresh = r.filter { it.id !in seen }
                        if (fresh.isEmpty()) end = true else { items = items + fresh; page++; Net.preloadCovers(fresh); fresh.take(2).forEach { Net.prefetchDetail(it.id) } }
                    }
                }
                .onFailure { if (items.isEmpty() || page > 1) error = "Gagal memuat" }
            saveMem()
            busy = false; loading = false
        }
    }
    fun retry() = more()

    /**
     * Pull-to-refresh: ambil halaman 1 langsung dari jaringan (lewati cache HTTP) lalu ganti daftar.
     * Gagal / hasil kosong -> daftar lama dibiarkan.
     */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            Net.forceNet.incrementAndGet()
            try {
                runCatching { loader(1) }.onSuccess { r ->
                    if (r.isNotEmpty()) {
                        items = r; page = 2; end = false; error = null
                        snapKey?.let { Net.writeSnap(it, seriesListSer, r); Net.markFresh("page:$it") }
                        saveMem()
                        r.take(3).forEach { Net.prefetchDetail(it.id) }; Net.preloadCovers(r, skip = 12)
                    }
                }
            } finally {
                Net.forceNet.decrementAndGet()
                refreshing = false
            }
        }
    }
}

class HomeVm : ViewModel() {
    // Diisi dulu dari snapshot disk (instan), lalu disegarkan dari server.
    var spotlight by mutableStateOf(Net.readSnap("home-spotlight", seriesListSer).orEmpty()); private set
    var genres by mutableStateOf(Net.genresSnap().sortedByDescending { it.count }.take(20)); private set

    init {
        // Sama dengan request tab Populer & warm-up (limit 12) -> digabung jadi satu; sorotan = 6 teratas.
        viewModelScope.launch { runCatching { Net.popular(1, 12).take(6) }.onSuccess { spotlight = it; Net.writeSnap("home-spotlight", seriesListSer, it) } }
        viewModelScope.launch { runCatching { Net.genres().sortedByDescending { it.count }.take(20) }.onSuccess { genres = it } }
    }

    /** Ikut pull-to-refresh Beranda: sorotan & daftar genre disegarkan juga. */
    fun refresh() {
        viewModelScope.launch {
            Net.forceNet.incrementAndGet()
            try {
                runCatching { Net.popular(1, 12).take(6) }.onSuccess { if (it.isNotEmpty()) { spotlight = it; Net.writeSnap("home-spotlight", seriesListSer, it) } }
                runCatching { Net.genres().sortedByDescending { it.count }.take(20) }.onSuccess { if (it.isNotEmpty()) genres = it }
            } finally { Net.forceNet.decrementAndGet() }
        }
    }
}
