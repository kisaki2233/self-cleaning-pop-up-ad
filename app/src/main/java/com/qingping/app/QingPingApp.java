package com.qingping.app;

import android.app.Application;

import com.qingping.app.data.StatsDb;

public class QingPingApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        StatsDb.get(this).prune();
    }
}
