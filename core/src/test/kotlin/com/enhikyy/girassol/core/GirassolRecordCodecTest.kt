package com.enhikyy.girassol.core

import org.junit.Assert.*
import org.junit.Test

class GirassolRecordCodecTest {
    @Test fun float32EncodingRoundTripsExactlyAndPreservesShape() {
        val original = GrsMatrix(2, 3, listOf(-1.25f, 0f, .125f, 2f, -3.5f, .75f))
        val restored = GirassolRecordCodec.decode(GirassolRecordCodec.encode(original, GrsEncoding.FLOAT32))
        assertEquals(original.rows, restored.rows)
        assertEquals(original.columns, restored.columns)
        assertEquals(original.values, restored.values)
    }

    @Test fun int8DeltaVarintUsesSymmetricRangeAndBoundedError() {
        val original = GrsMatrix(3, 3, listOf(-1f, -.5f, -.25f, 0f, .25f, .5f, 1f, .75f, -.75f))
        val encoded = GirassolRecordCodec.encode(original, GrsEncoding.INT8_DELTA_VARINT)
        val restored = GirassolRecordCodec.decode(encoded)
        val rmse = kotlin.math.sqrt(original.values.indices.sumOf { index ->
            val delta = original.values[index].toDouble() - restored.values[index].toDouble()
            delta * delta
        } / original.values.size)
        assertTrue(rmse < .01)
        assertTrue(encoded.size < original.values.size * 4 + 54)
    }

    @Test fun ternaryBitPackingPreservesZerosAndSigns() {
        val original = GrsMatrix(2, 4, listOf(0f, -1f, 1f, 0f, 1f, -1f, 0f, 0f))
        val encoded = GirassolRecordCodec.encode(original, GrsEncoding.TERNARY_2BIT)
        val restored = GirassolRecordCodec.decode(encoded)
        assertEquals(original.values, restored.values)
        assertEquals(2, encoded.size - 54)
    }

    @Test fun adaptiveQuantizerSelectsSmallestEncodingWithinErrorBudget() {
        val original = GrsMatrix(1, 8, listOf(0f, -1f, 1f, 0f, -1f, 1f, 0f, 0f))
        val result = AdaptiveQuantizerEngine.encode(original, 0.0)
        assertEquals(GrsEncoding.TERNARY_2BIT, result.encoding)
        assertEquals(0.0, result.rootMeanSquareError, 0.0)
        assertEquals(original.values, GirassolRecordCodec.decode(result.bytes).values)
    }

    @Test fun payloadTamperingAndMalformedDimensionsAreRejected() {
        val bytes = GirassolRecordCodec.encode(GrsMatrix(1, 3, listOf(1f, 2f, 3f)), GrsEncoding.FLOAT32)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        try {
            GirassolRecordCodec.decode(bytes)
            fail("Checksum deve detectar alteração")
        } catch (_: IllegalArgumentException) {
            // Expected integrity rejection.
        }
        try {
            GrsMatrix(2, 2, listOf(1f))
            fail("Dimensões inconsistentes devem ser rejeitadas")
        } catch (_: IllegalArgumentException) {
            // Expected shape rejection.
        }
    }

    @Test fun emptyAndNonFinitePayloadsAreRejected() {
        try {
            GrsMatrix(0, 1, emptyList())
            fail("Matriz vazia não tem dimensão válida")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        try {
            GrsMatrix(1, 1, listOf(Float.NaN))
            fail("NaN não pode entrar no formato")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
