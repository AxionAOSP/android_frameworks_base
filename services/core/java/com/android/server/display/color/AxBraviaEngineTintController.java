/*
 * Copyright 2025-2026 AxionOS
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

package com.android.server.display.color;

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_BRAVIA_ENGINE;

import android.content.Context;
import android.hardware.display.ColorDisplayManager;
import android.opengl.Matrix;

import java.util.Arrays;

final class AxBraviaEngineTintController extends TintController {

    static final int MODE_OFF = 0;
    static final int MODE_VIVID = 1;
    static final int MODE_NATURAL = 2;
    static final int MODE_CINEMA = 3;

    private static final float LR = 0.2126f;
    private static final float LG = 0.7152f;
    private static final float LB = 0.0722f;

    private static final float DIAG_SAFE_MIN = 0.3f;
    private static final float DIAG_SAFE_MAX = 1.8f;

    private final float[] mMatrix = new float[16];

    @Override
    public void setUp(Context context, boolean needsLinear) {
    }

    @Override
    public float[] getMatrix() {
        return Arrays.copyOf(mMatrix, mMatrix.length);
    }

    @Override
    public void setMatrix(int mode) {
        if (mode <= MODE_OFF || mode > MODE_CINEMA) {
            setActivated(false);
            Matrix.setIdentityM(mMatrix, 0);
            return;
        }

        setActivated(true);

        float[] result;
        switch (mode) {
            case MODE_VIVID:
                result = buildVividMatrix();
                break;
            case MODE_NATURAL:
                result = buildNaturalMatrix();
                break;
            case MODE_CINEMA:
                result = buildCinemaMatrix();
                break;
            default:
                setActivated(false);
                Matrix.setIdentityM(mMatrix, 0);
                return;
        }

        if (result[0] < DIAG_SAFE_MIN || result[0] > DIAG_SAFE_MAX
                || result[5] < DIAG_SAFE_MIN || result[5] > DIAG_SAFE_MAX
                || result[10] < DIAG_SAFE_MIN || result[10] > DIAG_SAFE_MAX) {
            setActivated(false);
            Matrix.setIdentityM(mMatrix, 0);
            return;
        }

        System.arraycopy(result, 0, mMatrix, 0, 16);
    }

    private static float[] buildSaturationMatrix(float sat) {
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        float desat = 1.0f - sat;
        m[0]  = LR * desat + sat;
        m[1]  = LR * desat;
        m[2]  = LR * desat;
        m[4]  = LG * desat;
        m[5]  = LG * desat + sat;
        m[6]  = LG * desat;
        m[8]  = LB * desat;
        m[9]  = LB * desat;
        m[10] = LB * desat + sat;
        return m;
    }

    private static float[] buildVividMatrix() {
        return buildSaturationMatrix(1.35f);
    }

    private static float[] buildNaturalMatrix() {
        float[] satM = buildSaturationMatrix(1.20f);
        float[] warmM = new float[16];
        float[] result = new float[16];
        Matrix.setIdentityM(warmM, 0);
        warmM[0]  = 1.06f;
        warmM[5]  = 1.01f;
        warmM[10] = 0.90f;
        Matrix.multiplyMM(result, 0, warmM, 0, satM, 0);
        return result;
    }

    private static float[] buildCinemaMatrix() {
        float[] satM = buildSaturationMatrix(1.15f);
        float[] warmM = new float[16];
        float[] result = new float[16];
        Matrix.setIdentityM(warmM, 0);
        warmM[0]  = 1.12f;
        warmM[5]  = 0.98f;
        warmM[10] = 0.82f;
        Matrix.multiplyMM(result, 0, warmM, 0, satM, 0);
        return result;
    }

    @Override
    public int getLevel() {
        return LEVEL_COLOR_MATRIX_BRAVIA_ENGINE;
    }

    @Override
    public boolean isAvailable(Context context) {
        return ColorDisplayManager.isColorTransformAccelerated(context);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
