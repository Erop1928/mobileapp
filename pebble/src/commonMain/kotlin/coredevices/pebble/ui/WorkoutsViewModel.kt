package coredevices.pebble.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock

class WorkoutsViewModel(
    private val libPebble: LibPebble,
) : ViewModel() {
    val imperialUnits = libPebble.healthSettings
        .map { it.imperialUnits }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Null while loading. */
    val workouts: StateFlow<List<OverlayDataEntity>?> = libPebble.healthDataUpdated
        .onStart { emit(Unit) }
        .conflate()
        .map {
            libPebble.getActivitySessions(0, Clock.System.now().epochSeconds)
                .sortedByDescending { it.startTime }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

sealed interface WorkoutDetailState {
    data object Loading : WorkoutDetailState
    data object NotFound : WorkoutDetailState
    data class Loaded(val summary: WorkoutSummary) : WorkoutDetailState
}

class WorkoutDetailViewModel(
    private val libPebble: LibPebble,
    private val startTime: Long,
    private val type: Int,
) : ViewModel() {
    val imperialUnits = libPebble.healthSettings
        .map { it.imperialUnits }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val workout: StateFlow<WorkoutDetailState> = libPebble.healthDataUpdated
        .onStart { emit(Unit) }
        .conflate()
        .map { load() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutDetailState.Loading)

    private suspend fun load(): WorkoutDetailState {
        val session = libPebble.getActivitySessions(startTime, startTime + 1).firstOrNull { it.type == type }
            ?: return WorkoutDetailState.NotFound
        val end = session.startTime + session.duration
        val minutes = libPebble.getHealthDataForRange(workoutMinuteDataStart(session), end)
        val hrSamples = libPebble.getHeartRateSamples(session.startTime, end)
        return WorkoutDetailState.Loaded(buildWorkoutSummary(session, minutes, hrSamples))
    }
}
