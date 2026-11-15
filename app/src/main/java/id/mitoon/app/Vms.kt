package id.mitoon.app

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
inline fun <reified T : ViewModel> keyedVm(key: String, crossinline make: () -> T): T =
    viewModel(key = key, factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <V : ViewModel> create(modelClass: Class<V>): V = make() as V
    })

/** Daftar berhalaman (infinite scroll) dengan dedup. */
class PagedVm(private val loader: suspend (Int) -> List<Series>) : ViewModel() {
    var items by mutableStateOf<List<Series>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var page = 1
    private var end = false

    init { more() }

    fun more() {
        if (loading || end) return
        loading = true; error = null
        viewModelScope.launch {
            runCatching { loader(page) }
                .onSuccess { r ->
                    val seen = items.mapTo(HashSet()) { it.id }
                    val fresh = r.filter { it.id !in seen }
                    if (fresh.isEmpty()) end = true else { items = items + fresh; page++ }
                }
                .onFailure { error = "Gagal memuat" }
            loading = false
        }
    }
    fun retry() = more()
}

class HomeVm : ViewModel() {
    var spotlight by mutableStateOf<List<Series>>(emptyList()); private set
    var genres by mutableStateOf<List<Genre>>(emptyList()); private set
    var notices by mutableStateOf<List<Notice>>(emptyList()); private set

    init {
        viewModelScope.launch { runCatching { Net.api.popular(limit = 6).data }.onSuccess { spotlight = it } }
        viewModelScope.launch { runCatching { Net.genres().sortedByDescending { it.count }.take(20) }.onSuccess { genres = it } }
        viewModelScope.launch { runCatching { Net.api.announcements().data }.onSuccess { notices = it } }
    }
}
