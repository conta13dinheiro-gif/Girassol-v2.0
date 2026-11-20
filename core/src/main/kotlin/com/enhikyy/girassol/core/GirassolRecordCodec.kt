package com.enhikyy.girassol.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

enum class GrsEncoding { FLOAT32, INT8_DELTA_VARINT, TERNARY_2BIT }

data class GrsMatrix(val rows: Int, val columns: Int, val values: List<Float>) {
    init {
        require(rows > 0 && columns > 0)
        require(Math.multiplyExact(rows, columns) == values.size)
        require(values.all { it.isFinite() })
    }
    operator fun get(row: Int, column: Int): Float {
        require(row in 0 until rows && column in 0 until columns)
        return values[row * columns + column]
    }
}
data class GrsEncodingResult(val bytes: ByteArray, val encoding: GrsEncoding, val rootMeanSquareError: Double)

/** Versioned .grs matrix container: SHA-256, delta-varint INT8 and packed ternary values. */
object GirassolRecordCodec {
    private const val VERSION = 1
    private const val PREFIX_BYTES = 22
    private const val DIGEST_BYTES = 32
    private const val HEADER_BYTES = PREFIX_BYTES + DIGEST_BYTES
    // Bound decoded allocations even when a tiny, checksummed file advertises huge dimensions.
    private const val MAX_ELEMENTS = 1_048_576
    private val MAGIC = byteArrayOf(71.toByte(), 82.toByte(), 83.toByte(), 49.toByte())

    fun encode(matrix: GrsMatrix, encoding: GrsEncoding): ByteArray {
        val maximum = matrix.values.maxOf { abs(it) }
        val scale = when (encoding) {
            GrsEncoding.FLOAT32 -> 1f
            GrsEncoding.INT8_DELTA_VARINT -> if (maximum == 0f) 0f else maximum / 127f
            GrsEncoding.TERNARY_2BIT -> maximum
        }
        val payload = when (encoding) {
            GrsEncoding.FLOAT32 -> ByteBuffer.allocate(Math.multiplyExact(matrix.values.size, 4))
                .order(ByteOrder.LITTLE_ENDIAN).apply { matrix.values.forEach { putFloat(it) } }.array()
            GrsEncoding.INT8_DELTA_VARINT -> encodeInt8Delta(matrix.values, scale)
            GrsEncoding.TERNARY_2BIT -> encodeTernary(matrix.values, scale)
        }
        val prefix = ByteBuffer.allocate(PREFIX_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC)
            put(VERSION.toByte())
            put(encoding.ordinal.toByte())
            putInt(matrix.rows)
            putInt(matrix.columns)
            putFloat(scale)
            putInt(payload.size)
        }.array()
        val digest = sha256(prefix + payload)
        return ByteArrayOutputStream(HEADER_BYTES + payload.size).apply {
            write(prefix); write(digest); write(payload)
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): GrsMatrix {
        require(bytes.size >= HEADER_BYTES) { "Arquivo GRS truncado" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        require(magic.contentEquals(MAGIC)) { "Assinatura GRS inválida" }
        require((input.get().toInt() and 0xff) == VERSION) { "Versão GRS não suportada" }
        val encodingId = input.get().toInt() and 0xff
        val encoding = GrsEncoding.entries.getOrNull(encodingId)
            ?: throw IllegalArgumentException("Codificação GRS desconhecida")
        val rows = input.int
        val columns = input.int
        val scale = input.float
        val payloadLength = input.int
        require(rows > 0 && columns > 0)
        val count = Math.multiplyExact(rows, columns)
        require(count in 1..MAX_ELEMENTS) { "Matriz GRS excede o limite de $MAX_ELEMENTS elementos" }
        require(scale.isFinite() && scale >= 0f)
        require(payloadLength >= 0 && payloadLength == bytes.size - HEADER_BYTES) {
            "Tamanho do payload GRS divergente"
        }
        when (encoding) {
            GrsEncoding.FLOAT32 -> require(payloadLength.toLong() == count.toLong() * 4L) {
                "Payload Float32 incompatível com as dimensões"
            }
            GrsEncoding.INT8_DELTA_VARINT -> require(payloadLength in count..(count * 2)) {
                "Payload INT8 incompatível com as dimensões"
            }
            GrsEncoding.TERNARY_2BIT -> require(payloadLength.toLong() == (count.toLong() + 3L) / 4L) {
                "Payload ternário incompatível com as dimensões"
            }
        }
        val expectedDigest = ByteArray(DIGEST_BYTES).also { input.get(it) }
        val prefix = bytes.copyOfRange(0, PREFIX_BYTES)
        val payload = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        require(MessageDigest.isEqual(expectedDigest, sha256(prefix + payload))) {
            "Integridade SHA-256 GRS inválida"
        }
        val values = when (encoding) {
            GrsEncoding.FLOAT32 -> decodeFloat32(payload, count)
            GrsEncoding.INT8_DELTA_VARINT -> decodeInt8Delta(payload, count, scale)
            GrsEncoding.TERNARY_2BIT -> decodeTernary(payload, count, scale)
        }
        return GrsMatrix(rows, columns, values)
    }

    private fun decodeFloat32(payload: ByteArray, count: Int): List<Float> {
        require(payload.size == Math.multiplyExact(count, 4))
        val input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return List(count) { input.float.also { value -> require(value.isFinite()) } }
    }

    private fun encodeInt8Delta(values: List<Float>, scale: Float): ByteArray {
        val output = ByteArrayOutputStream()
        var previous = 0
        values.forEach { value ->
            val quantized = if (scale == 0f) 0 else round(value / scale).toInt().coerceIn(-127, 127)
            val delta = quantized - previous
            previous = quantized
            writeUnsignedVarInt(output, (delta shl 1) xor (delta shr 31))
        }
        return output.toByteArray()
    }

    private fun decodeInt8Delta(payload: ByteArray, count: Int, scale: Float): List<Float> {
        var offset = 0
        var previous = 0
        val values = List(count) {
            val (encoded, nextOffset) = readUnsignedVarInt(payload, offset)
            offset = nextOffset
            val delta = (encoded ushr 1) xor -(encoded and 1)
            val quantized = Math.addExact(previous, delta)
            require(quantized in -127..127) { "Delta INT8 fora da faixa" }
            previous = quantized
            (quantized * scale).also { require(it.isFinite()) }
        }
        require(offset == payload.size) { "Bytes excedentes no payload INT8" }
        return values
    }

    private fun encodeTernary(values: List<Float>, scale: Float): ByteArray {
        val output = ByteArray(((values.size.toLong() + 3L) / 4L).toInt())
        if (scale == 0f) return output
        values.forEachIndexed { index, value ->
            val code = when {
                value <= -scale * .35f -> 1
                value >= scale * .35f -> 2
                else -> 0
            }
            val position = index / 4
            val shift = (index % 4) * 2
            output[position] = (output[position].toInt() or (code shl shift)).toByte()
        }
        return output
    }

    private fun decodeTernary(payload: ByteArray, count: Int, scale: Float): List<Float> {
        require(payload.size == (count.toLong() + 3L).div(4L).toInt())
        return List(count) { index ->
            when ((payload[index / 4].toInt() ushr ((index % 4) * 2)) and 3) {
                0 -> 0f
                1 -> -scale
                2 -> scale
                else -> throw IllegalArgumentException("Código ternário reservado")
            }
        }
    }

    private fun writeUnsignedVarInt(output: ByteArrayOutputStream, value: Int) {
        require(value >= 0)
        var remaining = value
        while (remaining >= 0x80) {
            output.write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        output.write(remaining)
    }

    private fun readUnsignedVarInt(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var offset = start
        while (shift <= 28) {
            require(offset < bytes.size) { "Varint truncado" }
            val next = bytes[offset++].toInt() and 0xff
            result = result or ((next and 0x7f) shl shift)
            if (next and 0x80 == 0) {
                require(result in 0..508) { "Delta INT8 excede a faixa codificável" }
                var canonicalLength = 1
                var remaining = result
                while (remaining >= 0x80) { canonicalLength++; remaining = remaining ushr 7 }
                require(offset - start == canonicalLength) { "Varint INT8 não canônico" }
                return result to offset
            }
            shift += 7
        }
        throw IllegalArgumentException("Varint excede 32 bits")
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** Chooses the smallest representation that remains within the requested RMSE. */
object AdaptiveQuantizerEngine {
    fun encode(matrix: GrsMatrix, maximumRmse: Double): GrsEncodingResult {
        require(maximumRmse.isFinite() && maximumRmse >= 0.0)
        val candidates = GrsEncoding.entries.map { encoding ->
            val bytes = GirassolRecordCodec.encode(matrix, encoding)
            val decoded = GirassolRecordCodec.decode(bytes)
            val error = sqrt(matrix.values.indices.sumOf { index ->
                val delta = matrix.values[index].toDouble() - decoded.values[index].toDouble()
                delta * delta
            } / matrix.values.size)
            GrsEncodingResult(bytes, encoding, error)
        }
        return candidates.filter { it.rootMeanSquareError <= maximumRmse }
            .minWithOrNull(compareBy<GrsEncodingResult> { it.bytes.size }.thenBy { it.rootMeanSquareError })
            ?: candidates.first { it.encoding == GrsEncoding.FLOAT32 }
    }
}
