package android.app;

import android.app.IFreeformDisplayCallback;
import android.view.Surface;

/** {@hide} */
interface IFreeformOverlayManager {
    void launchInFreeform(String packageName);
    void launchInFreeformActivity(String packageName, String activityName);
    
    void bringAllWindowsToBack();

    void createFreeform(String name, IFreeformDisplayCallback callback,
            int width, int height, int densityDpi, boolean secure,
            boolean ownContentOnly, boolean shouldShowSystemDecorations, in Surface surface,
            float refreshRate, long presentationDeadlineNanos);

    void pauseDisplay(int displayId);
    void resumeDisplay(int displayId);

    void launchAppOnDisplay(String packageName, String activityName, int displayId, int userId);

    void resizeFreeform(IBinder appToken, int width, int height, int densityDpi);

    void releaseFreeform(IBinder appToken);

    void launchDesktopApp(String packageName, String activityName);
    void launchDesktopAppOnDisplay(String packageName, String activityName, int displayId);
}
