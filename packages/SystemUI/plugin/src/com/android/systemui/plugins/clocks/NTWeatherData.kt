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
package com.android.systemui.plugins.clocks

data class NTWeatherData(
    var lastUpdateTime: Long = 0L,
    var phrase: String? = null,
    var iconType: Int = 0,
    var lowTemp: Int = 0,
    var highTemp: Int = 0,
    var temp: Int = Int.MIN_VALUE,
    var locationKey: String? = null,
    var locationType: Int = 0
)
