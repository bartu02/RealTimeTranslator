package com.example.realtimetranslator.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class TransformTest {

    @Test
    fun `identity leaves a point where it is`() {
        val identity = Transform.IDENTITY
        assertEquals(1.0, identity.scale, TOLERANCE)
        assertEquals(0.0, identity.tx, TOLERANCE)
        assertEquals(0.0, identity.ty, TOLERANCE)
    }

    @Test
    fun `composing with identity changes nothing`() {
        val transform = Transform(a = 0.9, b = 0.2, tx = 12.0, ty = -4.0)

        val afterIdentity = transform.then(Transform.IDENTITY)
        assertEquals(transform.a, afterIdentity.a, TOLERANCE)
        assertEquals(transform.b, afterIdentity.b, TOLERANCE)
        assertEquals(transform.tx, afterIdentity.tx, TOLERANCE)
        assertEquals(transform.ty, afterIdentity.ty, TOLERANCE)

        val beforeIdentity = Transform.IDENTITY.then(transform)
        assertEquals(transform.a, beforeIdentity.a, TOLERANCE)
        assertEquals(transform.b, beforeIdentity.b, TOLERANCE)
        assertEquals(transform.tx, beforeIdentity.tx, TOLERANCE)
        assertEquals(transform.ty, beforeIdentity.ty, TOLERANCE)
    }

    @Test
    fun `composing two translations adds them`() {
        val first = Transform(a = 1.0, b = 0.0, tx = 5.0, ty = 3.0)
        val second = Transform(a = 1.0, b = 0.0, tx = -2.0, ty = 7.0)

        val combined = first.then(second)

        assertEquals(3.0, combined.tx, TOLERANCE)
        assertEquals(10.0, combined.ty, TOLERANCE)
        assertEquals(1.0, combined.scale, TOLERANCE)
    }

    @Test
    fun `composing two scalings multiplies them`() {
        val doubling = Transform(a = 2.0, b = 0.0, tx = 0.0, ty = 0.0)
        val tripling = Transform(a = 3.0, b = 0.0, tx = 0.0, ty = 0.0)

        assertEquals(6.0, doubling.then(tripling).scale, TOLERANCE)
    }

    @Test
    fun `composition applies this transform before the next one`() {
        // Move right by 10, then scale everything by 2: the offset scales too.
        val move = Transform(a = 1.0, b = 0.0, tx = 10.0, ty = 0.0)
        val scale = Transform(a = 2.0, b = 0.0, tx = 0.0, ty = 0.0)

        val combined = move.then(scale)

        assertEquals(20.0, combined.tx, TOLERANCE)
        assertEquals(2.0, combined.scale, TOLERANCE)
    }

    @Test
    fun `quarter turn maps the x axis onto the y axis`() {
        val quarterTurn = Transform(a = 0.0, b = 1.0, tx = 0.0, ty = 0.0)

        // Composing four quarter turns returns to the identity.
        val full = quarterTurn.then(quarterTurn).then(quarterTurn).then(quarterTurn)

        assertEquals(1.0, full.a, TOLERANCE)
        assertEquals(0.0, full.b, TOLERANCE)
    }

    @Test
    fun `rescale grows the translation but not the rotation`() {
        val measured = Transform(a = 0.8, b = 0.6, tx = 4.0, ty = -8.0)

        val applied = measured.rescale(0.25f)

        assertEquals(measured.a, applied.a, TOLERANCE)
        assertEquals(measured.b, applied.b, TOLERANCE)
        assertEquals(16.0, applied.tx, TOLERANCE)
        assertEquals(-32.0, applied.ty, TOLERANCE)
    }

    @Test
    fun `rescale ignores a non positive scale`() {
        val measured = Transform(a = 1.0, b = 0.0, tx = 4.0, ty = 4.0)
        assertEquals(measured, measured.rescale(0f))
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
