package app.personal.my;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android calls this at prayer time, whether or not the app is running. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String key = i.getStringExtra(Alarms.KEY);
        if (key == null) return;
        if (Digest.KEY.equals(key)) {
            Digest.fire(c);
            return;
        }
        Alarms.fire(c, key, i.getStringExtra(Alarms.NAME), i.getLongExtra(Alarms.AT, 0));
        if (!"test".equals(key)) Alarms.scheduleNext(c);
    }
}
