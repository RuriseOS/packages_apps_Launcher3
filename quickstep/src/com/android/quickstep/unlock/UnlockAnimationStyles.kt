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

/**
 * Maps the values stored in [com.android.launcher3.LauncherPrefs.UNLOCK_ANIMATION_STYLE] to
 * [UnlockAnimationStyle] implementations.
 *
 * To add a style: add an id here, return its implementation from [fromId], and append the id and
 * its label to `unlock_animation_style_values` / `unlock_animation_style_entries`.
 */
object UnlockAnimationStyles {

    /** No Launcher-side animation, SystemUI plays its default unlock transition (AOSP). */
    const val ID_NONE = "none"
    const val ID_RING = "ring"

    /** Returns the style for [id], or null when Launcher should not take part in the unlock. */
    @JvmStatic
    fun fromId(id: String?): UnlockAnimationStyle? =
        when (id) {
            ID_RING -> RingAppearStyle()
            else -> null
        }
}
