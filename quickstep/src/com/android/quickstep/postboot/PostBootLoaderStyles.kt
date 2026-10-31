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
import com.android.launcher3.LauncherPrefs

/**
 * Ids stored in [LauncherPrefs.POST_BOOT_LOADER_STYLE].
 *
 * To add a style: add an id here, handle it in [PostBootLoader], and append the id and its label
 * to `post_boot_loader_style_values` / `post_boot_loader_style_entries`.
 */
object PostBootLoaderStyles {

    /** Only the wallpaper is shown until the home screen is ready, like Settings' FallbackHome. */
    const val ID_NONE = "none"

    /** A blurred, slowly drifting noise cloud with a loading indicator. */
    const val ID_GLOW = "glow"

    /** Whether a loading screen should cover the home screen while it loads after a restart. */
    @JvmStatic
    fun isLoaderEnabled(context: Context): Boolean =
        LauncherPrefs.POST_BOOT_LOADER_STYLE.get(context) == ID_GLOW
}
