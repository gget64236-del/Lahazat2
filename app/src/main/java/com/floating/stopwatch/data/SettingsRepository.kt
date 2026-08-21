package com.floating.stopwatch.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val STYLE_PRESET = stringPreferencesKey("style_preset")
        val COLOR_PRESET = stringPreferencesKey("color_preset")
        val CUSTOM_COLOR_HEX = stringPreferencesKey("custom_color_hex")
        val FLOATING_X = floatPreferencesKey("floating_x")
        val FLOATING_Y = floatPreferencesKey("floating_y")
        val HAPTIC_INTENSITY = stringPreferencesKey("haptic_intensity")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SHAPE_PRESET = stringPreferencesKey("shape_preset")
        val VOLUME_COUNTER_SCREEN_OFF_ENABLED = booleanPreferencesKey("volume_counter_screen_off_enabled")
        val LAYOUT_ORIENTATION = stringPreferencesKey("layout_orientation")
        val ACTIVE_WIDGETS_COUNT = intPreferencesKey("active_widgets_count")
        val FLOATING_PADDING = floatPreferencesKey("floating_padding")
        val FLOATING_OPACITY = floatPreferencesKey("floating_opacity")
        val MAIN_DISPLAY_SCALE = floatPreferencesKey("main_display_scale")
        val INTERVAL_NAME = stringPreferencesKey("interval_name")
        val INTERVAL_WORK_MS = longPreferencesKey("interval_work_ms")
        val INTERVAL_REST_MS = longPreferencesKey("interval_rest_ms")
        val INTERVAL_ROUNDS = intPreferencesKey("interval_rounds")
        val CUSTOM_INTERVAL_TEMPLATES = stringPreferencesKey("custom_interval_templates")
        val SAVED_LEGACIES_JSON = stringPreferencesKey("saved_legacies_json")
    }

    val savedLegaciesJson: Flow<String> = context.dataStore.data.map {
        it[SAVED_LEGACIES_JSON] ?: ""
    }

    suspend fun setSavedLegaciesJson(json: String) {
        context.dataStore.edit { it[SAVED_LEGACIES_JSON] = json }
    }

    val intervalName: Flow<String> = context.dataStore.data.map { it[INTERVAL_NAME] ?: "HIT" }
    val intervalWorkMs: Flow<Long> = context.dataStore.data.map { it[INTERVAL_WORK_MS] ?: 40000L }
    val intervalRestMs: Flow<Long> = context.dataStore.data.map { it[INTERVAL_REST_MS] ?: 20000L }
    val intervalRounds: Flow<Int> = context.dataStore.data.map { it[INTERVAL_ROUNDS] ?: 8 }

    suspend fun setIntervalConfig(name: String, workMs: Long, restMs: Long, rounds: Int) {
        context.dataStore.edit {
            it[INTERVAL_NAME] = name
            it[INTERVAL_WORK_MS] = workMs
            it[INTERVAL_REST_MS] = restMs
            it[INTERVAL_ROUNDS] = rounds
        }
    }

    val customIntervalTemplates: Flow<String> = context.dataStore.data.map {
        it[CUSTOM_INTERVAL_TEMPLATES] ?: ""
    }

    suspend fun setCustomIntervalTemplates(json: String) {
        context.dataStore.edit { it[CUSTOM_INTERVAL_TEMPLATES] = json }
    }

    val hasAnyWidgetActive: Flow<Boolean> = context.dataStore.data.map { prefs ->
        (0..3).any { i -> prefs[booleanPreferencesKey("widget_${i}_active")] == true }
    }

    // Dynamic Indexed Preferences for Multi-Widget (up to 5 concurrent widgets)
    fun isWidgetActive(index: Int): Flow<Boolean> = context.dataStore.data.map {
        it[booleanPreferencesKey("widget_${index}_active")] ?: false // Default false to avoid auto-spawning
    }

    fun getWidgetType(index: Int): Flow<String> = context.dataStore.data.map {
        it[stringPreferencesKey("widget_${index}_type")] ?: when (index) {
            1 -> "countdown"
            2 -> "counter"
            3 -> "intervals"
            else -> "stopwatch"
        }
    }

    fun getWidgetX(index: Int): Flow<Float> = context.dataStore.data.map {
        it[floatPreferencesKey("widget_${index}_x")] ?: (100f + index * 40f)
    }

    fun getWidgetY(index: Int): Flow<Float> = context.dataStore.data.map {
        it[floatPreferencesKey("widget_${index}_y")] ?: (200f + index * 80f)
    }

    fun getWidgetValue(index: Int): Flow<Long> = context.dataStore.data.map {
        it[longPreferencesKey("widget_${index}_value")] ?: 0L
    }

    fun isWidgetRunning(index: Int): Flow<Boolean> = context.dataStore.data.map {
        it[booleanPreferencesKey("widget_${index}_running")] ?: false
    }

    fun getWidgetWidth(index: Int): Flow<Float> = context.dataStore.data.map {
        it[floatPreferencesKey("widget_${index}_width")] ?: 170.0f
    }

    fun getWidgetHeight(index: Int): Flow<Float> = context.dataStore.data.map {
        it[floatPreferencesKey("widget_${index}_height")] ?: 56.0f
    }

    fun getWidgetSaveDimensions(index: Int): Flow<Boolean> = context.dataStore.data.map {
        it[booleanPreferencesKey("widget_${index}_save_dimensions")] ?: true
    }

    fun getWidgetFontSizeScale(index: Int): Flow<Float> = context.dataStore.data.map {
        it[floatPreferencesKey("widget_${index}_font_size_scale")] ?: 1.0f
    }


    fun getWidgetCountdownDuration(index: Int): Flow<Int> = context.dataStore.data.map {
        it[intPreferencesKey("widget_${index}_countdown_duration")] ?: 300 // 5 minutes default
    }

    suspend fun setWidgetActive(index: Int, active: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("widget_${index}_active")] = active }
    }

    suspend fun setWidgetType(index: Int, type: String) {
        context.dataStore.edit { it[stringPreferencesKey("widget_${index}_type")] = type }
    }

    suspend fun setWidgetPosition(index: Int, x: Float, y: Float) {
        context.dataStore.edit {
            it[floatPreferencesKey("widget_${index}_x")] = x
            it[floatPreferencesKey("widget_${index}_y")] = y
        }
    }

    suspend fun setWidgetValue(index: Int, value: Long) {
        context.dataStore.edit { it[longPreferencesKey("widget_${index}_value")] = value }
    }

    suspend fun setWidgetRunning(index: Int, running: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("widget_${index}_running")] = running }
    }

    suspend fun setWidgetWidth(index: Int, width: Float) {
        context.dataStore.edit { it[floatPreferencesKey("widget_${index}_width")] = width }
    }

    suspend fun setWidgetHeight(index: Int, height: Float) {
        context.dataStore.edit { it[floatPreferencesKey("widget_${index}_height")] = height }
    }

    suspend fun setWidgetSaveDimensions(index: Int, enabled: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("widget_${index}_save_dimensions")] = enabled }
    }

    suspend fun setWidgetFontSizeScale(index: Int, scale: Float) {
        context.dataStore.edit { it[floatPreferencesKey("widget_${index}_font_size_scale")] = scale }
    }


    suspend fun setWidgetCountdownDuration(index: Int, seconds: Int) {
        context.dataStore.edit { it[intPreferencesKey("widget_${index}_countdown_duration")] = seconds }
    }

    val themeMode: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "Midnight" }

    val shapePreset: Flow<String> = context.dataStore.data.map { it[SHAPE_PRESET] ?: "rounded" }
    val volumeCounterScreenOffEnabled: Flow<Boolean> = context.dataStore.data.map { it[VOLUME_COUNTER_SCREEN_OFF_ENABLED] ?: false }
    val layoutOrientation: Flow<String> = context.dataStore.data.map { it[LAYOUT_ORIENTATION] ?: "horizontal" }
    val activeWidgetsCount: Flow<Int> = context.dataStore.data.map { it[ACTIVE_WIDGETS_COUNT] ?: 1 }

    val floatingPadding: Flow<Float> = context.dataStore.data.map { it[FLOATING_PADDING] ?: 6.0f }
    val floatingOpacity: Flow<Float> = context.dataStore.data.map { it[FLOATING_OPACITY] ?: 0.85f }
    val mainDisplayScale: Flow<Float> = context.dataStore.data.map { it[MAIN_DISPLAY_SCALE] ?: 1.0f }

    val stylePreset: Flow<String> = context.dataStore.data.map { it[STYLE_PRESET] ?: "Glass" }
    val colorPreset: Flow<String> = context.dataStore.data.map { it[COLOR_PRESET] ?: "Gold" }
    val customColorHex: Flow<String> = context.dataStore.data.map { it[CUSTOM_COLOR_HEX] ?: "#C9A66B" }

    val floatingX: Flow<Float> = context.dataStore.data.map { it[FLOATING_X] ?: -1.0f }
    val floatingY: Flow<Float> = context.dataStore.data.map { it[FLOATING_Y] ?: -1.0f }

    val hapticIntensity: Flow<String> = context.dataStore.data.map { it[HAPTIC_INTENSITY] ?: "Medium" }

    suspend fun setStylePreset(preset: String) {
        context.dataStore.edit { it[STYLE_PRESET] = preset }
    }

    suspend fun setColorPreset(preset: String) {
        context.dataStore.edit { it[COLOR_PRESET] = preset }
    }

    suspend fun setCustomColorHex(hex: String) {
        context.dataStore.edit { it[CUSTOM_COLOR_HEX] = hex }
    }

    suspend fun setFloatingPosition(x: Float, y: Float) {
        context.dataStore.edit {
            it[FLOATING_X] = x
            it[FLOATING_Y] = y
        }
    }

    suspend fun setHapticIntensity(intensity: String) {
        context.dataStore.edit { it[HAPTIC_INTENSITY] = intensity }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setShapePreset(preset: String) {
        context.dataStore.edit { it[SHAPE_PRESET] = preset }
    }


    suspend fun setVolumeCounterScreenOffEnabled(enabled: Boolean) {
        context.dataStore.edit { it[VOLUME_COUNTER_SCREEN_OFF_ENABLED] = enabled }
    }

    suspend fun setLayoutOrientation(orientation: String) {
        context.dataStore.edit { it[LAYOUT_ORIENTATION] = orientation }
    }

    suspend fun setFloatingPadding(padding: Float) {
        context.dataStore.edit { it[FLOATING_PADDING] = padding }
    }

    suspend fun setFloatingOpacity(opacity: Float) {
        context.dataStore.edit { it[FLOATING_OPACITY] = opacity }
    }

    suspend fun setMainDisplayScale(scale: Float) {
        context.dataStore.edit { it[MAIN_DISPLAY_SCALE] = scale.coerceIn(0.7f, 1.3f) }
    }

    suspend fun setActiveWidgetsCount(count: Int) {
        context.dataStore.edit { it[ACTIVE_WIDGETS_COUNT] = count }
    }
}
