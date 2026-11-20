package id.mitoon.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private val Ink = SolidColor(Color.Black)
private fun icon(name: String, mirror: Boolean = false, b: ImageVector.Builder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply(b).build()
private fun ImageVector.Builder.line(d: String, w: Float) = addPath(addPathNodes(d), stroke = Ink, strokeLineWidth = w, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
private fun ImageVector.Builder.solid(d: String) = addPath(addPathNodes(d), fill = Ink)
private fun ImageVector.Builder.eo(d: String) = addPath(addPathNodes(d), pathFillType = PathFillType.EvenOdd, fill = Ink)
private fun ImageVector.Builder.both(d: String, w: Float) = addPath(addPathNodes(d), fill = Ink, stroke = Ink, strokeLineWidth = w, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)

object Ic {
    val Left = icon("Left", true) { line("M15 4.6L7.6 12L15 19.4", 2.8f) }
    val Right = icon("Right", true) { line("M9 4.6L16.4 12L9 19.4", 2.8f) }
    val Down = icon("Down") { line("M4.6 9L12 16.4L19.4 9", 2.8f) }
    val Up = icon("Up") { line("M4.6 15L12 7.6L19.4 15", 2.8f) }
    val Search = icon("Search") { line("M10.5 3.8a6.7 6.7 0 1 0 0 13.4a6.7 6.7 0 0 0 0-13.4z", 2.6f); line("M15.6 15.6L20.4 20.4", 2.9f) }
    val Home = icon("Home") { line("M12 3.2a1.7 1.7 0 0 0-1.1.4L3.8 9.4A1.7 1.7 0 0 0 3.2 10.7V19.3A1.8 1.8 0 0 0 5 21.1H8.7V15.7a1 1 0 0 1 1-1h4.6a1 1 0 0 1 1 1V21.1H19A1.8 1.8 0 0 0 20.8 19.3V10.7A1.7 1.7 0 0 0 20.2 9.4L13.1 3.6A1.7 1.7 0 0 0 12 3.2z", 2.3f) }
    val HomeOn = icon("HomeOn") { both("M12 3.2a1.7 1.7 0 0 0-1.1.4L3.8 9.4A1.7 1.7 0 0 0 3.2 10.7V19.3A1.8 1.8 0 0 0 5 21.1H8.7V15.7a1 1 0 0 1 1-1h4.6a1 1 0 0 1 1 1V21.1H19A1.8 1.8 0 0 0 20.8 19.3V10.7A1.7 1.7 0 0 0 20.2 9.4L13.1 3.6A1.7 1.7 0 0 0 12 3.2z", 1.4f) }
    val Heart = icon("Heart") { line("M12 20.6C12 20.6 3.2 15.2 3.2 8.8C3.2 6 5.4 3.9 8 3.9c1.6 0 3.1.8 4 2.1c.9-1.3 2.4-2.1 4-2.1c2.6 0 4.8 2.1 4.8 4.9C20.8 15.2 12 20.6 12 20.6z", 2.3f) }
    val HeartOn = icon("HeartOn") { both("M12 20.6C12 20.6 3.2 15.2 3.2 8.8C3.2 6 5.4 3.9 8 3.9c1.6 0 3.1.8 4 2.1c.9-1.3 2.4-2.1 4-2.1c2.6 0 4.8 2.1 4.8 4.9C20.8 15.2 12 20.6 12 20.6z", 1.4f) }
    val Bell = icon("Bell") { both("M12 2.9c-.7 0-1.2.5-1.2 1.2v.4C7.9 5.2 6.1 7.6 6.1 10.5v3.2c0 1.2-.5 2.3-1.4 3.1-.5.5-.2 1.4.6 1.4h13.4c.8 0 1.1-.9.6-1.4-.9-.8-1.4-1.9-1.4-3.1v-3.2c0-2.9-1.8-5.3-4.7-6V4.1c0-.7-.5-1.2-1.2-1.2z", 1.2f); both("M9.6 19.9h4.8a2.4 2.4 0 0 1-4.8 0z", 1.2f) }
    val Person = icon("Person") { both("M12 3a4.5 4.5 0 1 0 0 9a4.5 4.5 0 0 0 0-9z", 1.2f); both("M12 13.7c-4.4 0-7.8 2.1-7.8 5 0 1.4 1 2.4 2.5 2.4h10.6c1.5 0 2.5-1 2.5-2.4 0-2.9-3.4-5-7.8-5z", 1.2f) }
    val Star = icon("Star") { both("M12 3.4l2.6 5.4 5.9.8-4.3 4.1 1.1 5.9L12 16.8 6.7 19.6l1.1-5.9-4.3-4.1 5.9-.8z", 1.8f) }
    val Gear = icon("Gear") { eo("M10.29 4.59L10.61 2.7L13.39 2.7L13.71 4.59L16.03 5.55L17.59 4.44L19.56 6.41L18.45 7.97L19.41 10.29L21.3 10.61L21.3 13.39L19.41 13.71L18.45 16.03L19.56 17.59L17.59 19.56L16.03 18.45L13.71 19.41L13.39 21.3L10.61 21.3L10.29 19.41L7.97 18.45L6.41 19.56L4.44 17.59L5.55 16.03L4.59 13.71L2.7 13.39L2.7 10.61L4.59 10.29L5.55 7.97L4.44 6.41L6.41 4.44L7.97 5.55zM12 8.6a3.4 3.4 0 1 0 0 6.8a3.4 3.4 0 0 0 0-6.8z") }
    val Lock = icon("Lock") { both("M7.2 10.4h9.6A2.2 2.2 0 0 1 19 12.6v5.7a2.2 2.2 0 0 1-2.2 2.2H7.2A2.2 2.2 0 0 1 5 18.3V12.6a2.2 2.2 0 0 1 2.2-2.2z", 1.0f); line("M8 10.6V8a4 4 0 0 1 8 0v2.6", 2.4f) }
    val Close = icon("Close") { line("M6 6L18 18M18 6L6 18", 2.8f) }
    val Edit = icon("Edit") { both("M4.9 15.9L15.8 5a2.1 2.1 0 0 1 3 0l.2.2a2.1 2.1 0 0 1 0 3L8.1 19.1 3.7 20.3z", 1.5f) }
    val Trash = icon("Trash") { line("M4 6.8h16", 2.4f); line("M9.4 6.4V5.2c0-.9.7-1.6 1.6-1.6h2c.9 0 1.6.7 1.6 1.6v1.2", 2.2f); both("M5.8 9.2h12.4l-.8 9.5a2.3 2.3 0 0 1-2.3 2.1H8.9a2.3 2.3 0 0 1-2.3-2.1z", 1.2f) }
    val Refresh = icon("Refresh") { line("M20.6 12a8.6 8.6 0 1 1-2.5-6.1L20.6 8.4", 2.5f); line("M20.6 3.6v4.8h-4.8", 2.5f) }
    val Play = icon("Play") { both("M8 5.7v12.6c0 .8.9 1.3 1.6.8l9.5-6.3c.6-.4.6-1.2 0-1.6L9.6 4.9C8.9 4.4 8 4.9 8 5.7z", 1.4f) }
    val Info = icon("Info") { eo("M12 2.8a9.2 9.2 0 1 0 0 18.4a9.2 9.2 0 0 0 0-18.4zM12 10.5a1.1 1.1 0 0 1 1.1 1.1v4.7a1.1 1.1 0 0 1-2.2 0v-4.7a1.1 1.1 0 0 1 1.1-1.1zM12 6.5a1.3 1.3 0 1 1 0 2.6a1.3 1.3 0 0 1 0-2.6z") }
    val Tool = icon("Tool") { both("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z", 1.3f) }
    val Logout = icon("Logout", true) { line("M10 4H7.2A2.2 2.2 0 0 0 5 6.2v11.6A2.2 2.2 0 0 0 7.2 20H10", 2.3f); line("M10 12h10.2M16.6 8.4L20.2 12l-3.6 3.6", 2.5f) }
    val List = icon("List", true) { line("M9 7h11M9 12h11M9 17h11", 2.4f); line("M4.6 7v.05M4.6 12v.05M4.6 17v.05", 3.2f) }
    val ThumbUp = icon("ThumbUp") { line("M14 9.2V5.4a2.9 2.9 0 0 0-2.9-2.9L7.2 11.3v10.2h10.6a2 2 0 0 0 2-1.7l1.3-8.5a2 2 0 0 0-2-2.3z", 2.2f); line("M7.2 21.5H4.3a2 2 0 0 1-2-2v-6.2a2 2 0 0 1 2-2h2.9", 2.2f) }
    val Reply = icon("Reply") { both("M10.6 4.3L3.7 10.5a1.1 1.1 0 0 0 0 1.6l6.9 6.2a1 1 0 0 0 1.7-.8V15c4.3 0 6.9 1.5 8.2 4.7.1.3.6.3.6-.1 0-5.6-3.2-9.5-8.8-9.8V5.1a1 1 0 0 0-1.7-.8z", 1.2f) }
    val Filter = icon("Filter") { line("M4 7h16M7 12h10M10 17h4", 2.5f) }
    val ThumbUpOn = icon("ThumbUpOn") { both("M14 9.2V5.4a2.9 2.9 0 0 0-2.9-2.9L7.2 11.3v10.2h10.6a2 2 0 0 0 2-1.7l1.3-8.5a2 2 0 0 0-2-2.3z", 2.0f); both("M7.2 21.5H4.3a2 2 0 0 1-2-2v-6.2a2 2 0 0 1 2-2h2.9", 2.0f) }
    val StarOn = icon("StarOn") { both("M12 3.4l2.6 5.4 5.9.8-4.3 4.1 1.1 5.9L12 16.8 6.7 19.6l1.1-5.9-4.3-4.1 5.9-.8z", 1.8f) }
}
