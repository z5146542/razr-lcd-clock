package dev.lcdclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The timer reached zero: ring, and show STOP on the cover screen if the phone is closed. */
public class TimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (TimerController.state(context) != TimerController.State.RUNNING) {
            return;
        }
        TimerController.onFinished(context);
        context.startForegroundService(new Intent(context, RingService.class)
                .putExtra(RingService.EXTRA_KIND, RingService.KIND_TIMER));
        if (CoverScreen.isPhoneClosed(context)) {
            CoverScreen.open(context, CoverActivity.PAGE_CLOCK);
        }
    }
}
