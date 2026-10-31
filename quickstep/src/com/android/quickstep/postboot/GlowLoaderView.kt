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

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.View.VISIBLE
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import com.android.app.animation.Interpolators
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.systemui.surfaceeffects.core.turbulencenoise.TurbulenceNoiseAnimationConfig
import com.android.systemui.surfaceeffects.core.turbulencenoise.TurbulenceNoiseShader
import com.android.systemui.surfaceeffects.view.PaintDrawCallback
import com.android.systemui.surfaceeffects.view.loadingeffect.LoadingEffect
import com.google.android.material.loadingindicator.LoadingIndicator
import kotlin.math.roundToInt

/**
 * Content of the [PostBootLoader] window: a cloud of accent colored noise over the blurred
 * wallpaper that slowly zooms in, with a loading indicator in the middle.
 *
 * It shows an opaque surface color until the window is shown after having been hidden, which is
 * when the keyguard goes away, and then dissolves into the noise while the keyguard fades out.
 * The first time the window is shown doesn't count: that is at boot, before the keyguard.
 */
class GlowLoaderView(context: Context, private val surfaceColor: Int, accentColor: Int) :
    FrameLayout(context), PaintDrawCallback {

    private val noiseColor = ColorUtils.setAlphaComponent(accentColor, NOISE_ALPHA)
    private val indicator =
        LoadingIndicator(
                ContextThemeWrapper(context, R.style.Theme_Material3_DynamicColors_DayNight)
            )
            .apply {
                indicatorSize = Utilities.dpToPx(INDICATOR_SIZE_DP)
                setIndicatorColor(accentColor)
                alpha = 0f
            }

    // Separate from the indicator's ViewPropertyAnimator, which runs the zoom with other timings
    private val indicatorFadeIn =
        ObjectAnimator.ofFloat(indicator, View.ALPHA, 1f).apply {
            startDelay = INDICATOR_FADE_IN_DELAY
            duration = INDICATOR_FADE_IN_DURATION
            interpolator = AccelerateInterpolator()
        }

    private var noiseEffect: LoadingEffect? = null
    private var noisePaint: Paint? = null
    // 0 shows only the surface color, 1 only the noise
    private var noiseProgress = 0f
    private var zoom = 1f
    private var wasWindowHidden = false

    /** Called once the animation starts. */
    var onStart: Runnable? = null

    /** [SystemClock.elapsedRealtime] when the animation started, or 0 before that. */
    var startTime = 0L
        private set

    init {
        setWillNotDraw(false)
        addView(
            indicator,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A window that is hidden when first attached never reports becoming hidden
        if (windowVisibility != VISIBLE) {
            wasWindowHidden = true
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            wasWindowHidden = true
        } else if (wasWindowHidden) {
            start()
        }
    }

    private fun start() {
        if (startTime != 0L) return
        startTime = SystemClock.elapsedRealtime()
        Log.d(TAG, "Starting")
        onStart?.run()
        noiseEffect?.play()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NOISE_FADE_IN_DURATION
            interpolator = Interpolators.LINEAR
            addUpdateListener {
                noiseProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        indicatorFadeIn.start()
        zoomIn(FIRST_ZOOM_STEP)
    }

    /** Fades out while growing slightly, then calls [onEnd]. */
    fun playExit(duration: Long, onEnd: Runnable) {
        noiseEffect?.finish()
        indicatorFadeIn.cancel()
        indicator.animate().cancel()
        indicator
            .animate()
            .alpha(0f)
            .scaleX(indicator.scaleX + EXIT_SCALE_STEP)
            .scaleY(indicator.scaleY + EXIT_SCALE_STEP)
            .setDuration(INDICATOR_EXIT_DURATION)
        // Cancelling stops the zoom chain, its end action doesn't run on cancel
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(scaleX + EXIT_SCALE_STEP)
            .scaleY(scaleY + EXIT_SCALE_STEP)
            .setDuration(duration)
            .withEndAction(onEnd)
    }

    /**
     * Zooms in by [step] over [ZOOM_STEP_DURATION], then keeps going with ever smaller steps so the
     * motion slows down without stopping. The indicator is scaled the other way to keep its size.
     */
    private fun zoomIn(step: Float) {
        zoom += step
        indicator.animate().scaleX(1f / zoom).scaleY(1f / zoom).setDuration(ZOOM_STEP_DURATION)
        animate()
            .scaleX(zoom)
            .scaleY(zoom)
            .setDuration(ZOOM_STEP_DURATION)
            .withEndAction { zoomIn(step * ZOOM_STEP_DECAY) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (noiseEffect != null || w == 0 || h == 0) return
        val config =
            TurbulenceNoiseAnimationConfig(
                gridCount = NOISE_GRID_COUNT,
                color = noiseColor,
                screenColor = Color.TRANSPARENT,
                width = w.toFloat(),
                height = h.toFloat(),
                pixelDensity = resources.displayMetrics.density,
                maxDuration = NOISE_MAX_DURATION,
                fadeInDuration = NOISE_FADE_IN_DURATION.toFloat(),
            )
        noiseEffect =
            LoadingEffect(NOISE_TYPE, config, this)
        if (startTime != 0L) {
            noiseEffect?.play()
        }
    }

    override fun onDraw(paint: Paint) {
        noisePaint = paint
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val surfaceAlpha = ((1f - noiseProgress) * 255).roundToInt()
        canvas.drawColor(ColorUtils.setAlphaComponent(surfaceColor, surfaceAlpha))
        // The noise is a runtime shader, which needs hardware acceleration
        val paint = noisePaint ?: return
        if (!canvas.isHardwareAccelerated) return
        paint.alpha = (noiseProgress * 255).roundToInt()
        canvas.drawPaint(paint)
    }

    companion object {
        private const val TAG = "GlowLoaderView"
        private const val WARM_UP_SIZE = 16

        /**
         * Compiles the noise shader by drawing it offscreen. With an empty shader cache, as on the
         * first boot after an update, compiling it on first use stalls the render thread of the
         * whole process for seconds, which freezes the loader on its first frame. Blocks until
         * drawn, call it from a background thread before the loader is shown.
         */
        @JvmStatic
        fun warmUpShader() {
            val reader =
                ImageReader.newInstance(
                    WARM_UP_SIZE,
                    WARM_UP_SIZE,
                    PixelFormat.RGBA_8888,
                    1,
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
                )
            val renderer = HardwareRenderer()
            try {
                val node = RenderNode("GlowLoaderWarmUp")
                node.setPosition(0, 0, WARM_UP_SIZE, WARM_UP_SIZE)
                val canvas = node.beginRecording()
                val paint = Paint().apply { shader = TurbulenceNoiseShader(NOISE_TYPE) }
                // Opaque and translucent paints can compile to different programs, draw both
                canvas.drawPaint(paint)
                paint.alpha = 128
                canvas.drawPaint(paint)
                node.endRecording()
                renderer.setContentRoot(node)
                renderer.setSurface(reader.surface)
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                Log.d(TAG, "Shader warmed up")
            } finally {
                renderer.destroy()
                reader.close()
            }
        }

        private val NOISE_TYPE = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE_SIMPLE
        private const val NOISE_ALPHA = 51 // 20%
        private const val NOISE_GRID_COUNT = 4.5f
        // The loader is normally gone within seconds, this only bounds a stuck load
        private const val NOISE_MAX_DURATION = 60_000f
        private const val NOISE_FADE_IN_DURATION = 500L

        private const val INDICATOR_SIZE_DP = 54f
        private const val INDICATOR_FADE_IN_DELAY = 500L
        private const val INDICATOR_FADE_IN_DURATION = 500L
        private const val INDICATOR_EXIT_DURATION = 166L

        private const val ZOOM_STEP_DURATION = 4000L
        private const val FIRST_ZOOM_STEP = 0.5f
        private const val ZOOM_STEP_DECAY = 0.75f
        private const val EXIT_SCALE_STEP = 0.2f
    }
}
