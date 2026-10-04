package dev.lcdclock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.VibratorManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The cover screen's MUSIC page, after a GOM Audio mini player: the track position in big
 * segment digits, the same size for MM:SS and H:MM:SS (as big as the hours fit); under it the
 * play state, a scrolling "Artist - Title" and the track length; a progress bar, album art on the right, and previous / back 5 s / play-pause / forward 5 s /
 * next along the bottom (the time of day is in CoverTabs' menu row). Each app playing has its
 * own MUSIC page and tab; the plain MUSIC tab, shown when none is, uses whichever Media picks.
 * Laid out for the 368x194 outside screen; on any other shape it uses the largest box of that
 * shape.
 */
final class MusicView extends View {
    private static final int NONE = -1;
    private static final int PREV = 2;
    private static final int BACK = 3;
    private static final int PLAY = 4;
    private static final int FORWARD = 5;
    private static final int NEXT = 6;
    private static final int[] ROW = {PREV, BACK, PLAY, FORWARD, NEXT};
    /** Play-pause is this many times as wide as the other buttons. */
    private static final float PLAY_WEIGHT = 1.4f;

    private static final int GREY = 0xFF7A7A7A;
    private static final int OUTLINE = 0xFFB4B4B4;
    private static final long SEEK_MS = 5000;
    private static final long TICK_MS = 250;
    /** A long title holds still this long, then scrolls at CoverPage.MARQUEE_SPEED like the other pages. */
    private static final long MARQUEE_HOLD_MS = 1500;
    /** The play state and the track length beside the title, in title cap heights. */
    private static final float STATE_H = 1.1f;
    private static final float LENGTH_H = 1.05f;

    private final SegmentPainter seg;
    /** The player this tab shows (Media.key), or null for the plain MUSIC tab. */
    private final String key;
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    /** Touch targets laid out by the last onDraw. */
    private final Map<Integer, RectF> hits = new LinkedHashMap<>();
    private int pressed = NONE;

    // The player as of the last refresh(); reading it is a call into the player, so not per frame.
    private MediaController controller;
    private PlaybackState state;
    private MediaMetadata metadata;
    private boolean allowed;
    private String title = "";
    /** The layout box's height, for the scrolling speed. */
    private float boxH;
    private long titleSince;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            invalidate();
            postDelayed(this, TICK_MS);
        }
    };

    private final Runnable changed = () -> {
        refresh();
        invalidate();
    };

    MusicView(Context context, String key) {
        super(context);
        this.key = key;
        seg = new SegmentPainter(context);
        outline.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Media.listen(changed);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        Media.unlisten(changed);
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void refresh() {
        Context context = getContext();
        controller = key == null ? Media.current(context) : Media.session(context, key);
        state = controller == null ? null : controller.getPlaybackState();
        metadata = controller == null ? null : controller.getMetadata();
        allowed = controller != null || Media.allowed(context);
        String now;
        if (!allowed) {
            now = "Needs notification access: open LCD Clock on the inside screen";
        } else if (controller == null) {
            now = "Nothing playing";
        } else {
            now = Media.title(metadata);
            if (now.isEmpty()) {
                now = "Unknown track";
            }
        }
        if (!now.equals(title)) {
            title = now;
            titleSince = SystemClock.uptimeMillis();
        }
    }

    // ---- drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        hits.clear();
        int color = Prefs.color(getContext());

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
        boxH = bh;

        // The menu row above is CoverTabs'.
        float tabBase = by + pad + bh * CoverTabs.TAB_H;

        float btnH = bh * 0.25f;
        float btnTop = by + bh - pad - btnH;
        float midTop = tabBase + pad;
        float midH = btnTop - pad * 0.8f - midTop;

        // Album art: a square on the right of the middle area; position, title and bar on the left.
        RectF art = new RectF(bx + bw - pad - midH, midTop, bx + bw - pad, midTop + midH);
        drawArt(canvas, art, color);
        drawInfo(canvas, color, bx + pad * 1.4f, art.left - pad * 1.4f, midTop, midH);
        buttons(canvas, color, bx, bw, pad, btnTop, btnH);
    }

    /**
     * The track position in the clock's digits ("MM:SS", or "H:MM:SS" for tracks of an hour or
     * more), both at the size that fits H:MM:SS across the column; then ▶ or ❚❚, the scrolling
     * title and the track length on one line; the bar.
     */
    private void drawInfo(Canvas canvas, int color, float left, float right, float top, float h) {
        long duration = Media.duration(metadata);
        long position = controller == null ? -1 : Media.position(controller, state);
        boolean hours = Math.max(duration, position) >= 3_600_000;
        String lengthGhost = ghost7(length(hours ? 3_600_000 : 0));

        // One size for MM:SS and H:MM:SS: as big as the hours row fits.
        float digitH = Math.min(h * 0.55f, (right - left) / (SegmentPainter.ROW_HHMMSS + SegmentPainter.SLANT));

        long secs = Math.max(0, position) / 1000;
        int[] values = hours ? new int[] {(int) (secs / 3600), (int) (secs / 60 % 60), (int) (secs % 60)}
                : new int[] {(int) (secs / 60), (int) (secs % 60)};
        SegmentPainter.italic(canvas, top + digitH / 2);
        float x = left;
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                x = seg.colon(canvas, x, top, digitH, position >= 0, color);
            }
            if (position < 0) {
                seg.digit(canvas, x, top, digitH, SegmentPainter.BLANK, color);
                x += digitH * (SegmentPainter.DIGIT_W + SegmentPainter.DIGIT_GAP);
                seg.digit(canvas, x, top, digitH, SegmentPainter.BLANK, color);
                x += digitH * SegmentPainter.DIGIT_W;
            } else {
                x = seg.pair(canvas, x, top, digitH, values[i], hours && i == 0, color);
            }
        }
        canvas.restore();

        // Progress bar along the bottom, title centred in the space between.
        float t = Math.max(2f, h * 0.035f);
        float barY = top + h - t / 2;
        fill.setColor(SegmentPainter.dim(color));
        canvas.drawRect(left, barY - t / 2, right, barY + t / 2, fill);
        if (duration > 0 && position >= 0) {
            fill.setColor(color);
            canvas.drawRect(left, barY - t / 2, left + (right - left) * Math.min(1f, position / (float) duration),
                    barY + t / 2, fill);
        }
        titlePaint.setTextSize(h * 0.19f);
        Paint.FontMetrics metrics = titlePaint.getFontMetrics();
        float capH = -titlePaint.ascent() * 0.72f;
        float titleBase = (top + digitH + barY) / 2 + capH / 2;

        // ▶ or ❚❚ before the title, the track length after it.
        float stateS = capH * STATE_H;
        float lengthH = capH * LENGTH_H;
        seg.textStyle(SegmentPainter.seg7(), lengthH);
        float lengthW = seg.textWidth(lengthGhost);
        float gap = capH * 0.7f;
        SegmentPainter.italic(canvas, titleBase - capH / 2);
        drawState(canvas, left, titleBase - capH / 2 - stateS / 2, stateS, color);
        seg.cell(canvas, lengthGhost, duration > 0 ? length(duration) : lengthGhost.replace('8', '!'),
                right - lengthW, titleBase, Color.WHITE);
        canvas.restore();
        drawTitle(canvas, left + stateS * 0.85f + gap, right - lengthW - gap, titleBase, capH, metrics,
                boxH * CoverPage.MARQUEE_SPEED);
    }

    /** ▶ while playing, ❚❚ while paused, an unlit ▶ with nothing to play; `s` tall from `top`. */
    private void drawState(Canvas canvas, float x, float top, float s, int color) {
        boolean playing = Media.isActive(state);
        fill.setColor(controller == null ? SegmentPainter.dim(color) : color);
        path.reset();
        if (controller != null && !playing) {
            float bar = s * 0.3f;
            canvas.drawRect(x, top, x + bar, top + s, fill);
            canvas.drawRect(x + s * 0.55f, top, x + s * 0.55f + bar, top + s, fill);
        } else {
            triangle(x, top + s / 2, s * 0.85f, s);
            canvas.drawPath(path, fill);
        }
    }

    /** One line, scrolling round like a car stereo's when it's too long to fit. */
    private void drawTitle(Canvas canvas, float left, float right, float baseline, float capH,
            Paint.FontMetrics metrics, float speed) {
        float boxW = right - left;
        float textW = titlePaint.measureText(title);
        canvas.save();
        // Clipped to the column; the slant's small lean at the left edge is allowed for.
        canvas.clipRect(left - capH * SegmentPainter.SLANT, baseline + metrics.ascent, right,
                baseline + metrics.descent);
        SegmentPainter.italic(canvas, baseline - capH / 2);
        if (textW <= boxW) {
            canvas.drawText(title, left, baseline, titlePaint);
        } else {
            float gapW = titlePaint.measureText("     ");
            long t = Math.max(0, SystemClock.uptimeMillis() - titleSince - MARQUEE_HOLD_MS);
            float offset = (t * speed / 1000f) % (textW + gapW);
            canvas.drawText(title, left - offset, baseline, titlePaint);
            canvas.drawText(title, left - offset + textW + gapW, baseline, titlePaint);
            postInvalidateOnAnimation();
        }
        canvas.restore();
        canvas.restore();
    }

    /** A track length as "m:ss", or "h:mm:ss" from an hour. */
    private static String length(long ms) {
        long s = ms / 1000;
        if (s >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String ghost7(String s) {
        return s.replaceAll("[0-9]", "8");
    }

    /** The player's album art, or an unlit note when it has none. */
    private void drawArt(Canvas canvas, RectF r, int color) {
        Bitmap bitmap = Media.art(metadata);
        float radius = r.width() * 0.08f;
        if (bitmap != null) {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int side = Math.min(w, h);
            canvas.save();
            path.reset();
            path.addRoundRect(r, radius, radius, Path.Direction.CW);
            canvas.clipPath(path);
            canvas.drawBitmap(bitmap, new Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2), r,
                    artPaint);
            canvas.restore();
            return;
        }
        float stroke = Math.max(1.5f, r.height() * 0.025f);
        outline.setStrokeWidth(stroke);
        outline.setColor(GREY);
        canvas.drawRoundRect(r.left + stroke / 2, r.top + stroke / 2, r.right - stroke / 2, r.bottom - stroke / 2,
                radius, radius, outline);
        // Two beamed quavers in segments.
        float s = r.width() * 0.5f;
        float cx = r.centerX();
        float cy = r.centerY();
        float t = s * 0.1f;
        float stemL = cx - 0.16f * s;
        float stemR = cx + 0.30f * s;
        path.reset();
        SegmentPainter.bar(path, stemL, cy - 0.30f * s, stemR, cy - 0.42f * s, t * 1.5f);
        SegmentPainter.bar(path, stemL, cy - 0.30f * s, stemL, cy + 0.26f * s, t);
        SegmentPainter.bar(path, stemR, cy - 0.42f * s, stemR, cy + 0.14f * s, t);
        path.addOval(stemL - 0.30f * s, cy + 0.17f * s, stemL + t / 2, cy + 0.37f * s, Path.Direction.CW);
        path.addOval(stemR - 0.30f * s, cy + 0.05f * s, stemR + t / 2, cy + 0.25f * s, Path.Direction.CW);
        fill.setColor(SegmentPainter.dim(color));
        canvas.drawPath(path, fill);
    }

    /** The control row; play-pause is wider and drawn in the clock colour, like the primary buttons elsewhere. */
    private void buttons(Canvas canvas, int color, float bx, float bw, float pad, float top, float h) {
        float gap = pad * 0.8f;
        float unit = (bw - 2 * pad - gap * (ROW.length - 1)) / (ROW.length - 1 + PLAY_WEIGHT);
        float stroke = Math.max(1.5f, h * 0.045f);
        outline.setStrokeWidth(stroke);
        float x = bx + pad;
        for (int id : ROW) {
            RectF r = new RectF(x, top, x + unit * (id == PLAY ? PLAY_WEIGHT : 1), top + h);
            boolean enabled = enabled(id);
            float radius = h * 0.28f;
            if (enabled && pressed == id) {
                fill.setColor(SegmentPainter.dim(color));
                canvas.drawRoundRect(r, radius, radius, fill);
            }
            outline.setColor(!enabled ? GREY : id == PLAY ? color : OUTLINE);
            canvas.drawRoundRect(r.left + stroke / 2, r.top + stroke / 2, r.right - stroke / 2, r.bottom - stroke / 2,
                    radius, radius, outline);
            SegmentPainter.italic(canvas, r.centerY());
            drawControl(canvas, id, r, !enabled ? GREY : id == PLAY ? color : Color.WHITE);
            canvas.restore();
            if (enabled) {
                hits.put(id, r);
            }
            x = r.right + gap;
        }
    }

    private void drawControl(Canvas canvas, int id, RectF r, int ink) {
        float s = r.height() * 0.36f;
        float cx = r.centerX();
        float cy = r.centerY();
        fill.setColor(ink);
        path.reset();
        switch (id) {
            case PREV:
            case NEXT: {
                // Bar and triangle: |◀ or ▶|
                float bar = s * 0.18f;
                float tri = s * 0.8f;
                float l = cx - (bar + tri) / 2;
                boolean prev = id == PREV;
                float barX = prev ? l : l + tri;
                canvas.drawRect(barX, cy - s / 2, barX + bar, cy + s / 2, fill);
                triangle(prev ? l + bar + tri : l, cy, prev ? -tri : tri, s);
                break;
            }
            case BACK:
            case FORWARD: {
                // −5 / +5: the timer's plain bars, then a 7-segment 5.
                float digitH = s * 1.15f;
                float half = s * 0.3f;
                float t = s * 0.16f;
                float w = 2 * half + digitH * 0.25f + digitH * SegmentPainter.DIGIT_W;
                float l = cx - w / 2;
                canvas.drawRect(l, cy - t / 2, l + 2 * half, cy + t / 2, fill);
                if (id == FORWARD) {
                    canvas.drawRect(l + half - t / 2, cy - half, l + half + t / 2, cy + half, fill);
                }
                seg.digit(canvas, l + 2 * half + digitH * 0.25f, cy - digitH / 2, digitH, 5, ink);
                break;
            }
            default: {
                if (Media.isActive(state)) { // pause bars
                    float bar = s * 0.3f;
                    float space = s * 0.24f;
                    canvas.drawRect(cx - space / 2 - bar, cy - s / 2, cx - space / 2, cy + s / 2, fill);
                    canvas.drawRect(cx + space / 2, cy - s / 2, cx + space / 2 + bar, cy + s / 2, fill);
                } else {
                    float tri = s * 0.9f;
                    triangle(cx - tri * 0.45f, cy, tri, s);
                }
                break;
            }
        }
        canvas.drawPath(path, fill);
    }

    /** Adds a triangle to `path` with its flat side at x, pointing right for w > 0 or left for w < 0. */
    private void triangle(float x, float cy, float w, float h) {
        path.moveTo(x, cy - h / 2);
        path.lineTo(x + w, cy);
        path.lineTo(x, cy + h / 2);
        path.close();
    }

    /** Greyed when there's nothing to control or the player says it can't do that. */
    private boolean enabled(int id) {
        if (controller == null) {
            return id == PLAY && allowed;
        }
        long actions = state == null ? 0 : state.getActions();
        long needed;
        switch (id) {
            case PREV:
                needed = PlaybackState.ACTION_SKIP_TO_PREVIOUS;
                break;
            case NEXT:
                needed = PlaybackState.ACTION_SKIP_TO_NEXT;
                break;
            case BACK:
            case FORWARD:
                if (Media.position(controller, state) < 0) {
                    return false;
                }
                needed = PlaybackState.ACTION_SEEK_TO;
                break;
            default:
                needed = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;
                break;
        }
        // Some players report no actions at all; let those try everything.
        return actions == 0 || (actions & needed) != 0;
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
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(event.getX(), event.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed != NONE && hit(event.getX(), event.getY()) != pressed) {
                    pressed = NONE;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                int control = pressed;
                pressed = NONE;
                if (control != NONE && hit(event.getX(), event.getY()) == control) {
                    perform(control);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = NONE;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void perform(int control) {
        Context context = getContext();
        switch (control) {
            case PLAY:
                Media.playPause(context, controller, state);
                break;
            case PREV:
                controller.getTransportControls().skipToPrevious();
                break;
            case NEXT:
                controller.getTransportControls().skipToNext();
                break;
            case BACK:
            case FORWARD:
                Media.seekBy(controller, state, metadata, control == FORWARD ? SEEK_MS : -SEEK_MS);
                break;
            default:
                return;
        }
        Log.d(Binders.TAG, "music: " + control + " on " + (controller == null ? "none" : controller.getPackageName()));
        context.getSystemService(VibratorManager.class).getDefaultVibrator()
                .vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
    }
}
