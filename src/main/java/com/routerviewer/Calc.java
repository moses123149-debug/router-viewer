package com.routerviewer;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure helpers (no Android types) so they can be checked with: java -ea Calc.java */
final class Calc {
    private Calc() {}

    // {band, DL low edge in 0.1 MHz, N_Offs-DL, last EARFCN} from 3GPP TS 36.101
    private static final int[][] LTE = {
        {1, 21100, 0, 599}, {2, 19300, 600, 1199}, {3, 18050, 1200, 1949},
        {4, 21100, 1950, 2399}, {5, 8690, 2400, 2649}, {7, 26200, 2750, 3449},
        {8, 9250, 3450, 3799}, {12, 7290, 5010, 5179}, {13, 7460, 5180, 5279},
        {14, 7580, 5280, 5379}, {17, 7340, 5730, 5849}, {18, 8600, 5850, 5999},
        {19, 8750, 6000, 6149}, {20, 7910, 6150, 6449}, {21, 14959, 6450, 6599},
        {25, 19300, 8040, 8689}, {26, 8590, 8690, 9039}, {28, 7580, 9210, 9659},
        {38, 25700, 37750, 38249}, {39, 18800, 38250, 38649}, {40, 23000, 38650, 39649},
        {41, 24960, 39650, 41589}, {42, 34000, 41590, 43589}, {66, 21100, 66436, 67335},
    };

    private static int[] lteRow(int earfcn) {
        for (int[] r : LTE) if (earfcn >= r[2] && earfcn <= r[3]) return r;
        return null;
    }

    /** LTE band number for a downlink EARFCN, or 0 if unknown. */
    static int lteBand(int earfcn) {
        int[] r = lteRow(earfcn);
        return r == null ? 0 : r[0];
    }

    /** Downlink centre frequency in MHz, or NaN if unknown. */
    static double lteDlMhz(int earfcn) {
        int[] r = lteRow(earfcn);
        return r == null ? Double.NaN : (r[1] + (earfcn - r[2])) / 10.0;
    }

    /** Start (00:00) of the billing cycle containing {@code now}; resetDay past month end clamps to the last day. */
    static long cycleStart(long now, int resetDay, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        int today = c.get(Calendar.DAY_OF_MONTH);
        c.set(Calendar.DAY_OF_MONTH, 1);
        if (today < Math.min(resetDay, c.getActualMaximum(Calendar.DAY_OF_MONTH))) c.add(Calendar.MONTH, -1);
        c.set(Calendar.DAY_OF_MONTH, Math.min(resetDay, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static final Pattern NEIGH = Pattern.compile(" dev (\\S+) lladdr (\\S+)");

    /** Distinct MACs on tethering interfaces (Samsung swlan, AOSP ap/softap, USB rndis, BT pan) in `ip neigh` output. */
    static int countNeighbors(Iterable<String> lines) {
        Set<String> macs = new HashSet<>();
        for (String l : lines) {
            if (l.contains("FAILED") || l.contains("INCOMPLETE")) continue;
            Matcher m = NEIGH.matcher(l);
            if (!m.find()) continue;
            String dev = m.group(1);
            if (dev.startsWith("swlan") || dev.startsWith("ap") || dev.startsWith("softap")
                    || dev.startsWith("rndis") || dev.startsWith("bt-pan")) macs.add(m.group(2));
        }
        return macs.size();
    }

    public static void main(String[] args) {
        check(lteBand(1550) == 3 && lteDlMhz(1550) == 1840.0, "B3 1550");
        check(lteBand(1200) == 3 && lteDlMhz(1200) == 1805.0, "B3 low edge");
        check(lteBand(2500) == 5 && lteDlMhz(2500) == 879.0, "B5");
        check(lteBand(3050) == 7 && lteDlMhz(3050) == 2650.0, "B7");
        check(lteBand(3600) == 8 && lteDlMhz(3600) == 940.0, "B8");
        check(lteBand(99999) == 0 && Double.isNaN(lteDlMhz(99999)), "unknown");

        TimeZone kst = TimeZone.getTimeZone("Asia/Seoul");
        check(cycleStart(at(kst, 2026, 11, 16), 1, kst) == at(kst, 2026, 11, 1), "reset 1");
        check(cycleStart(at(kst, 2026, 11, 16), 20, kst) == at(kst, 2026, 10, 20), "reset 20 before day");
        check(cycleStart(at(kst, 2026, 11, 20), 20, kst) == at(kst, 2026, 11, 20), "reset 20 on day");
        check(cycleStart(at(kst, 2026, 2, 28), 31, kst) == at(kst, 2026, 2, 28), "reset 31 in Feb");
        check(cycleStart(at(kst, 2026, 3, 15), 31, kst) == at(kst, 2026, 2, 28), "reset 31 back to Feb");
        check(cycleStart(at(kst, 2026, 1, 5), 10, kst) == at(kst, 2025, 12, 10), "year wrap");

        check(countNeighbors(Arrays.asList(
            "192.168.43.10 dev swlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE",
            "fe80::a8bb:ccff:fedd:eeff dev swlan0 lladdr aa:bb:cc:dd:ee:ff STALE",
            "192.168.43.11 dev swlan0 lladdr aa:bb:cc:dd:ee:00 STALE",
            "192.168.43.12 dev swlan0  FAILED",
            "fe80::1 dev rmnet_data0 lladdr 00:00:00:00:00:01 ROUTER REACHABLE")) == 2, "hotspot clients");
        check(countNeighbors(Arrays.asList(
            "172.20.10.1 dev wlan0 lladdr 02:00:00:00:00:01 REACHABLE",
            "fe80::ff:fe00:1 dev wlan0 lladdr 02:00:00:00:00:01 router REACHABLE")) == 0, "wifi client side");
        System.out.println("Calc OK");
    }

    private static long at(TimeZone tz, int y, int m, int d) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, m - 1, d);
        return c.getTimeInMillis();
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
