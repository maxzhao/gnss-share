package dezz.gnssshare.client;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.service.quicksettings.TileService;
import android.util.AtomicFile;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

final class ServiceControl {
    static final String EXTRA_START_FROM_TILE =
            "dezz.gnssshare.client.extra.START_FROM_TILE";

    private static final String TAG = "ClientServiceControl";
    private static final String SERVICE_STATE_FILE = "gnss_client_service_state";

    private ServiceControl() {
    }

    static boolean isMainProcessRunning(Context context) {
        return mainProcessId(context) != 0;
    }

    static boolean isServiceRunning(Context context) {
        int mainProcessId = mainProcessId(context);
        if (mainProcessId == 0) {
            return false;
        }
        AtomicFile stateFile = serviceStateFile(context);
        try (DataInputStream input = new DataInputStream(stateFile.openRead())) {
            return input.readInt() == mainProcessId;
        } catch (IOException exception) {
            return false;
        }
    }

    static void publishServiceRunning(Context context) {
        AtomicFile stateFile = serviceStateFile(context);
        stateFile.delete();
        FileOutputStream output = null;
        try {
            output = stateFile.startWrite();
            DataOutputStream dataOutput = new DataOutputStream(output);
            dataOutput.writeInt(Process.myPid());
            dataOutput.flush();
            stateFile.finishWrite(output);
        } catch (IOException exception) {
            if (output != null) {
                stateFile.failWrite(output);
            }
            Log.e(TAG, "Unable to publish the GNSS client service state", exception);
        }
        requestTileRefresh(context);
    }

    static void publishServiceStopped(Context context) {
        serviceStateFile(context).delete();
        requestTileRefresh(context);
    }

    private static int mainProcessId(Context context) {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return 0;
        }
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) {
            return 0;
        }
        String mainProcessName = context.getPackageName();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.uid == Process.myUid() && mainProcessName.equals(process.processName)) {
                return process.pid;
            }
        }
        return 0;
    }

    private static AtomicFile serviceStateFile(Context context) {
        File file = new File(context.getFilesDir(), SERVICE_STATE_FILE);
        return new AtomicFile(file);
    }

    static boolean canStartDirectly(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED)) {
            return false;
        }

        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            return false;
        }
        try {
            return bluetoothAdapter.isEnabled();
        } catch (SecurityException exception) {
            return false;
        }
    }

    static boolean startService(Context context) {
        try {
            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, GNSSClientService.class)
            );
            return true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to start the GNSS client service", exception);
            return false;
        }
    }

    static void stopService(Context context) {
        context.stopService(new Intent(context, GNSSClientService.class));
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    static void launchMainActivity(TileService tileService) {
        Intent intent = new Intent(tileService, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_START_FROM_TILE, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    tileService,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            tileService.startActivityAndCollapse(pendingIntent);
        } else {
            tileService.startActivityAndCollapse(intent);
        }
    }

    static void requestTileRefresh(Context context) {
        TileService.requestListeningState(
                context,
                new ComponentName(context, GNSSClientTileService.class)
        );
    }

}
