/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dezz.gnssshare.server;

final class DeadReckoningNative {
    private static final boolean AVAILABLE;

    static {
        boolean available;
        try {
            System.loadLibrary("gnss_dead_reckoning");
            available = true;
        } catch (LinkageError error) {
            available = false;
        }
        AVAILABLE = available;
    }

    private DeadReckoningNative() {
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    static native long nativeCreate(
            double accelerationVariance,
            double locationVariance,
            double speedVariance
    );

    static native boolean nativeCorrect(
            long handle,
            double latitude,
            double longitude,
            double altitude,
            double locationVariance,
            double speed,
            double bearing,
            double speedVariance,
            double monotonicSeconds
    );

    static native boolean nativePredict(
            long handle,
            double eastAcceleration,
            double northAcceleration,
            double upAcceleration,
            double monotonicSeconds
    );

    static native double[] nativeEstimate(long handle);

    static native void nativeDestroy(long handle);
}
