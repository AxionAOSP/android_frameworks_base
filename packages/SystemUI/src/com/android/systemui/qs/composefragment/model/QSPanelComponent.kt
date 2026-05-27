package com.android.systemui.qs.composefragment.model

enum class QSPanelComponent(val key: String) {
    BRIGHTNESS("brightness"),
    VOLUME("volume"),
    TILES("tiles"),
    MEDIA("media");

    companion object {
        val DEFAULT_ORDER = listOf(TILES, BRIGHTNESS, VOLUME, MEDIA)

        private fun fromKey(key: String): QSPanelComponent? =
            entries.find { it.key == key }

        fun fromCsv(csv: String?): List<QSPanelComponent> {
            if (csv.isNullOrBlank()) return DEFAULT_ORDER
            val parsed = csv.split(",").mapNotNull { fromKey(it.trim()) }
            return if (parsed.size == entries.size && parsed.toSet() == entries.toSet()) {
                parsed
            } else {
                DEFAULT_ORDER
            }
        }

        fun toCsv(order: List<QSPanelComponent>): String =
            order.joinToString(",") { it.key }
    }
}

enum class QSComponentVisibility(val settingValue: Int) {
    ALWAYS(2),
    QS_ONLY(1),
    HIDDEN(0);

    val visibleInQuickQuickSettings: Boolean
        get() = this == ALWAYS

    val visibleInQuickSettings: Boolean
        get() = this != HIDDEN

    companion object {
        fun fromSettingValue(
            settingValue: Int,
            defaultValue: QSComponentVisibility,
        ): QSComponentVisibility =
            entries.firstOrNull { it.settingValue == settingValue } ?: defaultValue
    }
}
