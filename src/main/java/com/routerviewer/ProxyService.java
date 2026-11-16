package com.routerviewer;

import android.app.Notification;
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

/** Runs the SOCKS5 proxy on the hotspot address only, so just hotspot clients can reach it. */
public class ProxyService extends Service {
    static final int PORT = 1080;
    static volatile String address;
    private ServerSocket server;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server != null && !server.isClosed()) return START_STICKY;
        InetAddress ip = hotspotAddress();
        if (ip == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            server = new ServerSocket(PORT, 50, ip);
        } catch (IOException e) {
            Log.w("RouterViewer", "proxy bind failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        address = ip.getHostAddress() + ":" + PORT;
        ServerSocket s = server;
        new Thread(() -> Socks5.serve(s)).start();
        startForeground(1, new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("SOCKS5 프록시 실행 중")
                .setContentText(address)
                .setOngoing(true)
                .build());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        address = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
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
