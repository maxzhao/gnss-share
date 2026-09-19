/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dezz.gnssshare.server;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.gnssshare.proto.LocationProto;
import dezz.gnssshare.shared.BluetoothContract;
import dezz.gnssshare.shared.ServerStatus;

public class GNSSServerService extends Service {
    private static final String TAG = "GNSSServerService";
    private static final String CHANNEL_ID = "GNSSServerChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long LOCATION_STOP_DELAY_MS = 15000;

    private static boolean running;
    private static GNSSServerService instance;

    private final Object transportLock = new Object();
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService clientExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothServerSocket serverSocket;
    private ClientHandler activeClient;
    private boolean acceptRunning;
    private boolean shuttingDown;
    private int transportGeneration;
    private String transportStatus;

    private LocationManager locationManager;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private DeadReckoningEstimator deadReckoningEstimator;
    private PowerManager.WakeLock locationWakeLock;
    private Location bestAnchor;
    private String locationStatus;
    private long latestRealLocationTimestamp = Long.MIN_VALUE;
    private long latestLiveElapsedRealtimeNanos = Long.MIN_VALUE;
    private boolean hasReceivedLiveLocation;
    private int locationSessionGeneration;
    private final com.google.android.gms.location.LocationListener fusedLocationListener = this::handleLocationUpdate;
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            handleLocationUpdate(location);
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }
    };
    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            gnssStatus = status;
            lastServerResponse = lastServerResponse.toBuilder()
                    .setSatellites(getSatelliteCount())
                    .build();
            if (running && hasActiveClient() && !lastServerResponse.hasLocationUpdate()) {
                updateNotification("GNSS status changed");
            }
        }
    };
    private volatile LocationProto.ServerResponse lastServerResponse =
            LocationProto.ServerResponse.newBuilder()
                    .setStatus(ServerStatus.UNINITIALIZED.name())
                    .build();
    private final Runnable delayedStopLocationUpdates = this::stopLocationUpdates;

    private NotificationManager notificationManager;
    private GnssStatus gnssStatus;
    private boolean isGnssActive;

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
                    || BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                refreshServer();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            locationWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":LocationCollection"
            );
            locationWakeLock.setReferenceCounted(false);
        }
        transportStatus = getString(R.string.service_starting);
        deadReckoningEstimator = new DeadReckoningEstimator(
                this,
                mainHandler,
                new DeadReckoningEstimator.Listener() {
                    @Override
                    public void onPredictedLocation(@NonNull Location location) {
                        if (isGnssActive && hasActiveClient()) {
                            publishLocation(location);
                        }
                    }

                    @Override
                    public void onStateChanged(@NonNull DeadReckoningEstimator.State state) {
                        if (!isGnssActive) {
                            return;
                        }
                        locationStatus = switch (state) {
                            case INITIALIZING -> getString(R.string.status_inertial_initializing);
                            case ACTIVE -> getString(R.string.status_inertial_active);
                            case WAITING_FOR_ANCHOR -> getString(R.string.status_waiting_for_initial_location);
                            case UNSUPPORTED -> getString(R.string.status_inertial_unsupported);
                        };
                        updateNotification("Inertial state changed");
                    }
                }
        );

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(bluetoothStateReceiver, filter);
        }

        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            );
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }

        running = true;
        instance = this;
        ServiceControl.publishServiceRunning(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refreshServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        ServiceControl.publishServiceStopped(this);
        shuttingDown = true;
        mainHandler.removeCallbacks(delayedStopLocationUpdates);
        closeTransport(getString(R.string.service_stopped));
        stopLocationUpdates();
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        acceptExecutor.shutdownNow();
        clientExecutor.shutdownNow();
        sendExecutor.shutdownNow();
        notificationManager.cancel(NOTIFICATION_ID);
        notificationManager = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isServiceRunning() {
        return running;
    }

    public static void notifyTargetChanged() {
        GNSSServerService service = instance;
        if (service != null) {
            service.applyTargetChange();
        }
    }

    public static String getTransportStatus(Context context) {
        GNSSServerService service = instance;
        if (service != null) {
            synchronized (service.transportLock) {
                String status = service.transportStatus;
                if (service.isGnssActive && service.locationStatus != null) {
                    status += service.getString(R.string.notification_divider) + service.locationStatus;
                }
                return status;
            }
        }
        String address = Preferences.targetDeviceAddress(context);
        return address == null || address.isBlank()
                ? context.getString(R.string.status_select_tablet)
                : context.getString(R.string.service_stopped);
    }

    private void applyTargetChange() {
        closeTransport(getString(R.string.status_target_changing));
        refreshServer();
    }

    private void refreshServer() {
        final int generation;
        final String expectedAddress;
        BluetoothServerSocket oldServerSocket = null;
        ClientHandler oldClient = null;
        String blockedStatus = null;

        synchronized (transportLock) {
            if (shuttingDown) {
                return;
            }

            expectedAddress = Preferences.targetDeviceAddress(this);
            if (!hasBluetoothPermission()) {
                blockedStatus = getString(R.string.status_bluetooth_permission_required);
            } else if (bluetoothAdapter == null) {
                blockedStatus = getString(R.string.status_bluetooth_unsupported);
            } else if (!bluetoothAdapter.isEnabled()) {
                blockedStatus = getString(R.string.status_bluetooth_disabled);
            } else if (expectedAddress == null || expectedAddress.isBlank()) {
                blockedStatus = getString(R.string.status_select_tablet);
            } else if (!isBonded(expectedAddress)) {
                blockedStatus = getString(R.string.status_target_unavailable);
            }

            if (blockedStatus != null) {
                transportGeneration++;
                oldServerSocket = serverSocket;
                serverSocket = null;
                acceptRunning = false;
                oldClient = activeClient;
                activeClient = null;
                if (oldClient != null) {
                    scheduleLocationStopLocked();
                }
                transportStatus = blockedStatus;
                generation = -1;
            } else if (activeClient != null || acceptRunning) {
                return;
            } else {
                generation = ++transportGeneration;
                acceptRunning = true;
                transportStatus = getString(R.string.status_waiting_for_tablet);
            }
        }

        closeServerSocket(oldServerSocket);
        if (oldClient != null) {
            oldClient.disconnect();
        }
        updateNotification("Bluetooth prerequisites changed");
        if (generation >= 0) {
            acceptExecutor.execute(() -> acceptOneClient(expectedAddress, generation));
        }
    }

    private void acceptOneClient(String expectedAddress, int generation) {
        BluetoothServerSocket listeningSocket = null;
        BluetoothSocket acceptedSocket = null;
        try {
            listeningSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                    BluetoothContract.SERVICE_NAME,
                    BluetoothContract.SERVICE_UUID
            );
            synchronized (transportLock) {
                if (shuttingDown || generation != transportGeneration) {
                    if (generation == transportGeneration) {
                        acceptRunning = false;
                    }
                    closeServerSocket(listeningSocket);
                    return;
                }
                serverSocket = listeningSocket;
            }

            Log.i(TAG, "Waiting for RFCOMM client");
            acceptedSocket = listeningSocket.accept();
            String remoteAddress = acceptedSocket.getRemoteDevice().getAddress();
            ClientHandler handler = null;
            boolean authorized = expectedAddress.equalsIgnoreCase(remoteAddress);

            synchronized (transportLock) {
                if (serverSocket == listeningSocket) {
                    serverSocket = null;
                }
                if (generation == transportGeneration) {
                    acceptRunning = false;
                }
                if (!shuttingDown
                        && generation == transportGeneration
                        && activeClient == null
                        && authorized) {
                    handler = new ClientHandler(acceptedSocket);
                    activeClient = handler;
                    mainHandler.removeCallbacks(delayedStopLocationUpdates);
                    transportStatus = getString(R.string.status_tablet_connected);
                }
            }
            closeServerSocket(listeningSocket);

            if (handler == null) {
                if (!authorized) {
                    Log.w(TAG, "Rejected unauthorized Bluetooth device " + remoteAddress);
                }
                closeSocket(acceptedSocket);
                mainHandler.post(this::refreshServer);
                return;
            }

            Log.i(TAG, "Authorized tablet connected: " + remoteAddress);
            ClientHandler authorizedClient = handler;
            mainHandler.post(() -> startLocationUpdates(authorizedClient));
            updateNotification("Tablet connected");
            clientExecutor.execute(handler);
        } catch (IOException | SecurityException e) {
            synchronized (transportLock) {
                if (serverSocket == listeningSocket) {
                    serverSocket = null;
                }
                if (generation == transportGeneration) {
                    acceptRunning = false;
                    if (!shuttingDown && hasBluetoothPermission()
                            && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                        transportStatus = getString(R.string.status_listen_failed, safeMessage(e));
                    }
                }
            }
            closeSocket(acceptedSocket);
            closeServerSocket(listeningSocket);
            if (!shuttingDown && generation == transportGeneration) {
                Log.w(TAG, "RFCOMM listener stopped: " + e.getMessage());
                mainHandler.postDelayed(this::refreshServer, BluetoothContract.RECONNECT_DELAY_MS);
            }
            updateNotification("RFCOMM listener stopped");
        }
    }

    private void closeTransport(String status) {
        BluetoothServerSocket oldServerSocket;
        ClientHandler oldClient;
        synchronized (transportLock) {
            transportGeneration++;
            oldServerSocket = serverSocket;
            serverSocket = null;
            acceptRunning = false;
            oldClient = activeClient;
            activeClient = null;
            transportStatus = status;
            if (oldClient != null) {
                scheduleLocationStopLocked();
            }
        }
        closeServerSocket(oldServerSocket);
        if (oldClient != null) {
            oldClient.disconnect();
        }
    }

    private void scheduleLocationStopLocked() {
        if (shuttingDown || activeClient != null) {
            return;
        }
        mainHandler.removeCallbacks(delayedStopLocationUpdates);
        mainHandler.postDelayed(delayedStopLocationUpdates, LOCATION_STOP_DELAY_MS);
    }

    private void onClientDisconnected(ClientHandler client) {
        boolean removed;
        synchronized (transportLock) {
            removed = activeClient == client;
            if (removed) {
                activeClient = null;
                transportStatus = getString(R.string.status_waiting_for_tablet);
                scheduleLocationStopLocked();
            }
        }
        if (!removed) {
            return;
        }

        Log.i(TAG, "Tablet disconnected: " + client.getClientAddress());
        updateNotification("Tablet disconnected");
        refreshServer();
    }

    private boolean hasActiveClient() {
        synchronized (transportLock) {
            return activeClient != null;
        }
    }

    private void initializeLocationManager() {
        if (locationManager != null) {
            return;
        }
        locationManager = getSystemService(LocationManager.class);
        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler);
            Log.d(TAG, "GNSS status callback registered");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to register GNSS status callback", e);
        }
    }

    private void initializeFusedLocationProviderClient() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !Preferences.fusedLocationEnabled(this)
                || fusedLocationProviderClient != null) {
            return;
        }
        try {
            if (isGooglePlayServicesAvailable(this)) {
                fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
            }
        } catch (NoClassDefFoundError e) {
            Log.w(TAG, "Google Play Services not available on this device", e);
            fusedLocationProviderClient = null;
        }
    }

    public static boolean isFusedLocationSupported(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        try {
            return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                    == ConnectionResult.SUCCESS;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    private void startLocationUpdates(ClientHandler client) {
        synchronized (transportLock) {
            if (activeClient != client) {
                return;
            }
        }
        if (isGnssActive) {
            return;
        }

        initializeLocationManager();
        initializeFusedLocationProviderClient();
        try {
            Log.d(TAG, "Starting location updates");
            lastServerResponse = lastServerResponse.toBuilder()
                    .setStatus(ServerStatus.AWAITING_LOCATION.name())
                    .clearLocationUpdate()
                    .build();
            locationStatus = getString(R.string.status_inertial_initializing);
            latestRealLocationTimestamp = Long.MIN_VALUE;
            latestLiveElapsedRealtimeNanos = Long.MIN_VALUE;
            hasReceivedLiveLocation = false;
            final int sessionGeneration = ++locationSessionGeneration;
            acquireLocationWakeLock();
            isGnssActive = true;
            deadReckoningEstimator.start();
            initializeFromCachedLocations();

            final int minimumIntervalMs = 500;
            final int minimumDistanceMeters = 0;
            if (fusedLocationProviderClient != null) {
                LocationRequest request = new LocationRequest.Builder(minimumIntervalMs)
                        .setMinUpdateDistanceMeters(minimumDistanceMeters)
                        .setWaitForAccurateLocation(false)
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setGranularity(Granularity.GRANULARITY_FINE)
                        .build();
                fusedLocationProviderClient.requestLocationUpdates(
                        request,
                        fusedLocationListener,
                        Looper.getMainLooper()
                );
                fusedLocationProviderClient.getLastLocation().addOnSuccessListener(location -> {
                    if (isGnssActive
                            && sessionGeneration == locationSessionGeneration
                            && !hasReceivedLiveLocation
                            && considerAnchor(location, false)) {
                        initializeEstimatorFromBestAnchor();
                    }
                });
            } else {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        minimumIntervalMs,
                        minimumDistanceMeters,
                        locationListener
                );
            }
            updateNotification("Started location updates");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
            isGnssActive = false;
            deadReckoningEstimator.stop();
            clearLocationResources();
            releaseLocationWakeLock();
            synchronized (transportLock) {
                transportStatus = getString(R.string.status_location_permission_required);
            }
            updateNotification("Location permission missing");
        } catch (Exception e) {
            Log.e(TAG, "Error starting location updates", e);
            isGnssActive = false;
            deadReckoningEstimator.stop();
            clearLocationResources();
            releaseLocationWakeLock();
            updateNotification("Location updates failed");
        }
    }

    private void initializeFromCachedLocations() {
        considerAnchor(Preferences.lastRealLocation(this), false);
        if (locationManager != null) {
            for (String provider : locationManager.getAllProviders()) {
                try {
                    considerAnchor(locationManager.getLastKnownLocation(provider), false);
                } catch (SecurityException e) {
                    throw e;
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Cannot read last location from " + provider, e);
                }
            }
        }
        initializeEstimatorFromBestAnchor();
    }

    private boolean considerAnchor(Location location, boolean persist) {
        if (!DeadReckoningEstimator.isValidRealLocation(location)) {
            return false;
        }
        if (bestAnchor != null && location.getTime() < bestAnchor.getTime()) {
            return false;
        }
        if (persist) {
            Preferences.setLastRealLocation(this, location);
        }
        bestAnchor = new Location(location);
        return true;
    }

    private void initializeEstimatorFromBestAnchor() {
        Location anchor = bestAnchor;
        if (anchor == null || !isGnssActive || anchor.getTime() <= latestRealLocationTimestamp) {
            return;
        }
        correctAndPublishRealLocation(anchor);
    }

    private void correctAndPublishRealLocation(Location location) {
        latestRealLocationTimestamp = location.getTime();
        if (deadReckoningEstimator.correct(location)) {
            Location corrected = deadReckoningEstimator.correctedEstimate(location);
            publishLocation(corrected == null ? location : corrected);
        } else {
            publishLocation(location);
        }
    }

    private void stopLocationUpdates() {
        if (running && hasActiveClient()) {
            return;
        }
        locationSessionGeneration++;
        deadReckoningEstimator.stop();
        bestAnchor = null;
        latestRealLocationTimestamp = Long.MIN_VALUE;
        latestLiveElapsedRealtimeNanos = Long.MIN_VALUE;
        hasReceivedLiveLocation = false;
        locationStatus = null;
        clearLocationResources();
        releaseLocationWakeLock();
        isGnssActive = false;
        lastServerResponse = lastServerResponse.toBuilder()
                .setStatus(ServerStatus.LOCATION_STOPPED.name())
                .clearLocationUpdate()
                .build();
        updateNotification("Stopped location updates");
    }

    @SuppressLint("WakelockTimeout")
    private void acquireLocationWakeLock() {
        if (locationWakeLock != null && !locationWakeLock.isHeld()) {
            locationWakeLock.acquire();
        }
    }

    private void releaseLocationWakeLock() {
        if (locationWakeLock != null && locationWakeLock.isHeld()) {
            locationWakeLock.release();
        }
    }

    private void clearLocationResources() {
        LocationManager currentLocationManager = locationManager;
        locationManager = null;
        if (currentLocationManager != null) {
            try {
                currentLocationManager.removeUpdates(locationListener);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to remove location updates", e);
            }
            try {
                currentLocationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to unregister GNSS status callback", e);
            }
        }
        FusedLocationProviderClient currentFusedClient = fusedLocationProviderClient;
        fusedLocationProviderClient = null;
        if (currentFusedClient != null) {
            try {
                currentFusedClient.removeLocationUpdates(fusedLocationListener);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to remove fused location updates", e);
            }
        }
    }

    private void handleLocationUpdate(Location location) {
        if (!isGnssActive || !DeadReckoningEstimator.isValidRealLocation(location)) {
            return;
        }
        long elapsedRealtimeNanos = location.getElapsedRealtimeNanos();
        if (hasReceivedLiveLocation) {
            boolean stale = elapsedRealtimeNanos > 0 && latestLiveElapsedRealtimeNanos > 0
                    ? elapsedRealtimeNanos <= latestLiveElapsedRealtimeNanos
                    : location.getTime() <= latestRealLocationTimestamp;
            if (stale) {
                return;
            }
        }

        hasReceivedLiveLocation = true;
        latestLiveElapsedRealtimeNanos = elapsedRealtimeNanos;
        Preferences.setLastRealLocation(this, location);
        bestAnchor = new Location(location);
        correctAndPublishRealLocation(bestAnchor);
    }

    private void publishLocation(Location location) {
        if (location == null || !Double.isFinite(location.getLatitude())
                || !Double.isFinite(location.getLongitude())) {
            return;
        }
        String provider = location.getProvider();
        LocationProto.LocationUpdate.Builder builder = LocationProto.LocationUpdate.newBuilder()
                .setTimestamp(location.getTime())
                .setLatitude(location.getLatitude())
                .setLongitude(location.getLongitude())
                .setProvider(provider == null ? "unknown" : provider)
                .setLocationAge("dead_reckoning".equals(provider)
                        ? 0
                        : Math.max(0, (System.currentTimeMillis() - location.getTime()) / 1000.0f));
        if (location.hasAltitude()) {
            builder.setAltitude(location.getAltitude());
        }
        if (location.hasAccuracy()) {
            builder.setAccuracy(location.getAccuracy());
        }
        if (location.hasBearing()) {
            builder.setBearing(location.getBearing());
        }
        if (location.hasSpeed()) {
            builder.setSpeed(location.getSpeed());
        }

        lastServerResponse = lastServerResponse.toBuilder()
                .setStatus(ServerStatus.TRANSMITTING_LOCATION.name())
                .setSatellites(getSatelliteCount())
                .setLocationUpdate(builder.build())
                .build();
        updateNotification("Published location update");

        ClientHandler client;
        synchronized (transportLock) {
            client = activeClient;
        }
        if (client != null) {
            LocationProto.ServerResponse response = lastServerResponse;
            sendExecutor.execute(() -> client.sendResponse(response));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.app_description));
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String content;
        synchronized (transportLock) {
            content = transportStatus;
        }
        if (isGnssActive) {
            content += getString(R.string.notification_divider)
                    + String.format(getString(R.string.notification_satellites), getSatelliteCount());
            if (locationStatus != null) {
                content += getString(R.string.notification_divider) + locationStatus;
            }
            if (lastServerResponse.hasLocationUpdate()) {
                content += getString(R.string.notification_divider)
                        + String.format(
                        getString(R.string.notification_age),
                        (System.currentTimeMillis()
                                - lastServerResponse.getLocationUpdate().getTimestamp()) / 1000.0
                );
            }
        } else {
            content += getString(R.string.notification_divider)
                    + getString(R.string.notification_gnss_inactive);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(String.format(getString(R.string.notification_title), getString(R.string.app_name)))
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> updateNotification(reason));
            return;
        }
        if (notificationManager != null) {
            Log.d(TAG, "Updating notification: " + reason);
            notificationManager.notify(NOTIFICATION_ID, createNotification());
        }
    }

    public int getSatelliteCount() {
        return gnssStatus == null ? 0 : gnssStatus.getSatelliteCount();
    }

    private boolean isGooglePlayServicesAvailable(Context context) {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                == ConnectionResult.SUCCESS;
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isBonded(String address) {
        try {
            Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                if (address.equalsIgnoreCase(device.getAddress())) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read bonded devices", e);
        }
        return false;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static void closeServerSocket(BluetoothServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close Bluetooth server socket", e);
        }
    }

    private static void closeSocket(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close Bluetooth socket", e);
        }
    }

    private final class ClientHandler implements Runnable {
        private final BluetoothSocket socket;
        private final String clientAddress;
        private final Object writeLock = new Object();
        private final AtomicBoolean disconnected = new AtomicBoolean(false);
        private volatile long lastHeartbeatTime = System.currentTimeMillis();
        private volatile long lastResponseTime;

        private final Runnable heartbeatWatchdog = new Runnable() {
            @Override
            public void run() {
                if (disconnected.get()) {
                    return;
                }
                long inactiveFor = System.currentTimeMillis() - lastHeartbeatTime;
                if (inactiveFor > BluetoothContract.STALE_CONNECTION_TIMEOUT_MS) {
                    Log.w(TAG, "Heartbeat timeout for " + clientAddress);
                    disconnect();
                    return;
                }
                mainHandler.postDelayed(this, 250);
            }
        };

        ClientHandler(BluetoothSocket socket) {
            this.socket = socket;
            clientAddress = socket.getRemoteDevice().getAddress();
        }

        String getClientAddress() {
            return clientAddress;
        }

        @Override
        public void run() {
            mainHandler.post(heartbeatWatchdog);
            sendResponse(lastServerResponse);
            try {
                InputStream inputStream = socket.getInputStream();
                byte[] buffer = new byte[1];
                while (!disconnected.get()) {
                    int result = inputStream.read(buffer);
                    if (result < 0) {
                        throw new IOException("Connection closed by client");
                    }
                    if (buffer[0] != BluetoothContract.HEARTBEAT_BYTE) {
                        Log.w(TAG, "Unknown packet from tablet: " + buffer[0]);
                        continue;
                    }

                    lastHeartbeatTime = System.currentTimeMillis();
                    if (lastResponseTime < lastHeartbeatTime - BluetoothContract.RESPONSE_INTERVAL_MS
                            || !lastServerResponse.hasLocationUpdate()) {
                        sendResponse(lastServerResponse);
                    }
                }
            } catch (IOException e) {
                if (!disconnected.get()) {
                    Log.i(TAG, "Tablet disconnected: " + e.getMessage());
                }
            } finally {
                disconnect();
            }
        }

        void sendResponse(LocationProto.ServerResponse response) {
            if (disconnected.get()) {
                return;
            }
            synchronized (writeLock) {
                if (disconnected.get()) {
                    return;
                }
                try {
                    byte[] data = response.toByteArray();
                    OutputStream output = socket.getOutputStream();
                    output.write(intToBytes(data.length));
                    output.write(data);
                    output.flush();
                    lastResponseTime = System.currentTimeMillis();
                } catch (IOException e) {
                    Log.w(TAG, "Failed to send GNSS response", e);
                    disconnect();
                }
            }
        }

        void disconnect() {
            if (!disconnected.compareAndSet(false, true)) {
                return;
            }
            mainHandler.removeCallbacks(heartbeatWatchdog);
            closeSocket(socket);
            onClientDisconnected(this);
        }

        private byte[] intToBytes(int value) {
            return new byte[]{
                    (byte) (value >>> 24),
                    (byte) (value >>> 16),
                    (byte) (value >>> 8),
                    (byte) value
            };
        }
    }
}
