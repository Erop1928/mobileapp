package io.rebble.libpebblecommon.health.parsers

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.HeartRateSampleEntity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

private val logger = Logger.withTag("HeartRateLogParser")

// Subset of the firmware's pebble.pipeline protos (payload.proto / measurements.proto)
@Serializable
private class PipelinePayload(
    @ProtoNumber(12) val measurementSets: List<PipelineMeasurementSet> = emptyList(),
)

@Serializable
private class PipelineMeasurementSet(
    @ProtoNumber(3) val timeUtc: Long = 0,
    @ProtoNumber(7) val types: List<Int> = emptyList(),
    @ProtoNumber(8) val measurements: List<PipelineMeasurement> = emptyList(),
)

@Serializable
private class PipelineMeasurement(
    @ProtoNumber(1) val offsetSec: Long = 0,
    @ProtoNumber(2) @ProtoPacked val data: List<Long> = emptyList(),
)

private const val MEASUREMENT_TYPE_BPM = 7
private const val MEASUREMENT_TYPE_HR_QUALITY = 12
private const val RECORD_HEADER_SIZE = 2

/**
 * Parses the firmware's protobuf log records (datalogging tag 85) into heart rate samples.
 *
 * Each item is a little-endian uint16 message size, a `pebble.pipeline.Payload` of that size,
 * then zero padding up to [itemSize].
 */
fun parseHeartRateLogData(payload: ByteArray, itemSize: UShort): List<HeartRateSampleEntity> {
    val size = itemSize.toInt()
    // TEMP DEBUG: verifying the real tag-85 payload format on-device. Remove before the upstream PR.
    logger.i { "HR_LOG: payload=${payload.size}B itemSize=$size head=${payload.toHexPreview(48)}" }
    if (payload.isEmpty() || size <= RECORD_HEADER_SIZE) {
        logger.w { "Cannot parse protobuf log data: empty payload or item size $itemSize" }
        return emptyList()
    }

    val samples = mutableListOf<HeartRateSampleEntity>()
    var offset = 0
    while (offset + size <= payload.size) {
        val msgSize = (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
        if (msgSize == 0 || msgSize > size - RECORD_HEADER_SIZE) {
            logger.w { "Skipping protobuf log record with invalid size $msgSize (itemSize=$itemSize)" }
        } else {
            val msgStart = offset + RECORD_HEADER_SIZE
            val recordBytes = payload.copyOfRange(msgStart, msgStart + msgSize)
            try {
                val decoded = ProtoBuf.decodeFromByteArray<PipelinePayload>(recordBytes)
                val recordSamples = decoded.measurementSets.flatMap { it.heartRateSamples() }
                samples += recordSamples
                logger.i {
                    "HR_LOG: record msgSize=$msgSize sets=${decoded.measurementSets.size} " +
                        "samples=${recordSamples.size} types=${decoded.measurementSets.map { it.types }}" +
                        if (recordSamples.isEmpty()) " bytes=${recordBytes.toHexPreview(48)}" else ""
                }
            } catch (e: SerializationException) {
                logger.w(e) { "HR_LOG: decode failed msgSize=$msgSize bytes=${recordBytes.toHexPreview(48)}" }
            } catch (e: IllegalArgumentException) {
                logger.w(e) { "HR_LOG: decode failed msgSize=$msgSize bytes=${recordBytes.toHexPreview(48)}" }
            }
        }
        offset += size
    }
    logger.i { "HR_LOG: total samples=${samples.size}" }
    return samples
}

// TEMP DEBUG helper; remove with the HR_LOG lines before the upstream PR.
private fun ByteArray.toHexPreview(max: Int): String =
    take(max).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun PipelineMeasurementSet.heartRateSamples(): List<HeartRateSampleEntity> {
    val bpmIndex = types.indexOf(MEASUREMENT_TYPE_BPM)
    if (bpmIndex < 0) return emptyList()
    val qualityIndex = types.indexOf(MEASUREMENT_TYPE_HR_QUALITY)
    return measurements.mapNotNull { m ->
        val bpm = m.data.getOrNull(bpmIndex)?.toInt() ?: return@mapNotNull null
        if (bpm <= 0) return@mapNotNull null
        HeartRateSampleEntity(
            timestamp = timeUtc + m.offsetSec,
            bpm = bpm,
            quality = m.data.getOrNull(qualityIndex)?.toInt() ?: 0,
        )
    }
}
