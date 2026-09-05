package com.example.realtimetranslator.tracking

import android.graphics.RectF
import com.example.realtimetranslator.model.OrientedBox
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A 2D similarity transform - rotation and uniform scale stored as the complex
 * number [a] + [b]i, plus a translation:
 *
 *     x' = a*x - b*y + tx
 *     y' = b*x + a*y + ty
 *
 * Frame-to-frame camera motion over a roughly planar surface is well described
 * by this, and it composes cheaply, which is what lets overlay boxes keep
 * following the scene while a recognizer pass is still in flight.
 */
data class Transform(val a: Double, val b: Double, val tx: Double, val ty: Double) {

    /** Uniform scale factor of this transform. */
    val scale: Double get() = sqrt(a * a + b * b)

    /** Rotation in degrees, which oriented boxes have to follow. */
    val rotationDegrees: Double get() = Math.toDegrees(atan2(b, a))

    fun mapX(x: Float, y: Float): Float = (a * x - b * y + tx).toFloat()

    fun mapY(x: Float, y: Float): Float = (b * x + a * y + ty).toFloat()

    /**
     * Maps an axis-aligned box by moving its corners and re-bounding them. The
     * result grows slightly under rotation, which is harmless here because these
     * bounds are only used for matching and sampling, never for drawing.
     */
    fun map(box: RectF): RectF {
        val xs = doubleArrayOf(
            a * box.left - b * box.top + tx,
            a * box.right - b * box.top + tx,
            a * box.left - b * box.bottom + tx,
            a * box.right - b * box.bottom + tx
        )
        val ys = doubleArrayOf(
            b * box.left + a * box.top + ty,
            b * box.right + a * box.top + ty,
            b * box.left + a * box.bottom + ty,
            b * box.right + a * box.bottom + ty
        )
        return RectF(
            xs.min().toFloat(),
            ys.min().toFloat(),
            xs.max().toFloat(),
            ys.max().toFloat()
        )
    }

    /** Oriented boxes lose nothing here: the rotation simply adds. */
    fun map(box: OrientedBox): OrientedBox {
        val factor = scale.toFloat()
        return OrientedBox(
            centerX = mapX(box.centerX, box.centerY),
            centerY = mapY(box.centerX, box.centerY),
            width = box.width * factor,
            height = box.height * factor,
            angleDegrees = box.angleDegrees + rotationDegrees.toFloat()
        )
    }

    /** The transform equivalent to applying `this` first and then [next]. */
    fun then(next: Transform): Transform = Transform(
        a = next.a * a - next.b * b,
        b = next.b * a + next.a * b,
        tx = next.a * tx - next.b * ty + next.tx,
        ty = next.b * tx + next.a * ty + next.ty
    )

    /**
     * Re-expresses a transform that was measured in downscaled tracking space so
     * it can be applied to full-resolution coordinates. Rotation and scale are
     * dimensionless; only the translation has to grow.
     */
    fun rescale(trackingScale: Float): Transform =
        if (trackingScale <= 0f) this
        else Transform(a, b, tx / trackingScale, ty / trackingScale)

    companion object {
        val IDENTITY = Transform(1.0, 0.0, 0.0, 0.0)
    }
}
