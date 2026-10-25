package com.qingping.app.guard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.qingping.app.Prefs;
import com.qingping.app.R;
import com.qingping.app.data.StatsDb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The only component that reads other apps' accessibility trees.
 *
 * It never tries to block network requests or touches. It only performs an accessibility click
 * on a button whose own text says "跳过/关闭广告", or a BACK gesture after an unexpected app
 * switch. All matching is scoped to a cold-start watch after a foreground app change: the normal
 * launch window allows generic close buttons, then a longer, explicit-label-only watch covers
 * apps that show a branded loading screen before their splash ad.
 */
public class AdGuardService extends AccessibilityService {
    private static final String TAG = "QingPing";
    private static final int MAX_NODES = 2200;
    private static final int MAX_DEPTH = 24;
    // One retry budget for the whole cold-start watch; 30 s at ~1 s intervals stays well below it.
    private static final int MAX_SCANS = 45;

    /**
     * Some apps (Baidu Netdisk is a typical one) keep a branded loading screen for much longer
     * than the normal launch window and only then show the splash ad. The strict window is still
     * used for generic "关闭/×" buttons and for mis-tap detection, but we keep watching for
     * explicit "跳过/关闭广告" buttons for this much longer. This is what makes a delayed ad
     * catchable without turning the guard into a page-wide "close anything" clicker.
     */
    private static final long EXTENDED_WATCH_MS = 30_000L;
    private static final long SCAN_RETRY_MS = 1100L;
    private static final long EXTENDED_SCAN_RETRY_MS = 900L;
    private static final long ACTION_COOLDOWN_MS = 1200L;
    private static final long JUMP_COOLDOWN_MS = 2500L;

    /** Only treat a jump as ad-induced while the source page still looks like the same ad. */
    private static final long AD_PHASE_JUMP_WINDOW_MS = 15_000L;
    /** Without ad evidence, only a very recent tap inside a freshly opened app can be a mis-tap. */
    private static final long MISCLICK_FALLBACK_MAX_AGE_MS = 5_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            runScan();
        }
    };

    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    private boolean master;
    private boolean splashEnabled;
    private boolean jumpEnabled;
    private boolean onlyDuringLaunch;
    private boolean showToast;
    private boolean cornerFallback;
    private long launchWindowMs;
    private long jumpWindowMs;
    private int clickDelayMs;
    private Set<String> jumpTargets = new HashSet<>();
    private Set<String> appWhitelist = new HashSet<>();
    private Set<String> appBlacklist = new HashSet<>();
    private Set<String> homePackages = new HashSet<>();
    private Set<String> ignoredPackages = new HashSet<>();

    private String foregroundPkg;
    private long foregroundSince;
    private String foregroundWindowClass;
    private boolean foregroundAdPhase;
    private long foregroundAdPhaseAt;
    private String handledPkg;
    private long lastActionAt;
    private long lastPageResetAt;
    private int scanAttempts;
    private boolean scanScheduled;
    private boolean extendedPhaseLogged;

    private long lastInteractionAt;
    private String lastInteractionPkg;
    private String lastBlockedTarget;
    private long lastBlockedAt;
    private long suppressSplashUntil;
    private int screenWidth;
    private int screenHeight;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = Prefs.get(this);
        prefListener = (sharedPreferences, key) -> reloadSettings();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        reloadSettings();
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        handler.postDelayed(this::captureCurrentForeground, 500);
    }

    private void reloadSettings() {
        master = Prefs.master(this);
        splashEnabled = Prefs.splash(this);
        jumpEnabled = Prefs.jump(this);
        onlyDuringLaunch = Prefs.onlyDuringLaunch(this);
        showToast = Prefs.showToast(this);
        cornerFallback = Prefs.cornerFallback(this);
        launchWindowMs = Prefs.launchWindowMs(this);
        jumpWindowMs = Prefs.jumpWindowMs(this);
        clickDelayMs = Prefs.clickDelayMs(this);
        jumpTargets = Prefs.jumpTargets(this);
        appWhitelist = Prefs.appWhitelist(this);
        appBlacklist = Prefs.appBlacklist(this);

        homePackages = Rules.getHomePackages(this);
        ignoredPackages = new HashSet<>();
        ignoredPackages.add(getPackageName());
        ignoredPackages.add("android");
        ignoredPackages.add("com.android.systemui");
        ignoredPackages.add("com.android.permissioncontroller");
        ignoredPackages.add("com.google.android.permissioncontroller");
        ignoredPackages.add("com.android.packageinstaller");
        ignoredPackages.add("com.google.android.packageinstaller");
        ignoredPackages.addAll(homePackages);
        ignoredPackages.addAll(Rules.getImePackages(this));

        if (!master) {
            handler.removeCallbacksAndMessages(null);
            scanScheduled = false;
            handledPkg = null;
        }
    }

    private void captureCurrentForeground() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) {
                return;
            }
            String pkg = root.getPackageName().toString();
            if (!pkg.equals(getPackageName()) && !isBoundaryPackage(pkg)) {
                foregroundPkg = pkg;
                foregroundWindowClass = null;
                foregroundAdPhase = false;
                foregroundAdPhaseAt = 0;
                // We joined mid-session: don't treat the current app as freshly launched.
                foregroundSince = SystemClock.uptimeMillis() - extendedWatchMs() - 1L;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !master) {
            return;
        }
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) {
            return;
        }
        String pkg = pkgCs.toString();
        int type = event.getEventType();
        String windowClass = classNameOf(event);
        long now = SystemClock.uptimeMillis();

        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            if (!pkg.equals(getPackageName())) {
                lastInteractionAt = now;
                lastInteractionPkg = pkg;
            }
        }

        if (pkg.equals(getPackageName())) {
            return;
        }

        // Home, recents and System UI are explicit boundaries. Most importantly, they break the
        // "previous app -> target app" chain, so opening Taobao/Netdisk from the launcher or the
        // recents screen can never be mistaken for an ad mis-tap.
        if (isBoundaryWindow(pkg, windowClass)) {
            // Only a real window change means "the user is now on home/recents". System UI also
            // emits content/click events for the status bar, which must not wipe the source app.
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                clearForegroundState();
            }
            return;
        }
        if (ignoredPackages.contains(pkg)) {
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            boolean appChanged = !pkg.equals(foregroundPkg);
            if (appChanged) {
                String previous = foregroundPkg;
                long previousSince = foregroundSince;
                foregroundPkg = pkg;
                foregroundSince = now;
                foregroundWindowClass = windowClass;
                handledPkg = null;
                lastPageResetAt = now;
                scanAttempts = 0;
                extendedPhaseLogged = false;
                cancelScheduledScan();
                Log.d(TAG, "foreground=" + pkg + " class=" + windowClass);

                // Evaluate the jump while foregroundAdPhase still describes the *source* page.
                boolean block = jumpEnabled && jumpTargets.contains(pkg)
                        && shouldBlockJump(previous, pkg, previousSince, now);
                foregroundAdPhase = false;
                foregroundAdPhaseAt = 0;
                if (block) {
                    blockJump(previous, pkg, now);
                    return;
                }
                // A jump-list app can still show its own splash ad (Baidu Netdisk is exactly this
                // case). Do not exclude it from splash scanning just because it is a landing app.
                scheduleScan(220L);
            } else {
                // A long branding/loading activity often hands over to a separate ad activity in
                // the same package. Treat that as a fresh page: otherwise the retry budget has
                // already been spent on the logo and the ad is never examined.
                boolean pageChanged = windowClass != null
                        && foregroundWindowClass != null
                        && !windowClass.equals(foregroundWindowClass);
                if (windowClass != null) {
                    foregroundWindowClass = windowClass;
                }
                if (pageChanged) {
                    Log.d(TAG, "page=" + windowClass + " age=" + (now - foregroundSince)
                            + "ms scans=" + scanAttempts);
                    // After a successful skip, the ad is gone; don't let its evidence suppress a
                    // later, intentional navigation.
                    if (pkg.equals(handledPkg)) {
                        foregroundAdPhase = false;
                        foregroundAdPhaseAt = 0;
                    }
                }
                if (pageChanged && now - foregroundSince <= extendedWatchMs()
                        && now - lastPageResetAt >= 1500L) {
                    lastPageResetAt = now;
                    scanAttempts = 0;
                    extendedPhaseLogged = false;
                    cancelScheduledScan();
                }
                scheduleScan(pageChanged ? 180L : 260L);
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (pkg.equals(foregroundPkg)) {
                scheduleScan(260L);
            }
        }
    }

    private boolean shouldBlockJump(String previous, String target, long previousSince, long now) {
        if (previous == null || previous.equals(target)) {
            return false;
        }
        // The source must be a real app, not the launcher, recents, an IME or a system dialog.
        if (isBoundaryPackage(previous) || ignoredPackages.contains(previous)) {
            return false;
        }
        // App scope applies to the source app: if the user excluded it, neither skip nor return
        // should act on its behalf.
        if (!isAppAllowed(previous)) {
            return false;
        }
        long sourceAge = now - previousSince;
        boolean recentClick = previous.equals(lastInteractionPkg)
                && now - lastInteractionAt <= 2500L;
        // If the source page itself showed an explicit ad-dismiss button, a jump away from it is
        // almost certainly an ad jump (including "shake to jump" ads). This is what allows a
        // return when the source is itself in the jump-target list, e.g. Baidu Netdisk.
        boolean sourceAdPhase = foregroundAdPhase
                && sourceAge <= extendedWatchMs()
                && now - foregroundAdPhaseAt <= AD_PHASE_JUMP_WINDOW_MS;
        Log.d(TAG, "jump? source=" + previous + " target=" + target + " age=" + sourceAge
                + "ms adPhase=" + sourceAdPhase + " recentClick=" + recentClick);

        if (!sourceAdPhase) {
            // Target-to-target chains are ordinary navigation, not a splash-ad mis-tap.
            if (jumpTargets.contains(previous)) {
                return false;
            }
            if (onlyDuringLaunch) {
                if (!recentClick
                        || sourceAge > Math.min(launchWindowMs, MISCLICK_FALLBACK_MAX_AGE_MS)) {
                    return false;
                }
            } else {
                if (sourceAge > jumpWindowMs) {
                    return false;
                }
                if (!recentClick && sourceAge > 1500L) {
                    return false;
                }
            }
        }
        return !(target.equals(lastBlockedTarget) && now - lastBlockedAt < JUMP_COOLDOWN_MS);
    }

    private void blockJump(String source, String target, long now) {
        lastBlockedTarget = target;
        lastBlockedAt = now;
        suppressSplashUntil = now + 2500L;
        String sourceLabel = AppInfoUtils.label(this, source);
        String targetLabel = AppInfoUtils.label(this, target);
        Log.i(TAG, "block jump " + source + " -> " + target);
        StatsDb.get(this).record(source, sourceLabel, StatsDb.KIND_JUMP, target, targetLabel);
        if (showToast) {
            GuardUtils.toast(this, getString(R.string.toast_jump, targetLabel));
        }

        final long startedAt = now;
        handler.postDelayed(() -> {
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> {
                if (target.equals(activePackage())
                        && SystemClock.uptimeMillis() - startedAt < 3200L) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            }, 850L);
        }, 220L);
    }

    private String activePackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                return root.getPackageName().toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String classNameOf(AccessibilityEvent event) {
        CharSequence cls = event.getClassName();
        if (cls == null || cls.length() == 0) {
            return null;
        }
        return cls.toString();
    }

    /**
     * The launcher/recents are not "the previous app" when the user opens another app from them.
     * Some ROMs report the launcher as System UI or use an OEM-specific package that does not
     * resolve through CATEGORY_HOME, so use both the resolved set and a conservative name check.
     */
    private boolean isBoundaryPackage(String pkg) {
        if (pkg == null) {
            return true;
        }
        if (homePackages.contains(pkg) || "com.android.systemui".equals(pkg)) {
            return true;
        }
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("launcher")
                || p.contains("quickstep")
                || p.contains("recents")
                || p.contains("overview")
                || p.endsWith(".home")
                || p.contains(".home.");
    }

    /**
     * System UI owns recents on some ROMs, but also owns the status bar/volume dialog. Only its
     * recents/overview window is a boundary; otherwise hiding the status bar during a fullscreen ad
     * would incorrectly wipe the source app.
     */
    private boolean isBoundaryWindow(String pkg, String windowClass) {
        if ("com.android.systemui".equals(pkg)) {
            if (windowClass == null) {
                return false;
            }
            String c = windowClass.toLowerCase(Locale.ROOT);
            return c.contains("recents") || c.contains("overview") || c.contains("quickstep");
        }
        return isBoundaryPackage(pkg);
    }

    private void clearForegroundState() {
        foregroundPkg = null;
        foregroundSince = 0;
        foregroundWindowClass = null;
        foregroundAdPhase = false;
        foregroundAdPhaseAt = 0;
        handledPkg = null;
        lastPageResetAt = 0;
        scanAttempts = 0;
        extendedPhaseLogged = false;
        cancelScheduledScan();
    }

    /**
     * How long we are willing to wait for a delayed splash ad. The user's "启动保护时长" still
     * defines when generic close buttons and mis-tap returns are allowed; this longer cap only
     * keeps looking for unambiguous "跳过/关闭广告" buttons.
     */
    private long extendedWatchMs() {
        return Math.max(launchWindowMs, EXTENDED_WATCH_MS);
    }

    private boolean isStrictPhase(long now) {
        return now - foregroundSince <= launchWindowMs;
    }

    private boolean isExtendedPhase(long now) {
        return now - foregroundSince <= extendedWatchMs();
    }

    /**
     * App-level scope, modeled after the app lists used by Li Tiaotiao/GKD: an empty whitelist
     * means "all apps"; a blacklist entry always wins. It gates both splash skipping and
     * returns attributed to that source app. The separate jump-target list still controls
     * which landing apps are eligible for a return.
     */
    private boolean isAppAllowed(String pkg) {
        if (appBlacklist.contains(pkg)) {
            return false;
        }
        return appWhitelist.isEmpty() || appWhitelist.contains(pkg);
    }

    private void scheduleScan(long delay) {
        if (!master || !splashEnabled || scanScheduled) {
            return;
        }
        if (foregroundPkg == null || ignoredPackages.contains(foregroundPkg)
                || !isAppAllowed(foregroundPkg)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean strict = isStrictPhase(now);
        if ((!strict && !isExtendedPhase(now)) || now < suppressSplashUntil) {
            return;
        }
        if (foregroundPkg.equals(handledPkg) || scanAttempts >= MAX_SCANS) {
            return;
        }
        scanScheduled = true;
        long wait = Math.max(100L, Math.max(delay, clickDelayMs));
        handler.postDelayed(scanRunnable, wait);
    }

    private void runScan() {
        scanScheduled = false;
        if (!master || !splashEnabled || foregroundPkg == null
                || !isAppAllowed(foregroundPkg)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean strict = isStrictPhase(now);
        if (!strict && !isExtendedPhase(now)) {
            return;
        }
        if (!strict && !extendedPhaseLogged) {
            extendedPhaseLogged = true;
            Log.d(TAG, "extended watch pkg=" + foregroundPkg + " age=" + (now - foregroundSince) + "ms");
        }
        if (foregroundPkg.equals(handledPkg) || now - lastActionAt < ACTION_COOLDOWN_MS
                || scanAttempts >= MAX_SCANS) {
            return;
        }
        scanAttempts++;
        String pkg = foregroundPkg;
        scanOnce(pkg, strict);

        if (master && splashEnabled && pkg.equals(foregroundPkg)
                && !pkg.equals(handledPkg)) {
            scheduleScan(strict ? SCAN_RETRY_MS : EXTENDED_SCAN_RETRY_MS);
        }
    }

    private void scanOnce(String pkg, boolean strictPhase) {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return;
        }
        if (root == null || root.getPackageName() == null
                || !pkg.equals(root.getPackageName().toString())) {
            return;
        }

        ScanState state = new ScanState();
        try {
            walk(root, 0, state);
        } catch (Exception e) {
            // The window can disappear mid-traversal; never let that crash the guard.
            return;
        }
        // Remember that this cold start really showed an ad-dismiss button. If the ad then opens
        // a landing app, this is the evidence that lets the jump guard return even when the source
        // app is itself in the user's jump-target list.
        if (state.explicitAd) {
            foregroundAdPhase = true;
            foregroundAdPhaseAt = SystemClock.uptimeMillis();
            Log.d(TAG, "ad phase pkg=" + pkg + " age=" + (foregroundAdPhaseAt - foregroundSince) + "ms");
        }

        // Outside the strict launch window only click labels that explicitly say they dismiss
        // an ad. A generic "关闭/×" is too easy to confuse with normal app UI on a page the user
        // may have reached much later.
        Candidate best = state.best(state.adMarker, strictPhase);
        if (best == null) {
            if (strictPhase && cornerFallback && state.adMarker) {
                cornerFallbackClick(pkg);
            }
            return;
        }

        Log.d(TAG, "candidate=" + best.label + " kind=" + best.kind + " score=" + best.score
                + " age=" + (SystemClock.uptimeMillis() - foregroundSince) + "ms");
        boolean clicked = clickNode(best.node, best.bounds);
        if (!clicked && best.bounds != null) {
            clicked = tap(best.bounds.centerX(), best.bounds.centerY());
        }
        if (!clicked) {
            Log.d(TAG, "click failed for " + best.label);
            return;
        }

        boolean isSkip = best.kind == Candidate.KIND_SKIP;
        String kind = isSkip ? StatsDb.KIND_SKIP : StatsDb.KIND_CLOSE;
        String appLabel = AppInfoUtils.label(this, pkg);
        String detail = best.label == null ? "" : best.label;
        StatsDb.get(this).record(pkg, appLabel, kind, null, detail);
        handledPkg = pkg;
        lastActionAt = SystemClock.uptimeMillis();
        Log.i(TAG, "clicked " + kind + " in " + pkg + " label=" + detail);
        if (showToast) {
            int res = isSkip ? R.string.toast_skip : R.string.toast_close;
            GuardUtils.toast(this, getString(res, appLabel));
        }
    }

    private void cancelScheduledScan() {
        scanScheduled = false;
        handler.removeCallbacks(scanRunnable);
    }

    private void cornerFallbackClick(String pkg) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }
        int x = screenWidth - GuardUtils.dp(this, 30);
        int y = GuardUtils.dp(this, 56);
        if (!tap(x, y)) {
            return;
        }
        String appLabel = AppInfoUtils.label(this, pkg);
        StatsDb.get(this).record(pkg, appLabel, StatsDb.KIND_CLOSE, null, "右上角兜底");
        handledPkg = pkg;
        lastActionAt = SystemClock.uptimeMillis();
        if (showToast) {
            GuardUtils.toast(this, getString(R.string.toast_close, appLabel));
        }
    }

    private void walk(AccessibilityNodeInfo node, int depth, ScanState state) {
        if (node == null || depth > MAX_DEPTH || state.nodes >= MAX_NODES) {
            return;
        }
        state.nodes++;
        String label = join(node.getText(), node.getContentDescription());
        String n = Rules.normalize(label);
        if (!n.isEmpty()) {
            if (Rules.hasAdMarker(n)) {
                state.adMarker = true;
            }
            boolean skip = Rules.isSkipText(n);
            boolean closeAd = Rules.isCloseAdText(n);
            boolean exactClose = Rules.isExactClose(n);
            if ((skip || closeAd || exactClose) && n.length() <= 40
                    && node.isVisibleToUser() && node.isEnabled()) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (isUsableBounds(b)) {
                    if (skip || closeAd) {
                        state.explicitAd = true;
                    }
                    int kind;
                    int score;
                    if (skip) {
                        kind = Candidate.KIND_SKIP;
                        score = 110;
                    } else if (closeAd) {
                        kind = Candidate.KIND_CLOSE_AD;
                        score = 85;
                    } else {
                        kind = Candidate.KIND_EXACT_CLOSE;
                        score = 50;
                    }
                    if (isTopRight(b)) {
                        score += 12;
                    }
                    if (node.isClickable()) {
                        score += 8;
                    }
                    if (n.length() <= 8) {
                        score += 4;
                    }
                    if ((long) b.width() * b.height() > (long) screenWidth * screenHeight / 2) {
                        score -= 25;
                    }
                    state.candidates.add(new Candidate(node, label, new Rect(b), score, kind));
                }
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                break;
            }
            if (child != null) {
                walk(child, depth + 1, state);
            }
        }
    }

    private boolean isUsableBounds(Rect b) {
        if (b.width() <= 0 || b.height() <= 0) {
            return false;
        }
        if (screenWidth > 0 && screenHeight > 0
                && b.width() > screenWidth * 0.96f
                && b.height() > screenHeight * 0.2f) {
            return false;
        }
        return b.top >= -40 && b.bottom <= screenHeight + 80;
    }

    private boolean isTopRight(Rect b) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return false;
        }
        return b.centerX() > screenWidth * 0.55f && b.centerY() < screenHeight * 0.35f;
    }

    private boolean clickNode(AccessibilityNodeInfo node, Rect original) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 8 && current != null; i++) {
            try {
                if (current.isClickable() && current.isEnabled()) {
                    Rect b = new Rect();
                    current.getBoundsInScreen(b);
                    if (isSafeClickTarget(b, original)
                            && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                }
                AccessibilityNodeInfo parent = current.getParent();
                if (parent == current) {
                    break;
                }
                current = parent;
            } catch (Exception e) {
                break;
            }
        }
        return false;
    }

    /**
     * Avoid clicking a full-screen ad container just because it is an ancestor of the "跳过"
     * text. A tap on the text's own centre is safer in that case.
     */
    private boolean isSafeClickTarget(Rect target, Rect original) {
        if (target == null || original == null || target.width() <= 0 || target.height() <= 0) {
            return false;
        }
        long targetArea = (long) target.width() * target.height();
        long originalArea = Math.max(1L, (long) original.width() * original.height());
        long screenArea = Math.max(1L, (long) screenWidth * screenHeight);
        long min = GuardUtils.dp(this, 48);
        long maxByNode = Math.max(originalArea * 12L, min * min);
        long max = Math.min(screenArea / 3L, maxByNode);
        return targetArea <= max;
    }

    private boolean tap(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 45);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    private static String join(CharSequence a, CharSequence b) {
        String left = a == null ? "" : a.toString();
        String right = b == null ? "" : b.toString();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty() || left.contains(right)) {
            return left;
        }
        return left + " " + right;
    }

    private static final class ScanState {
        int nodes;
        boolean adMarker;
        boolean explicitAd;
        final List<Candidate> candidates = new ArrayList<>();

        Candidate best(boolean hasAd, boolean allowGenericClose) {
            Candidate best = null;
            for (Candidate c : candidates) {
                if (c.kind == Candidate.KIND_EXACT_CLOSE && (!allowGenericClose || !hasAd)) {
                    continue;
                }
                if (best == null || c.score > best.score) {
                    best = c;
                }
            }
            return best;
        }
    }

    private static final class Candidate {
        static final int KIND_SKIP = 0;
        static final int KIND_CLOSE_AD = 1;
        static final int KIND_EXACT_CLOSE = 2;

        final AccessibilityNodeInfo node;
        final String label;
        final Rect bounds;
        final int score;
        final int kind;

        Candidate(AccessibilityNodeInfo node, String label, Rect bounds, int score, int kind) {
            this.node = node;
            this.label = label;
            this.bounds = bounds;
            this.score = score;
            this.kind = kind;
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        scanScheduled = false;
    }

    @Override
    public void onDestroy() {
        try {
            if (prefs != null && prefListener != null) {
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            }
        } catch (Exception ignored) {
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
