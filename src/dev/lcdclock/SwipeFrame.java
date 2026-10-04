package dev.lcdclock;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Holds the cover screen's current page and turns a horizontal swipe across it into a page
 * change; taps still reach the page.
 */
final class SwipeFrame extends FrameLayout {
    interface Listener {
        /** +1: towards the page on the right (a swipe to the left). */
        void onSwipe(int direction);

        /** +1 up, -1 down, on a page that doesn't take touches itself (the clock face). */
        void onVerticalSwipe(int direction);
    }

    /** A swipe must cover this much of the width (or height, going up or down) to count. */
    private static final float MIN_SWIPE = 0.12f;
    private static final float MIN_VERTICAL = 0.25f;
    private static final long SLIDE_MS = 180;

    private final Listener listener;
    private final int slop;
    private float downX;
    private float downY;
    private boolean swiping;

    SwipeFrame(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(Color.BLACK);
    }

    /** Follows a gesture: whether it has become a sideways swipe. */
    private void track(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                swiping = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - downX);
                swiping = swiping || dx > slop && dx > 1.5f * Math.abs(event.getY() - downY);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        track(event);
        return swiping;
    }

    /** Reached after intercepting a swipe, or for every event when the page doesn't take touches. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        track(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (swiping && Math.abs(dx) > getWidth() * MIN_SWIPE) {
                listener.onSwipe(dx < 0 ? 1 : -1);
            } else if (!swiping && Math.abs(dy) > getHeight() * MIN_VERTICAL && Math.abs(dy) > 1.5f * Math.abs(dx)) {
                listener.onVerticalSwipe(dy < 0 ? 1 : -1);
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            swiping = false;
        }
        return true;
    }

    /** Replaces the page, the new one sliding up from below (+1) or down from above (-1). */
    void showVertical(View page, int direction) {
        View old = getChildCount() > 0 ? getChildAt(getChildCount() - 1) : null;
        addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (old == null || getHeight() == 0) {
            if (old != null) {
                removeView(old);
            }
            return;
        }
        float h = getHeight();
        page.setTranslationY(direction * h);
        page.animate().translationY(0).setDuration(SLIDE_MS);
        old.animate().translationY(-direction * h).setDuration(SLIDE_MS).withEndAction(() -> removeView(old));
    }

    /** Replaces the page; with a direction, the new one slides in from that side. */
    void show(View page, int direction) {
        View old = getChildCount() > 0 ? getChildAt(getChildCount() - 1) : null;
        addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (old == null) {
            return;
        }
        if (direction == 0 || getWidth() == 0) {
            removeView(old);
            return;
        }
        float w = getWidth();
        page.setTranslationX(direction * w);
        page.animate().translationX(0).setDuration(SLIDE_MS);
        old.animate().translationX(-direction * w).setDuration(SLIDE_MS).withEndAction(() -> removeView(old));
    }
}
