package com.qingping.app.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.qingping.app.Prefs;

/**
 * Boot receiver is best-effort. The accessibility service is normally rebound by the system
 * on its own; this also restores the optional foreground watchdog notification.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot) {
            return;
        }
        if (!Prefs.master(context) || !Prefs.watchdog(context)) {
            return;
        }
        if (!GuardUtils.isAccessibilityEnabled(context)) {
            return;
        }
        GuardForegroundService.start(context);
    }
}
