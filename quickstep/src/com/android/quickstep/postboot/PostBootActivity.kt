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
import android.app.KeyguardManager
import android.os.Bundle
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import com.android.launcher3.util.LockedUserState
import com.android.quickstep.OverviewComponentObserver

/**
 * Stands in for the home activity while the user is still locked after a restart, see
 * [PostBootComponentFactory]. It has no content of its own, so only the wallpaper is visible,
 * optionally covered by a [PostBootLoader] that stays up until the real home screen is ready.
 */
class PostBootActivity : Activity() {

    private val onUserUnlocked = Runnable { switchToHome() }

    // Without a credential the user is unlocked automatically at boot, so Launcher is usually
    // ready before the keyguard is dismissed and SystemUI plays the unlock animation as usual
    private var isDeviceSecure = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        isDeviceSecure = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        PostBootReveal.start(application, onlyIfLate = !isDeviceSecure)
        LockedUserState.get(this).runOnUserUnlocked(action = onUserUnlocked)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (
            isDeviceSecure &&
                PostBootLoader.current == null &&
                PostBootLoaderStyles.isLoaderEnabled(this)
        ) {
            PostBootLoader.show(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LockedUserState.get(this).removeOnUserUnlockedRunnable(onUserUnlocked)
        // A relaunch into Launcher keeps the loader, anything else would leave it orphaned
        if (!isChangingConfigurations) {
            PostBootLoader.current?.removeNow()
            PostBootReveal.current?.stop()
        }
    }

    private fun switchToHome() {
        if (isDestroyed) return
        val overviewObserver = OverviewComponentObserver.INSTANCE.get(this)
        if (overviewObserver.isHomeAndOverviewSame) {
            // This activity is the home component, relaunching it creates the real Launcher
            Log.d(TAG, "User unlocked, relaunching as Launcher")
            recreate()
            return
        }
        // Another app handles recents and is the home activity, hand over once it is covered
        PostBootReveal.current?.stop()
        val homeIntent = overviewObserver.getHomeIntent(DEFAULT_DISPLAY)
        val launchHome = Runnable {
            startActivity(homeIntent)
            finish()
        }
        val loader = PostBootLoader.current
        if (loader == null) launchHome.run() else loader.dismiss().add(launchHome)
    }

    private companion object {
        const val TAG = "PostBootActivity"
    }
}
