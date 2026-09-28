package com.nextbench.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private inline fun nbVector(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

private fun ImageVector.Builder.line(block: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}

private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) {
    path(fill = SolidColor(Color.Black), pathBuilder = block)
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, 2 * r, 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, -2 * r, 0f)
    close()
}

object NbIcons {

    val Home: ImageVector by lazy {
        nbVector("Home") {
            line {
                moveTo(4f, 10f); lineTo(12f, 3.5f); lineTo(20f, 10f)
                lineTo(20f, 20f); lineTo(4f, 20f); close()
            }
            line {
                moveTo(9.5f, 20f); lineTo(9.5f, 14f); lineTo(14.5f, 14f); lineTo(14.5f, 20f)
            }
        }
    }

    val Marketplace: ImageVector by lazy {
        nbVector("Marketplace") {
            line {
                moveTo(5f, 8f); lineTo(19f, 8f); lineTo(18f, 20f); lineTo(6f, 20f); close()
            }
            line {
                moveTo(8.5f, 8f); lineTo(8.5f, 6.5f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15.5f, 6.5f)
                lineTo(15.5f, 8f)
            }
        }
    }

    val Plus: ImageVector by lazy {
        nbVector("Plus") {
            line { moveTo(12f, 5f); lineTo(12f, 19f) }
            line { moveTo(5f, 12f); lineTo(19f, 12f) }
        }
    }

    val Messages: ImageVector by lazy {
        nbVector("Messages") {
            line {
                moveTo(7f, 5f); lineTo(17f, 5f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20f, 8f)
                lineTo(20f, 12f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 15f)
                lineTo(12f, 15f); lineTo(8f, 19f); lineTo(8f, 15f); lineTo(7f, 15f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, 12f)
                lineTo(4f, 8f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7f, 5f)
                close()
            }
        }
    }

    val Profile: ImageVector by lazy {
        nbVector("Profile") {
            line {
                moveTo(8.5f, 8f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.5f, 8f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8.5f, 8f)
                close()
            }
            line {
                moveTo(5f, 20f)
                arcTo(7.2f, 7.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 19f, 20f)
            }
        }
    }

    val Search: ImageVector by lazy {
        nbVector("Search") {
            line {
                moveTo(4.5f, 10.5f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.5f, 10.5f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4.5f, 10.5f)
                close()
            }
            line { moveTo(15.5f, 15.5f); lineTo(20f, 20f) }
        }
    }

    val Bell: ImageVector by lazy {
        nbVector("Bell") {
            line {
                moveTo(6f, 11f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18f, 11f)
                lineTo(18f, 16f); lineTo(20f, 18.5f); lineTo(4f, 18.5f); lineTo(6f, 16f); close()
            }
            line {
                moveTo(10f, 18.5f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 14f, 18.5f)
            }
        }
    }

    val Heart: ImageVector by lazy {
        nbVector("Heart") {
            line {
                moveTo(12f, 20.5f)
                lineToRelative(-1.4f, -1.3f)
                curveToRelative(-5f, -4.5f, -8.3f, -7.5f, -8.3f, -11.2f)
                curveToRelative(0f, -3f, 2.4f, -5.3f, 5.4f, -5.3f)
                curveToRelative(1.7f, 0f, 3.3f, 0.8f, 4.3f, 2f)
                curveToRelative(1f, -1.2f, 2.6f, -2f, 4.3f, -2f)
                curveToRelative(3f, 0f, 5.4f, 2.3f, 5.4f, 5.3f)
                curveToRelative(0f, 3.7f, -3.3f, 6.7f, -8.3f, 11.2f)
                close()
            }
        }
    }

    val HeartFilled: ImageVector by lazy {
        nbVector("Heart filled") {
            solid {
                moveTo(12f, 21.2f)
                lineToRelative(-1.35f, -1.22f)
                curveToRelative(-5.05f, -4.55f, -8.35f, -7.55f, -8.35f, -11.45f)
                curveToRelative(0f, -3.18f, 2.48f, -5.73f, 5.58f, -5.73f)
                curveToRelative(1.75f, 0f, 3.32f, 0.82f, 4.12f, 2.08f)
                curveToRelative(0.8f, -1.26f, 2.37f, -2.08f, 4.12f, -2.08f)
                curveToRelative(3.1f, 0f, 5.58f, 2.55f, 5.58f, 5.73f)
                curveToRelative(0f, 3.9f, -3.3f, 6.9f, -8.35f, 11.45f)
                close()
            }
        }
    }

    val Bookmark: ImageVector by lazy {
        nbVector("Bookmark") {
            line {
                moveTo(6f, 4f); lineTo(18f, 4f); lineTo(18f, 20f)
                lineTo(12f, 15.5f); lineTo(6f, 20f); close()
            }
        }
    }

    val BookmarkFilled: ImageVector by lazy {
        nbVector("Bookmark filled") {
            solid {
                moveTo(6f, 3.5f); lineTo(18f, 3.5f); lineTo(18f, 21f)
                lineTo(12f, 16.45f); lineTo(6f, 21f); close()
            }
        }
    }

    val Archive: ImageVector by lazy {
        nbVector("Archive") {
            line {
                moveTo(4f, 7f); lineTo(20f, 7f); lineTo(19f, 20f); lineTo(5f, 20f); close()
            }
            line { moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 7f); lineTo(3f, 7f); close() }
            line { moveTo(9f, 11f); lineTo(15f, 11f) }
        }
    }

    val ArrowUp: ImageVector by lazy {
        nbVector("ArrowUp") {
            line { moveTo(12f, 5f); lineTo(12f, 19f) }
            line { moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) }
        }
    }

    val ArrowDown: ImageVector by lazy {
        nbVector("ArrowDown") {
            line { moveTo(12f, 5f); lineTo(12f, 19f) }
            line { moveTo(6f, 13f); lineTo(12f, 19f); lineTo(18f, 13f) }
        }
    }

    val Reply: ImageVector by lazy {
        nbVector("Reply") {
            line { moveTo(10f, 8f); lineTo(5f, 12.5f); lineTo(10f, 17f) }
            line {
                moveTo(5f, 12.5f); lineTo(15f, 12.5f)
                arcTo(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 19.5f, 17f)
                lineTo(19.5f, 18.5f)
            }
        }
    }

    val Share: ImageVector by lazy {
        nbVector("Share") {
            line { moveTo(8f, 11f); lineTo(16f, 6.5f) }
            line { moveTo(8f, 13f); lineTo(16f, 17.5f) }
            line { circle(6f, 12f, 2f) }
            line { circle(18f, 5.5f, 2f) }
            line { circle(18f, 18.5f, 2f) }
        }
    }

    val Copy: ImageVector by lazy {
        nbVector("Copy") {
            line {
                moveTo(9f, 8f); lineTo(18f, 8f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20f, 10f)
                lineTo(20f, 19f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18f, 21f)
                lineTo(9f, 21f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7f, 19f)
                lineTo(7f, 10f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9f, 8f)
                close()
            }
            line {
                moveTo(7f, 16f); lineTo(6f, 16f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, 14f)
                lineTo(4f, 5f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6f, 3f)
                lineTo(15f, 3f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 5f)
                lineTo(17f, 8f)
            }
        }
    }

    val More: ImageVector by lazy {
        nbVector("More") {
            solid { circle(5f, 12f, 1.6f) }
            solid { circle(12f, 12f, 1.6f) }
            solid { circle(19f, 12f, 1.6f) }
        }
    }

    val Back: ImageVector by lazy {
        nbVector("Back") { line { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }
    }

    val Close: ImageVector by lazy {
        nbVector("Close") {
            line { moveTo(6f, 6f); lineTo(18f, 18f) }
            line { moveTo(18f, 6f); lineTo(6f, 18f) }
        }
    }

    val Camera: ImageVector by lazy {
        nbVector("Camera") {
            line {
                moveTo(4.5f, 8f); lineTo(8.5f, 8f); lineTo(10f, 5.5f); lineTo(14f, 5.5f)
                lineTo(15.5f, 8f); lineTo(19.5f, 8f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 21f, 9.5f)
                lineTo(21f, 17.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 19.5f, 19f)
                lineTo(4.5f, 19f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 17.5f)
                lineTo(3f, 9.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4.5f, 8f)
                close()
            }
            line {
                moveTo(8.5f, 13.2f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15.5f, 13.2f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8.5f, 13.2f)
                close()
            }
        }
    }

    val Send: ImageVector by lazy {
        nbVector("Send") {
            line {
                moveTo(21f, 4f); lineTo(3f, 11f); lineTo(10f, 13.5f); lineTo(13f, 20.5f); close()
            }
            line { moveTo(21f, 4f); lineTo(10f, 13.5f) }
        }
    }

    val Check: ImageVector by lazy {
        nbVector("Check") { line { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 6.5f) } }
    }

    val Forward: ImageVector by lazy {
        nbVector("Forward") {
            line {
                moveTo(14f, 5f); lineTo(21f, 12f); lineTo(14f, 19f)
                moveTo(21f, 12f); lineTo(10f, 12f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 4f, 18f)
            }
        }
    }

    val Moon: ImageVector by lazy {
        nbVector("Moon") {
            line {
                moveTo(20f, 14.2f)
                arcTo(8.4f, 8.4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 9.8f, 3.5f)
                arcTo(8.2f, 8.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 14.2f)
                close()
            }
        }
    }

    val Sun: ImageVector by lazy {
        nbVector("Sun") {
            line {
                moveTo(12f, 4f)
                lineTo(12f, 2.5f)
                moveTo(12f, 21.5f)
                lineTo(12f, 20f)
                moveTo(4f, 12f)
                lineTo(2.5f, 12f)
                moveTo(21.5f, 12f)
                lineTo(20f, 12f)
                moveTo(6.3f, 6.3f)
                lineTo(5.2f, 5.2f)
                moveTo(18.8f, 18.8f)
                lineTo(17.7f, 17.7f)
                moveTo(17.7f, 6.3f)
                lineTo(18.8f, 5.2f)
                moveTo(5.2f, 18.8f)
                lineTo(6.3f, 17.7f)
                circle(12f, 12f, 3.3f)
            }
        }
    }

    val Mail: ImageVector by lazy {
        nbVector("Mail") {
            line {
                moveTo(4f, 6f); lineTo(20f, 6f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 21.5f, 7.5f)
                lineTo(21.5f, 16.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20f, 18f)
                lineTo(4f, 18f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.5f, 16.5f)
                lineTo(2.5f, 7.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4f, 6f)
                close()
            }
            line { moveTo(3f, 7f); lineTo(12f, 13.5f); lineTo(21f, 7f) }
        }
    }

    val Shield: ImageVector by lazy {
        nbVector("Shield") {
            line {
                moveTo(12f, 3f); lineTo(19f, 6f); lineTo(18f, 13f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 21f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6f, 13f)
                lineTo(5f, 6f); close()
            }
            line { moveTo(8.5f, 12f); lineTo(11f, 14.5f); lineTo(15.5f, 9.5f) }
        }
    }

    val Building: ImageVector by lazy {
        nbVector("Building") {
            line {
                moveTo(4f, 21f); lineTo(4f, 4f); lineTo(14f, 4f); lineTo(14f, 21f)
                moveTo(14f, 9f); lineTo(20f, 9f); lineTo(20f, 21f)
                moveTo(2f, 21f); lineTo(22f, 21f)
                moveTo(7f, 8f); lineTo(11f, 8f)
                moveTo(7f, 12f); lineTo(11f, 12f)
                moveTo(7f, 16f); lineTo(11f, 16f)
                moveTo(16.5f, 13f); lineTo(17.5f, 13f)
                moveTo(16.5f, 17f); lineTo(17.5f, 17f)
            }
        }
    }

    val ArrowRight: ImageVector by lazy {
        nbVector("ArrowRight") {
            line { moveTo(4f, 12f); lineTo(20f, 12f) }
            line { moveTo(14f, 6f); lineTo(20f, 12f); lineTo(14f, 18f) }
        }
    }

    val Logout: ImageVector by lazy {
        nbVector("Logout") {
            line { moveTo(10f, 5f); lineTo(5f, 5f); lineTo(5f, 19f); lineTo(10f, 19f) }
            line { moveTo(4f, 12f); lineTo(19f, 12f) }
            line { moveTo(14f, 7f); lineTo(19f, 12f); lineTo(14f, 17f) }
        }
    }

    val ChevronDown: ImageVector by lazy {
        nbVector("ChevronDown") { line { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) } }
    }

    val Filter: ImageVector by lazy {
        nbVector("Filter") {
            line {
                moveTo(4f, 5f); lineTo(20f, 5f); lineTo(14f, 12f)
                lineTo(14f, 19f); lineTo(10f, 21f); lineTo(10f, 12f); close()
            }
        }
    }

    val Upload: ImageVector by lazy {
        nbVector("Upload") {
            line { moveTo(12f, 16f); lineTo(12f, 4f) }
            line { moveTo(7f, 9f); lineTo(12f, 4f); lineTo(17f, 9f) }
            line { moveTo(5f, 20f); lineTo(19f, 20f) }
        }
    }

    val Refresh: ImageVector by lazy {
        nbVector("Refresh") {
            line { moveTo(20f, 11f); arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = false, 6.5f, 6.5f) }
            line { moveTo(20f, 5f); lineTo(20f, 11f); lineTo(14f, 11f) }
        }
    }

    val Play: ImageVector by lazy {
        nbVector("Play") {
            solid {
                moveTo(8f, 5f); lineTo(20f, 12f); lineTo(8f, 19f); close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        nbVector("Pause") {
            solid { moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close() }
            solid { moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close() }
        }
    }

    val Mic: ImageVector by lazy {
        nbVector("Microphone") {
            line {
                moveTo(12f, 4f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15f, 7f)
                lineTo(15f, 12f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 9f, 12f)
                lineTo(9f, 7f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 4f)
            }
            line { moveTo(5f, 11f); lineTo(5f, 12f); arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = false, 19f, 12f); lineTo(19f, 11f) }
            line { moveTo(12f, 19f); lineTo(12f, 22f); moveTo(8f, 22f); lineTo(16f, 22f) }
        }
    }

    val Stop: ImageVector by lazy {
        nbVector("Stop") { solid { moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close() } }
    }

    val FileText: ImageVector by lazy {
        nbVector("Document") {
            line {
                moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f)
                lineTo(6f, 21f); close()
                moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
                moveTo(9f, 12f); lineTo(16f, 12f)
                moveTo(9f, 16f); lineTo(16f, 16f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        nbVector("Trash") {
            line {
                moveTo(5f, 7f); lineTo(19f, 7f)
                moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
                moveTo(7f, 7f); lineTo(8f, 20f); lineTo(16f, 20f); lineTo(17f, 7f)
                moveTo(10f, 11f); lineTo(10.5f, 17f)
                moveTo(14f, 11f); lineTo(13.5f, 17f)
            }
        }
    }

    val Volume: ImageVector by lazy {
        nbVector("Volume") {
            line { moveTo(4f, 10f); lineTo(8f, 10f); lineTo(13f, 6f); lineTo(13f, 18f); lineTo(8f, 14f); lineTo(4f, 14f); close() }
            line { moveTo(16f, 9f); curveTo(18f, 10.5f, 18f, 13.5f, 16f, 15f) }
            line { moveTo(18.5f, 6.5f); curveTo(22f, 9.5f, 22f, 14.5f, 18.5f, 17.5f) }
        }
    }

    val VolumeOff: ImageVector by lazy {
        nbVector("Volume off") {
            line { moveTo(4f, 10f); lineTo(8f, 10f); lineTo(13f, 6f); lineTo(13f, 18f); lineTo(8f, 14f); lineTo(4f, 14f); close() }
            line { moveTo(16.5f, 10f); lineTo(21f, 14.5f) }
            line { moveTo(21f, 10f); lineTo(16.5f, 14.5f) }
        }
    }

    val List: ImageVector by lazy {
        nbVector("List") {
            line { moveTo(8f, 6f); lineTo(20f, 6f) }
            line { moveTo(8f, 12f); lineTo(20f, 12f) }
            line { moveTo(8f, 18f); lineTo(20f, 18f) }
            solid { circle(4f, 6f, 1.1f) }
            solid { circle(4f, 12f, 1.1f) }
            solid { circle(4f, 18f, 1.1f) }
        }
    }

    val Layout: ImageVector by lazy {
        nbVector("Layout") {
            line {
                moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
                moveTo(4f, 10f); lineTo(20f, 10f)
            }
        }
    }

    val Clock: ImageVector by lazy {
        nbVector("Clock") {
            line { circle(12f, 12f, 8.5f) }
            line { moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15.5f, 14f) }
        }
    }
}
