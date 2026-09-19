package com.par9uet.jm.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

object NavigationMotion {
    const val HierarchicalSpringStiffness = 700f
    const val HierarchicalSpringDampingRatio = 1f
    const val BackgroundParallaxFraction = 0.35f
    const val MainTabAnimationDurationMillis = 320

    val HierarchicalSpring: FiniteAnimationSpec<IntOffset> = spring(
        stiffness = HierarchicalSpringStiffness,
        dampingRatio = HierarchicalSpringDampingRatio,
    )

    val MainTabAnimationSpec: FiniteAnimationSpec<Float> = tween(
        durationMillis = MainTabAnimationDurationMillis,
        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    )

    fun hierarchicalEnter(): EnterTransition = slideInHorizontally(
        animationSpec = HierarchicalSpring,
        initialOffsetX = { fullWidth -> fullWidth },
    )

    fun hierarchicalExit(): ExitTransition = slideOutHorizontally(
        animationSpec = HierarchicalSpring,
        targetOffsetX = { fullWidth ->
            -(fullWidth * BackgroundParallaxFraction).roundToInt()
        },
    )

    fun hierarchicalPopEnter(): EnterTransition = slideInHorizontally(
        animationSpec = HierarchicalSpring,
        initialOffsetX = { fullWidth ->
            -(fullWidth * BackgroundParallaxFraction).roundToInt()
        },
    )

    fun hierarchicalPopExit(): ExitTransition = slideOutHorizontally(
        animationSpec = HierarchicalSpring,
        targetOffsetX = { fullWidth -> fullWidth },
    )

    /**
     * 侧滑预测性返回的进/出动画。必须与 [hierarchicalPopEnter]/[hierarchicalPopExit] 一致：
     * navigation-compose 2.8+ 在 predictive back 进行中会改走 predictivePop*，默认实现是
     * scaleOut（屏幕中部缩小淡出），与本项目的水平 Spring 不是一套语言。
     * swipeEdge 目前不参与偏移：nagram 式 pop 固定向右退出。
     */
    fun predictivePopEnter(@Suppress("UNUSED_PARAMETER") swipeEdge: Int): EnterTransition =
        hierarchicalPopEnter()

    fun predictivePopExit(@Suppress("UNUSED_PARAMETER") swipeEdge: Int): ExitTransition =
        hierarchicalPopExit()
}
