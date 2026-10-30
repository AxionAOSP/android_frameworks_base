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

package com.android.systemui.statusbar.connectivity

import android.content.Context
import android.content.res.Resources
import android.content.res.ThemeEngine
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.widget.ImageView
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.statusbar.core.NewStatusBarIcons
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
object ThemeIconController {

    private val SIGNAL_4BAR_NAMES = arrayOf(
        "ic_signal_cellular_0_4_bar",
        "ic_signal_cellular_1_4_bar",
        "ic_signal_cellular_2_4_bar",
        "ic_signal_cellular_3_4_bar",
        "ic_signal_cellular_4_4_bar",
    )

    private val SIGNAL_5BAR_NAMES = arrayOf(
        "ic_signal_cellular_0_5_bar",
        "ic_signal_cellular_1_5_bar",
        "ic_signal_cellular_2_5_bar",
        "ic_signal_cellular_3_5_bar",
        "ic_signal_cellular_4_5_bar",
        "ic_signal_cellular_5_5_bar",
    )

    private val WIFI_ICON_NAMES = arrayOf(
        "ic_wifi_signal_0",
        "ic_wifi_signal_1",
        "ic_wifi_signal_2",
        "ic_wifi_signal_3",
        "ic_wifi_signal_4",
    )

    private val QS_ICON_CANDIDATES: Map<String, List<String>> = mapOf(
        "qs_airplane_icon_on" to listOf("ic_qs_airplane", "ic_signal_airplane", "stat_sys_airplane_mode"),
        "qs_airplane_icon_off" to listOf("ic_qs_airplane", "ic_signal_airplane", "stat_sys_airplane_mode"),
        "qs_bluetooth_icon_on" to listOf("ic_qs_bluetooth_connected", "ic_bluetooth_connected", "ic_qs_bluetooth_on", "ic_qs_bluetooth"),
        "qs_bluetooth_icon_off" to listOf("ic_qs_bluetooth", "ic_qs_bluetooth_on"),
        "qs_bluetooth_icon_disconnected" to listOf("ic_qs_bluetooth", "ic_qs_bluetooth_on"),
        "qs_bluetooth_icon_search" to listOf("ic_qs_bluetooth_connecting", "ic_qs_bluetooth"),
        "qs_flashlight_icon_on" to listOf("ic_qs_flashlight", "ic_signal_flashlight", "stat_sys_flashlight"),
        "qs_flashlight_icon_off" to listOf("ic_qs_flashlight", "ic_signal_flashlight", "stat_sys_flashlight"),
        "qs_auto_rotate_icon_on" to listOf("ic_qs_auto_rotate"),
        "qs_auto_rotate_icon_off" to listOf("ic_qs_auto_rotate"),
        "qs_battery_saver_icon_on" to listOf("ic_qs_battery_saver", "ic_power_saver"),
        "qs_battery_saver_icon_off" to listOf("ic_qs_battery_saver", "ic_power_saver"),
        "qs_dnd_icon_on" to listOf("ic_qs_dnd", "ic_qs_dnd_on", "stat_sys_dnd"),
        "qs_dnd_icon_off" to listOf("ic_qs_dnd", "ic_qs_dnd_on", "stat_sys_dnd"),
        "ic_zen_priority_modes" to listOf("ic_qs_dnd", "ic_qs_dnd_on", "stat_sys_dnd"),
        "qs_nightlight_icon_on" to listOf("ic_qs_night_display_on"),
        "qs_nightlight_icon_off" to listOf("ic_qs_night_display_on"),
        "qs_light_dark_theme_icon_on" to listOf("ic_qs_ui_mode_night"),
        "qs_light_dark_theme_icon_off" to listOf("ic_qs_ui_mode_night"),
        "qs_hotspot_icon_on" to listOf("ic_hotspot", "stat_sys_hotspot"),
        "qs_hotspot_icon_off" to listOf("ic_hotspot", "stat_sys_hotspot"),
        "qs_hotspot_icon_search" to listOf("ic_hotspot", "stat_sys_hotspot"),
        "qs_data_saver_icon_on" to listOf("ic_data_saver", "stat_sys_data_saver"),
        "qs_data_saver_icon_off" to listOf("ic_data_saver_off", "ic_data_saver"),
        "qs_location_icon_on" to listOf("ic_location", "ic_signal_location", "stat_sys_location"),
        "qs_location_icon_off" to listOf("ic_location", "ic_signal_location", "stat_sys_location"),
        "qs_screen_record_icon_on" to listOf("ic_screenrecord", "ic_screen_recorder"),
        "qs_screen_record_icon_off" to listOf("ic_screenrecord", "ic_screen_recorder"),
        "qs_invert_colors_icon_on" to listOf("ic_invert_colors"),
        "qs_invert_colors_icon_off" to listOf("ic_invert_colors"),
        "qs_camera_access_icon_on" to listOf("ic_camera", "ic_camera_alt_24dp", "stat_sys_camera"),
        "qs_camera_access_icon_off" to listOf("ic_camera", "ic_camera_alt_24dp", "stat_sys_camera"),
        "qs_mic_access_on" to listOf("ic_mic_26dp", "stat_sys_mic_none"),
        "qs_mic_access_off" to listOf("ic_mic_26dp", "stat_sys_mic_none"),
        "qs_screenshot_icon" to listOf("ic_qs_screenshot", "ic_screenshot"),
        "qs_work_mode_icon_on" to listOf("stat_sys_managed_profile_status"),
        "qs_work_mode_icon_off" to listOf("stat_sys_managed_profile_status"),
        "ic_qs_data_switch_sim_1" to listOf("ic_qs_data_switch_1"),
        "ic_qs_data_switch_sim_2" to listOf("ic_qs_data_switch_2"),
        "ic_qs_footer_settings" to listOf("ic_settings", "ic_settings_16dp"),
        "ic_qs_brightness_auto_on" to listOf("ic_qs_brightness_auto_on", "auto_brightness_icon_on"),
        "ic_qs_brightness_auto_off" to listOf("ic_qs_brightness_auto_off", "auto_brightness_icon_off"),
        "ic_brightness_low" to listOf("ic_qs_brightness_low", "ic_brightness_low"),
        "ic_brightness_medium" to listOf("ic_qs_brightness_medium", "ic_brightness_medium"),
        "ic_brightness_full" to listOf("ic_qs_brightness_high", "ic_brightness_full"),
    )

    private data class ThemeSnapshot(
        val signalPkg: String?,
        val wifiPkg: String?,
        val sysuiPackPkg: String?,
        val androidPackPkg: String?,
        val unifiedPkg: String?,
    ) {
        val signalCandidates: List<String> =
            listOfNotNull(signalPkg, androidPackPkg, sysuiPackPkg, unifiedPkg).distinct()
        val wifiCandidates: List<String> =
            listOfNotNull(wifiPkg, androidPackPkg, sysuiPackPkg, unifiedPkg).distinct()
        val qsCandidates: List<String> =
            listOfNotNull(sysuiPackPkg, androidPackPkg, unifiedPkg).distinct()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _themeVersion = MutableStateFlow(0L)
    val themeVersion: StateFlow<Long> = _themeVersion.asStateFlow()

    private val refreshCallbacks = CopyOnWriteArrayList<Runnable>()
    @Volatile private var wifiResIds: IntArray? = null
    @Volatile private var wifiErrorResIds: IntArray? = null
    @Volatile private var cachedSnapshot: ThemeSnapshot? = null
    @Volatile private var observerRegistered = false
    @Volatile private var appContextRef: Context? = null
    @Volatile private var lastTilesRef: Collection<com.android.systemui.plugins.qs.QSTile>? = null

    private val pkgResourcesCache = ConcurrentHashMap<String, Resources>()
    private val resEntryNameCache = ConcurrentHashMap<Int, String>()
    private val qsBitmapCache = ConcurrentHashMap<Int, Bitmap>()
    private val signalBitmapCache = ConcurrentHashMap<Int, Bitmap>()
    private val wifiBitmapCache = ConcurrentHashMap<Int, Bitmap>()
    private val missingQsResIds = ConcurrentHashMap.newKeySet<Int>()
    private val missingSignalKeys = ConcurrentHashMap.newKeySet<Int>()
    private val missingWifiLevels = ConcurrentHashMap.newKeySet<Int>()

    private val debouncedRefreshRunnable = Runnable {
        val targetTiles = lastTilesRef ?: emptyList()
        for (tile in targetTiles) {
            runCatching { tile.refreshState() }
        }
        for (cb in refreshCallbacks) {
            runCatching { cb.run() }
        }
    }

    private fun ensureObserver(context: Context) {
        if (observerRegistered) return
        synchronized(this) {
            if (observerRegistered) return
            val appCtx = context.applicationContext ?: context
            appContextRef = appCtx
            observerRegistered = true
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    val ctx = appContextRef ?: return
                    val newSnap = readSnapshotFromSettings(ctx)
                    if (newSnap != cachedSnapshot) {
                        cachedSnapshot = newSnap
                        invalidateCachesAndNotify(lastTilesRef ?: emptyList())
                    }
                }
            }
            runCatching {
                appCtx.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(ThemeEngine.SETTINGS_THEME_ENGINE_DATA),
                    false,
                    observer,
                    UserHandle.USER_ALL
                )
                appCtx.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
                    false,
                    observer,
                    UserHandle.USER_ALL
                )
            }
        }
    }

    private fun readSnapshotFromSettings(context: Context): ThemeSnapshot {
        val resolver = context.contentResolver
        var signalPkg: String? = null
        var wifiPkg: String? = null
        var sysuiPackPkg: String? = null
        var androidPackPkg: String? = null
        var unifiedPkg: String? = null

        runCatching {
            val jsonStr = Settings.Secure.getStringForUser(
                resolver,
                ThemeEngine.SETTINGS_THEME_ENGINE_DATA,
                UserHandle.USER_CURRENT
            )
            if (!jsonStr.isNullOrBlank()) {
                val root = JSONObject(jsonStr)
                val catThemes = root.optJSONObject("categoryThemes")
                val themesObj = root.optJSONObject("themes")
                fun resolveCat(vararg keys: String): String? {
                    for (k in keys) {
                        val v = catThemes?.optString(k, "")?.takeIf { it.isNotEmpty() }
                        if (v != null) return v
                        val tObj = themesObj?.optJSONObject(k)
                        if (tObj != null && tObj.optBoolean("enabled", false)) {
                            val tv = tObj.optString("packageName", "").takeIf { it.isNotEmpty() }
                            if (tv != null) return tv
                        }
                    }
                    return null
                }
                signalPkg = resolveCat(
                    "signal",
                    "android.theme.customization.signal_icon",
                    "statusbar_signal"
                )
                wifiPkg = resolveCat(
                    "wifi",
                    "android.theme.customization.wifi_icon",
                    "statusbar_wifi"
                )
                sysuiPackPkg = resolveCat(
                    "android.theme.customization.icon_pack.systemui",
                    "systemui"
                )
                androidPackPkg = resolveCat(
                    "android.theme.customization.icon_pack.android",
                    "android"
                )
                unifiedPkg = root.optString("systemThemeIcons", "").takeIf { it.isNotEmpty() }
            }
        }

        runCatching {
            val overlayJsonStr = Settings.Secure.getStringForUser(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                UserHandle.USER_CURRENT
            )
            if (!overlayJsonStr.isNullOrBlank()) {
                val oJson = JSONObject(overlayJsonStr)
                if (signalPkg == null) {
                    signalPkg = oJson.optString("android.theme.customization.signal_icon", "")
                        .takeIf { it.isNotEmpty() }
                }
                if (wifiPkg == null) {
                    wifiPkg = oJson.optString("android.theme.customization.wifi_icon", "")
                        .takeIf { it.isNotEmpty() }
                }
                if (sysuiPackPkg == null) {
                    sysuiPackPkg = oJson.optString("android.theme.customization.icon_pack.systemui", "")
                        .takeIf { it.isNotEmpty() }
                }
                if (androidPackPkg == null) {
                    androidPackPkg = oJson.optString("android.theme.customization.icon_pack.android", "")
                        .takeIf { it.isNotEmpty() }
                }
            }
        }

        return ThemeSnapshot(
            signalPkg = signalPkg,
            wifiPkg = wifiPkg,
            sysuiPackPkg = sysuiPackPkg,
            androidPackPkg = androidPackPkg,
            unifiedPkg = unifiedPkg,
        )
    }

    private fun getSnapshot(context: Context): ThemeSnapshot {
        ensureObserver(context)
        cachedSnapshot?.let { return it }
        return readSnapshotFromSettings(context).also { cachedSnapshot = it }
    }

    private fun getPackageResources(context: Context, pkg: String): Resources? {
        pkgResourcesCache[pkg]?.let { return it }
        return runCatching {
            context.packageManager.getResourcesForApplication(pkg).also {
                pkgResourcesCache[pkg] = it
            }
        }.getOrNull()
    }

    private fun loadDrawableFromPackages(
        context: Context,
        packages: List<String>,
        drawableNames: List<String>
    ): Drawable? {
        for (pkg in packages) {
            val res = getPackageResources(context, pkg) ?: continue
            for (name in drawableNames) {
                val id = res.getIdentifier(name, "drawable", pkg)
                if (id != 0) {
                    val d = runCatching { res.getDrawable(id, context.theme)?.mutate() }.getOrNull()
                    if (d != null) return d
                }
            }
        }
        return null
    }

    @JvmStatic
    fun registerRefreshCallback(callback: Runnable) {
        refreshCallbacks.add(callback)
    }

    @JvmStatic
    fun unregisterRefreshCallback(callback: Runnable) {
        refreshCallbacks.remove(callback)
    }

    private fun invalidateCachesAndNotify(tiles: Collection<com.android.systemui.plugins.qs.QSTile>) {
        if (tiles.isNotEmpty()) {
            lastTilesRef = tiles
        }
        pkgResourcesCache.clear()
        qsBitmapCache.clear()
        signalBitmapCache.clear()
        wifiBitmapCache.clear()
        missingQsResIds.clear()
        missingSignalKeys.clear()
        missingWifiLevels.clear()
        QSTileImpl.ResourceIcon.clearCache()
        _themeVersion.value++

        mainHandler.removeCallbacks(debouncedRefreshRunnable)
        mainHandler.postDelayed(debouncedRefreshRunnable, 32L)
    }

    @JvmStatic
    fun onThemeChanged(tiles: Collection<com.android.systemui.plugins.qs.QSTile>) {
        cachedSnapshot = appContextRef?.let { readSnapshotFromSettings(it) }
        invalidateCachesAndNotify(tiles)
    }

    @JvmStatic
    fun getThemedSignalIcon(context: Context, level: Int, numLevels: Int): Drawable? {
        val snapshot = getSnapshot(context)
        val candidates = snapshot.signalCandidates
        if (candidates.isEmpty()) return null

        val primaryNames = if (numLevels > 5) SIGNAL_5BAR_NAMES else SIGNAL_4BAR_NAMES
        if (level < 0 || level >= primaryNames.size) return null

        val cacheKey = (level shl 4) or (numLevels and 0x0f)
        if (missingSignalKeys.contains(cacheKey)) return null
        signalBitmapCache[cacheKey]?.let {
            if (!it.isRecycled) return BitmapDrawable(context.resources, it)
        }

        val fallback4Bar = SIGNAL_4BAR_NAMES[level.coerceIn(0, SIGNAL_4BAR_NAMES.lastIndex)]
        val drawableNames = if (primaryNames[level] == fallback4Bar) {
            listOf(primaryNames[level])
        } else {
            listOf(primaryNames[level], fallback4Bar)
        }

        val d = loadDrawableFromPackages(context, candidates, drawableNames)
        if (d == null) {
            missingSignalKeys.add(cacheKey)
            return null
        }
        val bmp = scaleDrawableToBitmap(d, getSignalIconSize(context))
        signalBitmapCache[cacheKey] = bmp
        return BitmapDrawable(context.resources, bmp)
    }

    @JvmStatic
    fun getThemedQsSignalIcon(context: Context, level: Int, numLevels: Int): Drawable? {
        val snapshot = getSnapshot(context)
        val candidates = snapshot.signalCandidates
        if (candidates.isEmpty()) return null

        val primaryNames = if (numLevels > 5) SIGNAL_5BAR_NAMES else SIGNAL_4BAR_NAMES
        if (level < 0 || level >= primaryNames.size) return null

        val cacheKey = 0x1000 or (level shl 4) or (numLevels and 0x0f)
        if (missingSignalKeys.contains(cacheKey)) return null
        signalBitmapCache[cacheKey]?.let {
            if (!it.isRecycled) return BitmapDrawable(context.resources, it)
        }

        val fallback4Bar = SIGNAL_4BAR_NAMES[level.coerceIn(0, SIGNAL_4BAR_NAMES.lastIndex)]
        val drawableNames = if (primaryNames[level] == fallback4Bar) {
            listOf(primaryNames[level])
        } else {
            listOf(primaryNames[level], fallback4Bar)
        }

        val d = loadDrawableFromPackages(context, candidates, drawableNames)
        if (d == null) {
            missingSignalKeys.add(cacheKey)
            return null
        }
        val bmp = renderQsIconBitmap(d)
        signalBitmapCache[cacheKey] = bmp
        return BitmapDrawable(context.resources, bmp)
    }

    @JvmStatic
    fun hasThemedSignalIcons(context: Context): Boolean {
        val snapshot = getSnapshot(context)
        val candidates = snapshot.signalCandidates
        if (candidates.isEmpty()) return false
        return getThemedSignalIcon(context, 0, 4) != null
    }

    @JvmStatic
    fun getThemedWifiIcon(context: Context, resId: Int): Drawable? {
        val snapshot = getSnapshot(context)
        val candidates = snapshot.wifiCandidates
        if (candidates.isEmpty()) return null

        val level = mapWifiResIdToLevel(context, resId)
        if (level < 0) return null
        if (missingWifiLevels.contains(level)) return null
        wifiBitmapCache[level]?.let {
            if (!it.isRecycled) return BitmapDrawable(context.resources, it)
        }

        val d = loadDrawableFromPackages(
            context,
            candidates,
            listOf(WIFI_ICON_NAMES[level], "ic_qs_wifi_$level")
        )
        if (d == null) {
            missingWifiLevels.add(level)
            return null
        }
        val bmp = scaleDrawableToBitmap(d, getWifiIconSize(context))
        wifiBitmapCache[level] = bmp
        return BitmapDrawable(context.resources, bmp)
    }

    @JvmStatic
    fun getThemedQsIcon(context: Context, resId: Int): Drawable? {
        if (resId == 0) return null
        val snapshot = getSnapshot(context)
        if (snapshot.qsCandidates.isEmpty() &&
            snapshot.wifiCandidates.isEmpty() &&
            snapshot.signalCandidates.isEmpty()
        ) {
            return null
        }
        if (missingQsResIds.contains(resId)) return null
        qsBitmapCache[resId]?.let {
            if (!it.isRecycled) return BitmapDrawable(context.resources, it)
        }

        val wifiLevel = mapWifiResIdToLevel(context, resId)
        if (wifiLevel >= 0) {
            val wifiPkgs = snapshot.wifiCandidates
            if (wifiPkgs.isEmpty()) {
                missingQsResIds.add(resId)
                return null
            }
            val raw = loadDrawableFromPackages(
                context,
                wifiPkgs,
                listOf(WIFI_ICON_NAMES[wifiLevel], "ic_qs_wifi_$wifiLevel")
            )
            if (raw == null) {
                missingQsResIds.add(resId)
                return null
            }
            val bmp = renderQsIconBitmap(raw)
            qsBitmapCache[resId] = bmp
            return BitmapDrawable(context.resources, bmp)
        }

        val entryName = resEntryNameCache.getOrPut(resId) {
            runCatching { context.resources.getResourceEntryName(resId) }.getOrDefault("")
        }
        if (entryName.isEmpty()) {
            missingQsResIds.add(resId)
            return null
        }

        if (entryName.startsWith("ic_mobile_") || entryName == "ic_signal_mobile_data_off") {
            val sigPkgs = snapshot.signalCandidates
            if (sigPkgs.isEmpty()) {
                missingQsResIds.add(resId)
                return null
            }
            val sigName = if (entryName == "ic_signal_mobile_data_off") {
                "ic_signal_cellular_0_4_bar"
            } else {
                entryName.replace("ic_mobile_", "ic_signal_cellular_")
            }
            val raw = loadDrawableFromPackages(
                context,
                sigPkgs,
                listOf(sigName, "ic_signal_cellular_4_4_bar")
            )
            if (raw == null) {
                missingQsResIds.add(resId)
                return null
            }
            val bmp = renderQsIconBitmap(raw)
            qsBitmapCache[resId] = bmp
            return BitmapDrawable(context.resources, bmp)
        }

        val qsPkgs = snapshot.qsCandidates
        if (qsPkgs.isEmpty()) {
            missingQsResIds.add(resId)
            return null
        }

        val candidates = (listOf(entryName) + (QS_ICON_CANDIDATES[entryName] ?: emptyList())).distinct()
        val raw = loadDrawableFromPackages(context, qsPkgs, candidates.distinct())
        if (raw == null) {
            missingQsResIds.add(resId)
            return null
        }
        val bmp = renderQsIconBitmap(raw)
        qsBitmapCache[resId] = bmp
        return BitmapDrawable(context.resources, bmp)
    }

    private fun renderQsIconBitmap(d: Drawable): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val mutated = d.mutate()
        mutated.colorFilter = null
        mutated.setTint(Color.WHITE)
        val oldBounds = Rect(mutated.bounds)
        mutated.setBounds(0, 0, size, size)
        mutated.draw(canvas)
        mutated.setBounds(oldBounds)
        return bitmap
    }
    @JvmStatic
    fun applyThemedSignalIcon(
        context: Context, iconView: ImageView, parentGroup: android.view.ViewGroup,
        level: Int, numLevels: Int, fallback: Runnable
    ) {
        val themed = getThemedSignalIcon(context, level, numLevels)
        if (themed != null) {
            iconView.setImageDrawable(themed)
        } else {
            fallback.run()
        }
        parentGroup.invalidate()
    }

    private fun mapWifiResIdToLevel(context: Context, resId: Int): Int {
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_0) return 0
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_1) return 1
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_2) return 2
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_3) return 3
        if (resId == com.android.internal.R.drawable.ic_wifi_signal_4) return 4
        if (resId == com.android.settingslib.R.drawable.ic_no_internet_wifi_signal_0) return 0
        if (resId == com.android.settingslib.R.drawable.ic_no_internet_wifi_signal_1) return 1
        if (resId == com.android.settingslib.R.drawable.ic_no_internet_wifi_signal_2) return 2
        if (resId == com.android.settingslib.R.drawable.ic_no_internet_wifi_signal_3) return 3
        if (resId == com.android.settingslib.R.drawable.ic_no_internet_wifi_signal_4) return 4
        if (resId == R.drawable.ic_qs_no_internet_available ||
            resId == R.drawable.ic_qs_no_internet_unavailable ||
            resId == R.drawable.ic_signal_wifi_off
        ) {
            return 0
        }

        if (wifiResIds == null) {
            val res = context.resources
            val pkg = context.packageName
            wifiResIds = intArrayOf(
                res.getIdentifier("ic_wifi_0", "drawable", pkg),
                res.getIdentifier("ic_wifi_1", "drawable", pkg),
                res.getIdentifier("ic_wifi_2", "drawable", pkg),
                res.getIdentifier("ic_wifi_3", "drawable", pkg),
            )
            wifiErrorResIds = intArrayOf(
                res.getIdentifier("ic_wifi_0_error", "drawable", pkg),
                res.getIdentifier("ic_wifi_1_error", "drawable", pkg),
                res.getIdentifier("ic_wifi_2_error", "drawable", pkg),
                res.getIdentifier("ic_wifi_3_error", "drawable", pkg),
            )
        }
        val levelMap = intArrayOf(0, 1, 3, 4)
        val ids = wifiResIds
        if (ids != null) {
            for (i in ids.indices) {
                if (ids[i] != 0 && ids[i] == resId) return levelMap[i]
            }
        }
        val errIds = wifiErrorResIds
        if (errIds != null) {
            for (i in errIds.indices) {
                if (errIds[i] != 0 && errIds[i] == resId) return levelMap[i]
            }
        }
        return -1
    }

    private fun getSignalIconSize(context: Context): IconSize {
        if (NewStatusBarIcons.isEnabled) {
            return getIconSize(
                context,
                R.dimen.status_bar_signal_overlay_width,
                R.dimen.status_bar_signal_overlay_height,
            )
        }
        val size = context.resources.getDimensionPixelSize(R.dimen.status_bar_mobile_signal_size)
        return IconSize(size, size)
    }

    private fun getWifiIconSize(context: Context): IconSize {
        if (NewStatusBarIcons.isEnabled) {
            return getIconSize(
                context,
                R.dimen.status_bar_wifi_overlay_width,
                R.dimen.status_bar_wifi_overlay_height,
            )
        }
        val size = context.resources.getDimensionPixelSize(R.dimen.status_bar_wifi_signal_size)
        return IconSize(size, size)
    }

    private fun getIconSize(context: Context, widthRes: Int, heightRes: Int): IconSize =
        IconSize(
            context.resources.getDimensionPixelSize(widthRes),
            context.resources.getDimensionPixelSize(heightRes),
        )

    private fun scaleDrawableToBitmap(d: Drawable, size: IconSize): Bitmap {
        val width = size.width.coerceAtLeast(1)
        val height = size.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val intrinsicWidth = if (d.intrinsicWidth > 0) d.intrinsicWidth else width
        val intrinsicHeight = if (d.intrinsicHeight > 0) d.intrinsicHeight else height
        val scale = min(width.toFloat() / intrinsicWidth, height.toFloat() / intrinsicHeight)
        val scaledWidth = (intrinsicWidth * scale).roundToInt().coerceIn(1, width)
        val scaledHeight = (intrinsicHeight * scale).roundToInt().coerceIn(1, height)
        val left = (width - scaledWidth) / 2
        val top = (height - scaledHeight) / 2
        val oldBounds = Rect(d.bounds)
        d.setBounds(left, top, left + scaledWidth, top + scaledHeight)
        d.draw(canvas)
        d.setBounds(oldBounds)
        return bitmap.trimTransparentPadding()
    }

    private fun Bitmap.trimTransparentPadding(): Bitmap {
        val bounds = findOpaqueBounds() ?: return this
        if (
            bounds.left == 0 &&
                bounds.top == 0 &&
                bounds.right == width &&
                bounds.bottom == height
        ) {
            return this
        }
        val cropped = Bitmap.createBitmap(
            this,
            bounds.left,
            bounds.top,
            bounds.width(),
            bounds.height(),
        )
        if (cropped !== this && !isRecycled) {
            recycle()
        }
        return cropped
    }

    private fun Bitmap.findOpaqueBounds(): Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        val pixels = IntArray(width)
        for (y in 0 until height) {
            getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if ((pixels[x] ushr 24) == 0) {
                    continue
                }
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) {
            return null
        }
        return Rect(left, top, right + 1, bottom + 1)
    }

    private data class IconSize(val width: Int, val height: Int)
}
