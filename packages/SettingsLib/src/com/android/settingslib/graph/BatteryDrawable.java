package com.android.settingslib.graph;

public interface BatteryDrawable {
    void setCharging(boolean charging);
    void setBatteryLevel(int level);
    void setPowerSaveEnabled(boolean powerSaveEnabled);
    void setColors(int fgColor, int bgColor, int singleToneColor);
    void setShowPercent(boolean show);
}
