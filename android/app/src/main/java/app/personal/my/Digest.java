package app.personal.my;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The daily to do notification. The app may not be running when it's due, so the web app keeps
 * a copy of the tasks here. isDone must match isDone() in index.html.
 */
final class Digest {
    static final String KEY = "digest";
    private static final String PREFS = "todos", DATA = "data", CHANNEL = "todo_digest";
    private static final int ALARM = 3, NOTIFICATION = 2, MAX_PER_LIST = 5;

    private Digest() {}

    static void save(Context c, String json) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DATA, json).commit(); // on disk before anything can kill us
        schedule(c);
    }

    private static JSONObject load(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DATA, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent i = new Intent(c, AlarmReceiver.class).putExtra(Alarms.KEY, KEY);
        PendingIntent fire = PendingIntent.getBroadcast(c, ALARM, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        JSONObject data = load(c);
        if (!data.optBoolean("on", false)) {
            am.cancel(fire);
            return;
        }
        LocalTime time;
        try {
            time = LocalTime.parse(data.optString("time", "08:00"));
        } catch (RuntimeException e) {
            time = LocalTime.of(8, 0);
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime next = now.with(time).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long at = next.toInstant().toEpochMilli();
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire);
        }
    }

    static void fire(Context c) {
        JSONObject data = load(c);
        JSONArray items = data.optJSONArray("items");
        int firstDay = data.optInt("firstDay", 1);
        List<String> daily = new ArrayList<>(), weekly = new ArrayList<>(), longTerm = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject t = items.optJSONObject(i);
            if (t == null || isDone(t, today, firstDay)) continue;
            String list = t.optString("list"), text = t.optString("text");
            if ("daily".equals(list)) daily.add(text);
            else if ("weekly".equals(list)) weekly.add(text);
            else longTerm.add(text);
        }
        if (!daily.isEmpty() || !weekly.isEmpty() || !longTerm.isEmpty()) {
            post(c, daily, weekly, longTerm);
        }
        schedule(c);
    }

    private static boolean isDone(JSONObject t, LocalDate today, int firstDay) {
        String doneOn = t.isNull("doneOn") ? "" : t.optString("doneOn");
        if (doneOn.isEmpty()) return false;
        String list = t.optString("list");
        if ("long".equals(list)) return true;
        LocalDate done;
        try {
            done = LocalDate.parse(doneOn);
        } catch (RuntimeException e) {
            return false;
        }
        boolean samePeriod = "daily".equals(list)
                ? done.equals(today)
                : weekStart(done, firstDay).equals(weekStart(today, firstDay));
        // A finished one-off is about to be removed, so it never counts as still to do.
        boolean repeats = t.optBoolean("repeat", true);
        return samePeriod || !repeats;
    }

    private static LocalDate weekStart(LocalDate d, int firstDay) {
        int dow = d.getDayOfWeek().getValue() % 7; // 0 is Sunday, as in JavaScript
        return d.minusDays((dow - firstDay + 7) % 7);
    }

    private static void section(StringBuilder b, String title, List<String> tasks) {
        if (tasks.isEmpty()) return;
        if (b.length() > 0) b.append("\n\n");
        b.append(title);
        for (int i = 0; i < Math.min(tasks.size(), MAX_PER_LIST); i++) b.append("\n• ").append(tasks.get(i));
        if (tasks.size() > MAX_PER_LIST) b.append("\n+ ").append(tasks.size() - MAX_PER_LIST).append(" more");
    }

    private static void post(Context c, List<String> daily, List<String> weekly, List<String> longTerm) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Daily to do list", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(ch);

        StringBuilder body = new StringBuilder();
        section(body, "Daily", daily);
        section(body, "Weekly", weekly);
        section(body, "Long term", longTerm);
        String summary = daily.size() + " daily · " + weekly.size() + " weekly · " + longTerm.size() + " long term";

        Intent open = new Intent(c, MainActivity.class)
                .putExtra(MainActivity.ROUTE, "todo")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        nm.notify(NOTIFICATION, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_prayer)
                .setContentTitle("Your to do list")
                .setContentText(summary)
                .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 20, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build());
    }
}
