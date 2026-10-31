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
import android.app.ActivityThread
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.app.CoreComponentFactory
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.util.LockedUserState

/**
 * Lets the home activity be direct boot aware without running Launcher before the user unlocks.
 *
 * The home activity is declared direct boot aware so that the system starts it at boot instead of
 * Settings' FallbackHome. While credential encrypted storage is still locked, Launcher can't load
 * its model, so [PostBootActivity] is created in its place. Once the user unlocks, it relaunches
 * itself and this factory creates the real Launcher.
 */
class PostBootComponentFactory : CoreComponentFactory() {

    private var application: Application? = null

    override fun instantiateApplication(cl: ClassLoader, className: String): Application =
        super.instantiateApplication(cl, className).also { application = it }

    override fun instantiateActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent?,
    ): Activity {
        // The application is created through this factory first, the fallback is only defensive
        val app = application ?: ActivityThread.currentApplication()
        if (className != LAUNCHER_CLASS || app == null || LockedUserState.get(app).isUserUnlocked) {
            return super.instantiateActivity(cl, className, intent)
        }
        Log.d(TAG, "User is locked, creating ${PostBootActivity::class.java.simpleName}")
        return super.instantiateActivity(cl, PostBootActivity::class.java.name, intent)
    }

    private companion object {
        const val TAG = "PostBootComponentFactory"
        val LAUNCHER_CLASS: String = QuickstepLauncher::class.java.name
    }
}
