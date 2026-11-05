package app.aomarchy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import app.aomarchy.i18n.T;

/**
 * Optional background keep-alive: one low-priority foreground notification, so the app's process (and with it the
 * running Linux session) is less likely to be reclaimed while another app is in front.
 *
 * <p>It deliberately does not touch {@link SessionService}: the session keeps running exactly as before, this service
 * only holds the process in the foreground and shows the notification. Turning it off stops nothing but the
 * notification; the session is controlled from the console / the session notification.
 *
 * <p>The user-facing copy comes from {@code keepalive.*} in {@link T} and is resolved for the console's
 * {@code ui_lang} (see {@link T#wrap(Context)}); {@link #refresh(Context)} rebuilds the notification so a language
 * switch takes effect without restarting the service.
 */
public class KeepAliveService extends Service {
    /** Notification channel id, as requested by the settings page. */
    static final String CHANNEL = "background";
    /** Service action that stops the keep-alive (also used by the notification's 停止 button). */
    static final String ACTION_STOP = "app.aomarchy.keepalive.STOP";
    /** Service action that rebuilds the notification (language switch). */
    static final String ACTION_REFRESH = "app.aomarchy.keepalive.REFRESH";

    private static final String TAG = "KeepAlive";
    private static final int NOTIFICATION_ID = 42;

    private static volatile boolean running;

    private NotificationManager manager;

    public static boolean isRunning(Context ctx) {
        return running;
    }

    /** Starts the foreground service; it shows the persistent notification on {@link #onCreate}. */
    public static void start(Context ctx) {
        if (ctx == null)
            return;
        try {
            ctx.startForegroundService(new Intent(ctx, KeepAliveService.class));
            // onCreate runs one main-loop turn later: claim the state now so the widget refresh below sees it.
            running = true;
            SessionWidget.refresh(ctx);
        } catch (RuntimeException e) {
            // Starting a foreground service is restricted in the background on Android 12+: the settings page that
            // calls this is in the foreground, so this is only a safety net.
            android.util.Log.w(TAG, "could not start the keep-alive service", e);
        }
    }

    /** Stops the service and removes the notification. */
    public static void stop(Context ctx) {
        if (ctx == null)
            return;
        // onDestroy runs one main-loop turn later, after this call returns: report the stopped state right away.
        running = false;
        ctx.stopService(new Intent(ctx, KeepAliveService.class));
        SessionWidget.refresh(ctx);
    }

    /** Rebuilds the notification with the current ui_lang. No-op when the service is not running. */
    public static void refresh(Context ctx) {
        if (!running || ctx == null)
            return;
        try {
            ctx.startForegroundService(new Intent(ctx, KeepAliveService.class).setAction(ACTION_REFRESH));
        } catch (RuntimeException e) {
            android.util.Log.w(TAG, "could not refresh the keep-alive notification", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(notificationChannel(this));
        startForeground(NOTIFICATION_ID, buildNotification(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            // Stop from the notification: still startForeground first, so a startForegroundService() delivery keeps
            // its "called startForeground in time" contract, then go away.
            startForeground(NOTIFICATION_ID, buildNotification(this));
            stopSelf();
            return START_NOT_STICKY;
        }
        // Initial start and ACTION_REFRESH both end here: (re)build the foreground notification. createNotification-
        // Channel() with an existing id also updates the channel name, so a language switch reaches the system UI too.
        if (manager != null)
            manager.createNotificationChannel(notificationChannel(this));
        startForeground(NOTIFICATION_ID, buildNotification(this));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (manager != null)
            manager.cancel(NOTIFICATION_ID);
        stopForeground(true);
        SessionWidget.refresh(this); // the notification's 停止 action stops the service here, not via stop()
        super.onDestroy();
    }

    private static NotificationChannel notificationChannel(Context ctx) {
        String name = T.t("keepalive.title");
        NotificationChannel channel = new NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(T.t("keepalive.notif_text"));
        channel.setShowBadge(false);
        return channel;
    }

    private static Notification buildNotification(Context ctx) {
        T.init(ctx); // a language switch must be visible here too (refresh() runs after the console changed ui_lang)

        final int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP;
        Intent open = new Intent(ctx, ShellActivity.class).addFlags(flags);
        PendingIntent openIntent = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(ctx, KeepAliveService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getForegroundService(ctx, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(T.t("keepalive.notif_title"))
                .setContentText(T.t("keepalive.notif_text"))
                .setContentIntent(openIntent)
                .addAction(new Notification.Action.Builder(null, T.t("keepalive.notif_open"), openIntent).build())
                .addAction(new Notification.Action.Builder(null, T.t("keepalive.notif_stop"), stopIntent).build())
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setAutoCancel(false);
        if (Build.VERSION.SDK_INT >= 31)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
}
