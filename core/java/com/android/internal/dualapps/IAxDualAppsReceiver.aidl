package com.android.internal.dualapps;

oneway interface IAxDualAppsReceiver {
    void onDualSpacePrepareStatus(int status, String reason);
    void onDualSpaceDeletionStatus(boolean success, String reason);
    void onDualAppDeleted(String packageName, int returnCode);
}
