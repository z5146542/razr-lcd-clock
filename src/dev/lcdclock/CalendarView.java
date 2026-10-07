package dev.lcdclock;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.MotionEvent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The cover screen's CALENDAR page: events from now to a week ahead from the phone's visible
 * calendars, two rows to the screen: a bar in the calendar's colour, the start time in segment
 * digits (lit while it's on), the title (scrolling when it's too long), and the day and place
 * underneath. Drag up or down to scroll.
 */
final class CalendarView extends CoverPage {
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long RELOAD_MS = 60_000;
    /** Events to the screen; everything in a row scales with its height. */
    private static final int ROWS = 2;
    private static final ExecutorService loader = Executors.newSingleThreadExecutor();

    private static final class Event {
        String title;
        long begin;
        long end;
        boolean allDay;
        int color;
        String place;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private List<Event> events = Collections.emptyList();
    private boolean loaded;
    /** Rows scrolled past, in px, and the drag that's moving them. */
    private float scroll;
    private float dragY;
    private float rowH;
    private float maxScroll;

    private final Runnable reload = new Runnable() {
        @Override
        public void run() {
            load();
            postDelayed(this, RELOAD_MS);
        }
    };

    CalendarView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(reload);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(reload);
        super.onDetachedFromWindow();
    }

    private boolean allowed() {
        return getContext().checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private void load() {
        if (!allowed()) {
            invalidate();
            return;
        }
        Context context = getContext().getApplicationContext();
        loader.execute(() -> {
            List<Event> found = query(context);
            main.post(() -> {
                events = found;
                loaded = true;
                invalidate();
            });
        });
    }

    private static List<Event> query(Context context) {
        long now = System.currentTimeMillis();
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, now - 24L * 60 * 60 * 1000); // all-day events of today start at midnight UTC
        ContentUris.appendId(uri, now + WEEK_MS);
        String[] columns = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_LOCATION};
        List<Event> events = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(uri.build(), columns,
                CalendarContract.Instances.VISIBLE + " = 1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext()) {
                Event e = new Event();
                e.title = c.getString(0) == null || c.getString(0).isEmpty() ? "(No title)" : c.getString(0);
                e.begin = c.getLong(1);
                e.end = c.getLong(2);
                e.allDay = c.getInt(3) != 0;
                e.color = c.getInt(4) | 0xFF000000;
                e.place = c.getString(5);
                if (e.allDay) {
                    // Stored as UTC midnights; read them as local dates.
                    e.begin = localMidnight(e.begin);
                    e.end = localMidnight(e.end);
                }
                if (e.end > now) {
                    events.add(e);
                }
            }
        } catch (RuntimeException ex) {
            Log.w(Binders.TAG, "can't read calendars", ex);
        }
        events.sort((a, b) -> Long.compare(a.begin, b.begin)); // all-day dates moved to local time
        return events;
    }

    private static long localMidnight(long utcMidnight) {
        Calendar utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(utcMidnight);
        Calendar local = Calendar.getInstance();
        local.clear();
        local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH));
        return local.getTimeInMillis();
    }

    @Override
    void drawPage(Canvas canvas, int color) {
        Context context = getContext();
        float left = bx + pad * 1.4f;
        float right = bx + bw - pad * 1.4f;
        float bottom = by + bh - pad;

        if (!allowed() || loaded && events.isEmpty()) {
            String note = context.getString(allowed() ? R.string.calendar_empty : R.string.calendar_needs_access);
            line(canvas, note, left, top + bh * 0.12f, bh * 0.085f, right - left, allowed() ? Color.WHITE : GREY);
            return;
        }

        rowH = (bottom - top) / ROWS;
        maxScroll = Math.max(0, events.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        long now = System.currentTimeMillis();
        boolean twelveHour = !DateFormat.is24HourFormat(context);
        float timeH = rowH * 0.27f;
        seg.textStyle(SegmentPainter.seg7(), timeH);
        float timeW = seg.textWidth("88:88");
        float timeX = left + rowH * 0.15f;
        float titleX = timeX + timeW + rowH * 0.18f;
        boolean scrolling = false;

        canvas.save();
        canvas.clipRect(bx, top, bx + bw, bottom);
        for (int i = 0; i < events.size(); i++) {
            float rowTop = top + i * rowH - scroll;
            if (rowTop > bottom || rowTop + rowH < top) {
                continue;
            }
            Event e = events.get(i);
            boolean on = e.begin <= now && now < e.end;
            // Calendar colour bar.
            fill.setColor(e.color);
            canvas.drawRect(left, rowTop + rowH * 0.12f, left + rowH * 0.05f, rowTop + rowH * 0.88f, fill);
            // Start time, or ALL for all day; lit while it's happening.
            float base = rowTop + rowH * 0.5f + timeH / 2;
            if (e.allDay) {
                label(canvas, "ALL", timeX, base, timeH, on ? color : Color.WHITE);
            } else {
                Calendar at = Calendar.getInstance();
                at.setTimeInMillis(e.begin);
                int hour = at.get(Calendar.HOUR_OF_DAY);
                int shown = twelveHour ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
                String time = String.format(Locale.ROOT, twelveHour && shown < 10 ? "!%d:%02d" : "%02d:%02d", shown,
                        at.get(Calendar.MINUTE));
                seg.textStyle(SegmentPainter.seg7(), timeH);
                SegmentPainter.italic(canvas, base - timeH / 2);
                seg.cell(canvas, "88:88", time, timeX, base, on ? color : Color.WHITE);
                canvas.restore();
            }
            // Title, then day and place.
            scrolling |= marquee(canvas, e.title, titleX, rowTop + rowH * 0.46f, rowH * 0.34f, right - titleX,
                    Color.WHITE);
            String day = dayLabel(e.begin, now, twelveHour && !e.allDay ? amPm(e.begin) : "");
            String sub = e.place == null || e.place.isEmpty() ? day : day + " · " + e.place;
            line(canvas, sub, titleX, rowTop + rowH * 0.84f, rowH * 0.27f, right - titleX, GREY);
        }
        canvas.restore();
        if (scrolling) {
            postInvalidateOnAnimation();
        }

        // Scroll position, when there's more than fits.
        if (maxScroll > 0) {
            float trackH = bottom - top;
            float thumbH = trackH * (bottom - top) / (events.size() * rowH);
            float thumbTop = top + (trackH - thumbH) * scroll / maxScroll;
            fill.setColor(SegmentPainter.dim(color));
            canvas.drawRect(bx + bw - pad * 0.6f, top, bx + bw - pad * 0.3f, bottom, fill);
            fill.setColor(color);
            canvas.drawRect(bx + bw - pad * 0.6f, thumbTop, bx + bw - pad * 0.3f, thumbTop + thumbH, fill);
        }
    }

    private static String amPm(long when) {
        Calendar at = Calendar.getInstance();
        at.setTimeInMillis(when);
        return at.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
    }

    /** "Today", "Tomorrow", or "Mon 6 Oct", with AM/PM in 12-hour mode. */
    private String dayLabel(long when, long now, String amPm) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(when);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        int days = (int) Math.round((startOfDay(day) - startOfDay(today)) / 86_400_000.0);
        String name;
        if (days <= 0) {
            name = getContext().getString(R.string.calendar_today);
        } else if (days == 1) {
            name = getContext().getString(R.string.calendar_tomorrow);
        } else {
            name = new SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"),
                    Locale.getDefault()).format(day.getTime());
        }
        return amPm.isEmpty() ? name : name + " " + amPm;
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** Vertical drags scroll the list; sideways ones are SwipeFrame's. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                scroll = Math.max(0, Math.min(maxScroll, scroll - (event.getY() - dragY)));
                dragY = event.getY();
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
