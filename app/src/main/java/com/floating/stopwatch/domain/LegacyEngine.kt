package com.floating.stopwatch.domain

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class LegacyState {
    IDLE, RUNNING, PAUSED, COMPLETED
}

enum class PaceStatus {
    ON_PACE, AHEAD, BEHIND
}

data class LegacyItem(
    val id: String,
    val name: String,
    val totalDays: Int,
    val dailyTargetHours: Int,
    val dailyTargetMinutes: Int,
    val accumulatedMs: Long = 0L,
    val createdTimestampMs: Long = System.currentTimeMillis(),
    val postponedDays: Int = 0,
    val todayAccumulatedMs: Long = 0L,
    val lastSessionDateDay: Int = -1
) {
    val totalTargetMs: Long
        get() = (dailyTargetHours * 3600000L + dailyTargetMinutes * 60000L) * totalDays

    val remainingMs: Long
        get() = (totalTargetMs - accumulatedMs).coerceAtLeast(0L)

    val isCompleted: Boolean
        get() = accumulatedMs >= totalTargetMs && totalTargetMs > 0L

    val dailyTargetMs: Long
        get() = dailyTargetHours * 3600000L + dailyTargetMinutes * 60000L

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"id\":\"").append(id).append("\",")
        sb.append("\"name\":\"").append(name.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",")
        sb.append("\"totalDays\":").append(totalDays).append(",")
        sb.append("\"dailyTargetHours\":").append(dailyTargetHours).append(",")
        sb.append("\"dailyTargetMinutes\":").append(dailyTargetMinutes).append(",")
        sb.append("\"accumulatedMs\":").append(accumulatedMs).append(",")
        sb.append("\"createdTimestampMs\":").append(createdTimestampMs).append(",")
        sb.append("\"postponedDays\":").append(postponedDays).append(",")
        sb.append("\"todayAccumulatedMs\":").append(todayAccumulatedMs).append(",")
        sb.append("\"lastSessionDateDay\":").append(lastSessionDateDay)
        sb.append("}")
        return sb.toString()
    }

    companion object {
        fun jsonToItem(jsonStr: String): LegacyItem? {
            return try {
                fun getString(key: String): String? {
                    val regex = "\"$key\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"".toRegex()
                    return regex.find(jsonStr)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                }
                fun getLong(key: String): Long? {
                    val regex = "\"$key\"\\s*:\\s*(-?\\d+)".toRegex()
                    return regex.find(jsonStr)?.groupValues?.get(1)?.toLongOrNull()
                }
                fun getInt(key: String): Int? {
                    return getLong(key)?.toInt()
                }

                val idVal = getString("id") ?: return null
                val nameVal = getString("name") ?: "UNTITLED LEGACY"
                val totalDaysVal = getInt("totalDays") ?: 30
                val dailyTargetHoursVal = getInt("dailyTargetHours") ?: 0
                val dailyTargetMinutesVal = getInt("dailyTargetMinutes") ?: 0
                val accumulatedMsVal = getLong("accumulatedMs") ?: 0L
                val createdTimestampMsVal = getLong("createdTimestampMs") ?: System.currentTimeMillis()
                val postponedDaysVal = getInt("postponedDays") ?: 0
                val todayAccumulatedMsVal = getLong("todayAccumulatedMs") ?: 0L
                val lastSessionDateDayVal = getInt("lastSessionDateDay") ?: -1

                LegacyItem(
                    id = idVal,
                    name = nameVal,
                    totalDays = totalDaysVal,
                    dailyTargetHours = dailyTargetHoursVal,
                    dailyTargetMinutes = dailyTargetMinutesVal,
                    accumulatedMs = accumulatedMsVal,
                    createdTimestampMs = createdTimestampMsVal,
                    postponedDays = postponedDaysVal,
                    todayAccumulatedMs = todayAccumulatedMsVal,
                    lastSessionDateDay = lastSessionDateDayVal
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

class LegacyEngine {
    private val _legacies = MutableStateFlow<List<LegacyItem>>(emptyList())
    val legacies: StateFlow<List<LegacyItem>> = _legacies.asStateFlow()

    private val _selectedLegacyId = MutableStateFlow<String?>(null)
    val selectedLegacyId: StateFlow<String?> = _selectedLegacyId.asStateFlow()

    private val _state = MutableStateFlow(LegacyState.IDLE)
    val state: StateFlow<LegacyState> = _state.asStateFlow()

    private val _sessionElapsedMs = MutableStateFlow(0L)
    val sessionElapsedMs: StateFlow<Long> = _sessionElapsedMs.asStateFlow()

    private var job: Job? = null
    private var lastTimeMs = 0L

    var onLegaciesUpdated: ((List<LegacyItem>) -> Unit)? = null

    fun getActiveLegacy(): LegacyItem? {
        val activeId = _selectedLegacyId.value ?: return _legacies.value.firstOrNull()
        return _legacies.value.find { it.id == activeId } ?: _legacies.value.firstOrNull()
    }

    fun loadLegaciesFromJson(jsonString: String) {
        if (jsonString.isBlank()) return
        try {
            val list = mutableListOf<LegacyItem>()
            val objectRegex = "\\{[^{}]*\\}".toRegex()
            objectRegex.findAll(jsonString).forEach { match ->
                val item = LegacyItem.jsonToItem(match.value)
                if (item != null) {
                    list.add(item)
                }
            }
            _legacies.value = list
            if (_selectedLegacyId.value == null && list.isNotEmpty()) {
                _selectedLegacyId.value = list.first().id
            }
        } catch (e: Exception) {
            // Safe fallback
        }
    }

    fun serializeLegaciesToJson(): String {
        val list = _legacies.value
        val sb = java.lang.StringBuilder("[")
        for (i in list.indices) {
            if (i > 0) sb.append(",")
            sb.append(list[i].toJson())
        }
        sb.append("]")
        return sb.toString()
    }

    private fun notifyUpdated() {
        onLegaciesUpdated?.invoke(_legacies.value)
    }

    fun selectLegacy(id: String) {
        if (_state.value == LegacyState.RUNNING) {
            pause()
        }
        _selectedLegacyId.value = id
        _sessionElapsedMs.value = 0L
        _state.value = LegacyState.IDLE
    }

    fun createLegacy(name: String, days: Int, targetHours: Int, targetMinutes: Int): LegacyItem {
        val newItem = LegacyItem(
            id = "legacy_${System.currentTimeMillis()}_${(1000..9999).random()}",
            name = name.ifBlank { "UNTITLED LEGACY" },
            totalDays = days.coerceAtLeast(1),
            dailyTargetHours = targetHours.coerceAtLeast(0),
            dailyTargetMinutes = targetMinutes.coerceIn(0, 59)
        )
        val updatedList = _legacies.value + newItem
        _legacies.value = updatedList
        _selectedLegacyId.value = newItem.id
        notifyUpdated()
        return newItem
    }

    fun deleteLegacy(id: String) {
        if (_selectedLegacyId.value == id && _state.value == LegacyState.RUNNING) {
            pause()
        }
        val updatedList = _legacies.value.filter { it.id != id }
        _legacies.value = updatedList
        if (_selectedLegacyId.value == id) {
            _selectedLegacyId.value = updatedList.firstOrNull()?.id
        }
        notifyUpdated()
    }

    fun start(scope: CoroutineScope) {
        val active = getActiveLegacy() ?: return
        if (active.isCompleted) return

        _state.value = LegacyState.RUNNING
        lastTimeMs = SystemClock.elapsedRealtime()

        job?.cancel()
        job = scope.launch(Dispatchers.Main) {
            while (isActive && _state.value == LegacyState.RUNNING) {
                delay(100L)
                val now = SystemClock.elapsedRealtime()
                val delta = now - lastTimeMs
                lastTimeMs = now

                _sessionElapsedMs.value += delta
                addTimeToActiveLegacy(delta)
            }
        }
    }

    fun pause() {
        if (_state.value == LegacyState.RUNNING) {
            _state.value = LegacyState.PAUSED
        }
        job?.cancel()
        job = null
    }

    fun resetSession() {
        pause()
        _sessionElapsedMs.value = 0L
        _state.value = LegacyState.IDLE
    }

    fun stopSession() {
        pause()
        _sessionElapsedMs.value = 0L
        _state.value = LegacyState.IDLE
    }

    fun addManualTime(addedMs: Long) {
        if (addedMs <= 0L) return
        addTimeToActiveLegacy(addedMs)
    }

    fun postponeDays(additionalDays: Int) {
        if (additionalDays <= 0) return
        val active = getActiveLegacy() ?: return
        val updated = active.copy(
            totalDays = active.totalDays + additionalDays,
            postponedDays = active.postponedDays + additionalDays
        )
        updateLegacyItem(updated)
    }

    private fun addTimeToActiveLegacy(deltaMs: Long) {
        val active = getActiveLegacy() ?: return
        val currentDay = (System.currentTimeMillis() / 86400000L).toInt()
        val isNewDay = active.lastSessionDateDay != currentDay

        val newTodayMs = if (isNewDay) deltaMs else active.todayAccumulatedMs + deltaMs
        val newTotalMs = active.accumulatedMs + deltaMs

        val updated = active.copy(
            accumulatedMs = newTotalMs,
            todayAccumulatedMs = newTodayMs,
            lastSessionDateDay = currentDay
        )

        updateLegacyItem(updated)

        if (updated.isCompleted) {
            pause()
            _state.value = LegacyState.COMPLETED
        }
    }

    private fun updateLegacyItem(updatedItem: LegacyItem) {
        val list = _legacies.value.toMutableList()
        val index = list.indexOfFirst { it.id == updatedItem.id }
        if (index != -1) {
            list[index] = updatedItem
            _legacies.value = list
            notifyUpdated()
        }
    }

    fun getRemainingDays(item: LegacyItem): Int {
        val elapsedDays = ((System.currentTimeMillis() - item.createdTimestampMs) / 86400000L).toInt().coerceAtLeast(0)
        return (item.totalDays - elapsedDays).coerceAtLeast(0)
    }

    fun getPaceStatus(item: LegacyItem): PaceStatus {
        if (item.totalDays <= 0) return PaceStatus.ON_PACE
        val elapsedDays = ((System.currentTimeMillis() - item.createdTimestampMs) / 86400000L).toInt().coerceIn(0, item.totalDays)
        val expectedProgressMs = elapsedDays * item.dailyTargetMs
        val diff = item.accumulatedMs - expectedProgressMs

        return when {
            diff > 1800000L -> PaceStatus.AHEAD // > 30 mins ahead
            diff < -1800000L -> PaceStatus.BEHIND // > 30 mins behind
            else -> PaceStatus.ON_PACE
        }
    }
}
