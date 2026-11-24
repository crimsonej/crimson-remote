package com.crimson.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the bridge after a reboot if it was on. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
                && c.getSharedPreferences("crimson", Context.MODE_PRIVATE).getBoolean("bridge_on", false)) {
            BridgeService.start(c);
        }
    }
}
