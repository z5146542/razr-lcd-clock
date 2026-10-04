package dev.lcdclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms the alarm and a running timer after a reboot, a clock change or an app update. */
public class RescheduleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AlarmController.reschedule(context);
        TimerController.reschedule(context);
    }
}
