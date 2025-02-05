/**
  * Copyright (C) 2025 the AxionAOSP Project
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
package com.android.systemui.biometrics;

import android.content.Context;
import android.util.Log;

import com.android.systemui.res.R;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieAnimationView;

public class UdfpsAnimation extends LottieAnimationView {

    private static final String LOG_TAG = "UdfpsAnimation";

    public UdfpsAnimation(Context context) {
        super(context);
        setAnimation(R.raw.nt_udfps_lockscreen_fp_scanning);
        setRepeatCount(LottieDrawable.INFINITE);
        setSpeed(1.7f);
    }

    public void startAnimation() {
        playAnimation();
    }

    public void stopAnimation() {
        pauseAnimation();
        setProgress(0f);
    }
}
