package com.enhikyy.girassol.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeProjectionTest {
    @Test fun projectionMatchesCoordinateTransform() {
        assertTrue("Biblioteca JNI não foi carregada", NativeProjection.available)
        val points = listOf(0f to 0f, 4f to 1f, -3.5f to 2.5f)
        val projected = NativeProjection.project(points)
        assertEquals(points.size, projected.size)
        points.zip(projected).forEach { (input, output) ->
            assertEquals(input.first - input.second, output.first, .00001f)
            assertEquals((input.first + input.second) * .52f, output.second, .00001f)
        }
        assertTrue(NativeProjection.project(emptyList()).isEmpty())
    }

    @Test fun directBuffersRejectInvalidInputsWithoutPartialWrites() {
        assertTrue(NativeProjection.available)
        val method = NativeProjection::class.java.getDeclaredMethod("nativeProject", ByteBuffer::class.java, ByteBuffer::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        val output = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        input.putFloat(0, 1f); input.putFloat(4, 2f); input.putFloat(8, Float.NaN); input.putFloat(12, 3f)
        output.putFloat(0, 77f)
        fun call(src: ByteBuffer, dst: ByteBuffer, count: Int) = method.invoke(NativeProjection, src, dst, count) as Boolean
        assertFalse(call(input, output, 2))
        assertEquals(77f, output.getFloat(0), 0f)
        assertFalse(call(input, output, -1))
        assertFalse(call(ByteBuffer.allocate(16), output, 1))
        assertFalse(call(input, ByteBuffer.allocateDirect(4), 1))
        assertFalse(call(input, input, 1))
        assertTrue(call(input, output, 1))
        assertEquals(-1f, output.getFloat(0), .00001f)
    }
}
