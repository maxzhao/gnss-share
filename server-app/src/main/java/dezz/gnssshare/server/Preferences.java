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
import android.location.Location;
import android.os.Build;

import androidx.annotation.Nullable;

public final class Preferences {
    private static final String PREF_TARGET_DEVICE_ADDRESS = "targetTabletAddress";
    private static final String PREF_TARGET_DEVICE_NAME = "targetTabletName";
    private static final String PREF_FUSED_LOCATION_ENABLED = "fusedLocationEnabled";
    private static final String PREF_LAST_REAL_LOCATION = "lastRealLocationPresent";
    private static final String PREF_LAST_REAL_PROVIDER = "lastRealLocationProvider";
    private static final String PREF_LAST_REAL_TIME = "lastRealLocationTime";
    private static final String PREF_LAST_REAL_LATITUDE = "lastRealLocationLatitude";
    private static final String PREF_LAST_REAL_LONGITUDE = "lastRealLocationLongitude";
    private static final String PREF_LAST_REAL_HAS_ALTITUDE = "lastRealLocationHasAltitude";
    private static final String PREF_LAST_REAL_ALTITUDE = "lastRealLocationAltitude";
    private static final String PREF_LAST_REAL_HAS_ACCURACY = "lastRealLocationHasAccuracy";
    private static final String PREF_LAST_REAL_ACCURACY = "lastRealLocationAccuracy";
    private static final String PREF_LAST_REAL_HAS_SPEED = "lastRealLocationHasSpeed";
    private static final String PREF_LAST_REAL_SPEED = "lastRealLocationSpeed";
    private static final String PREF_LAST_REAL_HAS_BEARING = "lastRealLocationHasBearing";
    private static final String PREF_LAST_REAL_BEARING = "lastRealLocationBearing";
    private static final String PREF_LAST_REAL_HAS_SPEED_ACCURACY = "lastRealLocationHasSpeedAccuracy";
    private static final String PREF_LAST_REAL_SPEED_ACCURACY = "lastRealLocationSpeedAccuracy";

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

    public static void setLastRealLocation(Context context, Location location) {
        SharedPreferences.Editor editor = getPrefs(context).edit()
                .putBoolean(PREF_LAST_REAL_LOCATION, true)
                .putString(PREF_LAST_REAL_PROVIDER, location.getProvider())
                .putLong(PREF_LAST_REAL_TIME, location.getTime())
                .putLong(PREF_LAST_REAL_LATITUDE, Double.doubleToRawLongBits(location.getLatitude()))
                .putLong(PREF_LAST_REAL_LONGITUDE, Double.doubleToRawLongBits(location.getLongitude()))
                .putBoolean(PREF_LAST_REAL_HAS_ALTITUDE, location.hasAltitude())
                .putBoolean(PREF_LAST_REAL_HAS_ACCURACY, location.hasAccuracy())
                .putBoolean(PREF_LAST_REAL_HAS_SPEED, location.hasSpeed())
                .putBoolean(PREF_LAST_REAL_HAS_BEARING, location.hasBearing())
                .putBoolean(
                        PREF_LAST_REAL_HAS_SPEED_ACCURACY,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()
                );
        if (location.hasAltitude()) {
            editor.putLong(PREF_LAST_REAL_ALTITUDE, Double.doubleToRawLongBits(location.getAltitude()));
        }
        if (location.hasAccuracy()) {
            editor.putFloat(PREF_LAST_REAL_ACCURACY, location.getAccuracy());
        }
        if (location.hasSpeed()) {
            editor.putFloat(PREF_LAST_REAL_SPEED, location.getSpeed());
        }
        if (location.hasBearing()) {
            editor.putFloat(PREF_LAST_REAL_BEARING, location.getBearing());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
            editor.putFloat(PREF_LAST_REAL_SPEED_ACCURACY, location.getSpeedAccuracyMetersPerSecond());
        }
        editor.apply();
    }

    @Nullable
    public static Location lastRealLocation(Context context) {
        SharedPreferences preferences = getPrefs(context);
        if (!preferences.getBoolean(PREF_LAST_REAL_LOCATION, false)) {
            return null;
        }
        try {
            String provider = preferences.getString(PREF_LAST_REAL_PROVIDER, "cached");
            Location location = new Location(provider == null ? "cached" : provider);
            location.setTime(preferences.getLong(PREF_LAST_REAL_TIME, 0));
            location.setLatitude(Double.longBitsToDouble(
                    preferences.getLong(PREF_LAST_REAL_LATITUDE, 0)
            ));
            location.setLongitude(Double.longBitsToDouble(
                    preferences.getLong(PREF_LAST_REAL_LONGITUDE, 0)
            ));
            if (preferences.getBoolean(PREF_LAST_REAL_HAS_ALTITUDE, false)) {
                location.setAltitude(Double.longBitsToDouble(
                        preferences.getLong(PREF_LAST_REAL_ALTITUDE, 0)
                ));
            }
            if (preferences.getBoolean(PREF_LAST_REAL_HAS_ACCURACY, false)) {
                location.setAccuracy(preferences.getFloat(PREF_LAST_REAL_ACCURACY, 0));
            }
            if (preferences.getBoolean(PREF_LAST_REAL_HAS_SPEED, false)) {
                location.setSpeed(preferences.getFloat(PREF_LAST_REAL_SPEED, 0));
            }
            if (preferences.getBoolean(PREF_LAST_REAL_HAS_BEARING, false)) {
                location.setBearing(preferences.getFloat(PREF_LAST_REAL_BEARING, 0));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && preferences.getBoolean(PREF_LAST_REAL_HAS_SPEED_ACCURACY, false)) {
                location.setSpeedAccuracyMetersPerSecond(
                        preferences.getFloat(PREF_LAST_REAL_SPEED_ACCURACY, 0)
                );
            }
            return DeadReckoningEstimator.isValidRealLocation(location) ? location : null;
        } catch (ClassCastException exception) {
            return null;
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
}
