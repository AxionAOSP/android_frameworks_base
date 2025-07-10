/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.systemui.util

import android.app.AppLockManager
import android.content.Context
import android.os.RemoteException
import android.util.Log
import com.android.internal.app.IAppLockListener
import com.android.systemui.shade.QuickSettingsControllerImpl
import java.util.concurrent.ConcurrentHashMap

class NTAppLockerHelper private constructor(context: Context) {

    companion object {
        private const val TAG = "NTAppLockerHelper"

        @Volatile
        private var instance: NTAppLockerHelper? = null

        @JvmStatic
        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = NTAppLockerHelper(context.applicationContext)
                    }
                }
            }
        }

        @JvmStatic
        fun get(): NTAppLockerHelper {
            return instance ?: throw IllegalStateException(
                "NTAppLockerHelper is not initialized. Call init(context) before get()."
            )
        }
    }

    private val appLockCache = ConcurrentHashMap<String, Boolean>()
    private var qsController: QuickSettingsControllerImpl? = null
    private var lastExpandedState: Boolean = false
    private var listenerRegistered = false

    private val appLockManager: AppLockManager? by lazy {
        context.getSystemService(Context.APP_LOCK_SERVICE) as? AppLockManager
    }

    private val listener = object : IAppLockListener.Stub() {
        override fun onAppLockerUpdated() {
            clearAppLockedCache()
            qsController?.onAppLockerUpdated()
        }
    }

    fun isAppLocked(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        appLockCache[packageName]?.let { return it }
        val locked = try {
            appLockManager?.isAppLocked(packageName) ?: false
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException in isAppLocked: ${e.message}")
            false
        }
        appLockCache[packageName] = locked
        return locked
    }

    fun isAppLockedWithoutCache(packageName: String): Boolean {
        return try {
            appLockManager?.isAppLocked(packageName) ?: false
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException in isAppLockedWithoutCache: ${e.message}")
            false
        }
    }

    fun clearAppLockedCache() {
        appLockCache.clear()
    }

    fun onPanelExpandedChanged(expanded: Boolean) {
        lastExpandedState = expanded
        if (expanded) {
            clearAppLockedCache()
        }
    }

    fun setQsController(controller: QuickSettingsControllerImpl) {
        qsController = controller
    }

    fun registerListener() {
        if (!listenerRegistered) {
            try {
                appLockManager?.registerListener(listener)
                listenerRegistered = true
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to register listener: ${e.message}")
            }
        }
    }

    fun onDestroy() {
        try {
            if (listenerRegistered) {
                appLockManager?.unregisterListener(listener)
                listenerRegistered = false
            }
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to unregister listener: ${e.message}")
        }
        clearAppLockedCache()
    }
}
