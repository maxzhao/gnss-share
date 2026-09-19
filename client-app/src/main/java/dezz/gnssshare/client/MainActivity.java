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
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.IntentCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import dezz.gnssshare.shared.LogExporter;
import dezz.gnssshare.shared.VersionGetter;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GNSSClientActivity";

    private TextView statusText;
    private TextView connectionText;
    private TextView dataAgeText;
    private TextView locationText;
    private TextView satellitesText;
    private TextView providerText;
    private TextView ageText;
    private TextView additionalInfoText;
    private View permissionsSection;
    private Button requestPermissionsButton;
    private TextView permissionsStatusText;
    private TextView mockLocationStatusText;
    private TextView serviceStatusText;
    private TextView targetDeviceText;
    private Button selectDeviceButton;
    private Button serviceControlButton;

    private BluetoothAdapter bluetoothAdapter;
    private boolean permissionRequestInFlight;
    private boolean permissionDeclined;
    private boolean bluetoothEnableRequestInFlight;
    private boolean bluetoothEnableDeclined;
    private boolean openPickerAfterPrerequisites;
    private boolean manualStopRequested;
    private String appVersion = "<unknown>";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateDynamicInfo();
            updateServiceAndTargetStatus();
            uiHandler.postDelayed(this, 1000);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                permissionRequestInFlight = false;
                permissionDeclined = !hasRequiredPermissions();
                if (permissionDeclined) {
                    Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                    updatePermissionsStatus();
                    return;
                }
                continueStartup();
            });

    private final ActivityResultLauncher<Intent> bluetoothEnableLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                bluetoothEnableRequestInFlight = false;
                bluetoothEnableDeclined = bluetoothAdapter == null || !bluetoothAdapter.isEnabled();
                if (bluetoothEnableDeclined) {
                    Toast.makeText(this, R.string.bluetooth_enable_required, Toast.LENGTH_LONG).show();
                }
                continueStartup();
            });

    private final ActivityResultLauncher<Intent> mockLocationSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                updatePermissionsStatus();
                continueStartup();
            });

    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"dezz.gnssshare.CONNECTION_CHANGED".equals(intent.getAction())) {
                return;
            }
            String stateValue = intent.getStringExtra("state");
            if (stateValue == null) {
                return;
            }
            ConnectionManager.ConnectionState state = ConnectionManager.ConnectionState.valueOf(stateValue);
            updateConnectionStatus(
                    state,
                    intent.getStringExtra("targetDescription"),
                    intent.getStringExtra("message")
            );
        }
    };

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!"dezz.gnssshare.LOCATION_UPDATE".equals(intent.getAction())) {
                return;
            }
            updateSatelliteInfo(intent.getIntExtra("satellites", 0));
            Location location = IntentCompat.getParcelableExtra(intent, "location", Location.class);
            if (location != null) {
                updateLocationInfo(
                        location,
                        intent.getStringExtra("provider"),
                        intent.getFloatExtra("locationAge", 0)
                );
            }
        }
    };

    private final BroadcastReceiver mockLocationStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("dezz.gnssshare.MOCK_LOCATION_STATUS".equals(intent.getAction())) {
                updatePermissionsStatus(
                        intent.getStringExtra("message"),
                        intent.getBooleanExtra("error", true)
                );
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        setContentView(R.layout.activity_main);

        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        appVersion = VersionGetter.getAppVersionName(this);

        initializeViews();
        registerReceivers();
        updatePermissionsStatus();
        updateServiceAndTargetStatus();
        updateConnectionStatus(
                GNSSClientService.getConnectionState(),
                GNSSClientService.getTargetDescription(this),
                GNSSClientService.getConnectionMessage()
        );
        continueStartup();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(ServiceControl.EXTRA_START_FROM_TILE, false)) {
            manualStopRequested = false;
            continueStartup();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        manualStopRequested = false;
        uiHandler.removeCallbacks(uiUpdateRunnable);
        uiHandler.post(uiUpdateRunnable);
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            bluetoothEnableDeclined = false;
        }
        continueStartup();
    }

    @Override
    protected void onStop() {
        uiHandler.removeCallbacks(uiUpdateRunnable);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(connectionReceiver);
        unregisterReceiver(locationReceiver);
        unregisterReceiver(mockLocationStatusReceiver);
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void initializeViews() {
        statusText = findViewById(R.id.statusText);
        connectionText = findViewById(R.id.connectionText);
        dataAgeText = findViewById(R.id.dataAgeText);
        locationText = findViewById(R.id.locationText);
        satellitesText = findViewById(R.id.satellitesText);
        providerText = findViewById(R.id.providerText);
        ageText = findViewById(R.id.ageText);
        additionalInfoText = findViewById(R.id.additionalInfoText);
        permissionsSection = findViewById(R.id.permissionsSection);
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);
        mockLocationStatusText = findViewById(R.id.mockLocationStatusText);
        serviceStatusText = findViewById(R.id.serviceStatusText);
        targetDeviceText = findViewById(R.id.targetDeviceText);
        selectDeviceButton = findViewById(R.id.selectDeviceButton);
        serviceControlButton = findViewById(R.id.serviceControlButton);

        dataAgeText.setText(String.format(
                getString(R.string.data_age_status),
                getString(R.string.unknown)
        ));
        additionalInfoText.setText(String.format(
                "%s  %s",
                String.format(getString(R.string.movement_speed), getString(R.string.unknown)),
                String.format(getString(R.string.movement_bearing), getString(R.string.unknown))
        ));

        requestPermissionsButton.setOnClickListener(view -> {
            permissionRequestInFlight = false;
            permissionDeclined = false;
            requestMissingPermissions();
        });
        selectDeviceButton.setOnClickListener(view -> {
            openPickerAfterPrerequisites = true;
            continueStartup();
        });
        serviceControlButton.setOnClickListener(view -> {
            if (GNSSClientService.isServiceRunning()) {
                manualStopRequested = true;
                ServiceControl.stopService(this);
                updateServiceAndTargetStatus();
            } else {
                manualStopRequested = false;
                continueStartup();
            }
        });
        findViewById(R.id.mockLocationSettingsButton).setOnClickListener(view -> checkMockLocationSettings());
        findViewById(R.id.exportLogsButton).setOnClickListener(view -> exportLogs("gnss-client"));

        CheckBox staticJitterCheckbox = findViewById(R.id.staticJitterCheckbox);
        staticJitterCheckbox.setChecked(Preferences.staticJitterEnabled(this));
        staticJitterCheckbox.setOnCheckedChangeListener((buttonView, checked) ->
                Preferences.setStaticJitterEnabled(this, checked));
    }

    private void registerReceivers() {
        registerReceiver(
                connectionReceiver,
                new IntentFilter("dezz.gnssshare.CONNECTION_CHANGED"),
                RECEIVER_NOT_EXPORTED
        );
        registerReceiver(
                locationReceiver,
                new IntentFilter("dezz.gnssshare.LOCATION_UPDATE"),
                RECEIVER_NOT_EXPORTED
        );
        registerReceiver(
                mockLocationStatusReceiver,
                new IntentFilter("dezz.gnssshare.MOCK_LOCATION_STATUS"),
                RECEIVER_NOT_EXPORTED
        );
    }

    private void continueStartup() {
        updatePermissionsStatus();
        updateServiceAndTargetStatus();
        if (!hasRequiredPermissions()) {
            if (!permissionDeclined) {
                requestMissingPermissions();
            }
            return;
        }
        if (bluetoothAdapter == null) {
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            if (!bluetoothEnableRequestInFlight && !bluetoothEnableDeclined) {
                bluetoothEnableRequestInFlight = true;
                bluetoothEnableLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            }
            return;
        }
        bluetoothEnableDeclined = false;

        if (!manualStopRequested && !GNSSClientService.isServiceRunning()) {
            ServiceControl.startService(this);
        }
        if (openPickerAfterPrerequisites) {
            openPickerAfterPrerequisites = false;
            showBluetoothDevicePicker();
        }
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED);
    }

    private void requestMissingPermissions() {
        if (permissionRequestInFlight) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!missing.isEmpty()) {
            permissionRequestInFlight = true;
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void checkMockLocationSettings() {
        Toast.makeText(this, R.string.mock_location_enable_message, Toast.LENGTH_LONG).show();
        try {
            mockLocationSettingsLauncher.launch(
                    new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            );
        } catch (Exception e) {
            mockLocationSettingsLauncher.launch(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void updatePermissionsStatus() {
        updatePermissionsStatus(null, false);
    }

    private void updatePermissionsStatus(String mockLocationsErrorMessage, boolean mockLocationError) {
        List<String> missingPermissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(getString(R.string.permission_fine_location));
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(getString(R.string.permission_coarse_location));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(getString(R.string.permission_bluetooth_connect));
        }

        boolean permissionsGranted = missingPermissions.isEmpty();
        if (permissionsGranted) {
            permissionsStatusText.setText(R.string.all_permissions_granted);
            permissionsStatusText.setTextColor(getColor(android.R.color.holo_green_light));
            requestPermissionsButton.setVisibility(View.GONE);
        } else {
            permissionsStatusText.setText(String.format(
                    getString(R.string.missing_permissions),
                    String.join(", ", missingPermissions)
            ));
            permissionsStatusText.setTextColor(getColor(android.R.color.holo_red_light));
            requestPermissionsButton.setVisibility(View.VISIBLE);
        }

        boolean mockLocationEnabled = MockLocationManager.isMockLocationEnabled(getContentResolver());
        if (mockLocationEnabled && !mockLocationError) {
            mockLocationStatusText.setVisibility(View.GONE);
        } else {
            mockLocationStatusText.setText(mockLocationError && mockLocationsErrorMessage != null
                    ? mockLocationsErrorMessage
                    : getString(R.string.mock_location_enable_message));
            mockLocationStatusText.setVisibility(View.VISIBLE);
        }
        permissionsSection.setVisibility(
                permissionsGranted && mockLocationEnabled && !mockLocationError ? View.GONE : View.VISIBLE
        );
    }

    private void updateServiceAndTargetStatus() {
        String address = Preferences.targetDeviceAddress(this);
        String name = Preferences.targetDeviceName(this);
        if (address == null || address.isBlank()) {
            targetDeviceText.setText(R.string.target_device_not_selected);
            selectDeviceButton.setText(R.string.select_phone);
        } else {
            targetDeviceText.setText(String.format(
                    getString(R.string.target_device_selected),
                    name == null || name.isBlank() ? address : name,
                    address
            ));
            selectDeviceButton.setText(R.string.change_phone);
        }

        boolean serviceRunning = GNSSClientService.isServiceRunning();
        serviceControlButton.setText(serviceRunning ? R.string.stop_service : R.string.start_service);
        if (serviceRunning) {
            serviceStatusText.setText(R.string.service_running);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_green_light));
        } else if (!hasRequiredPermissions()) {
            serviceStatusText.setText(R.string.status_waiting_for_permissions);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_red_light));
        } else if (bluetoothAdapter == null) {
            serviceStatusText.setText(R.string.status_bluetooth_unsupported);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_red_light));
        } else if (!bluetoothAdapter.isEnabled()) {
            serviceStatusText.setText(bluetoothEnableDeclined
                    ? R.string.status_bluetooth_enable_denied
                    : R.string.status_bluetooth_disabled);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_red_light));
        } else {
            serviceStatusText.setText(R.string.service_stopped);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_orange_light));
        }
    }

    private void showBluetoothDevicePicker() {
        if (!hasRequiredPermissions() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            continueStartup();
            return;
        }

        Set<BluetoothDevice> bondedDevices;
        try {
            bondedDevices = bluetoothAdapter.getBondedDevices();
        } catch (SecurityException e) {
            Log.e(TAG, "Unable to read paired Bluetooth devices", e);
            Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (bondedDevices.isEmpty()) {
            Toast.makeText(this, R.string.bluetooth_no_paired_devices, Toast.LENGTH_LONG).show();
            return;
        }

        List<BluetoothDevice> devices = new ArrayList<>(bondedDevices);
        devices.sort(Comparator.comparing(device -> displayName(device).toLowerCase()));
        String[] labels = devices.stream()
                .map(device -> displayName(device) + "\n" + device.getAddress())
                .toArray(String[]::new);

        new AlertDialog.Builder(this)
                .setTitle(R.string.select_phone_title)
                .setItems(labels, (dialog, which) -> {
                    BluetoothDevice selected = devices.get(which);
                    Preferences.setTargetDevice(this, selected.getAddress(), displayName(selected));
                    GNSSClientService.notifyTargetChanged();
                    updateServiceAndTargetStatus();
                    updateConnectionStatus(
                            GNSSClientService.getConnectionState(),
                            GNSSClientService.getTargetDescription(this),
                            GNSSClientService.getConnectionMessage()
                    );
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private String displayName(BluetoothDevice device) {
        // Only called after hasRequiredPermissions() verifies BLUETOOTH_CONNECT on API 31+.
        String name = device.getName();
        return name == null || name.isBlank() ? device.getAddress() : name;
    }

    private void updateConnectionStatus(
            ConnectionManager.ConnectionState state,
            String targetDescription,
            String message
    ) {
        runOnUiThread(() -> {
            statusText.setText(String.format(
                    "%s %s - %s",
                    getString(R.string.app_name),
                    appVersion,
                    getString(switch (state) {
                        case CONNECTED -> R.string.connected;
                        case CONNECTING -> R.string.connecting;
                        case DISCONNECTED -> R.string.disconnected;
                    })
            ));

            switch (state) {
                case CONNECTED -> {
                    connectionText.setText(String.format(
                            getString(R.string.connection_status_connected),
                            targetDescription == null ? getString(R.string.unknown) : targetDescription
                    ));
                    connectionText.setTextColor(getColor(android.R.color.holo_green_light));
                }
                case CONNECTING -> {
                    connectionText.setText(String.format(
                            getString(R.string.connection_status_connecting),
                            targetDescription == null ? getString(R.string.unknown) : targetDescription
                    ));
                    connectionText.setTextColor(getColor(android.R.color.holo_orange_light));
                }
                case DISCONNECTED -> {
                    connectionText.setText(message == null || message.isBlank()
                            ? getString(R.string.connection_status_disconnected)
                            : message);
                    connectionText.setTextColor(getColor(android.R.color.holo_red_light));
                }
            }

            if (state != ConnectionManager.ConnectionState.CONNECTED) {
                locationText.setText(String.format(
                        getString(R.string.location_status),
                        getString(R.string.unknown)
                ));
                satellitesText.setText(String.format(getString(R.string.satellites_status), 0));
                providerText.setText(String.format(
                        getString(R.string.provider_status),
                        getString(R.string.unknown)
                ));
                ageText.setText(String.format(
                        getString(R.string.age_status),
                        getString(R.string.unknown)
                ));
            }
        });
    }

    private void updateSatelliteInfo(int satellites) {
        satellitesText.setText(String.format(getString(R.string.satellites_status), satellites));
    }

    private void updateLocationInfo(Location location, String provider, float locationAge) {
        runOnUiThread(() -> {
            StringBuilder locationBuilder = new StringBuilder(String.format(
                    getString(R.string.location_status),
                    String.format(
                            getString(R.string.location_format),
                            location.getLatitude(),
                            location.getLongitude()
                    )
            ));
            if (location.hasAltitude()) {
                locationBuilder.append(String.format(
                        getString(R.string.altitude_format),
                        location.getAltitude()
                ));
            }
            if (location.hasAccuracy()) {
                locationBuilder.append(String.format(
                        getString(R.string.location_accuracy_format),
                        location.getAccuracy()
                ));
            }
            locationText.setText(locationBuilder.toString());
            providerText.setText(String.format(
                    getString(R.string.provider_status),
                    provider == null ? getString(R.string.unknown) : provider
            ));
            ageText.setText(String.format(
                    getString(R.string.age_status),
                    String.format(getString(R.string.age_format), locationAge)
            ));

            StringBuilder additionalInfo = new StringBuilder();
            if (location.hasSpeed()) {
                additionalInfo.append(String.format(
                        getString(R.string.movement_speed),
                        String.format(getString(R.string.speed_format), location.getSpeed())
                ));
            }
            if (location.hasBearing()) {
                if (additionalInfo.length() > 0) {
                    additionalInfo.append("  ");
                }
                additionalInfo.append(String.format(
                        getString(R.string.movement_bearing),
                        String.format(getString(R.string.bearing_format), location.getBearing())
                ));
            }
            if (additionalInfo.length() > 0) {
                additionalInfoText.setText(additionalInfo.toString());
            }
        });
    }

    private void updateDynamicInfo() {
        if (!GNSSClientService.isServiceRunning()) {
            return;
        }
        long updateTime = GNSSClientService.getLastUpdateTime();
        if (updateTime <= 0) {
            return;
        }
        long ageSeconds = (System.currentTimeMillis() - updateTime) / 1000;
        if (ageSeconds < 60) {
            dataAgeText.setText(String.format(
                    getString(R.string.data_age_status),
                    String.format(getString(R.string.data_age_format_s), ageSeconds)
            ));
        } else {
            dataAgeText.setText(String.format(
                    getString(R.string.data_age_status),
                    String.format(
                            getString(R.string.data_age_format_ms),
                            ageSeconds / 60,
                            ageSeconds % 60
                    )
            ));
        }
        dataAgeText.setTextColor(getColor(
                ageSeconds < 10 ? android.R.color.holo_green_light : android.R.color.holo_red_light
        ));
    }

    private void exportLogs(String appName) {
        Toast.makeText(
                this,
                dezz.gnssshare.logexporter.R.string.export_logs_in_progress,
                Toast.LENGTH_SHORT
        ).show();
        new Thread(() -> {
            try {
                File logFile = LogExporter.exportLogs(this, appName);
                LogExporter.cleanupOldLogs(this, appName);
                runOnUiThread(() -> {
                    if (logFile != null) {
                        shareLogFile(logFile);
                        Toast.makeText(
                                this,
                                dezz.gnssshare.logexporter.R.string.export_logs_success,
                                Toast.LENGTH_SHORT
                        ).show();
                    } else {
                        Toast.makeText(
                                this,
                                dezz.gnssshare.logexporter.R.string.export_logs_no_logs,
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error exporting logs", e);
                runOnUiThread(() -> Toast.makeText(
                        this,
                        String.format(
                                getString(dezz.gnssshare.logexporter.R.string.export_logs_error),
                                e.getMessage()
                        ),
                        Toast.LENGTH_LONG
                ).show());
            }
        }).start();
    }

    private void shareLogFile(File logFile) {
        try {
            Uri fileUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    logFile
            );
            Intent shareIntent = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, fileUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(
                    shareIntent,
                    getString(dezz.gnssshare.logexporter.R.string.share_logs)
            ));
        } catch (Exception e) {
            Log.e(TAG, "Error sharing log file", e);
            Toast.makeText(
                    this,
                    String.format(
                            getString(dezz.gnssshare.logexporter.R.string.export_logs_error),
                            e.getMessage()
                    ),
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}
