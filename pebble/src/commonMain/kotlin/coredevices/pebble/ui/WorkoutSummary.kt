package coredevices.pebble.ui

import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.HeartRateSampleEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType
import kotlin.math.roundToInt

data class WorkoutHrPoint(val offsetSec: Long, val bpm: Int)

data class WorkoutSummary(
    val startTime: Long,
    val durationSec: Long,
    val type: OverlayType,
    val steps: Long,
    val distanceM: Long,
    val activeKcal: Long,
    val avgHr: Int?,
    val maxHr: Int?,
    /** Per-second readings when the watch logged them, otherwise one point per minute. */
    val hrPoints: List<WorkoutHrPoint>,
    val hrIsPerSecond: Boolean,
    /** Steps per minute, minute 0 being the minute the workout started in. */
    val stepsPerMinute: List<Int>,
    /** Average heart rate per minute, aligned with [stepsPerMinute]. */
    val hrPerMinute: List<Int?>,
    val zoneMinutes: Map<Int, Long>,
) {
    val endTime: Long get() = startTime + durationSec

    val paceSecPerKm: Long?
        get() = if (distanceM >= MIN_PACE_DISTANCE_M) durationSec * 1000 / distanceM else null

    val cadenceSpm: Int?
        get() = if (durationSec >= 60 && steps > 0) (steps * 60 / durationSec).toInt() else null
}

private const val MIN_PACE_DISTANCE_M = 50L
private const val HR_CHART_MAX_POINTS = 300L
private const val HR_SAMPLE_MAX_GAP_SEC = 30L
private const val HR_MINUTE_MAX_GAP_SEC = 150L
private const val METERS_PER_MILE = 1609.344

/** Minute data for a workout must be queried from this timestamp, as minute records are minute-aligned. */
internal fun workoutMinuteDataStart(session: OverlayDataEntity): Long = session.startTime - session.startTime % 60

/**
 * Totals prefer the session's own figures (what the watch showed at the end of the workout) and
 * fall back to the minute data, which is all the parser keeps for open workouts.
 */
internal fun buildWorkoutSummary(
    session: OverlayDataEntity,
    minutes: List<HealthDataEntity>,
    hrSamples: List<HeartRateSampleEntity>,
): WorkoutSummary {
    val start = session.startTime
    val end = start + session.duration
    val minuteStart = workoutMinuteDataStart(session)
    val inWindow = minutes.filter { it.timestamp in minuteStart until end }

    val minuteCount = if (end > minuteStart) ((end - 1 - minuteStart) / 60 + 1).toInt() else 0
    val stepsPerMinute = IntArray(minuteCount)
    for (m in inWindow) {
        stepsPerMinute[((m.timestamp - minuteStart) / 60).toInt()] += m.steps
    }

    val samples = hrSamples.filter { it.timestamp in start until end && it.bpm > 0 }
    val hrPoints = if (samples.isNotEmpty()) {
        samples.map { WorkoutHrPoint(it.timestamp - start, it.bpm) }
    } else {
        inWindow.filter { it.heartRate > 0 }
            .map { WorkoutHrPoint((it.timestamp - start).coerceAtLeast(0), it.heartRate) }
    }

    return WorkoutSummary(
        startTime = start,
        durationSec = session.duration,
        type = OverlayType.fromValue(session.type) ?: OverlayType.OpenWorkout,
        steps = session.steps.takeIf { it > 0 }?.toLong() ?: inWindow.sumOf { it.steps.toLong() },
        // The session record carries metres, despite the column name.
        distanceM = session.distanceCm.takeIf { it > 0 }?.toLong()
            ?: (inWindow.sumOf { it.distanceCm.toLong() } / 100),
        activeKcal = session.activeKiloCalories.takeIf { it > 0 }?.toLong()
            ?: (inWindow.sumOf { it.activeGramCalories.toLong() } / 1000),
        avgHr = hrPoints.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average()?.roundToInt(),
        maxHr = hrPoints.maxOfOrNull { it.bpm },
        hrPoints = hrPoints,
        hrIsPerSecond = samples.isNotEmpty(),
        stepsPerMinute = stepsPerMinute.toList(),
        hrPerMinute = hrPoints.groupBy { ((it.offsetSec + start - minuteStart) / 60).toInt() }.let { byMinute ->
            (0 until minuteCount).map { minute -> byMinute[minute]?.map { it.bpm }?.average()?.roundToInt() }
        },
        zoneMinutes = inWindow.filter { it.heartRate > 0 }
            .groupingBy { it.heartRateZone }
            .eachCount()
            .mapValues { it.value.toLong() },
    )
}

/** Line segments to draw: per-second readings averaged down to a drawable count, split where readings stop. */
internal fun WorkoutSummary.hrChartSegments(): List<List<WorkoutHrPoint>> {
    if (!hrIsPerSecond) return splitAtGaps(hrPoints, HR_MINUTE_MAX_GAP_SEC)
    val bucketSec = (durationSec / HR_CHART_MAX_POINTS).coerceAtLeast(1)
    return splitAtGaps(downsampleHr(hrPoints, bucketSec), maxOf(HR_SAMPLE_MAX_GAP_SEC, bucketSec * 3))
}

internal fun downsampleHr(points: List<WorkoutHrPoint>, bucketSec: Long): List<WorkoutHrPoint> {
    if (bucketSec <= 1) return points
    return points.groupBy { it.offsetSec / bucketSec }.map { (bucket, inBucket) ->
        WorkoutHrPoint(bucket * bucketSec + bucketSec / 2, inBucket.map { it.bpm }.average().roundToInt())
    }
}

internal fun splitAtGaps(points: List<WorkoutHrPoint>, maxGapSec: Long): List<List<WorkoutHrPoint>> {
    val segments = mutableListOf<MutableList<WorkoutHrPoint>>()
    for (p in points) {
        val current = segments.lastOrNull()
        if (current == null || p.offsetSec - current.last().offsetSec > maxGapSec) {
            segments += mutableListOf(p)
        } else {
            current += p
        }
    }
    return segments
}

internal fun workoutTitle(type: OverlayType): String = when (type) {
    OverlayType.Walk -> "Walk"
    OverlayType.Run -> "Run"
    else -> "Workout"
}

internal fun formatWorkoutDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = (seconds % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
}

internal fun formatPace(secPerKm: Long, imperial: Boolean): String {
    val secPerUnit = if (imperial) (secPerKm * METERS_PER_MILE / 1000).roundToInt().toLong() else secPerKm
    val unit = if (imperial) "mi" else "km"
    return "${secPerUnit / 60}'${(secPerUnit % 60).toString().padStart(2, '0')}\"/$unit"
}
