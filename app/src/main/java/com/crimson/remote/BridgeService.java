package com.crimson.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the iPhone bridge running in the background, with a quiet notification. */
public class BridgeService extends Service {
    static final String CHANNEL = "bridge";
    static volatile boolean running;
    private Bridge bridge;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    public static void start(Context c) {
        Intent i = new Intent(c, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) { c.stopService(new Intent(c, BridgeService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "iPhone bridge", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while other devices can use this phone as a TV remote");
            nm.createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String addr = Bridge.address();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("Crimson bridge is on")
                .setContentText(addr == null ? "Waiting for Wi-Fi" : "Open " + addr + " on your iPhone")
                .setContentIntent(open)
                .setOngoing(true)
                .setColor(0xFFE11D48)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);

        if (bridge == null) {
            bridge = new Bridge(this);
            try { bridge.start(); } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crimson:bridge");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "crimson:bridge");
            wifi.acquire();
        }
        running = true;
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (bridge != null) bridge.stop();
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
