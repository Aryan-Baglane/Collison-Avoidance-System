package com.example.collisionavoidancesystem.laneKeepAssist

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import com.example.collisionavoidancesystem.service.PartnerDto
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

// Detect lanes from camera frame using OpenCV
fun detectLanes(frame: Bitmap): List<LaneLine> {
    if (!OpenCVLoader.initDebug()) {
        Log.e("OpenCV", "OpenCV not loaded!")
        return emptyList()
    }

    val mat = Mat()
    Utils.bitmapToMat(frame, mat)

    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY)
    Imgproc.GaussianBlur(mat, mat, org.opencv.core.Size(5.0, 5.0), 0.0)
    Imgproc.Canny(mat, mat, 50.0, 150.0)

    val lines = Mat()
    Imgproc.HoughLinesP(mat, lines, 1.0, Math.PI / 180, 50, 50.0, 10.0)

    val laneLines = mutableListOf<LaneLine>()
    for (i in 0 until lines.rows()) {
        val l = lines[i, 0]
        laneLines.add(
            LaneLine(
                start = Offset(l[0].toFloat(), l[1].toFloat()),
                end = Offset(l[2].toFloat(), l[3].toFloat())
            )
        )
    }
    return laneLines
}

data class LaneLine(val start: Offset, val end: Offset)

enum class LaneDeparture { LEFT, RIGHT }

fun checkLaneDeparture(laneLines: List<LaneLine>, frameWidth: Int): LaneDeparture? {
    if (laneLines.isEmpty()) return null
    val leftLane = laneLines.minByOrNull { it.start.x + it.end.x } ?: return null
    val rightLane = laneLines.maxByOrNull { it.start.x + it.end.x } ?: return null
    val centerLaneX = (leftLane.start.x + rightLane.end.x) / 2

    return when {
        centerLaneX < frameWidth * 0.4 -> LaneDeparture.LEFT
        centerLaneX > frameWidth * 0.6 -> LaneDeparture.RIGHT
        else -> null
    }
}

@Composable
fun LaneOverlay(
    laneLines: List<LaneLine>,
    laneDeparture: LaneDeparture? = null,
    partners: List<PartnerDto> = emptyList()
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Draw lane lines
        laneLines.forEach { line ->
            drawLine(
                color = Color.Yellow,
                start = line.start,
                end = line.end,
                strokeWidth = 6f
            )
        }

        // Draw lane departure alert
        laneDeparture?.let {
            val warningText = if (it == LaneDeparture.LEFT) "🚨 LEFT LANE!" else "🚨 RIGHT LANE!"
            drawContext.canvas.nativeCanvas.apply {
                drawText(
                    warningText,
                    size.width / 2,
                    100f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.RED
                        textSize = 64f
                        isFakeBoldText = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                )
            }
        }

        // Draw nearby vehicle indicators
        partners.forEach { partner ->
            if (partner.warning != "SAFE") {
                drawCircle(
                    color = if (partner.warning == "COLLISION_RISK") Color.Red else Color(0xFFFFA500),
                    radius = 20f,
                    center = Offset(100f, 100f) // TODO: map actual vehicle coordinates here
                )
            }
        }
    }
}
