package com.floating.stopwatch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.floating.stopwatch.data.SettingsRepository
import com.floating.stopwatch.domain.HapticController
import com.floating.stopwatch.domain.StopwatchEngine
import com.floating.stopwatch.service.StopwatchService
import com.floating.stopwatch.ui.AppMode
import com.floating.stopwatch.ui.MainViewModel
import com.floating.stopwatch.ui.screens.MainScreen
import com.floating.stopwatch.ui.screens.SettingsScreen
import com.floating.stopwatch.ui.theme.LuxuryColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var mainViewModel: MainViewModel
    private lateinit var hapticController: HapticController
    private var pendingSettingsOpen by mutableStateOf(false)

    // Foldable postures window tracker
    private var foldingFeatureState = mutableStateOf<FoldingFeature?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        settingsRepository = SettingsRepository(applicationContext)
        mainViewModel = MainViewModel(
            engine = StopwatchService.getEngine(),
            countdownEngine = StopwatchService.getCountdownEngine(),
            intervalEngine = StopwatchService.getIntervalEngine(),
            legacyEngine = StopwatchService.getLegacyEngine()
        )
        hapticController = HapticController(applicationContext)

        // Observe DataStore for persisted Interval configuration and update shared IntervalEngine
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsRepository.intervalName,
                settingsRepository.intervalWorkMs,
                settingsRepository.intervalRestMs,
                settingsRepository.intervalRounds
            ) { name, workMs, restMs, rounds ->
                com.floating.stopwatch.domain.IntervalTemplate(
                    id = "persisted_interval",
                    name = name,
                    workDurationMs = workMs,
                    restDurationMs = restMs,
                    repetitions = rounds
                )
            }.collectLatest { template ->
                StopwatchService.getIntervalEngine().loadTemplate(template)
            }
        }

        // Load and sync Legacy data from DataStore
        lifecycleScope.launch {
            settingsRepository.savedLegaciesJson.collectLatest { json ->
                if (json.isNotBlank()) {
                    StopwatchService.getLegacyEngine().loadLegaciesFromJson(json)
                }
            }
        }

        StopwatchService.getLegacyEngine().onLegaciesUpdated = { list ->
            lifecycleScope.launch {
                val json = StopwatchService.getLegacyEngine().serializeLegaciesToJson()
                settingsRepository.setSavedLegaciesJson(json)
            }
        }

        // Track fold/hinge updates with explicit safe fallbacks in case Jetpack WindowManager throws on traditional non-foldable devices
        lifecycleScope.launch {
            try {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collectLatest { layoutInfo ->
                        val folding = layoutInfo.displayFeatures
                            .filterIsInstance<FoldingFeature>()
                            .firstOrNull()
                        foldingFeatureState.value = folding
                    }
            } catch (e: Exception) {
                // Fallback gracefully: ignore exception and set folding to null (traditional posture layout)
                foldingFeatureState.value = null
            }
        }

        setContent {
            var currentScreen by remember {
                mutableStateOf(
                    if (intent?.getBooleanExtra("OPEN_SETTINGS", false) == true || pendingSettingsOpen) {
                        "Settings"
                    } else {
                        "Main"
                    }
                )
            }
            var isUnlockedByBiometrics by remember { mutableStateOf(false) }

            LaunchedEffect(pendingSettingsOpen) {
                if (pendingSettingsOpen) {
                    currentScreen = "Settings"
                    pendingSettingsOpen = false
                }
            }

            val colorPreset by settingsRepository.colorPreset.collectAsState(initial = "Gold")
            val customColorHex by settingsRepository.customColorHex.collectAsState(initial = "#C9A66B")
            val hapticIntensity by settingsRepository.hapticIntensity.collectAsState(initial = "Medium")
            val themeMode by settingsRepository.themeMode.collectAsState(initial = "Midnight")
            val mainDisplayScale by settingsRepository.mainDisplayScale.collectAsState(initial = 1.0f)

            val accentColor = if (colorPreset == "Custom") {
                try { Color(android.graphics.Color.parseColor(customColorHex)) } catch (e: Exception) { LuxuryColors.AccentGold }
            } else {
                LuxuryColors.fromName(colorPreset)
            }

            // Standard permission verification state flow for Alert Overlay
            var hasOverlayPermission by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Settings.canDrawOverlays(this@MainActivity)
                    } else {
                        true
                    }
                )
            }

            // Monitor state update correctly on resumed/activity context focus change
            DisposableEffect(Unit) {
                onResumeCallback = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        hasOverlayPermission = Settings.canDrawOverlays(this@MainActivity)
                    }
                }
                onDispose {
                    onResumeCallback = null
                }
            }

            LaunchedEffect(hasOverlayPermission) {
                if (hasOverlayPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this@MainActivity)) {
                    lifecycleScope.launch {
                        if (settingsRepository.hasAnyWidgetActive.first()) {
                            startFloatingService()
                        }
                    }
                }
            }

            if (!hasOverlayPermission) {
                OverlayPermissionExplanationScreen(
                    onGrantClick = {
                        requestOverlayPermission()
                    },
                    onSkipClick = {
                        hasOverlayPermission = true
                    }
                )
            } else {
                MainScreen(
                    viewModel = mainViewModel,
                    settingsRepository = settingsRepository,
                    hapticController = hapticController,
                    hapticIntensity = hapticIntensity,
                    showCentiseconds = true,
                    mainSize = mainDisplayScale,
                    accentColor = accentColor,
                    themeMode = themeMode,
                    onNavigateToSettings = { currentScreen = "Settings" }
                )

                if (currentScreen == "Settings") {
                    SettingsScreen(
                        settingsRepository = settingsRepository,
                        onBack = { currentScreen = "Main" }
                    )
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, 1024)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1024) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    lifecycleScope.launch {
                        if (settingsRepository.hasAnyWidgetActive.first()) {
                            startFloatingService()
                        }
                    }
                }
            }
        }
    }

    private fun triggerBiometricAuthentication(activity: androidx.fragment.app.FragmentActivity, onAuthenticated: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onAuthenticated()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Premium Biometric Lock")
            .setSubtitle("Authenticate to view premium stopwatch insights")
            .setNegativeButtonText("Cancel")
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            // Safe fallback if biometric hardware is not set up / ready
            onAuthenticated()
        }
    }

    private fun startFloatingService() {
        // Double check canDrawOverlays before launching intent defensively
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }
        val intent = Intent(this, StopwatchService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // Live update when activity is resumed
    private var onResumeCallback: (() -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("OPEN_SETTINGS", false)) {
            pendingSettingsOpen = true
        }
    }

    override fun onResume() {
        super.onResume()
        onResumeCallback?.invoke()
        // verify permission status live
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                lifecycleScope.launch {
                    if (settingsRepository.hasAnyWidgetActive.first()) {
                        startFloatingService()
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (mainViewModel.currentMode.value == AppMode.Counter) {
            if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
                hapticController.trigger(
                    lifecycleScope.run { "Medium" },
                    "Lap"
                )
                mainViewModel.incrementCounter()
                return true
            } else if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                hapticController.trigger(
                    lifecycleScope.run { "Medium" },
                    "Reset"
                )
                mainViewModel.decrementCounter()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun OverlayPermissionExplanationScreen(
    onGrantClick: () -> Unit,
    onSkipClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LuxuryColors.WarmBlack)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "FLOATING OVERLAY",
                style = TextStyle(
                    color = LuxuryColors.CreamyWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraLight,
                    letterSpacing = 4.sp
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "لحَظات يتيح للتأقيت والأدوات العائمة الظهور مباشرة فوق التطبيقات المفتوحة للمتابعة الحية. يتطلب ذلك إذن 'الظهور فوق التطبيقات الأخرى'.",
                style = TextStyle(
                    color = LuxuryColors.WarmGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 20.sp
                ),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = LuxuryColors.AccentGold),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    text = "GRANT PERMISSION",
                    color = LuxuryColors.WarmBlack,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "SKIP AND OPEN IN-APP TIMER",
                style = TextStyle(
                    color = LuxuryColors.WarmGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 1.sp
                ),
                modifier = Modifier
                    .clickable { onSkipClick() }
                    .padding(8.dp)
            )
        }
    }
}
