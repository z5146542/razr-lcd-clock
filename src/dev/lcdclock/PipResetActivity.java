package dev.lcdclock;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/**
 * A black landscape screen for a moment, so the inner screen really rotates once: that is the only
 * thing that resets SystemUI's picture-in-picture geometry after the phone has been folded (see
 * PipGuard). PipGuard opens it and closes it once the screen has turned.
 */
public class PipResetActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        // Turn at once rather than animate the black screen round.
        attrs.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT;
        getWindow().setAttributes(attrs);
        View black = new View(this);
        black.setBackgroundColor(Color.BLACK);
        setContentView(black);
        PipGuard.resetShown(this);
    }

    /** Closes it without a closing animation. */
    void close() {
        if (!isFinishing()) {
            finish();
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PipGuard.resetGone(this);
    }
}
