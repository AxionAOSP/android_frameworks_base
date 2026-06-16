/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.am;

import java.util.Set;

public class ServiceRecordExt {

    private static final Set<String> FOREGROUND_NOTIFICATION_BLACKLIST = Set.of(
        "com.oneplus.camera",
        "com.oplus.camera"
    );

    public static boolean interceptPostNotification(String packageName) {
        return FOREGROUND_NOTIFICATION_BLACKLIST.contains(packageName);
    }
}
