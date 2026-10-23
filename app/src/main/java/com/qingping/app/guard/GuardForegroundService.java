package com.qingping.app.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.qingping.app.MainActivity;
import com.qingping.app.Prefs;
import com.qingping.app.R;
import com.qingping.app.data.StatsDb;

/**
 * Optional low-priority foreground watchdog. The accessibility service itself is bound and
 * restarted by the system; this notification mainly improves process priority and gives the
 * user a visible on/off indicator.
 */
public class GuardForegroundService extends Service {
    private static final String CHANNEL_ID = "qingping_guard";
    private static final int NOTIFICATION_ID = 1001;
    private static final long TICK_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateNotification();
            handler.postDelayed(this, TICK_MS);
        }
    };

    public static void start(Context context) {
        try {
            Intent i = new Intent(context, GuardForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception ignored) {
            // Background-start restrictions or user force-stop: nothing safe to retry here.
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, GuardForegroundService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.watchdog(this) || !Prefs.master(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureForeground();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
        return START_STICKY;
    }

    private void ensureForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
        }
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean accessibility = GuardUtils.isAccessibilityEnabled(this);
        String text;
        if (!accessibility) {
            text = getString(R.string.notif_text_off);
        } else {
            int total = StatsDb.get(this).totalCount();
            text = total > 0
                    ? getString(R.string.notif_text_count, total)
                    : getString(R.string.notif_text);
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(getColor(R.color.mint))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    getString(R.string.notif_action), pi).build());
        }
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
