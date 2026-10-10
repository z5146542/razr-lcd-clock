package dev.lcdclock;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.Locale;

/**
 * The "LCD Clock cover button" home screen widget (2x1): where the accessibility service stands
 * (ButtonPause: ON, PAUSED until the phone closes, or OFF) in segment letters on a black card,
 * and a tap switches it (ButtonPause.toggle). Without notification access or "write secure
 * settings" nothing can switch it, so a tap opens the app's Setup list instead. Redrawn by
 * ButtonService whenever the service comes on or goes off, and by its own taps.
 */
public class ButtonWidget extends AppWidgetProvider {
    private static final String TITLE = "COVER BUTTON";
    /** ON is red: while it's on, banking apps object. */
    private static final int ON_RED = 0xFFFF3B30;

    /** The widget's tap; not exported, so only the widget's own PendingIntent reaches it. */
    public static class Toggle extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            ButtonPause.toggle(context);
            update(context);
        }
    }

    /** Redraws every cover button widget. */
    static void update(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context, ButtonWidget.class))) {
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
            widthDp = 140;
            heightDp = 70;
        }
        String state = ButtonPause.state(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.clock_widget);
        views.setImageViewBitmap(R.id.clock_widget_face, picture(context, Math.round(widthDp * density),
                Math.round(heightDp * density), state));
        boolean switchable = ButtonPause.manages(context);
        views.setContentDescription(R.id.clock_widget_face, context.getString(
                "on".equals(state) ? R.string.button_widget_on : "paused".equals(state) ? R.string.button_widget_paused
                        : R.string.button_widget_off)
                + " " + context.getString(switchable ? R.string.button_widget_tap : R.string.button_widget_setup));
        views.setOnClickPendingIntent(R.id.clock_widget_face, switchable
                ? PendingIntent.getBroadcast(context, 0, new Intent(context, Toggle.class), PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getActivity(context, 0, new Intent(context, PreviewActivity.class),
                        PendingIntent.FLAG_IMMUTABLE));
        try {
            manager.updateAppWidget(id, views);
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "cover button widget " + id + ": can't update", e);
        }
    }

    /** "COVER BUTTON" in white over the state: ON in red, PAUSED light grey, OFF grey. */
    private static Bitmap picture(Context context, int w, int h, String state) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
        black.setColor(Color.BLACK);
        float radius = Math.min(w, h) * 0.18f;
        canvas.drawRoundRect(0, 0, w, h, radius, radius, black);

        String big = state.toUpperCase(Locale.ROOT);
        int color = "on".equals(state) ? ON_RED : "paused".equals(state) ? Lock.BADGE : CoverPage.GREY;
        SegmentPainter seg = new SegmentPainter(context);
        // Sizes from the widths at 100 px, so both lines fit with a margin.
        seg.textStyle(SegmentPainter.seg14(), 100);
        float titleH = Math.min(h * 0.14f, w * 0.8f * 100 / seg.textWidth(SegmentPainter.ghost14(TITLE)));
        float bigH = Math.min(h * 0.36f, w * 0.8f * 100 / seg.textWidth("~~~~~~"));
        float gap = h * 0.12f;
        float top = (h - titleH - gap - bigH) / 2;

        SegmentPainter.italic(canvas, h / 2f);
        seg.textStyle(SegmentPainter.seg14(), titleH);
        String titleGhost = SegmentPainter.ghost14(TITLE);
        seg.cell(canvas, titleGhost, TITLE, (w - seg.textWidth(titleGhost)) / 2, top + titleH, Color.WHITE);
        seg.textStyle(SegmentPainter.seg14(), bigH);
        String bigGhost = SegmentPainter.ghost14(big);
        seg.cell(canvas, bigGhost, big, (w - seg.textWidth(bigGhost)) / 2, top + titleH + gap + bigH, color);
        canvas.restore();
        return bitmap;
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
