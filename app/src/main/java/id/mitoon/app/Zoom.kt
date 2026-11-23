package id.mitoon.app

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f

@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offX by mutableFloatStateOf(0f)
    fun zoomBy(z: Float, cx: Float, cy: Float, panX: Float, panY: Float, w: Float, list: LazyListState) {
        val s0 = scale; val s1 = (s0 * z).coerceIn(1f, MAX_ZOOM); val k = s1 / s0
        scale = s1
        offX = (cx - (cx - offX) * k + panX).coerceIn(w - w * s1, 0f)
        list.dispatchRawDelta(cy / s0 - cy / s1 - panY / s1)
    }
    fun panX(dx: Float, w: Float) { offX = (offX + dx).coerceIn(w - w * scale, 0f) }
}

@Composable
fun ZoomLayer(state: ZoomState, list: LazyListState, onDoubleTap: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val dbl by rememberUpdatedState(onDoubleTap)
    Box(Modifier.fillMaxSize().clipToBounds().pointerInput(state) {
        var anim: Job? = null
        var lastUp = 0L
        var lastPos = Offset.Zero
        awaitEachGesture {
            val w = size.width.toFloat()
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val candidate = lastUp > 0 && down.uptimeMillis - lastUp < 300 && (down.position - lastPos).getDistance() < 90f
            var moved = false; var multi = false; var hit: Offset? = null
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = ev.changes.count { it.pressed }
                if (pressed >= 2) {
                    multi = true; anim?.cancel()
                    val z = ev.calculateZoom(); val pan = ev.calculatePan(); val c = ev.calculateCentroid(useCurrent = true)
                    if (z != 1f || pan != Offset.Zero) { state.zoomBy(z, c.x, c.y, pan.x, pan.y, w, list); ev.changes.forEach { if (it.positionChanged()) it.consume() } }
                } else if (pressed == 1 && !multi && state.scale > 1f) state.panX(ev.calculatePan().x, w)
                val first = ev.changes.first()
                if ((first.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (pressed == 0 && !moved && !multi && first.uptimeMillis - down.uptimeMillis < 300) {
                    if (candidate) { ev.changes.forEach { it.consume() }; hit = first.position } else { lastUp = first.uptimeMillis; lastPos = first.position }
                }
            } while (ev.changes.any { it.pressed })
            hit?.let { p ->
                lastUp = 0L
                dbl()
                val from = state.scale; val to = if (from > 1.05f) 1f else DOUBLE_TAP_ZOOM
                anim?.cancel()
                anim = scope.launch { animate(from, to, animationSpec = tween(220)) { v, _ -> state.zoomBy(v / state.scale, p.x, p.y, 0f, 0f, w, list) } }
            }
        }
    }) {
        Box(Modifier.layout { m, c ->
            val p = m.measure(Constraints.fixed(c.maxWidth, (c.maxHeight / state.scale).roundToInt().coerceAtLeast(1)))
            layout(p.width, p.height) { p.place(0, 0) }
        }.graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = state.scale; scaleY = state.scale; translationX = state.offX }) { content() }
    }
}
