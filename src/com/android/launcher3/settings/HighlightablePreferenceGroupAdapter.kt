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

package com.android.launcher3.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import com.android.settingslib.widget.DrawableStateLayout
import com.android.settingslib.widget.FooterData
import com.android.settingslib.widget.SettingsPreferenceGroupAdapter
import com.android.settingslib.widget.theme.R
import com.google.android.material.appbar.AppBarLayout

/**
 * Highlights a preference the same way as the Settings app: the app bar collapses, the list
 * scrolls to the preference and its rounded background blinks in the accent color for a while.
 *
 * Only meant for the expressive theme, where the rounded backgrounds exist.
 */
class HighlightablePreferenceGroupAdapter(
    preferenceGroup: PreferenceGroup,
    footerDataMap: Map<String, FooterData>,
    private val highlightKey: String?,
) : SettingsPreferenceGroupAdapter(preferenceGroup, footerDataMap) {

    private var highlightRequested = false
    private var highlightPosition = RecyclerView.NO_POSITION
    // Whether the fade in has been played, so that rebinding doesn't play it again
    private var highlightShown = false
    private var highlightedHolder: PreferenceViewHolder? = null
    private var backgroundAnimator: ValueAnimator? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position)
        if (
            position == highlightPosition &&
                highlightKey != null &&
                highlightKey == preference?.key &&
                holder.itemView.isShown
        ) {
            requestInitialAccessibilityFocus(holder)
            addHighlightBackground(holder, position, animate = !highlightShown)
        } else if (holder === highlightedHolder) {
            // The highlighted view is reused for another preference
            removeHighlightBackground(holder, position, animate = false)
        }
    }

    /**
     * Highlights the preference with the key given to this adapter, if it is in the list.
     *
     * @return whether the highlight was started.
     */
    fun requestHighlight(root: View, list: RecyclerView, appBar: AppBarLayout?): Boolean {
        if (highlightRequested || highlightKey.isNullOrEmpty()) {
            return false
        }
        if (getPreferenceAdapterPosition(highlightKey) < 0) {
            return false
        }
        highlightRequested = true

        appBar?.let { root.postDelayed({ it.setExpanded(false, true) }, COLLAPSE_DELAY_MS) }

        // Item animations can crash the list while the background changes
        list.itemAnimator = null
        val touchExploration =
            list.context.getSystemService(AccessibilityManager::class.java)
                ?.isTouchExplorationEnabled == true
        root.postDelayed(
            {
                // The position may have changed while waiting
                val position = getPreferenceAdapterPosition(highlightKey)
                if (position >= 0) {
                    highlightPosition = position
                    list.smoothScrollToPosition(position)
                    highlightWhenVisible(list, position)
                }
            },
            if (touchExploration) HIGHLIGHT_DELAY_A11Y_MS else HIGHLIGHT_DELAY_MS,
        )
        return true
    }

    private fun highlightWhenVisible(list: RecyclerView, position: Int) {
        val target = list.findViewHolderForAdapterPosition(position)
        if (target != null) {
            notifyItemChanged(position)
            target.itemView.requestFocus()
            return
        }
        // Wait for the scroll to bring it in
        list.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                        return
                    }
                    notifyItemChanged(position)
                    recyclerView.findViewHolderForAdapterPosition(position)
                        ?.itemView
                        ?.requestFocus()
                    recyclerView.removeOnScrollListener(this)
                }
            }
        )
    }

    private fun requestInitialAccessibilityFocus(holder: PreferenceViewHolder) {
        val title = holder.findViewById(android.R.id.title) as? TextView ?: return
        ViewCompat.setAccessibilityDelegate(
            title,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.setRequestInitialAccessibilityFocus(true)
                }
            },
        )
    }

    private fun addHighlightBackground(
        holder: PreferenceViewHolder,
        position: Int,
        animate: Boolean,
    ) {
        val normalRes = getRoundCornerDrawableRes(position, isSelected = false)
        val highlightRes =
            getRoundCornerDrawableRes(position, isSelected = false, isHighlighted = true)
        if (normalRes == 0 || highlightRes == 0) {
            // Not in a group, so there is no rounded background to highlight
            return
        }
        val view = holder.itemView
        cancelBackgroundAnimator()
        highlightedHolder = holder
        // Keep the view while it is highlighted, as it would lose the highlight when reused
        holder.setIsRecyclable(false)

        if (!animate) {
            view.setBackgroundResource(highlightRes)
            scheduleRemoveHighlight(holder, position)
            return
        }
        highlightShown = true

        val transition = transitionOf(view, normalRes, highlightRes)
        view.background = transition
        // Blink a few times like the Settings app, then stay highlighted
        val blink = ValueAnimator.ofInt(0, 1)
        blink.duration = FADE_IN_DURATION_MS.toLong()
        blink.repeatMode = ValueAnimator.REVERSE
        blink.repeatCount = BLINK_REPEAT_COUNT
        blink.addListener(
            object : AnimatorListenerAdapter() {
                private var reverseNext = false

                override fun onAnimationStart(animation: Animator) {
                    transition.startTransition(FADE_IN_DURATION_MS)
                    reverseNext = true
                }

                override fun onAnimationRepeat(animation: Animator) {
                    if (reverseNext) {
                        transition.reverseTransition(FADE_IN_DURATION_MS)
                    } else {
                        transition.startTransition(FADE_IN_DURATION_MS)
                    }
                    reverseNext = !reverseNext
                }

                override fun onAnimationEnd(animation: Animator) {
                    // Whoever cancelled it takes care of the background
                    if (backgroundAnimator !== animation) {
                        return
                    }
                    backgroundAnimator = null
                    view.setBackgroundResource(highlightRes)
                    scheduleRemoveHighlight(holder, position)
                }
            }
        )
        backgroundAnimator = blink
        blink.start()
    }

    private fun scheduleRemoveHighlight(holder: PreferenceViewHolder, position: Int) {
        holder.itemView.postDelayed(
            {
                if (highlightedHolder === holder) {
                    highlightPosition = RecyclerView.NO_POSITION
                    removeHighlightBackground(holder, position, animate = true)
                }
            },
            HIGHLIGHT_DURATION_MS,
        )
    }

    private fun removeHighlightBackground(
        holder: PreferenceViewHolder,
        position: Int,
        animate: Boolean,
    ) {
        val view = holder.itemView
        cancelBackgroundAnimator()
        highlightedHolder = null

        val normalRes = getRoundCornerDrawableRes(position, isSelected = false)
        val highlightRes =
            getRoundCornerDrawableRes(position, isSelected = false, isHighlighted = true)
        if (!animate || normalRes == 0 || highlightRes == 0) {
            // Called while binding, where the list can't be notified
            restoreBackground(holder, position, rebind = false)
            return
        }

        val transition = transitionOf(view, highlightRes, normalRes)
        view.background = transition
        val fadeOut = ValueAnimator.ofInt(0, 1)
        fadeOut.duration = FADE_OUT_DURATION_MS.toLong()
        fadeOut.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    transition.startTransition(FADE_OUT_DURATION_MS)
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (backgroundAnimator !== animation) {
                        return
                    }
                    backgroundAnimator = null
                    restoreBackground(holder, position, rebind = true)
                }
            }
        )
        backgroundAnimator = fadeOut
        fadeOut.start()
    }

    private fun cancelBackgroundAnimator() {
        val animator = backgroundAnimator ?: return
        // Cleared first so that its end listener does nothing
        backgroundAnimator = null
        animator.cancel()
    }

    /** Puts back the background the base adapter gives the view. */
    private fun restoreBackground(holder: PreferenceViewHolder, position: Int, rebind: Boolean) {
        val view = holder.itemView
        if (view is DrawableStateLayout) {
            view.setBackgroundResource(R.drawable.settingslib_round_background_stateful)
        } else {
            view.setBackgroundResource(getRoundCornerDrawableRes(position, isSelected = false))
        }
        holder.setIsRecyclable(true)
        // Let the base adapter set up the drawable state of the new background
        if (rebind && position < itemCount) {
            notifyItemChanged(position)
        }
    }

    private fun transitionOf(view: View, fromRes: Int, toRes: Int): TransitionDrawable {
        val context = view.context
        return TransitionDrawable(
                arrayOf(
                    ContextCompat.getDrawable(context, fromRes),
                    ContextCompat.getDrawable(context, toRes),
                )
            )
            .apply {
                // Use the padding of one layer, not the sum of both
                setPaddingMode(LayerDrawable.PADDING_MODE_STACK)
            }
    }

    private companion object {
        // Same timing as the Settings app
        const val COLLAPSE_DELAY_MS = 300L
        const val HIGHLIGHT_DELAY_MS = 600L
        const val HIGHLIGHT_DELAY_A11Y_MS = 300L
        const val HIGHLIGHT_DURATION_MS = 15000L
        const val FADE_IN_DURATION_MS = 200
        const val FADE_OUT_DURATION_MS = 500
        const val BLINK_REPEAT_COUNT = 4
    }
}
