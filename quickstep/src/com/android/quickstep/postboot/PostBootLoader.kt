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

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.launcher3.R
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.RunnableList
import com.android.launcher3.util.Themes

/**
 * A full screen window covering the home screen from the first unlock after a restart until
 * Launcher has bound its workspace.
 *
 * The window belongs to the home activity, so it is created by [PostBootActivity] and survives
 * the relaunch into Launcher. It is drawn on its own thread so that it keeps animating while the
 * main thread is busy loading Launcher. Once [PostBootReveal] sees the workspace bound, and the
 * loader has been visible for at least [MIN_VISIBLE_DURATION], it fades out while the home screen
 * is revealed.
 */
class PostBootLoader private constructor(activity: Activity) {

    private val windowManager = activity.windowManager
    private val thread =
        HandlerThread("PostBootLoader", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    // Colors follow the home activity theme, which tracks the wallpaper and dark mode
    private val surfaceColor = activity.getColor(R.color.materialColorSurfaceContainerHigh)
    private val accentColor =
        Themes.getAttrColor(activity, com.android.internal.R.attr.colorAccentPrimary)

    // Main thread only
    private var revealCallbacks: RunnableList? = null

    // Keep the system bar icons as they are over the home activity
    private val systemBarsAppearance = activity.window.insetsController?.systemBarsAppearance ?: 0

    // Loader thread only
    private val windowParams =
        LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                LayoutParams.TYPE_DRAWN_APPLICATION,
                // Without FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS the system draws opaque bars over the
                // loader. Nothing is drawn behind them here, so they stay transparent.
                LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
                title = "PostBootLoader"
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                windowAnimations = 0
                @Suppress("DEPRECATION")
                systemUiVisibility =
                    (if ((systemBarsAppearance and APPEARANCE_LIGHT_STATUS_BARS) != 0) {
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    } else 0) or
                        (if ((systemBarsAppearance and APPEARANCE_LIGHT_NAVIGATION_BARS) != 0) {
                            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        } else 0)
            }
    private var activityToken: IBinder? = null
    private var root: FrameLayout? = null
    private var content: GlowLoaderView? = null
    private var blur: BackgroundBlurDrawable? = null

    init {
        val context = activity.window.context
        handler.post { addWindow(context) }
        handler.post { warmUpShader() }
    }

    /**
     * Fades the loader out, after [MIN_VISIBLE_DURATION] if it only just appeared. Must be called
     * on the main thread. Returns the callbacks run on the main thread when the home screen
     * underneath should start appearing.
     */
    fun dismiss(): RunnableList {
        revealCallbacks?.let {
            return it
        }
        val callbacks = RunnableList()
        revealCallbacks = callbacks
        handler.post {
            val startTime = content?.startTime ?: 0L
            // Not started means it was never seen, there is nothing to keep on screen
            val delay =
                if (startTime == 0L) 0L
                else
                    (MIN_VISIBLE_DURATION - (SystemClock.elapsedRealtime() - startTime))
                        .coerceAtLeast(0L)
            Log.d(TAG, "Started at $startTime, dismissing in ${delay}ms")
            handler.postDelayed({ playExit(callbacks) }, delay)
        }
        return callbacks
    }

    /** Removes the loader without animating. Must be called on the main thread. */
    fun removeNow() {
        Log.d(TAG, "Removing immediately")
        handler.removeCallbacksAndMessages(null)
        handler.post { removeWindow() }
        revealCallbacks?.executeAllAndDestroy()
        clear()
    }

    private fun addWindow(context: Context) {
        // An exception on this thread would take down Launcher on every boot, show nothing instead
        try {
            addWindowUnchecked(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show loader", e)
        }
    }

    private fun warmUpShader() {
        // Without it the loader may freeze on its first frame, but it still works
        try {
            GlowLoaderView.warmUpShader()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to warm up the loader shader", e)
        }
    }

    private fun addWindowUnchecked(context: Context) {
        val loaderView = GlowLoaderView(context, surfaceColor, accentColor)
        // The window takes all touches, never let it get stuck on screen
        loaderView.onStart = Runnable {
            handler.postDelayed(
                {
                    Log.w(TAG, "Home screen not ready in time, giving up")
                    MAIN_EXECUTOR.execute { if (current === this) removeNow() }
                },
                MAX_VISIBLE_DURATION,
            )
        }
        val container = FrameLayout(context).apply { addView(loaderView) }
        // The blur needs the view root, which is only attached on the first traversal
        container.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    container.removeOnAttachStateChangeListener(this)
                    addBlur(container)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            }
        )
        windowManager.addView(container, windowParams)
        // Closing an activity removes every window that uses its token, including on the relaunch
        // into Launcher. Hide the token from that lookup while the window is up, the server side
        // keeps the original one.
        activityToken = windowParams.token
        windowParams.token = Binder()
        root = container
        content = loaderView
        Log.d(TAG, "Loader window added")
    }

    private fun addBlur(container: FrameLayout) {
        if (!windowManager.isCrossWindowBlurEnabled) return
        // Only a nicety, keep the loader if it fails
        try {
            val drawable = container.viewRootImpl?.createBackgroundBlurDrawable() ?: return
            drawable.setBlurRadius(BLUR_RADIUS)
            container.background = drawable
            blur = drawable
        } catch (e: Exception) {
            Log.e(TAG, "Failed to blur the loader background", e)
        }
    }

    private fun playExit(callbacks: RunnableList) {
        val revealHome = Runnable {
            callbacks.executeAllAndDestroy()
            clear()
        }
        val container = root
        val loaderView = content
        if (container == null || loaderView == null) {
            removeWindow()
            MAIN_EXECUTOR.execute(revealHome)
            return
        }
        container.animate().alpha(0f).setDuration(EXIT_DURATION)
        blur?.let { drawable ->
            ValueAnimator.ofInt(BLUR_RADIUS, 0).apply {
                duration = EXIT_DURATION
                addUpdateListener { drawable.setBlurRadius(it.animatedValue as Int) }
                start()
            }
        }
        loaderView.playExit(EXIT_DURATION) { removeWindow() }
        MAIN_EXECUTOR.handler.postDelayed(revealHome, REVEAL_DELAY)
    }

    private fun removeWindow() {
        root?.let {
            windowParams.token = activityToken
            windowManager.removeViewImmediate(it)
        }
        root = null
        content = null
        blur = null
        thread.quitSafely()
    }

    private fun clear() {
        if (current === this) {
            current = null
        }
    }

    companion object {
        private const val TAG = "PostBootLoader"

        /** Shortest time the loader stays up once visible, so that it never just flashes. */
        private const val MIN_VISIBLE_DURATION = 4000L
        private const val MAX_VISIBLE_DURATION = 20_000L
        private const val EXIT_DURATION = 500L
        /** Delay into the exit before the home screen starts appearing. */
        private const val REVEAL_DELAY = 375L
        private const val BLUR_RADIUS = 30

        /** The loader currently on screen, main thread only. */
        @JvmStatic
        var current: PostBootLoader? = null
            private set

        /** Shows the loader over [activity], which must be attached to its window. */
        @JvmStatic
        fun show(activity: Activity) {
            if (current != null) return
            current = PostBootLoader(activity)
        }
    }
}
