package app.personal.my;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The web app works out the prayer times and hands over a list. Only the next one is ever
 * registered with Android; when it fires, the following one is registered.
 */
final class Alarms {
    static final String KEY = "key", AT = "at", NAME = "name", ACTION = "action", PRAYED = "prayed";
    private static final String PREFS = "alarms", LIST = "list", CHANNEL = "prayer_time";
    private static final int FIRE = 1, TEST = 2, NOTIFICATION = 1;
    private static final long TOO_LATE_MS = 10 * 60 * 1000;
    // About a minute of buzzing, so it's noticed even if the phone is in a pocket.
    private static final long[] PATTERN = buildPattern();

    private Alarms() {}

    private static long[] buildPattern() {
        long[] p = new long[48];
        for (int i = 0; i < p.length; i += 4) {
            p[i] = i == 0 ? 0 : 1800; // pause
            p[i + 1] = 700;
            p[i + 2] = 250;
            p[i + 3] = 1100;
        }
        return p;
    }

    static void save(Context c, String json) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LIST, json).apply();
        scheduleNext(c);
    }

    static void scheduleNext(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent fire = new Intent(c, AlarmReceiver.class);
        JSONObject next = next(c);
        if (next == null) {
            am.cancel(pending(c, FIRE, fire));
            return;
        }
        long at = next.optLong(AT);
        fire.putExtra(KEY, next.optString(KEY)).putExtra(NAME, next.optString(NAME)).putExtra(AT, at);
        setExact(c, am, at, pending(c, FIRE, fire));
    }

    static void scheduleTest(Context c, long at) {
        Intent fire = new Intent(c, AlarmReceiver.class)
                .putExtra(KEY, "test").putExtra(NAME, "Test alarm").putExtra(AT, at);
        setExact(c, c.getSystemService(AlarmManager.class), at, pending(c, TEST, fire));
    }

    private static void setExact(Context c, AlarmManager am, long at, PendingIntent fire) {
        // setAlarmClock is what clock apps use: it fires on time, even in Doze.
        try {
            PendingIntent show = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), fire);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
        }
    }

    private static PendingIntent pending(Context c, int code, Intent i) {
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static JSONObject next(Context c) {
        String json = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LIST, "[]");
        long now = System.currentTimeMillis();
        try {
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                if (o.getLong(AT) > now) return o;
            }
        } catch (JSONException ignored) {
            // Nothing usable stored: no alarm until the app next sends a list.
        }
        return null;
    }

    static void fire(Context c, String key, String name, long at) {
        if (System.currentTimeMillis() - at > TOO_LATE_MS) return; // e.g. the phone was off
        vibrate(c);
        MainActivity open = MainActivity.visible;
        if (open != null) open.showAlarm(key, at, null);
        else notify(c, key, name, at);
    }

    static void stop(Context c) {
        c.getSystemService(Vibrator.class).cancel();
        c.getSystemService(NotificationManager.class).cancel(NOTIFICATION);
    }

    private static void vibrate(Context c) {
        AudioAttributes alarm = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
        c.getSystemService(Vibrator.class).vibrate(VibrationEffect.createWaveform(PATTERN, -1), alarm);
    }

    private static void notify(Context c, String key, String name, long at) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Prayer time", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);
        ch.enableVibration(false); // Vibrating directly: a notification can't be stopped from the app.
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);

        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_prayer)
                .setContentTitle(name)
                .setContentText("It's prayer time")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(open(c, 10, key, at, null))
                .setFullScreenIntent(open(c, 11, key, at, null), true);
        if (!"test".equals(key)) {
            b.addAction(new Notification.Action.Builder(null, "I've prayed", open(c, 12, key, at, PRAYED)).build());
        }
        nm.notify(NOTIFICATION, b.build());
    }

    private static PendingIntent open(Context c, int code, String key, long at, String action) {
        Intent i = new Intent(c, MainActivity.class)
                .putExtra(KEY, key).putExtra(AT, at).putExtra(ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean notificationsAllowed(Context c) {
        return c.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33;
    }
}
