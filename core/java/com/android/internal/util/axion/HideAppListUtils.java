package com.android.internal.util.axion;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class HideAppListUtils {

    private static boolean isBootCompleted() {
        return SystemProperties.getBoolean("sys.boot_completed", false);
    }

    public static boolean shouldHideAppList(Context context, String packageName) {
        if (!isBootCompleted() || context == null || packageName == null) {
            return false;
        }
        Set<String> apps = getApps(context);
        return !apps.isEmpty() && apps.contains(packageName);
    }

    public static Set<String> getApps(Context context) {
        if (context == null) {
            return Collections.emptySet();
        }
        String apps = Settings.Secure.getString(context.getContentResolver(), "hide_applist");
        if (apps == null || apps.isEmpty() || apps.equals(",")) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(apps.split(","))));
    }
}
