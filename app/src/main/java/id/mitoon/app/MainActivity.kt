package id.mitoon.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.navigation.NavBackStackEntry
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*

private data class Tab(val route: String, val label: String, val icon: ImageVector, val on: ImageVector = icon)

/** Activity ini exported: id seri dari Intent luar hanya diterima bila berbentuk "<provider>:<id>" tanpa pemisah path. */
private val SERIES_ID = Regex("^[a-z0-9_-]{2,20}:[^\\s/\\\\?#]{1,300}$", RegexOption.IGNORE_CASE)
private fun safeSeriesId(s: String?): String? = s?.takeIf { SERIES_ID.matches(it) }

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        safeSeriesId(intent.getStringExtra("series"))?.let { Updates.pendingOpen = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        safeSeriesId(intent?.getStringExtra("series"))?.let { Updates.pendingOpen = it }
        Updates.askNotif = { if (android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        // Aplikasi selalu gelap: ikon status/navigasi harus terang, apa pun mode sistem perangkat.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        requestHighRefreshRate()
        Lang.init(this)
        setContent { MiToonTheme { App() } }
    }

    /** Minta mode layar dengan refresh rate tertinggi (90/120 Hz) pada resolusi yang sama; banyak HP memakai 60 Hz untuk aplikasi bila tidak diminta. */
    @Suppress("DEPRECATION")
    private fun requestHighRefreshRate() = runCatching {
        val d = windowManager.defaultDisplay
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }.maxByOrNull { it.refreshRate } ?: return@runCatching
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }
}

private val tabRoutes = setOf("home", "explore", "library", "ranking")
private fun NavBackStackEntry.isTab() = destination.route in tabRoutes
private fun NavBackStackEntry.isReader() = destination.route?.startsWith("reader") == true

private fun NavHostController.detail(id: String) = navigate("series/${Uri.encode(id)}")
private fun NavHostController.detail(s: Series) { Net.previews[s.id] = s; detail(s.id) }
private fun NavHostController.reader(sid: String, cid: String, replace: Boolean = false) =
    navigate("reader/${Uri.encode(sid)}/${Uri.encode(cid)}") { if (replace) popUpTo("reader/{sid}/{cid}") { inclusive = true } }

@Composable
private fun App() {
    // Belum login dan belum memilih "Lanjut tanpa masuk" = layar pembuka + login; setelah itu aplikasi masuk dengan zoom lembut.
    val stage = if (Auth.token == null && !Auth.visitor) 0 else 2
    Crossfade(stage, animationSpec = tween(320), label = "root") { st ->
        when (st) {
            0 -> AuthScreen()
            else -> AppEntrance { AppContent() }
        }
    }
}

@Composable
private fun AppContent() {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        AppScaffold()
    }
}

@Composable
private fun AppScaffold() {
    val nav = rememberNavController()
    LvClock.Run()
    val tabs = listOf(Tab("home", "Home", Ic.Home, Ic.HomeOn), Tab("explore", "Explore", Ic.Search), Tab("library", "Library", Ic.Heart, Ic.HeartOn),
        Tab("ranking", "Ranking", Ic.Trophy, Ic.TrophyOn))
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    // Pil merah notifikasi (lonceng di header): cek tiap menit selama login, dan setiap kali pindah layar (kecuali saat sedang membuka halaman Notifikasi).
    val uid = Auth.user?.id
    LaunchedEffect(uid) { if (uid == null) NotifState.clear() else while (true) { NotifState.refresh(); kotlinx.coroutines.delay(60_000) } }
    LaunchedEffect(route) { if (uid != null && route != "notifications") NotifState.refresh() }
    // Ikon akun di header membuka halaman profil.
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val bookmarkCount = Library.entries.values.count { it.bookmarked }
    LaunchedEffect(bookmarkCount > 0) { if (bookmarkCount > 0) Updates.ensurePermission(appCtx) }
    LaunchedEffect(Updates.pendingOpen) { Updates.pendingOpen?.let { Updates.pendingOpen = null; nav.detail(it) } }
    LaunchedEffect(Auth.menuOpen) { if (Auth.menuOpen) { Auth.menuOpen = false; nav.navigate("profile") { launchSingleTop = true } } }
    val openUser: (Long) -> Unit = { id -> if (id == Auth.user?.id) nav.navigate("profile") { launchSingleTop = true } else nav.navigate("user/$id") { launchSingleTop = true } }
    Auth.roleUnlocked?.let { r ->
        RoleUnlockDialog(r, { Auth.roleUnlocked = null }) { Auth.roleUnlocked = null; nav.navigate("profile/roles") { launchSingleTop = true } }
    }
    GuestGateHost()
    val openNotif: () -> Unit = { if (Auth.isGuest) GuestGate.ask(GUEST_NOTIF) else nav.navigate("notifications") { launchSingleTop = true } }
    val openChat: () -> Unit = { nav.navigate("chat") { launchSingleTop = true } }
    CompositionLocalProvider(LocalOpenUser provides openUser, LocalOpenNotif provides openNotif, LocalOpenChat provides openChat) {
    Scaffold(containerColor = Bg, bottomBar = {
        // Saat keyboard terbuka (mis. mengetik di Global Chat) bilah bawah disembunyikan supaya kolom tulis tepat di atas keyboard.
        val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        if (tabs.any { it.route == route } && !imeOpen) NavigationBar(containerColor = CardBg) {
            tabs.forEach { t ->
                NavigationBarItem(route == t.route, {
                    nav.navigate(t.route) { popUpTo("home"); launchSingleTop = true; restoreState = true }
                }, { Icon(if (route == t.route) t.on else t.icon, t.label) }, label = { Text(t.label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent,
                        indicatorColor = Color.Transparent, unselectedIconColor = Muted, unselectedTextColor = Muted))
            }
        }
    }) { pad ->
        // Transisi singkat (<250 ms) dan ringan: pindah tab = fade; buka layar = fade + geser tipis; kembali = layar atas menyingkir.
        NavHost(nav, "home", Modifier.padding(pad).consumeWindowInsets(pad),
            // Maju: layar baru geser masuk dari kanan, layar lama sedikit bergeser ke kiri. Mundur (tombol/gestur back): kebalikannya.
            // Dengan predictive back aktif (manifest), animasi pop ini mengikuti jari saat gestur back ditarik.
            enterTransition = { if (initialState.isReader() && targetState.isReader()) EnterTransition.None else if (initialState.isTab() && targetState.isTab()) fadeIn(tween(160)) else fadeIn(tween(240, 40)) + slideInHorizontally(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 5 } },
            exitTransition = { if (initialState.isReader() && targetState.isReader()) ExitTransition.None else if (initialState.isTab() && targetState.isTab()) fadeOut(tween(100)) else fadeOut(tween(220)) + slideOutHorizontally(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 8 } },
            popEnterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 8 } },
            popExitTransition = { if (initialState.isTab() && targetState.isTab()) fadeOut(tween(120)) else fadeOut(tween(220)) + slideOutHorizontally(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it / 5 } }) {
            composable("home") {
                HomeScreen({ nav.detail(it) }, { nav.navigate("search") }) {
                    nav.navigate("explore") { popUpTo("home"); launchSingleTop = true; restoreState = true }
                }
            }
            composable("explore") { ExploreScreen({ nav.detail(it) }, { nav.navigate("search") }) }
            composable("library") {
                LibraryScreen({ nav.detail(it) }, { e -> e.lastChapterId?.let { nav.reader(e.id, it) } ?: nav.detail(e.id) }, { nav.navigate("search") }, { sid, cid -> nav.reader(sid, cid) })
            }
            composable("chat") { ChatScreen(nav::popBackStack) }
            composable("ranking") { LeaderboardScreen { nav.navigate("search") } }
            composable("notifications") {
                NotificationsScreen(nav::popBackStack) { o -> if (o.chapterId != null) nav.reader(o.seriesId, o.chapterId) else nav.detail(o.seriesId) }
            }
            composable("search") { SearchScreen(nav::popBackStack) { nav.detail(it) } }
            composable("profile") { ProfileScreen(nav::popBackStack, { nav.navigate("profile/edit") }, { nav.navigate("profile/history") }, { nav.navigate("profile/account") }, { nav.navigate("profile/roles") }, { nav.navigate("admin") }, { nav.navigate("profile/privacy") }) }
            composable("profile/privacy") { PrivacyScreen(nav::popBackStack) }
            composable("admin") { AdminScreen(nav::popBackStack) }
            composable("admin/series/{id}") { e -> AdminSeriesEditScreen(e.arguments?.getString("id")?.toLongOrNull() ?: 0L, nav::popBackStack) }
            composable("profile/roles") { RolesScreen(nav::popBackStack) }
            composable("profile/edit") { ProfileEditScreen(nav::popBackStack) }
            composable("profile/history") {
                ActivityHistoryScreen(nav::popBackStack,
                    { o -> if (o.chapterId != null) nav.reader(o.seriesId, o.chapterId) else nav.detail(o.seriesId) },
                    { e -> e.lastChapterId?.let { nav.reader(e.id, it) } ?: nav.detail(e.id) })
            }
            composable("profile/account") { AccountSettingsScreen(nav::popBackStack) }
            composable("user/{id}") { e ->
                UserProfileScreen(e.arguments?.getString("id")?.toLongOrNull() ?: 0L, nav::popBackStack) { r ->
                    nav.detail(r.seriesId)
                }
            }
            composable("series/{id}") { e ->
                DetailScreen(e.arguments?.getString("id").orEmpty(), nav::popBackStack, { nav.navigate("search") },
                    { slug -> ExploreRequest.genre = slug; nav.navigate("explore") { popUpTo("home"); launchSingleTop = true; restoreState = true } }) { sid, cid -> nav.reader(sid, cid) }
            }
            composable("reader/{sid}/{cid}") { e ->
                val sid = e.arguments?.getString("sid").orEmpty()
                ReaderScreen(sid, e.arguments?.getString("cid").orEmpty(), nav::popBackStack,
                    onSeries = { if (nav.previousBackStackEntry?.destination?.route == "series/{id}") nav.popBackStack() else nav.detail(sid) }) {
                    nav.reader(sid, it, replace = true)
                }
            }
        }
    }
    } // CompositionLocalProvider
}
