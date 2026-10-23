package com.qingping.app.guard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.qingping.app.Prefs;
import com.qingping.app.R;
import com.qingping.app.data.StatsDb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The only component that reads other apps' accessibility trees.
 *
 * It never tries to block network requests or touches. It only performs an accessibility click
 * on a button whose own text says "跳过/关闭广告", or a BACK gesture after an unexpected app
 * switch. All matching is deliberately scoped to the first few seconds after a foreground app
 * change to avoid interfering with normal app UI.
 */
public class AdGuardService extends AccessibilityService {
    private static final int MAX_NODES = 2200;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_SCANS = 10;
    private static final long SCAN_RETRY_MS = 1100L;
    private static final long ACTION_COOLDOWN_MS = 1200L;
    private static final long JUMP_COOLDOWN_MS = 2500L;

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
    private Set<String> homePackages = new HashSet<>();
    private Set<String> ignoredPackages = new HashSet<>();

    private String foregroundPkg;
    private long foregroundSince;
    private String handledPkg;
    private long lastActionAt;
    private int scanAttempts;
    private boolean scanScheduled;

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
            if (!pkg.equals(getPackageName()) && !homePackages.contains(pkg)) {
                foregroundPkg = pkg;
                // We joined mid-session: don't treat the current app as freshly launched.
                foregroundSince = SystemClock.uptimeMillis() - launchWindowMs - 1L;
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

        // Going home is an explicit boundary: never treat a launch from the launcher as an ad jump.
        if (homePackages.contains(pkg)) {
            foregroundPkg = null;
            foregroundSince = 0;
            handledPkg = null;
            scanAttempts = 0;
            scanScheduled = false;
            handler.removeCallbacks(scanRunnable);
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
                handledPkg = null;
                scanAttempts = 0;
                scanScheduled = false;
                handler.removeCallbacks(scanRunnable);

                if (jumpEnabled && jumpTargets.contains(pkg)
                        && shouldBlockJump(previous, pkg, previousSince, now)) {
                    blockJump(previous, pkg, now);
                    return;
                }
                if (!jumpTargets.contains(pkg)) {
                    scheduleScan(220L);
                }
            } else {
                scheduleScan(260L);
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
        if (ignoredPackages.contains(previous) || jumpTargets.contains(previous)) {
            return false;
        }
        long sourceAge = now - previousSince;
        long maxAge = onlyDuringLaunch ? launchWindowMs : jumpWindowMs;
        if (sourceAge > maxAge) {
            return false;
        }
        if (!onlyDuringLaunch) {
            boolean recentClick = previous.equals(lastInteractionPkg)
                    && now - lastInteractionAt <= 2500L;
            if (!recentClick && sourceAge > 1500L) {
                return false;
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

    private void scheduleScan(long delay) {
        if (!master || !splashEnabled || scanScheduled) {
            return;
        }
        if (foregroundPkg == null || ignoredPackages.contains(foregroundPkg)
                || jumpTargets.contains(foregroundPkg)) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long age = now - foregroundSince;
        if (age > launchWindowMs || now < suppressSplashUntil) {
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
        if (!master || !splashEnabled || foregroundPkg == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - foregroundSince > launchWindowMs) {
            return;
        }
        if (foregroundPkg.equals(handledPkg) || now - lastActionAt < ACTION_COOLDOWN_MS) {
            return;
        }
        scanAttempts++;
        String pkg = foregroundPkg;
        scanOnce(pkg);

        if (master && splashEnabled && pkg.equals(foregroundPkg)
                && !pkg.equals(handledPkg) && scanAttempts < MAX_SCANS
                && SystemClock.uptimeMillis() - foregroundSince <= launchWindowMs) {
            scheduleScan(SCAN_RETRY_MS);
        }
    }

    private void scanOnce(String pkg) {
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
        Candidate best = state.best(state.adMarker);
        if (best == null) {
            if (cornerFallback && state.adMarker) {
                cornerFallbackClick(pkg);
            }
            return;
        }

        String normalized = Rules.normalize(best.label);
        boolean isSkip = Rules.isSkipText(normalized);
        boolean clicked = clickNode(best.node, best.bounds);
        if (!clicked && best.bounds != null) {
            clicked = tap(best.bounds.centerX(), best.bounds.centerY());
        }
        if (!clicked) {
            return;
        }

        String kind = isSkip ? StatsDb.KIND_SKIP : StatsDb.KIND_CLOSE;
        String appLabel = AppInfoUtils.label(this, pkg);
        String detail = best.label == null ? "" : best.label;
        StatsDb.get(this).record(pkg, appLabel, kind, null, detail);
        handledPkg = pkg;
        lastActionAt = SystemClock.uptimeMillis();
        if (showToast) {
            int res = isSkip ? R.string.toast_skip : R.string.toast_close;
            GuardUtils.toast(this, getString(res, appLabel));
        }
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
                    int score;
                    if (skip) {
                        score = 110;
                    } else if (closeAd) {
                        score = 85;
                    } else {
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
                    state.candidates.add(new Candidate(node, label, new Rect(b), score, skip));
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
        final List<Candidate> candidates = new ArrayList<>();

        Candidate best(boolean hasAd) {
            Candidate best = null;
            for (Candidate c : candidates) {
                if (!c.isSkip && !hasAd) {
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
        final AccessibilityNodeInfo node;
        final String label;
        final Rect bounds;
        final int score;
        final boolean isSkip;

        Candidate(AccessibilityNodeInfo node, String label, Rect bounds, int score, boolean isSkip) {
            this.node = node;
            this.label = label;
            this.bounds = bounds;
            this.score = score;
            this.isSkip = isSkip;
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
