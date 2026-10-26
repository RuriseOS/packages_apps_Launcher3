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

import android.graphics.Rect
import android.util.Log
import com.android.internal.jank.Cuj
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState.NORMAL
import com.android.launcher3.anim.AnimatorListeners.forEndCallback
import com.android.launcher3.anim.AnimatorPlaybackController
import com.android.launcher3.util.EventLogArray
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.systemui.shared.system.InteractionJankMonitorWrapper
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import java.io.PrintWriter
import java.lang.ref.WeakReference

private const val TAG = "LauncherUnlockAnim"

/**
 * Plays Launcher's side of the keyguard unlock transition on behalf of SystemUI's
 * KeyguardUnlockAnimationController.
 *
 * This class only owns the timeline: it prepares, plays and force-finishes a canned animation
 * through [Launcher.getAnimationCoordinator]. What the home screen looks like during that
 * animation is delegated to an [UnlockAnimationStyle].
 *
 * Smartspace shared-element support is intentionally not implemented; since the smartspace
 * state is never reported to SystemUI, it will not attempt that part of the transition.
 */
class LauncherUnlockAnimationController(launcher: Launcher, style: UnlockAnimationStyle) :
    ILauncherUnlockAnimationController.Stub() {

    private val launcherRef = WeakReference(launcher)
    private val eventLog = EventLogArray("LauncherUnlockAnimation", MAX_EVENT_LOGS)

    // Main thread only
    private var playback: AnimatorPlaybackController? = null
    private var playbackTotalDuration = -1L
    private var isInstrumenting = false
    private var isDestroyed = false

    /**
     * The style used for the next unlock. Changing it finishes any animation in progress so that
     * the new style starts from a clean state. Must be called on the main thread.
     */
    var style: UnlockAnimationStyle = style
        set(value) {
            if (field === value) return
            field = value
            eventLog.addLog("style changed: ${value.javaClass.simpleName}")
            finishAnimation()
        }

    /**
     * Restores the home screen and ignores any further calls from SystemUI. Must be called on the
     * main thread after this controller is unregistered.
     */
    fun destroy() {
        eventLog.addLog("destroy")
        finishAnimation()
        isDestroyed = true
    }

    override fun prepareForUnlock(
        animateSmartspace: Boolean,
        lockscreenSmartspaceBounds: Rect?,
        selectedPage: Int,
    ) {
        runOnLauncher { launcher ->
            eventLog.addLog("prepareForUnlock")
            if (!launcher.isInState(NORMAL)) {
                eventLog.addLog("prepareForUnlock: skipped, launcher not in NORMAL")
                return@runOnLauncher
            }
            setupAnimation(launcher, DEFAULT_DURATION, DEFAULT_START_DELAY)
        }
    }

    override fun playUnlockAnimation(unlocked: Boolean, duration: Long, startDelay: Long) {
        runOnLauncher { launcher ->
            eventLog.addLog("playUnlockAnimation: duration=$duration, delay=$startDelay", unlocked)
            if (!unlocked) {
                finishAnimation()
                return@runOnLauncher
            }
            if (!launcher.isInState(NORMAL)) {
                eventLog.addLog("playUnlockAnimation: skipped, launcher not in NORMAL")
                finishAnimation()
                return@runOnLauncher
            }
            if (playback == null || playbackTotalDuration != duration + startDelay) {
                setupAnimation(launcher, duration, startDelay)
            }
            val controller = launcher.animationCoordinator.getPlaybackController(this)
            if (controller == null || controller != playback) {
                eventLog.addLog("playUnlockAnimation: no playback controller")
                return@runOnLauncher
            }
            if (!isInstrumenting) {
                InteractionJankMonitorWrapper.begin(
                    launcher.rootView,
                    Cuj.CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION,
                )
                isInstrumenting = true
            }
            // Plays from the current fraction, with the remaining duration scaled accordingly
            controller.start()
        }
    }

    override fun setUnlockAmount(amount: Float, forceIfAnimating: Boolean) {
        runOnLauncher {
            if (amount != 1f) {
                // Only the final value is supported, intermediate (gesture driven) values would
                // need a scrubbed animation which no style provides yet.
                eventLog.addLog("setUnlockAmount: ignored", amount)
                return@runOnLauncher
            }
            val controller = playback ?: return@runOnLauncher
            if (!controller.animationPlayer.isRunning || forceIfAnimating) {
                eventLog.addLog("setUnlockAmount: finishing, force", forceIfAnimating)
                finishAnimation()
            }
        }
    }

    override fun setSmartspaceSelectedPage(selectedPage: Int) {}

    override fun setSmartspaceVisibility(visibility: Int) {}

    override fun dispatchSmartspaceStateToSysui() {}

    fun dump(prefix: String, writer: PrintWriter) {
        eventLog.dump(prefix, writer)
    }

    private fun setupAnimation(launcher: Launcher, duration: Long, startDelay: Long) {
        val totalDuration = duration + startDelay
        val timing = UnlockTiming.forDelay(duration, startDelay)

        // A page scroll interrupted by turning the screen off only resumes once Launcher draws
        // again. Settle it first, otherwise the style animates the page the scroll started from,
        // which is mostly off screen, around a center that keeps moving.
        val workspace = launcher.workspace
        if (workspace.isPageInTransition) {
            eventLog.addLog("setupAnimation: settling page", workspace.nextPage)
            workspace.setCurrentPage(workspace.nextPage)
        }

        val coordinator = launcher.animationCoordinator
        coordinator.setAnimation(
            this,
            { anim -> style.buildAnimation(anim, UnlockTargets.from(launcher), timing) },
            totalDuration,
        )

        val controller = coordinator.getPlaybackController(this) ?: return
        playback = controller
        playbackTotalDuration = totalDuration
        controller.target.addListener(
            forEndCallback { success: Boolean ->
                if (playback !== controller) return@forEndCallback
                eventLog.addLog("animation end, success", success)
                playback = null
                playbackTotalDuration = -1L
                if (isInstrumenting) {
                    InteractionJankMonitorWrapper.end(Cuj.CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION)
                    isInstrumenting = false
                }
            }
        )
        eventLog.addLog("setupAnimation: total=$totalDuration, start", timing.startFraction)
    }

    /** Jumps to the end of the current animation so that every item is restored. */
    private fun finishAnimation() {
        val controller = playback ?: return
        controller.pause()
        controller.setPlayFraction(1f)
        controller.dispatchOnEnd()
    }

    private inline fun runOnLauncher(crossinline block: (Launcher) -> Unit) {
        MAIN_EXECUTOR.execute {
            if (isDestroyed) return@execute
            val launcher = launcherRef.get()
            if (launcher == null || launcher.isDestroyed) {
                Log.d(TAG, "Launcher is gone, dropping unlock animation call")
                return@execute
            }
            block(launcher)
        }
    }

    companion object {
        /** Canned unlock timing used by SystemUI, used until it reports the actual values. */
        const val DEFAULT_DURATION = 633L
        const val DEFAULT_START_DELAY = 25L

        private const val MAX_EVENT_LOGS = 20
    }
}
