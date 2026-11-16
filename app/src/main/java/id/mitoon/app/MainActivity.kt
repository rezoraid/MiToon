package id.mitoon.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*

private data class Tab(val route: String, val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Aplikasi selalu gelap: ikon status/navigasi harus terang, apa pun mode sistem perangkat.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { MiToonTheme { App() } }
    }
}

private fun NavHostController.detail(id: String) = navigate("series/${Uri.encode(id)}")
private fun NavHostController.detail(s: Series) { Net.previews[s.id] = s; detail(s.id) }
private fun NavHostController.reader(sid: String, cid: String, replace: Boolean = false) =
    navigate("reader/${Uri.encode(sid)}/${Uri.encode(cid)}") { if (replace) popUpTo("reader/{sid}/{cid}") { inclusive = true } }

@Composable
private fun App() {
    val nav = rememberNavController()
    val tabs = listOf(Tab("home", "Home", Icons.Default.Home), Tab("explore", "Explore", Icons.Default.Search), Tab("library", "Library", Icons.Default.Favorite))
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(containerColor = Bg, bottomBar = {
        if (tabs.any { it.route == route }) NavigationBar(containerColor = CardBg) {
            tabs.forEach { t ->
                NavigationBarItem(route == t.route, {
                    nav.navigate(t.route) { popUpTo("home"); launchSingleTop = true; restoreState = true }
                }, { Icon(t.icon, t.label) }, label = { Text(t.label, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent,
                        indicatorColor = Color.Transparent, unselectedIconColor = Muted, unselectedTextColor = Muted))
            }
        }
    }) { pad ->
        // Default Navigation memakai fade ~700ms -> terasa lambat. Dimatikan supaya perpindahan layar instan.
        NavHost(nav, "home", Modifier.padding(pad),
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
            composable("home") {
                HomeScreen({ nav.detail(it) }, { nav.navigate("search") }) {
                    nav.navigate("explore") { popUpTo("home"); launchSingleTop = true; restoreState = true }
                }
            }
            composable("explore") { ExploreScreen({ nav.detail(it) }, { nav.navigate("search") }) }
            composable("library") {
                LibraryScreen({ nav.detail(it) }, { e -> e.lastChapterId?.let { nav.reader(e.id, it) } ?: nav.detail(e.id) }, { nav.navigate("search") })
            }
            composable("search") { SearchScreen(nav::popBackStack) { nav.detail(it) } }
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
}
