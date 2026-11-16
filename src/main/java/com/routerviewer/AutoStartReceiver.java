package com.routerviewer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens the viewer when the charger is plugged in while the hotspot is on, and starts the proxy when the hotspot turns on. */
public class AutoStartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!MainActivity.hotspotOn(c)) return;
        if (Intent.ACTION_POWER_CONNECTED.equals(i.getAction())) {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else {
            c.startService(new Intent(c, ProxyService.class));
        }
    }
}
