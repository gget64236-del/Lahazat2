package com.floating.stopwatch.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegacyEngineTest {

    private lateinit var legacyEngine: LegacyEngine
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        legacyEngine = LegacyEngine()
    }

    @Test
    fun testCreateLegacyAndActiveLegacy() {
        val created = legacyEngine.createLegacy("Mastering Kotlin", 30, 2, 0)

        assertEquals("Mastering Kotlin", created.name)
        assertEquals(30, created.totalDays)
        assertEquals(2, created.dailyTargetHours)
        assertEquals(0, created.dailyTargetMinutes)

        val active = legacyEngine.getActiveLegacy()
        assertNotNull(active)
        assertEquals(created.id, active?.id)
    }

    @Test
    fun testMultipleLegaciesAndSelection() {
        val legacy1 = legacyEngine.createLegacy("Legacy 1", 10, 1, 0)
        val legacy2 = legacyEngine.createLegacy("Legacy 2", 20, 2, 30)

        assertEquals(2, legacyEngine.legacies.value.size)

        legacyEngine.selectLegacy(legacy1.id)
        assertEquals(legacy1.id, legacyEngine.getActiveLegacy()?.id)

        legacyEngine.selectLegacy(legacy2.id)
        assertEquals(legacy2.id, legacyEngine.getActiveLegacy()?.id)
    }

    @Test
    fun testManualTimeAddition() {
        val legacy = legacyEngine.createLegacy("Book Writing", 10, 1, 0)
        val oneHourMs = 3600000L

        legacyEngine.addManualTime(oneHourMs)

        val updated = legacyEngine.getActiveLegacy()
        assertEquals(oneHourMs, updated?.accumulatedMs)
        assertEquals(oneHourMs, updated?.todayAccumulatedMs)
    }

    @Test
    fun testPostponeDays() {
        val legacy = legacyEngine.createLegacy("Project Target", 10, 1, 0)

        legacyEngine.postponeDays(5)

        val updated = legacyEngine.getActiveLegacy()
        assertEquals(15, updated?.totalDays)
        assertEquals(5, updated?.postponedDays)
    }

    @Test
    fun testPaceStatusCalculation() {
        val legacy = legacyEngine.createLegacy("Pace Test", 10, 2, 0) // Daily target = 2 hours

        val active = legacyEngine.getActiveLegacy()!!
        val paceStatus = legacyEngine.getPaceStatus(active)

        assertEquals(PaceStatus.ON_PACE, paceStatus)
    }

    @Test
    fun testSerializationAndDeserialization() {
        val created = legacyEngine.createLegacy("Persistent Legacy", 15, 3, 30)
        legacyEngine.addManualTime(1800000L) // 30 mins

        val json = legacyEngine.serializeLegaciesToJson()
        assertTrue(json.contains("Persistent Legacy"))

        val newEngine = LegacyEngine()
        newEngine.loadLegaciesFromJson(json)

        val restoredLegacies = newEngine.legacies.value
        assertEquals(1, restoredLegacies.size)
        assertEquals("Persistent Legacy", restoredLegacies[0].name)
        assertEquals(15, restoredLegacies[0].totalDays)
        assertEquals(1800000L, restoredLegacies[0].accumulatedMs)
    }
}
