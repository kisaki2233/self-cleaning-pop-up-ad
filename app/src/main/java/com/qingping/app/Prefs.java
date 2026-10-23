package com.qingping.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.qingping.app.guard.Rules;

import java.util.HashSet;
import java.util.Set;

/** All user-visible settings live in one local SharedPreferences file. */
public final class Prefs {
    private static final String FILE = "qingping_settings";

    public static final String KEY_MASTER = "master_enabled";
    public static final String KEY_SPLASH = "splash_enabled";
    public static final String KEY_JUMP = "jump_enabled";
    public static final String KEY_ONLY_SPLASH = "jump_only_during_launch";
    public static final String KEY_TOAST = "show_toast";
    public static final String KEY_CORNER = "corner_fallback";
    public static final String KEY_WATCHDOG = "foreground_watchdog";
    public static final String KEY_LAUNCH_MS = "launch_window_ms";
    public static final String KEY_CLICK_DELAY = "click_delay_ms";
    public static final String KEY_JUMP_WINDOW = "jump_window_ms";
    public static final String KEY_TARGETS = "jump_targets";
    public static final String KEY_TARGETS_INIT = "jump_targets_initialized";
    public static final String KEY_FIRST_RUN = "first_run_done";

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean master(Context c) {
        return get(c).getBoolean(KEY_MASTER, true);
    }

    public static boolean splash(Context c) {
        return get(c).getBoolean(KEY_SPLASH, true);
    }

    public static boolean jump(Context c) {
        return get(c).getBoolean(KEY_JUMP, true);
    }

    public static boolean onlyDuringLaunch(Context c) {
        return get(c).getBoolean(KEY_ONLY_SPLASH, true);
    }

    public static boolean showToast(Context c) {
        return get(c).getBoolean(KEY_TOAST, true);
    }

    public static boolean cornerFallback(Context c) {
        return get(c).getBoolean(KEY_CORNER, false);
    }

    public static boolean watchdog(Context c) {
        return get(c).getBoolean(KEY_WATCHDOG, true);
    }

    public static long launchWindowMs(Context c) {
        long v = get(c).getLong(KEY_LAUNCH_MS, 8000L);
        return Math.max(3000L, Math.min(15000L, v));
    }

    public static int clickDelayMs(Context c) {
        int v = get(c).getInt(KEY_CLICK_DELAY, 200);
        return Math.max(0, Math.min(800, v));
    }

    public static long jumpWindowMs(Context c) {
        long v = get(c).getLong(KEY_JUMP_WINDOW, 3500L);
        return Math.max(1200L, Math.min(8000L, v));
    }

    /**
     * The first time this is called the known shopping/browser packages become the default
     * target list. An intentionally empty list stays empty because we track initialization
     * separately.
     */
    public static Set<String> jumpTargets(Context c) {
        SharedPreferences p = get(c);
        if (!p.getBoolean(KEY_TARGETS_INIT, false)) {
            Set<String> initial = new HashSet<>(Rules.DEFAULT_JUMP_TARGETS);
            p.edit()
                    .putStringSet(KEY_TARGETS, initial)
                    .putBoolean(KEY_TARGETS_INIT, true)
                    .apply();
            return initial;
        }
        Set<String> stored = p.getStringSet(KEY_TARGETS, null);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    public static void setJumpTargets(Context c, Set<String> targets) {
        get(c).edit()
                .putStringSet(KEY_TARGETS, new HashSet<>(targets))
                .putBoolean(KEY_TARGETS_INIT, true)
                .apply();
    }

    public static boolean firstRun(Context c) {
        return !get(c).getBoolean(KEY_FIRST_RUN, false);
    }

    public static void markFirstRunDone(Context c) {
        get(c).edit().putBoolean(KEY_FIRST_RUN, true).apply();
    }
}
