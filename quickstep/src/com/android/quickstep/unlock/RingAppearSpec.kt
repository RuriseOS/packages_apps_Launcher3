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

/**
 * Tunables for [RingAppearStyle]. All progress values are fractions of the visible part of the
 * timeline, i.e. after [UnlockTiming.startFraction].
 */
data class RingAppearSpec(
    /** Scale each item starts from before growing to its resting size. */
    val startScale: Float = 0.65f,
    /** Portion of the distance to the grid center each item is offset by at the start. */
    val pullToCenter: Float = 0.35f,
    /** Delay between two consecutive rings, as a fraction of the visible timeline. */
    val ringDelay: Float = 0.08f,
    /** Portion of an item's remaining timeline over which it fades in. */
    val alphaProgress: Float = 0.25f,
    val alphaInterpolator: UnlockInterpolatorSpec = UnlockInterpolatorSpec.LINEAR,
    /** Interpolator shared by scale and translation. */
    val motionInterpolator: UnlockInterpolatorSpec = UnlockInterpolatorSpec.EMPHASIZED_DECELERATE,
    /** Point each item scales around, see [ScalePivot]. */
    val scalePivot: ScalePivot = ScalePivot.SELF,
) {

    enum class ScalePivot {
        /** Each item scales around its own pivot (its center by default). */
        SELF,
        /**
         * Every item scales around the grid center. Scaling then already moves items towards the
         * center by `1 - startScale` of their distance, on top of [pullToCenter], so this is meant
         * to be combined with a [pullToCenter] close to 0.
         */
        SHARED_CENTER,
    }

    init {
        require(startScale >= 0f) { "startScale must not be negative: $startScale" }
        require(ringDelay >= 0f) { "ringDelay must not be negative: $ringDelay" }
        require(alphaProgress in 0f..1f) { "alphaProgress must be in [0, 1]: $alphaProgress" }
    }

    companion object {
        @JvmField val DEFAULT = RingAppearSpec()
    }
}
