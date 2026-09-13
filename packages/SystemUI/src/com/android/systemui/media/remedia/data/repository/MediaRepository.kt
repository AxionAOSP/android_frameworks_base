/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.media.remedia.data.repository

import android.app.WallpaperColors
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.theming.ThemeStyle
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon as AndroidIcon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Color
import com.android.internal.annotations.GuardedBy
import com.android.internal.logging.InstanceId
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.media.controls.data.model.MediaSortKeyModel
import com.android.systemui.media.controls.shared.model.MediaData
import com.android.systemui.media.remedia.data.model.MediaDataModel
import com.android.systemui.media.remedia.data.model.UpdateArtInfoModel
import com.android.systemui.media.remedia.shared.model.MediaColorScheme
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.monet.ColorScheme
import com.android.systemui.res.R
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.time.SystemClock
import java.util.TreeMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A repository that holds the state of current media on the device. */
interface MediaRepository {
    /** Current sorted media sessions. */
    val currentMedia: List<MediaDataModel>

    /** Index of the current visible media session */
    val currentCarouselIndex: Int

    /** Whether media carousel should show first media session. */
    val shouldScrollToFirst: Boolean

    /** Seek to [to], in milliseconds on the media session with the given [sessionKey]. */
    fun seek(sessionKey: InstanceId, to: Long)

    /** Reorders media list when media is not visible to user */
    fun reorderMedia()

    fun storeCarouselIndex(index: Int)

    /** Resets [shouldScrollToFirst] flag. */
    fun resetScrollToFirst()
}

@SysUISingleton
class MediaRepositoryImpl
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background val backgroundDispatcher: CoroutineDispatcher,
    private val systemClock: SystemClock,
    secureSettings: SecureSettings,
) :
    MediaRepository,
    MediaPipelineRepository(
        applicationContext,
        applicationScope,
        backgroundDispatcher,
        secureSettings,
    ) {

    override val currentMedia: SnapshotStateList<MediaDataModel> = mutableStateListOf()

    override var currentCarouselIndex by mutableIntStateOf(0)

    override var shouldScrollToFirst by mutableStateOf(false)

    @GuardedBy("mediaMutex")
    private var sortedMedia = TreeMap<MediaSortKeyModel, MediaDataModel>(comparator)

    // To store active controllers and their callbacks
    private val activeControllers = mutableMapOf<InstanceId, MediaController>()
    private val mediaCallbacks = mutableMapOf<InstanceId, MediaController.Callback>()
    // To store active polling jobs
    private val positionPollers = mutableMapOf<InstanceId, Job>()
    private val mediaMutex = Mutex()

    override fun addCurrentUserMediaEntry(data: MediaData): UpdateArtInfoModel? {
        return super.addCurrentUserMediaEntry(data).also { updateModel ->
            applicationScope.launch {
                mediaMutex.withLock { addToSortedMediaLocked(data, updateModel) }
            }
        }
    }

    override fun removeCurrentUserMediaEntry(key: InstanceId): MediaData? {
        return super.removeCurrentUserMediaEntry(key)?.also {
            applicationScope.launch { mediaMutex.withLock { removeFromSortedMediaLocked(it) } }
        }
    }

    override fun removeCurrentUserMediaEntry(key: InstanceId, data: MediaData): Boolean {
        return super.removeCurrentUserMediaEntry(key, data).also {
            if (it) {
                applicationScope.launch {
                    mediaMutex.withLock { removeFromSortedMediaLocked(data) }
                }
            }
        }
    }

    override fun clearCurrentUserMedia() {
        val userEntries = LinkedHashMap<InstanceId, MediaData>(mutableUserEntries.value)
        mutableUserEntries.value = LinkedHashMap()
        applicationScope.launch {
            mediaMutex.withLock { userEntries.forEach { removeFromSortedMediaLocked(it.value) } }
        }
    }

    override fun seek(sessionKey: InstanceId, to: Long) {
        activeControllers[sessionKey]?.let { controller ->
            controller.transportControls.seekTo(to)
            applicationScope.launch {
                mediaMutex.withLock {
                    currentMedia
                        .find { it.instanceId == sessionKey }
                        ?.let { latestModel ->
                            updateMediaModelInStateLocked(latestModel) { it.copy(positionMs = to) }
                        }
                }
            }
        }
    }

    override fun reorderMedia() {
        applicationScope.launch {
            mediaMutex.withLock {
                currentMedia.clear()
                currentMedia.addAll(sortedMedia.values.toList())
            }
        }
        currentCarouselIndex = 0
    }

    override fun storeCarouselIndex(index: Int) {
        currentCarouselIndex = index
    }

    override fun resetScrollToFirst() {
        shouldScrollToFirst = false
    }

    @GuardedBy("mediaMutex")
    private suspend fun addToSortedMediaLocked(data: MediaData, updateModel: UpdateArtInfoModel?) {
        val sortedMap = TreeMap<MediaSortKeyModel, MediaDataModel>(comparator)
        val currentModel = sortedMedia.values.find { it.instanceId == data.instanceId }

        sortedMap.putAll(sortedMedia.filter { (_, model) -> model.instanceId != data.instanceId })

        mutableUserEntries.value[data.instanceId]?.let { mediaData ->
            with(mediaData) {
                val sortKey =
                    MediaSortKeyModel(
                        isPlaying,
                        playbackLocation,
                        active,
                        resumption,
                        lastActive,
                        notificationKey,
                        systemClock.currentTimeMillis(),
                        instanceId,
                    )
                val controller =
                    if (currentModel != null && currentModel.token == token) {
                        activeControllers[currentModel.instanceId]
                    } else {
                        // Clear controller state if changed for the same media session.
                        currentModel?.instanceId?.let { clearControllerState(it) }
                        token?.let { MediaController(applicationContext, it) }
                    }
                val (icon, background) = getIconAndBackground(mediaData, currentModel, updateModel)
                val mediaModel = toDataModel(controller, icon, background)
                sortedMap[sortKey] = mediaModel
                controller?.let { setupController(mediaModel, it) }

                var isNewToCurrentMedia = true
                val currentList = mutableListOf<MediaDataModel>().apply { addAll(currentMedia) }
                currentList.forEachIndexed { index, mediaDataModel ->
                    if (mediaDataModel.instanceId == data.instanceId) {
                        // When loading an update for an existing media control.
                        isNewToCurrentMedia = false
                        if (mediaDataModel != mediaModel) {
                            // Update media model if changed.
                            currentList[index] = mediaModel
                        }
                    }
                }
                currentMedia.clear()
                if (isNewToCurrentMedia && active) {
                    // New media added is at the top of the current media given its priority.
                    // Media carousel should show the first card in the current media list.
                    shouldScrollToFirst = true
                    currentMedia.addAll(sortedMap.values.toList())
                } else {
                    currentMedia.addAll(currentList)
                }

                sortedMedia = sortedMap
            }
        }
    }

    @GuardedBy("mediaMutex")
    private fun removeFromSortedMediaLocked(data: MediaData) {
        currentMedia.removeIf { model -> data.instanceId == model.instanceId }
        sortedMedia =
            TreeMap<MediaSortKeyModel, MediaDataModel>(comparator).apply {
                putAll(sortedMedia.filter { (_, model) -> model.instanceId != data.instanceId })
            }
        clearControllerState(data.instanceId)
    }

    private suspend fun MediaData.toDataModel(
        controller: MediaController?,
        icon: Icon,
        background: Icon?,
    ): MediaDataModel {
        return withContext(backgroundDispatcher) {
            val metadata = controller?.metadata
            val currentPlaybackState = controller?.playbackState

            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = currentPlaybackState?.position ?: 0L
            val state = currentPlaybackState?.state ?: PlaybackState.STATE_NONE

            val description = metadata?.description
            val metaTitle =
                description?.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                        ?.takeIf { it.isNotBlank() }
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() }
            val metaSubtitle =
                description?.subtitle?.toString()?.takeIf { it.isNotBlank() }
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?.takeIf { it.isNotBlank() }
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                        ?.takeIf { it.isNotBlank() }
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                        ?.takeIf { it.isNotBlank() }

            MediaDataModel(
                instanceId = instanceId,
                appUid = appUid,
                packageName = packageName,
                appName = app.toString(),
                appIcon = icon,
                background = background,
                title = metaTitle ?: song.toString(),
                subtitle = metaSubtitle ?: artist.toString(),
                colorScheme = getScheme(artwork, packageName),
                notificationActions = actions,
                playbackStateActions = semanticActions,
                outputDevice = device,
                clickIntent = clickIntent,
                state =
                    when {
                        NotificationMediaManager.isPlayingState(state) -> MediaSessionState.Playing
                        NotificationMediaManager.isConnectingState(state) ->
                            MediaSessionState.Buffering
                        else -> MediaSessionState.Paused
                    },
                durationMs = duration,
                positionMs = position,
                canBeScrubbed =
                    state != PlaybackState.STATE_NONE &&
                        duration > 0L &&
                        (((currentPlaybackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L),
                canBeDismissed = isClearable,
                isActive = active,
                isResume = resumption,
                resumeAction = resumeAction,
                isExplicit = isExplicit,
                suggestionData = suggestionData,
                token = token,
            )
        }
    }

    private suspend fun getIconAndBackground(
        currentData: MediaData,
        currentModel: MediaDataModel?,
        updateModel: UpdateArtInfoModel?,
    ): Pair<Icon, Icon?> {
        return with(currentData) {
            val icon =
                if (currentModel != null && updateModel?.isAppIconUpdated == false) {
                    currentModel.appIcon
                } else {
                    appIcon?.loadDrawable(applicationContext)?.let { drawable ->
                        Icon.Loaded(drawable, contentDescription = ContentDescription.Loaded(app))
                    } ?: getAltIcon(packageName)
                }
            val background =
                if (currentModel != null && updateModel?.isBackgroundUpdated == false) {
                    currentModel.background
                } else {
                    artwork?.loadDrawable(applicationContext)?.let { drawable ->
                        Icon.Loaded(drawable, contentDescription = null)
                    }
                }
            Pair(icon, background)
        }
    }

    private suspend fun getScheme(
        artwork: android.graphics.drawable.Icon?,
        packageName: String,
    ): MediaColorScheme? {
        val wallpaperColors = getWallpaperColor(applicationContext, backgroundDispatcher, artwork)
        val colorScheme =
            wallpaperColors?.let { ColorScheme(it, false, ThemeStyle.CONTENT) }
                ?: let {
                    val launcherIcon = getAltIcon(packageName)
                    if (launcherIcon is Icon.Loaded) {
                        getColorScheme(launcherIcon.drawable)
                    } else {
                        null
                    }
                }
        return colorScheme?.run {
            MediaColorScheme(
                Color(colorScheme.materialScheme.getPrimaryFixed()),
                Color(colorScheme.materialScheme.getOnPrimaryFixed()),
                Color(colorScheme.materialScheme.getOnSurface()),
            )
        }
    }

    private suspend fun getAltIcon(packageName: String): Icon {
        return withContext(backgroundDispatcher) {
            try {
                val icon = applicationContext.packageManager.getApplicationIcon(packageName)
                Icon.Loaded(icon, null)
            } catch (exception: PackageManager.NameNotFoundException) {
                Icon.Resource(R.drawable.ic_music_note, null)
            }
        }
    }

    /**
     * This method should be called from a background thread. WallpaperColors.fromBitmap is a
     * blocking call.
     */
    private suspend fun getWallpaperColor(
        applicationContext: Context,
        backgroundDispatcher: CoroutineDispatcher,
        artworkIcon: android.graphics.drawable.Icon?,
    ): WallpaperColors? {
        return withContext(backgroundDispatcher) {
            artworkIcon?.let {
                if (
                    it.type == android.graphics.drawable.Icon.TYPE_BITMAP ||
                        it.type == android.graphics.drawable.Icon.TYPE_ADAPTIVE_BITMAP
                ) {
                    // Avoids extra processing if this is already a valid bitmap
                    it.bitmap.let { artworkBitmap ->
                        if (artworkBitmap.isRecycled) {
                            Log.d(TAG, "Cannot load wallpaper color from a recycled bitmap")
                            null
                        } else {
                            WallpaperColors.fromBitmap(artworkBitmap)
                        }
                    }
                } else {
                    it.loadDrawable(applicationContext)?.let { artworkDrawable ->
                        WallpaperColors.fromDrawable(artworkDrawable)
                    }
                }
            }
        }
    }

    /** Returns [ColorScheme] of media app given its [icon]. */
    private fun getColorScheme(icon: Drawable): ColorScheme? {
        return try {
            ColorScheme(WallpaperColors.fromDrawable(icon), false, ThemeStyle.CONTENT)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Fail to get media app info", e)
            null
        }
    }

    private fun setupController(dataModel: MediaDataModel, controller: MediaController) {
        mediaCallbacks[dataModel.instanceId]?.let { controller.unregisterCallback(it) }
        activeControllers[dataModel.instanceId] = controller
        val callback =
            object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state == null || state.state == PlaybackState.STATE_NONE) {
                        positionPollers[dataModel.instanceId]?.cancel()
                        positionPollers.remove(dataModel.instanceId)
                        applicationScope.launch {
                            mediaMutex.withLock {
                                currentMedia
                                    .find { it.instanceId == dataModel.instanceId }
                                    ?.let { latestModel ->
                                        updateMediaModelInStateLocked(latestModel) { model ->
                                            model.copy(
                                                state = MediaSessionState.Paused,
                                                canBeScrubbed = false,
                                            )
                                        }
                                    }
                            }
                        }
                    } else {
                        updatePollingState(dataModel.instanceId, state)
                        applicationScope.launch {
                            mediaMutex.withLock {
                                currentMedia
                                    .find { it.instanceId == dataModel.instanceId }
                                    ?.let { latestModel ->
                                        val sessionState =
                                            when {
                                                NotificationMediaManager.isPlayingState(state.state) ->
                                                    MediaSessionState.Playing
                                                NotificationMediaManager.isConnectingState(state.state) ->
                                                    MediaSessionState.Buffering
                                                else -> MediaSessionState.Paused
                                            }
                                        val canBeScrubbed =
                                            state.state != PlaybackState.STATE_NONE &&
                                                latestModel.durationMs > 0L &&
                                                ((state.actions and PlaybackState.ACTION_SEEK_TO) != 0L)
                                        val actualPosition = state.computeActualPosition(latestModel.durationMs)
                                        updateMediaModelInStateLocked(latestModel) { model ->
                                            model.copy(
                                                state = sessionState,
                                                canBeScrubbed = canBeScrubbed,
                                                positionMs = actualPosition,
                                            )
                                        }
                                    }
                            }
                        }
                    }
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    applicationScope.launch {
                        mediaMutex.withLock {
                            val currentController = activeControllers[dataModel.instanceId] ?: controller
                            val meta = metadata ?: currentController.metadata ?: return@withLock
                            currentMedia
                                .find { it.instanceId == dataModel.instanceId }
                                ?.let { latestModel ->
                                    val duration =
                                        meta.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
                                            ?: latestModel.durationMs
                                    val currentPlayback = currentController.playbackState
                                    val canBeScrubbed =
                                        currentPlayback?.state != PlaybackState.STATE_NONE &&
                                            duration > 0L &&
                                            (((currentPlayback?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO) != 0L)
                                    val description = meta.description
                                    val newTitle =
                                        description?.title?.toString()?.takeIf { it.isNotBlank() }
                                            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                                                ?.takeIf { it.isNotBlank() }
                                            ?: meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                                                ?.takeIf { it.isNotBlank() }
                                            ?: latestModel.title
                                    val newSubtitle =
                                        description?.subtitle?.toString()?.takeIf { it.isNotBlank() }
                                            ?: meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                                                ?.takeIf { it.isNotBlank() }
                                            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                                                ?.takeIf { it.isNotBlank() }
                                            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                                                ?.takeIf { it.isNotBlank() }
                                            ?: latestModel.subtitle

                                    val rawBitmap =
                                        description?.iconBitmap
                                            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                                            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                                            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                                    val bitmap = rawBitmap?.takeUnless { it.isRecycled }

                                    val iconUri = description?.iconUri
                                    val artUriString =
                                        if (bitmap == null) {
                                            iconUri?.toString()
                                                ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
                                                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                                                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                                        } else {
                                            null
                                        }

                                    val (newBackground, newColorScheme) =
                                        when {
                                            bitmap != null -> {
                                                val artIcon = AndroidIcon.createWithBitmap(bitmap)
                                                val bg = Icon.Loaded(
                                                    BitmapDrawable(applicationContext.resources, bitmap),
                                                    contentDescription = null,
                                                )
                                                val scheme = getScheme(artIcon, latestModel.packageName)
                                                Pair(bg, scheme)
                                            }
                                            artUriString != null -> {
                                                try {
                                                    val parsedUri = Uri.parse(artUriString)
                                                    if (
                                                        parsedUri.scheme in
                                                            listOf(
                                                                ContentResolver.SCHEME_CONTENT,
                                                                ContentResolver.SCHEME_ANDROID_RESOURCE,
                                                                ContentResolver.SCHEME_FILE,
                                                            )
                                                    ) {
                                                        val artIcon = AndroidIcon.createWithContentUri(parsedUri)
                                                        val loadedDrawable = artIcon.loadDrawable(applicationContext)
                                                        if (loadedDrawable != null) {
                                                            val bg = Icon.Loaded(loadedDrawable, contentDescription = null)
                                                            val scheme = getScheme(artIcon, latestModel.packageName)
                                                            Pair(bg, scheme)
                                                        } else {
                                                            Pair(latestModel.background, latestModel.colorScheme)
                                                        }
                                                    } else {
                                                        Pair(latestModel.background, latestModel.colorScheme)
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Failed to load art from URI $artUriString", e)
                                                    Pair(latestModel.background, latestModel.colorScheme)
                                                }
                                            }
                                            else -> Pair(latestModel.background, latestModel.colorScheme)
                                        }

                                    val currentEntry = mutableUserEntries.value[latestModel.instanceId]
                                    if (currentEntry != null) {
                                        val updatedArtwork =
                                            when {
                                                bitmap != null -> AndroidIcon.createWithBitmap(bitmap)
                                                artUriString != null -> {
                                                    try {
                                                        AndroidIcon.createWithContentUri(Uri.parse(artUriString))
                                                    } catch (e: Exception) {
                                                        currentEntry.artwork
                                                    }
                                                }
                                                else -> currentEntry.artwork
                                            }
                                        val updatedEntry =
                                            currentEntry.copy(
                                                song = newTitle,
                                                artist = newSubtitle,
                                                artwork = updatedArtwork,
                                            )
                                        mutableUserEntries.value =
                                            mutableUserEntries.value + (latestModel.instanceId to updatedEntry)
                                    }

                                    updateMediaModelInStateLocked(latestModel) { model ->
                                        model.copy(
                                            title = newTitle,
                                            subtitle = newSubtitle,
                                            background = newBackground,
                                            colorScheme = newColorScheme,
                                            canBeScrubbed = canBeScrubbed,
                                            durationMs = duration,
                                        )
                                    }
                                }
                        }
                    }
                }

                override fun onSessionDestroyed() {
                    clearControllerState(dataModel.instanceId)
                }
            }
        controller.registerCallback(callback)
        mediaCallbacks[dataModel.instanceId] = callback

        // Initial polling setup.
        controller.playbackState?.let {
            updatePollingState(dataModel.instanceId, it, requireUpdate = false)
        }
    }

    private fun updatePollingState(
        instanceId: InstanceId,
        playbackState: PlaybackState,
        requireUpdate: Boolean = true,
    ) {
        val controller = activeControllers[instanceId] ?: return
        val isInMotion = NotificationMediaManager.isPlayingState(playbackState.state)

        if (isInMotion) {
            if (positionPollers[instanceId]?.isActive != true) {
                // Cancel previous if any.
                positionPollers[instanceId]?.cancel()
                positionPollers[instanceId] =
                    applicationScope.launch(backgroundDispatcher) {
                        while (isActive) {
                            val currentController = activeControllers[instanceId]
                            val latestPlaybackState = currentController?.playbackState
                            checkPlaybackPosition(instanceId, latestPlaybackState)
                            delay(POSITION_UPDATE_INTERVAL_MILLIS)
                        }
                        positionPollers.remove(instanceId)
                    }
            }
        } else if (requireUpdate) {
            positionPollers[instanceId]?.cancel()
            positionPollers.remove(instanceId)
            checkPlaybackPosition(instanceId, controller.playbackState)
        }
    }

    private fun PlaybackState.computeActualPosition(mediaDurationMs: Long): Long {
        var currentPosition = position
        if (NotificationMediaManager.isPlayingState(state)) {
            val currentTime = systemClock.elapsedRealtime()
            if (lastPositionUpdateTime > 0) {
                var estimatedPosition =
                    (playbackSpeed * (currentTime - lastPositionUpdateTime)).toLong() + position
                if (mediaDurationMs in 0..<estimatedPosition) {
                    estimatedPosition = mediaDurationMs
                } else if (estimatedPosition < 0) {
                    estimatedPosition = 0
                }
                currentPosition = estimatedPosition
            }
        }
        return currentPosition
    }

    private fun checkPlaybackPosition(instanceId: InstanceId, playbackState: PlaybackState?) {
        applicationScope.launch {
            mediaMutex.withLock {
                currentMedia
                    .find { it.instanceId == instanceId }
                    ?.let { latestModel ->
                        val newPosition =
                            playbackState?.computeActualPosition(latestModel.durationMs)
                        updateMediaModelInStateLocked(latestModel) {
                            if (newPosition != null && newPosition <= latestModel.durationMs) {
                                it.copy(positionMs = newPosition)
                            } else {
                                it
                            }
                        }
                    }
            }
        }
    }

    private fun clearControllerState(instanceId: InstanceId) {
        positionPollers[instanceId]?.cancel()
        positionPollers.remove(instanceId)
        mediaCallbacks[instanceId]?.let { activeControllers[instanceId]?.unregisterCallback(it) }
        activeControllers.remove(instanceId)
        mediaCallbacks.remove(instanceId)
    }

    @GuardedBy("mediaMutex")
    private fun updateMediaModelInStateLocked(
        oldModel: MediaDataModel,
        updateBlock: (MediaDataModel) -> MediaDataModel,
    ) {
        val currentModel = currentMedia.find { it.instanceId == oldModel.instanceId } ?: oldModel
        val newModel = updateBlock(currentModel)
        if (currentModel != newModel) {
            sortedMedia.keys
                .find { it.instanceId == newModel.instanceId }
                ?.let {
                    val sortedMap = TreeMap<MediaSortKeyModel, MediaDataModel>(comparator)
                    sortedMap.putAll(
                        sortedMedia.filter { (_, model) -> model.instanceId != newModel.instanceId }
                    )
                    sortedMap[it] = newModel
                    sortedMedia = sortedMap
                }

            val index = currentMedia.indexOfFirst { it.instanceId == newModel.instanceId }
            if (index != -1) {
                currentMedia[index] = newModel
            }
        }
    }

    companion object {
        private const val TAG = "MediaRepository"
        private const val POSITION_UPDATE_INTERVAL_MILLIS = 500L
    }
}
