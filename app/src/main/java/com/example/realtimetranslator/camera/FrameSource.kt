package com.example.realtimetranslator.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/** Width of the frame once sensor rotation has been applied. */
val ImageProxy.uprightWidth: Int
    get() = if (imageInfo.rotationDegrees % 180 == 0) width else height

/** Height of the frame once sensor rotation has been applied. */
val ImageProxy.uprightHeight: Int
    get() = if (imageInfo.rotationDegrees % 180 == 0) height else width

/**
 * Full-resolution upright RGB bitmap.
 *
 * This performs a YUV to RGB conversion and, on most phones, a rotation, so it
 * is only used for frames that actually go to the recognizer - never on the
 * per-frame tracking path, which reads the luminance plane instead.
 */
fun ImageProxy.uprightBitmap(): Bitmap? {
    val raw = try {
        toBitmap()
    } catch (throwable: Throwable) {
        return null
    }

    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return raw

    return try {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        rotated
    } catch (throwable: Throwable) {
        raw
    }
}
