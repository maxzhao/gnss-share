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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.GeomagneticField;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class DeadReckoningEstimator implements SensorEventListener {
    interface Listener {
        void onPredictedLocation(@NonNull Location location);

        void onStateChanged(@NonNull State state);
    }

    enum State {
        INITIALIZING,
        ACTIVE,
        WAITING_FOR_ANCHOR,
        UNSUPPORTED
    }

    private static final String TAG = "DeadReckoningEstimator";
    private static final long PREDICTION_INTERVAL_MS = 500;
    private static final long MAX_ROTATION_AGE_NS = 100_000_000L;
    private static final double ACCELERATION_VARIANCE = 0.3;
    private static final double DEFAULT_LOCATION_VARIANCE = 8.0;
    private static final double DEFAULT_SPEED_VARIANCE = 0.1;
    private static final double UNOBSERVED_SPEED_VARIANCE = 1.0e12;

    private final SensorManager sensorManager;
    private final android.os.Handler handler;
    private final Listener listener;
    private final Object lock = new Object();
    private final float[] rotationQuaternion = new float[4];
    private final float[] worldAcceleration = new float[3];
    private final Runnable predictionTicker = this::publishPrediction;

    private Sensor linearAccelerationSensor;
    private Sensor rotationVectorSensor;
    private long nativeHandle;
    private long lastRotationTimestampNanos;
    private boolean running;
    private boolean sensorsAvailable;
    private boolean initialized;
    private boolean nativeFailed;
    private double correctionMonotonicSeconds;
    private double anchorAgeAtCorrectionSeconds;
    private double lastNativeMonotonicSeconds;
    private float baseAccuracy;
    private float speedSigma;
    private double lastAltitude;
    private boolean hasLastAltitude;
    private double declinationCosine = 1;
    private double declinationSine;
    private long lastPredictionWallTime;
    private State state = State.INITIALIZING;

    DeadReckoningEstimator(
            @NonNull Context context,
            @NonNull android.os.Handler handler,
            @NonNull Listener listener
    ) {
        sensorManager = context.getSystemService(SensorManager.class);
        this.handler = handler;
        this.listener = listener;
    }

    void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            initialized = false;
            nativeFailed = false;
            lastRotationTimestampNanos = 0;
            lastNativeMonotonicSeconds = Double.NEGATIVE_INFINITY;
            lastPredictionWallTime = 0;
            hasLastAltitude = false;
            changeStateLocked(State.INITIALIZING);

            if (!DeadReckoningNative.isAvailable() || sensorManager == null) {
                markUnsupportedLocked("Native inertial filter is unavailable");
                return;
            }

            linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (linearAccelerationSensor == null || rotationVectorSensor == null) {
                markUnsupportedLocked("Required inertial sensors are unavailable");
                return;
            }

            nativeHandle = DeadReckoningNative.nativeCreate(
                    ACCELERATION_VARIANCE,
                    DEFAULT_LOCATION_VARIANCE,
                    DEFAULT_SPEED_VARIANCE
            );
            if (nativeHandle == 0) {
                markUnsupportedLocked("Failed to create native inertial filter");
                return;
            }

            boolean linearRegistered = sensorManager.registerListener(
                    this,
                    linearAccelerationSensor,
                    SensorManager.SENSOR_DELAY_GAME,
                    handler
            );
            boolean rotationRegistered = sensorManager.registerListener(
                    this,
                    rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_GAME,
                    handler
            );
            sensorsAvailable = linearRegistered && rotationRegistered;
            if (!sensorsAvailable) {
                sensorManager.unregisterListener(this);
                markUnsupportedLocked("Failed to register inertial sensors");
                return;
            }
            changeStateLocked(State.WAITING_FOR_ANCHOR);
        }
    }

    void stop() {
        synchronized (lock) {
            running = false;
            handler.removeCallbacks(predictionTicker);
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
            sensorsAvailable = false;
            initialized = false;
            lastRotationTimestampNanos = 0;
            lastNativeMonotonicSeconds = Double.NEGATIVE_INFINITY;
            destroyNativeLocked();
        }
    }

    boolean correct(@NonNull Location location) {
        synchronized (lock) {
            if (!running || nativeFailed || nativeHandle == 0) {
                return false;
            }
            double monotonicSeconds = elapsedRealtimeSeconds();
            double locationVariance = square(positiveOrDefault(
                    location.hasAccuracy() ? location.getAccuracy() : 0,
                    Math.sqrt(DEFAULT_LOCATION_VARIANCE)
            ));
            boolean hasVelocityMeasurement = location.hasSpeed()
                    && Float.isFinite(location.getSpeed())
                    && location.hasBearing()
                    && Float.isFinite(location.getBearing());
            double currentSpeedSigma = positiveOrDefault(
                    hasVelocityMeasurement
                            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            && location.hasSpeedAccuracy()
                            ? location.getSpeedAccuracyMetersPerSecond()
                            : 0,
                    Math.sqrt(DEFAULT_SPEED_VARIANCE)
            );
            double altitude = location.hasAltitude() && Double.isFinite(location.getAltitude())
                    ? location.getAltitude()
                    : lastAltitude;
            double speed;
            double bearing;
            double speedVariance;
            if (hasVelocityMeasurement) {
                speed = Math.max(0, location.getSpeed());
                bearing = normalizeBearing(location.getBearing());
                speedVariance = square(currentSpeedSigma);
            } else {
                double[] previousEstimate = initialized ? estimateLocked() : null;
                if (nativeFailed) {
                    return false;
                }
                speed = previousEstimate == null ? 0 : Math.max(0, previousEstimate[2]);
                bearing = previousEstimate == null ? 0 : normalizeBearing(previousEstimate[3]);
                speedVariance = UNOBSERVED_SPEED_VARIANCE;
            }
            boolean corrected;
            try {
                corrected = DeadReckoningNative.nativeCorrect(
                        nativeHandle,
                        location.getLatitude(),
                        location.getLongitude(),
                        altitude,
                        locationVariance,
                        speed,
                        bearing,
                        speedVariance,
                        monotonicSeconds
                );
            } catch (RuntimeException | LinkageError error) {
                failNativeLocked("Native correction failed", error);
                return false;
            }
            if (!corrected) {
                failNativeLocked("Native correction returned false", null);
                return false;
            }

            initialized = true;
            correctionMonotonicSeconds = monotonicSeconds;
            anchorAgeAtCorrectionSeconds = Math.max(
                    0,
                    (System.currentTimeMillis() - location.getTime()) / 1000.0
            );
            lastNativeMonotonicSeconds = monotonicSeconds;
            baseAccuracy = (float) Math.sqrt(locationVariance);
            speedSigma = (float) currentSpeedSigma;
            if (location.hasAltitude() && Double.isFinite(location.getAltitude())) {
                lastAltitude = location.getAltitude();
                hasLastAltitude = true;
            }
            updateDeclinationLocked(location, altitude);
            lastPredictionWallTime = Math.max(lastPredictionWallTime, location.getTime());
            changeStateLocked(State.ACTIVE);
            handler.removeCallbacks(predictionTicker);
            handler.postDelayed(predictionTicker, PREDICTION_INTERVAL_MS);
            return true;
        }
    }

    @Nullable
    Location correctedEstimate(@NonNull Location source) {
        synchronized (lock) {
            double[] estimate = estimateLocked();
            if (estimate == null) {
                return null;
            }
            Location location = new Location(source);
            location.setLatitude(estimate[0]);
            location.setLongitude(estimate[1]);
            if (hasLastAltitude) {
                location.setAltitude(lastAltitude);
            } else {
                location.removeAltitude();
            }
            location.setAccuracy(baseAccuracy);
            location.setSpeed((float) Math.max(0, estimate[2]));
            location.setBearing(normalizeBearing(estimate[3]));
            return location;
        }
    }

    boolean isSupported() {
        synchronized (lock) {
            return sensorsAvailable && !nativeFailed;
        }
    }

    State getState() {
        synchronized (lock) {
            return state;
        }
    }

    @Override
    public void onSensorChanged(@NonNull SensorEvent event) {
        synchronized (lock) {
            if (!running || !sensorsAvailable || nativeFailed || nativeHandle == 0 || !initialized) {
                return;
            }
            if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getQuaternionFromVector(rotationQuaternion, event.values);
                lastRotationTimestampNanos = event.timestamp;
                return;
            }
            if (event.sensor.getType() != Sensor.TYPE_LINEAR_ACCELERATION
                    || lastRotationTimestampNanos == 0
                    || event.timestamp < lastRotationTimestampNanos
                    || event.timestamp - lastRotationTimestampNanos > MAX_ROTATION_AGE_NS) {
                return;
            }

            double sensorMonotonicSeconds = event.timestamp / 1_000_000_000.0;
            if (sensorMonotonicSeconds <= lastNativeMonotonicSeconds) {
                return;
            }
            rotateVector(rotationQuaternion, event.values, worldAcceleration);
            double trueEastAcceleration = worldAcceleration[0] * declinationCosine
                    + worldAcceleration[1] * declinationSine;
            double trueNorthAcceleration = -worldAcceleration[0] * declinationSine
                    + worldAcceleration[1] * declinationCosine;
            try {
                if (!DeadReckoningNative.nativePredict(
                        nativeHandle,
                        trueEastAcceleration,
                        trueNorthAcceleration,
                        worldAcceleration[2],
                        sensorMonotonicSeconds
                )) {
                    failNativeLocked("Native prediction returned false", null);
                } else {
                    lastNativeMonotonicSeconds = sensorMonotonicSeconds;
                }
            } catch (RuntimeException | LinkageError error) {
                failNativeLocked("Native prediction failed", error);
            }
        }
    }

    @Override
    public void onAccuracyChanged(@NonNull Sensor sensor, int accuracy) {
        // Accuracy changes are already reflected by Android's virtual sensor output.
    }

    private void publishPrediction() {
        Location prediction = null;
        synchronized (lock) {
            if (!running || !sensorsAvailable || nativeFailed || !initialized) {
                return;
            }
            double[] estimate = estimateLocked();
            if (estimate != null) {
                prediction = new Location("dead_reckoning");
                prediction.setLatitude(estimate[0]);
                prediction.setLongitude(estimate[1]);
                if (hasLastAltitude) {
                    prediction.setAltitude(lastAltitude);
                }
                prediction.setSpeed((float) Math.max(0, estimate[2]));
                prediction.setBearing(normalizeBearing(estimate[3]));
                prediction.setAccuracy(predictedAccuracyLocked());
                long now = System.currentTimeMillis();
                lastPredictionWallTime = Math.max(now, lastPredictionWallTime + 1);
                prediction.setTime(lastPredictionWallTime);
                prediction.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            }
            if (running && !nativeFailed && initialized) {
                handler.postDelayed(predictionTicker, PREDICTION_INTERVAL_MS);
            }
        }
        if (prediction != null) {
            listener.onPredictedLocation(prediction);
        }
    }

    @Nullable
    private double[] estimateLocked() {
        if (!initialized || nativeHandle == 0 || nativeFailed) {
            return null;
        }
        double[] estimate;
        try {
            estimate = DeadReckoningNative.nativeEstimate(nativeHandle);
        } catch (RuntimeException | LinkageError error) {
            failNativeLocked("Native estimate failed", error);
            return null;
        }
        if (estimate == null || estimate.length != 4
                || !isFiniteLatitudeLongitude(estimate[0], estimate[1])
                || !Double.isFinite(estimate[2]) || !Double.isFinite(estimate[3])) {
            failNativeLocked("Native estimate was invalid", null);
            return null;
        }
        return estimate;
    }

    private float predictedAccuracyLocked() {
        double elapsedSeconds = Math.max(0, elapsedRealtimeSeconds() - correctionMonotonicSeconds);
        double uncertaintySeconds = anchorAgeAtCorrectionSeconds + elapsedSeconds;
        double elapsedSquared = uncertaintySeconds * uncertaintySeconds;
        double variance = square(baseAccuracy)
                + square(speedSigma) * elapsedSquared
                + 0.25 * ACCELERATION_VARIANCE * elapsedSquared * elapsedSquared;
        double accuracy = Math.sqrt(variance);
        return (float) Math.min(accuracy, Float.MAX_VALUE);
    }

    private void updateDeclinationLocked(@NonNull Location location, double altitude) {
        try {
            long time = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
            GeomagneticField field = new GeomagneticField(
                    (float) location.getLatitude(),
                    (float) location.getLongitude(),
                    (float) altitude,
                    time
            );
            double radians = Math.toRadians(field.getDeclination());
            declinationCosine = Math.cos(radians);
            declinationSine = Math.sin(radians);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Failed to calculate magnetic declination; using magnetic north", exception);
            declinationCosine = 1;
            declinationSine = 0;
        }
    }

    private void markUnsupportedLocked(@NonNull String message) {
        Log.w(TAG, message);
        sensorsAvailable = false;
        nativeFailed = true;
        destroyNativeLocked();
        changeStateLocked(State.UNSUPPORTED);
    }

    private void failNativeLocked(@NonNull String message, @Nullable Throwable error) {
        if (error == null) {
            Log.e(TAG, message);
        } else {
            Log.e(TAG, message, error);
        }
        handler.removeCallbacks(predictionTicker);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        sensorsAvailable = false;
        nativeFailed = true;
        initialized = false;
        destroyNativeLocked();
        changeStateLocked(State.UNSUPPORTED);
    }

    private void destroyNativeLocked() {
        if (nativeHandle == 0) {
            return;
        }
        try {
            DeadReckoningNative.nativeDestroy(nativeHandle);
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Failed to destroy native inertial filter", error);
        }
        nativeHandle = 0;
    }

    private void changeStateLocked(@NonNull State newState) {
        if (state == newState) {
            return;
        }
        state = newState;
        handler.post(() -> listener.onStateChanged(newState));
    }

    static boolean isValidRealLocation(@Nullable Location location) {
        if (location == null || "dead_reckoning".equals(location.getProvider())) {
            return false;
        }
        boolean mock = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? location.isMock()
                : location.isFromMockProvider();
        return !mock && isFiniteLatitudeLongitude(location.getLatitude(), location.getLongitude());
    }

    private static boolean isFiniteLatitudeLongitude(double latitude, double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }

    private static double elapsedRealtimeSeconds() {
        return SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0;
    }

    private static double positiveOrDefault(double value, double fallback) {
        return Double.isFinite(value) && value > 0 ? value : fallback;
    }

    private static double square(double value) {
        return value * value;
    }

    private static float normalizeBearing(double value) {
        double normalized = value % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        return (float) normalized;
    }

    private static void rotateVector(float[] quaternion, float[] vector, float[] output) {
        float w = quaternion[0];
        float x = quaternion[1];
        float y = quaternion[2];
        float z = quaternion[3];
        float tx = 2f * (y * vector[2] - z * vector[1]);
        float ty = 2f * (z * vector[0] - x * vector[2]);
        float tz = 2f * (x * vector[1] - y * vector[0]);
        output[0] = vector[0] + w * tx + (y * tz - z * ty);
        output[1] = vector[1] + w * ty + (z * tx - x * tz);
        output[2] = vector[2] + w * tz + (x * ty - y * tx);
    }
}
