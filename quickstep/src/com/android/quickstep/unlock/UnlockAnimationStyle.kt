/*
 * Copyright (C) 2026 The RuriseOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.quickstep.unlock

import android.view.View
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.anim.PendingAnimation

/**
 * A visual style for Launcher's home screen entrance, e.g. when returning from the keyguard.
 *
 * Implementations only describe what the views look like over the animation's progress; the
 * caller owns the timeline (duration, playback, cancellation). The animation built into [anim]
 * must leave every target in its resting state at progress 1, since that is what is applied when
 * the animation is interrupted.
 */
fun interface UnlockAnimationStyle {

    fun buildAnimation(anim: PendingAnimation, targets: UnlockTargets, timing: UnlockTiming)
}

/** The set of Launcher views an [UnlockAnimationStyle] may animate. */
class UnlockTargets(
    /** Common ancestor used to map coordinates between targets. */
    val root: View,
    /** Workspace pages currently on screen, in page order. */
    val visiblePages: List<CellLayout>,
    val hotseat: View?,
) {
    companion object {
        @JvmStatic
        fun from(launcher: Launcher): UnlockTargets {
            val workspace = launcher.workspace
            val pages = ArrayList<CellLayout>()
            workspace.visiblePageIndices.forEach { index ->
                (workspace.getChildAt(index) as? CellLayout)?.let(pages::add)
            }
            return UnlockTargets(launcher.rootView, pages, launcher.hotseat)
        }
    }
}

/**
 * Describes where the visible part of an entrance animation starts within the full timeline.
 *
 * The timeline always runs over progress [0, 1]; styles should hold their start state until
 * [startFraction] so that a leading delay can be expressed without a separate animator.
 */
data class UnlockTiming(val startFraction: Float) {

    init {
        require(startFraction in 0f..1f) { "startFraction must be in [0, 1]: $startFraction" }
    }

    companion object {
        /** Start fraction used when Launcher is revealed after leaving a dream. */
        const val DREAM_START_FRACTION = 0.37111112f

        @JvmField val DREAM = UnlockTiming(DREAM_START_FRACTION)

        /** Timing for an animation of [duration] that should only start after [startDelay]. */
        @JvmStatic
        fun forDelay(duration: Long, startDelay: Long): UnlockTiming {
            val total = duration + startDelay
            if (total <= 0L) return UnlockTiming(0f)
            return UnlockTiming((startDelay.coerceAtLeast(0L).toFloat() / total).coerceIn(0f, 1f))
        }
    }
}
