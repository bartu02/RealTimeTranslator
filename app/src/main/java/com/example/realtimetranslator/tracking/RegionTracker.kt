package com.example.realtimetranslator.tracking

import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * Estimates how the scene moved between consecutive frames, using sparse
 * Lucas-Kanade optical flow over corner features.
 *
 * This is what decouples overlay smoothness from recognizer speed: the
 * recognizer can run a few times per second while the overlay keeps up with the
 * camera at display rate.
 *
 * Everything here is reused between frames. Point data moves through primitive
 * arrays rather than `Point` objects, and the reference frame is copied into an
 * existing Mat instead of being cloned, so a tracked frame allocates nothing in
 * the steady state. At 30 frames per second the object churn was otherwise large
 * enough to show up as collection pauses.
 *
 * Not thread safe - drive it from the single camera analysis thread.
 */
class RegionTracker {

    private val previous = Mat()
    private val previousPoints = MatOfPoint2f()
    private val trackedPoints = MatOfPoint2f()
    private val trackStatus = MatOfByte()
    private val trackError = MatOfFloat()
    private val fromPoints = MatOfPoint2f()
    private val toPoints = MatOfPoint2f()
    private val corners = MatOfPoint()

    private val previousXy = FloatArray(MAX_POINTS * 2)
    private val trackedXy = FloatArray(MAX_POINTS * 2)
    private val survivingFrom = FloatArray(MAX_POINTS * 2)
    private val survivingTo = FloatArray(MAX_POINTS * 2)
    private val cornerXy = IntArray(MAX_POINTS * 2)
    private val flags = ByteArray(MAX_POINTS)
    private val affine = DoubleArray(6)

    // Mat.put writes the whole array it is handed, so a copy trimmed to the
    // exact point count is needed whenever the working arrays are larger.
    private var scratch = FloatArray(0)

    private var hasPrevious = false
    private var pointCount = 0
    private var framesSinceDetect = 0

    /**
     * Returns the transform mapping the previous frame onto [frame], or null when
     * motion could not be estimated - the first frame, a featureless scene, or a
     * jump too large to be believable.
     *
     * The caller keeps ownership of [frame]; this class copies what it needs.
     */
    fun track(frame: Mat): Transform? {
        if (!hasPrevious || previous.rows() != frame.rows() || previous.cols() != frame.cols()) {
            frame.copyTo(previous)
            hasPrevious = true
            pointCount = 0
            return null
        }

        if (pointCount < MIN_POINTS || framesSinceDetect >= REDETECT_INTERVAL) {
            pointCount = detectFeatures(previous)
            framesSinceDetect = 0
        }

        if (pointCount < MIN_POINTS) {
            frame.copyTo(previous)
            return null
        }

        Video.calcOpticalFlowPyrLK(
            previous,
            frame,
            previousPoints,
            trackedPoints,
            trackStatus,
            trackError
        )

        val moved = trackedPoints.get(0, 0, trackedXy) / 2
        val reported = trackStatus.get(0, 0, flags)
        val usable = minOf(pointCount, moved, reported)

        var surviving = 0
        for (index in 0 until usable) {
            if (flags[index].toInt() != 1) continue
            survivingFrom[surviving * 2] = previousXy[index * 2]
            survivingFrom[surviving * 2 + 1] = previousXy[index * 2 + 1]
            survivingTo[surviving * 2] = trackedXy[index * 2]
            survivingTo[surviving * 2 + 1] = trackedXy[index * 2 + 1]
            surviving++
        }

        frame.copyTo(previous)

        if (surviving < MIN_POINTS) {
            pointCount = 0
            return null
        }

        val transform = estimate(surviving)

        // Carry the points that survived into the next frame, so features are
        // only re-detected once tracking actually degrades.
        System.arraycopy(survivingTo, 0, previousXy, 0, surviving * 2)
        writePoints(previousPoints, previousXy, surviving)
        pointCount = surviving
        framesSinceDetect++

        return transform
    }

    fun reset() {
        hasPrevious = false
        pointCount = 0
        framesSinceDetect = 0
    }

    fun release() {
        reset()
        previous.release()
        previousPoints.release()
        trackedPoints.release()
        trackStatus.release()
        trackError.release()
        fromPoints.release()
        toPoints.release()
        corners.release()
    }

    private fun estimate(count: Int): Transform? {
        writePoints(fromPoints, survivingFrom, count)
        writePoints(toPoints, survivingTo, count)

        val matrix = Calib3d.estimateAffinePartial2D(fromPoints, toPoints)
        if (matrix.empty() || matrix.rows() != 2 || matrix.cols() != 3) {
            matrix.release()
            return null
        }
        matrix.get(0, 0, affine)
        matrix.release()

        // Row major 2x3: [ a11 a12 tx ; a21 a22 ty ]. For a similarity transform
        // a11 == a22 and a21 == -a12, which is exactly the complex form Transform
        // stores.
        val transform = Transform(a = affine[0], b = affine[3], tx = affine[2], ty = affine[5])

        // A wild estimate would fling the overlay off screen. Treat it as a
        // failure and let the next recognizer pass re-anchor instead.
        if (transform.scale !in MIN_SCALE..MAX_SCALE) return null
        if (!transform.tx.isFinite() || !transform.ty.isFinite()) return null

        return transform
    }

    private fun detectFeatures(image: Mat): Int {
        Imgproc.goodFeaturesToTrack(image, corners, MAX_POINTS, QUALITY_LEVEL, MIN_DISTANCE)
        if (corners.rows() < MIN_POINTS) return 0

        val found = corners.get(0, 0, cornerXy) / 2
        val count = minOf(found, MAX_POINTS)
        if (count < MIN_POINTS) return 0

        for (index in 0 until count * 2) {
            previousXy[index] = cornerXy[index].toFloat()
        }
        writePoints(previousPoints, previousXy, count)
        return count
    }

    private fun writePoints(target: MatOfPoint2f, xy: FloatArray, count: Int) {
        if (target.rows() != count || target.type() != CvType.CV_32FC2) {
            target.create(count, 1, CvType.CV_32FC2)
        }
        val size = count * 2
        if (xy.size == size) {
            target.put(0, 0, xy)
            return
        }
        if (scratch.size != size) scratch = FloatArray(size)
        System.arraycopy(xy, 0, scratch, 0, size)
        target.put(0, 0, scratch)
    }

    private companion object {
        const val MIN_POINTS = 8
        const val MAX_POINTS = 160
        const val QUALITY_LEVEL = 0.01
        const val MIN_DISTANCE = 8.0
        const val REDETECT_INTERVAL = 15
        const val MIN_SCALE = 0.5
        const val MAX_SCALE = 2.0
    }
}
