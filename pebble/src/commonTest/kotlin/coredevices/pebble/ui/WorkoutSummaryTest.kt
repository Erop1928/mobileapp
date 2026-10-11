package coredevices.pebble.ui

import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.HeartRateSampleEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val START = 1_760_000_010L

private fun session(
    type: OverlayType = OverlayType.Run,
    duration: Long = 180,
    steps: Int = 0,
    distanceM: Int = 0,
    activeKcal: Int = 0,
) = OverlayDataEntity(
    startTime = START,
    duration = duration,
    type = type.value,
    steps = steps,
    restingKiloCalories = 0,
    activeKiloCalories = activeKcal,
    distanceCm = distanceM,
    offsetUTC = 0,
)

private fun minute(timestamp: Long, steps: Int, heartRate: Int = 0, zone: Int = 0) = HealthDataEntity(
    timestamp = timestamp,
    steps = steps,
    orientation = 0,
    intensity = 0,
    lightIntensity = 0,
    activeMinutes = 1,
    restingGramCalories = 1000,
    activeGramCalories = 20_000,
    distanceCm = steps * 100,
    heartRate = heartRate,
    heartRateZone = zone,
)

private fun hr(timestamp: Long, bpm: Int) = HeartRateSampleEntity(timestamp, bpm, quality = 6)

// START is 30s into a minute, so a 180s workout touches 4 minute records.
private val minutes = listOf(
    minute(START - 90, steps = 999, heartRate = 70),
    minute(START - 30, steps = 80, heartRate = 120, zone = 1),
    minute(START + 30, steps = 160, heartRate = 150, zone = 2),
    minute(START + 90, steps = 170, heartRate = 160, zone = 2),
    minute(START + 150, steps = 90, heartRate = 0),
    minute(START + 210, steps = 999, heartRate = 70),
)

class WorkoutSummaryTest {
    @Test
    fun minuteDataStartIsMinuteAligned() {
        assertEquals(START - 30, workoutMinuteDataStart(session()))
    }

    @Test
    fun prefersSessionTotals() {
        val summary = buildWorkoutSummary(
            session(steps = 480, distanceM = 600, activeKcal = 40),
            minutes,
            emptyList(),
        )

        assertEquals(480, summary.steps)
        assertEquals(600, summary.distanceM)
        assertEquals(40, summary.activeKcal)
        assertEquals(300L, summary.paceSecPerKm)
        assertEquals(160, summary.cadenceSpm)
        assertEquals(START + 180, summary.endTime)
    }

    @Test
    fun fallsBackToMinuteDataTotals() {
        val summary = buildWorkoutSummary(session(type = OverlayType.OpenWorkout), minutes, emptyList())

        assertEquals(OverlayType.OpenWorkout, summary.type)
        assertEquals(500, summary.steps)
        assertEquals(500, summary.distanceM)
        assertEquals(80, summary.activeKcal)
        assertEquals(listOf(80, 160, 170, 90), summary.stepsPerMinute)
    }

    @Test
    fun usesPerSecondHeartRateWhenAvailable() {
        val samples = listOf(
            hr(START - 5, 200),
            hr(START, 100),
            hr(START + 1, 110),
            hr(START + 2, 0),
            hr(START + 3, 130),
            hr(START + 180, 200),
        )

        val summary = buildWorkoutSummary(session(), minutes, samples)

        assertTrue(summary.hrIsPerSecond)
        assertEquals(listOf(WorkoutHrPoint(0, 100), WorkoutHrPoint(1, 110), WorkoutHrPoint(3, 130)), summary.hrPoints)
        assertEquals(113, summary.avgHr)
        assertEquals(130, summary.maxHr)
        assertEquals(listOf(113, null, null, null), summary.hrPerMinute)
    }

    @Test
    fun fallsBackToMinuteHeartRate() {
        val summary = buildWorkoutSummary(session(), minutes, emptyList())

        assertFalse(summary.hrIsPerSecond)
        assertEquals(listOf(WorkoutHrPoint(0, 120), WorkoutHrPoint(30, 150), WorkoutHrPoint(90, 160)), summary.hrPoints)
        assertEquals(143, summary.avgHr)
        assertEquals(160, summary.maxHr)
        assertEquals(mapOf(1 to 1L, 2 to 2L), summary.zoneMinutes)
        assertEquals(listOf(120, 150, 160, null), summary.hrPerMinute)
    }

    @Test
    fun handlesWorkoutWithoutData() {
        val summary = buildWorkoutSummary(session(duration = 30), emptyList(), emptyList())

        assertNull(summary.avgHr)
        assertNull(summary.maxHr)
        assertNull(summary.paceSecPerKm)
        assertNull(summary.cadenceSpm)
        assertEquals(listOf(0), summary.stepsPerMinute)
        assertEquals(listOf(null), summary.hrPerMinute)
    }

    @Test
    fun downsamplesByAveragingBuckets() {
        val points = listOf(WorkoutHrPoint(0, 100), WorkoutHrPoint(1, 104), WorkoutHrPoint(5, 120), WorkoutHrPoint(6, 121))

        assertEquals(points, downsampleHr(points, 1))
        assertEquals(listOf(WorkoutHrPoint(2, 102), WorkoutHrPoint(7, 121)), downsampleHr(points, 5))
    }

    @Test
    fun splitsLineWhereReadingsStop() {
        val points = listOf(WorkoutHrPoint(0, 100), WorkoutHrPoint(20, 110), WorkoutHrPoint(80, 120), WorkoutHrPoint(81, 121))

        assertEquals(
            listOf(points.subList(0, 2), points.subList(2, 4)),
            splitAtGaps(points, 30),
        )
        assertTrue(splitAtGaps(emptyList(), 30).isEmpty())
    }

    @Test
    fun formatsDurationAndPace() {
        assertEquals("0:59", formatWorkoutDuration(59))
        assertEquals("46:05", formatWorkoutDuration(46 * 60 + 5))
        assertEquals("1:02:03", formatWorkoutDuration(3723))
        assertEquals("5'12\"/km", formatPace(312, imperial = false))
        assertEquals("8'22\"/mi", formatPace(312, imperial = true))
    }
}
