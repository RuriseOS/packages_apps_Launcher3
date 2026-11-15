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

package com.android.quickstep.postboot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import com.android.app.animation.Interpolators.ACCELERATE
import com.android.app.animation.Interpolators.ACCELERATE_DECELERATE
import com.android.app.animation.Interpolators.DECELERATE_QUINT
import com.android.quickstep.unlock.UnlockStylePreviewView
import com.android.settingslib.widget.theme.R as ThemeR
import com.android.systemui.surfaceeffects.core.turbulencenoise.TurbulenceNoiseShader
import com.google.android.material.shape.MaterialShapes
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The loading screen of a [PostBootLoaderStyles] style, played by [UnlockStylePreviewView] between
 * the lock screen and the home screen. The few seconds of a real load are shortened to fit a loop.
 */
class PostBootLoaderInterlude(context: Context, styleId: String) :
    UnlockStylePreviewView.Interlude {

    private val glow =
        if (styleId == PostBootLoaderStyles.ID_GLOW) GlowPreviewView(context) else null

    override val view: View?
        get() = glow

    // The home screen comes in once the glow starts to leave, as PostBootReveal does
    override val durationMs: Long =
        if (glow != null) GLOW_HOLD_MS + REVEAL_DELAY_MS else WALLPAPER_HOLD_MS

    override fun showTime(time: Float) {
        glow?.showTime(time)
    }

    override fun reset() {
        glow?.showTime(-1f)
    }

    /**
     * A small [GlowLoaderView]: the same noise cloud and indicator shapes, driven by the time of
     * the preview so that pausing it freezes them. Without a real wallpaper there is nothing to
     * blur, and the cloud is a stronger, plain translucent color to stay visible at this size and
     * on the light screen of a light theme.
     */
    private class GlowPreviewView(context: Context) : View(context) {

        private val accentColor = context.getColor(ThemeR.color.settingslib_materialColorPrimary)
        private val noiseShader =
            TurbulenceNoiseShader(NOISE_TYPE).apply {
                setGridCount(GlowLoaderView.NOISE_GRID_COUNT)
                setPixelDensity(context.resources.displayMetrics.density)
                // Opaque, so that the cloud is laid over the screen as a plain translucent color.
                // The real loader passes a translucent color, which the shader multiplies by its
                // alpha twice: that adds light, which a light preview screen can't show.
                setColor(accentColor)
                setScreenColor(Color.TRANSPARENT)
                // Defaults to 0 and is otherwise set by LoadingEffect; fading uses the paint alpha
                setOpacity(1f)
            }
        private val noisePaint = Paint().apply { shader = noiseShader }
        private val indicatorPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.FILL
            }
        private val indicatorPath = Path()
        private val indicatorMatrix = Matrix()

        /** Milliseconds since the lock screen started to leave, negative before. */
        private var time = -1f

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun showTime(time: Float) {
            if (this.time == time) return
            this.time = time
            // Nothing to draw before the start and once gone
            visibility = if (time < 0 || time >= GLOW_HOLD_MS + EXIT_MS) INVISIBLE else VISIBLE
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            noiseShader.setSize(w.toFloat(), h.toFloat())
        }

        override fun onDraw(canvas: Canvas) {
            if (time < 0) return
            val exit = progress(time - GLOW_HOLD_MS, EXIT_MS)
            val cx = width / 2f
            val cy = height / 2f

            // The noise is a runtime shader, which needs hardware acceleration
            if (canvas.isHardwareAccelerated) {
                val fadeIn = progress(time, NOISE_FADE_IN_MS)
                val alpha = fadeIn * (1 - ACCELERATE_DECELERATE.getInterpolation(exit))
                if (alpha > 0) {
                    noiseShader.setNoiseMove(
                        NOISE_OFFSET_X,
                        NOISE_OFFSET_Y,
                        NOISE_OFFSET_Z + time / 1000f * NOISE_SPEED_Z,
                    )
                    noisePaint.alpha = (alpha * NOISE_STRENGTH * 255).toInt()
                    // Slowly zooms in, then grows a little more while leaving
                    val zoom =
                        1 +
                            ZOOM * DECELERATE_QUINT.getInterpolation(progress(time, GLOW_HOLD_MS)) +
                            EXIT_SCALE_STEP * ACCELERATE_DECELERATE.getInterpolation(exit)
                    canvas.save()
                    canvas.scale(zoom, zoom, cx, cy)
                    canvas.drawPaint(noisePaint)
                    canvas.restore()
                }
            }

            val indicatorExit = progress(time - GLOW_HOLD_MS, INDICATOR_EXIT_MS)
            val indicatorAlpha =
                ACCELERATE.getInterpolation(
                    progress(time - INDICATOR_FADE_IN_DELAY_MS, INDICATOR_FADE_IN_MS)
                ) * (1 - indicatorExit)
            if (indicatorAlpha > 0) {
                val size = width * INDICATOR_SIZE_RATIO * (1 + EXIT_SCALE_STEP * indicatorExit)
                drawIndicator(canvas, cx, cy, size, indicatorAlpha)
            }
        }

        /** Draws the shape of Material's LoadingIndicator at [time]. */
        private fun drawIndicator(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Float) {
            // Each shape morphs into the next with a spring, one shape per period
            val shapeIndex = floor(time / SHAPE_DURATION_MS)
            val morphFactor =
                shapeIndex + spring((time - shapeIndex * SHAPE_DURATION_MS) / 1000f)
            val morphIndex = floor(morphFactor).toInt()
            val morph = MORPHS[morphIndex.mod(MORPHS.size)]
            indicatorPath.rewind()
            morph.toPath(morphFactor - morphIndex, indicatorPath)
            indicatorMatrix.setScale(size / 2, size / 2)
            indicatorPath.transform(indicatorMatrix)

            indicatorPaint.alpha = (alpha * 255).toInt()
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate((ROTATION_PER_SHAPE * morphFactor) % 360f)
            canvas.drawPath(indicatorPath, indicatorPaint)
            canvas.restore()
        }

        private fun progress(time: Float, duration: Long) = (time / duration).coerceIn(0f, 1f)

        /** Position of a spring released from 0 towards 1 at rest, after [seconds]. */
        private fun spring(seconds: Float): Float {
            if (seconds <= 0) return 0f
            val decay = exp(-SPRING_DAMPING_RATIO * SPRING_FREQUENCY * seconds)
            val dampedFrequency =
                SPRING_FREQUENCY * sqrt(1 - SPRING_DAMPING_RATIO * SPRING_DAMPING_RATIO)
            val phase = dampedFrequency * seconds
            return 1 -
                decay *
                    (cos(phase) +
                        SPRING_DAMPING_RATIO * SPRING_FREQUENCY / dampedFrequency * sin(phase))
        }
    }

    companion object {
        // A real load takes from a few seconds to tens of seconds; the preview shows a short one.
        // The timings are those of GlowLoaderView and PostBootReveal, shortened to match.
        private const val GLOW_HOLD_MS = 1800L
        /** Without a loading screen, the wallpaper alone; kept short so that it doesn't drag. */
        private const val WALLPAPER_HOLD_MS = 1300L
        private const val NOISE_FADE_IN_MS = 400L
        private const val INDICATOR_FADE_IN_DELAY_MS = 300f
        private const val INDICATOR_FADE_IN_MS = 300L
        private const val EXIT_MS = 400L
        private const val INDICATOR_EXIT_MS = 166L
        /** The home screen starts 375 ms into the 500 ms exit of the real loader. */
        private const val REVEAL_DELAY_MS = 300L
        private const val ZOOM = 0.3f
        private const val EXIT_SCALE_STEP = 0.2f

        private val NOISE_TYPE = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE_SIMPLE
        /** Opacity of the densest parts of the cloud. */
        private const val NOISE_STRENGTH = 0.35f
        // Fixed, so that every run looks the same
        private const val NOISE_OFFSET_X = 0.37f
        private const val NOISE_OFFSET_Y = 0.61f
        private const val NOISE_OFFSET_Z = 0.13f
        // Same speed as LoadingEffect's default
        private const val NOISE_SPEED_Z = 0.3f

        /** Relative to the screen width; 54dp on a 412dp wide phone. */
        private const val INDICATOR_SIZE_RATIO = 0.13f

        // Material's LoadingIndicator: shapes, timing and spring
        private const val SHAPE_DURATION_MS = 650f
        private const val ROTATION_PER_SHAPE = 140f
        private val SPRING_FREQUENCY = sqrt(200f) // stiffness 200
        private const val SPRING_DAMPING_RATIO = 0.6f
        private val MORPHS: Array<Morph> by lazy {
            val bounds = RectF(-1f, -1f, 1f, 1f)
            val shapes =
                listOf(
                        MaterialShapes.SOFT_BURST,
                        MaterialShapes.COOKIE_9,
                        MaterialShapes.PENTAGON,
                        MaterialShapes.PILL,
                        MaterialShapes.SUNNY,
                        MaterialShapes.COOKIE_4,
                        MaterialShapes.OVAL,
                    )
                    .map { MaterialShapes.normalize(it, true, bounds) }
            Array(shapes.size) { Morph(shapes[it], shapes[(it + 1) % shapes.size]) }
        }
    }
}
