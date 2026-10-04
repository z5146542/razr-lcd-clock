package dev.lcdclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;

/**
 * Draws the LCD look shared by the clock face and the cover screen: hand-drawn seven-segment
 * digits and colons with their unlit segments showing, and DSEG text cells.
 *
 * DSEG glyph advances: digits, letters, '!' (blank cell), '~' (all segments, DSEG14) and
 * '%' are one cell wide; ':' and ' ' are the same narrower width; '.' is zero-width and
 * sits in the corner of the previous cell. That lets each lit string be drawn exactly on
 * top of an all-segments "ghost" string of the same shape.
 */
final class SegmentPainter {
    /** Segment bits a..g = bit 0..6, standard seven-segment layout. */
    private static final int[] DIGIT_SEGMENTS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
    private static final int ALL_SEGMENTS = 0x7F;
    static final int BLANK = -1;

    // Drawn digit proportions, relative to digit height.
    static final float DIGIT_W = 0.52f;
    static final float SEGMENT_T = 0.09f;
    static final float DIGIT_GAP = 0.20f;
    static final float COLON_W = 0.42f;
    /** Width of "HH:MM" in digit heights. */
    static final float ROW_HHMM = 4 * DIGIT_W + 2 * DIGIT_GAP + COLON_W;
    /** Width of "HH:MM:SS" in digit heights. */
    static final float ROW_HHMMSS = 6 * DIGIT_W + 3 * DIGIT_GAP + 2 * COLON_W;
    /** Italic lean: tan(8 degrees). */
    static final float SLANT = 0.14f;

    private static Typeface seg7;
    private static Typeface seg14;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect bounds = new Rect();

    SegmentPainter(Context context) {
        if (seg7 == null) {
            seg7 = Typeface.createFromAsset(context.getAssets(), "fonts/DSEG7Modern-Regular.ttf");
            seg14 = Typeface.createFromAsset(context.getAssets(), "fonts/DSEG14Modern-Regular.ttf");
        }
        fill.setStyle(Paint.Style.FILL);
    }

    static Typeface seg7() {
        return seg7;
    }

    static Typeface seg14() {
        return seg14;
    }

    /** 25% alpha: unlit LCD segments. Much lower and the OLED panel renders them as black. */
    static int dim(int color) {
        return (color & 0x00FFFFFF) | 0x40000000;
    }

    /** Leans everything drawn until the matching restore around the horizontal line at y. */
    static void italic(Canvas canvas, float y) {
        canvas.save();
        canvas.translate(0, y);
        canvas.skew(-SLANT, 0);
        canvas.translate(0, -y);
    }

    /** All seven segments unlit, then the digit's segments lit on top. */
    void digit(Canvas canvas, float x, float y, float h, int digit, int color) {
        float w = h * DIGIT_W;
        float t = h * SEGMENT_T;
        fill.setColor(dim(color));
        segments(canvas, x, y, w, h, t, ALL_SEGMENTS);
        if (digit != BLANK) {
            fill.setColor(color);
            segments(canvas, x, y, w, h, t, DIGIT_SEGMENTS[digit]);
        }
    }

    /** Two digits of a value, e.g. "07"; returns the x after them. */
    float pair(Canvas canvas, float x, float y, float h, int value, boolean blankLeadingZero, int color) {
        digit(canvas, x, y, h, value < 10 && blankLeadingZero ? BLANK : value / 10, color);
        x += h * (DIGIT_W + DIGIT_GAP);
        digit(canvas, x, y, h, value % 10, color);
        return x + h * DIGIT_W;
    }

    /** A colon centred in its COLON_W slot starting at x; returns the x after the slot. */
    float colon(Canvas canvas, float x, float y, float h, boolean on, int color) {
        float cx = x + h * COLON_W / 2;
        float t = h * SEGMENT_T;
        fill.setColor(dim(color));
        colonDots(canvas, cx, y, h, t);
        if (on) {
            fill.setColor(color);
            colonDots(canvas, cx, y, h, t);
        }
        return x + h * COLON_W;
    }

    private void colonDots(Canvas canvas, float cx, float y, float h, float t) {
        canvas.drawRect(cx - t / 2, y + h * 0.3f - t / 2, cx + t / 2, y + h * 0.3f + t / 2, fill);
        canvas.drawRect(cx - t / 2, y + h * 0.7f - t / 2, cx + t / 2, y + h * 0.7f + t / 2, fill);
    }

    private void segments(Canvas canvas, float x, float y, float w, float h, float t, int bits) {
        float gap = t * 0.15f;
        float left = x + t / 2;
        float right = x + w - t / 2;
        float mid = y + h / 2;
        path.reset();
        if ((bits & 0x01) != 0) horizontal(left + gap, right - gap, y + t / 2, t); // a
        if ((bits & 0x02) != 0) vertical(right, y + t / 2 + gap, mid - gap, t); // b
        if ((bits & 0x04) != 0) vertical(right, mid + gap, y + h - t / 2 - gap, t); // c
        if ((bits & 0x08) != 0) horizontal(left + gap, right - gap, y + h - t / 2, t); // d
        if ((bits & 0x10) != 0) vertical(left, mid + gap, y + h - t / 2 - gap, t); // e
        if ((bits & 0x20) != 0) vertical(left, y + t / 2 + gap, mid - gap, t); // f
        if ((bits & 0x40) != 0) horizontal(left + gap, right - gap, mid, t); // g
        canvas.drawPath(path, fill);
    }

    /** Hexagonal segment with pointed ends, tip to tip from x0 to x1. */
    private void horizontal(float x0, float x1, float cy, float t) {
        float half = t / 2;
        path.moveTo(x0, cy);
        path.lineTo(x0 + half, cy - half);
        path.lineTo(x1 - half, cy - half);
        path.lineTo(x1, cy);
        path.lineTo(x1 - half, cy + half);
        path.lineTo(x0 + half, cy + half);
        path.close();
    }

    private void vertical(float cx, float y0, float y1, float t) {
        float half = t / 2;
        path.moveTo(cx, y0);
        path.lineTo(cx + half, y0 + half);
        path.lineTo(cx + half, y1 - half);
        path.lineTo(cx, y1);
        path.lineTo(cx - half, y1 - half);
        path.lineTo(cx - half, y0 + half);
        path.close();
    }

    /** Adds a pointed-end segment from (x0, y0) to (x1, y1), `t` thick, to `out` (any angle). */
    static void bar(Path out, float x0, float y0, float x1, float y1, float t) {
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        float dx = (x1 - x0) / len;
        float dy = (y1 - y0) / len;
        float half = t / 2;
        float nx = -dy * half;
        float ny = dx * half;
        out.moveTo(x0, y0);
        out.lineTo(x0 + dx * half + nx, y0 + dy * half + ny);
        out.lineTo(x1 - dx * half + nx, y1 - dy * half + ny);
        out.lineTo(x1, y1);
        out.lineTo(x1 - dx * half - nx, y1 - dy * half - ny);
        out.lineTo(x0 + dx * half - nx, y0 + dy * half - ny);
        out.close();
    }

    /** An alarm bell `s` tall standing on `bottom`: lit, or unlit segments. */
    void bell(Canvas canvas, float cx, float bottom, float s, boolean lit, int color) {
        float y = bottom - s;
        path.reset();
        path.moveTo(cx - 0.30f * s, y + 0.70f * s);
        path.lineTo(cx - 0.30f * s, y + 0.42f * s);
        path.cubicTo(cx - 0.30f * s, y + 0.10f * s, cx + 0.30f * s, y + 0.10f * s, cx + 0.30f * s, y + 0.42f * s);
        path.lineTo(cx + 0.30f * s, y + 0.70f * s);
        path.lineTo(cx + 0.44f * s, y + 0.82f * s);
        path.lineTo(cx - 0.44f * s, y + 0.82f * s);
        path.close();
        path.addCircle(cx, y + 0.08f * s, 0.07f * s, Path.Direction.CW); // knob
        path.addCircle(cx, y + 0.92f * s, 0.08f * s, Path.Direction.CW); // clapper
        fill.setColor(lit ? color : dim(color));
        canvas.drawPath(path, fill);
    }

    /** Sets the text typeface and the size at which its "8" is the given height. */
    void textStyle(Typeface typeface, float glyphHeight) {
        text.setTypeface(typeface);
        text.setTextSize(100);
        text.getTextBounds("8", 0, 1, bounds);
        text.setTextSize(glyphHeight * 100 / bounds.height());
    }

    float textWidth(String s) {
        return text.measureText(s);
    }

    /** Draws the all-segments ghost, then the lit text on top; returns the x after the cells. */
    float cell(Canvas canvas, String ghost, String lit, float x, float baseline, int color) {
        text.setColor(dim(color));
        canvas.drawText(ghost, x, baseline, text);
        text.setColor(color);
        canvas.drawText(lit, x, baseline, text);
        return x + text.measureText(ghost);
    }

    /** The all-segments ghost for DSEG14 text: '~' per character cell, narrow cells kept. */
    static String ghost14(String lit) {
        StringBuilder ghost = new StringBuilder(lit.length());
        for (char c : lit.toCharArray()) {
            ghost.append(c == ' ' || c == ':' || c == '.' ? c : '~');
        }
        return ghost.toString();
    }
}
