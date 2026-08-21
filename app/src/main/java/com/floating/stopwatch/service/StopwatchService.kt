package com.floating.stopwatch.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.floating.stopwatch.MainActivity
import com.floating.stopwatch.R
import com.floating.stopwatch.data.SettingsRepository
import com.floating.stopwatch.domain.CountdownEngine
import com.floating.stopwatch.domain.HapticController
import com.floating.stopwatch.domain.StopwatchEngine
import com.floating.stopwatch.ui.components.TimeDisplay
import com.floating.stopwatch.ui.theme.LuxuryColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt
import androidx.compose.animation.core.tween
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class StopwatchService : Service() {

    companion object {
        const val CHANNEL_ID = "StopwatchOverlayChannel"
        const val NOTIFICATION_ID = 4842

        // Multi-widget tracking list of instances
        private val activeServices = mutableListOf<StopwatchService>()

        private var sharedEngine: StopwatchEngine? = null
        private var sharedCountdownEngine: CountdownEngine? = null
        private var sharedIntervalEngine: com.floating.stopwatch.domain.IntervalEngine? = null
        private var sharedLegacyEngine: com.floating.stopwatch.domain.LegacyEngine? = null

        fun getEngine(): StopwatchEngine {
            if (sharedEngine == null) {
                sharedEngine = StopwatchEngine()
            }
            return sharedEngine!!
        }

        fun getCountdownEngine(): CountdownEngine {
            if (sharedCountdownEngine == null) {
                sharedCountdownEngine = CountdownEngine()
            }
            return sharedCountdownEngine!!
        }

        fun getIntervalEngine(): com.floating.stopwatch.domain.IntervalEngine {
            if (sharedIntervalEngine == null) {
                sharedIntervalEngine = com.floating.stopwatch.domain.IntervalEngine()
            }
            return sharedIntervalEngine!!
        }

        fun triggerHapticOnAll(intensity: String, effect: String) {
            activeServices.forEach {
                try {
                    it.hapticController.trigger(intensity, effect)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        fun handleVolumePress(increment: Boolean): Boolean {
            if (increment) {
                getEngine().incrementCounter()
            } else {
                getEngine().decrementCounter()
            }
            val newVal = getEngine().counterValue.value.toInt()
            activeServices.forEach { service ->
                service.serviceScope.launch {
                    val intensity = service.settingsRepository.hapticIntensity.first()
                    service.hapticController.trigger(intensity, if (increment) "Lap" else "Reset")
                }
                service.checkCounterMilestoneAndVibrate(newVal)
            }
            return true
        }

        fun getLegacyEngine(): com.floating.stopwatch.domain.LegacyEngine {
            if (sharedLegacyEngine == null) {
                sharedLegacyEngine = com.floating.stopwatch.domain.LegacyEngine()
            }
            return sharedLegacyEngine!!
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepository: SettingsRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var hapticController: HapticController

    // Multi-Widget context structures
    private val activeOverlays = mutableMapOf<Int, ActiveOverlay>()
    private val activeMenuOverlays = mutableMapOf<Int, ActiveMenuOverlay>()
    private val menuAnchorStates = mutableMapOf<Int, MutableState<MenuAnchor>>()
    private val widgetStates = List(4) { index -> WidgetState(index) }

    private data class MenuAnchor(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private class ActiveOverlay(
        val index: Int,
        val lifecycleOwner: ComposeOverlayLifecycleOwner,
        val composeView: ComposeView,
        val params: WindowManager.LayoutParams
    )

    private class ActiveMenuOverlay(
        val index: Int,
        val lifecycleOwner: ComposeOverlayLifecycleOwner,
        val composeView: ComposeView,
        val params: WindowManager.LayoutParams
    )

    private class WidgetState(
        val index: Int,
        val type: MutableStateFlow<String> = MutableStateFlow("stopwatch"),
        val running: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val elapsedOrValue: MutableStateFlow<Long> = MutableStateFlow(0L),
        val baseTime: MutableStateFlow<Long> = MutableStateFlow(0L),
        val countdownDuration: MutableStateFlow<Int> = MutableStateFlow(300), // default 5 mins
        val tapCount: MutableStateFlow<Int> = MutableStateFlow(0),
        val isVolumeCounterActive: MutableStateFlow<Boolean> = MutableStateFlow(false),
        val milestones: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    )

    override fun onCreate() {
        super.onCreate()
        activeServices.add(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsRepository = SettingsRepository(applicationContext)
        hapticController = HapticController(applicationContext)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Observe shared StopwatchEngine for real-time bidirectional synchronization
        serviceScope.launch {
            getEngine().elapsedTimeMs.collectLatest { engineElapsed ->
                widgetStates.forEach { state ->
                    if (state.type.value == "stopwatch") {
                        state.elapsedOrValue.value = engineElapsed
                    }
                }
            }
        }

        // Observe shared counterValue for real-time bidirectional synchronization
        serviceScope.launch {
            getEngine().counterValue.collectLatest { count ->
                widgetStates.forEach { state ->
                    if (state.type.value == "counter") {
                        state.tapCount.value = count.toInt()
                        state.elapsedOrValue.value = count
                        serviceScope.launch {
                            settingsRepository.setWidgetValue(state.index, count)
                        }
                    }
                }
            }
        }

        serviceScope.launch {
            getEngine().state.collectLatest { engineState ->
                widgetStates.forEach { state ->
                    if (state.type.value == "stopwatch") {
                        state.running.value = (engineState == com.floating.stopwatch.domain.StopwatchState.Running)
                    }
                }
            }
        }

        // Observe shared CountdownEngine for real-time bidirectional synchronization & completion trigger
        serviceScope.launch {
            getCountdownEngine().remainingTimeMs.collectLatest { countdownMs ->
                widgetStates.forEach { state ->
                    if (state.type.value == "countdown") {
                        val prevMs = state.elapsedOrValue.value
                        state.elapsedOrValue.value = countdownMs
                        if (prevMs > 0L && countdownMs == 0L && state.running.value) {
                            state.running.value = false
                            triggerCountdownCompletion(state.index)
                        }
                    }
                }
            }
        }

        serviceScope.launch {
            getCountdownEngine().isRunning.collectLatest { isRunning ->
                widgetStates.forEach { state ->
                    if (state.type.value == "countdown") {
                        state.running.value = isRunning
                    }
                }
            }
        }

        // Monitor and auto-sync active widgets list
        startWidgetLifecycleManager()

        // Screen-off / background volume button handler for counter mode
        serviceScope.launch {
            combine(
                settingsRepository.volumeCounterScreenOffEnabled,
                snapshotFlow { widgetStates.any { it.type.value == "counter" && it.isVolumeCounterActive.value } }
            ) { enabled, isVolActive ->
                val hasActiveCounter = widgetStates.any { it.type.value == "counter" }
                (enabled && hasActiveCounter) || isVolActive
            }.collectLatest { active ->
                setupMediaSessionForScreenOffVolume(active, true)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startWidgetLifecycleManager() {
        // Observe settings for up to 4 fixed widgets and spawn/dismiss them reactively
        for (i in 0..3) {
            serviceScope.launch {
                settingsRepository.isWidgetActive(i).collectLatest { active ->
                    if (active) {
                        if (!activeOverlays.containsKey(i)) {
                            // Fetch persisted state first
                            val type = settingsRepository.getWidgetType(i).first()
                            val value = settingsRepository.getWidgetValue(i).first()
                            val running = settingsRepository.isWidgetRunning(i).first()

                            android.util.Log.d("CounterWidget", "Widget #$i type=$type restored initial value=$value from DataStore")
                            widgetStates[i].type.value = type
                            widgetStates[i].elapsedOrValue.value = value
                            widgetStates[i].running.value = running
                            if (type == "counter") {
                                widgetStates[i].tapCount.value = value.toInt()
                                android.util.Log.d("CounterWidget", "Widget #$i tapCount initialized to ${widgetStates[i].tapCount.value}")
                            }

                            spawnWidget(i)

                            if (type == "counter") {
                                widgetStates[i].running.value = false
                                serviceScope.launch { settingsRepository.setWidgetRunning(i, false) }
                            }
                        }
                    } else {
                        dismissWidget(i)
                    }
                }
            }

            // Sync countdown duration changes dynamically
            serviceScope.launch {
                settingsRepository.getWidgetCountdownDuration(i).collectLatest { seconds ->
                    widgetStates[i].countdownDuration.value = seconds
                    if (widgetStates[i].type.value == "countdown" && !widgetStates[i].running.value) {
                        widgetStates[i].elapsedOrValue.value = seconds * 1000L
                        getCountdownEngine().setDuration(seconds * 1000L)
                    }
                }
            }

            // Sync type changes dynamically
            serviceScope.launch {
                settingsRepository.getWidgetType(i).collectLatest { type ->
                    widgetStates[i].type.value = type
                    if (type == "counter") {
                        widgetStates[i].tapCount.value = widgetStates[i].elapsedOrValue.value.toInt()
                    }
                }
            }

            // Sync width and height changes dynamically for each individual widget
            serviceScope.launch {
                combine(
                    settingsRepository.getWidgetWidth(i),
                    settingsRepository.getWidgetHeight(i)
                ) { wDp, hDp -> Pair(wDp, hDp) }.collectLatest { (wDp, hDp) ->
                    val overlay = activeOverlays[i]
                    if (overlay != null && overlay.composeView.isAttachedToWindow) {
                        val wPx = wDp.toInt().dpToPx().coerceAtLeast(1)
                        val hPx = hDp.toInt().dpToPx().coerceAtLeast(1)
                        if (overlay.params.width != wPx || overlay.params.height != hPx) {
                            overlay.params.width = wPx
                            overlay.params.height = hPx
                            try {
                                windowManager.updateViewLayout(overlay.composeView, overlay.params)
                                activeMenuOverlays[i]?.composeView?.post { updateMenuAnchor(i) }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun spawnWidget(index: Int) {
        val owner = ComposeOverlayLifecycleOwner().apply {
            onCreate()
            onStart()
            onResume()
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)

            setContent {
                val state = widgetStates[index]
                val type by state.type.collectAsState()
                val running by state.running.collectAsState()
                val elapsedOrValue by state.elapsedOrValue.collectAsState()
                val tapCount by state.tapCount.collectAsState()
                val isVolumeActive by state.isVolumeCounterActive.collectAsState()
                val milestones by state.milestones.collectAsState()

                val floatingWidth by settingsRepository.getWidgetWidth(index).collectAsState(initial = 170.0f)
                val floatingHeight by settingsRepository.getWidgetHeight(index).collectAsState(initial = 56.0f)
                val stylePreset by settingsRepository.stylePreset.collectAsState(initial = "Glass Premium")
                val colorPreset by settingsRepository.colorPreset.collectAsState(initial = "Gold")
                val customColorHex by settingsRepository.customColorHex.collectAsState(initial = "#C9A66B")
                val hapticIntensity by settingsRepository.hapticIntensity.collectAsState(initial = "Medium")

                val shapePreset by settingsRepository.shapePreset.collectAsState(initial = "rounded")
                val fontSizeScale by settingsRepository.getWidgetFontSizeScale(index).collectAsState(initial = 1.0f)
                val layoutOrientation by settingsRepository.layoutOrientation.collectAsState(initial = "horizontal")
                val floatingPadding by settingsRepository.floatingPadding.collectAsState(initial = 6.0f)
                val floatingOpacity by settingsRepository.floatingOpacity.collectAsState(initial = 0.85f)


                val accentColor = if (colorPreset == "Custom") {
                    try { Color(android.graphics.Color.parseColor(customColorHex)) } catch (e: Exception) { LuxuryColors.AccentGold }
                } else {
                    LuxuryColors.fromName(colorPreset)
                }

                // Coordinate bindings
                val initialX by settingsRepository.getWidgetX(index).collectAsState(initial = -1.0f)
                val initialY by settingsRepository.getWidgetY(index).collectAsState(initial = -1.0f)

                LaunchedEffect(initialX, initialY) {
                    val overlay = activeOverlays[index]
                    if (initialX != -1.0f && initialY != -1.0f && overlay != null) {
                        overlay.params.x = initialX.roundToInt()
                        overlay.params.y = initialY.roundToInt()
                        windowManager.updateViewLayout(overlay.composeView, overlay.params)
                        activeMenuOverlays[index]?.composeView?.post { updateMenuAnchor(index) }
                    }
                }

                LaunchedEffect(floatingWidth, floatingHeight) {
                    val overlay = activeOverlays[index]
                    if (overlay != null && overlay.composeView.isAttachedToWindow) {
                        val wPx = floatingWidth.toInt().dpToPx().coerceAtLeast(1)
                        val hPx = floatingHeight.toInt().dpToPx().coerceAtLeast(1)
                        if (overlay.params.width != wPx || overlay.params.height != hPx) {
                            overlay.params.width = wPx
                            overlay.params.height = hPx
                            try {
                                windowManager.updateViewLayout(overlay.composeView, overlay.params)
                                activeMenuOverlays[index]?.composeView?.post { updateMenuAnchor(index) }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }

                // Permanently preserve FLAG_NOT_FOCUSABLE to prevent stealing input focus from underlying applications
                LaunchedEffect(Unit) {
                    val overlay = activeOverlays[index]
                    if (overlay != null) {
                        overlay.params.flags = overlay.params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        windowManager.updateViewLayout(overlay.composeView, overlay.params)
                        activeMenuOverlays[index]?.composeView?.post { updateMenuAnchor(index) }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    ThemedOverlayContainer(
                        index = index,
                        widgetType = type,
                        running = running,
                        elapsedTimeMs = elapsedOrValue,
                        tapCount = tapCount,
                        isVolumeActive = isVolumeActive,
                        milestones = milestones,
                        showCentiseconds = true,
                        stylePreset = stylePreset,
                        accentColor = accentColor,
                        shapePreset = shapePreset,
                        fontSizeScale = fontSizeScale,
                        gradientEnabled = false,
                        layoutOrientation = layoutOrientation,
                        paddingDpValue = floatingPadding,
                        opacity = floatingOpacity,
                        onMovementDrag = { dx, dy ->
                            activeOverlays[index]?.let {
                                it.params.x += dx.roundToInt()
                                it.params.y += dy.roundToInt()
                                windowManager.updateViewLayout(it.composeView, it.params)
                                activeMenuOverlays[index]?.composeView?.post { updateMenuAnchor(index) }
                            }
                        },
                        onMovementRelease = {
                            activeOverlays[index]?.let {
                                smartEdgeSnapAndClamp(it.params)
                                serviceScope.launch {
                                    settingsRepository.setWidgetPosition(index, it.params.x.toFloat(), it.params.y.toFloat())
                                }
                            }
                        },
                        onToggleMenu = {
                            if (activeMenuOverlays.containsKey(index)) {
                                dismissMenuOverlay(index)
                            } else {
                                spawnMenuOverlay(index, type, fontSizeScale, isVolumeActive)
                            }
                        },
                        onAction = { action ->
                            handleWidgetAction(index, action, hapticIntensity)
                        }
                    )
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            170.dpToPx().coerceAtLeast(1),
            56.dpToPx().coerceAtLeast(1),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100 + index * 40
            y = 200 + index * 80
        }

        composeView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {
                serviceScope.launch {
                    val wDp = settingsRepository.getWidgetWidth(index).first()
                    val hDp = settingsRepository.getWidgetHeight(index).first()
                    val overlay = activeOverlays[index]
                    if (overlay != null) {
                        val wPx = wDp.toInt().dpToPx().coerceAtLeast(1)
                        val hPx = hDp.toInt().dpToPx().coerceAtLeast(1)
                        if (overlay.params.width != wPx || overlay.params.height != hPx) {
                            overlay.params.width = wPx
                            overlay.params.height = hPx
                            try {
                                windowManager.updateViewLayout(overlay.composeView, overlay.params)
                                activeMenuOverlays[index]?.composeView?.post { updateMenuAnchor(index) }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
            override fun onViewDetachedFromWindow(v: android.view.View) {}
        })

        activeOverlays[index] = ActiveOverlay(index, owner, composeView, params)
        windowManager.addView(composeView, params)
    }

    private fun handleWidgetAction(index: Int, action: String, hapticIntensity: String) {
        val state = widgetStates[index]
        val type = state.type.value
        when (action) {
            "Start" -> {
                hapticController.trigger(hapticIntensity, "Start")
                if (type == "stopwatch") {
                    getEngine().start()
                } else if (type == "countdown") {
                    getCountdownEngine().start()
                } else if (type == "intervals") {
                    getIntervalEngine().start(serviceScope)
                }
                state.running.value = true
                serviceScope.launch { settingsRepository.setWidgetRunning(index, true) }
            }
            "Stop" -> {
                hapticController.trigger(hapticIntensity, "Stop")
                if (type == "stopwatch") {
                    getEngine().pause()
                } else if (type == "countdown") {
                    getCountdownEngine().pause()
                } else if (type == "intervals") {
                    getIntervalEngine().pause()
                }
                state.running.value = false
                serviceScope.launch { settingsRepository.setWidgetRunning(index, false) }
            }
            "Reset" -> {
                hapticController.trigger(hapticIntensity, "Reset")
                if (type == "stopwatch") {
                    getEngine().reset()
                } else if (type == "countdown") {
                    getCountdownEngine().reset()
                } else if (type == "intervals") {
                    getIntervalEngine().reset()
                }
                state.running.value = false
                if (type == "countdown") {
                    state.elapsedOrValue.value = state.countdownDuration.value * 1000L
                    serviceScope.launch { settingsRepository.setWidgetValue(index, state.countdownDuration.value * 1000L) }
                } else {
                    state.elapsedOrValue.value = 0L
                    state.tapCount.value = 0
                    serviceScope.launch { settingsRepository.setWidgetValue(index, 0L) }
                }
                serviceScope.launch { settingsRepository.setWidgetRunning(index, false) }
            }
            "Increment" -> {
                getEngine().incrementCounter()
                val newVal = getEngine().counterValue.value.toInt()
                checkCounterMilestoneAndVibrate(newVal)
                if (newVal != 33 && newVal != 66 && newVal != 99 && newVal != 100) {
                    hapticController.trigger(hapticIntensity, "Lap")
                }
            }
            "Decrement" -> {
                getEngine().decrementCounter()
                val newVal = getEngine().counterValue.value.toInt()
                checkCounterMilestoneAndVibrate(newVal)
                if (newVal != 33 && newVal != 66 && newVal != 99 && newVal != 100) {
                    hapticController.trigger(hapticIntensity, "Reset")
                }
            }
            "ToggleVolume" -> {
                hapticController.trigger(hapticIntensity, "Lap")
                state.isVolumeCounterActive.value = !state.isVolumeCounterActive.value
            }
            "Milestone" -> {
                hapticController.trigger(hapticIntensity, "Lap")
                if (type == "stopwatch") {
                    getEngine().lap()
                } else {
                    val currentMilestone = if (type == "countdown") {
                        "Focus Session remaining: " + formatDuration(state.elapsedOrValue.value)
                    } else {
                        "Time record: " + formatDuration(state.elapsedOrValue.value)
                    }
                    state.milestones.value = state.milestones.value + currentMilestone
                }
            }
            "OpenApp" -> {
                val intent = Intent(this@StopwatchService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("OPEN_SETTINGS", true)
                }
                startActivity(intent)
            }
            "Close" -> {
                dismissMenuOverlay(index)
                serviceScope.launch { settingsRepository.setWidgetActive(index, false) }
            }
        }
    }

    private fun spawnMenuOverlay(index: Int, widgetType: String, fontSizeScale: Float, isVolumeActive: Boolean) {
        dismissMenuOverlay(index)
        val overlay = activeOverlays[index] ?: return

        val owner = ComposeOverlayLifecycleOwner().apply {
            onCreate()
            onStart()
            onResume()
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val menuAnchorState = mutableStateOf(
            MenuAnchor(
                x = overlay.params.x,
                y = overlay.params.y,
                width = overlay.params.width,
                height = overlay.params.height
            )
        )
        menuAnchorStates[index] = menuAnchorState

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)

            setContent {
                val hapticIntensity by settingsRepository.hapticIntensity.collectAsState(initial = "Medium")
                var menuSize by remember { mutableStateOf(IntSize.Zero) }
                var containerSize by remember { mutableStateOf(IntSize.Zero) }
                val density = androidx.compose.ui.platform.LocalDensity.current
                val systemInsets = WindowInsets.systemBars
                val topInset = systemInsets.getTop(density)
                val bottomInset = systemInsets.getBottom(density)
                val edgeMargin = with(density) { 8.dp.roundToPx() }
                val anchor by menuAnchorState
                var isDismissing by remember { mutableStateOf(false) }
                LaunchedEffect(isDismissing) {
                    if (isDismissing) {
                        delay(140)
                        dismissMenuOverlay(index)
                    }
                }
                fun requestDismiss() {
                    if (!isDismissing) isDismissing = true
                }
                val menuPosition = remember(anchor, menuSize, containerSize, topInset, bottomInset) {
                    val maxX = (containerSize.width - menuSize.width - edgeMargin).coerceAtLeast(edgeMargin)
                    val availableBottom = (containerSize.height - bottomInset - edgeMargin).coerceAtLeast(edgeMargin)
                    val availableTop = (topInset + edgeMargin).coerceAtMost(availableBottom)
                    val belowY = anchor.y + anchor.height
                    val preferredY = if (belowY + menuSize.height <= availableBottom) {
                        belowY
                    } else {
                        anchor.y - menuSize.height
                    }
                    val maxY = (availableBottom - menuSize.height).coerceAtLeast(availableTop)
                    IntOffset(
                        x = anchor.x.coerceIn(edgeMargin, maxX),
                        y = preferredY.coerceIn(availableTop, maxY)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { containerSize = it.size }
                        .pointerInput(isDismissing) {
                            detectTapGestures(onTap = { requestDismiss() })
                        }
                ) {
                    AnimatedVisibility(
                        visible = !isDismissing,
                        enter = fadeIn(animationSpec = tween(160)) + scaleIn(animationSpec = tween(160), initialScale = 0.96f),
                        exit = fadeOut(animationSpec = tween(120)) + scaleOut(animationSpec = tween(120), targetScale = 0.96f)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(with(density) { anchor.width.coerceAtLeast(1).toDp() })
                                .offset { menuPosition }
                                .onGloballyPositioned { menuSize = it.size }
                        ) {
                            LuxuryTextDropdownMenu(
                                widgetType = widgetType,
                                fontSizeScale = fontSizeScale,
                                isVolumeActive = isVolumeActive,
                                onAction = { action ->
                                    handleWidgetAction(index, action, hapticIntensity)
                                    requestDismiss()
                                },
                                onDismiss = { requestDismiss() }
                            )
                        }
                    }
                }
            }
        }

        activeMenuOverlays[index] = ActiveMenuOverlay(index, owner, composeView, menuParams)
        try {
            windowManager.addView(composeView, menuParams)
            composeView.post { updateMenuAnchor(index) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissMenuOverlay(index: Int) {
        val menuOverlay = activeMenuOverlays.remove(index) ?: return
        menuAnchorStates.remove(index)
        menuOverlay.lifecycleOwner.apply {
            onPause()
            onStop()
            onDestroy()
        }
        try {
            windowManager.removeView(menuOverlay.composeView)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun updateMenuAnchor(index: Int) {
        val overlay = activeOverlays[index] ?: return
        val menu = activeMenuOverlays[index] ?: return
        if (!overlay.composeView.isAttachedToWindow || !menu.composeView.isAttachedToWindow) return

        val widgetLocation = IntArray(2)
        val menuLocation = IntArray(2)
        overlay.composeView.getLocationOnScreen(widgetLocation)
        menu.composeView.getLocationOnScreen(menuLocation)

        menuAnchorStates[index]?.value = MenuAnchor(
            x = widgetLocation[0] - menuLocation[0],
            y = widgetLocation[1] - menuLocation[1],
            width = overlay.composeView.width.coerceAtLeast(overlay.params.width),
            height = overlay.composeView.height.coerceAtLeast(overlay.params.height)
        )
    }

    private var mediaSession: android.media.session.MediaSession? = null

    private fun setupMediaSessionForScreenOffVolume(enabled: Boolean, hasActiveCounter: Boolean) {
        if (enabled && hasActiveCounter) {
            if (mediaSession == null) {
                mediaSession = android.media.session.MediaSession(this, "StopwatchVolumeCounterSession").apply {
                    val volumeProvider = object : android.media.VolumeProvider(
                        android.media.VolumeProvider.VOLUME_CONTROL_RELATIVE,
                        100,
                        50
                    ) {
                        override fun onAdjustVolume(direction: Int) {
                            if (direction > 0) {
                                handleCounterVolumePress(increment = true)
                            } else if (direction < 0) {
                                handleCounterVolumePress(increment = false)
                            }
                        }
                    }
                    setPlaybackToRemote(volumeProvider)
                    setCallback(object : android.media.session.MediaSession.Callback() {
                        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                            }
                            if (keyEvent != null && keyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                if (keyEvent.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                                    handleCounterVolumePress(increment = true)
                                    return true
                                } else if (keyEvent.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                                    handleCounterVolumePress(increment = false)
                                    return true
                                }
                            }
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }
                    })
                }
            }
            val playbackState = android.media.session.PlaybackState.Builder()
                .setState(android.media.session.PlaybackState.STATE_PLAYING, 0L, 1.0f)
                .setActions(android.media.session.PlaybackState.ACTION_PLAY)
                .build()
            mediaSession?.setPlaybackState(playbackState)
            mediaSession?.isActive = true
        } else {
            mediaSession?.isActive = false
        }
    }

    private fun checkCounterMilestoneAndVibrate(newValue: Int) {
        if (newValue == 33 || newValue == 66 || newValue == 99 || newValue == 100) {
            serviceScope.launch {
                val intensity = settingsRepository.hapticIntensity.first()
                hapticController.trigger(intensity, "CounterMilestone")
            }
        }
    }

    private fun handleCounterVolumePress(increment: Boolean) {
        if (increment) {
            getEngine().incrementCounter()
        } else {
            getEngine().decrementCounter()
        }
        val newVal = getEngine().counterValue.value.toInt()
        serviceScope.launch {
            val intensity = settingsRepository.hapticIntensity.first()
            hapticController.trigger(intensity, if (increment) "Lap" else "Reset")
        }
        checkCounterMilestoneAndVibrate(newVal)
    }

    private fun dismissWidget(index: Int) {
        dismissMenuOverlay(index)
        val overlay = activeOverlays.remove(index) ?: return
        overlay.lifecycleOwner.apply {
            onPause()
            onStop()
            onDestroy()
        }
        try {
            windowManager.removeView(overlay.composeView)
        } catch (e: Exception) { e.printStackTrace() }
    }


    private fun triggerCountdownCompletion(index: Int) {
        serviceScope.launch {
            hapticController.trigger("Strong", "Reset")
            // Send complete notification alert
            val notification = NotificationCompat.Builder(this@StopwatchService, CHANNEL_ID)
                .setContentTitle("Focus Session Complete!")
                .setContentText("Focus session countdown for Widget #$index has finished.")
                .setSmallIcon(R.drawable.ic_stat_stopwatch)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(5000 + index, notification)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        val cents = (ms % 1000) / 10
        return String.format("%02d:%02d.%02d", mins, secs, cents)
    }

    private fun smartEdgeSnapAndClamp(lp: WindowManager.LayoutParams) {
        val metrics = applicationContext.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val leftX = 0
        val rightX = screenWidth - lp.width
        val topY = 0
        val bottomY = screenHeight - lp.height

        if (lp.x < leftX) lp.x = leftX
        if (lp.x > rightX) lp.x = rightX
        if (lp.y < topY) lp.y = topY
        if (lp.y > bottomY) lp.y = bottomY

        // Clean layout refresh
        activeOverlays.values.find { it.params == lp }?.let {
            windowManager.updateViewLayout(it.composeView, lp)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        activeOverlays.values.forEach {
            smartEdgeSnapAndClamp(it.params)
            activeMenuOverlays[it.index]?.composeView?.post { updateMenuAnchor(it.index) }
        }
    }

    @Composable
    fun ThemedOverlayContainer(
        index: Int,
        widgetType: String,
        running: Boolean,
        elapsedTimeMs: Long,
        tapCount: Int,
        isVolumeActive: Boolean,
        milestones: List<String>,
        showCentiseconds: Boolean,
        stylePreset: String,
        accentColor: Color,
        shapePreset: String,
        fontSizeScale: Float,
        gradientEnabled: Boolean,
        layoutOrientation: String,
        paddingDpValue: Float,
        opacity: Float,
        onMovementDrag: (Float, Float) -> Unit,
        onMovementRelease: () -> Unit,
        onToggleMenu: () -> Unit,
        onAction: (String) -> Unit
    ) {
        val finalCornerRadius = when (shapePreset) {
            "capsule" -> 32.dp
            "circle" -> 99.dp
            "sharp" -> 0.dp
            else -> 16.dp
        }

        val safePadding = paddingDpValue.coerceAtLeast(0.0f)

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                if (widgetType == "counter" && !isVolumeActive) {
                                    onAction("Increment")
                                } else {
                                    onToggleMenu()
                                }
                            },
                            onLongPress = {
                                onToggleMenu()
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { onMovementRelease() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onMovementDrag(dragAmount.x, dragAmount.y)
                            }
                        )
                    }
                    .onKeyEvent { keyEvent ->
                        if (isVolumeActive && widgetType == "counter" && keyEvent.type == KeyEventType.KeyDown) {
                            if (keyEvent.key == Key.VolumeUp) {
                                onAction("Increment")
                                true
                            } else if (keyEvent.key == Key.VolumeDown) {
                                onAction("Decrement")
                                true
                            } else false
                        } else false
                    },
                contentAlignment = Alignment.Center
            ) {
                // Backdrop
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (stylePreset == "Glass Premium" || shapePreset == "glass") {
                                Modifier
                                    .background(Color.White.copy(alpha = 0.12f * opacity), RoundedCornerShape(finalCornerRadius))
                                    .blur(16.dp)
                            } else if (stylePreset == "Obsidian") {
                                Modifier.background(Color(0xFF0A0A0A).copy(alpha = 0.88f * opacity), RoundedCornerShape(finalCornerRadius))
                            } else if (stylePreset == "Titanium") {
                                val titaniumBrush = Brush.verticalGradient(
                                    colors = listOf(Color(0xFF2C2F33), Color(0xFF1E2124))
                                )
                                Modifier.background(titaniumBrush, RoundedCornerShape(finalCornerRadius))
                            } else {
                                Modifier.background(Color.Black.copy(alpha = opacity), RoundedCornerShape(finalCornerRadius))
                            }
                        )
                )

                // Content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(safePadding.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (widgetType == "counter") {
                            Text(
                                text = "$tapCount",
                                style = TextStyle(
                                    color = if (isVolumeActive) accentColor else accentColor,
                                    fontSize = (22.sp.value * fontSizeScale).sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                            if (isVolumeActive) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF9500))
                                )
                            }
                        } else if (widgetType == "intervals") {
                            val intervalEngine = getIntervalEngine()
                            val intervalState by intervalEngine.state.collectAsState()
                            val stageRemainingMs by intervalEngine.stageRemainingMs.collectAsState()
                            val currentStage = intervalEngine.getCurrentStage()
                            val currentRound by intervalEngine.currentRound.collectAsState()
                            val activeTemplate by intervalEngine.activeTemplate.collectAsState()

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = activeTemplate.name.uppercase(),
                                    style = TextStyle(
                                        color = accentColor,
                                        fontSize = (11.sp.value * fontSizeScale).sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                )
                                Text(
                                    text = currentStage?.name?.uppercase() ?: "WORK",
                                    style = TextStyle(
                                        color = LuxuryColors.CreamyWhite,
                                        fontSize = (10.sp.value * fontSizeScale).sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                                TimeDisplay(
                                    elapsedTimeMs = stageRemainingMs,
                                    showCentiseconds = true,
                                    baseStyle = TextStyle(color = accentColor, fontSize = 20.sp),
                                    scaleFactor = fontSizeScale,
                                    accentColor = accentColor,
                                    gradientGoldEnabled = false
                                )
                                Text(
                                    text = "ROUND $currentRound/${activeTemplate.repetitions}",
                                    style = TextStyle(
                                        color = LuxuryColors.WarmGray,
                                        fontSize = (9.sp.value * fontSizeScale).sp,
                                        fontWeight = FontWeight.Light
                                    )
                                )
                            }
                        } else {
                            TimeDisplay(
                                elapsedTimeMs = elapsedTimeMs,
                                showCentiseconds = showCentiseconds,
                                baseStyle = TextStyle(color = accentColor, fontSize = 22.sp),
                                scaleFactor = fontSizeScale,
                                accentColor = accentColor,
                                gradientGoldEnabled = gradientEnabled,
                                isVertical = layoutOrientation == "vertical",
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun Int.dpToPx(): Int {
        val density = applicationContext.resources.displayMetrics.density
        val px = (this * density).toInt()
        return if (px < 0) 0 else px
    }

    private fun Float.dpToPx(): Int {
        val density = applicationContext.resources.displayMetrics.density
        val px = (this * density).roundToInt()
        return if (px < 0) 0 else px
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Stopwatch Overlay Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("لحَظات يعمل")
            .setContentText("انقر لفتح لحَظات وإدارة الأدوات العائمة النشطة.")
            .setSmallIcon(R.drawable.ic_stat_stopwatch)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        activeServices.remove(this)
        serviceScope.cancel()

        // Secure unmount and clean up all spawned widget overlay life-cycles
        activeOverlays.keys.toList().forEach { dismissWidget(it) }
    }
}

@Composable
fun LuxuryTextDropdownMenu(
    widgetType: String,
    fontSizeScale: Float,
    isVolumeActive: Boolean,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scaleFontSize = (10.sp.value * fontSizeScale).coerceAtLeast(9.0f).sp

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xF70B0B0B)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.Start
        ) {
            val options = mutableListOf<Pair<String, String>>()
            if (widgetType == "counter") {
                options.add("INCREMENT" to "Increment")
                options.add("DECREMENT" to "Decrement")
                options.add((if (isVolumeActive) "DISABLE VOLUME" else "ENABLE VOLUME") to "ToggleVolume")
            } else {
                options.add("START" to "Start")
                options.add("PAUSE" to "Stop")
                options.add("RESET" to "Reset")
            }
            options.add("SETTING" to "OpenApp")
            options.add("CLOSE" to "Close")

            options.forEach { (label, action) ->
                Text(
                    text = label,
                    style = TextStyle(
                        color = when (label) {
                            "START", "INCREMENT" -> Color(0xFF4AC98F)
                            "PAUSE" -> Color(0xFFF5A623)
                            "CLOSE" -> Color(0xFFC94A4A)
                            else -> LuxuryColors.CreamyWhite
                        },
                        fontSize = scaleFontSize,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (action == "Hide") {
                                onDismiss()
                            } else {
                                onAction(action)
                                onDismiss()
                            }
                        }
                        .padding(vertical = 8.dp, horizontal = 12.dp)
                )
            }
        }
    }
}
