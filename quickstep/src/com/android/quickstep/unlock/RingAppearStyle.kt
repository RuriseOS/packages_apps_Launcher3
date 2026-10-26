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

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.appwidget.AppWidgetHostView
import android.util.FloatProperty
import android.view.View
import android.view.animation.Interpolator
import com.android.app.animation.Interpolators.LINEAR
import com.android.app.animation.Interpolators.clampToProgress
import com.android.launcher3.CellLayout
import com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY
import com.android.launcher3.LauncherAnimUtils.VIEW_ALPHA
import com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_X
import com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_Y
import com.android.launcher3.Reorderable
import com.android.launcher3.Utilities
import com.android.launcher3.anim.PendingAnimation
import com.android.launcher3.util.MultiPropertyFactory.MULTI_PROPERTY_VALUE
import com.android.launcher3.util.MultiTranslateDelegate.INDEX_MOVE_FROM_CENTER_ANIM

/** Scale of a workspace item, composed with any scale it applies on its own (e.g. widgets). */
private val REORDER_BOUNCE_SCALE =
    object : FloatProperty<Reorderable>("reorderBounceScale") {
        override fun setValue(obj: Reorderable, value: Float) = obj.setReorderBounceScale(value)

        override fun get(obj: Reorderable): Float = obj.reorderBounceScale
    }

/**
 * Reveals the home screen ring by ring, from the outer edge of the visible grid towards its
 * center. Every item fades in while it scales up and slides out from the grid center to its
 * resting position.
 */
class RingAppearStyle(private val spec: RingAppearSpec = RingAppearSpec.DEFAULT) :
    UnlockAnimationStyle {

    override fun buildAnimation(
        anim: PendingAnimation,
        targets: UnlockTargets,
        timing: UnlockTiming,
    ) {
        val rings = collectRings(targets)
        if (rings.isEmpty()) return
        val center = gridCenterInRoot(targets) ?: return

        val alphaInterpolator = spec.alphaInterpolator.create()
        val motionInterpolator = spec.motionInterpolator.create()
        val f = timing.startFraction

        rings.forEachIndexed { index, ring ->
            val start = (f + (1 - f) * spec.ringDelay * index).coerceAtMost(1f)
            val alphaEnd = start + (1 - start) * spec.alphaProgress
            val alpha = clampToProgress(alphaInterpolator, start, alphaEnd)
            val motion = clampToProgress(motionInterpolator, start, 1f)
            ring.forEach { view ->
                animateItem(anim, view, targets.root, center, alpha, motion)
                if (view is AppWidgetHostView) {
                    useLayerWhileFading(anim, view, start, alphaEnd)
                }
            }
        }

        disableClipping(anim, targets.visiblePages)
    }

    private fun animateItem(
        anim: PendingAnimation,
        view: View,
        root: View,
        center: FloatArray,
        alpha: Interpolator,
        motion: Interpolator,
    ) {
        // Grid center in the item's own coordinates
        val target = center.copyOf()
        Utilities.mapCoordInSelfToDescendant(view, root, target)

        val pullX = (target[0] - view.pivotX) * spec.pullToCenter
        val pullY = (target[1] - view.pivotY) * spec.pullToCenter

        if (spec.scalePivot == RingAppearSpec.ScalePivot.SHARED_CENTER) {
            scaleAroundPoint(anim, view, target[0], target[1])
        }
        anim.addFloat(view, VIEW_ALPHA, 0f, view.alpha, alpha)

        if (view is Reorderable) {
            // Workspace items compose their transforms from several sources (widget scale-to-fit
            // and centering, reorder hints, ...). Only animate our own components so that any
            // change made by those while the animation runs, or is recreated, is preserved.
            val restScale = view.reorderBounceScale
            val startScale = restScale * spec.startScale
            anim.addFloat(view, REORDER_BOUNCE_SCALE, startScale, restScale, motion)
            val delegate = view.translateDelegate
            anim.add(
                ObjectAnimator.ofFloat(
                    delegate.getTranslationX(INDEX_MOVE_FROM_CENTER_ANIM),
                    MULTI_PROPERTY_VALUE,
                    pullX,
                    0f,
                ),
                motion,
            )
            anim.add(
                ObjectAnimator.ofFloat(
                    delegate.getTranslationY(INDEX_MOVE_FROM_CENTER_ANIM),
                    MULTI_PROPERTY_VALUE,
                    pullY,
                    0f,
                ),
                motion,
            )
        } else {
            val restScale = view.scaleX
            val restTransX = view.translationX
            val restTransY = view.translationY
            anim.addFloat(view, SCALE_PROPERTY, restScale * spec.startScale, restScale, motion)
            anim.addFloat(view, VIEW_TRANSLATE_X, restTransX + pullX, restTransX, motion)
            anim.addFloat(view, VIEW_TRANSLATE_Y, restTransY + pullY, restTransY, motion)
        }
    }

    /**
     * Renders [view] into a hardware layer while it is partially transparent, between the
     * progress [start] and [end]. Without it, a large widget with overlapping content is drawn
     * into a fresh offscreen buffer on every frame of the fade. The result looks the same.
     */
    private fun useLayerWhileFading(anim: PendingAnimation, view: View, start: Float, end: Float) {
        val restLayerType = view.layerType
        if (restLayerType == View.LAYER_TYPE_HARDWARE || start >= end) return
        // Not a frame listener: those run on an animator with the default easing, whereas start and
        // end are linear progress values.
        val progressTracker = ValueAnimator.ofFloat(0f, 1f)
        progressTracker.addUpdateListener { animator ->
            val progress = animator.animatedFraction
            val layerType =
                if (progress > start && progress < end) View.LAYER_TYPE_HARDWARE
                else restLayerType
            if (view.layerType != layerType) view.setLayerType(layerType, null)
        }
        anim.add(progressTracker, LINEAR)
        anim.addEndListener {
            if (view.layerType != restLayerType) view.setLayerType(restLayerType, null)
        }
    }

    /** Moves the pivot of [view] to ([x], [y]) for the duration of the animation. */
    private fun scaleAroundPoint(anim: PendingAnimation, view: View, x: Float, y: Float) {
        val hadPivot = view.isPivotSet
        val restPivotX = view.pivotX
        val restPivotY = view.pivotY
        view.pivotX = x
        view.pivotY = y
        anim.addEndListener {
            if (hadPivot) {
                view.pivotX = restPivotX
                view.pivotY = restPivotY
            } else {
                view.resetPivot()
            }
        }
    }

    /** Items can be drawn outside their container while pulled in, so clipping is lifted. */
    private fun disableClipping(anim: PendingAnimation, pages: List<CellLayout>) {
        val clipped = pages.map { it.shortcutsAndWidgets }.filter { it.clipChildren }
        if (clipped.isEmpty()) return
        clipped.forEach { it.clipChildren = false }
        anim.addEndListener { clipped.forEach { it.clipChildren = true } }
    }

    /**
     * Returns the average center of the visible pages' item containers, in [UnlockTargets.root]
     * coordinates.
     */
    private fun gridCenterInRoot(targets: UnlockTargets): FloatArray? {
        if (targets.visiblePages.isEmpty()) return null
        val sum = FloatArray(2)
        val point = FloatArray(2)
        targets.visiblePages.forEach { page ->
            val container = page.shortcutsAndWidgets
            point[0] = container.width / 2f
            point[1] = container.height / 2f
            Utilities.getDescendantCoordRelativeToAncestor(container, targets.root, point, false)
            sum[0] += point[0]
            sum[1] += point[1]
        }
        val count = targets.visiblePages.size
        return floatArrayOf(sum[0] / count, sum[1] / count)
    }

    /**
     * Splits the visible items into concentric rings. The visible pages are joined side by side
     * into one virtual grid; ring 0 is its outer border (plus the hotseat) and every following
     * ring is the border of the grid shrunk by one cell on each side. Items spanning several
     * cells belong to the outermost ring they touch.
     */
    private fun collectRings(targets: UnlockTargets): List<List<View>> {
        val pages = targets.visiblePages.sortedBy { it.left }
        val columns = pages.sumOf { it.countX }
        val rows = pages.maxOfOrNull { it.countY } ?: 0

        val seen = HashSet<View>()
        val rings = ArrayList<List<View>>()

        fun itemAt(x: Int, y: Int): View? {
            var offset = 0
            for (page in pages) {
                if (x < offset + page.countX) {
                    if (y >= page.countY) return null
                    return page.shortcutsAndWidgets.getChildAt(x - offset, y)
                }
                offset += page.countX
            }
            return null
        }

        var top = 0
        var bottom = rows - 1
        var left = 0
        var right = columns - 1
        while (top <= bottom && left <= right) {
            val ring = ArrayList<View>()
            fun visit(x: Int, y: Int) {
                itemAt(x, y)?.takeIf(seen::add)?.let(ring::add)
            }
            for (x in left..right) {
                visit(x, top)
                visit(x, bottom)
            }
            for (y in top + 1 until bottom) {
                visit(left, y)
                visit(right, y)
            }
            if (rings.isEmpty()) {
                targets.hotseat?.takeIf { it.visibility == View.VISIBLE }?.let(ring::add)
            }
            // Keep empty rings so that the ring delay still reflects the distance to the edge.
            rings.add(ring)
            top++
            bottom--
            left++
            right--
        }
        // Trailing empty rings would only delay nothing.
        while (rings.isNotEmpty() && rings.last().isEmpty()) rings.removeAt(rings.size - 1)
        return rings
    }
}
