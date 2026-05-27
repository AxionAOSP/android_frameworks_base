package com.android.systemui.qs.panels.ui.viewmodel

import android.content.res.Configuration
import androidx.compose.runtime.getValue
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.composefragment.model.QSComponentVisibility
import com.android.systemui.qs.composefragment.model.QSPanelComponent
import com.android.systemui.qs.panels.domain.interactor.ComponentReorderInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class ComponentReorderViewModel @AssistedInject constructor(
    private val interactor: ComponentReorderInteractor,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("ComponentReorderViewModel")

    val componentOrder by
        hydrator.hydratedStateOf(
            traceName = "componentOrder",
            source = interactor.componentOrder,
            initialValue = interactor.getCurrentComponentOrder(),
        )

    val brightnessSliderVisibility by
        hydrator.hydratedStateOf(
            traceName = "brightnessSliderVisibility",
            source = interactor.brightnessSliderVisibility,
            initialValue = interactor.getBrightnessSliderVisibility(),
        )

    val volumeSliderVisibility by
        hydrator.hydratedStateOf(
            traceName = "volumeSliderVisibility",
            source = interactor.volumeSliderVisibility,
            initialValue = interactor.getVolumeSliderVisibility(),
        )

    val orientation by
        hydrator.hydratedStateOf(
            traceName = "orientation",
            source = interactor.orientation,
            initialValue = Configuration.ORIENTATION_UNDEFINED,
        )

    fun updateComponentOrder(newOrder: List<QSPanelComponent>) {
        interactor.saveComponentOrder(newOrder)
    }

    fun updateBrightnessSliderVisibility(visibility: QSComponentVisibility) {
        interactor.saveBrightnessSliderVisibility(visibility)
    }

    fun updateVolumeSliderVisibility(visibility: QSComponentVisibility) {
        interactor.saveVolumeSliderVisibility(visibility)
    }

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    @AssistedFactory
    interface Factory {
        fun create(): ComponentReorderViewModel
    }
}
