package com.qingping.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Local-only event store. No network permission is declared anywhere in the app, so these
 * records cannot leave the device.
 */
public final class StatsDb extends SQLiteOpenHelper {
    public static final String KIND_SKIP = "SKIP";
    public static final String KIND_CLOSE = "CLOSE";
    public static final String KIND_JUMP = "JUMP";

    private static final String DB_NAME = "qingping.db";
    private static final int DB_VERSION = 1;
    private static final long RETENTION_MS = 180L * 24 * 60 * 60 * 1000;

    private static volatile StatsDb instance;

    private StatsDb(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    public static StatsDb get(Context context) {
        if (instance == null) {
            synchronized (StatsDb.class) {
                if (instance == null) {
                    instance = new StatsDb(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "package_name TEXT NOT NULL,"
                + "app_label TEXT NOT NULL,"
                + "kind TEXT NOT NULL,"
                + "target_package TEXT,"
                + "detail TEXT,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_events_created ON events(created_at)");
        db.execSQL("CREATE INDEX idx_events_pkg ON events(package_name, created_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the initial schema. Future migrations belong here.
    }

    public void record(String pkg, String label, String kind, String targetPkg, String detail) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put("package_name", pkg);
            cv.put("app_label", label == null || label.isEmpty() ? pkg : label);
            cv.put("kind", kind);
            cv.put("target_package", targetPkg);
            cv.put("detail", detail);
            cv.put("created_at", System.currentTimeMillis());
            getWritableDatabase().insert("events", null, cv);
        } catch (Exception ignored) {
            // A failed counter must never break the guard itself.
        }
    }

    public static long todayStart() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public Summary summary() {
        long today = todayStart();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*),"
                            + " SUM(CASE WHEN created_at >= ? THEN 1 ELSE 0 END),"
                            + " SUM(CASE WHEN kind = ? THEN 1 ELSE 0 END),"
                            + " COUNT(DISTINCT package_name)"
                            + " FROM events",
                    new String[]{String.valueOf(today), KIND_JUMP});
            if (c.moveToFirst()) {
                return new Summary(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return new Summary(0, 0, 0, 0);
    }

    public List<AppStat> appStats() {
        long today = todayStart();
        List<AppStat> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT package_name, MAX(app_label) AS label, COUNT(*) AS total,"
                            + " SUM(CASE WHEN created_at >= ? THEN 1 ELSE 0 END) AS today,"
                            + " SUM(CASE WHEN kind = ? THEN 1 ELSE 0 END) AS jumps,"
                            + " MAX(created_at) AS last_at"
                            + " FROM events GROUP BY package_name"
                            + " ORDER BY total DESC, last_at DESC LIMIT 200",
                    new String[]{String.valueOf(today), KIND_JUMP});
            while (c.moveToNext()) {
                result.add(new AppStat(
                        c.getString(0),
                        c.getString(1),
                        c.getInt(2),
                        c.getInt(3),
                        c.getInt(4),
                        c.getLong(5)));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return result;
    }

    public List<InterceptEvent> recent(String pkg, int limit) {
        List<InterceptEvent> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(
                    "events",
                    new String[]{"id", "package_name", "app_label", "kind", "target_package", "detail", "created_at"},
                    "package_name = ?",
                    new String[]{pkg},
                    null,
                    null,
                    "created_at DESC",
                    String.valueOf(limit));
            while (c.moveToNext()) {
                result.add(new InterceptEvent(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getString(5),
                        c.getLong(6)));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return result;
    }

    public void clearAll() {
        try {
            getWritableDatabase().delete("events", null, null);
        } catch (Exception ignored) {
        }
    }

    public void clearPackage(String pkg) {
        try {
            getWritableDatabase().delete("events", "package_name = ?", new String[]{pkg});
        } catch (Exception ignored) {
        }
    }

    public int totalCount() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events", null);
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return 0;
    }

    /** Keep the local database small; called once when the app process starts. */
    public void prune() {
        try {
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            getWritableDatabase().delete("events", "created_at < ?", new String[]{String.valueOf(cutoff)});
        } catch (Exception ignored) {
        }
    }
}
