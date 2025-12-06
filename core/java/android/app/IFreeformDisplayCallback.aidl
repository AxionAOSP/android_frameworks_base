package android.app;

/** {@hide} */
oneway interface IFreeformDisplayCallback {
    void onDisplayAdd(int displayId);
    void onDisplayPaused();
    void onDisplayResumed();
    void onDisplayStopped();
}
