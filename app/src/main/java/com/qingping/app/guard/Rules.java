package com.qingping.app.guard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Text heuristics and package classification for the accessibility guard. */
public final class Rules {

    /** Common landing apps for splash mis-taps. User can edit this list in settings. */
    public static final Set<String> DEFAULT_JUMP_TARGETS;

    static {
        Set<String> s = new LinkedHashSet<>();
        // Shopping / market places
        s.add("com.taobao.taobao");
        s.add("com.tmall.wireless");
        s.add("com.jingdong.app.mall");
        s.add("com.xunmeng.pinduoduo");
        s.add("com.taobao.idlefish");
        s.add("com.eg.android.AlipayGphone");
        s.add("com.achievo.vipshop");
        s.add("com.suning.mobile.ebuy");
        // Browsers
        s.add("com.tencent.mtt");
        s.add("com.UCMobile");
        s.add("com.baidu.searchbox");
        s.add("com.quark.browser");
        s.add("com.android.chrome");
        s.add("com.android.browser");
        s.add("com.sec.android.app.sbrowser");
        s.add("com.huawei.browser");
        s.add("com.miui.browser");
        s.add("com.heytap.browser");
        s.add("com.vivo.browser");
        // App stores
        s.add("com.android.vending");
        s.add("com.huawei.appmarket");
        s.add("com.xiaomi.market");
        s.add("com.oppo.market");
        s.add("com.bbk.appstore");
        s.add("com.tencent.android.qqdownloader");
        s.add("com.360buy.jdmobile");
        // Short-video apps are frequent forced-landing targets
        s.add("com.ss.android.ugc.aweme");
        s.add("com.smile.gifmaker");
        s.add("com.kuaishou.nebula");
        DEFAULT_JUMP_TARGETS = Collections.unmodifiableSet(s);
    }

    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
    ));

    private static final Set<String> CLOSE_EXACT = new HashSet<>(Arrays.asList(
            "关闭", "關閉", "close", "dismiss", "×", "x", "✕", "✖", "╳", "✗", "✘"
    ));

    private Rules() {
    }

    public static String normalize(CharSequence raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toString().toLowerCase(Locale.ROOT);
        s = s.replace('\u00A0', ' ').replace('\u3000', ' ');
        s = s.replace(" ", "").replace("\n", "").replace("\t", "");
        s = s.replace('（', '(').replace('）', ')');
        return s.trim();
    }

    public static boolean isSkipText(String n) {
        if (n.isEmpty()) {
            return false;
        }
        if (n.contains("跳过") || n.contains("跳過")) {
            return true;
        }
        if (n.equals("skip") || n.startsWith("skip") || n.contains("skipad")) {
            return true;
        }
        return n.contains("skipad") || n.contains("skipthisad");
    }

    public static boolean isCloseAdText(String n) {
        if (n.isEmpty()) {
            return false;
        }
        return n.contains("关闭广告") || n.contains("關閉廣告")
                || n.contains("closead") || n.contains("close this ad")
                || n.contains("dismissad") || n.contains("关闭此广告")
                || n.contains("關閉此廣告");
    }

    public static boolean isExactClose(String n) {
        return CLOSE_EXACT.contains(n);
    }

    public static boolean hasAdMarker(String n) {
        if (n.isEmpty()) {
            return false;
        }
        return n.contains("广告") || n.contains("廣告")
                || n.contains("推广") || n.contains("推廣")
                || n.contains("赞助") || n.contains("贊助")
                || n.contains("advertisement") || n.contains("sponsored")
                || n.contains("跳过") || n.contains("跳過")
                || n.contains("skipad") || n.contains("skip");
    }

    public static boolean isIgnored(String pkg, Context context) {
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        if (pkg.equals(context.getPackageName())) {
            return true;
        }
        if (IGNORED.contains(pkg)) {
            return true;
        }
        if (pkg.startsWith("com.android.systemui")) {
            return true;
        }
        return getHomePackages(context).contains(pkg) || getImePackages(context).contains(pkg);
    }

    public static Set<String> getHomePackages(Context context) {
        Set<String> result = new HashSet<>();
        try {
            PackageManager pm = context.getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo ri : list) {
                if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    result.add(ri.activityInfo.packageName);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static Set<String> getImePackages(Context context) {
        Set<String> result = new HashSet<>();
        try {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (android.view.inputmethod.InputMethodInfo info : imm.getEnabledInputMethodList()) {
                    result.add(info.getPackageName());
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
