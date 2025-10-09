package com.example.collisionavoidancesystem.laneKeepAssist



import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.max

private const val TAG = "LaneDetector"

/**
 * Main function to perform lane detection using the ported Python OpenCV logic.
 *
 * @param srcBitmap The input frame from the camera as a Bitmap.
 * @return A Bitmap with the detected lanes drawn on it, or the original Bitmap if an error occurs.
 */
fun detectAndOverlayLanes(srcBitmap: Bitmap): Bitmap {
    val outputBitmap = srcBitmap.copy(srcBitmap.config, true)
    val srcMat = Mat()
    Utils.bitmapToMat(srcBitmap, srcMat)

    // Ensure the Mat is 3-channel (color) for the final overlay
    if (srcMat.channels() < 3) Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_GRAY2RGB)

    val processedMat = Mat()
    val width = srcMat.cols()
    val height = srcMat.rows()

    // 1. Grayscale Conversion
    Imgproc.cvtColor(srcMat, processedMat, Imgproc.COLOR_RGB2GRAY)

    // 2. Canny Edge Detection (Python: 100, 200)
    // We use a separate Mat for the edge image as Canny output is single channel.
    val cannyMat = Mat()
    Imgproc.Canny(processedMat, cannyMat, 100.0, 200.0)

    // 3. Region of Interest (ROI) Masking
    val mask = Mat.zeros(cannyMat.size(), CvType.CV_8UC1)
    val roiVertices = MatOfPoint(
        Point(0.0, height.toDouble()),
        Point(width / 2.0, height / 2.0),
        Point(width.toDouble(), height.toDouble())
    )
    val points = listOf(roiVertices)
    Imgproc.fillPoly(mask, points, Scalar(255.0))

    val croppedCanny = Mat()
    Core.bitwise_and(cannyMat, mask, croppedCanny)

    // 4. Hough Transform to detect line segments (Python params)
    val lines = Mat()
    Imgproc.HoughLinesP(
        croppedCanny,
        lines,
        6.0,                          // rho (distance resolution)
        PI / 60,                     // theta (angle resolution)
        160,                          // threshold (min number of votes)
        40.0,                         // minLineLength
        25.0                          // maxLineGap
    )

    if (lines.empty()) {
        Utils.matToBitmap(srcMat, outputBitmap)
        return outputBitmap // Return original if no lines detected
    }

    // 5. Line Grouping and Averaging
    val leftLineX = mutableListOf<Double>()
    val leftLineY = mutableListOf<Double>()
    val rightLineX = mutableListOf<Double>()
    val rightLineY = mutableListOf<Double>()

    // Lines are stored as [x1, y1, x2, y2]
    for (i in 0 until lines.rows()) {
        val l = lines.get(i, 0)
        val x1 = l[0]
        val y1 = l[1]
        val x2 = l[2]
        val y2 = l[3]

        if (x2 == x1) continue // Avoid division by zero

        val slope = (y2 - y1) / (x2 - x1)

        if (abs(slope) < 0.5) continue // Only consider extreme slope

        if (slope <= 0) { // Left line (negative slope in top-left origin system)
            leftLineX.add(x1)
            leftLineX.add(x2)
            leftLineY.add(y1)
            leftLineY.add(y2)
        } else { // Right line (positive slope)
            rightLineX.add(x1)
            rightLineX.add(x2)
            rightLineY.add(y1)
            rightLineY.add(y2)
        }
    }

    val min_y = (height * (3.0 / 5.0)).toInt() // Just below the horizon
    val max_y = height.toInt() // The bottom of the image

    // Helper to calculate the single averaged line endpoints
    fun getLineEndpoints(xCoords: List<Double>, yCoords: List<Double>): IntArray? {
        if (xCoords.isEmpty() || yCoords.isEmpty()) return null

        // Polyfit equivalent: Solve for m and c in y = mx + c (or x = my + c)
        // Since we are fitting x on y (x = f(y)), we use polyfit(y, x, 1)
        val yMat = Mat(yCoords.size, 1, CvType.CV_64FC1)
        yMat.put(0, 0, yCoords.toDoubleArray())
        val xMat = Mat(xCoords.size, 1, CvType.CV_64FC1)
        xMat.put(0, 0, xCoords.toDoubleArray())

        val p = Mat() // Output for coefficients [m, c]
        Core.solve(yMat, xMat, p, Core.DECOMP_NORMAL)

        // Note: OpenCV doesn't have a direct `polyfit` for 1D. A simpler approach is linear regression.
        // For simplicity and direct porting, we will use a common linear fit if available,
        // but for a robust solution in Android, manual linear regression or a specialized library is better.
        // However, let's simplify for this code block by returning average slope/intercept for demonstration.

        // Manual Linear Regression for x = my + c
        val n = yCoords.size.toDouble()
        val sumY = yCoords.sum()
        val sumX = xCoords.sum()
        val sumY2 = yCoords.sumOf { it * it }
        val sumXY = yCoords.zip(xCoords) { y, x -> y * x }.sum()

        // Solve: [ (n * sumXY) - (sumX * sumY) ] / [ (n * sumY2) - (sumY * sumY) ]
        val m = if (abs(n * sumY2 - sumY * sumY) > 1e-6) {
            (n * sumXY - sumX * sumY) / (n * sumY2 - sumY * sumY)
        } else {
            0.0
        }
        val c = (sumX / n) - m * (sumY / n) // c = avgX - m * avgY

        // x = my + c
        val xStart = (m * max_y + c).toInt()
        val xEnd = (m * min_y + c).toInt()

        return intArrayOf(xStart, max_y, xEnd, min_y)
    }

    val leftLine = getLineEndpoints(leftLineX, leftLineY)
    val rightLine = getLineEndpoints(rightLineX, rightLineY)

    // 6. Rendering (Draw the new lines on the original Mat)
    val lineMat = Mat.zeros(srcMat.size(), srcMat.type())
    val thickness = 10
    val color = Scalar(255.0, 0.0, 0.0) // Red color for overlay

    if (leftLine != null) {
        Imgproc.line(
            lineMat,
            Point(leftLine[0].toDouble(), leftLine[1].toDouble()),
            Point(leftLine[2].toDouble(), leftLine[3].toDouble()),
            color,
            thickness
        )
    }
    if (rightLine != null) {
        Imgproc.line(
            lineMat,
            Point(rightLine[0].toDouble(), rightLine[1].toDouble()),
            Point(rightLine[2].toDouble(), rightLine[3].toDouble()),
            color,
            thickness
        )
    }

    // Merge the line Mat with the original frame (addWeighted)
    Core.addWeighted(srcMat, 0.8, lineMat, 1.0, 0.0, srcMat)

    // Convert the final Mat back to Bitmap
    Utils.matToBitmap(srcMat, outputBitmap)
    return outputBitmap
}

/**
 * Placeholder for simplified lane departure check.
 * This should be expanded in a real system.
 */
fun checkLaneDeparture(lanes: Pair<IntArray?, IntArray?>?, frameWidth: Int): Float {
    if (lanes?.first == null || lanes.second == null) return 0f // No lines detected

    val leftXEnd = lanes.first!![2]
    val rightXEnd = lanes.second!![2]

    val centerOfLane = (leftXEnd + rightXEnd) / 2
    val centerOfFrame = frameWidth / 2

    // Deviation scaled to a -1.0 to 1.0 range (e.g., -0.5 is a left deviation)
    return (centerOfFrame - centerOfLane).toFloat() / (frameWidth / 4)
}

// Data class to hold the calculated lane lines (endpoints: x_start, y_start, x_end, y_end)
data class LaneLines(
    val left: IntArray?,
    val right: IntArray?
)