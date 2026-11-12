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

import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceCategory
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

/**
 * Picks the style of [LauncherPrefs.UNLOCK_ANIMATION_STYLE], with a preview of the selected style
 * on top of the choices.
 */
class UnlockAnimationSettingsFragment : SettingsBasePreferenceFragment() {

    private lateinit var preview: UnlockAnimationPreviewPreference
    private val selectors = ArrayList<SelectorWithWidgetPreference>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        preview = UnlockAnimationPreviewPreference(context).apply { key = KEY_PREVIEW }
        screen.addPreference(preview)

        val styles =
            PreferenceCategory(context).apply {
                key = KEY_STYLES
                title = getString(R.string.unlock_animation_style_category)
            }
        // A category must be on the screen before preferences are added to it
        screen.addPreference(styles)

        val labels = resources.getStringArray(R.array.unlock_animation_style_entries)
        val ids = resources.getStringArray(R.array.unlock_animation_style_values)
        val summaries = resources.getStringArray(R.array.unlock_animation_style_summaries)
        selectors.clear()
        ids.forEachIndexed { index, id ->
            val selector =
                SelectorWithWidgetPreference(context).apply {
                    key = KEY_STYLE_PREFIX + id
                    title = labels[index]
                    summary = summaries.getOrNull(index)
                    isPersistent = false
                    setOnClickListener { select(id) }
                }
            styles.addPreference(selector)
            selectors.add(selector)
        }

        screen.addPreference(
            FooterPreference(context).apply {
                key = KEY_FOOTER
                title = getString(R.string.unlock_animation_style_footer)
            }
        )
        preferenceScreen = screen

        // Unknown ids fall back to the default, see UnlockAnimationStyles.fromId()
        val current = LauncherPrefs.UNLOCK_ANIMATION_STYLE.get(context)
        showSelection(if (current in ids) current else UnlockAnimationStyles.ID_NONE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.pref_unlock_animation_style_title)

        // Same as the main settings page, which draws behind the navigation bar
        val listView = listView
        val bottomPadding = listView.paddingBottom
        listView.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                bottomPadding + insets.systemWindowInsetBottom,
            )
            insets.consumeSystemWindowInsets()
        }
        view.textDirection = View.TEXT_DIRECTION_LOCALE
    }

    private fun select(id: String) {
        LauncherPrefs.get(requireContext()).put(LauncherPrefs.UNLOCK_ANIMATION_STYLE, id)
        showSelection(id)
    }

    private fun showSelection(id: String) {
        selectors.forEach { it.isChecked = it.key == KEY_STYLE_PREFIX + id }
        preview.styleId = id
    }

    companion object {
        private const val KEY_PREVIEW = "unlock_animation_preview"
        private const val KEY_STYLES = "unlock_animation_styles"
        private const val KEY_STYLE_PREFIX = "unlock_animation_style_"
        private const val KEY_FOOTER = "unlock_animation_footer"
    }
}
