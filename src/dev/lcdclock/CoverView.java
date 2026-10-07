package dev.lcdclock;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cover-screen TIMER or ALARM page (one instance each), drawn in the clock face's LCD
 * style. The alarm is LCD Clock's own (AlarmController); tapping its bell turns it on or off.
 * Each page shows its own ringing: ALARM flashes the alarm time with SNOOZE and STOP, TIMER
 * flashes zero with STOP, and stopping one leaves the other ringing. Laid out for the
 * 368x194 outside screen; on any other shape it uses the largest box of that shape.
 */
final class CoverView extends View {
    private static final int NONE = -1;
    private static final int FIELD = 3; // FIELD + 0..2: hours, minutes, seconds
    private static final int MINUS = 7;
    private static final int PLUS = 8;
    private static final int START = 9;
    private static final int PAUSE = 10;
    private static final int GO = 11;
    private static final int RESET = 12;
    private static final int STOP = 13;
    private static final int SET = 14;
    private static final int BELL = 15;
    private static final int SNOOZE = 16;

    private static final int GREY = 0xFF7A7A7A;
    private static final int OUTLINE = 0xFFB4B4B4;
    private static final long TICK_MS = 250;
    private static final long REPEAT_DELAY_MS = 400;
    private static final long REPEAT_EVERY_MS = 90;
    /** AM/PM above the bell, after the alarm's minutes, in digit heights (as on the face). */
    private static final float COLUMN_GAP = 0.25f;
    private static final float AMPM_H = 0.28f;
    private static final float BELL_H = 0.32f;

    private final SegmentPainter seg;
    /** This instance is the ALARM tab; otherwise TIMER. */
    private final boolean alarmTab;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Touch targets laid out by the last onDraw. */
    private final Map<Integer, RectF> hits = new LinkedHashMap<>();

    private int timerField = 1;
    private int alarmField = 0;
    private int pressed = NONE;
    /** The held − or + has started repeating, so letting go doesn't step again. */
    private boolean repeated;
    private String alarmMessage;
    private long alarmMessageUntil;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (pressed == MINUS || pressed == PLUS) {
                step(pressed == PLUS ? 1 : -1);
                repeated = true;
                handler.postDelayed(this, REPEAT_EVERY_MS);
            }
        }
    };

    CoverView(Context context, boolean alarmTab) {
        super(context);
        this.alarmTab = alarmTab;
        seg = new SegmentPainter(context);
        outline.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        hits.clear();
        Context context = getContext();
        int color = Prefs.color(context);
        TimerController.State timer = TimerController.state(context);
        boolean alarmRinging = alarmTab && AlarmController.state(context) == AlarmController.State.RINGING;

        // Largest 368:194 box, centred.
        float bw = getWidth();
        float bh = getHeight();
        if (bw / bh < 368f / 194f) {
            bh = bw * 194f / 368f;
        } else {
            bw = bh * 368f / 194f;
        }
        float bx = (getWidth() - bw) / 2;
        float by = (getHeight() - bh) / 2;
        float pad = bh * 0.05f;

        // The menu row above (tabs, or ALARM / TIME UP while ringing) is CoverTabs'.
        float tabBase = by + pad + bh * CoverTabs.TAB_H;

        float btnH = bh * 0.25f;
        float btnTop = by + bh - pad - btnH;
        float midTop = tabBase + pad;
        float midH = btnTop - pad * 0.8f - midTop;
        if (alarmRinging) {
            drawAlarm(canvas, color, bx, bw, pad, midTop, midH, true);
            buttons(canvas, color, bx, bw, pad, btnTop, btnH, new int[] {SNOOZE, STOP});
        } else if (alarmTab) {
            drawAlarm(canvas, color, bx, bw, pad, midTop, midH, false);
            buttons(canvas, color, bx, bw, pad, btnTop, btnH, new int[] {MINUS, PLUS, SET});
        } else {
            drawTimer(canvas, color, timer, bx, bw, pad, midTop, midH);
            int[] row;
            switch (timer) {
                case RUNNING:
                    row = new int[] {PAUSE, RESET};
                    break;
                case PAUSED:
                    row = new int[] {GO, RESET};
                    break;
                case RINGING:
                    row = new int[] {STOP};
                    break;
                default:
                    row = new int[] {MINUS, PLUS, START};
            }
            buttons(canvas, color, bx, bw, pad, btnTop, btnH, row);
        }
    }

    private void drawTimer(Canvas canvas, int color, TimerController.State state, float bx, float bw, float pad,
            float midTop, float midH) {
        boolean idle = state == TimerController.State.IDLE;
        long millis = TimerController.remaining(getContext());
        long secs = idle ? millis / 1000 : (millis + 999) / 1000;
        int[] values = {(int) (secs / 3600), (int) (secs / 60 % 60), (int) (secs % 60)};
        boolean lit = state != TimerController.State.RINGING || System.currentTimeMillis() / 400 % 2 == 0;

        float digitH = digitHeight(bw, pad, midH);
        float rowW = digitH * SegmentPainter.ROW_HHMMSS;
        float left = bx + (bw - rowW) / 2;
        float top = midTop + (midH - digitH) / 2 - digitH * 0.06f;
        float pairW = digitH * (2 * SegmentPainter.DIGIT_W + SegmentPainter.DIGIT_GAP);

        SegmentPainter.italic(canvas, top + digitH / 2);
        float x = left;
        for (int i = 0; i < 3; i++) {
            float start = x;
            x = lit ? seg.pair(canvas, x, top, digitH, values[i], false, color) : blankPair(canvas, x, top, digitH, color);
            if (idle && i == timerField) {
                underline(canvas, start, start + pairW, top, digitH, color);
            }
            if (idle) {
                hits.put(FIELD + i, new RectF(start - digitH * 0.1f, top - digitH * 0.2f,
                        start + pairW + digitH * 0.1f, top + digitH * 1.2f));
            }
            if (i < 2) {
                x = seg.colon(canvas, x, top, digitH, true, color);
            }
        }
        canvas.restore();
    }

    /** Digit height for both tabs: what fits "HH:MM:SS" across the screen. */
    private static float digitHeight(float bw, float pad, float midH) {
        return Math.min(midH * 0.82f, (bw - 2 * pad) / (SegmentPainter.ROW_HHMMSS + SegmentPainter.SLANT));
    }

    /** The alarm time with AM/PM above a bell; ringing flashes the time and locks the controls. */
    private void drawAlarm(Canvas canvas, int color, float bx, float bw, float pad, float midTop, float midH,
            boolean ringing) {
        Context context = getContext();
        boolean twelveHour = !DateFormat.is24HourFormat(context);
        int hour = AlarmController.hour(context);
        int minute = AlarmController.minute(context);
        int shownHour = twelveHour ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
        boolean lit = !ringing || System.currentTimeMillis() / 400 % 2 == 0;

        // Same digit size as the timer; "HH:MM" plus the column is narrower, so it always fits.
        float digitH = digitHeight(bw, pad, midH);
        float ampmH = digitH * AMPM_H;
        float bellH = digitH * BELL_H;
        float ampmW = 0;
        if (twelveHour) {
            seg.textStyle(SegmentPainter.seg14(), ampmH);
            ampmW = seg.textWidth("AM");
        }
        float columnW = Math.max(ampmW, bellH);
        float rowW = digitH * SegmentPainter.ROW_HHMM;
        float totalW = rowW + digitH * COLUMN_GAP + columnW;
        float left = bx + (bw - totalW) / 2;
        float top = midTop + (midH - digitH) / 2 - digitH * 0.06f;
        float pairW = digitH * (2 * SegmentPainter.DIGIT_W + SegmentPainter.DIGIT_GAP);
        float minuteX = left + pairW + digitH * SegmentPainter.COLON_W;

        SegmentPainter.italic(canvas, top + digitH / 2);
        float x;
        if (lit) {
            x = seg.pair(canvas, left, top, digitH, shownHour, twelveHour, color);
            x = seg.colon(canvas, x, top, digitH, true, color);
            x = seg.pair(canvas, x, top, digitH, minute, false, color);
        } else {
            x = blankPair(canvas, left, top, digitH, color);
            x = seg.colon(canvas, x, top, digitH, false, color);
            x = blankPair(canvas, x, top, digitH, color);
        }
        float columnX = x + digitH * COLUMN_GAP;
        if (twelveHour) {
            seg.textStyle(SegmentPainter.seg14(), ampmH);
            String ampm = hour < 12 ? "AM" : "PM";
            seg.cell(canvas, SegmentPainter.ghost14(ampm), ampm, columnX, top + ampmH, color);
        }
        seg.bell(canvas, columnX + bellH / 2, top + digitH, bellH, AlarmController.isOn(context), color);
        if (!ringing) {
            underline(canvas, alarmField == 0 ? left : minuteX, (alarmField == 0 ? left : minuteX) + pairW, top,
                    digitH, color);
        }
        canvas.restore();

        if (!ringing) {
            hits.put(FIELD, new RectF(left - digitH * 0.1f, top - digitH * 0.2f, left + pairW + digitH * 0.1f,
                    top + digitH * 1.2f));
            hits.put(FIELD + 1, new RectF(minuteX - digitH * 0.1f, top - digitH * 0.2f,
                    minuteX + pairW + digitH * 0.1f, top + digitH * 1.2f));
            // The whole column toggles the alarm. AM/PM only shows the half of the day; stepping the
            // hour past 12 changes it, so a tap meant for the bell can't flip the alarm to the evening.
            hits.put(BELL, new RectF(columnX - digitH * COLUMN_GAP / 2, top - digitH * 0.2f,
                    Math.min(bx + bw, columnX + columnW + pad * 2), top + digitH * 1.2f));
        }
    }

    private float blankPair(Canvas canvas, float x, float y, float h, int color) {
        seg.digit(canvas, x, y, h, SegmentPainter.BLANK, color);
        x += h * (SegmentPainter.DIGIT_W + SegmentPainter.DIGIT_GAP);
        seg.digit(canvas, x, y, h, SegmentPainter.BLANK, color);
        return x + h * SegmentPainter.DIGIT_W;
    }

    /** Marks the field the − and + buttons change. */
    private void underline(Canvas canvas, float x0, float x1, float top, float digitH, int color) {
        fill.setColor(color);
        float y = top + digitH * 1.1f;
        canvas.drawRect(x0, y, x1, y + digitH * 0.05f, fill);
    }

    /** A row of outlined buttons; − and + are narrow, the rest share the remaining width. */
    private void buttons(Canvas canvas, int color, float bx, float bw, float pad, float top, float h, int[] ids) {
        float weightSum = 0;
        for (int id : ids) {
            weightSum += weight(id);
        }
        float x = bx + pad;
        float unit = (bw - 2 * pad - pad * (ids.length - 1)) / weightSum;
        float stroke = Math.max(1.5f, h * 0.045f);
        outline.setStrokeWidth(stroke);
        for (int id : ids) {
            RectF r = new RectF(x, top, x + unit * weight(id), top + h);
            boolean primary = id == START || id == GO || id == SET || id == STOP;
            float radius = h * 0.28f;
            if (pressed == id) {
                fill.setColor(SegmentPainter.dim(color));
                canvas.drawRoundRect(r, radius, radius, fill);
            }
            outline.setColor(primary ? color : OUTLINE);
            canvas.drawRoundRect(r.left + stroke / 2, r.top + stroke / 2, r.right - stroke / 2, r.bottom - stroke / 2,
                    radius, radius, outline);
            String text = id == SET && alarmMessage != null && System.currentTimeMillis() < alarmMessageUntil
                    ? alarmMessage : buttonText(id);
            float glyphH = h * 0.34f;
            SegmentPainter.italic(canvas, r.centerY());
            if (id == MINUS || id == PLUS) {
                // Plain bars: a lone 14-segment "-" or "+" is lost among its unlit segments.
                float half = glyphH * 0.55f;
                float t = glyphH * 0.16f;
                fill.setColor(Color.WHITE);
                canvas.drawRect(r.centerX() - half, r.centerY() - t / 2, r.centerX() + half, r.centerY() + t / 2, fill);
                if (id == PLUS) {
                    canvas.drawRect(r.centerX() - t / 2, r.centerY() - half, r.centerX() + t / 2, r.centerY() + half, fill);
                }
            } else {
                seg.textStyle(SegmentPainter.seg14(), glyphH);
                float textW = seg.textWidth(text);
                seg.cell(canvas, SegmentPainter.ghost14(text), text, r.centerX() - textW / 2, r.centerY() + glyphH / 2,
                        primary ? color : Color.WHITE);
            }
            canvas.restore();
            hits.put(id, r);
            x = r.right + pad;
        }
    }

    private static float weight(int id) {
        return id == MINUS || id == PLUS ? 1 : 2;
    }

    private static String buttonText(int id) {
        switch (id) {
            case MINUS: return "-";
            case PLUS: return "+";
            case START: return "START";
            case PAUSE: return "PAUSE";
            case GO: return "GO";
            case RESET: return "RESET";
            case STOP: return "STOP";
            case SET: return "SET";
            case SNOOZE: return "SNOOZE";
            default: return "";
        }
    }

    // ---- touch ----

    private int hit(float x, float y) {
        for (Map.Entry<Integer, RectF> e : hits.entrySet()) {
            if (e.getValue().contains(x, y)) {
                return e.getKey();
            }
        }
        return NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(x, y);
                if (pressed == MINUS || pressed == PLUS) {
                    // Steps on release (or once held), so a page swipe starting here changes nothing.
                    repeated = false;
                    handler.postDelayed(repeat, REPEAT_DELAY_MS);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed != NONE && hit(x, y) != pressed) {
                    handler.removeCallbacks(repeat);
                    pressed = NONE;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(repeat);
                int control = pressed;
                pressed = NONE;
                if ((control == MINUS || control == PLUS) && !repeated && hit(x, y) == control) {
                    step(control == PLUS ? 1 : -1);
                } else if (control != NONE && control != MINUS && control != PLUS && hit(x, y) == control) {
                    perform(control);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(repeat);
                pressed = NONE;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void perform(int control) {
        Context context = getContext();
        Log.d(Binders.TAG, "menu: " + control + " alarm=" + AlarmController.state(context) + " at "
                + AlarmController.hour(context) + ":" + AlarmController.minute(context));
        switch (control) {
            case FIELD:
            case FIELD + 1:
            case FIELD + 2:
                if (alarmTab) {
                    alarmField = control - FIELD;
                } else {
                    timerField = control - FIELD;
                }
                break;
            case BELL:
                if (AlarmController.isOn(context)) {
                    AlarmController.turnOff(context);
                } else {
                    AlarmController.turnOn(context);
                }
                break;
            case SNOOZE:
                context.startService(new Intent(context, RingService.class).setAction(RingService.ACTION_SNOOZE));
                break;
            case START:
            case GO:
                TimerController.start(context);
                break;
            case PAUSE:
                TimerController.pause(context);
                break;
            case RESET:
                TimerController.reset(context);
                break;
            case STOP:
                if (alarmTab) {
                    context.startService(new Intent(context, RingService.class).setAction(RingService.ACTION_STOP)
                            .putExtra(RingService.EXTRA_KIND, RingService.KIND_ALARM));
                } else {
                    TimerController.stopRinging(context);
                }
                break;
            case SET:
                AlarmController.turnOn(context);
                alarmMessage = "DONE";
                alarmMessageUntil = System.currentTimeMillis() + 2500;
                break;
            default:
                break;
        }
    }

    /** − / + on the selected field; values wrap within the field. */
    private void step(int delta) {
        Context context = getContext();
        if (alarmTab) {
            int hour = AlarmController.hour(context);
            int minute = AlarmController.minute(context);
            if (alarmField == 0) {
                hour = Math.floorMod(hour + delta, 24);
            } else {
                minute = Math.floorMod(minute + delta, 60);
            }
            AlarmController.setTime(context, hour, minute);
        } else if (TimerController.state(context) == TimerController.State.IDLE) {
            long secs = TimerController.duration(context) / 1000;
            int h = (int) (secs / 3600);
            int m = (int) (secs / 60 % 60);
            int s = (int) (secs % 60);
            if (timerField == 0) {
                h = Math.floorMod(h + delta, 24);
            } else if (timerField == 1) {
                m = Math.floorMod(m + delta, 60);
            } else {
                s = Math.floorMod(s + delta, 60);
            }
            TimerController.setDuration(context, (h * 3600L + m * 60L + s) * 1000);
        }
        invalidate();
    }

}
