package com.routerviewer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;

/**
 * Runs the SOCKS5 proxy on the hotspot address only, so just hotspot clients can reach it.
 * Watches the hotspot itself: rebinds when the address changes, closes every session when it goes away,
 * and stops when the hotspot is turned off.
 */
public class ProxyService extends Service {
    static final int PORT = 1080;
    static volatile String address;
    private volatile boolean running;
    private ServerSocket server;
    private InetAddress bound;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        running = true;
        startForeground(1, notification("핫스팟 주소 기다리는 중"));
        new Thread(this::watch).start();
        return START_STICKY;
    }

    private void watch() {
        while (running) {
            if (!MainActivity.hotspotOn(this)) {
                stopSelf();
                return;
            }
            InetAddress ip = hotspotAddress();
            if (ip == null ? bound != null : !ip.equals(bound)) {
                unbind();
                if (ip != null) bind(ip);
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private synchronized void bind(InetAddress ip) {
        if (!running) return;
        try {
            server = new ServerSocket(PORT, 50, ip);
        } catch (IOException e) {
            Log.w("RouterViewer", "proxy bind failed", e);
            return;
        }
        bound = ip;
        address = ip.getHostAddress() + ":" + PORT;
        ServerSocket s = server;
        new Thread(() -> Socks5.serve(s)).start();
        getSystemService(NotificationManager.class).notify(1, notification(address));
    }

    private synchronized void unbind() {
        address = null;
        bound = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        Socks5.closeAll();
    }

    private Notification notification(String text) {
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("SOCKS5 프록시 실행 중")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    @Override
    public synchronized void onDestroy() {
        running = false;
        unbind();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static InetAddress hotspotAddress() {
        try {
            NetworkInterface ni = NetworkInterface.getByName("swlan0");
            if (ni != null) {
                for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) return a.getAddress();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
