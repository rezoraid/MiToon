package id.mitoon.app

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
import kotlin.math.roundToInt

private const val MAX_ZOOM = 4f

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

/** Zoom hanya lewat cubit dua jari (dan geser samping saat diperbesar). Ketuk dua kali tidak memperbesar. */
@Composable
fun ZoomLayer(state: ZoomState, list: LazyListState, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().clipToBounds().pointerInput(state) {
        awaitEachGesture {
            val w = size.width.toFloat()
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var multi = false
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = ev.changes.count { it.pressed }
                if (pressed >= 2) {
                    multi = true
                    val z = ev.calculateZoom(); val pan = ev.calculatePan(); val c = ev.calculateCentroid(useCurrent = true)
                    if (z != 1f || pan != Offset.Zero) { state.zoomBy(z, c.x, c.y, pan.x, pan.y, w, list); ev.changes.forEach { if (it.positionChanged()) it.consume() } }
                } else if (pressed == 1 && !multi && state.scale > 1f) state.panX(ev.calculatePan().x, w)
            } while (ev.changes.any { it.pressed })
        }
    }) {
        Box(Modifier.layout { m, c ->
            val p = m.measure(Constraints.fixed(c.maxWidth, (c.maxHeight / state.scale).roundToInt().coerceAtLeast(1)))
            layout(p.width, p.height) { p.place(0, 0) }
        }.graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = state.scale; scaleY = state.scale; translationX = state.offX }) { content() }
    }
}
