package app.aomarchy;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import app.aomarchy.i18n.T;

/**
 * Home-screen widget: the session's state plus one-tap 控制台 / 终端 entries.
 *
 * <p>The state is the same one the console's top chip shows: no system → 未安装; startup mode terminal → 终端模式
 * (checked before "running", exactly like {@link ShellActivity}); session service alive → 运行中; otherwise 就绪.
 * {@link #refresh(Context)} is called by {@link SessionService} when a session starts or stops — the widget requests
 * no periodic updates ({@code updatePeriodMillis="0"}), so nothing polls in the background.
 *
 * <p>Views and ids are resolved through {@code getIdentifier} instead of {@code R}: the project's offline javac
 * check only sees a stale generated R (no R.layout / R.id inner classes at all), while at runtime the ids come from
 * the compiled resources. All visible text is a resource string read through {@link T#wrap(Context)}, so the widget
 * follows the console's {@code ui_lang} (res/values = English, res/values-zh = Chinese).
 */
public class SessionWidget extends AppWidgetProvider {
    private static final String TAG = "SessionWidget";
    /** Startup mode preference, as read by ShellActivity.openDesktop() and the 控制 page. */
    private static final String PREF_SESSION_MODE = "session_mode";
    private static final String MODE_TERMINAL = "terminal";
    /** Must match ShellActivity.ACTION_TERMINAL (private there): opens the terminal instead of the console. */
    private static final String ACTION_TERMINAL = "terminal";
    private static final int REQ_CONSOLE = 1;
    private static final int REQ_TERMINAL = 2;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateAll(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        refresh(context); // a freshly added instance shows the current state without waiting for a session change
    }

    /** Re-renders every instance. Safe from the session service (main or any thread) and from launcher broadcasts. */
    public static void refresh(Context context) {
        if (context == null)
            return;
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, SessionWidget.class));
            if (ids != null && ids.length > 0)
                updateAll(context, manager, ids);
        } catch (RuntimeException e) {
            android.util.Log.w(TAG, "could not refresh widgets", e);
        }
    }

    private static void updateAll(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0)
            return;
        RemoteViews views = build(context);
        if (views == null)
            return; // resources missing: nothing sensible to show, and a launcher crash would be worse
        for (int id : ids) {
            try {
                manager.updateAppWidget(id, views);
            } catch (RuntimeException e) {
                android.util.Log.w(TAG, "could not update widget " + id, e);
            }
        }
    }

    private static RemoteViews build(Context context) {
        Context ui = T.wrap(context); // resource strings follow ui_lang like the tile and the console
        String packageName = context.getPackageName();
        int layout = res(ui, "widget_session", "layout");
        int statusId = res(ui, "widget_status", "id");
        int consoleId = res(ui, "widget_console", "id");
        int terminalId = res(ui, "widget_terminal", "id");
        int titleFormat = res(ui, "widget_title_format", "string");
        int labelId = res(ui, "widget_label", "string");
        if (layout == 0 || statusId == 0 || consoleId == 0 || terminalId == 0
                || titleFormat == 0 || labelId == 0) {
            android.util.Log.w(TAG, "widget resources are missing (layout, ids or strings)");
            return null;
        }

        RemoteViews views = new RemoteViews(packageName, layout);
        views.setTextViewText(statusId, ui.getString(titleFormat, ui.getString(labelId), statusText(ui)));

        // Both entries reuse the console activity (singleTop): an already running console gets onNewIntent.
        final int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP;
        Intent console = new Intent(context, ShellActivity.class).addFlags(flags);
        views.setOnClickPendingIntent(consoleId, PendingIntent.getActivity(context, REQ_CONSOLE, console,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent terminal = new Intent(context, ShellActivity.class)
                .putExtra(ShellActivity.EXTRA_ACTION, ACTION_TERMINAL)
                .addFlags(flags);
        views.setOnClickPendingIntent(terminalId, PendingIntent.getActivity(context, REQ_TERMINAL, terminal,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }

    /** "未安装" / "终端模式" / "运行中" / "就绪", the same precedence as ShellActivity.updateStatus(). */
    private static String statusText(Context context) {
        if (!SessionService.rootfsReady(context))
            return str(context, "widget_status_not_installed");
        if (MODE_TERMINAL.equals(sessionMode(context)))
            return str(context, "widget_status_terminal");
        return str(context, SessionService.started ? "widget_status_running" : "widget_status_ready");
    }

    private static String sessionMode(Context context) {
        return context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getString(PREF_SESSION_MODE, "desktop");
    }

    private static String str(Context context, String name) {
        int id = res(context, name, "string");
        return id == 0 ? name : context.getString(id);
    }

    private static int res(Context context, String name, String type) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }
}
