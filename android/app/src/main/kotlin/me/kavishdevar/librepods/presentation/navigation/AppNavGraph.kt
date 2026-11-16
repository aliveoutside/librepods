package me.kavishdevar.librepods.presentation.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesign
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device

@Composable
fun AppNavGraph(
    backStack: NavBackStack<Screen>,
    devicesState: State<Map<MacAddress, Device<*, *, *>>>,
    showReleaseNotes: Boolean = false,
    updatesShown: () -> Unit = {},
    onboardingComplete: () -> Unit = {},
) {
    val backRequests = remember { Channel<CompletableDeferred<Unit>>(Channel.BUFFERED) }

    val devices by devicesState
    val context = LocalContext.current

    val appDataRepository by lazy { (context.applicationContext as LibrePodsApplication).appDataRepository }
    val recordingRepository by lazy { (context.applicationContext as LibrePodsApplication).recordingRepository }
    val heartRateRepository by lazy { (context.applicationContext as LibrePodsApplication).heartRateRepository }

    val currentDevice: Device<*, *, *>? = devices[backStack.lastOrNull()?.let { (it as? DeviceScreen)?.macAddress }]

    LaunchedEffect(currentDevice) {
        val device = currentDevice as? AppleDevice ?: return@LaunchedEffect

        var wasConnected = false

        device.connectionState.collect { state ->
            when (state) {
                ConnectionState.CONNECTED -> {
                    wasConnected = true
                }

                ConnectionState.DISCONNECTED -> {
                    if (!wasConnected) return@collect

                    while (
                        backStack.size > 1 &&
                        backStack.lastOrNull() is DeviceScreen
                    ) {
                        val completed = CompletableDeferred<Unit>()
                        backRequests.send(completed)
                        completed.await()
                    }
                }

                else -> Unit
            }
        }
    }

    val appSettings by appDataRepository.settings.collectAsState()

    // Apple design: iOS-like stacked pages with swipe-to-go-back.
    // Material design: regular Material navigation motion and the system predictive back.
    val useSwipeBack = LocalDesign.current.system == DesignSystem.Apple

    val sceneStrategies = remember(useSwipeBack, appSettings.swipeAnywhereForBack) {
        if (useSwipeBack) {
            listOf(
                SwipeBackSceneStrategy<Screen>(
                    enabled = appSettings.swipeAnywhereForBack,
                    backRequests = backRequests,
                    onDismiss = {
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                        }
                    }
                )
            )
        } else {
            emptyList()
        }
    }

    if (!useSwipeBack) {
        // programmatic back (e.g. device disconnected), animated by NavDisplay itself
        LaunchedEffect(Unit) {
            for (completed in backRequests) {
                if (backStack.size > 1) {
                    backStack.removeAt(backStack.lastIndex)
                }
                completed.complete(Unit)
            }
        }
    }

    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fastEffectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this,
        ) {
            NavDisplay(
                sharedTransitionScope = this,
                backStack = backStack,
                sceneStrategies = sceneStrategies,
                onBack = {
                    if (backStack.size > 1) {
                        backStack.removeAt(backStack.lastIndex)
                    }
                },
                entryProvider = { screen ->
                    NavEntry(screen) {
                        RenderScreenContent(
                            screen = screen,
                            backStack = backStack,
                            backRequests = backRequests,
                            devices = devices,
                            appDataRepository = appDataRepository,
                            recordingRepository = recordingRepository,
                            heartRateRepository = heartRateRepository,
                            showReleaseNotes = showReleaseNotes,
                            updatesShown = updatesShown,
                            onboardingComplete = onboardingComplete
                        )
                    }
                },
                transitionSpec = {
                    if (useSwipeBack) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        // Material shared axis X
                        (slideInHorizontally(spatialSpec) { it / 5 } + fadeIn(effectsSpec)) togetherWith
                            (slideOutHorizontally(spatialSpec) { -it / 5 } + fadeOut(fastEffectsSpec))
                    }
                },
                popTransitionSpec = {
                    if (useSwipeBack) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (slideInHorizontally(spatialSpec) { -it / 5 } + fadeIn(effectsSpec)) togetherWith
                            (slideOutHorizontally(spatialSpec) { it / 5 } + fadeOut(fastEffectsSpec))
                    }
                },
                predictivePopTransitionSpec = {
                    if (useSwipeBack) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        // Material predictive back: the page shrinks and fades into the previous one
                        fadeIn(tween(250)) togetherWith
                            (scaleOut(tween(250), targetScale = 0.9f) + fadeOut(tween(250)))
                    }
                }
            )
        }
    }
}
