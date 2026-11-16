package me.kavishdevar.librepods.presentation.navigation

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf

val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * Progress of the running navigation transition: positive while a page is pushed, negative while
 * it goes back, 0 when idle. A lambda, so readers only re-run in the phase they read it in.
 */
val LocalTransitionProgress = compositionLocalOf<() -> Float> { { 0f } }
val LocalIsCurrentEntry = compositionLocalOf { false }
