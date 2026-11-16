package com.routerviewer;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.ResultReceiver;
import android.util.Log;
import android.widget.Button;
import java.lang.reflect.Field;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthLte;
import android.telephony.TelephonyManager;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

public class MainActivity extends Activity {
    static final double GB = 1024.0 * 1024 * 1024;
    static final String PREFS = "rv", LIMIT_GB = "limitGb", RESET_DAY = "resetDay",
            OFFSET_BYTES = "offsetBytes", OFFSET_CYCLE = "offsetCycle";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy년 M월 d일 EEEE", Locale.KOREAN);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.KOREAN);
    private final SimpleDateFormat headFmt = new SimpleDateFormat("M.d E · HH:mm", Locale.KOREAN);
    private final Random random = new Random();

    private ViewFlipper flipper;
    private GestureDetector gestures;
    private RingView ring;
    private int ticks, calendarDay = -1;
    private long lastRx = -1, lastTx = -1, lastSpeedAt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        flipper = findViewById(R.id.flipper);
        ring = findViewById(R.id.ring);
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (Math.abs(vx) < Math.abs(vy)) return false;
                if (vx < 0 && flipper.getDisplayedChild() == 0) flipper.showNext();
                else if (vx > 0 && flipper.getDisplayedChild() == 1) flipper.showPrevious();
                else return false;
                updateDots();
                return true;
            }
        });
        findViewById(R.id.btnLimit).setOnClickListener(v -> getSystemService(KeyguardManager.class)
                .requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override
                    public void onDismissSucceeded() {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                }));
        findViewById(R.id.btnHotspot).setOnClickListener(v -> toggleHotspot());
        findViewById(R.id.usageCard).setOnClickListener(v -> {
            if (!hasUsageAccess(this)) startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        });
        updateDots();

        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.READ_PHONE_STATE}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticks = 0;
        lastRx = -1;
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            updateClock(now);
            updateSpeed(now);
            updateLockIcon();
            if (ticks % 5 == 0) {
                if (hasWindowFocus()) hideSystemBars();
                updateHotspotButton();
                updateProxy();
                updateRadio();
                updateBattery();
                updateDevices();
            }
            if (ticks % 10 == 0) updateUsage(now);
            if (ticks % 60 == 59) shiftForBurnIn();
            ticks++;
            handler.postDelayed(this, 1000 - now % 1000);
        }
    };

    private void updateClock(long now) {
        Date d = new Date(now);
        text(R.id.tvDate, dateFmt.format(d));
        text(R.id.tvTime, timeFmt.format(d));
        text(R.id.tvTime2, headFmt.format(d));
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        if (c.get(Calendar.DAY_OF_YEAR) != calendarDay) {
            calendarDay = c.get(Calendar.DAY_OF_YEAR);
            buildCalendar(c);
        }
    }

    private void buildCalendar(Calendar today) {
        GridLayout g = findViewById(R.id.calGrid);
        g.removeAllViews();
        String[] heads = {"일", "월", "화", "수", "목", "금", "토"};
        for (int i = 0; i < 7; i++) g.addView(calCell(heads[i], dayColor(i, R.color.dim), false, 12, 22));
        Calendar c = (Calendar) today.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);
        int offset = c.get(Calendar.DAY_OF_WEEK) - 1;
        int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        int todayDay = today.get(Calendar.DAY_OF_MONTH);
        for (int i = 0; i < offset; i++) g.addView(calCell("", 0, false, 16, 38));
        for (int d = 1; d <= days; d++) {
            int col = (offset + d - 1) % 7;
            g.addView(calCell(String.valueOf(d), dayColor(col, R.color.fg), d == todayDay, 16, 38));
        }
        text(R.id.tvCalMonth, (today.get(Calendar.MONTH) + 1) + "월");
        text(R.id.tvCalYear, String.valueOf(today.get(Calendar.YEAR)));
    }

    private int dayColor(int col, int normal) {
        return getColor(col == 0 ? R.color.sunday : col == 6 ? R.color.saturday : normal);
    }

    private TextView calCell(String s, int color, boolean isToday, float sp, int heightDp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(sp);
        t.setTextColor(isToday ? getColor(R.color.bg) : color);
        if (isToday) {
            t.setBackgroundResource(R.drawable.today);
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.height = dp(heightDp);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        t.setLayoutParams(lp);
        return t;
    }

    private void updateSpeed(long now) {
        long rx = TrafficStats.getMobileRxBytes(), tx = TrafficStats.getMobileTxBytes();
        if (rx == TrafficStats.UNSUPPORTED) {
            text(R.id.tvDown, "—");
            text(R.id.tvUp, "—");
            return;
        }
        if (lastRx >= 0 && rx >= lastRx && tx >= lastTx && now > lastSpeedAt) {
            double sec = (now - lastSpeedAt) / 1000.0;
            text(R.id.tvDown, String.format(Locale.US, "%.1f Mbps", (rx - lastRx) * 8 / 1e6 / sec));
            text(R.id.tvUp, String.format(Locale.US, "%.1f Mbps", (tx - lastTx) * 8 / 1e6 / sec));
        }
        lastRx = rx;
        lastTx = tx;
        lastSpeedAt = now;
    }

    private void updateUsage(long now) {
        if (!hasUsageAccess(this)) {
            text(R.id.tvPct, "권한 필요");
            text(R.id.tvLimit, "탭해서 허용");
            ring.setFraction(0);
            return;
        }
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int limitGb = p.getInt(LIMIT_GB, 100);
        long cycle = Calc.cycleStart(now, p.getInt(RESET_DAY, 1), TimeZone.getDefault());
        long month = mobileBytes(this, cycle, now);
        long today = mobileBytes(this, startOfDay(now), now);
        if (month < 0) return;
        if (p.getLong(OFFSET_CYCLE, 0) == cycle) month = Math.max(0, month + p.getLong(OFFSET_BYTES, 0));
        double frac = month / (limitGb * GB);
        ring.setFraction((float) frac);
        text(R.id.tvPct, String.format(Locale.US, "%.1f%%", frac * 100));
        text(R.id.tvLimit, "한도 " + limitGb + " GB");
        text(R.id.tvMonth, gb(month));
        text(R.id.tvRemain, gb(Math.max(0, (long) (limitGb * GB) - month)));
        text(R.id.tvToday, gb(today));
        text(R.id.tvToday1, gb(today));
    }

    private void updateRadio() {
        TelephonyManager tm = getSystemService(TelephonyManager.class);
        String carrier = tm.getNetworkOperatorName();
        text(R.id.tvCarrier, carrier == null || carrier.isEmpty() ? "전송 방식" : "전송 방식 · " + carrier);
        String rat = "—";
        if (granted(Manifest.permission.READ_PHONE_STATE)) rat = ratName(tm.getDataNetworkType());
        text(R.id.tvRat, rat);

        CellInfo reg = null;
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells != null) for (CellInfo ci : cells) if (ci.isRegistered()) { reg = ci; break; }
        }
        String band = "—", freq = "", earfcn = "—", bw = "—", pci = "—", rsrp = "—", rsrq = "—", sinr = "—";
        int bandNo = 0;
        if (reg instanceof CellInfoLte) {
            CellIdentityLte id = ((CellInfoLte) reg).getCellIdentity();
            CellSignalStrengthLte s = ((CellInfoLte) reg).getCellSignalStrength();
            int n = id.getEarfcn();
            bandNo = Calc.lteBand(n);
            if (bandNo > 0) {
                band = "Band " + bandNo;
                freq = String.format(Locale.US, "%.0f MHz", Calc.lteDlMhz(n));
            }
            earfcn = num(n, "");
            bw = id.getBandwidth() == CellInfo.UNAVAILABLE ? "—" : (id.getBandwidth() / 1000) + " MHz";
            pci = num(id.getPci(), "");
            rsrp = num(s.getRsrp(), " dBm");
            rsrq = num(s.getRsrq(), " dB");
            sinr = num(s.getRssnr(), " dB");
        } else if (reg instanceof CellInfoWcdma) {
            int n = ((CellInfoWcdma) reg).getCellIdentity().getUarfcn();
            band = "WCDMA";
            freq = n == CellInfo.UNAVAILABLE ? "" : String.format(Locale.US, "%.0f MHz", n / 5.0);
        }
        text(R.id.tvBand, band);
        text(R.id.tvFreq, freq);
        text(R.id.tvEarfcn, earfcn);
        text(R.id.tvBw, bw);
        text(R.id.tvPci, pci);
        text(R.id.tvRsrp, rsrp);
        text(R.id.tvRsrq, rsrq);
        text(R.id.tvSinr, sinr);
        text(R.id.tvNet1, bandNo > 0 ? rat + " · B" + bandNo : rat);
    }

    private static String ratName(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case 19: return "LTE-A"; // NETWORK_TYPE_LTE_CA, hidden constant some Samsung builds report
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_UMTS: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GPRS: return "2G";
            default: return "—";
        }
    }

    private void updateBattery() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        text(R.id.tvBattery1, pct + "%");
        text(R.id.tvBattery2, pct + "%" + (charging ? " · 충전 중" : ""));
    }

    private void updateDevices() {
        new Thread(() -> {
            int n = -1;
            try {
                java.lang.Process p = new ProcessBuilder("ip", "neigh", "show").start();
                List<String> lines = new ArrayList<>();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    for (String l; (l = r.readLine()) != null; ) lines.add(l);
                }
                if (p.waitFor() == 0) n = Calc.countNeighbors(lines);
            } catch (Exception ignored) {
            }
            String s = n < 0 ? "—" : n + "대";
            runOnUiThread(() -> {
                text(R.id.tvDevices1, s);
                text(R.id.tvDevices2, s);
            });
        }).start();
    }

    private boolean hotspotOn() {
        return hotspotOn(this);
    }

    static boolean hotspotOn(Context c) {
        try {
            WifiManager wm = c.getApplicationContext().getSystemService(WifiManager.class);
            return (Boolean) WifiManager.class.getMethod("isWifiApEnabled").invoke(wm);
        } catch (Exception e) {
            return false;
        }
    }

    private void updateHotspotButton() {
        boolean on = hotspotOn();
        Button b = findViewById(R.id.btnHotspot);
        b.setText(on ? "핫스팟 끄기" : "핫스팟 켜기");
        b.setBackgroundResource(on ? R.drawable.pill_fill : R.drawable.pill);
        b.setTextColor(getColor(on ? R.color.bg : R.color.fg));
    }

    // ponytail: hidden IConnectivityManager call (works on Android 10 with WRITE_SETTINGS); falls back to the settings screen
    private void toggleHotspot() {
        if (!Settings.System.canWrite(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        boolean on = hotspotOn();
        try {
            Field f = ConnectivityManager.class.getDeclaredField("mService");
            f.setAccessible(true);
            Object svc = f.get(getSystemService(ConnectivityManager.class));
            if (on) {
                svc.getClass().getMethod("stopTethering", int.class, String.class).invoke(svc, 0, getPackageName());
            } else {
                ResultReceiver r = new ResultReceiver(handler) {
                    @Override
                    protected void onReceiveResult(int code, Bundle data) {
                        Log.i("RouterViewer", "startTethering result " + code);
                        if (code != 0) openTetherSettings();
                    }
                };
                svc.getClass().getMethod("startTethering", int.class, ResultReceiver.class, boolean.class, String.class)
                        .invoke(svc, 0, r, false, getPackageName());
            }
            ((Button) findViewById(R.id.btnHotspot)).setText(on ? "끄는 중…" : "켜는 중…");
            handler.postDelayed(this::updateHotspotButton, 3000);
        } catch (Exception e) {
            Log.w("RouterViewer", "tethering toggle failed", e);
            openTetherSettings();
        }
    }

    private void openTetherSettings() {
        Intent i = new Intent().setClassName("com.android.settings", "com.android.settings.Settings$WifiTetherSettingsActivity");
        try {
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
    }

    private void updateProxy() {
        boolean on = hotspotOn();
        Intent svc = new Intent(this, ProxyService.class);
        if (on && ProxyService.address == null) startService(svc);
        else if (!on && ProxyService.address != null) stopService(svc);
        String a = ProxyService.address;
        text(R.id.tvProxy, a == null ? "" : "SOCKS5 " + a + " · 연결 " + Socks5.active.get());
    }

    private void updateLockIcon() {
        boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
        ImageView v = findViewById(R.id.lockIcon);
        v.setImageResource(locked ? R.drawable.ic_lock : R.drawable.ic_lock_open);
        v.setColorFilter(getColor(locked ? R.color.dot_off : R.color.accent));
        v.setContentDescription(locked ? "잠김" : "잠금 해제됨");
    }

    private void shiftForBurnIn() {
        View root = findViewById(R.id.root);
        root.setTranslationX(dp(random.nextInt(13) - 6));
        root.setTranslationY(dp(random.nextInt(9) - 4));
    }

    private void updateDots() {
        int on = flipper.getDisplayedChild();
        findViewById(R.id.dot0).setBackgroundColor(getColor(on == 0 ? R.color.fg : R.color.dot_off));
        findViewById(R.id.dot1).setBackgroundColor(getColor(on == 1 ? R.color.fg : R.color.dot_off));
    }

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    /** Mobile bytes (rx+tx, all subscriptions, tethering included) between start and end, or -1. */
    static long mobileBytes(Context c, long start, long end) {
        try {
            NetworkStats.Bucket b = c.getSystemService(NetworkStatsManager.class)
                    .querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end);
            return b.getRxBytes() + b.getTxBytes();
        } catch (Exception e) {
            return -1;
        }
    }

    private static long startOfDay(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String gb(long bytes) {
        return String.format(Locale.US, "%.2f GB", bytes / GB);
    }

    private static String num(int v, String unit) {
        return v == CellInfo.UNAVAILABLE ? "—" : v + unit;
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void text(int id, String s) {
        ((TextView) findViewById(id)).setText(s);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
