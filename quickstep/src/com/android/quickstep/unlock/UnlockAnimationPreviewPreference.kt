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

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.launcher3.R
import com.android.settingslib.widget.GroupSectionDividerMixin

/**
 * A card on top of the unlock animation settings that keeps playing the selected style, from the
 * locked state to the home screen. Tapping it pauses or resumes the animation.
 */
class UnlockAnimationPreviewPreference(context: Context) :
    Preference(context), GroupSectionDividerMixin {

    /** Id from [UnlockAnimationStyles] of the style to preview. */
    var styleId: String = UnlockAnimationStyles.ID_NONE
        set(value) {
            field = value
            boundPreview?.styleId = value
        }

    /** The preview currently bound, if any, so that selection changes don't rebind the card. */
    private var boundPreview: UnlockStylePreviewView? = null

    init {
        layoutResource = R.layout.unlock_animation_preview_preference
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false

        val preview = holder.findViewById(R.id.unlock_animation_preview) as UnlockStylePreviewView
        boundPreview = preview
        preview.styleId = styleId

        val card = holder.findViewById(R.id.unlock_animation_preview_card) as View
        // Like illustrations in Settings, tapping pauses or resumes the animation.
        card.setOnClickListener { preview.togglePause() }
        card.setAccessibilityDelegate(
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    // Nothing to pause when animations are turned off, so don't offer a tap
                    if (!preview.isPlaying) {
                        info.removeAction(AccessibilityAction.ACTION_CLICK)
                        info.isClickable = false
                        return
                    }
                    val label =
                        if (preview.isPaused) R.string.unlock_animation_preview_resume
                        else R.string.unlock_animation_preview_pause
                    info.addAction(
                        AccessibilityAction(
                            AccessibilityAction.ACTION_CLICK.id,
                            context.getString(label),
                        )
                    )
                }
            }
        )
    }
}
