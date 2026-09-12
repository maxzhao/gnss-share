/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dezz.gnssshare.client;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import dezz.gnssshare.shared.BluetoothContract;

public final class ConnectionManager {
    private static final String TAG = "ConnectionManager";

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    public interface ConnectionListener {
        void onConnectionStateChanged(ConnectionState state, String message, String targetDescription);

        void onConnectionEstablished(
                BluetoothSocket socket,
                String targetDescription,
                int connectionGeneration
        );

        void onDisconnected(BluetoothSocket socket, int connectionGeneration);

        void onShutdownComplete();
    }

    private final Context context;
    private final ConnectionListener listener;
    private final BluetoothAdapter bluetoothAdapter;
    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private final Runnable reconnectRunnable = this::refreshConnection;
    private final Runnable responseWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            BluetoothSocket connectedSocket;
            long inactiveFor;
            synchronized (lock) {
                connectedSocket = socket;
                inactiveFor = System.currentTimeMillis() - lastServerActivity;
            }
            if (connectedSocket == null) {
                return;
            }
            if (inactiveFor > BluetoothContract.STALE_CONNECTION_TIMEOUT_MS) {
                connectionLost(connectedSocket, context.getString(R.string.status_server_response_timeout));
                return;
            }
            mainHandler.postDelayed(this, 250);
        }
    };

    private boolean shutdown;
    private boolean connectAttemptRunning;
    private int pendingControlActions;
    private int activeSocketUses;
    private int generation;
    private BluetoothSocket pendingSocket;
    private BluetoothSocket socket;
    private ConnectionState currentState = ConnectionState.DISCONNECTED;
    private String currentMessage;
    private String targetDescription;
    private long lastServerActivity;
    private long stateVersion;

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
                    || BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                refreshConnection();
            }
        }
    };

    public ConnectionManager(Context context, ConnectionListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bluetoothManager = this.context.getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            this.context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            this.context.registerReceiver(bluetoothStateReceiver, filter);
        }
    }

    public void start() {
        refreshConnection();
    }

    public void targetChanged() {
        executeControl(this::targetChangedInternal);
    }

    private void targetChangedInternal() {
        BluetoothSocket oldPending;
        BluetoothSocket oldSocket;
        int oldGeneration;
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            beginRevocationLocked();
            try {
                oldGeneration = generation;
                generation++;
                oldPending = pendingSocket;
                oldSocket = socket;
                pendingSocket = null;
                socket = null;
                connectAttemptRunning = false;
            } finally {
                endRevocationLocked();
            }
        }
        cancelTimers();
        closeSocket(oldPending);
        closeSocket(oldSocket);
        if (oldSocket != null) {
            mainHandler.post(() -> listener.onDisconnected(oldSocket, oldGeneration));
        }
        mainHandler.post(this::refreshConnection);
    }

    public void refreshConnection() {
        executeControl(this::refreshConnectionInternal);
    }

    private void refreshConnectionInternal() {
        final String address = Preferences.targetDeviceAddress(context);
        final String savedName = Preferences.targetDeviceName(context);
        final String description = describeTarget(savedName, address);
        final int attemptGeneration;
        BluetoothSocket socketToClose = null;
        BluetoothSocket disconnectedSocket = null;
        int disconnectedGeneration = -1;
        String unavailableReason = null;

        if (!hasBluetoothPermission()) {
            unavailableReason = context.getString(R.string.bluetooth_permission_required);
        } else if (bluetoothAdapter == null) {
            unavailableReason = context.getString(R.string.status_bluetooth_unsupported);
        } else if (!bluetoothAdapter.isEnabled()) {
            unavailableReason = context.getString(R.string.status_bluetooth_disabled);
        } else if (address == null || address.isBlank()) {
            unavailableReason = context.getString(R.string.status_select_phone);
        } else if (!isBonded(address)) {
            unavailableReason = context.getString(R.string.status_target_unavailable);
        }

        synchronized (lock) {
            if (shutdown) {
                return;
            }
            targetDescription = description;

            if (unavailableReason != null) {
                beginRevocationLocked();
                try {
                    disconnectedGeneration = generation;
                    generation++;
                    disconnectedSocket = socket;
                    socketToClose = pendingSocket != null ? pendingSocket : disconnectedSocket;
                    boolean hadConnectedSocket = disconnectedSocket != null;
                    pendingSocket = null;
                    socket = null;
                    connectAttemptRunning = false;
                    cancelTimers();
                    setStateLocked(ConnectionState.DISCONNECTED, unavailableReason, description);
                    if (!hadConnectedSocket) {
                        disconnectedGeneration = -1;
                    }
                    attemptGeneration = -1;
                } finally {
                    endRevocationLocked();
                }
            } else if (socket != null || connectAttemptRunning) {
                return;
            } else {
                connectAttemptRunning = true;
                attemptGeneration = ++generation;
                setStateLocked(
                        ConnectionState.CONNECTING,
                        context.getString(R.string.status_connecting_saved_phone),
                        description
                );
            }
        }

        closeSocket(socketToClose);
        if (disconnectedGeneration >= 0 && disconnectedSocket != null) {
            BluetoothSocket socketToReport = disconnectedSocket;
            int generationToReport = disconnectedGeneration;
            mainHandler.post(() -> listener.onDisconnected(
                    socketToReport,
                    generationToReport
            ));
        }
        if (attemptGeneration >= 0) {
            connectExecutor.execute(() -> connect(address, description, attemptGeneration));
        }
    }

    private void connect(String address, String description, int attemptGeneration) {
        BluetoothSocket candidate = null;
        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
            candidate = device.createRfcommSocketToServiceRecord(BluetoothContract.SERVICE_UUID);
            boolean staleAttempt;
            synchronized (lock) {
                staleAttempt = shutdown || generation != attemptGeneration;
                if (!staleAttempt) {
                    pendingSocket = candidate;
                }
            }
            if (staleAttempt) {
                closeSocket(candidate);
                return;
            }

            Log.i(TAG, "Connecting to saved phone " + description);
            candidate.connect();

            synchronized (lock) {
                staleAttempt = shutdown || generation != attemptGeneration;
                if (!staleAttempt) {
                    pendingSocket = null;
                    socket = candidate;
                    connectAttemptRunning = false;
                    lastServerActivity = System.currentTimeMillis();
                }
            }
            if (staleAttempt) {
                closeSocket(candidate);
                return;
            }

            BluetoothSocket connectedSocket = candidate;
            startHeartbeatLoop(connectedSocket);
            mainHandler.post(responseWatchdogRunnable);
            mainHandler.post(() -> deliverConnection(
                    connectedSocket,
                    description,
                    attemptGeneration
            ));
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            Log.w(TAG, "Bluetooth connection failed: " + e.getMessage());
            boolean retry;
            synchronized (lock) {
                retry = !shutdown && generation == attemptGeneration;
                if (retry) {
                    pendingSocket = null;
                    connectAttemptRunning = false;
                }
            }
            closeSocket(candidate);
            if (retry) {
                scheduleReconnect(context.getString(R.string.status_connection_failed_retrying));
            }
        }
    }

    private void deliverConnection(
            BluetoothSocket connectedSocket,
            String description,
            int attemptGeneration
    ) {
        boolean delivered = runIfSocketCurrent(
                connectedSocket,
                attemptGeneration,
                () -> listener.onConnectionEstablished(
                        connectedSocket,
                        description,
                        attemptGeneration
                )
        );
        if (!delivered) {
            closeSocket(connectedSocket);
        }
    }

    private void startHeartbeatLoop(BluetoothSocket connectedSocket) {
        connectExecutor.execute(() -> {
            while (isCurrentSocket(connectedSocket)) {
                try {
                    connectedSocket.getOutputStream().write(BluetoothContract.HEARTBEAT_BYTE);
                    connectedSocket.getOutputStream().flush();
                    Log.v(TAG, "Heartbeat sent");
                    Thread.sleep(BluetoothContract.HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException e) {
                    Log.w(TAG, "Failed to send heartbeat", e);
                    connectionLost(
                            connectedSocket,
                            context.getString(R.string.status_connection_lost_retrying)
                    );
                    return;
                }
            }
        });
    }

    private boolean isCurrentSocket(BluetoothSocket candidate) {
        synchronized (lock) {
            return !shutdown && socket == candidate;
        }
    }

    public void onServerActivity(BluetoothSocket activeSocket) {
        synchronized (lock) {
            if (socket != activeSocket) {
                return;
            }
            lastServerActivity = System.currentTimeMillis();
            setStateLocked(
                    ConnectionState.CONNECTED,
                    context.getString(R.string.status_receiving_gnss),
                    targetDescription
            );
        }
    }

    public void connectionLost(BluetoothSocket lostSocket, String message) {
        executeControl(() -> connectionLostInternal(lostSocket, message));
    }

    private void connectionLostInternal(BluetoothSocket lostSocket, String message) {
        final int disconnectedGeneration;
        synchronized (lock) {
            if (socket != lostSocket) {
                disconnectedGeneration = -1;
            } else {
                beginRevocationLocked();
            try {
                disconnectedGeneration = generation;
                generation++;
                socket = null;
                setStateLocked(ConnectionState.DISCONNECTED, message, targetDescription);
                } finally {
                    endRevocationLocked();
                }
            }
        }
        closeSocket(lostSocket);
        if (disconnectedGeneration < 0) {
            return;
        }
        cancelTimers();
        mainHandler.post(() -> listener.onDisconnected(
                lostSocket,
                disconnectedGeneration
        ));
        scheduleReconnectInternal(message);
    }

    private void scheduleReconnect(String message) {
        executeControl(() -> scheduleReconnectInternal(message));
    }

    private void scheduleReconnectInternal(String message) {
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            setStateLocked(ConnectionState.DISCONNECTED, message, targetDescription);
        }
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, BluetoothContract.RECONNECT_DELAY_MS);
    }

    public ConnectionState getCurrentState() {
        synchronized (lock) {
            return currentState;
        }
    }

    public String getTargetDescription() {
        synchronized (lock) {
            return targetDescription;
        }
    }

    public String getCurrentMessage() {
        synchronized (lock) {
            return currentMessage;
        }
    }

    public boolean ownsSocket(BluetoothSocket candidate, int expectedGeneration) {
        synchronized (lock) {
            return !shutdown
                    && generation == expectedGeneration
                    && socket == candidate;
        }
    }

    public boolean commitFrameIfCurrent(
            BluetoothSocket candidate,
            int expectedGeneration,
            Runnable commit
    ) {
        return runIfSocketCurrent(candidate, expectedGeneration, commit, true);
    }

    private boolean runIfSocketCurrent(
            BluetoothSocket candidate,
            int expectedGeneration,
            Runnable action
    ) {
        return runIfSocketCurrent(candidate, expectedGeneration, action, false);
    }

    private boolean runIfSocketCurrent(
            BluetoothSocket candidate,
            int expectedGeneration,
            Runnable action,
            boolean waitForControl
    ) {
        synchronized (lock) {
            while (waitForControl && !shutdown && pendingControlActions > 0) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (shutdown
                    || pendingControlActions > 0
                    || socket != candidate
                    || (expectedGeneration >= 0 && generation != expectedGeneration)) {
                return false;
            }
            activeSocketUses++;
        }
        try {
            action.run();
            return true;
        } finally {
            synchronized (lock) {
                activeSocketUses--;
                if (activeSocketUses == 0) {
                    lock.notifyAll();
                }
            }
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return currentState == ConnectionState.CONNECTED && socket != null;
        }
    }

    public void shutdown() {
        synchronized (lock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
        }
        cancelTimers();
        try {
            context.unregisterReceiver(bluetoothStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        executeControl(this::shutdownInternal);
    }

    private void shutdownInternal() {
        BluetoothSocket oldPending;
        BluetoothSocket oldSocket;
        synchronized (lock) {
            beginRevocationLocked();
            try {
                generation++;
                oldPending = pendingSocket;
                oldSocket = socket;
                pendingSocket = null;
                socket = null;
                connectAttemptRunning = false;
                setStateLocked(
                        ConnectionState.DISCONNECTED,
                        context.getString(R.string.status_shutting_down),
                        targetDescription
                );
            } finally {
                endRevocationLocked();
            }
        }
        closeSocket(oldPending);
        closeSocket(oldSocket);
        connectExecutor.shutdownNow();
        mainHandler.post(listener::onShutdownComplete);
        controlExecutor.shutdown();
    }

    private void cancelTimers() {
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(responseWatchdogRunnable);
    }

    private void executeControl(Runnable action) {
        synchronized (lock) {
            pendingControlActions++;
        }
        try {
            controlExecutor.execute(() -> {
                try {
                    action.run();
                } finally {
                    synchronized (lock) {
                        pendingControlActions--;
                        lock.notifyAll();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (lock) {
                pendingControlActions--;
                lock.notifyAll();
            }
            Log.d(TAG, "Ignoring control action after shutdown");
        }
    }

    private void beginRevocationLocked() {
        boolean interrupted = false;
        while (activeSocketUses > 0) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void endRevocationLocked() {
        lock.notifyAll();
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
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

    private void setStateLocked(ConnectionState newState, String message, String description) {
        if (currentState == newState
                && Objects.equals(currentMessage, message)
                && Objects.equals(targetDescription, description)) {
            return;
        }
        Log.d(TAG, "State change: " + currentState + " -> " + newState + " (" + message + ")");
        currentState = newState;
        currentMessage = message;
        targetDescription = description;
        long version = ++stateVersion;
        int stateGeneration = generation;
        mainHandler.post(() -> deliverStateIfCurrent(
                version,
                stateGeneration,
                newState,
                message,
                description
        ));
    }

    private void deliverStateIfCurrent(
            long version,
            int expectedGeneration,
            ConnectionState state,
            String message,
            String description
    ) {
        synchronized (lock) {
            if (shutdown || generation != expectedGeneration || stateVersion != version) {
                return;
            }
            activeSocketUses++;
        }
        try {
            listener.onConnectionStateChanged(state, message, description);
        } finally {
            synchronized (lock) {
                activeSocketUses--;
                if (activeSocketUses == 0) {
                    lock.notifyAll();
                }
            }
        }
    }

    private static String describeTarget(String name, String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        if (name == null || name.isBlank() || name.equals(address)) {
            return address;
        }
        return name + " (" + address + ")";
    }

    private static void closeSocket(BluetoothSocket bluetoothSocket) {
        if (bluetoothSocket == null) {
            return;
        }
        try {
            bluetoothSocket.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close Bluetooth socket", e);
        }
    }
}
