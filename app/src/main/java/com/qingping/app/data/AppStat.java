package com.qingping.app.data;

/** One row shown in the per-app statistics list. */
public final class AppStat {
    public final String packageName;
    public final String label;
    public final int total;
    public final int today;
    public final int jumps;
    public final long lastAt;

    public AppStat(String packageName, String label, int total, int today, int jumps, long lastAt) {
        this.packageName = packageName;
        this.label = label;
        this.total = total;
        this.today = today;
        this.jumps = jumps;
        this.lastAt = lastAt;
    }

    public int splashCount() {
        return Math.max(0, total - jumps);
    }
}
