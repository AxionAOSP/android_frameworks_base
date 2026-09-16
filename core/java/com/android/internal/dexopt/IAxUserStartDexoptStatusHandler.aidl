package com.android.internal.dexopt;

interface IAxUserStartDexoptStatusHandler {
    void notifyConnected(in List<String> pkgs, int status, String message);
    void notifyProgress(int current, int total, String pkgName);
    void notifyCompleted();
    void notifyError(String error);
}
