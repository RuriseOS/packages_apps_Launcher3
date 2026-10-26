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

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import com.android.app.animation.Interpolators
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val PREFIX_MATERIAL = "material"
private const val PREFIX_BEZIER = "bezier"
private const val PREFIX_SPRING = "spring"

/**
 * Serializable description of an interpolation curve used by the unlock animation styles.
 *
 * Every spec can be round-tripped through [encode] / [decode] so that it can later be persisted
 * as a plain string (e.g. a Trebuchet preference) without touching the animation code.
 */
sealed interface UnlockInterpolatorSpec {

    /** Creates a new [Interpolator] instance for this spec. */
    fun create(): Interpolator

    /** Returns a compact string form of this spec, see [decode]. */
    fun encode(): String

    /** One of the stock Material curves from [Interpolators]. */
    data class Material(val curve: Curve) : UnlockInterpolatorSpec {

        enum class Curve(val interpolator: Interpolator) {
            LINEAR(Interpolators.LINEAR),
            STANDARD(Interpolators.STANDARD),
            STANDARD_DECELERATE(Interpolators.STANDARD_DECELERATE),
            STANDARD_ACCELERATE(Interpolators.STANDARD_ACCELERATE),
            EMPHASIZED(Interpolators.EMPHASIZED),
            EMPHASIZED_DECELERATE(Interpolators.EMPHASIZED_DECELERATE),
            EMPHASIZED_ACCELERATE(Interpolators.EMPHASIZED_ACCELERATE),
        }

        override fun create(): Interpolator = curve.interpolator

        override fun encode(): String = "$PREFIX_MATERIAL:${curve.name}"
    }

    /** A CSS-style `cubic-bezier(x1, y1, x2, y2)` curve. */
    data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) :
        UnlockInterpolatorSpec {

        init {
            require(x1 in 0f..1f && x2 in 0f..1f) { "x control points must be in [0, 1]: $this" }
        }

        override fun create(): Interpolator = PathInterpolator(x1, y1, x2, y2)

        override fun encode(): String = "$PREFIX_BEZIER:$x1,$y1,$x2,$y2"
    }

    /**
     * A damped spring released from rest at 0 towards 1, using the same parameters as
     * [androidx.dynamicanimation.animation.SpringForce]. The spring's settle time is mapped onto
     * the animation segment it is used for, so the overall duration stays controlled by the
     * caller.
     */
    data class Spring(val stiffness: Float, val dampingRatio: Float) : UnlockInterpolatorSpec {

        init {
            require(stiffness > 0f) { "stiffness must be positive: $this" }
            require(dampingRatio > 0f) { "dampingRatio must be positive: $this" }
        }

        override fun create(): Interpolator = SpringInterpolator(stiffness, dampingRatio)

        override fun encode(): String = "$PREFIX_SPRING:$stiffness,$dampingRatio"
    }

    companion object {
        val LINEAR: UnlockInterpolatorSpec = Material(Material.Curve.LINEAR)

        val EMPHASIZED_DECELERATE: UnlockInterpolatorSpec =
            Material(Material.Curve.EMPHASIZED_DECELERATE)

        /**
         * Parses a string produced by [encode]. Returns [fallback] when the value is null or
         * malformed so that a bad stored preference never breaks the animation.
         */
        @JvmStatic
        fun decode(value: String?, fallback: UnlockInterpolatorSpec): UnlockInterpolatorSpec {
            if (value.isNullOrEmpty()) return fallback
            return try {
                val type = value.substringBefore(':')
                val args = value.substringAfter(':', "")
                when (type) {
                    PREFIX_MATERIAL -> Material(Material.Curve.valueOf(args))
                    PREFIX_BEZIER -> {
                        val p = args.split(',').map { it.trim().toFloat() }
                        CubicBezier(p[0], p[1], p[2], p[3])
                    }
                    PREFIX_SPRING -> {
                        val p = args.split(',').map { it.trim().toFloat() }
                        Spring(p[0], p[1])
                    }
                    else -> fallback
                }
            } catch (e: RuntimeException) {
                fallback
            }
        }
    }
}

/**
 * Closed-form solution of a unit-mass damped harmonic oscillator, normalized so that input 1
 * corresponds to the moment the spring settles within [SETTLE_THRESHOLD] of its target.
 */
private class SpringInterpolator(stiffness: Float, dampingRatio: Float) : Interpolator {

    private val omega = sqrt(stiffness.toDouble())
    private val zeta = dampingRatio.toDouble()
    private val settleTime: Double = computeSettleTime()

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        return (1.0 - displacement(input * settleTime)).toFloat()
    }

    /** Distance from the target at time [t], starting at 1 with zero velocity. */
    private fun displacement(t: Double): Double =
        when {
            zeta < 1.0 -> {
                val omegaD = omega * sqrt(1.0 - zeta * zeta)
                exp(-zeta * omega * t) *
                    (cos(omegaD * t) + (zeta * omega / omegaD) * sin(omegaD * t))
            }
            abs(zeta - 1.0) < 1e-4 -> exp(-omega * t) * (1.0 + omega * t)
            else -> {
                val root = sqrt(zeta * zeta - 1.0)
                val r1 = -omega * (zeta - root)
                val r2 = -omega * (zeta + root)
                (r2 * exp(r1 * t) - r1 * exp(r2 * t)) / (r2 - r1)
            }
        }

    private fun computeSettleTime(): Double {
        // Decay rate of the slowest mode bounds the envelope of the response.
        val decay =
            if (zeta <= 1.0) zeta * omega else omega * (zeta - sqrt(zeta * zeta - 1.0))
        val estimate = ln(1.0 / SETTLE_THRESHOLD) / decay
        // Critically damped / overdamped springs carry a polynomial term, give them some slack.
        return max(estimate * if (zeta >= 1.0) 1.5 else 1.0, 1e-3)
    }

    private companion object {
        const val SETTLE_THRESHOLD = 0.002
    }
}
