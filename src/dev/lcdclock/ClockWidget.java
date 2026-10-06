package dev.lcdclock;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.widget.RemoteViews;

/**
 * The 4x2 home screen widget: the clock face as on the outside screen (time, AM/PM, alarm bell,
 * date and battery), without the padlock, as a picture LcdClockView draws. A widget can't run the
 * face itself, so the picture is redrawn each minute, but only while the inside screen is on (the
 * minute tick and screen-on reach the receiver ButtonService registers): nothing runs for it while
 * the phone is closed or its screen is off. Tapping it opens the LCD Clock app.
 */
public class ClockWidget extends AppWidgetProvider {
    /** Largest picture drawn, in pixels across, however big the widget is. */
    private static final int MAX_WIDTH_PX = 1200;

    private static Context app;

    private static final BroadcastReceiver ticker = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (insideScreenOn(context)) {
                update(context);
            }
        }
    };

    /** Starts redrawing on the minute tick and screen-on; ButtonService runs it while it's on. */
    static void start(Context context) {
        if (app != null) {
            return;
        }
        app = context.getApplicationContext();
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        app.registerReceiver(ticker, filter, Context.RECEIVER_NOT_EXPORTED);
        update(app);
    }

    static void stop() {
        if (app != null) {
            app.unregisterReceiver(ticker);
            app = null;
        }
    }

    private static boolean insideScreenOn(Context context) {
        Display inner = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        return inner != null && inner.getState() == Display.STATE_ON;
    }

    /** Redraws every LCD Clock widget at its size. */
    static void update(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, ClockWidget.class))) {
            update(context, manager, id);
        }
    }

    private static void update(Context context, AppWidgetManager manager, int id) {
        // Portrait sizes: the minimum width and the maximum height, in dp.
        Bundle options = manager.getAppWidgetOptions(id);
        float density = context.getResources().getDisplayMetrics().density;
        int widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (widthDp <= 0 || heightDp <= 0) {
            widthDp = 300;
            heightDp = 150;
        }
        float scale = Math.min(1f, MAX_WIDTH_PX / (widthDp * density));
        int w = Math.max(1, Math.round(widthDp * density * scale));
        int h = Math.max(1, Math.round(heightDp * density * scale));
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.clock_widget);
        views.setImageViewBitmap(R.id.clock_widget_face, LcdClockView.widget(context, w, h));
        views.setOnClickPendingIntent(R.id.clock_widget_face, PendingIntent.getActivity(context, 0,
                new Intent(context, PreviewActivity.class), PendingIntent.FLAG_IMMUTABLE));
        try {
            manager.updateAppWidget(id, views);
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "widget " + id + ": can't update", e);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            update(context, manager, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        update(context, manager, id); // resized
    }
}
