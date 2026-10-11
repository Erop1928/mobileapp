package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun WorkoutsScreen(topBarParams: TopBarParams, nav: NavBarNav) {
    val vm = koinViewModel<WorkoutsViewModel>()
    val workouts by vm.workouts.collectAsState()
    val imperial by vm.imperialUnits.collectAsState()

    LaunchedEffect(Unit) {
        topBarParams.title("Workouts")
        topBarParams.actions {}
        topBarParams.searchAvailable(null)
    }

    val list = workouts
    when {
        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        list.isEmpty() -> Box(Modifier.fillMaxSize().padding(12.dp)) {
            HealthCard(ActivityBgColor, Modifier.fillMaxWidth()) {
                ChartPlaceholder("No workouts yet", "Walks, runs and workouts recorded by your watch will appear here")
            }
        }
        else -> LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { "${it.startTime}-${it.type}" }) { workout ->
                WorkoutListItem(workout, imperial) {
                    nav.navigateTo(PebbleNavBarRoutes.WorkoutDetailRoute(workout.startTime, workout.type))
                }
            }
        }
    }
}

@Composable
private fun WorkoutListItem(workout: OverlayDataEntity, imperial: Boolean, onClick: () -> Unit) {
    val type = OverlayType.fromValue(workout.type) ?: OverlayType.OpenWorkout
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(tz).date
    val date = Instant.fromEpochSeconds(workout.startTime).toLocalDateTime(tz).date

    Surface(onClick = onClick, shape = CardShape, color = ActivityBgColor, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkoutTypeBadge(type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(workoutTitle(type), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${formatDayLabel(date, today)} · ${formatTimeOfDay(workout.startTime, tz)}",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatWorkoutDuration(workout.duration), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                // Session records carry metres in this column.
                if (workout.distanceCm > 0) {
                    Text(
                        formatDistance(workout.distanceCm.toLong(), imperial),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutTypeBadge(type: OverlayType) {
    Box(
        Modifier.size(36.dp).background(workoutColor(type), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(workoutIcon(type), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

private fun workoutIcon(type: OverlayType): ImageVector = when (type) {
    OverlayType.Walk -> Icons.AutoMirrored.Filled.DirectionsWalk
    OverlayType.Run -> Icons.AutoMirrored.Filled.DirectionsRun
    else -> Icons.Filled.FitnessCenter
}

/** Everything fits on one screen without scrolling, so it can be shared as a single screenshot. */
@Composable
fun WorkoutDetailScreen(topBarParams: TopBarParams, startTime: Long, type: Int) {
    val vm = koinViewModel<WorkoutDetailViewModel> { parametersOf(startTime, type) }
    val state by vm.workout.collectAsState()
    val imperial by vm.imperialUnits.collectAsState()

    LaunchedEffect(Unit) {
        topBarParams.title("Workout")
        topBarParams.actions {}
        topBarParams.searchAvailable(null)
    }

    when (val s = state) {
        WorkoutDetailState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        WorkoutDetailState.NotFound -> Box(Modifier.fillMaxSize().padding(12.dp)) {
            HealthCard(ActivityBgColor, Modifier.fillMaxWidth()) {
                ChartPlaceholder("Workout not found", "It may have been removed after 90 days")
            }
        }
        is WorkoutDetailState.Loaded -> WorkoutDetail(s.summary, imperial)
    }
}

@Composable
private fun WorkoutDetail(summary: WorkoutSummary, imperial: Boolean) {
    val scrub = rememberScrubState()
    val tm = rememberTextMeasurer()
    val idx = scrub.scrubIndex?.takeIf { it in summary.stepsPerMinute.indices }
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(tz).date
    val date = Instant.fromEpochSeconds(summary.startTime).toLocalDateTime(tz).date
    val timeLabel = "${formatDayLabel(date, today)} · ${formatTimeOfDay(summary.startTime, tz)} – " +
        formatTimeOfDay(summary.endTime, tz)

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HealthCard(ActivityBgColor, Modifier.fillMaxWidth().weight(1f)) {
            CardHeader(
                color = ActivityHeaderColor,
                cardName = workoutTitle(summary.type),
                label = timeLabel,
                subtitle = if (idx != null) "${summary.stepsPerMinute[idx]} steps in minute ${idx + 1}" else "duration",
                mainValue = formatWorkoutDuration(summary.durationSec),
                secondLabel = "Distance",
                secondValue = formatDistance(summary.distanceM, imperial),
            )
            WorkoutStepsChart(summary.stepsPerMinute, scrub, tm, Modifier.weight(1f))
            StatsRow(
                ActivityBgColor,
                "Pace" to (summary.paceSecPerKm?.let { formatPace(it, imperial) } ?: "--"),
                "Cadence" to (summary.cadenceSpm?.let { "$it spm" } ?: "--"),
                "Steps" to "${summary.steps}",
                "Calories" to "${summary.activeKcal} kcal",
            )
        }
        if (summary.hrPoints.isNotEmpty()) {
            val scrubHr = idx?.let { summary.hrPerMinute[it] }
            HealthCard(HRBgColor, Modifier.fillMaxWidth().weight(1f)) {
                CardHeader(
                    color = HRHeaderColor,
                    cardName = "Heart Rate",
                    label = if (idx != null) "Minute ${idx + 1}" else "Average",
                    subtitle = when {
                        idx != null && scrubHr == null -> "no data"
                        summary.hrIsPerSecond -> "bpm · per-second readings"
                        else -> "bpm · per-minute readings"
                    },
                    mainValue = (if (idx != null) scrubHr else summary.avgHr)?.toString() ?: "--",
                    secondLabel = "Max",
                    secondValue = summary.maxHr?.toString() ?: "--",
                )
                WorkoutHrChart(summary, scrub, tm, Modifier.weight(1f))
                if (summary.zoneMinutes.isNotEmpty()) HRZoneBar(summary.zoneMinutes)
            }
        }
    }
}
