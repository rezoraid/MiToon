package id.mitoon.app

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

private val seriesListSer = ListSerializer(Series.serializer())

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
    var items by mutableStateOf(snapKey?.let { Net.readSnap(it, seriesListSer) }.orEmpty()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var page = 1
    private var end = false
    private var busy = false

    init { more() }

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
                        else { items = r; page = 2; snapKey?.let { Net.writeSnap(it, seriesListSer, r) }; r.take(4).forEach { Net.prefetchDetail(it.id) }; Net.preloadCovers(r) }
                    } else {
                        val seen = items.mapTo(HashSet()) { it.id }
                        val fresh = r.filter { it.id !in seen }
                        if (fresh.isEmpty()) end = true else { items = items + fresh; page++; Net.preloadCovers(fresh); fresh.take(2).forEach { Net.prefetchDetail(it.id) } }
                    }
                }
                .onFailure { if (items.isEmpty() || page > 1) error = "Gagal memuat" }
            busy = false; loading = false
        }
    }
    fun retry() = more()
}

class HomeVm : ViewModel() {
    // Diisi dulu dari snapshot disk (instan), lalu disegarkan dari server.
    var spotlight by mutableStateOf(Net.readSnap("home-spotlight", seriesListSer).orEmpty()); private set
    var genres by mutableStateOf(Net.genresSnap().sortedByDescending { it.count }.take(20)); private set

    init {
        viewModelScope.launch { runCatching { Net.api.popular(limit = 6).data }.onSuccess { spotlight = it; Net.writeSnap("home-spotlight", seriesListSer, it) } }
        viewModelScope.launch { runCatching { Net.genres().sortedByDescending { it.count }.take(20) }.onSuccess { genres = it } }
    }
}
