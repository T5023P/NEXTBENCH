package com.nextbench.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object NbEasing {
    /** iOS-style decelerate curve — fast start, smooth stop. */
    val EaseOutQuart = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    /** Slightly gentler decelerate for content reveals. */
    val EaseOutCubic = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
}

object NbDuration {
    const val Entry       = 420   // snappier content reveal (was 800)
    const val Interaction = 260   // crisper tap response (was 300)
    const val NavTransition = 340
    const val Stagger     = 60    // tighter list stagger (was 150)
    const val ThemeToggle = 350
}

object NbMotion {
    /** Content entering the screen — snappy decelerate. */
    fun <T> entryTween() = tween<T>(NbDuration.Entry, easing = NbEasing.EaseOutQuart)

    /** Interactive elements (color shifts, size changes). */
    fun <T> interactionTween() = tween<T>(NbDuration.Interaction, easing = NbEasing.EaseOutQuart)

    /** Navigation screen transitions. */
    fun <T> navTween() = tween<T>(NbDuration.NavTransition, easing = NbEasing.EaseOutCubic)

    /** Hard press feedback — no bounce, instant feel. */
    fun <T> pressSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness    = Spring.StiffnessHigh,
    )

    /** Tab / selector spring — subtle overshoot so it feels alive. */
    fun <T> bounceSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness    = Spring.StiffnessMediumLow,
    )

    /** Floating element spring (FAB, indicator pill). */
    fun <T> floatSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness    = Spring.StiffnessMedium,
    )
}
