package com.android.systemui.qs.composefragment.model

enum class QSPanelComponent(val key: String) {
    BRIGHTNESS("brightness"),
    TILES("tiles"),
    MEDIA("media");

    companion object {
        val DEFAULT_ORDER = listOf(TILES, BRIGHTNESS, MEDIA)

        private fun fromKey(key: String): QSPanelComponent? =
            entries.find { it.key == key }

        fun fromCsv(csv: String?): List<QSPanelComponent> {
            if (csv.isNullOrBlank()) return DEFAULT_ORDER
            val parsed = csv.split(",").mapNotNull { fromKey(it.trim()) }
            return if (parsed.toSet() == entries.toSet()) parsed else DEFAULT_ORDER
        }

        fun toCsv(order: List<QSPanelComponent>): String =
            order.joinToString(",") { it.key }
    }
}

