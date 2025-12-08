package com.axion.systemui.statusbar.notification.stack;

import android.annotation.ColorInt;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.stack.SectionHeaderView;


public class EssentialSectionHeaderView extends SectionHeaderView {

    private float mDozeAmount = 0f;

    public EssentialSectionHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void applyContentTransformation(float contentAlpha, float translationY) {
        super.applyContentTransformation(1.0f, 0f);
    }

    @Override
    protected void setForegroundColors(@ColorInt int onSurface, @ColorInt int onSurfaceVariant) {
        int essentialTextColor = getContext().getColor(R.color.essential_notification_section_text_color);
        super.setForegroundColors(essentialTextColor, onSurfaceVariant);
    }

    public void setDozeAmount(float dozeAmount) {
        if (mDozeAmount != dozeAmount) {
            mDozeAmount = dozeAmount;
            TextView labelView = findViewById(R.id.header_label);
            if (labelView != null) {
                labelView.setAlpha(1f - dozeAmount);
            }
        }
    }
}