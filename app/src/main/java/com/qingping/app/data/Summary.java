package com.qingping.app.data;

/** Aggregated counters for the dashboard header. */
public final class Summary {
    public final int total;
    public final int today;
    public final int jumps;
    public final int apps;

    public Summary(int total, int today, int jumps, int apps) {
        this.total = total;
        this.today = today;
        this.jumps = jumps;
        this.apps = apps;
    }
}
