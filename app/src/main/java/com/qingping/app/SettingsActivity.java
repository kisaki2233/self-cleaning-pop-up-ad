package com.qingping.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.qingping.app.data.StatsDb;
import com.qingping.app.guard.GuardForegroundService;
import com.qingping.app.guard.GuardUtils;

public class SettingsActivity extends android.app.Activity {
    private static final int REQ_NOTIFICATIONS = 42;

    private SeekBar windowSeek;
    private SeekBar delaySeek;
    private TextView windowValue;
    private TextView delayValue;
    private Switch switchMaster;
    private Switch switchToast;
    private Switch switchCorner;
    private Switch switchOnlySplash;
    private Switch switchWatchdog;
    private TextView targetsDesc;
    private TextView whitelistDesc;
    private TextView blacklistDesc;
    private TextView batteryDesc;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        windowSeek = findViewById(R.id.windowSeek);
        delaySeek = findViewById(R.id.delaySeek);
        windowValue = findViewById(R.id.windowValue);
        delayValue = findViewById(R.id.delayValue);
        switchMaster = findViewById(R.id.switchMaster);
        switchToast = findViewById(R.id.switchToast);
        switchCorner = findViewById(R.id.switchCorner);
        switchOnlySplash = findViewById(R.id.switchOnlySplash);
        switchWatchdog = findViewById(R.id.switchWatchdog);
        targetsDesc = findViewById(R.id.targetsDesc);
        whitelistDesc = findViewById(R.id.whitelistDesc);
        blacklistDesc = findViewById(R.id.blacklistDesc);
        batteryDesc = findViewById(R.id.batteryDesc);

        int windowProgress = (int) ((Prefs.launchWindowMs(this) - 3000L) / 1000L);
        windowSeek.setProgress(Math.max(0, Math.min(12, windowProgress)));
        updateWindowLabel();

        int delayProgress = Prefs.clickDelayMs(this) / 100;
        delaySeek.setProgress(Math.max(0, Math.min(8, delayProgress)));
        updateDelayLabel();

        windowSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateWindowLabel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                long ms = 3000L + seekBar.getProgress() * 1000L;
                Prefs.get(SettingsActivity.this).edit()
                        .putLong(Prefs.KEY_LAUNCH_MS, ms).apply();
            }
        });

        delaySeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateDelayLabel();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int ms = seekBar.getProgress() * 100;
                Prefs.get(SettingsActivity.this).edit()
                        .putInt(Prefs.KEY_CLICK_DELAY, ms).apply();
            }
        });

        switchMaster.setChecked(Prefs.master(this));
        switchMaster.setOnCheckedChangeListener((b, checked) -> {
            if (updating) {
                return;
            }
            Prefs.get(this).edit().putBoolean(Prefs.KEY_MASTER, checked).apply();
            if (!checked) {
                GuardForegroundService.stop(this);
            } else if (GuardUtils.isAccessibilityEnabled(this)) {
                requestNotificationPermission();
                GuardForegroundService.start(this);
            }
        });
        findViewById(R.id.rowMaster).setOnClickListener(v -> switchMaster.toggle());

        switchToast.setChecked(Prefs.showToast(this));
        switchToast.setOnCheckedChangeListener((b, checked) -> {
            if (!updating) {
                Prefs.get(this).edit().putBoolean(Prefs.KEY_TOAST, checked).apply();
            }
        });
        findViewById(R.id.rowToast).setOnClickListener(v -> switchToast.toggle());

        switchOnlySplash.setChecked(Prefs.onlyDuringLaunch(this));
        switchOnlySplash.setOnCheckedChangeListener((b, checked) -> {
            if (!updating) {
                Prefs.get(this).edit().putBoolean(Prefs.KEY_ONLY_SPLASH, checked).apply();
            }
        });
        findViewById(R.id.rowOnlySplash).setOnClickListener(v -> switchOnlySplash.toggle());

        switchCorner.setChecked(Prefs.cornerFallback(this));
        switchCorner.setOnCheckedChangeListener((b, checked) -> {
            if (updating) {
                return;
            }
            if (!checked) {
                Prefs.get(this).edit().putBoolean(Prefs.KEY_CORNER, false).apply();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_corner_warning_title)
                    .setMessage(R.string.settings_corner_warning)
                    .setNegativeButton(R.string.action_cancel, (d, w) -> {
                        updating = true;
                        switchCorner.setChecked(false);
                        updating = false;
                    })
                    .setOnCancelListener(d -> {
                        updating = true;
                        switchCorner.setChecked(false);
                        updating = false;
                    })
                    .setPositiveButton("仍然开启", (d, w) ->
                            Prefs.get(this).edit().putBoolean(Prefs.KEY_CORNER, true).apply())
                    .show();
        });
        findViewById(R.id.rowCorner).setOnClickListener(v -> switchCorner.toggle());

        switchWatchdog.setChecked(Prefs.watchdog(this));
        switchWatchdog.setOnCheckedChangeListener((b, checked) -> {
            if (updating) {
                return;
            }
            Prefs.get(this).edit().putBoolean(Prefs.KEY_WATCHDOG, checked).apply();
            if (checked) {
                requestNotificationPermission();
                if (GuardUtils.isAccessibilityEnabled(this)) {
                    GuardForegroundService.start(this);
                }
            } else {
                GuardForegroundService.stop(this);
            }
        });
        findViewById(R.id.rowWatchdog).setOnClickListener(v -> switchWatchdog.toggle());

        findViewById(R.id.rowWhitelist).setOnClickListener(v ->
                openAppPicker(AppPickerActivity.MODE_APP_WHITELIST));
        findViewById(R.id.rowBlacklist).setOnClickListener(v ->
                openAppPicker(AppPickerActivity.MODE_APP_BLACKLIST));
        findViewById(R.id.rowTargets).setOnClickListener(v ->
                openAppPicker(AppPickerActivity.MODE_JUMP));
        findViewById(R.id.rowBatterySettings).setOnClickListener(v ->
                GuardUtils.openBatterySettings(this));
        findViewById(R.id.rowClear).setOnClickListener(v -> confirmClear());
    }

    @Override
    protected void onResume() {
        super.onResume();
        int count = Prefs.jumpTargets(this).size();
        targetsDesc.setText(getString(R.string.settings_target_count, count));
        int whitelistCount = Prefs.appWhitelist(this).size();
        whitelistDesc.setText(whitelistCount == 0
                ? getString(R.string.settings_whitelist_all)
                : getString(R.string.settings_whitelist_count, whitelistCount));
        int blacklistCount = Prefs.appBlacklist(this).size();
        blacklistDesc.setText(blacklistCount == 0
                ? getString(R.string.settings_blacklist_none)
                : getString(R.string.settings_blacklist_count, blacklistCount));
        boolean batteryOk = GuardUtils.isBatteryOptimizationIgnored(this);
        batteryDesc.setText(batteryOk ? R.string.state_battery_ok : R.string.settings_battery_desc);
        batteryDesc.setTextColor(getColor(batteryOk ? R.color.mint_dark : R.color.muted));

        updating = true;
        switchMaster.setChecked(Prefs.master(this));
        switchWatchdog.setChecked(Prefs.watchdog(this));
        switchToast.setChecked(Prefs.showToast(this));
        switchCorner.setChecked(Prefs.cornerFallback(this));
        switchOnlySplash.setChecked(Prefs.onlyDuringLaunch(this));
        updating = false;
    }

    private void openAppPicker(String mode) {
        Intent intent = new Intent(this, AppPickerActivity.class);
        intent.putExtra(AppPickerActivity.EXTRA_MODE, mode);
        startActivity(intent);
    }

    private void updateWindowLabel() {
        long ms = 3000L + windowSeek.getProgress() * 1000L;
        windowValue.setText((ms / 1000L) + " " + getString(R.string.settings_sec_suffix));
    }

    private void updateDelayLabel() {
        int ms = delaySeek.getProgress() * 100;
        delayValue.setText(ms + " " + getString(R.string.settings_ms_suffix));
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clear_title)
                .setMessage(R.string.dialog_clear_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_clear, (d, w) -> StatsDb.get(this).clearAll())
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }
    }
}
