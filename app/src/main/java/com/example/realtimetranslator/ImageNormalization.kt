package com.example.realtimetranslator


import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

object ImageNormalization {

    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
        return mat
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    fun deskew(mat: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        val lines = Mat()
        Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180, 100)

        if (lines.rows() == 0) return mat

        var angleSum = 0.0
        for (i in 0 until lines.rows()) {
            angleSum += lines[i, 0][1]
        }

        var angle = angleSum / lines.rows()
        angle = angle * 180 / Math.PI - 90

        val center = Point(mat.width() / 2.0, mat.height() / 2.0)
        val rotMat = Imgproc.getRotationMatrix2D(center, angle, 1.0)

        val rotated = Mat()
        Imgproc.warpAffine(mat, rotated, rotMat, mat.size())

        return rotated
    }

    fun perspectiveCorrect(mat: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 75.0, 200.0)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return mat

        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(
            MatOfPoint2f(*largest.toArray()),
            approx,
            10.0,
            true
        )

        if (approx.total() != 4L) return mat

        val src = approx.toArray()
        val dst = arrayOf(
            Point(0.0, 0.0),
            Point(mat.width().toDouble(), 0.0),
            Point(mat.width().toDouble(), mat.height().toDouble()),
            Point(0.0, mat.height().toDouble())
        )

        val transform = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(*src),
            MatOfPoint2f(*dst)
        )

        val warped = Mat()
        Imgproc.warpPerspective(mat, warped, transform, mat.size())

        return warped
    }

    fun preprocess(bitmap: Bitmap): Bitmap {
        // 1. Bitmap → Mat
        var mat = bitmapToMat(bitmap)

        // 2. Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)

        // 3. Improve contrast (VERY important for OCR)
        Imgproc.equalizeHist(gray, gray)

        // 4. Adaptive threshold (handles curved surfaces & lighting)
        val thresh = Mat()
        Imgproc.adaptiveThreshold(
            gray,
            thresh,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            31,
            5.0
        )

        // 5. Light denoise
        Imgproc.medianBlur(thresh, thresh, 3)

        // 6. Convert back to Bitmap
        return matToBitmap(thresh)
    }

}
