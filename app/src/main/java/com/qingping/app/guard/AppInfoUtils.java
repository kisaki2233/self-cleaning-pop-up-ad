package com.qingping.app.guard;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/** Cached package labels/icons so accessibility events stay cheap. */
public final class AppInfoUtils {
    private static final LruCache<String, String> LABELS = new LruCache<>(96);
    private static final LruCache<String, Drawable> ICONS = new LruCache<>(64);

    private AppInfoUtils() {
    }

    public static String label(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "未知应用";
        }
        String cached = LABELS.get(pkg);
        if (cached != null) {
            return cached;
        }
        String label = pkg;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(info);
            if (cs != null && cs.length() > 0) {
                label = cs.toString();
            }
        } catch (Exception ignored) {
        }
        LABELS.put(pkg, label);
        return label;
    }

    public static Drawable icon(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        Drawable cached = ICONS.get(pkg);
        if (cached != null) {
            return cached;
        }
        try {
            Drawable d = context.getPackageManager().getApplicationIcon(pkg);
            if (d != null) {
                ICONS.put(pkg, d);
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }
}
