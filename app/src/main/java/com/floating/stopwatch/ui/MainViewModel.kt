package com.floating.stopwatch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.floating.stopwatch.domain.CountdownEngine
import com.floating.stopwatch.domain.Lap
import com.floating.stopwatch.domain.StopwatchEngine
import com.floating.stopwatch.domain.StopwatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.floating.stopwatch.domain.IntervalEngine
import com.floating.stopwatch.domain.LegacyEngine

enum class AppMode {
    Stopwatch, Countdown, Counter, Intervals, Legacy
}

class MainViewModel(
    val engine: StopwatchEngine,
    val countdownEngine: CountdownEngine = CountdownEngine(),
    val intervalEngine: IntervalEngine = IntervalEngine(),
    val legacyEngine: LegacyEngine = LegacyEngine()
) : ViewModel() {

    val currentMode = MutableStateFlow(AppMode.Stopwatch)

    val state: StateFlow<StopwatchState> = engine.state
    val elapsedTimeMs: StateFlow<Long> = engine.elapsedTimeMs
    val laps: StateFlow<List<Lap>> = engine.laps

    // Countdown State delegated to CountdownEngine
    val countdownInitialMs: StateFlow<Long> = countdownEngine.initialDurationMs
    val countdownRemainingMs: StateFlow<Long> = countdownEngine.remainingTimeMs
    val isCountdownRunning: StateFlow<Boolean> = countdownEngine.isRunning

    // Counter State delegated to shared engine
    val counterValue: StateFlow<Long> = engine.counterValue

    fun cycleMode() {
        val nextMode = when (currentMode.value) {
            AppMode.Stopwatch -> AppMode.Countdown
            AppMode.Countdown -> AppMode.Counter
            AppMode.Counter -> AppMode.Intervals
            AppMode.Intervals -> AppMode.Legacy
            AppMode.Legacy -> AppMode.Stopwatch
        }
        currentMode.value = nextMode
    }

    fun previousMode() {
        val prevMode = when (currentMode.value) {
            AppMode.Stopwatch -> AppMode.Legacy
            AppMode.Countdown -> AppMode.Stopwatch
            AppMode.Counter -> AppMode.Countdown
            AppMode.Intervals -> AppMode.Counter
            AppMode.Legacy -> AppMode.Intervals
        }
        currentMode.value = prevMode
    }

    // Countdown Time Adjustment via Drag
    fun adjustCountdownHours(deltaHours: Int) {
        countdownEngine.adjustDuration(deltaHours * 3600000L)
    }

    fun adjustCountdownMinutes(deltaMinutes: Int) {
        countdownEngine.adjustDuration(deltaMinutes * 60000L)
    }

    fun adjustCountdownSeconds(deltaSeconds: Int) {
        countdownEngine.adjustDuration(deltaSeconds * 1000L)
    }

    fun startCountdown() {
        countdownEngine.start()
    }

    fun pauseCountdown() {
        countdownEngine.pause()
    }

    fun resetCountdown() {
        countdownEngine.reset()
    }

    fun incrementCounter() {
        engine.incrementCounter()
    }

    fun decrementCounter() {
        engine.decrementCounter()
    }

    fun resetCounter() {
        engine.setCounterValue(0L)
    }

    fun start() {
        engine.start()
    }

    fun pause() {
        engine.pause()
    }

    fun reset() {
        engine.reset()
    }

    fun lap() {
        engine.lap()
    }
}
