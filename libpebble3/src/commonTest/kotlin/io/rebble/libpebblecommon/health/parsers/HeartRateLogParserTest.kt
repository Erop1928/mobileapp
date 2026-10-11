package io.rebble.libpebblecommon.health.parsers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartRateLogParserTest {
    private val itemSize = 300

    private fun varint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out += ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        out += v.toByte()
        return out.toByteArray()
    }

    private fun varintField(field: Int, value: Long) = varint((field shl 3).toLong()) + varint(value)

    private fun bytesField(field: Int, bytes: ByteArray) =
        varint(((field shl 3) or 2).toLong()) + varint(bytes.size.toLong()) + bytes

    private fun measurement(offsetSec: Long, vararg values: Long) =
        varintField(1, offsetSec) + bytesField(2, values.fold(ByteArray(0)) { acc, v -> acc + varint(v) })

    // Mirrors the firmware's encoding: types unpacked, measurement data packed.
    private fun measurementSet(timeUtc: Long, types: List<Int>, measurements: List<ByteArray>) =
        bytesField(1, ByteArray(16) { it.toByte() }) +
            varintField(3, timeUtc) +
            varintField(4, 7200) +
            types.fold(ByteArray(0)) { acc, t -> acc + varintField(7, t.toLong()) } +
            measurements.fold(ByteArray(0)) { acc, m -> acc + bytesField(8, m) }

    private fun payload(vararg sets: ByteArray): ByteArray {
        val sender = bytesField(1, "watch".encodeToByteArray()) + bytesField(2, "Q402445E00XX".encodeToByteArray())
        return bytesField(2, sender) +
            varintField(3, 1_760_000_500) +
            sets.fold(ByteArray(0)) { acc, s -> acc + bytesField(12, s) }
    }

    private fun item(payload: ByteArray, declaredSize: Int = payload.size): ByteArray {
        val header = byteArrayOf((declaredSize and 0xFF).toByte(), (declaredSize shr 8).toByte())
        return (header + payload).copyOf(itemSize)
    }

    @Test
    fun parsesHeartRateSamplesFromMultipleItems() {
        val first = payload(
            measurementSet(
                timeUtc = 1_760_000_000,
                types = listOf(7, 12),
                measurements = listOf(measurement(0, 92, 6), measurement(1, 95, 5), measurement(2, 101, 7)),
            )
        )
        val second = payload(
            measurementSet(
                timeUtc = 1_760_000_003,
                types = listOf(7, 12),
                measurements = listOf(measurement(0, 140, 6), measurement(200, 182, 4)),
            )
        )

        val samples = parseHeartRateLogData(item(first) + item(second), itemSize.toUShort())

        assertEquals(
            listOf(1_760_000_000L, 1_760_000_001L, 1_760_000_002L, 1_760_000_003L, 1_760_000_203L),
            samples.map { it.timestamp },
        )
        assertEquals(listOf(92, 95, 101, 140, 182), samples.map { it.bpm })
        assertEquals(listOf(6, 5, 7, 6, 4), samples.map { it.quality })
    }

    @Test
    fun usesTypeOrderToFindBpm() {
        val data = payload(
            measurementSet(
                timeUtc = 1_760_000_000,
                types = listOf(12, 7),
                measurements = listOf(measurement(5, 6, 88)),
            )
        )

        val samples = parseHeartRateLogData(item(data), itemSize.toUShort())

        assertEquals(1, samples.size)
        assertEquals(1_760_000_005L, samples[0].timestamp)
        assertEquals(88, samples[0].bpm)
        assertEquals(6, samples[0].quality)
    }

    @Test
    fun ignoresMeasurementSetsWithoutBpm() {
        val data = payload(
            measurementSet(
                timeUtc = 1_760_000_000,
                types = listOf(3, 4),
                measurements = listOf(measurement(0, 100, 7000)),
            )
        )

        assertTrue(parseHeartRateLogData(item(data), itemSize.toUShort()).isEmpty())
    }

    @Test
    fun skipsMalformedItemsAndKeepsParsing() {
        val good = payload(
            measurementSet(
                timeUtc = 1_760_000_000,
                types = listOf(7, 12),
                measurements = listOf(measurement(0, 120, 6)),
            )
        )
        val garbage = item(ByteArray(40) { 0xFF.toByte() })
        val oversized = item(good, declaredSize = itemSize)

        val samples = parseHeartRateLogData(garbage + oversized + item(good), itemSize.toUShort())

        assertEquals(listOf(120), samples.map { it.bpm })
    }
}
