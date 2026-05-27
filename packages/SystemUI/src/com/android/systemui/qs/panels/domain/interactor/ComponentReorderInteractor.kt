package com.android.systemui.qs.panels.domain.interactor

import android.content.res.Configuration
import android.os.UserHandle
import com.android.systemui.qs.composefragment.model.QSComponentVisibility
import com.android.systemui.qs.composefragment.model.QSPanelComponent
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class ComponentReorderInteractor @Inject constructor(
    private val secureSettings: SecureSettings,
    private val configurationController: ConfigurationController,
) {
    companion object {
        private const val COMPONENT_ORDER_KEY = "qs_panel_component_order"
        private const val BRIGHTNESS_SLIDER_KEY = "qs_brightness_slider_enabled"
        private const val VOLUME_SLIDER_KEY = "qs_volume_slider_enabled"
        private val DEFAULT_BRIGHTNESS_VISIBILITY = QSComponentVisibility.ALWAYS
        private val DEFAULT_VOLUME_VISIBILITY = QSComponentVisibility.QS_ONLY
    }

    val componentOrder: Flow<List<QSPanelComponent>> =
        secureSettings.observerFlow(COMPONENT_ORDER_KEY)
            .onStart { emit(Unit) }
            .map { getCurrentComponentOrder() }

    val brightnessSliderVisibility: Flow<QSComponentVisibility> =
        componentVisibility(BRIGHTNESS_SLIDER_KEY, DEFAULT_BRIGHTNESS_VISIBILITY)

    val volumeSliderVisibility: Flow<QSComponentVisibility> =
        componentVisibility(VOLUME_SLIDER_KEY, DEFAULT_VOLUME_VISIBILITY)

    val orientation: Flow<Int> = callbackFlow {
        val listener = object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                newConfig?.orientation?.let { trySend(it) }
            }
        }

        configurationController.addCallback(listener)

        trySend(Configuration.ORIENTATION_UNDEFINED)

        awaitClose {
            configurationController.removeCallback(listener)
        }
    }

    fun getCurrentComponentOrder(): List<QSPanelComponent> {
        val csv = secureSettings.getStringForUser(
            COMPONENT_ORDER_KEY,
            UserHandle.USER_CURRENT
        )
        return QSPanelComponent.fromCsv(csv)
    }

    fun saveComponentOrder(order: List<QSPanelComponent>) {
        val csv = QSPanelComponent.toCsv(order)
        secureSettings.putStringForUser(
            COMPONENT_ORDER_KEY,
            csv,
            null,
            false,
            UserHandle.USER_CURRENT,
            true,
        )
    }

    fun getBrightnessSliderVisibility(): QSComponentVisibility =
        getComponentVisibility(BRIGHTNESS_SLIDER_KEY, DEFAULT_BRIGHTNESS_VISIBILITY)

    fun saveBrightnessSliderVisibility(visibility: QSComponentVisibility) =
        saveComponentVisibility(BRIGHTNESS_SLIDER_KEY, visibility)

    fun getVolumeSliderVisibility(): QSComponentVisibility =
        getComponentVisibility(VOLUME_SLIDER_KEY, DEFAULT_VOLUME_VISIBILITY)

    fun saveVolumeSliderVisibility(visibility: QSComponentVisibility) =
        saveComponentVisibility(VOLUME_SLIDER_KEY, visibility)

    private fun componentVisibility(
        key: String,
        defaultVisibility: QSComponentVisibility,
    ): Flow<QSComponentVisibility> =
        secureSettings.observerFlow(key)
            .onStart { emit(Unit) }
            .map { getComponentVisibility(key, defaultVisibility) }

    private fun getComponentVisibility(
        key: String,
        defaultVisibility: QSComponentVisibility,
    ): QSComponentVisibility =
        QSComponentVisibility.fromSettingValue(
            secureSettings.getIntForUser(
                key,
                defaultVisibility.settingValue,
                UserHandle.USER_CURRENT,
            ),
            defaultVisibility,
        )

    private fun saveComponentVisibility(
        key: String,
        visibility: QSComponentVisibility,
    ) {
        secureSettings.putIntForUser(
            key,
            visibility.settingValue,
            UserHandle.USER_CURRENT,
        )
    }
}
