package app.personal.my;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android forgets alarms on restart, so the next one is registered again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Alarms.scheduleNext(c);
        Digest.schedule(c);
        LockScreen.sync(c);
    }
}
