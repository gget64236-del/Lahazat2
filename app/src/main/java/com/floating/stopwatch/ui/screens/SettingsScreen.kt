package com.floating.stopwatch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floating.stopwatch.data.SettingsRepository
import com.floating.stopwatch.ui.components.DragAdjustField
import com.floating.stopwatch.ui.theme.LuxuryColors
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // Presentation state:
    // null -> Showing Category Menu List
    // Non-null -> Category List hidden, showing compact top-left popup for selected category
    var activePopupCategory by remember { mutableStateOf<String?>(null) }

    val stylePreset by settingsRepository.stylePreset.collectAsState(initial = "Glass Premium")
    val colorPreset by settingsRepository.colorPreset.collectAsState(initial = "Gold")
    val customColorHex by settingsRepository.customColorHex.collectAsState(initial = "#C9A66B")
    val hapticIntensity by settingsRepository.hapticIntensity.collectAsState(initial = "Medium")
    val themeMode by settingsRepository.themeMode.collectAsState(initial = "Midnight")
    val mainDisplayScale by settingsRepository.mainDisplayScale.collectAsState(initial = 1.0f)

    val shapes = listOf("rounded", "capsule", "circle", "sharp", "glass")
    val themeModes = listOf("Midnight Dark", "Warm Paper Light", "Obsidian Dark", "Pure White Light")
    val presets = listOf("Glass Premium", "Obsidian", "Titanium", "Ultra Minimal")
    val intensities = listOf("Off", "Light", "Medium", "Strong")
    val colorPresets = listOf("Gold", "Galaxy Blue", "Titanium", "Emerald", "Sapphire", "Violet", "Rose", "Ice", "Amber", "Pure White", "Custom")

    val categories = listOf(
        "Appearance", "Stopwatch", "Countdown", "Counter", "Interval",
        "Sounds & Haptics", "Floating Widgets", "Advanced"
    )

    BackHandler {
        if (activePopupCategory != null) {
            activePopupCategory = null
        } else {
            onBack()
        }
    }

    val activeAccentColor = if (colorPreset == "Custom") {
        try { Color(android.graphics.Color.parseColor(customColorHex)) } catch (e: Exception) { LuxuryColors.AccentGold }
    } else {
        LuxuryColors.fromName(colorPreset)
    }

    // Exact FLOAT ↗ typography component style
    val floatStyle = TextStyle(
        color = activeAccentColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable {
                if (activePopupCategory != null) {
                    activePopupCategory = null
                } else {
                    onBack()
                }
            }
    ) {
        // 1. Category Menu List (Shown when no category popup is active)
        if (activePopupCategory == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.End
            ) {
                categories.forEach { cat ->
                    Text(
                        text = cat.uppercase(),
                        style = floatStyle,
                        modifier = Modifier
                            .clickable {
                                activePopupCategory = cat
                            }
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                    )
                }
            }
        }

        // 2. Compact Opaque Popup at Top-Left (Shown when category is selected)
        activePopupCategory?.let { category ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .wrapContentHeight()
                        .clickable(enabled = false) {}, // Consume taps inside popup
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0A0A0A), // Solid opaque surface - nothing behind readable
                    border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.5f)) // Thin refined outline
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = category.uppercase(),
                                style = TextStyle(color = activeAccentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                            )
                            Text(
                                text = "✕",
                                color = LuxuryColors.WarmGray,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable { activePopupCategory = null }
                                    .padding(4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when (category) {
                            "Appearance" -> {
                                Text(text = "ILLUMINATION MODE", color = LuxuryColors.WarmGray, fontSize = 9.sp, letterSpacing = 1.sp)
                                ResponsiveOptionGrid(
                                    options = themeModes,
                                    selectedOption = themeMode,
                                    accentColor = activeAccentColor,
                                    onOptionSelected = { mode -> scope.launch { settingsRepository.setThemeMode(mode) } }
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(text = "STYLE PRESET", color = LuxuryColors.WarmGray, fontSize = 9.sp, letterSpacing = 1.sp)
                                ResponsiveOptionGrid(
                                    options = presets,
                                    selectedOption = stylePreset,
                                    accentColor = activeAccentColor,
                                    onOptionSelected = { preset -> scope.launch { settingsRepository.setStylePreset(preset) } }
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(text = "COLOR ACCENT PRESET", color = LuxuryColors.WarmGray, fontSize = 9.sp, letterSpacing = 1.sp)
                                ResponsiveOptionGrid(
                                    options = colorPresets,
                                    selectedOption = colorPreset,
                                    accentColor = activeAccentColor,
                                    onOptionSelected = { color -> scope.launch { settingsRepository.setColorPreset(color) } }
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                DragAdjustField(
                                    label = "MAIN DISPLAY SIZE",
                                    value = mainDisplayScale,
                                    minValue = 0.7f,
                                    maxValue = 1.3f,
                                    pixelsPerUnit = 180f,
                                    accentColor = activeAccentColor,
                                    valueFormatter = { String.format("%.2fx", it) },
                                    onValueChange = { scope.launch { settingsRepository.setMainDisplayScale(it) } }
                                )
                            }
                            "Stopwatch" -> WidgetCategorySettings(index = 0, widgetTitle = "STOPWATCH", settingsRepository = settingsRepository, scope = scope, colorPresets = colorPresets, accentColor = activeAccentColor)
                            "Countdown" -> WidgetCategorySettings(index = 1, widgetTitle = "COUNTDOWN", settingsRepository = settingsRepository, scope = scope, colorPresets = colorPresets, accentColor = activeAccentColor)
                            "Counter" -> WidgetCategorySettings(index = 2, widgetTitle = "COUNTER", settingsRepository = settingsRepository, scope = scope, colorPresets = colorPresets, accentColor = activeAccentColor)
                            "Interval" -> {
                                val intervalName by settingsRepository.intervalName.collectAsState(initial = "HIT")
                                val workMs by settingsRepository.intervalWorkMs.collectAsState(initial = 40000L)
                                val restMs by settingsRepository.intervalRestMs.collectAsState(initial = 20000L)
                                val rounds by settingsRepository.intervalRounds.collectAsState(initial = 8)

                                var nameInput by remember(intervalName) { mutableStateOf(intervalName) }
                                var workSecs by remember(workMs) { mutableIntStateOf((workMs / 1000).toInt()) }
                                var restSecs by remember(restMs) { mutableIntStateOf((restMs / 1000).toInt()) }
                                var roundsVal by remember(rounds) { mutableIntStateOf(rounds) }

                                Text(text = "INTERVAL CONFIGURATION", color = LuxuryColors.WarmGray, fontSize = 9.sp, letterSpacing = 1.sp)
                                Spacer(modifier = Modifier.height(6.dp))

                                OutlinedTextField(
                                    value = nameInput,
                                    onValueChange = { nameInput = it },
                                    label = { Text("Interval Name", color = LuxuryColors.WarmGray, fontSize = 9.sp) },
                                    textStyle = TextStyle(color = LuxuryColors.CreamyWhite, fontSize = 11.sp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                DragAdjustField(
                                    label = "WORK DURATION",
                                    value = workSecs.toFloat(),
                                    minValue = 1f,
                                    maxValue = 18000f,
                                    pixelsPerUnit = 4f,
                                    accentColor = activeAccentColor,
                                    valueFormatter = { formatSettingsDuration(it.toInt()) },
                                    onValueChange = { workSecs = it.toInt().coerceIn(1, 18000) }
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                DragAdjustField(
                                    label = "REST DURATION",
                                    value = restSecs.toFloat(),
                                    minValue = 1f,
                                    maxValue = 3600f,
                                    pixelsPerUnit = 4f,
                                    accentColor = activeAccentColor,
                                    valueFormatter = { formatSettingsDuration(it.toInt()) },
                                    onValueChange = { restSecs = it.toInt().coerceIn(1, 3600) }
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("ROUNDS: $roundsVal", color = LuxuryColors.CreamyWhite, fontSize = 10.sp)
                                    Row {
                                        Box(modifier = Modifier.clickable { if (roundsVal > 1) roundsVal -= 1 }.padding(6.dp)) {
                                            Text("-", color = activeAccentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Box(modifier = Modifier.clickable { roundsVal += 1 }.padding(6.dp)) {
                                            Text("+", color = activeAccentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        scope.launch {
                                            settingsRepository.setIntervalConfig(
                                                name = nameInput.ifBlank { "HIT" },
                                                workMs = workSecs * 1000L,
                                                restMs = restSecs * 1000L,
                                                rounds = roundsVal
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = activeAccentColor),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("SAVE CONFIGURATION", color = LuxuryColors.WarmBlack, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                WidgetCategorySettings(index = 3, widgetTitle = "INTERVAL", settingsRepository = settingsRepository, scope = scope, colorPresets = colorPresets, accentColor = activeAccentColor)
                            }
                            "Floating Widgets" -> {
                                val shapePreset by settingsRepository.shapePreset.collectAsState(initial = "rounded")
                                val floatingPadding by settingsRepository.floatingPadding.collectAsState(initial = 6.0f)
                                val floatingOpacity by settingsRepository.floatingOpacity.collectAsState(initial = 0.85f)

                                Text(text = "SHAPE PRESET", color = LuxuryColors.WarmGray, fontSize = 9.sp)
                                ResponsiveOptionGrid(
                                    options = shapes,
                                    selectedOption = shapePreset,
                                    accentColor = activeAccentColor,
                                    onOptionSelected = { shape -> scope.launch { settingsRepository.setShapePreset(shape) } },
                                    displayName = { it.uppercase() }
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                DragAdjustField(
                                    label = "PADDING",
                                    value = floatingPadding,
                                    minValue = 0f,
                                    maxValue = 32f,
                                    pixelsPerUnit = 8f,
                                    accentColor = activeAccentColor,
                                    valueFormatter = { "${it.toInt()}dp" },
                                    onValueChange = { scope.launch { settingsRepository.setFloatingPadding(it) } }
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                DragAdjustField(
                                    label = "OPACITY",
                                    value = floatingOpacity,
                                    minValue = 0f,
                                    maxValue = 1f,
                                    pixelsPerUnit = 180f,
                                    accentColor = activeAccentColor,
                                    valueFormatter = { "${(it * 100).toInt()}%" },
                                    onValueChange = { scope.launch { settingsRepository.setFloatingOpacity(it) } }
                                )
                            }
                            "Sounds & Haptics" -> {
                                Text(text = "HAPTIC FEEDBACK INTENSITY", color = LuxuryColors.WarmGray, fontSize = 9.sp)
                                ResponsiveOptionGrid(
                                    options = intensities,
                                    selectedOption = hapticIntensity,
                                    accentColor = activeAccentColor,
                                    onOptionSelected = { intensity -> scope.launch { settingsRepository.setHapticIntensity(intensity) } }
                                )
                            }
                            "Advanced" -> {
                                val volumeCounterScreenOffEnabled by settingsRepository.volumeCounterScreenOffEnabled.collectAsState(initial = false)
                                val layoutOrientation by settingsRepository.layoutOrientation.collectAsState(initial = "horizontal")

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "VOLUME KEYS COUNTER (SCREEN OFF)", color = LuxuryColors.CreamyWhite, fontSize = 10.sp)
                                    LuxurySlimSwitch(
                                        checked = volumeCounterScreenOffEnabled,
                                        onCheckedChange = { scope.launch { settingsRepository.setVolumeCounterScreenOffEnabled(it) } },
                                        accentColor = activeAccentColor
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "VERTICAL DISPLAY ORIENTATION", color = LuxuryColors.CreamyWhite, fontSize = 10.sp)
                                    LuxurySlimSwitch(
                                        checked = layoutOrientation == "vertical",
                                        onCheckedChange = { scope.launch { settingsRepository.setLayoutOrientation(if (it) "vertical" else "horizontal") } },
                                        accentColor = activeAccentColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LuxurySlimSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = accentColor,
            checkedTrackColor = accentColor.copy(alpha = 0.3f),
            uncheckedThumbColor = LuxuryColors.WarmGray,
            uncheckedTrackColor = Color(0xFF222222)
        )
    )
}

@Composable
private fun ResponsiveOptionGrid(
    options: List<String>,
    selectedOption: String,
    accentColor: Color,
    onOptionSelected: (String) -> Unit,
    displayName: (String) -> String = { it }
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gridGap = 6.dp
        val minCardWidth = 110.dp
        val columns = when {
            maxWidth >= minCardWidth * 3 + gridGap * 2 -> 3
            maxWidth >= minCardWidth * 2 + gridGap -> 2
            else -> 1
        }

        Column(verticalArrangement = Arrangement.spacedBy(gridGap)) {
            options.chunked(columns).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gridGap)
                ) {
                    rowOptions.forEach { option ->
                        val isSelected = selectedOption == option
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) accentColor.copy(alpha = 0.12f) else Color(0xFF121212)
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) accentColor.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.07f)
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clickable { onOptionSelected(option) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onOptionSelected(option) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = accentColor,
                                        unselectedColor = LuxuryColors.WarmGray.copy(alpha = 0.65f)
                                    )
                                )
                                Text(
                                    text = displayName(option),
                                    color = if (isSelected) LuxuryColors.CreamyWhite else LuxuryColors.WarmGray,
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp,
                                    maxLines = 2,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    repeat(columns - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun formatSettingsDuration(totalSeconds: Int): String {
    return String.format("%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60)
}

@Composable
fun WidgetCategorySettings(
    index: Int,
    widgetTitle: String,
    settingsRepository: SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    colorPresets: List<String>,
    accentColor: Color
) {
    val isWidgetActive by settingsRepository.isWidgetActive(index).collectAsState(initial = index == 0)
    val wWidth by settingsRepository.getWidgetWidth(index).collectAsState(initial = 170.0f)
    val wHeight by settingsRepository.getWidgetHeight(index).collectAsState(initial = 56.0f)
    val saveDimensions by settingsRepository.getWidgetSaveDimensions(index).collectAsState(initial = true)
    val fontSizeScale by settingsRepository.getWidgetFontSizeScale(index).collectAsState(initial = 1.0f)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "ENABLE $widgetTitle OVERLAY", color = LuxuryColors.CreamyWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        LuxurySlimSwitch(
            checked = isWidgetActive,
            onCheckedChange = { scope.launch { settingsRepository.setWidgetActive(index, it) } },
            accentColor = accentColor
        )
    }

    if (isWidgetActive) {
        Spacer(modifier = Modifier.height(6.dp))

        DragAdjustField(
            label = "WIDTH",
            value = wWidth,
            minValue = 1f,
            maxValue = 320f,
            pixelsPerUnit = 1.5f,
            accentColor = accentColor,
            valueFormatter = { "${it.toInt()}dp" },
            onValueChange = { scope.launch { settingsRepository.setWidgetWidth(index, it) } }
        )

        DragAdjustField(
            label = "HEIGHT",
            value = wHeight,
            minValue = 1f,
            maxValue = 120f,
            pixelsPerUnit = 2.5f,
            accentColor = accentColor,
            valueFormatter = { "${it.toInt()}dp" },
            onValueChange = { scope.launch { settingsRepository.setWidgetHeight(index, it) } }
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "SAVE FLOATING DIMENSIONS", color = LuxuryColors.CreamyWhite, fontSize = 9.sp)
            LuxurySlimSwitch(
                checked = saveDimensions,
                onCheckedChange = { scope.launch { settingsRepository.setWidgetSaveDimensions(index, it) } },
                accentColor = accentColor
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        DragAdjustField(
            label = "FONT SIZE SCALE",
            value = fontSizeScale,
            minValue = 0.5f,
            maxValue = 1.5f,
            pixelsPerUnit = 180f,
            accentColor = accentColor,
            valueFormatter = { String.format("%.2f", it) },
            onValueChange = { scope.launch { settingsRepository.setWidgetFontSizeScale(index, it) } }
        )
    }
}
