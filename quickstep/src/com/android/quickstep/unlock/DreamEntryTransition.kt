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

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.view.RemoteAnimationTarget
import android.view.RemoteAnimationTarget.MODE_OPENING
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CLOSE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_BACK
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.RemoteTransition
import android.window.TransitionFilter
import com.android.app.animation.Interpolators.LINEAR
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAnimationRunner
import com.android.launcher3.LauncherAnimationRunner.AnimationResult
import com.android.launcher3.LauncherState.NORMAL
import com.android.launcher3.anim.AnimatorListeners.forEndCallback
import com.android.launcher3.util.EventLogArray
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.quickstep.RemoteAnimationTargets
import com.android.quickstep.util.SurfaceTransaction
import com.android.quickstep.util.SurfaceTransactionApplier
import java.io.PrintWriter
import java.lang.ref.WeakReference

/**
 * Plays the home screen side of the transition from Launcher into a dream (screen saver).
 *
 * The [UnlockAnimationStyle] used for the unlock entrance is played backwards, so the home screen
 * is put away the same way it was revealed, while the dream window fades in on top of it. Leaving
 * the dream is not handled here.
 *
 * The owner keeps this object alive while [remoteTransition] is registered, since
 * [LauncherAnimationRunner] only holds a weak reference to its factory.
 */
class DreamEntryTransition(launcher: Launcher, style: UnlockAnimationStyle) :
    LauncherAnimationRunner.RemoteAnimationFactory {

    private val launcherRef = WeakReference(launcher)
    private val eventLog = EventLogArray("DreamEntryAnimation", MAX_EVENT_LOGS)

    /** The transition to register with Shell through `SystemUiProxy.registerRemoteTransition`. */
    val remoteTransition =
        RemoteTransition(
            LauncherAnimationRunner(MAIN_EXECUTOR.handler, this, false /* startAtFrontOfQueue */)
                .toRemoteTransition(),
            launcher.iApplicationThread,
            "LauncherToDream",
            createFilter(launcher),
        )

    // Main thread only
    private var runningAnimation: AnimatorSet? = null
    private var isDestroyed = false

    /**
     * The style used for the next transition. Changing it cancels any transition in progress.
     * Must be called on the main thread.
     */
    var style: UnlockAnimationStyle = style
        set(value) {
            if (field === value) return
            field = value
            eventLog.addLog("style changed: ${value.javaClass.simpleName}")
            runningAnimation?.cancel()
        }

    /**
     * Restores the home screen and skips any further transitions. Must be called on the main
     * thread after [remoteTransition] is unregistered.
     */
    fun destroy() {
        eventLog.addLog("destroy")
        isDestroyed = true
        runningAnimation?.cancel()
    }

    override fun onAnimationStart(
        transit: Int,
        appTargets: Array<RemoteAnimationTarget>?,
        wallpaperTargets: Array<RemoteAnimationTarget>?,
        nonAppTargets: Array<RemoteAnimationTarget>?,
        result: AnimationResult?,
    ) {
        result ?: return
        val apps = appTargets ?: emptyArray()
        val targets =
            RemoteAnimationTargets(
                apps,
                wallpaperTargets ?: emptyArray(),
                nonAppTargets ?: emptyArray(),
                MODE_OPENING,
            )
        fun skip(reason: String) {
            eventLog.addLog("onAnimationStart: skipped, $reason")
            result.setAnimation(null, null)
            targets.release()
        }

        val launcher = launcherRef.get()
        if (isDestroyed || launcher == null || launcher.isDestroyed) return skip("destroyed")
        val dream =
            apps.firstOrNull { it.taskInfo?.topActivityType == ACTIVITY_TYPE_DREAM }
                ?: return skip("no dream target")
        if (!launcher.isInState(NORMAL)) return skip("launcher not in NORMAL")
        val hideHome = createHideHomeAnimator(launcher) ?: return skip("no playback controller")

        val anim = AnimatorSet()
        anim.playTogether(hideHome, createShowDreamAnimator(launcher, dream.leash, targets))
        anim.addListener(
            forEndCallback { success: Boolean ->
                eventLog.addLog("animation end, success", success)
                if (runningAnimation === anim) runningAnimation = null
                targets.release()
            }
        )
        runningAnimation = anim
        eventLog.addLog("onAnimationStart: playing")
        result.setAnimation(
            anim,
            launcher,
            null /* onCompleteCallback */,
            true /* skipFirstFrame */,
        )
    }

    override fun onAnimationCancelled() {
        eventLog.addLog("onAnimationCancelled")
        runningAnimation?.cancel()
    }

    fun dump(prefix: String, writer: PrintWriter) {
        eventLog.dump(prefix, writer)
    }

    /**
     * Plays the style's entrance backwards through [Launcher.getAnimationCoordinator]. Once done,
     * successfully or not, the coordinator restores every item so that the home screen is intact
     * under the dream.
     */
    private fun createHideHomeAnimator(launcher: Launcher): Animator? {
        val style = style
        val coordinator = launcher.animationCoordinator
        coordinator.setAnimation(
            this,
            { anim ->
                style.buildAnimation(anim, UnlockTargets.from(launcher), UnlockTiming.DREAM)
            },
            DURATION,
        )
        val controller = coordinator.getPlaybackController(this) ?: return null

        // Start from the resting state before the next frame is drawn
        controller.setPlayFraction(1f)
        return controller.animationPlayer.apply {
            setFloatValues(1f, 0f)
            duration = DURATION
            addListener(
                forEndCallback { success: Boolean ->
                    // The player only dispatches the end of a successful run on its own
                    if (!success) controller.dispatchOnCancel().dispatchOnEnd()
                }
            )
        }
    }

    private fun createShowDreamAnimator(
        launcher: Launcher,
        leash: SurfaceControl,
        targets: RemoteAnimationTargets,
    ): Animator {
        // Opening leashes normally start hidden already; make sure the dream does not show up
        // before its fade starts.
        SurfaceTransaction().apply { forSurface(leash).setAlpha(0f) }.transaction.apply()

        val applier = SurfaceTransactionApplier(launcher.dragLayer)
        targets.addReleaseCheck(applier)
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DREAM_FADE_DURATION
            startDelay = DREAM_FADE_START_DELAY
            interpolator = LINEAR
            addUpdateListener {
                val transaction = SurfaceTransaction()
                transaction.forSurface(leash).setAlpha(it.animatedValue as Float)
                applier.scheduleApply(transaction)
            }
        }
    }

    companion object {
        private const val DURATION = 450L
        private const val DREAM_FADE_DURATION = 250L
        private const val DREAM_FADE_START_DELAY = 200L

        private const val MAX_EVENT_LOGS = 20

        /** Matches a dream opening on top of this Launcher. */
        private fun createFilter(launcher: Launcher) =
            TransitionFilter().apply {
                mRequirements =
                    arrayOf(
                        TransitionFilter.Requirement().apply {
                            mActivityType = ACTIVITY_TYPE_DREAM
                            mModes = intArrayOf(TRANSIT_OPEN, TRANSIT_TO_FRONT)
                        },
                        TransitionFilter.Requirement().apply {
                            mActivityType = ACTIVITY_TYPE_HOME
                            mTopActivity = launcher.componentName
                            mModes = intArrayOf(TRANSIT_CLOSE, TRANSIT_TO_BACK)
                        },
                    )
            }
    }
}
