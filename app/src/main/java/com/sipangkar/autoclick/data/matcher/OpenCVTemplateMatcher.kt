package com.sipangkar.autoclick.data.matcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenCVTemplateMatcher @Inject constructor() {

    companion object {
        private const val TAG = "OpenCVTemplateMatcher"
    }

    data class MatchResult(
        val isMatch: Boolean,
        val x: Float,
        val y: Float,
        val score: Float
    )

    fun match(
        screenshot: Bitmap,
        templateImagePath: String,
        roiX: Int?,
        roiY: Int?,
        roiWidth: Int?,
        roiHeight: Int?,
        threshold: Float = 0.85f
    ): MatchResult {
        try {
            val templateFile = File(templateImagePath)
            if (!templateFile.exists()) {
                Log.e(TAG, "Template file does not exist: $templateImagePath")
                return MatchResult(false, 0f, 0f, 0f)
            }
            val templateBitmap = BitmapFactory.decodeFile(templateImagePath)
            if (templateBitmap == null) {
                Log.e(TAG, "Failed to decode template image: $templateImagePath")
                return MatchResult(false, 0f, 0f, 0f)
            }

            val screenshotMat = Mat()
            Utils.bitmapToMat(screenshot, screenshotMat)

            val templateMat = Mat()
            Utils.bitmapToMat(templateBitmap, templateMat)

            val grayScreenshot = Mat()
            val grayTemplate = Mat()
            Imgproc.cvtColor(screenshotMat, grayScreenshot, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(templateMat, grayTemplate, Imgproc.COLOR_RGBA2GRAY)

            var searchMat = grayScreenshot
            var offsetX = 0
            var offsetY = 0

            if (roiX != null && roiY != null && roiWidth != null && roiHeight != null) {
                val startX = roiX.coerceIn(0, screenshotMat.cols() - 1)
                val startY = roiY.coerceIn(0, screenshotMat.rows() - 1)
                val width = roiWidth.coerceAtMost(screenshotMat.cols() - startX)
                val height = roiHeight.coerceAtMost(screenshotMat.rows() - startY)

                if (width > 0 && height > 0) {
                    val roiRect = Rect(startX, startY, width, height)
                    searchMat = Mat(grayScreenshot, roiRect)
                    offsetX = startX
                    offsetY = startY
                }
            }

            if (searchMat.cols() < grayTemplate.cols() || searchMat.rows() < grayTemplate.rows()) {
                Log.e(TAG, "ROI size (${searchMat.cols()}x${searchMat.rows()}) is smaller than template size (${grayTemplate.cols()}x${grayTemplate.rows()})")
                return MatchResult(false, 0f, 0f, 0f)
            }

            val result = Mat()
            Imgproc.matchTemplate(searchMat, grayTemplate, result, Imgproc.TM_CCOEFF_NORMED)

            val mmr = Core.minMaxLoc(result)
            val maxVal = mmr.maxVal.toFloat()
            val maxLoc = mmr.maxLoc

            screenshotMat.release()
            templateMat.release()
            grayScreenshot.release()
            grayTemplate.release()
            if (searchMat != grayScreenshot) {
                searchMat.release()
            }
            result.release()

            Log.d(TAG, "Match max score: $maxVal (threshold: $threshold)")

            if (maxVal >= threshold) {
                val matchCenterX = offsetX + maxLoc.x + (templateBitmap.width / 2f)
                val matchCenterY = offsetY + maxLoc.y + (templateBitmap.height / 2f)
                return MatchResult(
                    isMatch = true,
                    x = matchCenterX.toFloat(),
                    y = matchCenterY.toFloat(),
                    score = maxVal
                )
            }

            return MatchResult(false, 0f, 0f, maxVal)

        } catch (e: Exception) {
            Log.e(TAG, "Template matching error", e)
            return MatchResult(false, 0f, 0f, 0f)
        }
    }
}
