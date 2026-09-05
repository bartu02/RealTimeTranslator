package com.example.realtimetranslator.tracking

import com.example.realtimetranslator.model.OrientedBox
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientedBoxTransformTest {

    @Test
    fun `identity leaves an oriented box untouched`() {
        val box = OrientedBox(centerX = 10f, centerY = 20f, width = 30f, height = 8f, angleDegrees = 5f)
        assertEquals(box, Transform.IDENTITY.map(box))
    }

    @Test
    fun `translation moves the centre and leaves the shape alone`() {
        val box = OrientedBox(10f, 20f, 30f, 8f, 5f)
        val moved = Transform(a = 1.0, b = 0.0, tx = 4.0, ty = -6.0).map(box)

        assertEquals(14f, moved.centerX, TOLERANCE)
        assertEquals(14f, moved.centerY, TOLERANCE)
        assertEquals(30f, moved.width, TOLERANCE)
        assertEquals(8f, moved.height, TOLERANCE)
        assertEquals(5f, moved.angleDegrees, TOLERANCE)
    }

    @Test
    fun `scaling grows the box and keeps its angle`() {
        val box = OrientedBox(10f, 20f, 30f, 8f, 5f)
        val scaled = Transform(a = 2.0, b = 0.0, tx = 0.0, ty = 0.0).map(box)

        assertEquals(20f, scaled.centerX, TOLERANCE)
        assertEquals(40f, scaled.centerY, TOLERANCE)
        assertEquals(60f, scaled.width, TOLERANCE)
        assertEquals(16f, scaled.height, TOLERANCE)
        assertEquals(5f, scaled.angleDegrees, TOLERANCE)
    }

    @Test
    fun `rotation adds to the box angle`() {
        // A quarter turn: a = 0, b = 1.
        val quarterTurn = Transform(a = 0.0, b = 1.0, tx = 0.0, ty = 0.0)
        assertEquals(90.0, quarterTurn.rotationDegrees, 1e-9)

        val box = OrientedBox(1f, 0f, 10f, 4f, 5f)
        val turned = quarterTurn.map(box)

        assertEquals(95f, turned.angleDegrees, TOLERANCE)
        // The point (1, 0) rotates onto (0, 1).
        assertEquals(0f, turned.centerX, TOLERANCE)
        assertEquals(1f, turned.centerY, TOLERANCE)
    }

    @Test
    fun `rotation of the identity is zero`() {
        assertEquals(0.0, Transform.IDENTITY.rotationDegrees, 1e-9)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
