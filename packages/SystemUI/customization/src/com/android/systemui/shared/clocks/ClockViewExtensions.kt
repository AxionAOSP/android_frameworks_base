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

package com.android.systemui.shared.clocks.extensions

import android.content.Context
import android.graphics.Bitmap
import android.util.SparseArray
import android.view.View
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.customization.R

private val viewCacheKey = Any() 

private const val MAX_SCALE_RATIO = 1.0f

val Context.scaleRatio: Float
    get() {
        val displayMetrics = resources.displayMetrics
        val sw = minOf(displayMetrics.widthPixels, displayMetrics.heightPixels) / displayMetrics.density
        val ratio = sw / 420f
        return minOf(ratio, MAX_SCALE_RATIO)
    }

fun Context.scaledDimen(resId: Int): Float {
    return resources.getDimension(resId) * scaleRatio
}

fun Context.scaledDimenInt(resId: Int): Int {
    return scaledDimen(resId).toInt()
}

fun createBitmaps(context: Context, resIds: IntArray): List<Bitmap?> {
    return resIds.map { resId ->
        ContextCompat.getDrawable(context, resId)?.toBitmap()
    }
}

inline fun <reified T : View> View.bindView(@IdRes id: Int): T {
    val cache = getTag(R.id.view_cache_key) as? SparseArray<View>
        ?: SparseArray<View>().also { setTag(R.id.view_cache_key, it) }

    val view = cache.get(id) ?: findViewById<T>(id)!!.also { cache.put(id, it) }
    return view as T
}

