package com.qingping.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.qingping.app.data.AppStat;
import com.qingping.app.data.InterceptEvent;
import com.qingping.app.data.StatsDb;
import com.qingping.app.data.Summary;
import com.qingping.app.guard.AppInfoUtils;
import com.qingping.app.guard.GuardForegroundService;
import com.qingping.app.guard.GuardUtils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends android.app.Activity {
    private static final int REQ_NOTIFICATIONS = 41;

    private TextView statusPill;
    private TextView totalCount;
    private TextView todayCount;
    private TextView jumpCount;
    private TextView appCount;
    private TextView heroHint;
    private TextView accessibilityState;
    private TextView accessibilityAction;
    private TextView batteryState;
    private TextView bootState;
    private LinearLayout statsContainer;
    private View emptyState;
    private Switch switchSplash;
    private Switch switchJump;
    private StatsDb db;
    private boolean bindingSwitches;
    private boolean lastAccessibility;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        db = StatsDb.get(this);

        statusPill = findViewById(R.id.statusPill);
        totalCount = findViewById(R.id.totalCount);
        todayCount = findViewById(R.id.todayCount);
        jumpCount = findViewById(R.id.jumpCount);
        appCount = findViewById(R.id.appCount);
        heroHint = findViewById(R.id.heroHint);
        accessibilityState = findViewById(R.id.accessibilityState);
        accessibilityAction = findViewById(R.id.accessibilityAction);
        batteryState = findViewById(R.id.batteryState);
        bootState = findViewById(R.id.bootState);
        statsContainer = findViewById(R.id.statsContainer);
        emptyState = findViewById(R.id.emptyState);
        switchSplash = findViewById(R.id.switchSplash);
        switchJump = findViewById(R.id.switchJump);

        switchSplash.setChecked(Prefs.splash(this));
        switchJump.setChecked(Prefs.jump(this));

        switchSplash.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingSwitches) {
                return;
            }
            Prefs.get(this).edit().putBoolean(Prefs.KEY_SPLASH, isChecked).apply();
        });
        switchJump.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingSwitches) {
                return;
            }
            Prefs.get(this).edit().putBoolean(Prefs.KEY_JUMP, isChecked).apply();
        });
        findViewById(R.id.rowSplashSwitch).setOnClickListener(v -> switchSplash.toggle());
        findViewById(R.id.rowJumpSwitch).setOnClickListener(v -> switchJump.toggle());

        View.OnClickListener accessibilityClick = v -> showAccessibilityDialog();
        findViewById(R.id.rowAccessibility).setOnClickListener(accessibilityClick);
        findViewById(R.id.accessibilityAction).setOnClickListener(accessibilityClick);
        heroHint.setOnClickListener(v -> {
            if (!Prefs.master(this)) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else {
                showAccessibilityDialog();
            }
        });

        View.OnClickListener batteryClick = v -> showBatteryDialog();
        findViewById(R.id.rowBattery).setOnClickListener(batteryClick);
        findViewById(R.id.batteryAction).setOnClickListener(batteryClick);

        View.OnClickListener bootClick = v -> showBootDialog();
        findViewById(R.id.rowBoot).setOnClickListener(bootClick);
        findViewById(R.id.bootAction).setOnClickListener(bootClick);

        findViewById(R.id.rowMore).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.clearStats).setOnClickListener(v -> confirmClearAll());

        if (Prefs.firstRun(this)) {
            showWelcomeDialog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = GuardUtils.isAccessibilityEnabled(this);
        if (enabled && !lastAccessibility && lastAccessibilityKnown) {
            Toast.makeText(this, "清屏守护已开启", Toast.LENGTH_SHORT).show();
        }
        lastAccessibilityKnown = true;
        lastAccessibility = enabled;
        refresh();
        maybeStartWatchdog();
    }

    private boolean lastAccessibilityKnown;

    private void maybeStartWatchdog() {
        if (Prefs.master(this) && Prefs.watchdog(this)
                && GuardUtils.isAccessibilityEnabled(this)) {
            GuardForegroundService.start(this);
        } else {
            GuardForegroundService.stop(this);
        }
    }

    private void refresh() {
        boolean accessibility = GuardUtils.isAccessibilityEnabled(this);
        boolean paused = !Prefs.master(this);
        if (!accessibility) {
            statusPill.setText(R.string.status_off);
            statusPill.setBackgroundResource(R.drawable.bg_pill_off);
            statusPill.setTextColor(getColor(R.color.muted));
        } else if (paused) {
            statusPill.setText(R.string.status_paused);
            statusPill.setBackgroundResource(R.drawable.bg_pill_off);
            statusPill.setTextColor(getColor(R.color.warning));
        } else {
            statusPill.setText(R.string.status_on);
            statusPill.setBackgroundResource(R.drawable.bg_pill_on);
            statusPill.setTextColor(getColor(R.color.mint_dark));
        }

        accessibilityState.setText(accessibility ? R.string.state_on : R.string.state_off);
        accessibilityState.setTextColor(getColor(accessibility ? R.color.mint_dark : R.color.danger));
        accessibilityAction.setText(accessibility ? R.string.action_go : R.string.action_enable);
        if (!accessibility) {
            heroHint.setText(R.string.hero_off_hint);
        } else if (paused) {
            heroHint.setText(R.string.hero_paused_hint);
        } else {
            heroHint.setText(R.string.hero_on_hint);
        }

        boolean batteryOk = GuardUtils.isBatteryOptimizationIgnored(this);
        batteryState.setText(batteryOk ? R.string.state_battery_ok : R.string.state_battery_bad);
        batteryState.setTextColor(getColor(batteryOk ? R.color.mint_dark : R.color.warning));
        bootState.setText(Prefs.watchdog(this) ? R.string.state_boot_ok : R.string.state_boot_tip);

        bindingSwitches = true;
        switchSplash.setChecked(Prefs.splash(this));
        switchJump.setChecked(Prefs.jump(this));
        bindingSwitches = false;

        Summary summary = db.summary();
        totalCount.setText(String.valueOf(summary.total));
        todayCount.setText(String.valueOf(summary.today));
        jumpCount.setText(String.valueOf(summary.jumps));
        appCount.setText(String.valueOf(summary.apps));

        List<AppStat> stats = db.appStats();
        statsContainer.removeAllViews();
        if (stats.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            statsContainer.setVisibility(View.GONE);
            return;
        }
        emptyState.setVisibility(View.GONE);
        statsContainer.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppStat stat : stats) {
            View row = inflater.inflate(R.layout.item_app_stat, statsContainer, false);
            ImageView icon = row.findViewById(R.id.appIcon);
            Drawable drawable = AppInfoUtils.icon(this, stat.packageName);
            if (drawable != null) {
                icon.setImageDrawable(drawable);
            } else {
                icon.setImageResource(R.drawable.ic_apps);
            }
            TextView name = row.findViewById(R.id.appName);
            TextView meta = row.findViewById(R.id.appMeta);
            TextView count = row.findViewById(R.id.appCount);
            TextView today = row.findViewById(R.id.appToday);
            name.setText(stat.label);
            meta.setText(getString(R.string.stats_splash) + " " + stat.splashCount()
                    + " · " + getString(R.string.stats_jump) + " " + stat.jumps
                    + " · " + formatRelative(stat.lastAt));
            count.setText(String.valueOf(stat.total));
            today.setText(getString(R.string.stats_today, stat.today));
            today.setTextColor(getColor(stat.today > 0 ? R.color.mint_dark : R.color.muted_light));
            row.setOnClickListener(v -> showDetail(stat));
            statsContainer.addView(row);
        }
    }

    private void showAccessibilityDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_accessibility_title)
                .setMessage(R.string.dialog_accessibility_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.dialog_accessibility_confirm, (d, w) -> {
                    requestNotificationPermission();
                    GuardUtils.openAccessibilitySettings(this);
                })
                .show();
    }

    private void showBatteryDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_battery_title)
                .setMessage(R.string.dialog_battery_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.dialog_autostart_confirm,
                        (d, w) -> GuardUtils.openAutostart(this))
                .setPositiveButton(R.string.dialog_battery_confirm,
                        (d, w) -> GuardUtils.openBatterySettings(this))
                .show();
    }

    private void showBootDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_autostart_title)
                .setMessage(R.string.dialog_autostart_message)
                .setNegativeButton(R.string.action_close, null)
                .setNeutralButton(R.string.dialog_battery_confirm,
                        (d, w) -> GuardUtils.openBatterySettings(this))
                .setPositiveButton(R.string.dialog_autostart_confirm,
                        (d, w) -> GuardUtils.openAutostart(this))
                .show();
    }

    private void showWelcomeDialog() {
        Prefs.markFirstRunDone(this);
        new AlertDialog.Builder(this)
                .setTitle("欢迎使用清屏")
                .setMessage("清屏通过 Android 无障碍服务，自动点击开屏页的「跳过 / 关闭广告」，"
                        + "并在误触跳转到购物 App、浏览器时尽快返回。\n\n"
                        + "它不申请联网权限，统计只保存在本机。\n\n"
                        + "系统不允许应用在点击发生之前拦截跳转，所以误触保护会先让目标页面短暂出现，再自动返回。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("去开启守护", (d, w) -> showAccessibilityDialog())
                .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_clear_title)
                .setMessage(R.string.dialog_clear_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_clear, (d, w) -> {
                    db.clearAll();
                    refresh();
                })
                .show();
    }

    private void showDetail(AppStat stat) {
        List<InterceptEvent> events = db.recent(stat.packageName, 12);
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.detail_summary,
                stat.splashCount(), stat.jumps, stat.today, stat.total));
        sb.append("\n\n").append(getString(R.string.detail_recent)).append('\n');
        if (events.isEmpty()) {
            sb.append(getString(R.string.detail_empty));
        } else {
            for (InterceptEvent e : events) {
                sb.append(formatTime(e.createdAt)).append("   ");
                if (StatsDb.KIND_JUMP.equals(e.kind)) {
                    String target = e.detail == null || e.detail.isEmpty() ? "目标应用" : e.detail;
                    sb.append(getString(R.string.kind_jump, target));
                } else if (StatsDb.KIND_CLOSE.equals(e.kind)) {
                    sb.append(getString(R.string.kind_close));
                } else {
                    sb.append(getString(R.string.kind_skip));
                }
                sb.append('\n');
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.detail_title, stat.label))
                .setMessage(sb.toString().trim())
                .setNegativeButton(R.string.action_close, null)
                .setNeutralButton(R.string.detail_clear, (d, w) -> {
                    db.clearPackage(stat.packageName);
                    refresh();
                })
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            maybeStartWatchdog();
        }
    }

    private String formatRelative(long time) {
        long diff = System.currentTimeMillis() - time;
        if (diff < 60_000L) {
            return "刚刚";
        }
        if (diff < 3_600_000L) {
            return (diff / 60_000L) + " 分钟前";
        }
        if (diff < 86_400_000L) {
            return (diff / 3_600_000L) + " 小时前";
        }
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(time));
    }

    private String formatTime(long time) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time);
        boolean sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        if (sameDay) {
            return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(time));
        }
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "昨天 " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(time));
        }
        return new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(time));
    }
}
