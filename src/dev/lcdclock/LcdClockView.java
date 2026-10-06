package dev.lcdclock;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * The clock face: an italic LCD-style "HH:MM" with the unlit segments showing, and a
 * weekday, day.month and battery line underneath (a white battery filled in a colour from red to
 * green by charge, light blue while battery saver is on; a bolt while plugged in, else a plus with
 * battery saver). After the minutes, a column of AM/PM, a
 * padlock (lit while the phone is locked, unlit once it's unlocked) and the alarm bell.
 */
final class LcdClockView extends View {
    static final String TEMPLATE_ID = "dev.lcdclock.digits";
    /** The column after the minutes (AM/PM above, alarm bell below): gap and sizes, in digit heights. */
    private static final float COLUMN_GAP = 0.18f;
    private static final float AMPM_H = 0.28f;
    private static final float BELL_H = 0.32f;
    /** The padlock between AM/PM and the bell: height and centre, in digit heights from the top. */
    private static final float LOCK_H = 0.25f;
    private static final float LOCK_Y = 0.485f;

    private final boolean preview;
    private final SegmentPainter seg;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int color = Palette.PRESETS[0];
    private boolean visibleToUser = true;
    private boolean alarmSet;

    /** Redraws on each second boundary so the colon blinks. */
    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            invalidate();
            scheduleBlink();
        }
    };

    LcdClockView(Context context, boolean preview) {
        super(context);
        this.preview = preview;
        seg = new SegmentPainter(context);
        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        alarmSet = preview || context.getSystemService(AlarmManager.class).getNextAlarmClock() != null;
    }

    /** The system's next alarm changed (set, cleared or rung). */
    void onAlarmChanged() {
        alarmSet = getContext().getSystemService(AlarmManager.class).getNextAlarmClock() != null;
        if (visibleToUser) {
            invalidate();
        }
    }

    void setColor(int color) {
        if (this.color != color) {
            this.color = color;
            invalidate();
        }
    }

    void setVisibleToUser(boolean visible) {
        visibleToUser = visible;
        if (visible) {
            invalidate();
        }
        scheduleBlink();
    }

    void onTimeChanged() {
        if (visibleToUser) {
            invalidate();
        }
    }

    private void scheduleBlink() {
        removeCallbacks(blink);
        if (visibleToUser && isAttachedToWindow()) {
            postDelayed(blink, 1000 - System.currentTimeMillis() % 1000);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleBlink();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(blink);
        super.onDetachedFromWindow();
    }

    /** Picker thumbnail: the face at 12:00 on a rounded black card, like Motorola's own. */
    static Bitmap thumbnail(Context context, int widthPx, int heightPx) {
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
        black.setColor(Color.BLACK);
        float radius = widthPx * 11.12f / 146f;
        canvas.drawRoundRect(0, 0, widthPx, heightPx, radius, radius, black);
        new LcdClockView(context, true).drawFace(canvas, widthPx, heightPx, 12, false, false, 0, true,
                Calendar.getInstance());
        return bitmap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int minute = now.get(Calendar.MINUTE);
        boolean twelveHour = !DateFormat.is24HourFormat(getContext());
        int displayHour = twelveHour ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
        boolean colonOn = now.getTimeInMillis() / 1000 % 2 == 0;

        if (!preview) {
            // Nudge everything around a 3x3 grid, one step per minute, against OLED burn-in.
            int step = (hour * 60 + minute) % 9;
            float unit = Math.max(1f, getHeight() / 100f);
            canvas.translate((step % 3 - 1) * unit, (step / 3 - 1) * unit);
        }
        drawFace(canvas, getWidth(), getHeight(), displayHour, twelveHour, hour >= 12, minute, colonOn, now);
    }

    /**
     * In 12-hour mode the leading hour digit is blank, like a real alarm clock (" 9:41"). A
     * column after the minutes holds AM/PM (12-hour mode), the padlock and the alarm bell.
     */
    private void drawFace(Canvas canvas, int w, int h, int hour, boolean twelveHour, boolean pm, int minute,
            boolean colonOn, Calendar now) {
        // Row width in digit heights, including the AM/PM and bell column.
        seg.textStyle(SegmentPainter.seg14(), 100);
        float ampmUnits = twelveHour ? AMPM_H * seg.textWidth("AM") / 100 : 0;
        float rowUnits = SegmentPainter.ROW_HHMM + COLUMN_GAP + Math.max(ampmUnits, BELL_H);
        // Digit height half the screen, or less if the row would get too wide.
        float digitH = Math.min(h * 0.5f, w * 0.88f / rowUnits);
        float rowW = digitH * rowUnits;
        float infoH = h * 0.11f;
        float gap = h * 0.09f;
        float top = (h - digitH - gap - infoH) / 2;
        float left = (w - rowW) / 2;

        // Italic: lean the whole face (time and date row) around its vertical centre, so it
        // stays centred and the date row's edges follow the digits' slant.
        SegmentPainter.italic(canvas, top + (digitH + gap + infoH) / 2);
        float x = seg.pair(canvas, left, top, digitH, hour, twelveHour, color);
        x = seg.colon(canvas, x, top, digitH, colonOn, color);
        x = seg.pair(canvas, x, top, digitH, minute, false, color) + digitH * COLUMN_GAP;
        if (twelveHour) {
            float ampmH = digitH * AMPM_H;
            seg.textStyle(SegmentPainter.seg14(), ampmH);
            seg.cell(canvas, "~~", pm ? "PM" : "AM", x, top + ampmH, color);
        }
        float columnX = x + digitH * BELL_H / 2;
        float lockH = digitH * LOCK_H;
        boolean locked = preview || Lock.locked(getContext());
        Lock.drawBadge(canvas, columnX - Lock.badgeWidth(lockH) / 2, top + digitH * LOCK_Y - lockH / 2, lockH,
                locked ? color : SegmentPainter.dim(color));
        seg.bell(canvas, columnX, top + digitH, digitH * BELL_H, alarmSet, color);
        drawInfo(canvas, left, left + rowW, top + digitH + gap + infoH, infoH, now);
        canvas.restore();
    }

    /** "SUN 04.10" and "64%" in white, with a battery icon coloured and filled by charge. */
    private void drawInfo(Canvas canvas, float left, float right, float baseline, float glyphH, Calendar now) {
        // Weekday needs letters, so it's 14-segment; the numbers stay 7-segment to match the time.
        String weekday = new SimpleDateFormat("EEE", Locale.ENGLISH).format(now.getTime()).toUpperCase(Locale.ENGLISH);
        String day = String.format(Locale.ROOT, "%02d", now.get(Calendar.DAY_OF_MONTH));
        String month = String.format(Locale.ROOT, "%02d", now.get(Calendar.MONTH) + 1);
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "dM");
        String date = pattern.indexOf('M') < pattern.indexOf('d') ? month + "." + day : day + "." + month;

        int white = Color.WHITE;
        seg.textStyle(SegmentPainter.seg14(), glyphH);
        float x = seg.cell(canvas, "~~~", weekday, left, baseline, white);
        seg.textStyle(SegmentPainter.seg7(), glyphH);
        x += seg.textWidth("  ");
        seg.cell(canvas, "88.88", date, x, baseline, white);

        BatteryManager battery = getContext().getSystemService(BatteryManager.class);
        int level = Math.max(0, Math.min(100, battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)));
        boolean charging = pluggedIn(getContext());
        int batteryColor = batteryColor(level);

        seg.textStyle(SegmentPainter.seg14(), glyphH);
        float percentX = right - seg.textWidth("%");
        seg.cell(canvas, "~", "%", percentX, baseline, white);
        seg.textStyle(SegmentPainter.seg7(), glyphH);
        String digits = level == 100 ? "100" : (level < 10 ? "!!" : "!") + level;
        float digitsX = percentX - seg.textWidth("888");
        seg.cell(canvas, "888", digits, digitsX, baseline, white);

        float iconW = glyphH * 1.9f;
        float iconX = digitsX - glyphH * 0.5f - iconW;
        boolean saver = !preview && QuickControls.saverOn(getContext());
        drawBattery(canvas, fill, outline, path, iconX, baseline - glyphH, iconW, glyphH, level, charging, saver,
                batteryColor);
    }

    /** The battery icon's colour: red when empty through yellow to green when full. */
    static int batteryColor(int level) {
        return Color.HSVToColor(new float[] {1.2f * level, 1f, 1f});
    }

    /**
     * Plugged in, as the status bar shows a bolt for: not BatteryManager.isCharging, which is false
     * while plugged in when Motorola's battery care holds charging back.
     */
    static boolean pluggedIn(Context context) {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** The battery's fill while battery saver is on, as Android shows it. */
    static final int SAVER_BLUE = 0xFF6EC6FF;

    /**
     * Battery outline (white) with a terminal nub, filled to the level in `color`, or light blue
     * while battery saver is switched on. On it, a bolt while plugged in, else a plus while battery
     * saver is on (so a phone charging with saver on shows both: light blue and the bolt). The
     * bolt or plus is cut out of the filled part and drawn in the empty part, so both stay
     * readable. Also the quick controls panel's battery.
     */
    static void drawBattery(Canvas canvas, Paint fill, Paint outline, Path path, float x, float y, float w, float h,
            int level, boolean charging, boolean saver, int color) {
        int ink = saver ? SAVER_BLUE : color;
        float sw = Math.max(1.5f, h * 0.12f);
        float nubW = h * 0.16f;
        float bodyW = w - nubW;
        outline.setColor(Color.WHITE);
        outline.setStrokeWidth(sw);
        canvas.drawRoundRect(x + sw / 2, y + sw / 2, x + bodyW - sw / 2, y + h - sw / 2, sw, sw, outline);
        fill.setColor(Color.WHITE);
        canvas.drawRect(x + bodyW, y + h * 0.3f, x + w, y + h * 0.7f, fill);

        float inset = sw * 1.75f;
        float ix = x + inset;
        float iy = y + inset;
        float iw = bodyW - 2 * inset;
        float ih = h - 2 * inset;
        float fillRight = ix + iw * level / 100f;
        fill.setColor(ink);
        canvas.drawRect(ix, iy, fillRight, iy + ih, fill);
        path.reset();
        if (charging) {
            path.moveTo(ix + iw * 0.58f, iy);
            path.lineTo(ix + iw * 0.22f, iy + ih * 0.58f);
            path.lineTo(ix + iw * 0.47f, iy + ih * 0.58f);
            path.lineTo(ix + iw * 0.40f, iy + ih);
            path.lineTo(ix + iw * 0.78f, iy + ih * 0.40f);
            path.lineTo(ix + iw * 0.53f, iy + ih * 0.40f);
            path.close();
        } else if (saver) {
            float cx = ix + iw / 2;
            float cy = iy + ih / 2;
            float arm = ih * 0.42f;
            float t = ih * 0.3f;
            path.addRect(cx - arm, cy - t / 2, cx + arm, cy + t / 2, Path.Direction.CW);
            path.addRect(cx - t / 2, cy - arm, cx + t / 2, cy + arm, Path.Direction.CW);
        } else {
            return;
        }
        canvas.save();
        canvas.clipRect(ix, iy, fillRight, iy + ih);
        fill.setColor(Color.BLACK);
        canvas.drawPath(path, fill);
        canvas.restore();
        canvas.save();
        canvas.clipRect(fillRight, iy, ix + iw, iy + ih);
        fill.setColor(ink);
        canvas.drawPath(path, fill);
        canvas.restore();
    }
}
