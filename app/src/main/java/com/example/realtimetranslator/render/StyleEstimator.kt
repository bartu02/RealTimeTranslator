package com.example.realtimetranslator.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.example.realtimetranslator.model.BlockStyle

/**
 * Estimates the two colours a line of text is made of: the surface behind the
 * words, and the ink the words are printed in.
 *
 * The surface is measured in the bands immediately above and below the line, not
 * inside it. A recognizer box is full of glyphs, so anything sampled from within
 * it is a mixture of ink and paper; the gaps between lines are almost always the
 * bare surface. The colour taken is the *median* of that band rather than its
 * mean, so a band that clips a neighbouring graphic still reports the dominant
 * surface instead of a blend that exists nowhere in the scene.
 *
 * The ink is then the average of the interior pixels lying on the far side of
 * the region's Otsu threshold from that surface - which is to say, the pixels
 * that are not the surface.
 *
 * When the bands are unusable, as for a line hard against the edge of the frame,
 * it falls back to splitting the interior alone.
 */
object StyleEstimator {

    /** Roughly how many samples to take along each axis of the interior. */
    private const val SAMPLES_PER_AXIS = 40

    /** How far the background bands reach, as a fraction of the line height. */
    private const val BAND_RATIO = 0.5f

    /** Fewer band samples than this and the bands are not worth trusting. */
    private const val MIN_BAND_SAMPLES = 16

    /** Luminance window around the median that defines the surface colour. */
    private const val MEDIAN_WINDOW = 12

    /** Ink this close to the surface would be unreadable, so it is replaced. */
    private const val MIN_READABLE_GAP = 55

    /** Below this share of the interior, the "ink" is more likely to be noise. */
    private const val MIN_INK_SHARE = 0.02f

    private const val LIGHT_SURFACE = 140

    private val FALLBACK = BlockStyle(paper = Color.BLACK, ink = Color.WHITE)

    fun estimate(source: Bitmap, box: RectF): BlockStyle {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return FALLBACK

        val left = box.left.toInt().coerceIn(0, source.width - 1)
        val top = box.top.toInt().coerceIn(0, source.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, source.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, source.height)

        val width = right - left
        val height = bottom - top
        if (width < 2 || height < 2) return FALLBACK

        val columnStep = maxOf(1, width / SAMPLES_PER_AXIS)
        val rowStep = maxOf(1, height / SAMPLES_PER_AXIS)
        val row = IntArray(width)

        val interior = Samples(estimateCapacity(width, height, columnStep, rowStep))
        if (!collect(source, left, right, top, bottom, columnStep, rowStep, row, interior)) {
            return FALLBACK
        }
        if (interior.count == 0) return FALLBACK

        val bandDepth = maxOf(2, (height * BAND_RATIO).toInt())
        val bandCapacity = estimateCapacity(width, bandDepth * 2, columnStep, 1)
        val band = Samples(bandCapacity)

        val aboveTop = (top - bandDepth).coerceAtLeast(0)
        if (aboveTop < top) {
            collect(source, left, right, aboveTop, top, columnStep, 1, row, band)
        }
        val belowBottom = (bottom + bandDepth).coerceAtMost(source.height)
        if (belowBottom > bottom) {
            collect(source, left, right, bottom, belowBottom, columnStep, 1, row, band)
        }

        if (band.count < MIN_BAND_SAMPLES) return fromInterior(interior)

        val paperLuminance = band.medianLuminance()
        val paper = band.meanColourNear(paperLuminance, MEDIAN_WINDOW) ?: return fromInterior(interior)

        val threshold = interior.otsuThreshold()
        val inkIsDarker = paperLuminance > threshold
        val ink = interior.meanColourOnSide(threshold, inkIsDarker)

        return BlockStyle(paper = paper, ink = readable(paper, ink))
    }

    /** No usable band: split the interior and call the larger population the surface. */
    private fun fromInterior(interior: Samples): BlockStyle {
        val threshold = interior.otsuThreshold()
        val darkCount = interior.countOnSide(threshold, darker = true)
        val lightCount = interior.count - darkCount

        if (darkCount == 0 || lightCount == 0) {
            val flat = interior.meanColour()
            return BlockStyle(paper = flat, ink = contrastTo(flat))
        }

        // Glyphs cover less of a line than the surface behind them.
        val inkIsDarker = darkCount < lightCount
        val paper = interior.meanColourOnSide(threshold, !inkIsDarker) ?: interior.meanColour()
        val ink = interior.meanColourOnSide(threshold, inkIsDarker)

        return BlockStyle(paper = paper, ink = readable(paper, ink))
    }

    /**
     * Falls back to plain black or white whenever the estimated ink would not
     * stand out against the estimated surface - which happens on a region that
     * is really one flat colour, where there is no ink to find.
     */
    private fun readable(paper: Int, ink: Int?): Int {
        if (ink == null) return contrastTo(paper)
        if (kotlin.math.abs(luminance(paper) - luminance(ink)) < MIN_READABLE_GAP) {
            return contrastTo(paper)
        }
        return ink
    }

    private fun contrastTo(paper: Int): Int =
        if (luminance(paper) > LIGHT_SURFACE) Color.BLACK else Color.WHITE

    private fun estimateCapacity(width: Int, height: Int, columnStep: Int, rowStep: Int): Int {
        val columns = (width + columnStep - 1) / columnStep
        val rows = (height + rowStep - 1) / rowStep
        return maxOf(1, columns * rows)
    }

    private fun collect(
        source: Bitmap,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        columnStep: Int,
        rowStep: Int,
        row: IntArray,
        into: Samples
    ): Boolean {
        val width = right - left
        if (width <= 0 || width > row.size) return false

        var y = top
        while (y < bottom) {
            try {
                source.getPixels(row, 0, width, left, y, width, 1)
            } catch (throwable: Throwable) {
                return false
            }
            var x = 0
            while (x < width && into.count < into.capacity) {
                into.add(row[x])
                x += columnStep
            }
            y += rowStep
        }
        return true
    }

    private fun luminance(pixel: Int): Int =
        (299 * Color.red(pixel) + 587 * Color.green(pixel) + 114 * Color.blue(pixel)) / 1000

    /** A bag of sampled pixels plus their luminance histogram. */
    private class Samples(val capacity: Int) {
        private val pixels = IntArray(capacity)
        private val histogram = IntArray(256)
        var count = 0
            private set

        fun add(pixel: Int) {
            pixels[count++] = pixel
            histogram[luminance(pixel)]++
        }

        fun medianLuminance(): Int {
            var seen = 0
            for (level in 0 until 256) {
                seen += histogram[level]
                if (seen * 2 >= count) return level
            }
            return 127
        }

        fun meanColourNear(target: Int, window: Int): Int? {
            var red = 0L
            var green = 0L
            var blue = 0L
            var used = 0
            for (index in 0 until count) {
                val pixel = pixels[index]
                if (kotlin.math.abs(luminance(pixel) - target) > window) continue
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                used++
            }
            if (used == 0) return null
            return Color.rgb((red / used).toInt(), (green / used).toInt(), (blue / used).toInt())
        }

        fun meanColourOnSide(threshold: Int, darker: Boolean): Int? {
            var red = 0L
            var green = 0L
            var blue = 0L
            var used = 0
            for (index in 0 until count) {
                val pixel = pixels[index]
                val level = luminance(pixel)
                val onSide = if (darker) level <= threshold else level > threshold
                if (!onSide) continue
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                used++
            }
            if (used == 0 || used < count * MIN_INK_SHARE) return null
            return Color.rgb((red / used).toInt(), (green / used).toInt(), (blue / used).toInt())
        }

        fun countOnSide(threshold: Int, darker: Boolean): Int {
            var used = 0
            for (level in 0 until 256) {
                val onSide = if (darker) level <= threshold else level > threshold
                if (onSide) used += histogram[level]
            }
            return used
        }

        fun meanColour(): Int {
            if (count == 0) return Color.BLACK
            var red = 0L
            var green = 0L
            var blue = 0L
            for (index in 0 until count) {
                red += Color.red(pixels[index])
                green += Color.green(pixels[index])
                blue += Color.blue(pixels[index])
            }
            return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        }

        /** The threshold that maximises the variance between the two groups it splits. */
        fun otsuThreshold(): Int {
            var weightedSum = 0L
            for (level in 0 until 256) weightedSum += level.toLong() * histogram[level]

            var backgroundSum = 0L
            var backgroundWeight = 0
            var bestVariance = -1.0
            var threshold = 127

            for (level in 0 until 256) {
                backgroundWeight += histogram[level]
                if (backgroundWeight == 0) continue

                val foregroundWeight = count - backgroundWeight
                if (foregroundWeight == 0) break

                backgroundSum += level.toLong() * histogram[level]

                val backgroundMean = backgroundSum.toDouble() / backgroundWeight
                val foregroundMean = (weightedSum - backgroundSum).toDouble() / foregroundWeight
                val difference = backgroundMean - foregroundMean
                val variance = backgroundWeight.toDouble() * foregroundWeight * difference * difference

                if (variance > bestVariance) {
                    bestVariance = variance
                    threshold = level
                }
            }
            return threshold
        }
    }
}
