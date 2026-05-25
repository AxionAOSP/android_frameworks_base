/*
 * Copyright (C) 2025 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.shared.clocks

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import com.android.systemui.customization.R
import com.android.systemui.plugins.keyguard.ui.clocks.*
import com.android.systemui.shared.clocks.view.BitmapFaceConfig
import com.android.systemui.shared.clocks.view.RenderMode

const val useAxClocks = true

class AxClockProvider(
    private val context: Context,
    private val layoutInflater: LayoutInflater,
    private val resources: Resources,
    private val isClockReactiveVariantsEnabled: Boolean = false,
    private val vibrator: Vibrator?,
) : ClockProvider {

    private val tag = "AxClockProvider"
    private var messageBuffers: ClockMessageBuffers? = null
    
    private data class ClockPlugin(
        val id: String,
        val packageName: String,
        val label: String
    )
    
    private var cachedPlugins: List<ClockPlugin>? = null

    init {
        Log.d(tag, "Initialized AxClockProvider")
        discoverPlugins(context)
    }

    private fun discoverPlugins(context: Context): List<ClockPlugin> {
        val pm = context.packageManager
        val intent = android.content.Intent("com.android.systemui.action.PLUGIN_CLOCK_PROVIDER")
        val resolveInfos = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        
        return resolveInfos.mapNotNull { info ->
            val serviceInfo = info.serviceInfo ?: return@mapNotNull null
            val clockId = serviceInfo.metaData?.getString("com.axion.theme.CLOCK_ID") ?: serviceInfo.packageName
            ClockPlugin(
                id = clockId,
                packageName = serviceInfo.packageName,
                label = serviceInfo.loadLabel(pm).toString()
            )
        }.also { 
            cachedPlugins = it
            Log.d(tag, "Discovered ${it.size} clock plugins: ${it.joinToString { p -> p.id }}")
        }
    }

    private fun getPluginConfig(pm: PackageManager, packageName: String): BitmapFaceConfig? {
        val intent = android.content.Intent("com.android.systemui.action.PLUGIN_CLOCK_PROVIDER")
        val resolveInfo = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
            .firstOrNull { it.serviceInfo.packageName == packageName } ?: return null
        
        val meta = resolveInfo.serviceInfo.metaData ?: return null
        val renderModeStr = meta.getString("com.axion.theme.RENDER_MODE") ?: "BITMAP"
        
        return if (renderModeStr == "FONT") {
            val fontPath = meta.getString("com.axion.theme.FONT_PATH") ?: return null
            BitmapFaceConfig(
                renderMode = RenderMode.FontDigit(
                    fontPath = fontPath,
                    fontSize = meta.getInt("com.axion.theme.FONT_SIZE", 120).toFloat(),
                    lsFontWeight = meta.getInt("com.axion.theme.FONT_WEIGHT", 400),
                    aodFontWeight = meta.getInt("com.axion.theme.AOD_FONT_WEIGHT", 100),
                    largeScale = meta.getFloat("com.axion.theme.LARGE_SCALE", 2.2f)
                )
            )
        } else null
    }

    override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig {
        val clockId = settings.clockId ?: "NTYPE"
        val plugin = cachedPlugins?.find { it.id == clockId }
        
        return ClockPickerConfig(
                clockId,
                plugin?.label ?: resources.getString(R.string.clock_id_general),
                plugin?.label ?: resources.getString(R.string.clock_id_general),
                resources.getDrawable(R.drawable.clock_default_thumbnail, null),
                isReactiveToTone = true,
                axes = emptyList(),
                presetConfig = null,
            )
    }

    override fun createClock(ctx: Context, settings: ClockSettings): AxClockController {
        val clockId = settings.clockId
        val resolvedType = AxClockType.values().firstOrNull {
            clockId == resources.getString(it.clockId)
        }
        
        if (resolvedType != null) {
            return AxClockController(ctx, resolvedType, layoutInflater, messageBuffers)
        }

        // Search for plugins
        val plugin = (cachedPlugins ?: discoverPlugins(ctx)).find { it.id == clockId }
        if (plugin != null) {
            try {
                val pluginCtx = ctx.createPackageContext(plugin.packageName, 
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                
                val customConfig = getPluginConfig(ctx.packageManager, plugin.packageName)
                
                return AxClockController(
                    context = ctx,
                    clockType = AxClockType.NTYPE,
                    layoutInflater = layoutInflater,
                    clockMessageBuffers = messageBuffers,
                    pluginContext = pluginCtx,
                    customConfig = customConfig
                )
            } catch (e: Exception) {
                Log.e(tag, "Failed to load plugin clock: $clockId", e)
            }
        }

        return AxClockController(ctx, AxClockType.NTYPE, layoutInflater, messageBuffers)
    }

    override fun getClocks(): List<ClockMetadata> {
        val systemClocks = AxClockType.values().map { type ->
            ClockMetadata(resources.getString(type.clockId))
        }
        
        val plugins = cachedPlugins ?: discoverPlugins(context)
        val pluginClocks = plugins.map { ClockMetadata(it.id) }

        return systemClocks + pluginClocks
    }

    override fun initialize(buffers: ClockMessageBuffers?) {
        messageBuffers = buffers
    }
}
