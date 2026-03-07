package com.android.systemui.qs.panels.domain.interactor

import android.content.res.Configuration
import android.os.UserHandle
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
        private const val DEFAULT_BRIGHTNESS_STATE = 2 
    }

    val componentOrder: Flow<List<QSPanelComponent>> =
        secureSettings.observerFlow(COMPONENT_ORDER_KEY)
            .onStart { emit(Unit) }
            .map { getCurrentComponentOrder() }

    val brightnessSliderState: Flow<Int> =
        secureSettings.observerFlow(BRIGHTNESS_SLIDER_KEY)
            .onStart { emit(Unit) }
            .map { getBrightnessSliderState() }

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

    fun getBrightnessSliderState(): Int {
        return secureSettings.getIntForUser(
            BRIGHTNESS_SLIDER_KEY,
            DEFAULT_BRIGHTNESS_STATE,
            UserHandle.USER_CURRENT
        )
    }

    fun saveBrightnessSliderState(state: Int) {
        secureSettings.putIntForUser(
            BRIGHTNESS_SLIDER_KEY,
            state,
            UserHandle.USER_CURRENT
        )
    }
}

