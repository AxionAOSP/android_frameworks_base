package com.android.systemui.qs.panels.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.composefragment.model.QSPanelComponent
import com.android.systemui.qs.panels.domain.interactor.ComponentReorderInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import android.content.res.Configuration

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

    val brightnessSliderState by
        hydrator.hydratedStateOf(
            traceName = "brightnessSliderState",
            source = interactor.brightnessSliderState,
            initialValue = interactor.getBrightnessSliderState(),
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

    fun updateBrightnessSliderState(state: Int) {
        interactor.saveBrightnessSliderState(state)
    }

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    @AssistedFactory
    interface Factory {
        fun create(): ComponentReorderViewModel
    }
}

