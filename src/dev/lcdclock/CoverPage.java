package dev.lcdclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the newer cover pages share: the largest 368:194 box, the content area under CoverTabs'
 * menu row, outlined buttons like the other pages', lines of plain text (cut or scrolling), tap
 * handling (a press shows on the button; letting go on it runs perform) and, for pages that set
 * maxScroll, dragging up and down to scroll (a drag isn't a tap).
 */
abstract class CoverPage extends View {
    static final int NONE = -1;
    static final int GREY = 0xFF7A7A7A;
    static final int OUTLINE = 0xFFB4B4B4;
    /** CLEAR's fill: it dismisses everything. */
    static final int CLEAR_RED = 0xFFE02020;
    /** Scrolling text moves this many box heights a second, on every page and whatever its length. */
    static final float MARQUEE_SPEED = 0.3f;

    final SegmentPainter seg;
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    /** Touch targets laid out by the last onDraw. */
    final Map<Integer, RectF> hits = new LinkedHashMap<>();
    int pressed = NONE;

    // The box and content area, set before each drawPage.
    float bx;
    float by;
    float bw;
    float bh;
    float pad;
    /** Top of the content, under the menu row. */
    float top;
    /** When this page appeared: scrolling text starts from here. */
    private long shownAt = SystemClock.uptimeMillis();
    /** How far the content is scrolled, in px, and how far it can go (0: not scrollable). */
    float scroll;
    float maxScroll;
    private float downY;
    private float lastY;
    private boolean dragging;
    /**
     * For a panel: which drag closes it, back the way it came (-1: down, the notifications that
     * came up; +1: up, the quick controls that came down; 0: none), once its list can't scroll
     * further that way; and what closing does.
     */
    int closeDrag;
    Runnable onCloseDrag;
    /** How far past the end of the list the closing drag has pulled the page, in px. */
    private float pulled;
    /** When the held control was touched, while it's one that needs holding; 0 otherwise. */
    private long holdStart;
    /** The held control has acted (its time was reached before the finger lifted). */
    private boolean held;

    /** Runs a held control once its hold time is reached, if the finger is still on it. */
    private final Runnable holdDone = () -> {
        if (pressed != NONE && !dragging && holdStart != 0) {
            held = true;
            int control = pressed;
            if (perform(control)) {
                vibrate(VibrationEffect.EFFECT_HEAVY_CLICK);
            }
            invalidate();
        }
    };

    /** Redraws the hold progress, with a tick at each whole second. */
    private final Runnable holdTick = new Runnable() {
        private long lastSecond;

        @Override
        public void run() {
            if (holdStart == 0 || held) {
                lastSecond = 0;
                return;
            }
            long second = (System.currentTimeMillis() - holdStart) / 1000;
            if (second > lastSecond) {
                lastSecond = second;
                vibrate(VibrationEffect.EFFECT_TICK);
            }
            invalidate();
            postDelayed(this, 50);
        }
    };

    CoverPage(Context context) {
        super(context);
        seg = new SegmentPainter(context);
        fill.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        text.setColor(Color.WHITE);
    }

    @Override
    protected final void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        hits.clear();
        bw = getWidth();
        bh = getHeight();
        if (bw / bh < 368f / 194f) {
            bh = bw * 194f / 368f;
        } else {
            bw = bh * 368f / 194f;
        }
        bx = (getWidth() - bw) / 2;
        by = (getHeight() - bh) / 2;
        pad = bh * 0.05f;
        top = by + pad + bh * CoverTabs.TAB_H + pad;
        drawPage(canvas, Prefs.color(getContext()));
    }

    abstract void drawPage(Canvas canvas, int color);

    /** A tapped control; return true to buzz. */
    boolean perform(int id) {
        return false;
    }

    /** How long a control must be held before it acts; 0 for an ordinary tap. */
    long holdMs(int id) {
        return 0;
    }

    /** A control that needs holding was let go too soon. */
    void onHoldTooShort(int id) {
    }

    /** How far `id` is through its hold, 0 to 1, while it's being held; otherwise -1. */
    float holdProgress(int id) {
        long ms = holdStart == 0 || pressed != id ? 0 : holdMs(id);
        return ms <= 0 ? -1 : Math.min(1f, (System.currentTimeMillis() - holdStart) / (float) ms);
    }

    private void cancelHold() {
        removeCallbacks(holdDone);
        removeCallbacks(holdTick);
        holdStart = 0;
    }

    private void vibrate(int effect) {
        getContext().getSystemService(VibratorManager.class).getDefaultVibrator()
                .vibrate(VibrationEffect.createPredefined(effect));
    }

    /** Height of the bottom button row, and its top. */
    float buttonHeight() {
        return bh * 0.25f;
    }

    float buttonTop() {
        return by + bh - pad - buttonHeight();
    }

    /**
     * An outlined button like the Alarm & Timer ones: primary ones in the clock colour, the
     * rest light grey, disabled ones grey and not tappable. Draws no content; returns its box.
     */
    RectF button(Canvas canvas, int id, RectF r, boolean primary, boolean enabled, int color) {
        float stroke = Math.max(1.5f, r.height() * 0.045f);
        float radius = r.height() * 0.28f;
        if (enabled && pressed == id) {
            fill.setColor(SegmentPainter.dim(color));
            canvas.drawRoundRect(r, radius, radius, fill);
        }
        outline.setStrokeWidth(stroke);
        outline.setColor(!enabled ? GREY : primary ? color : OUTLINE);
        canvas.drawRoundRect(r.left + stroke / 2, r.top + stroke / 2, r.right - stroke / 2, r.bottom - stroke / 2,
                radius, radius, outline);
        if (enabled) {
            hits.put(id, r);
        }
        return r;
    }

    /** Italic DSEG14 text over its unlit segments; returns the x after it. */
    float label(Canvas canvas, String s, float x, float baseline, float glyphH, int color) {
        seg.textStyle(SegmentPainter.seg14(), glyphH);
        SegmentPainter.italic(canvas, baseline - glyphH / 2);
        float end = seg.cell(canvas, SegmentPainter.ghost14(s), s, x, baseline, color);
        canvas.restore();
        return end;
    }

    /**
     * A panel's own top row, where the menu row is on the other pages: its name, then at the
     * right an optional action (e.g. CLEAR, filled red), boxed like the other buttons. No X: a
     * sideways swipe, or a drag back the way the panel came, closes it. The action's taps end
     * where the page starts, so they never take taps meant for the buttons below.
     */
    void panelHeader(Canvas canvas, String title, String action, int actionId) {
        float textH = bh * CoverTabs.TAB_H;
        float base = by + pad + textH;
        label(canvas, title, bx + pad * 1.4f, base, textH, Color.WHITE);
        if (action != null) {
            float y = headerY();
            headerButton(canvas, actionId, action, "CLEAR".equals(action) ? CLEAR_RED : 0, bx + bw - pad * 1.2f, y,
                    headerH(y), bx + bw);
        }
    }

    /** A panel's top row with "< BACK" at the left; returns its right edge. */
    float backHeader(Canvas canvas, int backId, int color) {
        float y = headerY();
        return backButton(canvas, backId, y, headerH(y), by, top, color);
    }

    /** Top and height of the buttons in a panel's top row. */
    private float headerY() {
        return by + pad * 0.45f;
    }

    private float headerH(float y) {
        return top - pad * 0.3f - y;
    }

    /**
     * A boxed button in a panel's top row, `h` tall from `y` with its right edge at `right`:
     * outlined like the other buttons, or filled with `fillColor` when it isn't 0. Taps count from a
     * little left of it to `touchRight`, down to where the page starts. Returns its left edge.
     */
    private float headerButton(Canvas canvas, int id, String label, int fillColor, float right, float y, float h,
            float touchRight) {
        float glyphH = h * 0.5f;
        seg.textStyle(SegmentPainter.seg14(), glyphH);
        float labelW = seg.textWidth(SegmentPainter.ghost14(label));
        RectF r = new RectF(right - (label.length() == 1 ? h * 1.15f : labelW + h * 0.84f), y, right, y + h);
        if (fillColor != 0) {
            float radius = r.height() * 0.28f;
            fill.setColor(pressed == id ? SegmentPainter.dim(fillColor) : fillColor);
            canvas.drawRoundRect(r, radius, radius, fill);
        } else {
            button(canvas, id, r, false, true, Prefs.color(getContext()));
        }
        label(canvas, label, r.centerX() - labelW / 2, r.centerY() + glyphH / 2, glyphH, Color.WHITE);
        hits.put(id, new RectF(r.left - pad * 0.4f, by, touchRight, top));
        return r.left;
    }

    /**
     * An outlined "< BACK" button at the left, `h` tall from `y`. Taps count anywhere in the left
     * 45% of the row (or a little past the button if wider), between `touchTop` and `touchBottom`,
     * so it's easy to hit and far from anything at the right; it takes those taps before anything drawn
     * after it there. Returns its right edge.
     */
    float backButton(Canvas canvas, int id, float y, float h, float touchTop, float touchBottom, int color) {
        float glyphH = h * 0.5f;
        seg.textStyle(SegmentPainter.seg14(), glyphH);
        float labelW = seg.textWidth(SegmentPainter.ghost14("BACK"));
        float inner = h * 0.42f;
        float chevron = glyphH * 0.5f;
        float left = bx + pad * 1.2f;
        RectF r = new RectF(left, y, left + inner + chevron + glyphH * 0.55f + labelW + inner, y + h);
        button(canvas, id, r, false, true, color);
        float cx = r.left + inner + chevron * 0.3f;
        float t = Math.max(1.5f, glyphH * 0.16f);
        path.reset();
        SegmentPainter.bar(path, cx + chevron * 0.7f, r.centerY() - glyphH / 2, cx, r.centerY(), t);
        SegmentPainter.bar(path, cx, r.centerY(), cx + chevron * 0.7f, r.centerY() + glyphH / 2, t);
        fill.setColor(Color.WHITE);
        canvas.drawPath(path, fill);
        label(canvas, "BACK", r.left + inner + chevron + glyphH * 0.55f, r.centerY() + glyphH / 2, glyphH, Color.WHITE);
        hits.put(id, new RectF(bx, touchTop, Math.max(r.right + h * 0.6f, bx + bw * 0.45f), touchBottom));
        return r.right;
    }

    /** A thin bar at the right edge showing where the scrolled content is. */
    void scrollBar(Canvas canvas, float viewTop, float viewBottom, float contentH, int color) {
        if (maxScroll <= 0) {
            return;
        }
        float trackH = viewBottom - viewTop;
        float thumbH = trackH * trackH / contentH;
        float thumbTop = viewTop + (trackH - thumbH) * scroll / maxScroll;
        fill.setColor(SegmentPainter.dim(color));
        canvas.drawRect(bx + bw - pad * 0.6f, viewTop, bx + bw - pad * 0.3f, viewBottom, fill);
        fill.setColor(color);
        canvas.drawRect(bx + bw - pad * 0.6f, thumbTop, bx + bw - pad * 0.3f, thumbTop + thumbH, fill);
    }

    /** One line in the plain font, cut with "…" to fit `width`. */
    void line(Canvas canvas, String s, float x, float baseline, float size, float width, int color) {
        text.setTextSize(size);
        text.setColor(color);
        if (text.measureText(s) > width) {
            int n = text.breakText(s, true, width - text.measureText("…"), null);
            s = s.substring(0, Math.max(0, n)).trim() + "…";
        }
        canvas.drawText(s, x, baseline, text);
    }

    /**
     * One line in the plain font that, when it's wider than `width`, holds still briefly and then
     * scrolls round like a car stereo's, clipped to its box. Returns true while it scrolls, so the
     * page keeps redrawing.
     */
    boolean marquee(Canvas canvas, String s, float x, float baseline, float size, float width, int color) {
        text.setTextSize(size);
        text.setColor(color);
        float textW = text.measureText(s);
        if (textW <= width) {
            canvas.drawText(s, x, baseline, text);
            return false;
        }
        Paint.FontMetrics metrics = text.getFontMetrics();
        int period = Math.round(textW + text.measureText("     "));
        long t = Math.max(0, getDrawingTime() - shownAt - 1500);
        long offset = marqueeOffset(this, t, bh * MARQUEE_SPEED) % period;
        canvas.save();
        canvas.clipRect(x, baseline + metrics.ascent, x + width, baseline + metrics.descent);
        canvas.drawText(s, x - offset, baseline, text);
        canvas.drawText(s, x - offset + period, baseline, text);
        canvas.restore();
        return true;
    }

    /**
     * How far scrolling text has moved `ms` (frame time) after it started, at about `speed` px a
     * second: always whole pixels, the same number every frame (or one every so many frames).
     * Moved by fractions of a pixel, each letter snapped to the pixel grid on its own, so letters
     * stepped at different moments and the text shimmered instead of sliding.
     */
    static long marqueeOffset(View view, long ms, float speed) {
        android.view.Display display = view.getDisplay();
        float fps = display != null && display.getRefreshRate() > 0 ? display.getRefreshRate() : 60;
        long frames = Math.round(ms * fps / 1000.0);
        float perFrame = speed / fps;
        return perFrame >= 1 ? frames * Math.round(perFrame) : frames / Math.max(1, Math.round(1 / perFrame));
    }

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
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pressed = hit(event.getX(), event.getY());
                downY = event.getY();
                lastY = downY;
                dragging = false;
                pulled = 0;
                cancelHold();
                held = false;
                long hold = pressed == NONE ? 0 : holdMs(pressed);
                if (hold > 0) {
                    holdStart = System.currentTimeMillis();
                    postDelayed(holdDone, hold);
                    post(holdTick);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if ((maxScroll > 0 || closeDrag != 0) && !dragging
                        && Math.abs(event.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    dragging = true;
                    pressed = NONE;
                    cancelHold();
                }
                if (dragging) {
                    drag(event.getY() - lastY);
                    lastY = event.getY();
                    invalidate();
                } else if (pressed != NONE && hit(event.getX(), event.getY()) != pressed) {
                    pressed = NONE;
                    cancelHold();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (pulled > bh * 0.18f && onCloseDrag != null) {
                    pulled = 0;
                    onCloseDrag.run(); // slides on from where the finger left it
                    return true;
                }
                if (pulled > 0) {
                    pulled = 0;
                    animate().translationY(0).setDuration(120);
                }
                int control = pressed;
                pressed = NONE;
                if (holdStart != 0) {
                    // A control that needs holding: it already acted if held long enough.
                    boolean acted = held;
                    cancelHold();
                    if (!acted && !dragging && control != NONE) {
                        onHoldTooShort(control);
                    }
                    invalidate();
                    return true;
                }
                if (!dragging && control != NONE && hit(event.getX(), event.getY()) == control && perform(control)) {
                    getContext().getSystemService(VibratorManager.class).getDefaultVibrator()
                            .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = NONE;
                cancelHold();
                pulled = 0;
                animate().translationY(0).setDuration(120);
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /**
     * Moves the list by a finger movement of dy; past the end of the list in the closing
     * direction it pulls the page instead (which follows the finger, at half the distance).
     */
    private void drag(float dy) {
        boolean towardClose = closeDrag != 0 && Math.signum(dy) == -closeDrag;
        boolean atEnd = closeDrag < 0 ? scroll <= 0 : scroll >= maxScroll;
        if (pulled > 0 || towardClose && atEnd) {
            pulled = Math.max(0, pulled + (towardClose ? Math.abs(dy) : -Math.abs(dy)));
            setTranslationY(-closeDrag * pulled * 0.5f);
            return;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll - dy));
    }
}
