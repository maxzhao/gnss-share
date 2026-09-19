package dezz.gnssshare.client;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class GNSSClientTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean mainProcessRunning = ServiceControl.isMainProcessRunning(this);
        if (ServiceControl.isServiceRunning(this)) {
            ServiceControl.stopService(this);
            updateTile(false);
            return;
        }
        if (isLocked() && isSecure()) {
            updateTile(false);
            unlockAndRun(() -> startOrOpenActivity(mainProcessRunning));
            return;
        }
        startOrOpenActivity(mainProcessRunning);
    }

    private void startOrOpenActivity(boolean mainProcessRunningBeforeClick) {
        if (ServiceControl.isServiceRunning(this)) {
            updateTile(true);
            return;
        }
        if (!mainProcessRunningBeforeClick || !ServiceControl.canStartDirectly(this)) {
            updateTile(false);
            ServiceControl.launchMainActivity(this);
            return;
        }
        updateTile(false);
        if (!ServiceControl.startService(this)) {
            ServiceControl.launchMainActivity(this);
        }
    }

    private void updateTile() {
        updateTile(ServiceControl.isServiceRunning(this));
    }

    private void updateTile(boolean running) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        CharSequence label = getText(R.string.quick_settings_tile_label);
        tile.setLabel(label);
        tile.setContentDescription(label);
        tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
