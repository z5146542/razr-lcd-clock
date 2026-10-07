package dev.lcdclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The alarm or a snooze went off: ring, and show SNOOZE/STOP on the cover screen if closed. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AlarmController.isOn(context)) {
            return;
        }
        AlarmController.onFired(context);
        context.startForegroundService(new Intent(context, RingService.class)
                .putExtra(RingService.EXTRA_KIND, RingService.KIND_ALARM));
        if (CoverScreen.isPhoneClosed(context)) {
            CoverScreen.open(context, CoverActivity.PAGE_ALARM);
        }
    }
}
