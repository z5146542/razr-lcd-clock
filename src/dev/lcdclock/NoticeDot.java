package dev.lcdclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The notification indicator under LCD Clock's own clock face, where Motorola's face has its
 * dot: a speech bubble, lit in the clock colour while the notifications panel has anything to
 * list (so not while the lock screen hides notifications), unlit segments otherwise, like the
 * face's alarm bell. Swipe up on the face to see them.
 */
final class NoticeDot extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bubble = new Path();
    private boolean any;

    private final Runnable changed = this::refresh;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh(); // the lock state can change what may be counted
            postDelayed(this, 2000);
        }
    };

    NoticeDot(Context context) {
        super(context);
        fill.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Notices.listen(changed);
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        Notices.unlisten(changed);
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void refresh() {
        boolean now = Notices.count(getContext()) > 0;
        if (now != any) {
            any = now;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float bh = Math.min(getHeight(), getWidth() * 194f / 368f);
        float by = (getHeight() - bh) / 2;
        float s = bh * 0.085f;
        float cx = getWidth() / 2f;
        float cy = by + bh * 0.915f;
        int color = Prefs.color(getContext());

        // A rounded bubble with a tail at the lower left, and two lines of "text" cut out of it.
        bubble.reset();
        bubble.addRoundRect(cx - s * 0.75f, cy - s * 0.5f, cx + s * 0.75f, cy + s * 0.3f, s * 0.22f, s * 0.22f,
                Path.Direction.CW);
        bubble.moveTo(cx - s * 0.42f, cy + s * 0.25f);
        bubble.lineTo(cx - s * 0.5f, cy + s * 0.62f);
        bubble.lineTo(cx - s * 0.1f, cy + s * 0.25f);
        bubble.close();
        fill.setColor(any ? color : SegmentPainter.dim(color));
        canvas.drawPath(bubble, fill);
        fill.setColor(Color.BLACK);
        float t = s * 0.12f;
        canvas.drawRect(cx - s * 0.45f, cy - s * 0.24f - t / 2, cx + s * 0.45f, cy - s * 0.24f + t / 2, fill);
        canvas.drawRect(cx - s * 0.45f, cy + s * 0.02f - t / 2, cx + s * 0.2f, cy + s * 0.02f + t / 2, fill);
    }
}
