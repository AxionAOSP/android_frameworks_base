package com.android.systemui.statusbar.notification.stack

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class AxAmbientStateEx @Inject constructor() {

    companion object {
        @JvmStatic
        var hideShelfForNotificationPanelExpandingNotComplete: Boolean = false

        @JvmStatic
        var hideShelfForPanelAnimation: Boolean = false

        @JvmStatic
        var optimizedNotificationPanelViewCollapse: Boolean = false
    }

    @get:JvmName("getQSExpansion")
    @set:JvmName("setQSExpansion")
    var qsExpansion: Float = 0f

    var alphaFraction: Float = 0f

    var ignoreExpandShadeForKeyguard: Boolean = false

    var isDispatchingDownTouchWithoutOtherEvent: Boolean = false

    var isProgressBarIndeterminateAnimationRunning: Boolean = true

    var playingCannedUnlockAnimationCancelTouch: Boolean = false

    var qsCustomizing: Boolean = false

    var skipDrawNotificationRowCount: Int = 0

    var splitShadeEnabled: Boolean = false

    var useSplitShadeFromKeyguardMediaController: Boolean = false
}
