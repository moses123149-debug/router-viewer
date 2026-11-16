package com.routerviewer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens the viewer when the charger is plugged in while the hotspot is on. */
public class AutoStartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_POWER_CONNECTED.equals(i.getAction()) && MainActivity.hotspotOn(c)) {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
