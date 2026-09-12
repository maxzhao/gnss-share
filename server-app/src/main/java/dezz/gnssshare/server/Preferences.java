/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dezz.gnssshare.server;

import android.content.Context;
import android.content.SharedPreferences;

public final class Preferences {
    private static final String PREF_TARGET_DEVICE_ADDRESS = "targetTabletAddress";
    private static final String PREF_TARGET_DEVICE_NAME = "targetTabletName";
    private static final String PREF_FUSED_LOCATION_ENABLED = "fusedLocationEnabled";

    private Preferences() {
    }

    public static void setTargetDevice(Context context, String address, String name) {
        getPrefs(context).edit()
                .putString(PREF_TARGET_DEVICE_ADDRESS, address)
                .putString(PREF_TARGET_DEVICE_NAME, name)
                .apply();
    }

    public static String targetDeviceAddress(Context context) {
        return getPrefs(context).getString(PREF_TARGET_DEVICE_ADDRESS, null);
    }

    public static String targetDeviceName(Context context) {
        return getPrefs(context).getString(PREF_TARGET_DEVICE_NAME, null);
    }

    public static void setFusedLocationEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_FUSED_LOCATION_ENABLED, enabled).apply();
    }

    public static boolean fusedLocationEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_FUSED_LOCATION_ENABLED, true);
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
}
