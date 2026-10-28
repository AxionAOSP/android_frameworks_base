/*
 * Copyright (C) 2025-2026 AxionOS Project
 * Copyright (C) 2026 Saikrishna1504
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
package com.android.systemui.biometrics

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader

class WaterRippleShader(customAgsl: String? = null) :
    RuntimeShader(if (!customAgsl.isNullOrBlank()) customAgsl else SHADER) {

    private val bgMatrix = Matrix()
    private var lastBitmap: Bitmap? = null
    private var lastViewWidth: Int = -1
    private var lastViewHeight: Int = -1

    init {
        setInputShader("in_bg", EMPTY_SHADER)
        setFloatUniform("in_hasBg", 0f)
        setFloatUniform("in_origin", 540f, 1825f)
        setFloatUniform("in_resolution", 1080f, 2340f)
        setFloatUniform("in_windowOffset", 0f, 0f)
        setFloatUniform("in_screenResolution", 1080f, 2340f)
        setFloatUniform("in_density", 2.75f)
        setFloatUniform("in_time", 0f)
        setFloatUniform("in_minRadius", 0f)
        setFloatUniform("in_radius", 0f)
        setFloatUniform("in_amplitude", 0f)
        setFloatUniform("in_ripples", 13f)
        setFloatUniform("in_fadingFactor", 1f)
        setFloatUniform("in_fadingFactor2", 0f)
        setFloatUniform("in_offset", 2f)
        setFloatUniform("in_colorFullAlpha", 1f)
        setFloatUniform("in_rotateSpeed", 0f)
        setFloatUniform("in_angleS", 0f)
        setFloatUniform("in_angleE", 90f)
        setFloatUniform("in_fadeOut", 1f)
    }

    fun setBackgroundBitmap(bitmap: Bitmap?, viewWidth: Int, viewHeight: Int) {
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            if (bitmap === lastBitmap && viewWidth == lastViewWidth && viewHeight == lastViewHeight) {
                return
            }
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            bgMatrix.reset()
            if (viewWidth > 0 && viewHeight > 0) {
                val scale = maxOf(
                    viewWidth.toFloat() / bitmap.width.toFloat(),
                    viewHeight.toFloat() / bitmap.height.toFloat()
                )
                val dx = (viewWidth.toFloat() - bitmap.width.toFloat() * scale) * 0.5f
                val dy = (viewHeight.toFloat() - bitmap.height.toFloat() * scale) * 0.5f
                bgMatrix.setScale(scale, scale)
                bgMatrix.postTranslate(dx, dy)
            }
            shader.setLocalMatrix(bgMatrix)
            setInputShader("in_bg", shader)
            setFloatUniform("in_hasBg", 1f)
            lastBitmap = bitmap
            lastViewWidth = viewWidth
            lastViewHeight = viewHeight
        } else {
            clearBackground()
        }
    }

    fun clearBackground() {
        lastBitmap = null
        lastViewWidth = -1
        lastViewHeight = -1
        setInputShader("in_bg", EMPTY_SHADER)
        setFloatUniform("in_hasBg", 0f)
    }

    fun setOrigin(x: Float, y: Float) {
        setFloatUniform("in_origin", x, y)
    }

    fun setResolution(width: Float, height: Float) {
        setFloatUniform("in_resolution", maxOf(width, 1f), maxOf(height, 1f))
    }

    fun setScreenSpace(
        windowLeft: Float,
        windowTop: Float,
        screenWidth: Float,
        screenHeight: Float
    ) {
        setFloatUniform("in_windowOffset", windowLeft, windowTop)
        setFloatUniform("in_screenResolution", maxOf(screenWidth, 1f), maxOf(screenHeight, 1f))
    }

    fun setDensity(density: Float) {
        setFloatUniform("in_density", maxOf(density, 1f))
    }

    fun updateWave(
        timeSec: Float,
        radiusDp: Float,
        amplitude: Float,
        ripplesDp: Float,
        minRadiusDp: Float = 0f,
        offsetDp: Float = 2f,
        colorFullAlpha: Float = 1.0f,
        fadeOut: Float = 1.0f
    ) {
        setFloatUniform("in_time", timeSec)
        setFloatUniform("in_radius", radiusDp)
        setFloatUniform("in_amplitude", amplitude)
        setFloatUniform("in_ripples", ripplesDp)
        setFloatUniform("in_minRadius", minRadiusDp)
        setFloatUniform("in_offset", offsetDp)
        setFloatUniform("in_colorFullAlpha", colorFullAlpha)
        setFloatUniform("in_fadeOut", fadeOut)
    }

    companion object {
        private val EMPTY_BITMAP: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val EMPTY_SHADER: BitmapShader = BitmapShader(
            EMPTY_BITMAP,
            Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP
        )
        // language=AGSL
        private const val SHADER =
            """
            uniform shader in_bg;
            uniform float in_hasBg;
            uniform vec2 in_origin;
            uniform vec2 in_resolution;
            uniform vec2 in_windowOffset;
            uniform vec2 in_screenResolution;
            uniform float in_density;
            uniform float in_time;
            uniform float in_minRadius;
            uniform float in_radius;
            uniform float in_amplitude;
            uniform float in_ripples;
            uniform float in_fadingFactor;
            uniform float in_fadingFactor2;
            uniform float in_offset;
            uniform float in_colorFullAlpha;
            uniform float in_rotateSpeed;
            uniform float in_angleS;
            uniform float in_angleE;
            uniform float in_fadeOut;

            const float STD = 75.0;
            const float REFRACTION_COEFFICIENT = 0.7501875;

            float square(float value) {
                return value * value;
            }

            float rippleGradient(float x) {
                float amplitude = in_amplitude * in_density;
                float ripples = max(in_ripples * in_density, 1.0);
                float fadingFactor = max(in_fadingFactor * in_density, 0.001);
                float xr = x / ripples;
                return (amplitude / ripples * exp(-square(xr) / fadingFactor) * (sin(xr)
                    - 2.0 * x / square(ripples * STD) * cos(xr)) / ripples);
            }

            mat2 rotate2d(float angle) {
                float radian = 0.0174532925 * angle;
                float c = cos(radian);
                float s = sin(radian);
                return mat2(c, -s, s, c);
            }

            half4 main(vec2 fragCoord) {
                vec2 radial_direction = fragCoord - in_origin;
                float dist = length(radial_direction);
                float r = in_radius * in_density;
                float ripples = max(in_ripples * in_density, 1.0);

                float outerEdge = 1.0 - smoothstep(r + ripples * 1.8, r + ripples * 3.2, dist);
                float alpha = clamp(outerEdge * in_fadeOut, 0.0, 1.0);
                if (alpha <= 0.001 || in_amplitude <= 0.01) {
                    return half4(0.0);
                }

                float minRadius = in_minRadius * in_density;
                float appear = smoothstep(minRadius - 50.0, minRadius, dist);
                vec2 p = radial_direction / max(dist, 0.0001);

                float x = dist - r;
                float offset = in_offset * in_density;
                vec3 col = vec3(
                    rippleGradient(x - offset),
                    rippleGradient(x),
                    rippleGradient(x + offset)
                ) * appear;

                float g = rippleGradient(x);
                vec2 partial_derivative = appear * p * g / (square(r * in_fadingFactor2) + 1.0);

                vec3 normal = vec3(-partial_derivative.x, 1.0, -partial_derivative.y);
                vec3 eye = vec3(0.0, -1.0, 0.0);

                float c1 = dot(normal, eye);
                float c2 = sqrt(max(0.0, 1.0 - square(REFRACTION_COEFFICIENT) * (1.0 - square(c1))));

                vec2 screenCoord = fragCoord + in_windowOffset;
                vec2 buv = screenCoord - in_screenResolution * 0.5;
                buv = abs(buv * rotate2d(in_time * in_rotateSpeed));
                float angle = degrees(atan(buv.y / max(buv.x, 0.0001)));
                float mask = smoothstep(in_angleS, in_angleE, angle);

                vec3 refracted = REFRACTION_COEFFICIENT * eye
                    + (REFRACTION_COEFFICIENT * c1 - c2) * normal;
                vec2 uvPx = clamp(
                    screenCoord + refracted.xz * mask * in_screenResolution,
                    vec2(0.0),
                    in_screenResolution - vec2(1.0)
                );

                vec3 caustic = col * in_colorFullAlpha * mask;
                if (in_hasBg > 0.5) {
                    vec3 bgRgb = vec3(in_bg.eval(uvPx).rgb);
                    vec3 finalRgb = clamp(bgRgb + caustic, 0.0, 1.0);
                    return half4(half3(finalRgb * alpha), half(alpha));
                } else {
                    float causticAlpha = clamp(
                        (abs(g) * 1.35 + length(caustic) * 1.5) * appear * mask * alpha,
                        0.0,
                        1.0
                    );
                    vec3 fallbackRgb = clamp(
                        vec3(0.76, 0.89, 1.0) * abs(g) * 1.4 + caustic,
                        0.0,
                        1.0
                    );
                    return half4(half3(fallbackRgb * causticAlpha), half(causticAlpha));
                }
            }
            """
    }
}
