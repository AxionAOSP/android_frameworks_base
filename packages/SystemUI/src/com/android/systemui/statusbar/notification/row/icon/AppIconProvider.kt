/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row.icon

import android.annotation.WorkerThread
import android.app.ActivityManager
import android.app.Flags
import android.app.Flags.notificationsRedesignThemedAppIcons
import android.content.Context
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.util.Log
import com.android.internal.R
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.BaseIconFactory.IconOptions
import com.android.launcher3.icons.BitmapInfo
import com.android.launcher3.icons.mono.MonoIconThemeController
import com.android.launcher3.util.UserIconInfo
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.collection.NotifCollectionCache
import com.android.systemui.util.asIndenting
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.withIncreasedIndent
import dagger.Module
import dagger.Provides
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider

/** A provider used to cache and fetch app icons used by notifications. */
interface AppIconProvider {
    /**
     * Loads the icon corresponding to [packageName] into cache, or fetches it from there if already
     * present. This should only be called from the background.
     */
    @Throws(NameNotFoundException::class)
    @WorkerThread
    fun getOrFetchAppIcon(
        packageName: String,
        context: Context,
        withWorkProfileBadge: Boolean = false,
        themed: Boolean = false,
    ): Drawable

    /**
     * Loads the skeleton (black and white)-themed icon corresponding to [packageName] into cache,
     * or fetches it from there if already present. This should only be called from the background.
     *
     * @param packageName the name of the app's package
     * @param context the app's context (NOT SystemUI)
     *
     * TODO: b/416215382 - if we get the SystemUI context here instead of the app's, and the package
     *   is not installed on the main profile, this will throw a [NameNotFoundException]. We should
     *   update the API to take a userId directly to avoid such issues.
     */
    @Throws(NameNotFoundException::class)
    @WorkerThread
    fun getOrFetchSkeletonAppIcon(packageName: String, context: Context): Drawable

    /**
     * Mark all the entries in the cache that are NOT in [wantedPackages] to be cleared. If they're
     * still not needed on the next call of this method (made after a timeout of 1s, in case they
     * happen more frequently than that), they will be purged. This can be done from any thread.
     */
    fun purgeCache(wantedPackages: Collection<String>)
}

@SysUISingleton
class AppIconProviderImpl
@Inject
constructor(
    @ShadeDisplayAware private val sysuiContext: Context,
    dumpManager: DumpManager,
    systemClock: SystemClock,
) : AppIconProvider, Dumpable {
    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    // TODO - b/406484337: Rename non-skeleton members to something like "standardWhatever".

    private val iconSize: Int
        get() =
            sysuiContext.resources.getDimensionPixelSize(
                R.dimen.notification_small_icon_size_low_ram
            )

    private val densityDpi: Int
        get() = sysuiContext.resources.configuration.densityDpi

    private class NotificationIcons(context: Context?, fillResIconDpi: Int, iconBitmapSize: Int) :
        BaseIconFactory(context, fillResIconDpi, iconBitmapSize) {

        init {
            if (notificationsRedesignThemedAppIcons()) {
                // Initialize the controller so that we can support themed icons.
                mThemeController =
                    MonoIconThemeController(
                        shouldForceThemeIcon = true,
                        colorProvider = { ctx ->
                            val res = ctx.resources
                            intArrayOf(
                                /* background */ res.getColor(R.color.materialColorPrimary),
                                /* icon */ res.getColor(R.color.materialColorSurfaceContainerHigh),
                            )
                        },
                    )
            }
        }
    }

    private class SkeletonNotificationIcons(
        context: Context?,
        fillResIconDpi: Int,
        iconBitmapSize: Int,
    ) : BaseIconFactory(context, fillResIconDpi, iconBitmapSize) {
        init {
            mThemeController =
                MonoIconThemeController(
                    shouldForceThemeIcon = true,
                    colorProvider = { _ ->
                        intArrayOf(/* background */ Color.BLACK, /* icon */ Color.WHITE)
                    },
                )
        }
    }

    private val iconFactory: BaseIconFactory
        get() = NotificationIcons(sysuiContext, densityDpi, iconSize)

    private val skeletonIconFactory: BaseIconFactory
        get() = SkeletonNotificationIcons(sysuiContext, densityDpi, iconSize)

    private val cache = NotifCollectionCache<Drawable>(systemClock = systemClock)

    private val skeletonCache = NotifCollectionCache<Drawable>(systemClock = systemClock)

    override fun getOrFetchAppIcon(
        packageName: String,
        context: Context,
        withWorkProfileBadge: Boolean,
        themed: Boolean,
    ): Drawable {
        // Add a suffix to distinguish the app installed on the work profile, since the icon will
        // be different.
        val key = packageName + if (withWorkProfileBadge) WORK_SUFFIX else ""

        return cache.getOrFetch(key) {
            fetchAppIcon(packageName, context, withWorkProfileBadge, themed)
        }
    }

    override fun getOrFetchSkeletonAppIcon(packageName: String, context: Context): Drawable {
        return skeletonCache.getOrFetch(packageName) { fetchSkeletonAppIcon(packageName, context) }
    }

    @WorkerThread
    private fun fetchAppIcon(
        packageName: String,
        context: Context,
        withWorkProfileBadge: Boolean,
        themed: Boolean,
    ): Drawable {
        val pm = context.packageManager
        val icon = pm.getApplicationInfo(packageName, 0).loadUnbadgedIcon(pm)

        val options = iconOptions(context, withWorkProfileBadge)
        val badgedIcon = iconFactory.createBadgedIconBitmap(icon, options)
        val creationFlags = if (themed) BitmapInfo.FLAG_THEMED else 0
        return badgedIcon.newIcon(sysuiContext, creationFlags).apply { isAnimationEnabled = false }
    }

    @WorkerThread
    private fun fetchSkeletonAppIcon(packageName: String, context: Context): Drawable {
        val pm = context.packageManager
        val icon = pm.getApplicationInfo(packageName, 0).loadUnbadgedIcon(pm)

        val options = iconOptions(context, withWorkProfileBadge = false)
        val badgedIcon = skeletonIconFactory.createBadgedIconBitmap(icon, options)
        return badgedIcon.newIcon(sysuiContext, BitmapInfo.FLAG_THEMED)
    }

    private fun iconOptions(context: Context, withWorkProfileBadge: Boolean): IconOptions {
        return IconOptions().apply {
            setUser(userIconInfo(context, withWorkProfileBadge))
            setBitmapGenerationMode(BaseIconFactory.MODE_HARDWARE)
            // This color will not be used, but we're just setting it so that the icon factory
            // doesn't try to extract colors from our bitmap (since it won't work, given it's a
            // hardware bitmap).
            setExtractedColor(Color.BLUE)
        }
    }

    private fun userIconInfo(context: Context, withWorkProfileBadge: Boolean): UserIconInfo {
        val userId = context.userId
        return UserIconInfo(
            UserHandle.of(userId),
            if (withWorkProfileBadge) UserIconInfo.TYPE_WORK else UserIconInfo.TYPE_MAIN,
        )
    }

    override fun purgeCache(wantedPackages: Collection<String>) {
        // We don't know from the packages if it's the work profile app or not, so let's just keep
        // both if they're present in the cache.
        cache.purge(wantedPackages.flatMap { listOf(it, "$it$WORK_SUFFIX") })

        // Skeleton icons don't include profile badges, so we don't need to handle the work profile
        // suffixes.
        skeletonCache.purge(wantedPackages)
    }

    override fun dump(pwOrig: PrintWriter, args: Array<out String>) {
        val pw = pwOrig.asIndenting()

        pw.println("cache information:")
        pw.withIncreasedIndent { cache.dump(pw, args) }

        pw.println("skeleton cache information:")
        pw.withIncreasedIndent { skeletonCache.dump(pw, args) }

        val iconFactory = iconFactory
        pw.println("icon factory information:")
        pw.withIncreasedIndent {
            pw.println("fullResIconDpi = ${iconFactory.fullResIconDpi}")
            pw.println("iconSize = ${iconFactory.iconBitmapSize}")
        }
    }

    companion object {
        const val TAG = "AppIconProviderImpl"
        const val WORK_SUFFIX = "|WORK"
    }
}

class NoOpIconProvider : AppIconProvider {
    companion object {
        const val TAG = "NoOpIconProvider"
    }

    override fun getOrFetchAppIcon(
        packageName: String,
        context: Context,
        withWorkProfileBadge: Boolean,
        themed: Boolean,
    ): Drawable {
        Log.wtf(TAG, "NoOpIconProvider should not be used anywhere.")
        return ColorDrawable(Color.WHITE)
    }

    override fun getOrFetchSkeletonAppIcon(packageName: String, context: Context): Drawable {
        Log.wtf(TAG, "NoOpIconProvider should not be used anywhere.")
        return ColorDrawable(Color.BLACK)
    }

    override fun purgeCache(wantedPackages: Collection<String>) {
        Log.wtf(TAG, "NoOpIconProvider should not be used anywhere.")
    }
}

@Module
class AppIconProviderModule {
    @Provides
    @SysUISingleton
    fun provideImpl(realImpl: Provider<AppIconProviderImpl>): AppIconProvider =
        if (Flags.notificationsRedesignAppIcons()) {
            realImpl.get()
        } else {
            NoOpIconProvider()
        }
}
