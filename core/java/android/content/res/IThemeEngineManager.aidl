package android.content.res;

import android.content.ComponentName;
import android.content.res.IThemeEngineCallback;
import android.graphics.Bitmap;

/**
 * @hide
 */
interface IThemeEngineManager {                                                 
    Bitmap getIconPackIcon(in ComponentName component, int density);
    boolean hasActiveIconPack();
    String getIconPackPackage();
    Bitmap getIconThemeDrawable(String resourceName, int density);
    boolean isTargetedResource(String resourceName);
    boolean hasActiveIconTheme();
    String getActiveIconTheme();
    String getCategoryTheme(String category);
    String getQsStyleId();
    String getVolumeStyleId();
    String getIconShape();
    String getIconShapePath();
    String getThemedString(String stringName);
    void registerCallback(IThemeEngineCallback callback);
    void unregisterCallback(IThemeEngineCallback callback);
    void notifyThemeChanged(String category);
}
