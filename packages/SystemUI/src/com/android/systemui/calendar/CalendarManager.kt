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
package com.android.systemui.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import com.android.systemui.plugins.clocks.CalendarSimpleData
import com.android.systemui.util.WeakListenerManager

class CalendarManager private constructor() {

    interface Callback {
        fun onCalendarDataChanged(data: CalendarSimpleData?)
    }

    private val listenerManager = WeakListenerManager<Callback>()
    private val handler = Handler(Looper.getMainLooper())
    private var isObservingCalendar = false
    private var isObservingSettings = false

    private var calendarObserver: ContentObserver? = null
    private var settingsObserver: ContentObserver? = null
    private var lastNotifiedData: CalendarSimpleData? = null

    init {
        listenerManager.setLifecycleCallbacks(
            onActive = {
                observeSettings()
                if (isQuicklookEnabled()) observeCalendar()
            },
            onInactive = {
                unobserveCalendar()
                unobserveSettings()
            }
        )
    }

    fun addCallback(callback: Callback) {
        listenerManager.addListener(callback)

        if (isQuicklookEnabled()) {
            queryCalendar()
        } else {
            callback.onCalendarDataChanged(null)
        }
    }

    fun removeCallback(callback: Callback) {
        listenerManager.removeListener(callback)
    }

    private fun observeSettings() {
        if (settingsObserver != null) return
        val context = appContext ?: return

        settingsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                when (uri) {
                    CLOCK_FACE_URI -> {
                        if (isQuicklookEnabled()) {
                            queryCalendar()
                        }
                    }
                    QUICKLOOK_URI -> {
                        if (isQuicklookEnabled()) {
                            if (!isObservingCalendar) observeCalendar()
                            queryCalendar()
                        } else {
                            if (isObservingCalendar) unobserveCalendar()
                            notifyCallbacks(null)
                        }
                    }
                }
            }
        }.also {
            context.contentResolver.registerContentObserver(CLOCK_FACE_URI, false, it)
            context.contentResolver.registerContentObserver(QUICKLOOK_URI, false, it)
            isObservingSettings = true
        }
    }

    private fun unobserveSettings() {
        val context = appContext ?: return
        settingsObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            settingsObserver = null
            isObservingSettings = false
        }
    }

    private fun observeCalendar() {
        if (calendarObserver != null) return
        val context = appContext ?: return

        calendarObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed(refreshRunnable(), 500)
            }
        }.also { observer ->
            val contentResolver = context.contentResolver
            val urisToObserve = listOf(
                CalendarContract.Instances.CONTENT_URI,
                CalendarContract.Events.CONTENT_URI,
                CalendarContract.Calendars.CONTENT_URI,
                CalendarContract.Reminders.CONTENT_URI,
                CalendarContract.Attendees.CONTENT_URI
            )

            urisToObserve.forEach { uri ->
                contentResolver.registerContentObserver(uri, true, observer)
            }

            handler.postDelayed(refreshRunnable(), REFRESH_INTERVAL_MS)
            isObservingCalendar = true
        }
    }

    private fun unobserveCalendar() {
        val context = appContext ?: return
        calendarObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            calendarObserver = null
            handler.removeCallbacksAndMessages(null)
            isObservingCalendar = false
        }
    }

    private fun refreshRunnable() = Runnable { queryCalendar() }

    private fun queryCalendar() {
        val context = appContext ?: return

        if (!isQuicklookEnabled()) return

        val now = System.currentTimeMillis()
        val end = now + 24 * 60 * 60 * 1000L

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, now)
            ContentUris.appendId(this, end)
        }.build()

        val visibleEvent = context.contentResolver.query(
            uri,
            arrayOf("event_id", "title", "begin", "end", "eventLocation"),
            "visible = 1 AND allDay = 0",
            null,
            "begin ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val event = CalendarSimpleData.buildDataFromCursor(cursor)
                if (event.isEventVisible() && isEventValid(event)) {
                    return@use event
                }
            }
            null
        } ?: CalendarSimpleData.EMPTY

        notifyCallbacks(if (visibleEvent == CalendarSimpleData.EMPTY) null else visibleEvent)

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(refreshRunnable(), REFRESH_INTERVAL_MS)
    }

    private fun checkEventStillValid(event: CalendarSimpleData): Boolean {
        return isEventValid(event) && checkEventInInstances(event)
    }

    private fun isEventValid(event: CalendarSimpleData): Boolean {
        val context = appContext ?: return false

        return context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf("_id", "deleted"),
            "_id = ?",
            arrayOf(event.id.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val deletedIndex = cursor.getColumnIndex("deleted")
                val deleted = if (deletedIndex != -1) cursor.getInt(deletedIndex) else 0
                deleted == 0
            } else false
        } ?: false
    }

    private fun checkEventInInstances(event: CalendarSimpleData): Boolean {
        val context = appContext ?: return false

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, event.startTime - 1000)
            ContentUris.appendId(this, event.endTime + 1000)
        }.build()

        return context.contentResolver.query(
            uri,
            arrayOf("event_id"),
            "event_id = ? AND visible = 1",
            arrayOf(event.id.toString()),
            null
        )?.use { cursor ->
            cursor.count > 0
        } ?: false
    }

    private fun notifyCallbacks(data: CalendarSimpleData?) {
        if (data == lastNotifiedData) return
        lastNotifiedData = data
        listenerManager.notify { it.onCalendarDataChanged(data) }
    }

    private fun isQuicklookEnabled(): Boolean {
        val context = appContext ?: return false
        return Settings.Secure.getInt(
            context.contentResolver,
            "nt_quicklook_events",
            1
        ) == 1
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30 * 1000L

        @Volatile
        private var INSTANCE: CalendarManager? = null
        private lateinit var appContext: Context

        private val CLOCK_FACE_URI: Uri =
            Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE)
        private val QUICKLOOK_URI: Uri =
            Settings.Secure.getUriFor("nt_quicklook_events")

        fun init(context: Context) {
            appContext = context.applicationContext
            get()
        }

        fun get(): CalendarManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CalendarManager().also { INSTANCE = it }
            }
    }
}
