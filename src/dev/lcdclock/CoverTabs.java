package dev.lcdclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.format.DateFormat;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The cover screen's menu row, drawn once over whichever page is showing so it stays put while
 * pages slide underneath: an icon tab per page and the time of day at the right; hidden on our own
 * clock face page, which looks just like Motorola's. No X: swipe, or tap the face's clock tab. FACE, TIMER, ALARM, CALENDAR, WEATHER, RECORDER and
 * the plain MUSIC page have segment-style icons (clock, hourglass, bell, calendar, sun and cloud,
 * microphone with a red dot while recording, notes); HOME has the Home
 * Assistant app's icon (a house without it) and a player's MUSIC page its app's icon. The
 * current tab is lit (white, or the app icon at full strength) and underlined in the clock
 * colour. While an alarm or the timer rings, a flashing ALARM or TIME UP takes the clock's place
 * (and the row shows on every page, the face too), the tabs still work, and the ringing page's
 * own buttons end it. Touches below the row fall through to the page.
 */
final class CoverTabs extends View {
    /** Menu text height, in box heights; pages lay their content out below this row. */
    static final float TAB_H = 0.068f;
    /** Tab icon size, in box heights, and the space between icons, in icon sizes. */
    private static final float ICON_H = 0.085f;
    private static final float ICON_GAP = 0.6f;
    /** The gap before the clock, in text heights. */
    private static final float CLOCK_GAP = 0.8f;

    interface Listener {
        void onTab(String page);
    }

    private static final int GREY = 0xFF7A7A7A;
    private static final long TICK_MS = 500;

    private final SegmentPainter seg;
    private final Listener listener;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TabIcons icons;
    /** Touch targets laid out by the last onDraw: pages by name. */
    private final Map<String, RectF> hits = new LinkedHashMap<>();
    private List<String> pages = new ArrayList<>();
    private String current;
    private float rowBottom;
    private String pressed;

    /** Keeps the time of day current while the row is showing; idle while the screen is off. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (getWindowVisibility() == VISIBLE) {
                postDelayed(this, TICK_MS);
            }
        }
    };

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(tick);
        if (visibility == VISIBLE && isAttachedToWindow()) {
            post(tick);
        }
    }

    CoverTabs(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        seg = new SegmentPainter(context);
        icons = new TabIcons(context);
        fill.setStyle(Paint.Style.FILL);
    }

    /** The pages in the row as last shown, in order. */
    List<String> pages() {
        return new ArrayList<>(pages);
    }

    void show(List<String> pages, String current) {
        this.pages = pages;
        this.current = current;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        hits.clear();
        Context context = getContext();
        int color = Prefs.color(context);

        // The pages' 368:194 box.
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
        float textH = bh * TAB_H;
        float base = by + pad + textH;
        float left = bx + pad * 1.4f;
        float right = bx + bw - pad * 1.4f;
        rowBottom = base + textH * 1.2f;

        if (current != null && CoverActivity.hidesMenu(current) && !CoverActivity.ringing(context)) {
            rowBottom = -1; // no row: every touch is the page's
            return;
        }
        seg.textStyle(SegmentPainter.seg14(), textH);
        float clockLeft;
        if (CoverActivity.ringing(context)) {
            boolean alarm = AlarmController.state(context) == AlarmController.State.RINGING;
            String label = alarm ? "ALARM" : "TIME UP";
            clockLeft = right - seg.textWidth(label);
            boolean lit = System.currentTimeMillis() / 400 % 2 == 0;
            text(canvas, label, clockLeft, base, textH, lit ? color : SegmentPainter.dim(color));
            postInvalidateDelayed(200);
        } else {
            clockLeft = drawClock(canvas, right, base, textH);
        }

        // Icons centred on the text's middle line; smaller only if there are too many to fit.
        int n = pages.size();
        float s = Math.min(bh * ICON_H, (clockLeft - textH * CLOCK_GAP - left) / (n + (n - 1) * ICON_GAP));
        float cy = base - textH / 2;
        float x = left;
        for (String page : pages) {
            boolean on = page.equals(current);
            RectF icon = new RectF(x, cy - s / 2, x + s, cy + s / 2);
            icons.draw(canvas, page, icon, on);
            if (on) {
                fill.setColor(color);
                float t = Math.max(1.5f, s * 0.09f);
                canvas.drawRect(icon.left, icon.bottom + s * 0.14f, icon.right, icon.bottom + s * 0.14f + t, fill);
            }
            hits.put(page, new RectF(x - s * ICON_GAP / 2, by, x + s + s * ICON_GAP / 2, rowBottom));
            x += s * (1 + ICON_GAP);
        }
    }

    /** The time of day, right-aligned at `right`: "2:20 PM", or "14:20". Returns its left edge. */
    private float drawClock(Canvas canvas, float right, float baseline, float glyphH) {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        boolean twelveHour = !DateFormat.is24HourFormat(getContext());
        int shown = twelveHour ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
        String time = String.format(Locale.ROOT, twelveHour && shown < 10 ? "!%d:%02d" : "%02d:%02d", shown,
                now.get(Calendar.MINUTE));
        SegmentPainter.italic(canvas, baseline - glyphH / 2);
        float x = right;
        if (twelveHour) {
            seg.textStyle(SegmentPainter.seg14(), glyphH);
            x -= seg.textWidth("~~");
            seg.cell(canvas, "~~", hour < 12 ? "AM" : "PM", x, baseline, Color.WHITE);
            x -= glyphH * 0.3f;
        }
        seg.textStyle(SegmentPainter.seg7(), glyphH);
        x -= seg.textWidth("88:88");
        seg.cell(canvas, "88:88", time, x, baseline, Color.WHITE);
        canvas.restore();
        return x;
    }

    /** Italic DSEG14 text over its unlit segments; returns the x after it. */
    private float text(Canvas canvas, String s, float x, float baseline, float glyphH, int color) {
        SegmentPainter.italic(canvas, baseline - glyphH / 2);
        float end = seg.cell(canvas, SegmentPainter.ghost14(s), s, x, baseline, color);
        canvas.restore();
        return end;
    }

    private String hit(float x, float y) {
        for (Map.Entry<String, RectF> e : hits.entrySet()) {
            if (e.getValue().contains(x, y)) {
                return e.getKey();
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getY() > rowBottom) {
                    return false; // the page's
                }
                pressed = hit(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                String target = pressed;
                pressed = null;
                if (target != null && target.equals(hit(event.getX(), event.getY()))) {
                    if (!target.equals(current)) {
                        listener.onTab(target);
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                return true;
            default:
                return true;
        }
    }
}
