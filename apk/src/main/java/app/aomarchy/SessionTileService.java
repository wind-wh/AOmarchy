package app.aomarchy;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import app.aomarchy.i18n.T;

/**
 * The notification-shade quick settings tile: one tap into the console, or straight into the terminal when the
 * startup mode is terminal. Same three outcomes as the launcher shortcuts (res/xml/shortcuts.xml), without opening
 * the app first.
 */
public class SessionTileService extends TileService {
    /** Startup mode preference, as read by ShellActivity.openDesktop() and the 控制 page. */
    private static final String PREF_SESSION_MODE = "session_mode";
    private static final String MODE_TERMINAL = "terminal";

    /** The tile label is a resource string, so it must follow ui_lang like the activities do (see {@link T#wrap}). */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(T.wrap(base));
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    /**
     * The shade shows what a tap will do: install (nothing installed yet), console, or terminal. INACTIVE only dims
     * the tile until the Linux side exists; it stays tappable.
     */
    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null)
            return;
        boolean ready = SessionService.rootfsReady(this);
        tile.setLabel(getString(!ready ? R.string.tile_uninstalled
                : MODE_TERMINAL.equals(sessionMode()) ? R.string.tile_terminal : R.string.tile_console));
        tile.setState(ready ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            final Intent intent = tapIntent();
            // A tile is not an Activity and may be tapped on the lock screen: start the activity once it is open.
            unlockAndRun(() -> launch(intent));
        } catch (Exception ignored) {
            // A tile must never take the system UI down; the launcher stays a way in.
        }
    }

    /** Where a tap goes: the 系统 page with no system, the terminal in terminal mode, else the console. */
    private Intent tapIntent() {
        if (!SessionService.rootfsReady(this)) {
            Intent intent = new Intent(this, ShellActivity.class);
            intent.putExtra(ShellActivity.EXTRA_TAB, 1); // the 系统 page installs the Linux side and explains it
            return intent;
        }
        if (MODE_TERMINAL.equals(sessionMode()))
            return new Intent(this, TerminalActivity.class);
        return new Intent(this, ShellActivity.class);
    }

    private String sessionMode() {
        return getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getString(PREF_SESSION_MODE, "desktop");
    }

    /** Starts the activity from the shade; NEW_TASK because the caller is a Service, not an Activity. */
    private void launch(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 deprecated the Intent variant: hand the system a PendingIntent instead.
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Exception e) {
            try {
                startActivity(intent);
            } catch (Exception ignored) {
                // the shade stays open rather than crashing the host process
            }
        }
    }
}
