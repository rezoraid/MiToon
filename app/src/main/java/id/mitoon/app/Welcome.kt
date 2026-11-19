package id.mitoon.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Isi aplikasi masuk dengan zoom lembut bila baru login. (Animasi sambutan lama sudah diganti layar pembuka di AuthScreen.) */
@Composable
fun AppEntrance(content: @Composable () -> Unit) {
    val animate = remember { Auth.pendingEntrance.also { Auth.pendingEntrance = false } }
    val s = remember { Animatable(if (animate) 0.94f else 1f) }
    LaunchedEffect(Unit) { if (animate) s.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxSize().graphicsLayer { scaleX = s.value; scaleY = s.value }) { content() }
}
