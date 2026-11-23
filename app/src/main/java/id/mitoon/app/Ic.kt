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
private fun ImageVector.Builder.eo(d: String) = addPath(addPathNodes(d), pathFillType = PathFillType.EvenOdd, fill = Ink)
private fun ImageVector.Builder.both(d: String, w: Float) = addPath(addPathNodes(d), fill = Ink, stroke = Ink, strokeLineWidth = w, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)

object Ic {
    val Left = icon("Left", true) { line("M14.6 5L8.2 12l6.4 7", 3.2f) }
    val Right = icon("Right", true) { line("M9.4 5L15.8 12l-6.4 7", 3.2f) }
    val Down = icon("Down") { line("M5 9.4L12 15.8l7-6.4", 3.2f) }
    val Up = icon("Up") { line("M5 14.6L12 8.2l7 6.4", 3.2f) }
    val Search = icon("Search") { line("M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 0 0 0-13z", 2.7f); line("M15.4 15.4L19.8 19.8", 3.2f); line("M7.8 10.2a2.8 2.8 0 0 1 2-2.5", 1.8f) }
    val Home = icon("Home") { line("M3.8 11.2L10.9 4.5a1.5 1.5 0 0 1 2.2 0l7.1 6.7", 2.6f); line("M5.8 10v8.2a2.5 2.5 0 0 0 2.5 2.5h7.4a2.5 2.5 0 0 0 2.5-2.5V10", 2.4f); line("M10 20.6v-3.6a2 2 0 0 1 4 0v3.6", 2.2f) }
    val HomeOn = icon("HomeOn") { eo("M12 3.4c-.5 0-.9.2-1.3.5L4.2 9.6C3.6 10.1 3.2 10.9 3.2 11.7v6.9A2.6 2.6 0 0 0 5.8 21.2h12.4a2.6 2.6 0 0 0 2.6-2.6v-6.9c0-.8-.4-1.6-1-2.1L13.3 3.9c-.4-.3-.8-.5-1.3-.5zM12 14.6a2 2 0 0 0-2 2v4.6h4v-4.6a2 2 0 0 0-2-2zM8.8 11.5a.95.95 0 1 0 0 1.9a.95.95 0 0 0 0-1.9zM15.2 11.5a.95.95 0 1 0 0 1.9a.95.95 0 0 0 0-1.9z") }
    val Heart = icon("Heart") { line("M12 20.3C6.4 16.6 3.4 13.3 3.4 9.4A4.7 4.7 0 0 1 8.1 4.7c1.6 0 3 .8 3.9 2.1.9-1.3 2.3-2.1 3.9-2.1A4.7 4.7 0 0 1 20.6 9.4c0 3.9-3 7.2-8.6 10.9z", 2.5f) }
    val HeartOn = icon("HeartOn") { eo("M12 20.3C6.4 16.6 3.4 13.3 3.4 9.4A4.7 4.7 0 0 1 8.1 4.7c1.6 0 3 .8 3.9 2.1.9-1.3 2.3-2.1 3.9-2.1A4.7 4.7 0 0 1 20.6 9.4c0 3.9-3 7.2-8.6 10.9zM9 9.8a1 1 0 1 0 0 2a1 1 0 0 0 0-2zM15 9.8a1 1 0 1 0 0 2a1 1 0 0 0 0-2zM10.5 12.9h3a1.5 1.5 0 0 1-3 0z") }
    val Bell = icon("Bell") { eo("M12 3.6a1 1 0 0 1 1 1v.7c2.4.6 4 2.7 4 5.2v2.9c0 .9.4 1.8 1 2.4.6.6.2 1.6-.7 1.6H6.7c-.9 0-1.3-1-.7-1.6.6-.6 1-1.5 1-2.4v-2.9c0-2.5 1.6-4.6 4-5.2v-.7a1 1 0 0 1 1-1z"); eo("M9.3 19.2h5.4a2.7 2.7 0 0 1-5.4 0z") }
    val Person = icon("Person") { eo("M12 3.2a4.7 4.7 0 1 0 0 9.4a4.7 4.7 0 0 0 0-9.4zM10.2 7.3a.95.95 0 1 0 0 1.9a.95.95 0 0 0 0-1.9zM13.8 7.3a.95.95 0 1 0 0 1.9a.95.95 0 0 0 0-1.9z"); eo("M12 14.2c-4.2 0-7.4 2-7.4 4.8 0 1.2.9 2.2 2.3 2.2h10.2c1.4 0 2.3-1 2.3-2.2 0-2.8-3.2-4.8-7.4-4.8z") }
    val Star = icon("Star") { both("M12 3.6l2.5 5.2 5.7.8-4.1 4 1 5.7L12 16.6l-5.1 2.7 1-5.7-4.1-4 5.7-.8z", 2.4f) }
    val Gear = icon("Gear") { eo("M12.00 4.50A3.3 3.3 0 0 1 17.30 6.70A3.3 3.3 0 0 1 19.50 12.00A3.3 3.3 0 0 1 17.30 17.30A3.3 3.3 0 0 1 12.00 19.50A3.3 3.3 0 0 1 6.70 17.30A3.3 3.3 0 0 1 4.50 12.00A3.3 3.3 0 0 1 6.70 6.70A3.3 3.3 0 0 1 12.00 4.50zM12 8.7a3.3 3.3 0 1 0 0 6.6a3.3 3.3 0 0 0 0-6.6z") }
    val Lock = icon("Lock") { eo("M7.4 10.2h9.2A2.4 2.4 0 0 1 19 12.6v5.6a2.4 2.4 0 0 1-2.4 2.4H7.4A2.4 2.4 0 0 1 5 18.2v-5.6a2.4 2.4 0 0 1 2.4-2.4zM12 13.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 0 0 0-3z"); line("M8.2 10.4V8a3.8 3.8 0 0 1 7.6 0v2.4", 2.6f) }
    val Close = icon("Close") { line("M6.6 6.6L17.4 17.4M17.4 6.6L6.6 17.4", 3.1f) }
    val Edit = icon("Edit") { both("M4.9 15.9L15.8 5a2.1 2.1 0 0 1 3 0l.2.2a2.1 2.1 0 0 1 0 3L8.1 19.1 3.7 20.3z", 1.9f) }
    val Trash = icon("Trash") { line("M4.2 6.4h15.6", 2.8f); line("M9.6 6.2V5.4c0-.9.7-1.6 1.6-1.6h1.6c.9 0 1.6.7 1.6 1.6v.8", 2.2f); eo("M5.8 9.2h12.4l-.8 9.6a2.4 2.4 0 0 1-2.4 2.2H9a2.4 2.4 0 0 1-2.4-2.2zM9.8 13.3a.95.95 0 1 0 0 1.9a.95.95 0 0 0 0-1.9zM14.2 13.3a.95.95 0 1 0 0 1.9a.95.95 0 0 0 0-1.9z") }
    val Refresh = icon("Refresh") { line("M20.4 12a8.4 8.4 0 1 1-2.5-5.9L20.4 8.6", 2.7f); line("M20.4 3.8v4.8h-4.8", 2.7f) }
    val Comment = icon("Comment") { line("M7.9 20A9 9 0 1 0 4 16.1L2.6 21.4z", 2.4f); line("M8.4 12v.05M12 12v.05M15.6 12v.05", 3.2f) }
    val Refetch = icon("Refetch") { line("M3.5 12a8.5 8.5 0 0 1 14.6-5.9L20.5 8.5", 2.4f); line("M20.5 3.6v4.9h-4.9", 2.4f); line("M20.5 12a8.5 8.5 0 0 1-14.6 5.9L3.5 15.5", 2.4f); line("M3.5 20.4v-4.9h4.9", 2.4f) }
    val Play = icon("Play") { both("M8.2 5.9v12.2c0 .9 1 1.4 1.7.9l8.9-6.1c.6-.4.6-1.3 0-1.7L9.9 5c-.7-.5-1.7 0-1.7.9z", 2.2f) }
    val Info = icon("Info") { eo("M12 2.8a9.2 9.2 0 1 0 0 18.4a9.2 9.2 0 0 0 0-18.4zM12 10.5a1.2 1.2 0 0 1 1.2 1.2v4.6a1.2 1.2 0 0 1-2.4 0v-4.6a1.2 1.2 0 0 1 1.2-1.2zM12 6.4a1.4 1.4 0 1 1 0 2.8a1.4 1.4 0 0 1 0-2.8z") }
    val Tool = icon("Tool") { both("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z", 1.6f) }
    val Logout = icon("Logout", true) { line("M10 4H7.2A2.2 2.2 0 0 0 5 6.2v11.6A2.2 2.2 0 0 0 7.2 20H10", 2.6f); line("M10 12h10.2M16.6 8.4L20.2 12l-3.6 3.6", 2.7f) }
    val List = icon("List", true) { line("M9 7h11M9 12h11M9 17h11", 2.6f); line("M4.8 7v.05M4.8 12v.05M4.8 17v.05", 3.8f) }
    val ThumbUp = icon("ThumbUp") { line("M14 9.2V5.4a2.9 2.9 0 0 0-2.9-2.9L7.2 11.3v10.2h10.6a2 2 0 0 0 2-1.7l1.3-8.5a2 2 0 0 0-2-2.3z", 2.4f); line("M7.2 21.5H4.3a2 2 0 0 1-2-2v-6.2a2 2 0 0 1 2-2h2.9", 2.4f) }
    val Reply = icon("Reply") { both("M10.6 4.3L3.7 10.5a1.1 1.1 0 0 0 0 1.6l6.9 6.2a1 1 0 0 0 1.7-.8V15c4.3 0 6.9 1.5 8.2 4.7.1.3.6.3.6-.1 0-5.6-3.2-9.5-8.8-9.8V5.1a1 1 0 0 0-1.7-.8z", 1.6f) }
    val ThumbUpOn = icon("ThumbUpOn") { both("M14 9.2V5.4a2.9 2.9 0 0 0-2.9-2.9L7.2 11.3v10.2h10.6a2 2 0 0 0 2-1.7l1.3-8.5a2 2 0 0 0-2-2.3z", 2.2f); both("M7.2 21.5H4.3a2 2 0 0 1-2-2v-6.2a2 2 0 0 1 2-2h2.9", 2.2f) }
    val StarOn = icon("StarOn") { both("M12 3.6l2.5 5.2 5.7.8-4.1 4 1 5.7L12 16.6l-5.1 2.7 1-5.7-4.1-4 5.7-.8z", 2.4f) }
    val Download = icon("Download") { line("M12 3.8v10.6", 2.8f); line("M7.2 10.2L12 15l4.8-4.8", 2.8f); line("M5 19.6h14", 2.8f) }
    val DownloadOn = icon("DownloadOn") { line("M12 3.4v11.6", 3.3f); line("M7 10.2L12 15.2l5-5", 3.3f); line("M4.6 19.8h14.8", 3.4f) }
    val Check = icon("Check") { line("M5.2 12.8L10 17.4L18.8 7.2", 3.1f) }
    val Trophy = icon("Trophy") { line("M7.6 3.8h8.8v5.4a4.4 4.4 0 0 1-8.8 0z", 2.4f); line("M7.6 5.8H4.5v1.3a3.5 3.5 0 0 0 3.3 3.5", 2.2f); line("M16.4 5.8h3.1v1.3a3.5 3.5 0 0 1-3.3 3.5", 2.2f); line("M12 13.6v3.6", 2.4f); line("M8.2 20.2h7.6", 2.8f) }
    val TrophyOn = icon("TrophyOn") { eo("M7.4 3.8h9.2v5.4a4.6 4.6 0 0 1-9.2 0zM10.2 7.2a.9.9 0 1 0 0 1.8a.9.9 0 0 0 0-1.8zM13.8 7.2a.9.9 0 1 0 0 1.8a.9.9 0 0 0 0-1.8z"); line("M7.6 5.8H4.5v1.3a3.5 3.5 0 0 0 3.3 3.5", 2.2f); line("M16.4 5.8h3.1v1.3a3.5 3.5 0 0 1-3.3 3.5", 2.2f); line("M12 13.6v3.6", 2.6f); line("M8.2 20.2h7.6", 2.8f) }
}
