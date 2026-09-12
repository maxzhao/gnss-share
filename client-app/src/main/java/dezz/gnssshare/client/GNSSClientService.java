/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dezz.gnssshare.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.gnssshare.proto.LocationProto;
import dezz.gnssshare.shared.BluetoothContract;

public class GNSSClientService extends Service implements ConnectionManager.ConnectionListener {
    private static final String TAG = "GNSSClientService";
    private static final String CHANNEL_ID = "GNSSClientChannel";
    private static final int NOTIFICATION_ID = 1;

    private static GNSSClientService instance;
    private static long lastUpdateTime;

    private ConnectionManager connectionManager;
    private MockLocationManager mockLocationManager;
    private NotificationManager notificationManager;
    private final ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();

    private volatile BluetoothSocket currentSocket;
    private volatile int currentConnectionGeneration = -1;
    private Location lastReceivedLocation;
    private long lastLocationTimestamp;
    private int lastBroadcastSatelliteCount = -1;

    private static final String WIDGET_SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    private static final String WIDGET_PACKAGE = "dezz.status.widget";

    public static boolean isServiceRunning() {
        return instance != null;
    }

    public static ConnectionManager.ConnectionState getConnectionState() {
        return instance != null && instance.connectionManager != null
                ? instance.connectionManager.getCurrentState()
                : ConnectionManager.ConnectionState.DISCONNECTED;
    }

    public static String getTargetDescription(Context context) {
        if (instance != null && instance.connectionManager != null) {
            return instance.connectionManager.getTargetDescription();
        }
        String address = Preferences.targetDeviceAddress(context);
        String name = Preferences.targetDeviceName(context);
        if (address == null || address.isBlank()) {
            return null;
        }
        return name == null || name.isBlank() || name.equals(address)
                ? address
                : name + " (" + address + ")";
    }

    public static String getConnectionMessage() {
        return instance != null && instance.connectionManager != null
                ? instance.connectionManager.getCurrentMessage()
                : null;
    }

    public static void notifyTargetChanged() {
        GNSSClientService service = instance;
        if (service != null && service.connectionManager != null) {
            service.connectionManager.targetChanged();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        mockLocationManager = new MockLocationManager(this);
        connectionManager = new ConnectionManager(this, this);
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    createNotification(false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            );
        } else {
            startForeground(NOTIFICATION_ID, createNotification(false));
        }

        instance = this;
        connectionManager.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        connectionManager.refreshConnection();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        BluetoothSocket socket = currentSocket;
        currentSocket = null;
        currentConnectionGeneration = -1;
        if (connectionManager != null) {
            connectionManager.shutdown();
        }
        closeSocket(socket);
        receiverExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConnectionStateChanged(
            ConnectionManager.ConnectionState state,
            String message,
            String targetDescription
    ) {
        Log.d(TAG, "Connection state: " + state + " - " + message);
        updateNotification();
        sendBroadcast(new Intent("dezz.gnssshare.CONNECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("state", state.toString())
                .putExtra("message", message)
                .putExtra("targetDescription", targetDescription));
    }

    @Override
    public void onConnectionEstablished(
            BluetoothSocket socket,
            String targetDescription,
            int connectionGeneration
    ) {
        if (connectionManager == null
                || !connectionManager.ownsSocket(socket, connectionGeneration)) {
            Log.i(TAG, "Ignoring stale Bluetooth connection delivery");
            closeSocket(socket);
            return;
        }
        Log.i(TAG, "Bluetooth connection established, starting location receiver");
        BluetoothSocket oldSocket = currentSocket;
        currentSocket = socket;
        currentConnectionGeneration = connectionGeneration;
        if (oldSocket != null && oldSocket != socket) {
            closeSocket(oldSocket);
        }
        startReceivingLocationUpdates(socket, connectionGeneration);
    }

    @Override
    public void onDisconnected(BluetoothSocket socket, int connectionGeneration) {
        if (currentSocket != socket || currentConnectionGeneration != connectionGeneration) {
            Log.i(TAG, "Ignoring stale Bluetooth disconnection");
            return;
        }
        Log.i(TAG, "Bluetooth connection lost, stopping location updates");
        ConnectionManager.ConnectionState state = connectionManager == null
                ? ConnectionManager.ConnectionState.DISCONNECTED
                : connectionManager.getCurrentState();
        String message = connectionManager == null ? null : connectionManager.getCurrentMessage();
        currentSocket = null;
        currentConnectionGeneration = -1;
        lastLocationTimestamp = 0;
        lastBroadcastSatelliteCount = -1;
        broadcastSatelliteStatusToWidget(0);
        stopReceivingLocationUpdates();
        sendBroadcast(new Intent("dezz.gnssshare.CONNECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("state", state.toString())
                .putExtra("message", message)
                .putExtra("targetDescription", getTargetDescription(this)));
    }

    @Override
    public void onShutdownComplete() {
        if (mockLocationManager != null) {
            mockLocationManager.shutdown();
        }
    }

    private void startReceivingLocationUpdates(BluetoothSocket socket, int connectionGeneration) {
        if (!MockLocationManager.isMockLocationEnabled(getContentResolver())) {
            Log.w(TAG, "Mock locations not enabled - please enable in Developer Options");
            broadcastMockLocationStatus(getString(R.string.mock_location_enable_message), true);
        }

        try {
            mockLocationManager.startMockLocationProvider();
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up mock location provider", e);
            broadcastMockLocationStatus(
                    String.format(getString(R.string.mock_location_setup_failed), e.getMessage()),
                    true
            );
        }

        receiverExecutor.execute(() -> receiveLocationUpdates(socket, connectionGeneration));
    }

    private void receiveLocationUpdates(BluetoothSocket socket, int connectionGeneration) {
        try {
            InputStream inputStream = socket.getInputStream();
            byte[] lengthBytes = new byte[BluetoothContract.FRAME_HEADER_BYTES];

            while (currentSocket == socket) {
                readFully(inputStream, lengthBytes);
                int messageLength = bytesToInt(lengthBytes);
                if (messageLength <= 0 || messageLength > BluetoothContract.MAX_FRAME_BYTES) {
                    throw new IOException("Invalid frame length: " + messageLength);
                }

                byte[] messageData = new byte[messageLength];
                readFully(inputStream, messageData);
                LocationProto.ServerResponse response = LocationProto.ServerResponse.parseFrom(messageData);
                boolean committed = connectionManager.commitFrameIfCurrent(
                        socket,
                        connectionGeneration,
                        () -> {
                            connectionManager.onServerActivity(socket);
                            if (response.hasLocationUpdate()) {
                                handleLocationUpdate(response);
                            } else {
                                Log.i(TAG, "Server status: " + response.getStatus());
                                sendBroadcast(new Intent("dezz.gnssshare.LOCATION_UPDATE")
                                        .setPackage(getPackageName())
                                        .putExtra("satellites", response.getSatellites()));
                            }
                            broadcastSatelliteStatusToWidget(response.getSatellites());
                        }
                );
                if (!committed) {
                    break;
                }
            }
        } catch (IOException e) {
            if (currentSocket == socket) {
                Log.w(TAG, "Location receiver stopped: " + e.getMessage());
            }
        } finally {
            connectionManager.connectionLost(
                    socket,
                    getString(R.string.status_connection_lost_retrying)
            );
        }
    }

    private static void readFully(InputStream inputStream, byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int read = inputStream.read(data, offset, data.length - offset);
            if (read < 0) {
                throw new IOException("Connection closed by server");
            }
            offset += read;
        }
    }

    private void stopReceivingLocationUpdates() {
        if (instance == null) {
            mockLocationManager.shutdown();
        } else {
            mockLocationManager.stopMockLocationProvider(5000);
        }
    }

    private static int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24)
                | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
    }

    private void handleLocationUpdate(LocationProto.ServerResponse response) {
        try {
            LocationProto.LocationUpdate locationUpdate = response.getLocationUpdate();
            Location location = new Location(LocationManager.GPS_PROVIDER);
            location.setLatitude(locationUpdate.getLatitude());
            location.setLongitude(locationUpdate.getLongitude());
            location.setTime(locationUpdate.getTimestamp());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            location.setAltitude(locationUpdate.getAltitude());
            location.setAccuracy(locationUpdate.getAccuracy());
            location.setBearing(locationUpdate.getBearing());
            location.setSpeed(locationUpdate.getSpeed());

            Log.i(TAG, "Received location update: " + location);
            lastReceivedLocation = location;
            lastUpdateTime = System.currentTimeMillis();
            updateNotification();

            sendBroadcast(new Intent("dezz.gnssshare.LOCATION_UPDATE")
                    .setPackage(getPackageName())
                    .putExtra("location", location)
                    .putExtra("satellites", response.getSatellites())
                    .putExtra("provider", locationUpdate.getProvider())
                    .putExtra("locationAge", locationUpdate.getLocationAge()));

            long gpsTimestamp = locationUpdate.getTimestamp();
            if (gpsTimestamp != lastLocationTimestamp) {
                lastLocationTimestamp = gpsTimestamp;
                mockLocationManager.setMockLocation(location);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting mock location", e);
            broadcastMockLocationStatus(
                    String.format(getString(R.string.mock_location_setup_failed), e.getMessage()),
                    true
            );
        }
    }

    private void broadcastMockLocationStatus(String message, boolean error) {
        sendBroadcast(new Intent("dezz.gnssshare.MOCK_LOCATION_STATUS")
                .setPackage(getPackageName())
                .putExtra("message", message)
                .putExtra("error", error));
    }

    private void broadcastSatelliteStatusToWidget(int count) {
        if (count == lastBroadcastSatelliteCount) {
            return;
        }
        lastBroadcastSatelliteCount = count;
        sendBroadcast(new Intent(WIDGET_SATELLITE_STATUS_ACTION)
                .setPackage(WIDGET_PACKAGE)
                .putExtra("count", count));
    }

    public static long getLastUpdateTime() {
        return lastUpdateTime;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(String.format(
                getString(R.string.notification_channel_description),
                getString(R.string.app_name)
        ));
        notificationManager.createNotificationChannel(channel);
    }

    private Notification createNotification(boolean isConnected) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String title = isConnected
                ? String.format(getString(R.string.notification_title_connected), getString(R.string.app_name))
                : String.format(getString(R.string.notification_title_disconnected), getString(R.string.app_name));
        String text = isConnected
                ? (lastReceivedLocation != null
                ? String.format(
                        getString(R.string.notification_text_connected),
                        (System.currentTimeMillis() - lastUpdateTime) / 1000.0
                )
                : getString(R.string.notification_text_connected_no_age))
                : getString(R.string.notification_text_disconnected);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        if (notificationManager != null) {
            notificationManager.notify(
                    NOTIFICATION_ID,
                    createNotification(connectionManager != null && connectionManager.isConnected())
            );
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
}
