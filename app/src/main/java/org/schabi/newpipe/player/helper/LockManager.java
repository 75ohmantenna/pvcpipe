package org.schabi.newpipe.player.helper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Log;

public class LockManager {
    private final String TAG = "LockManager@" + hashCode();

    private final PowerManager powerManager;
    private final WifiManager wifiManager;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public LockManager(final Context context) {
        powerManager = context.getApplicationContext().getSystemService(PowerManager.class);
        wifiManager = context.getSystemService(WifiManager.class);
    }

    @SuppressLint("WakelockTimeout")
    public void acquireWifiAndCpu() {
        Log.d(TAG, "acquireWifiAndCpu() called");
        if (wakeLock != null && wakeLock.isHeld() && wifiLock != null && wifiLock.isHeld()) {
            return;
        }

        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);

        if (wakeLock != null) {
            // The download service releases this when it leaves the foreground and again during
            // destruction. A fixed timeout would silently break downloads that legitimately run
            // longer than the chosen limit because the service does not periodically renew it.
            wakeLock.acquire();
        }
        if (wifiLock != null) {
            wifiLock.acquire();
        }
    }

    public void releaseWifiAndCpu() {
        Log.d(TAG, "releaseWifiAndCpu() called");
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }

        wakeLock = null;
        wifiLock = null;
    }
}
