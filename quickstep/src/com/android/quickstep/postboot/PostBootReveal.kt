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

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.ViewTreeObserver
import com.android.app.animation.Interpolators
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherState.NORMAL
import com.android.launcher3.dragndrop.DragLayer.ALPHA_INDEX_LOADER
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.quickstep.unlock.LauncherUnlockAnimationController
import com.android.quickstep.unlock.UnlockAnimationStyle
import com.android.quickstep.unlock.UnlockAnimationStyles
import java.lang.ref.WeakReference

/**
 * Shows the home screen the first time Launcher is created after a restart.
 *
 * At the first unlock SystemUI plays its unlock animation before the user is unlocked, so before
 * [PostBootActivity] turns into Launcher, and the selected unlock animation never runs. Instead
 * the home screen is kept hidden until its workspace is bound, then shown with that animation,
 * once the [PostBootLoader] covering it, if any, is gone. Main thread only.
 *
 * With [onlyIfLate] the keyguard may still be up while Launcher loads, as without a credential
 * the user is unlocked at boot. Launcher doesn't draw behind the keyguard, so the decision waits
 * for its first frame. SystemUI only animates a Launcher that sat stopped behind the keyguard, so
 * if this one was stopped and its workspace is bound, the unlock animation already ran as usual
 * and nothing is done. Otherwise the keyguard went away too early, or before this Launcher was
 * created, and it is handled as above.
 */
class PostBootReveal
private constructor(private val application: Application, private val onlyIfLate: Boolean) :
    Application.ActivityLifecycleCallbacks {

    private var launcherRef = WeakReference<Launcher>(null)
    private var workspaceBoundListener: ViewTreeObserver.OnPreDrawListener? = null
    // The workspace is checked before each frame, give up waiting if no frame follows the bind
    private var waitTimeout: Runnable? = null
    private var wasLauncherStopped = false
    private var isHidden = false

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    /** Stops waiting for Launcher, leaving its home screen as it is. */
    fun stop() {
        launcherRef.get()?.let { stopWaitingForWorkspace(it) }
        waitTimeout?.let { MAIN_EXECUTOR.handler.removeCallbacks(it) }
        waitTimeout = null
        launcherRef.clear()
        application.unregisterActivityLifecycleCallbacks(this)
        if (current === this) {
            current = null
        }
    }

    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is Launcher || launcherRef.get() != null) return
        // Launcher only exists once the user is unlocked, so its preferences can be read here
        val style = UnlockAnimationStyles.fromId(LauncherPrefs.UNLOCK_ANIMATION_STYLE.get(activity))
        if (style == null && PostBootLoader.current == null) {
            // Nothing to animate, the home screen appears as it loads like in AOSP
            stop()
            return
        }
        launcherRef = WeakReference(activity)
        wasLauncherStopped = false
        isHidden = false
        if (!onlyIfLate) {
            hideUntilBound(activity, style)
            isHidden = true
        }
        val listener =
            ViewTreeObserver.OnPreDrawListener {
                if (!isHidden) {
                    // Only with onlyIfLate, on the first frame after the keyguard. A stopped
                    // Launcher can still draw behind it, that frame doesn't count.
                    if (!activity.isStarted) return@OnPreDrawListener true
                    if (wasLauncherStopped && !activity.isWorkspaceLoading) {
                        stop()
                        return@OnPreDrawListener true
                    }
                    hideUntilBound(activity, style)
                    isHidden = true
                }
                if (!activity.isWorkspaceLoading) {
                    onWorkspaceBound(activity, style)
                }
                true
            }
        workspaceBoundListener = listener
        activity.dragLayer.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun hideUntilBound(launcher: Launcher, style: UnlockAnimationStyle?) {
        launcher.dragLayer.getAlphaProperty(ALPHA_INDEX_LOADER).value = 0f
        val timeout = Runnable {
            Log.w(TAG, "Workspace not bound in time, showing the home screen anyway")
            onWorkspaceBound(launcher, style)
        }
        waitTimeout = timeout
        MAIN_EXECUTOR.handler.postDelayed(timeout, MAX_WAIT_DURATION)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !is Launcher || activity !== launcherRef.get()) return
        if (activity.isChangingConfigurations) {
            // Start over with the recreated Launcher
            stopWaitingForWorkspace(activity)
            waitTimeout?.let { MAIN_EXECUTOR.handler.removeCallbacks(it) }
            waitTimeout = null
            launcherRef.clear()
        } else {
            PostBootLoader.current?.removeNow()
            stop()
        }
    }

    private fun onWorkspaceBound(launcher: Launcher, style: UnlockAnimationStyle?) {
        stop()
        val loader = PostBootLoader.current
        if (loader == null) {
            reveal(launcher, style)
        } else {
            loader.dismiss().add { reveal(launcher, style) }
        }
    }

    private fun stopWaitingForWorkspace(launcher: Launcher) {
        val listener = workspaceBoundListener ?: return
        workspaceBoundListener = null
        launcher.dragLayer.viewTreeObserver.removeOnPreDrawListener(listener)
    }

    private fun reveal(launcher: Launcher, style: UnlockAnimationStyle?) {
        if (launcher.isDestroyed) return
        val alpha = launcher.dragLayer.getAlphaProperty(ALPHA_INDEX_LOADER)
        if (style == null || !launcher.isInState(NORMAL)) {
            alpha.animateToValue(1f).apply {
                duration = FADE_DURATION
                interpolator = Interpolators.LINEAR
                start()
            }
            return
        }
        // Play the selected unlock animation as if SystemUI had asked for it. Both calls post
        // to the main thread, so the home screen is shown again only after the animation has
        // put the items in their start state.
        val unlockAnimation = LauncherUnlockAnimationController(launcher, style)
        unlockAnimation.prepareForUnlock(false, null, 0)
        unlockAnimation.playUnlockAnimation(true, ANIMATION_DURATION, 0)
        MAIN_EXECUTOR.execute { alpha.value = 1f }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        if (activity !== launcherRef.get()) return
        wasLauncherStopped = true
        if (onlyIfLate && isHidden) {
            // Launcher can draw a frame right after it is created, before the keyguard stops
            // it, so that frame wasn't after the keyguard. Decide again on the next one.
            (activity as Launcher).dragLayer.getAlphaProperty(ALPHA_INDEX_LOADER).value = 1f
            waitTimeout?.let { MAIN_EXECUTOR.handler.removeCallbacks(it) }
            waitTimeout = null
            isHidden = false
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    companion object {
        private const val TAG = "PostBootReveal"
        private const val FADE_DURATION = 250L
        private const val MAX_WAIT_DURATION = 20_000L
        private const val ANIMATION_DURATION = 500L

        /** The reveal waiting for Launcher, main thread only. */
        @JvmStatic
        var current: PostBootReveal? = null
            private set

        /**
         * Waits for the next Launcher to be created, does nothing if already waiting. With
         * [onlyIfLate] the home screen is only hidden if the keyguard is gone before it is ready.
         */
        @JvmStatic
        fun start(application: Application, onlyIfLate: Boolean) {
            if (current != null) return
            current = PostBootReveal(application, onlyIfLate)
        }
    }
}
