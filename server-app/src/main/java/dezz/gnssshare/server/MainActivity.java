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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import dezz.gnssshare.shared.LogExporter;
import dezz.gnssshare.shared.VersionGetter;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GNSSServerActivity";

    private TextView serviceStatusText;
    private TextView permissionsStatusText;
    private TextView targetDeviceText;
    private Button requestPermissionsButton;
    private Button selectDeviceButton;
    private Switch fusedLocationSwitch;
    private TextView fusedLocationInfo;

    private BluetoothAdapter bluetoothAdapter;
    private boolean permissionRequestInFlight;
    private boolean corePermissionDeclined;
    private boolean backgroundPermissionDeclined;
    private boolean bluetoothEnableRequestInFlight;
    private boolean bluetoothEnableDeclined;
    private boolean batteryOptimizationRequestHandled;
    private boolean openPickerAfterPrerequisites;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            continueStartup();
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                permissionRequestInFlight = false;
                corePermissionDeclined = !hasForegroundLocationPermissions() || !hasBluetoothPermission();
                if (!corePermissionDeclined) {
                    requestBackgroundLocationIfNeeded();
                } else {
                    Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                    updateStatus();
                }
            });

    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                permissionRequestInFlight = false;
                backgroundPermissionDeclined = !granted;
                if (!granted) {
                    Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                }
                continueStartup();
            });

    private final ActivityResultLauncher<Intent> backgroundLocationSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                permissionRequestInFlight = false;
                backgroundPermissionDeclined = !hasBackgroundLocationPermission();
                if (backgroundPermissionDeclined) {
                    Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
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

    private final ActivityResultLauncher<Intent> batteryOptimizationLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> updateStatus());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        setContentView(R.layout.activity_main_server);

        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();

        applyWindowInsets();
        initializeViews();
        continueStartup();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mainHandler.post(statusUpdateRunnable);
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            bluetoothEnableDeclined = false;
        }
        continueStartup();
    }

    @Override
    protected void onStop() {
        mainHandler.removeCallbacks(statusUpdateRunnable);
        super.onStop();
    }

    private void applyWindowInsets() {
        View header = findViewById(R.id.header);
        View copyright = findViewById(R.id.copyrightText);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout), (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            header.setPadding(
                    header.getPaddingLeft(),
                    bars.top + (int) (12 * getResources().getDisplayMetrics().density),
                    header.getPaddingRight(),
                    header.getPaddingBottom()
            );
            copyright.setPadding(
                    copyright.getPaddingLeft(),
                    copyright.getPaddingTop(),
                    copyright.getPaddingRight(),
                    bars.bottom + (int) (8 * getResources().getDisplayMetrics().density)
            );
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void initializeViews() {
        serviceStatusText = findViewById(R.id.statusText);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);
        targetDeviceText = findViewById(R.id.targetDeviceText);
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        selectDeviceButton = findViewById(R.id.selectDeviceButton);
        fusedLocationSwitch = findViewById(R.id.fusedLocationSwitch);
        fusedLocationInfo = findViewById(R.id.fusedLocationInfo);

        TextView header = findViewById(R.id.header);
        header.setText(String.format(
                "%s %s",
                getString(R.string.app_name),
                VersionGetter.getAppVersionName(this)
        ));

        requestPermissionsButton.setOnClickListener(view -> {
            permissionRequestInFlight = false;
            corePermissionDeclined = false;
            backgroundPermissionDeclined = false;
            requestMissingCorePermissions();
            if (hasForegroundLocationPermissions() && hasBluetoothPermission()) {
                requestBackgroundLocationIfNeeded();
            }
        });
        selectDeviceButton.setOnClickListener(view -> {
            openPickerAfterPrerequisites = true;
            continueStartup();
        });
        findViewById(R.id.exportLogsButton).setOnClickListener(view -> exportLogs("gnss-server"));

        View instructionsHeader = findViewById(R.id.instructionsHeader);
        TextView instructionsText = findViewById(R.id.instructionsText);
        ImageView instructionsArrow = findViewById(R.id.instructionsArrow);
        instructionsHeader.setOnClickListener(view -> {
            boolean visible = instructionsText.getVisibility() == View.VISIBLE;
            instructionsText.setVisibility(visible ? View.GONE : View.VISIBLE);
            instructionsArrow.setRotation(visible ? 0f : 180f);
        });

        fusedLocationSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                Preferences.setFusedLocationEnabled(this, checked));
        updateFusedLocationSettingsUI();
        updateStatus();
    }

    private void continueStartup() {
        updateStatus();
        if (!hasForegroundLocationPermissions() || !hasBluetoothPermission()) {
            if (!corePermissionDeclined) {
                requestMissingCorePermissions();
            }
            return;
        }
        if (!hasBackgroundLocationPermission()) {
            if (!backgroundPermissionDeclined) {
                requestBackgroundLocationIfNeeded();
            }
            return;
        }
        if (bluetoothAdapter == null) {
            serviceStatusText.setText(R.string.status_bluetooth_unsupported);
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

        if (!GNSSServerService.isServiceRunning()) {
            startGNSSService();
        }
        checkBatteryOptimization();
        if (openPickerAfterPrerequisites) {
            openPickerAfterPrerequisites = false;
            showBluetoothDevicePicker();
        }
    }

    private void requestMissingCorePermissions() {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!missing.isEmpty()) {
            permissionRequestInFlight = true;
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void requestBackgroundLocationIfNeeded() {
        if (hasBackgroundLocationPermission()) {
            continueStartup();
            return;
        }
        if (!permissionRequestInFlight) {
            permissionRequestInFlight = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                backgroundLocationSettingsLauncher.launch(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:" + getPackageName()))
                );
            } else {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
        }
    }

    private boolean hasForegroundLocationPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("BatteryLife")
    private void checkBatteryOptimization() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null
                || powerManager.isIgnoringBatteryOptimizations(getPackageName())
                || batteryOptimizationRequestHandled) {
            return;
        }
        batteryOptimizationRequestHandled = true;
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + getPackageName()));
        batteryOptimizationLauncher.launch(intent);
    }

    private void startGNSSService() {
        ContextCompat.startForegroundService(this, new Intent(this, GNSSServerService.class));
    }

    private void updateStatus() {
        List<String> missingPermissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(getString(R.string.permission_fine_location));
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(getString(R.string.permission_coarse_location));
        }
        if (!hasBackgroundLocationPermission()) {
            missingPermissions.add(getString(R.string.permission_background_location));
        }
        if (!hasBluetoothPermission()) {
            missingPermissions.add(getString(R.string.permission_bluetooth_connect));
        }

        if (missingPermissions.isEmpty()) {
            permissionsStatusText.setText(R.string.all_permissions_granted);
            permissionsStatusText.setTextColor(getColor(android.R.color.holo_green_dark));
            requestPermissionsButton.setVisibility(View.GONE);
        } else {
            permissionsStatusText.setText(String.format(
                    getString(R.string.missing_permissions),
                    String.join(", ", missingPermissions)
            ));
            permissionsStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
            requestPermissionsButton.setVisibility(View.VISIBLE);
        }

        String address = Preferences.targetDeviceAddress(this);
        String name = Preferences.targetDeviceName(this);
        if (address == null || address.isBlank()) {
            targetDeviceText.setText(R.string.target_device_not_selected);
            selectDeviceButton.setText(R.string.select_tablet);
        } else {
            targetDeviceText.setText(String.format(
                    getString(R.string.target_device_selected),
                    name == null || name.isBlank() ? address : name,
                    address
            ));
            selectDeviceButton.setText(R.string.change_tablet);
        }

        if (GNSSServerService.isServiceRunning()) {
            String serviceStatus = GNSSServerService.getTransportStatus(this);
            serviceStatusText.setText(serviceStatus);
            boolean error = serviceStatus.equals(getString(R.string.status_bluetooth_permission_required))
                    || serviceStatus.equals(getString(R.string.status_bluetooth_unsupported))
                    || serviceStatus.equals(getString(R.string.status_bluetooth_disabled))
                    || serviceStatus.equals(getString(R.string.status_select_tablet))
                    || serviceStatus.equals(getString(R.string.status_target_unavailable))
                    || serviceStatus.equals(getString(R.string.status_location_permission_required))
                    || serviceStatus.startsWith(getString(R.string.status_listen_failed, ""));
            serviceStatusText.setTextColor(getColor(
                    error ? android.R.color.holo_red_dark : android.R.color.holo_green_dark
            ));
        } else if (!missingPermissions.isEmpty()) {
            serviceStatusText.setText(R.string.status_waiting_for_permissions);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
        } else if (bluetoothAdapter == null) {
            serviceStatusText.setText(R.string.status_bluetooth_unsupported);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
        } else if (!bluetoothAdapter.isEnabled()) {
            serviceStatusText.setText(bluetoothEnableDeclined
                    ? R.string.status_bluetooth_enable_denied
                    : R.string.status_bluetooth_disabled);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
        } else {
            serviceStatusText.setText(R.string.service_starting);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_orange_dark));
        }
    }

    private void showBluetoothDevicePicker() {
        if (!hasBluetoothPermission() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
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
                .setTitle(R.string.select_tablet_title)
                .setItems(labels, (dialog, which) -> {
                    BluetoothDevice selected = devices.get(which);
                    Preferences.setTargetDevice(this, selected.getAddress(), displayName(selected));
                    GNSSServerService.notifyTargetChanged();
                    updateStatus();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private String displayName(BluetoothDevice device) {
        // Only called after hasBluetoothPermission() verifies BLUETOOTH_CONNECT on API 31+.
        String name = device.getName();
        return name == null || name.isBlank() ? device.getAddress() : name;
    }

    private void updateFusedLocationSettingsUI() {
        boolean supported = GNSSServerService.isFusedLocationSupported(this);
        fusedLocationSwitch.setEnabled(supported);
        fusedLocationSwitch.setChecked(supported && Preferences.fusedLocationEnabled(this));
        fusedLocationInfo.setVisibility(supported ? View.GONE : View.VISIBLE);
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
