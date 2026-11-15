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

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.preference.PreferenceCategory
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.quickstep.unlock.UnlockAnimationPreviewPreference
import com.android.settingslib.widget.BannerMessagePreference
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

/**
 * Picks the style of [LauncherPrefs.POST_BOOT_LOADER_STYLE], with a preview on top that goes from
 * the lock screen through the loading screen to the home screen, which comes in with the selected
 * unlock animation.
 *
 * The loading screen needs a screen lock: without one the user is unlocked at boot, before the
 * home screen exists. The choice is then kept but can't be changed, and a banner explains why.
 */
class PostBootLoaderSettingsFragment : SettingsBasePreferenceFragment() {

    private lateinit var preview: UnlockAnimationPreviewPreference
    private lateinit var lockBanner: BannerMessagePreference
    private val selectors = ArrayList<SelectorWithWidgetPreference>()
    private var shownStyleId: String? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        preview =
            UnlockAnimationPreviewPreference(context).apply {
                key = KEY_PREVIEW
                previewDescription = getString(R.string.post_boot_loader_preview_description)
            }
        screen.addPreference(preview)

        // Like other pages of Settings, the banner sits above the choices it is about
        lockBanner =
            BannerMessagePreference(context).apply {
                key = KEY_LOCK_BANNER
                title = getString(R.string.pref_post_boot_loader_style_requires_lock)
                summary = getString(R.string.post_boot_loader_requires_lock_summary)
                setAttentionLevel(BannerMessagePreference.AttentionLevel.NORMAL)
                setPositiveButtonText(R.string.post_boot_loader_set_screen_lock)
                setPositiveButtonOnClickListener { openScreenLockSettings() }
                isVisible = false
            }
        screen.addPreference(lockBanner)

        val styles =
            PreferenceCategory(context).apply {
                key = KEY_STYLES
                title = getString(R.string.unlock_animation_style_category)
            }
        // A category must be on the screen before preferences are added to it
        screen.addPreference(styles)

        val labels = resources.getStringArray(R.array.post_boot_loader_style_entries)
        val ids = resources.getStringArray(R.array.post_boot_loader_style_values)
        val summaries = resources.getStringArray(R.array.post_boot_loader_style_summaries)
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
                title = getString(R.string.post_boot_loader_style_footer)
            }
        )
        preferenceScreen = screen

        // Read from device protected storage, see LauncherPrefs.POST_BOOT_LOADER_STYLE
        val current = LauncherPrefs.POST_BOOT_LOADER_STYLE.get(context)
        showSelection(if (current in ids) current else PostBootLoaderStyles.ID_NONE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.pref_post_boot_loader_style_title)

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

    override fun onResume() {
        super.onResume()
        // A screen lock may have been set or removed while away, e.g. from the banner
        val secure = isDeviceSecure()
        lockBanner.isVisible = !secure
        selectors.forEach { it.isEnabled = secure }
        // The home screen comes in with the unlock animation, which is picked on another page
        preview.styleId = LauncherPrefs.UNLOCK_ANIMATION_STYLE.get(requireContext())
    }

    private fun select(id: String) {
        LauncherPrefs.get(requireContext()).put(LauncherPrefs.POST_BOOT_LOADER_STYLE, id)
        showSelection(id)
    }

    private fun showSelection(id: String) {
        selectors.forEach { it.isChecked = it.key == KEY_STYLE_PREFIX + id }
        if (id != shownStyleId) {
            shownStyleId = id
            preview.interlude = PostBootLoaderInterlude(requireContext(), id)
        }
    }

    private fun isDeviceSecure(): Boolean =
        requireContext().getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun openScreenLockSettings() {
        try {
            startActivity(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No screen lock settings", e)
        }
    }

    companion object {
        private const val TAG = "PostBootLoaderSettings"
        private const val KEY_PREVIEW = "post_boot_loader_preview"
        private const val KEY_LOCK_BANNER = "post_boot_loader_lock_banner"
        private const val KEY_STYLES = "post_boot_loader_styles"
        private const val KEY_STYLE_PREFIX = "post_boot_loader_style_"
        private const val KEY_FOOTER = "post_boot_loader_footer"
    }
}
