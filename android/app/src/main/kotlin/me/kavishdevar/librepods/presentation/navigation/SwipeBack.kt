package me.kavishdevar.librepods.presentation.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private enum class Direction {
    Forward,
    Back,
    None
}

// Critically damped, so it never overshoots, and it carries the fling velocity over.
private val SwipeSpec = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 0.5f,
)

class SwipeBackSceneStrategy<T : Any>(
    private val enabled: Boolean,
    private val backRequests: Channel<CompletableDeferred<Unit>>,
    private val onDismiss: () -> Unit
) : SceneStrategy<T> {

    private var lastKeys: List<Any> = emptyList()
    private var lastDirection = Direction.None

    override fun SceneStrategyScope<T>.calculateScene(
        entries: List<NavEntry<T>>
    ): Scene<T>? {
        if (entries.isEmpty()) return null

        val currentEntry = entries.last()
        val previousEntry = entries.getOrNull(entries.lastIndex - 1)

        // The scene can be recalculated for the same back stack (any recomposition of
        // NavDisplay). Only a real back stack change may change the direction, otherwise a
        // running push animation is restarted as a "back" one and the page gets stuck midway.
        val keys = entries.map { it.contentKey }
        if (keys != lastKeys) {
            lastDirection = when {
                lastKeys.isEmpty() -> Direction.None
                currentEntry.contentKey in lastKeys -> Direction.Back
                else -> Direction.Forward
            }
            lastKeys = keys
        }
        val direction = lastDirection

        return object : Scene<T> {
            override val key: Any
                get() = "${currentEntry.contentKey}_${currentEntry.hashCode()}"

            override val entries: List<NavEntry<T>>
                get() = entries

            override val previousEntries: List<NavEntry<T>>
                get() = listOfNotNull(previousEntry)

            override val content: @Composable () -> Unit
                get() = {
                    SwipeBackSceneContent(
                        previousEntry = previousEntry,
                        currentEntry = currentEntry,
                        direction = direction,
                        swipeEnabled = enabled,
                        backRequests = backRequests,
                        onDismiss = onDismiss
                    )
                }
        }
    }
}

@Composable
private fun <T : Any> SwipeBackSceneContent(
    previousEntry: NavEntry<T>?,
    currentEntry: NavEntry<T>,
    direction: Direction,
    swipeEnabled: Boolean,
    backRequests: Channel<CompletableDeferred<Unit>>,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) {
        LocalWindowInfo.current.containerDpSize.width.toPx()
    }

    val sceneKey = "${currentEntry.contentKey}_${currentEntry.hashCode()}"

    // Horizontal offset of the current entry, 0 = fully shown, screenWidthPx = fully hidden.
    var offset by remember(sceneKey) {
        mutableFloatStateOf(if (direction == Direction.Forward) screenWidthPx else 0f)
    }
    // false while the page is being pushed, true once it can only go away (swipe/back).
    var closing by remember(sceneKey) { mutableStateOf(direction != Direction.Forward) }

    // Positive while pushing, negative while going back, 0 when idle. Read lazily so that
    // the screens are not recomposed on every animation frame.
    val transitionProgress = remember(sceneKey, screenWidthPx) {
        {
            val progress = (offset / screenWidthPx).coerceIn(0f, 1f)
            if (closing) -progress else progress
        }
    }

    // Only one animation or gesture drives the offset at a time; a new one cancels the old.
    val mutex = remember(sceneKey) { MutatorMutex() }
    val scope = rememberCoroutineScope()

    suspend fun animateOffsetTo(target: Float, velocity: Float = 0f) {
        mutex.mutate {
            animate(offset, target, velocity, SwipeSpec) { value, _ -> offset = value }
        }
    }

    suspend fun dismiss(velocity: Float = 0f) {
        closing = true
        animateOffsetTo(screenWidthPx, velocity)
        onDismiss()
    }

    LaunchedEffect(sceneKey) {
        if (direction == Direction.Forward) {
            animateOffsetTo(0f)
        }
        closing = true
    }

    LaunchedEffect(sceneKey) {
        for (completed in backRequests) {
            try {
                dismiss()
            } catch (e: CancellationException) {
                // interrupted by a swipe, keep listening unless the scene itself is gone
                ensureActive()
            } finally {
                completed.complete(Unit)
            }
        }
    }

    PredictiveBackHandler(enabled = previousEntry != null) { progressFlow ->
        closing = true
        try {
            mutex.mutate {
                progressFlow.collect { backEvent ->
                    offset = backEvent.progress * screenWidthPx
                }
            }
            dismiss()
        } catch (_: CancellationException) {
            scope.launch { animateOffsetTo(0f) }
        }
    }

    val draggableState = rememberDraggableState { delta ->
        offset = (offset + delta).coerceIn(0f, screenWidthPx)
    }

    CompositionLocalProvider(
        LocalTransitionProgress provides transitionProgress
    ) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalIsCurrentEntry provides false
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress = (offset / screenWidthPx).coerceIn(0f, 1f)
                            translationX = -(screenWidthPx / 3f) * (1f - progress)
                        }
                ) {
                    previousEntry?.Content()
                }
            }

            CompositionLocalProvider(
                LocalIsCurrentEntry provides true
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = offset
                        }
                        .draggable(
                            enabled = swipeEnabled && previousEntry != null,
                            state = draggableState,
                            orientation = Orientation.Horizontal,
                            onDragStarted = {
                                closing = true
                                // stop whatever animation is running and take over
                                mutex.mutate { }
                            },
                            onDragStopped = { velocity ->
                                val shouldDismiss = velocity > 1000f ||
                                    (offset > screenWidthPx * 0.35f && velocity > -300f)

                                scope.launch {
                                    if (shouldDismiss) {
                                        dismiss(velocity)
                                    } else {
                                        animateOffsetTo(0f, velocity)
                                    }
                                }
                            }
                        )
                ) {
                    currentEntry.Content()
                }
            }
        }
    }
}
