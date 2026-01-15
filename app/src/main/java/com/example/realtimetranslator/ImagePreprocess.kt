package com.example.realtimetranslator

import android.graphics.*

object ImagePreprocess {

    fun normalizeForOcr(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height

        //  Convert to grayscale
        val grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayBitmap)
        val paint = Paint()

        val cm = ColorMatrix().apply {
            setSaturation(0f)
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        // ️ Mild contrast boost
        val contrast = 1.3f   // 1.0 = none, >1 increases contrast
        val brightness = 10f  // small brightness lift

        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val resultCanvas = Canvas(result)
        val contrastPaint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(contrastMatrix)
        }

        resultCanvas.drawBitmap(grayBitmap, 0f, 0f, contrastPaint)
        return result
    }
}