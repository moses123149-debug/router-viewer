package com.routerviewer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.NumberPicker;

import java.util.TimeZone;

public class SettingsActivity extends Activity {
    private EditText etLimit, etUsed;
    private NumberPicker npDay;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        etLimit = findViewById(R.id.etLimit);
        etUsed = findViewById(R.id.etUsed);
        npDay = findViewById(R.id.npDay);

        etLimit.setText(String.valueOf(p.getInt(MainActivity.LIMIT_GB, 100)));
        npDay.setMinValue(1);
        npDay.setMaxValue(31);
        npDay.setValue(p.getInt(MainActivity.RESET_DAY, 1));
        npDay.setFormatter(v -> v + "일");

        findViewById(R.id.btnMinus).setOnClickListener(v -> etLimit.setText(String.valueOf(Math.max(1, limit() - 10))));
        findViewById(R.id.btnPlus).setOnClickListener(v -> etLimit.setText(String.valueOf(limit() + 10)));
        findViewById(R.id.btnCancel).setOnClickListener(v -> finish());
        findViewById(R.id.btnSave).setOnClickListener(v -> save(p));
    }

    @Override
    protected void onResume() {
        super.onResume();
        long now = System.currentTimeMillis();
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        long cycle = Calc.cycleStart(now, p.getInt(MainActivity.RESET_DAY, 1), TimeZone.getDefault());
        new Thread(() -> {
            long m = MainActivity.mobileBytes(this, cycle, now);
            if (m >= 0 && p.getLong(MainActivity.OFFSET_CYCLE, 0) == cycle) {
                m = Math.max(0, m + p.getLong(MainActivity.OFFSET_BYTES, 0));
            }
            long measured = m;
            runOnUiThread(() -> etUsed.setHint(measured >= 0 ? "현재 " + MainActivity.gb(measured) : ""));
        }).start();
    }

    private int limit() {
        try {
            return Math.max(1, Integer.parseInt(etLimit.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private void save(SharedPreferences p) {
        int day = npDay.getValue();
        SharedPreferences.Editor e = p.edit()
                .putInt(MainActivity.LIMIT_GB, limit())
                .putInt(MainActivity.RESET_DAY, day);
        String used = etUsed.getText().toString().trim();
        double usedGb;
        try {
            usedGb = used.isEmpty() ? -1 : Double.parseDouble(used);
        } catch (NumberFormatException ex) {
            usedGb = -1;
        }
        if (usedGb < 0) {
            e.apply();
            finish();
            return;
        }
        findViewById(R.id.btnSave).setEnabled(false);
        double target = usedGb;
        new Thread(() -> {
            long now = System.currentTimeMillis();
            long cycle = Calc.cycleStart(now, day, TimeZone.getDefault());
            long measured = MainActivity.mobileBytes(this, cycle, now);
            if (measured >= 0) {
                e.putLong(MainActivity.OFFSET_BYTES, (long) (target * MainActivity.GB) - measured)
                 .putLong(MainActivity.OFFSET_CYCLE, cycle);
            }
            e.apply();
            runOnUiThread(this::finish);
        }).start();
    }
}
