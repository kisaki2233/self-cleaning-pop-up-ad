package com.qingping.app.data;

/** One recent interception entry, used in the detail dialog. */
public final class InterceptEvent {
    public final long id;
    public final String packageName;
    public final String label;
    public final String kind;
    public final String targetPackage;
    public final String detail;
    public final long createdAt;

    public InterceptEvent(long id, String packageName, String label, String kind,
                          String targetPackage, String detail, long createdAt) {
        this.id = id;
        this.packageName = packageName;
        this.label = label;
        this.kind = kind;
        this.targetPackage = targetPackage;
        this.detail = detail;
        this.createdAt = createdAt;
    }
}
