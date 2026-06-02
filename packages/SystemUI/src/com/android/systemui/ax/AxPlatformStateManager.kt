/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.ax

import android.database.ExecutorContentObserver
import android.os.Binder
import android.os.Bundle
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import com.android.axion.platform.AxFeatureState
import com.android.axion.platform.AxPlatformFeature
import com.android.axion.platform.IAxPlatformCallback
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class AxPlatformStateManager @Inject constructor(
    private val secureSettings: SecureSettings,
    private val globalSettings: GlobalSettings,
    @Main private val mainExecutor: Executor
) {

    interface LabelProvider {
        fun getLabel(feature: String): String?
        fun getSecondaryLabel(feature: String, state: Bundle): String?
    }

    fun interface StateListener {
        fun onStateChanged(key: String, state: AxFeatureState)
    }

    private val callbacks = RemoteCallbackList<IAxPlatformCallback>()
    private val listeners = ConcurrentHashMap<StateListener, Executor>()
    private val stateCache = ConcurrentHashMap<String, Bundle>()

    var labelProvider: LabelProvider? = null
    var supportedFeatures: Array<String> = emptyArray()
        internal set

    fun getState(feature: String): Bundle =
        stateCache[feature]
            ?: if (feature in supportedFeatures) normalizeState(feature, Bundle()).toBundle()
            else Bundle.EMPTY

    fun getAllStates(): Bundle = Bundle().also { result ->
        supportedFeatures.forEach { result.putBundle(it, getState(it)) }
        stateCache.forEach { (key, bundle) -> result.putBundle(key, bundle) }
    }

    fun registerCallback(callback: IAxPlatformCallback) {
        callbacks.register(callback)
        val states = getAllStates()
        states.keySet().forEach { key ->
            try {
                callback.onStateChanged(key, states.getBundle(key) ?: Bundle.EMPTY)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to send initial state for key=$key", e)
            }
        }
    }

    fun unregisterCallback(callback: IAxPlatformCallback) {
        callbacks.unregister(callback)
    }

    fun addListener(executor: Executor, listener: StateListener) {
        listeners[listener] = executor
        val states = getAllStates()
        states.keySet().forEach { key ->
            executor.execute {
                listener.onStateChanged(key, AxFeatureState.fromBundle(states.getBundle(key)))
            }
        }
    }

    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    fun broadcastState(key: String, state: Bundle) {
        val normalizedState = normalizeState(key, state)
        val normalizedBundle = normalizedState.toBundle()
        val oldState = stateCache[key]
        if (oldState != null && bundlesEqual(oldState, normalizedBundle)) {
            return
        }
        stateCache[key] = normalizedBundle
        listeners.forEach { (listener, executor) ->
            executor.execute { listener.onStateChanged(key, normalizedState) }
        }
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    try {
                        callbacks.getBroadcastItem(i).onStateChanged(key, normalizedBundle)
                    } catch (e: RemoteException) {
                        Log.w(TAG, "Callback failed for key=$key", e)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    fun broadcastFeatureState(key: String, state: AxFeatureState) {
        broadcastState(key, state.toBundle())
    }

    fun broadcastBool(feature: String, active: Boolean) {
        broadcastFeatureState(
            feature,
            AxFeatureState.newBuilder()
                .setEnabled(active)
                .setActive(active)
                .build()
        )
    }

    private fun normalizeState(key: String, state: Bundle): AxFeatureState {
        val normalized = Bundle(state)
        if (AxPlatformFeature.isKnownFeature(key)) {
            if (!normalized.containsKey(AxFeatureState.KEY_FEATURE)) {
                normalized.putString(AxFeatureState.KEY_FEATURE, key)
            }
            if (!normalized.containsKey(AxFeatureState.KEY_TILE_SPEC)) {
                AxPlatformFeature.getPrimaryTileSpec(key)?.let {
                    normalized.putString(AxFeatureState.KEY_TILE_SPEC, it)
                }
            }
            if (!normalized.containsKey(AxFeatureState.KEY_CATEGORY)) {
                AxPlatformFeature.getCategory(key)?.let {
                    normalized.putString(AxFeatureState.KEY_CATEGORY, it)
                }
            }
        }
        if (!normalized.containsKey(AxFeatureState.KEY_TILE_STATE)) {
            val tileState =
                when {
                    !normalized.getBoolean(AxFeatureState.KEY_AVAILABLE, true) ->
                        AxFeatureState.TILE_STATE_UNAVAILABLE
                    normalized.getBoolean(AxFeatureState.KEY_ACTIVE, false) ->
                        AxFeatureState.TILE_STATE_ACTIVE
                    else -> AxFeatureState.TILE_STATE_INACTIVE
                }
            normalized.putInt(AxFeatureState.KEY_TILE_STATE, tileState)
        }
        labelProvider?.let { provider ->
            if (!normalized.containsKey(AxFeatureState.KEY_LABEL)) {
                provider.getLabel(key)?.let { normalized.putString(AxFeatureState.KEY_LABEL, it) }
            }
            if (!normalized.containsKey(AxFeatureState.KEY_SECONDARY_LABEL)) {
                provider.getSecondaryLabel(key, normalized)?.let {
                    normalized.putString(AxFeatureState.KEY_SECONDARY_LABEL, it)
                }
            }
        }
        return AxFeatureState.fromBundle(normalized)
    }

    private fun bundlesEqual(first: Bundle, second: Bundle): Boolean {
        if (first.size() != second.size()) return false
        for (key in first.keySet()) {
            if (!second.containsKey(key)) return false
            if (!bundleValueEquals(first.get(key), second.get(key))) return false
        }
        return true
    }

    private fun bundleValueEquals(first: Any?, second: Any?): Boolean =
        when {
            first is Bundle && second is Bundle -> bundlesEqual(first, second)
            first is IntArray && second is IntArray -> first.contentEquals(second)
            first is LongArray && second is LongArray -> first.contentEquals(second)
            first is BooleanArray && second is BooleanArray -> first.contentEquals(second)
            first is Array<*> && second is Array<*> -> first.contentDeepEquals(second)
            else -> first == second
        }

    fun getSecureBool(key: String, def: Boolean = false): Boolean =
        secureSettings.getIntForUser(key, if (def) 1 else 0, UserHandle.USER_CURRENT) == 1

    fun setSecureBool(key: String, value: Boolean) {
        val token = Binder.clearCallingIdentity()
        try {
            secureSettings.putIntForUser(key, if (value) 1 else 0, UserHandle.USER_CURRENT)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun toggleSecure(key: String) = setSecureBool(key, !getSecureBool(key))

    fun getGlobalBool(key: String, def: Boolean = false): Boolean =
        globalSettings.getInt(key, if (def) 1 else 0) == 1

    fun setGlobalBool(key: String, value: Boolean) {
        val token = Binder.clearCallingIdentity()
        try {
            globalSettings.putInt(key, if (value) 1 else 0)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    fun toggleGlobal(key: String) = setGlobalBool(key, !getGlobalBool(key))

    fun observeSecure(key: String, feature: String) {
        secureSettings.registerContentObserverSync(
            key, object : ExecutorContentObserver(mainExecutor) {
                override fun onChange(selfChange: Boolean) {
                    broadcastBool(feature, getSecureBool(key))
                }
            }
        )
        broadcastBool(feature, getSecureBool(key))
    }

    fun observeGlobal(key: String, feature: String) {
        globalSettings.registerContentObserverSync(
            key, object : ExecutorContentObserver(mainExecutor) {
                override fun onChange(selfChange: Boolean) {
                    broadcastBool(feature, getGlobalBool(key))
                }
            }
        )
        broadcastBool(feature, getGlobalBool(key))
    }

    companion object {
        private const val TAG = "AxPlatformState"
    }
}
