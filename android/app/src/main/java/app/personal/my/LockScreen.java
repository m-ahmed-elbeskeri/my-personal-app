package app.personal.my;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** The optional glance screen shown on top of Android's own lock screen. */
final class LockScreen {
    private static final String PREFS = "lockscreen", ENABLED = "enabled";

    private LockScreen() {}

    static boolean enabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    /** "off", "needs" (waiting for the display-over-apps permission) or "on". */
    static String state(Context c) {
        if (!enabled(c)) return "off";
        return Settings.canDrawOverlays(c) ? "on" : "needs";
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).commit();
        sync(c);
    }

    /** Runs the watcher service only while the feature is on and allowed. */
    static void sync(Context c) {
        Intent service = new Intent(c, LockService.class);
        if ("on".equals(state(c))) {
            try {
                c.startForegroundService(service);
            } catch (RuntimeException e) {
                // Android can refuse from the background; opening the app tries again.
            }
        } else {
            c.stopService(service);
            LockActivity.close();
        }
    }
}
