/*
 * Copyright (C) 2025 AxionOS
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
package com.axion.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.PipelineEntry
import com.android.systemui.statusbar.notification.collection.coordinator.Coordinator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.dagger.EssentialHeader
import com.axion.systemui.statusbar.notification.EssentialNotificationManager
import com.axion.systemui.statusbar.notification.collection.provider.EssentialProvider
import javax.inject.Inject
import kotlin.math.abs

private const val ESSENTIAL_SECTION_PRIORITY = 14
private const val EPSILON = 1.0E-5f

class EssentialCoordinator @Inject constructor(
    private val statusBarStateController: StatusBarStateController,
    private val essentialProvider: EssentialProvider,
    @EssentialHeader private val nodeController: NodeController,
    private val essentialNotificationManager: EssentialNotificationManager
) : Coordinator {

    private val collectionListener = object : NotifCollectionListener {
        override fun onEntryAdded(entry: NotificationEntry) {
            if (essentialProvider.isEssentialNotification(entry)) {
                essentialSectioner.invalidateList("essential notification added: ${entry.key}")
            }
        }

        override fun onEntryUpdated(entry: NotificationEntry) {
            if (essentialProvider.isEssentialNotification(entry)) {
                essentialSectioner.invalidateList("essential notification updated: ${entry.key}")
            }
        }
    }

    val essentialSectioner = object : NotifSectioner("Essential", ESSENTIAL_SECTION_PRIORITY) {
        override fun isInSection(entry: PipelineEntry): Boolean {
            return (entry as? ListEntry)?.let {
                essentialProvider.isNotificationEntryWithAtLeastOneEssentialChild(it)
            } ?: false
        }

        override fun getHeaderNodeController(): NodeController = nodeController
    }

    private val suspendedFilter = object : NotifFilter("IsSuspendedFilter") {
        override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
            return entry.ranking.isSuspended
        }
    }

    private val dndVisualEffectsFilter = object : NotifFilter("DndSuppressingVisualEffects") {
        override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
            val isDozing = statusBarStateController.isDozing
            val dozeAmountIsOne = isApproximatelyEqual(statusBarStateController.dozeAmount, 1.0f)
            if ((isDozing || dozeAmountIsOne) && entry.shouldSuppressAmbient()) {
                return true
            }
            return !isDozing && entry.shouldSuppressNotificationList()
        }
    }

    private val statusBarStateCallback = object : StatusBarStateController.StateListener {
        private var prevDozeAmountIsOne = false

        override fun onDozeAmountChanged(linear: Float, eased: Float) {
            val dozeAmountIsOne = isApproximatelyEqual(linear, 1.0f)
            if (prevDozeAmountIsOne != dozeAmountIsOne) {
                dndVisualEffectsFilter.invalidateList(
                    "dozeAmount changed to ${if (dozeAmountIsOne) "one" else "not one"}"
                )
                prevDozeAmountIsOne = dozeAmountIsOne
            }
        }

        override fun onDozingChanged(isDozing: Boolean) {
            dndVisualEffectsFilter.invalidateList("onDozingChanged to $isDozing")
        }
    }

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addCollectionListener(collectionListener)
        statusBarStateController.addCallback(statusBarStateCallback)
        pipeline.addPreGroupFilter(suspendedFilter)
        pipeline.addPreGroupFilter(dndVisualEffectsFilter)
        essentialNotificationManager.addOnEssentialPackagesChangedListener(
            object : EssentialNotificationManager.OnEssentialPackagesChangedListener {
                override fun onEssentialPackagesChanged() {
                    essentialSectioner.invalidateList("essential packages changed")
                }
            }
        )
    }

    private fun isApproximatelyEqual(a: Float, b: Float): Boolean = abs(a - b) < EPSILON
}
