/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dezz.gnssshare.shared;

import java.util.UUID;

public final class BluetoothContract {
    public static final String SERVICE_NAME = "GNSS Share Location";
    public static final UUID SERVICE_UUID = UUID.fromString("a8e7f1b0-3d4c-4f29-9e6a-7b8c0d1e2f30");

    public static final byte HEARTBEAT_BYTE = 0x01;
    public static final int FRAME_HEADER_BYTES = 4;
    public static final int MAX_FRAME_BYTES = 1024 * 1024;

    public static final long HEARTBEAT_INTERVAL_MS = 1000;
    public static final long STALE_CONNECTION_TIMEOUT_MS = 3000;
    public static final long RESPONSE_INTERVAL_MS = 1000;
    public static final long RECONNECT_DELAY_MS = 500;

    private BluetoothContract() {
    }
}
