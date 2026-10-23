package com.qingping.app.guard;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.qingping.app.MainActivity;

import java.util.List;

/** Small helpers shared by the UI and the guard services. */
public final class GuardUtils {
    private GuardUtils() {
    }

    public static boolean isAccessibilityEnabled(Context context) {
        try {
            AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> enabled =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                ComponentName expected = new ComponentName(context, AdGuardService.class);
                for (AccessibilityServiceInfo info : enabled) {
                    ResolveInfo ri = info.getResolveInfo();
                    if (ri != null && ri.serviceInfo != null) {
                        ComponentName actual = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                        if (expected.equals(actual)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback for ROMs that do not update AccessibilityManager immediately.
        try {
            String flat = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat != null) {
                String expected = new ComponentName(context, AdGuardService.class).flattenToString();
                for (String part : flat.split(":")) {
                    if (expected.equalsIgnoreCase(part)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void openAccessibilitySettings(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            openAppDetails(context);
        }
    }

    public static boolean isBatteryOptimizationIgnored(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static void openBatterySettings(Context context) {
        try {
            if (!isBatteryOptimizationIgnored(context)) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + context.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            openAppDetails(context);
        }
    }

    public static void openAppDetails(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + context.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** Tries the common OEM autostart screens; falls back to the app details page. */
    public static void openAutostart(Context context) {
        String[][] candidates = new String[][]{
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.sec.android.lool", "com.sec.android.lool.activity.BatteryActivity"},
                {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"}
        };
        PackageManager pm = context.getPackageManager();
        for (String[] pair : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(pair[0], pair[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (pm.resolveActivity(i, 0) != null) {
                    context.startActivity(i);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        openAppDetails(context);
    }

    public static void openNotificationSettings(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            openAppDetails(context);
        }
    }

    public static void toast(Context context, String text) {
        try {
            Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void launchMain(Context context) {
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context);
    }

    public static boolean isActivityResolvable(Context context, Intent intent) {
        try {
            return context.getPackageManager().resolveActivity(intent, 0) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static void finishToHome(Activity activity) {
        activity.finish();
    }
}
