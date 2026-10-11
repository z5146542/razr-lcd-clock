package dev.lcdclock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * The outside-screen pages: FACE (LCD Clock's own copy of the clock face, while it's the chosen
 * face and the app's setting allows) first, then in the order chosen in the app (Prefs.tabOrder),
 * leaving out hidden ones: TIMER, ALARM, CALENDAR,
 * WEATHER, RECORDER, HOME with the Home Assistant buttons (only once set up) and a
 * MUSIC page per app with a media session, its tab showing the app's icon (opened by Media when
 * music plays; a plain MUSIC page when there's none). A hidden page asked for directly (a volume
 * hold, chosen in the app, or a ringing alarm) still opens, without a tab. Swiping slides between them under the menu
 * row (CoverTabs), which stays put; tapping a tab switches straight to its page.
 *
 * <p>With FACE, the pages go round: a swipe left from the face reaches the first tab, a swipe right
 * the last. A swipe up on the face opens the notifications panel, down the quick controls (whose
 * MOTOROLA tile closes LCD Clock, uncovering Motorola's face and panels); a sideways swipe on a
 * panel (or a drag back the way it came) goes back to the face, and elsewhere the face's clock tab
 * does. There are no X buttons. Without FACE, swiping past the first or last page closes LCD Clock. The activity then stays on the cover across sleeps,
 * reset to FACE (or the playing app's MUSIC page), so it's already there when the screen wakes;
 * it closes as soon as the phone opens.
 *
 * <p>When the alarm or the timer rings, its page opens, and every wake goes back to it while it
 * rings; the other pages stay a swipe or a tab away, and the screen stays on until it stops.
 */
public class CoverActivity extends Activity {
    static final String EXTRA_PAGE = "page";
    static final String PAGE_FACE = "face";
    /** TIMER; the name predates the ALARM page. */
    static final String PAGE_CLOCK = "clock";
    static final String PAGE_ALARM = "alarm";
    static final String PAGE_HOME = "home";
    static final String PAGE_CALENDAR = "calendar";
    static final String PAGE_WEATHER = "weather";
    static final String PAGE_RECORDER = "recorder";
    /** The plain MUSIC page; a player's page is musicPage(its Media key). */
    static final String PAGE_MUSIC = "music";
    /** The face's panels, not tabs: swipe up for notifications, down for quick controls. */
    static final String PAGE_NOTICES = "notices";
    static final String PAGE_CONTROLS = "controls";

    /** Resumed, so Media doesn't switch to MUSIC under someone using another page. */
    private static boolean showing;
    /** The page on screen, for Media (MUSIC may replace FACE, not the others). */
    private static String shownPage;

    private SwipeFrame frame;
    private CoverTabs tabs;
    private String page;
    /** Stopped with the phone closed: no page meanwhile, the home page again on the next start. */
    private boolean asleep;

    /**
     * Players come and go: keep one tab each, and move from a page whose player has gone to the
     * nearest page still there, looking left first.
     */
    private final Runnable mediaChanged = () -> {
        if (page == null) {
            return;
        }
        List<String> current = pages();
        if (current.contains(page)) {
            tabs.show(current, page);
            return;
        }
        List<String> before = tabs.pages();
        int at = before.indexOf(page);
        for (int step = 1; at >= 0 && step < before.size(); step++) {
            for (int i : new int[] {at - step, at + step}) {
                if (i >= 0 && i < before.size() && current.contains(before.get(i))) {
                    open(before.get(i), 0);
                    return;
                }
            }
        }
        open(page, 0);
    };

    /** The phone opening (inner screen on): the cover pages are done. */
    private final DisplayManager.DisplayListener innerScreen = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY && !CoverScreen.isPhoneClosed(CoverActivity.this)
                    && !ringing(CoverActivity.this)) {
                finish();
            }
        }
    };

    static String musicPage(String key) {
        return key == null ? PAGE_MUSIC : PAGE_MUSIC + ":" + key;
    }

    static boolean isPanel(String page) {
        return PAGE_NOTICES.equals(page) || PAGE_CONTROLS.equals(page);
    }

    /** Pages with no menu row: the face, which looks like Motorola's, and its panels, which have their own. */
    static boolean hidesMenu(String page) {
        return PAGE_FACE.equals(page) || isPanel(page);
    }

    static boolean isMusic(String page) {
        return page.equals(PAGE_MUSIC) || page.startsWith(PAGE_MUSIC + ":");
    }

    /** LCD Clock's own face is the first page: it's the chosen face, and the app's setting allows. */
    static boolean hasFace(Context context) {
        return Prefs.faceHome(context) && LcdSession.isFaceInUse();
    }

    /** ALARM or TIMER while one of them rings, otherwise null. */
    static String ringingPage(Context context) {
        if (AlarmController.state(context) == AlarmController.State.RINGING) {
            return PAGE_ALARM;
        }
        return TimerController.state(context) == TimerController.State.RINGING ? PAGE_CLOCK : null;
    }

    /**
     * The page to wake to: whatever is ringing, RECORDER while recording, the playing app's MUSIC
     * page, else our face; or null.
     */
    static String homePage(Context context) {
        String ringing = ringingPage(context);
        if (ringing != null) {
            return ringing;
        }
        if (RecorderService.isRecording()) {
            return PAGE_RECORDER;
        }
        String key = Media.wakeKey();
        if (key != null && Prefs.tabShown(context, PAGE_MUSIC)) {
            return musicPage(key);
        }
        return hasFace(context) ? PAGE_FACE : null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        frame = new SwipeFrame(this, new SwipeFrame.Listener() {
            @Override
            public void onSwipe(int direction) {
                swipe(direction);
            }

            @Override
            public void onVerticalSwipe(int direction) {
                Log.d(Binders.TAG, "vertical swipe " + direction + " on " + page);
                if (PAGE_FACE.equals(page)) {
                    // Up: notifications from below; down: quick controls from above.
                    openVertical(direction > 0 ? PAGE_NOTICES : PAGE_CONTROLS, direction);
                }
            }
        });
        tabs = new CoverTabs(this, new CoverTabs.Listener() {
            @Override
            public void onTab(String page) {
                if (PAGE_FACE.equals(page) && isMusic(CoverActivity.this.page)) {
                    Media.dismiss(); // wake to the face, not MUSIC, until playback next starts
                }
                open(page, 0);
            }
        });
        FrameLayout root = new FrameLayout(this);
        root.addView(frame);
        root.addView(tabs);
        setContentView(root);
        Media.listen(mediaChanged);
        getSystemService(DisplayManager.class).registerDisplayListener(innerScreen, null);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
        show(getIntent());
    }

    @Override
    protected void onDestroy() {
        shownPage = null;
        Media.unlisten(mediaChanged);
        getSystemService(DisplayManager.class).unregisterDisplayListener(innerScreen);
        unregisterReceiver(screenOff);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        String requested = intent.getStringExtra(EXTRA_PAGE);
        open(requested == null ? PAGE_CLOCK : requested, 0);
    }

    /** The pages in order. */
    private List<String> pages() {
        List<String> pages = new ArrayList<>();
        if (hasFace(this)) {
            pages.add(PAGE_FACE);
        }
        for (String tab : Prefs.tabOrder(this)) {
            if (!Prefs.tabShown(this, tab)) {
                continue;
            }
            if (PAGE_MUSIC.equals(tab)) {
                List<Media.Player> players = Media.players(this);
                if (players.isEmpty()) {
                    pages.add(PAGE_MUSIC);
                }
                for (Media.Player player : players) {
                    pages.add(musicPage(player.key));
                }
            } else if (!PAGE_HOME.equals(tab) || HomeAssistant.hasAnyAction(this)) {
                pages.add(tab);
            }
        }
        return pages;
    }

    private void swipe(int direction) {
        if (isPanel(page)) {
            open(PAGE_FACE, direction); // sideways off a panel: back to the face
            return;
        }
        List<String> pages = pages();
        int at = pages.indexOf(page);
        if (at < 0) {
            // A page without a tab (hidden, but opened by a hold or a ring): on to either end.
            if (!pages.isEmpty()) {
                open(pages.get(direction > 0 ? 0 : pages.size() - 1), direction);
            }
            return;
        }
        int next = at + direction;
        if (pages.contains(PAGE_FACE)) {
            next = Math.floorMod(next, pages.size()); // round, through the face
        }
        if (next >= 0 && next < pages.size()) {
            open(pages.get(next), direction);
        } else {
            finish(); // no face page: past either end is the way back to Motorola's screen
        }
    }

    /**
     * Shows a page. A player's page that has gone falls back to another player's, then the first
     * page; FACE once another face is chosen, to the first page. Other pages open even when
     * hidden (or HOME before it's set up), just without a tab.
     */
    private void open(String next, int direction) {
        frame.show(prepare(next), direction);
        applyTimeout();
    }

    /** Like open, sliding up (+1) or down (-1): the face's panels. */
    private void openVertical(String next, int direction) {
        frame.showVertical(prepare(next), direction);
        applyTimeout();
    }

    /** Back from a panel to the face, the panel sliding away the way it came. */
    private void closePanel() {
        openVertical(PAGE_FACE, PAGE_NOTICES.equals(page) ? -1 : 1);
    }

    private View prepare(String next) {
        Log.d(Binders.TAG, "page " + page + " -> " + next);
        List<String> current = pages();
        if (!current.contains(next) && (isMusic(next) || PAGE_FACE.equals(next))) {
            String fallback = current.isEmpty() ? PAGE_CLOCK : current.get(0);
            if (isMusic(next)) {
                for (String candidate : current) {
                    if (isMusic(candidate)) {
                        fallback = candidate;
                        break;
                    }
                }
            }
            next = fallback;
        }
        page = next;
        shownPage = next;
        View view;
        if (PAGE_FACE.equals(next)) {
            LcdClockView face = new LcdClockView(this, false);
            face.setColor(Prefs.color(this));
            FrameLayout withDot = new FrameLayout(this);
            withDot.addView(face);
            withDot.addView(new NoticeDot(this));
            // No touch handling here: SwipeFrame sees vertical swipes only on pages that take no touches.
            view = withDot;
        } else if (PAGE_NOTICES.equals(next)) {
            view = new NotificationsView(this, this::closePanel);
        } else if (PAGE_CONTROLS.equals(next)) {
            view = new QuickView(this, this::closePanel, this::finish);
        } else if (PAGE_HOME.equals(next)) {
            view = new HomeView(this);
        } else if (PAGE_CALENDAR.equals(next)) {
            view = new CalendarView(this);
        } else if (PAGE_WEATHER.equals(next)) {
            view = new WeatherView(this);
        } else if (PAGE_RECORDER.equals(next)) {
            view = new RecorderView(this);
        } else if (isMusic(next)) {
            view = new MusicView(this, next.equals(PAGE_MUSIC) ? null : next.substring(PAGE_MUSIC.length() + 1));
        } else {
            view = new CoverView(this, PAGE_ALARM.equals(next));
        }
        tabs.show(current, next);
        return view;
    }

    /**
     * Sets the screen timeout to the page's own, chosen in the app (Prefs.blackout); the phone's
     * own while something rings. Touches reset it as usual. The window field for this is hidden
     * from apps, hence reflection.
     */
    private void applyTimeout() {
        boolean ringing = ringing(this);
        // While ringing the screen stays on: turning off would also cancel the vibration.
        frame.removeCallbacks(ringWatch);
        if (ringing) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            frame.postDelayed(ringWatch, 1000);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        long timeout = ringing ? Prefs.PHONE_TIMEOUT : Prefs.blackout(this, isMusic(page) ? PAGE_MUSIC : page);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        try {
            WindowManager.LayoutParams.class.getField("userActivityTimeout").setLong(attrs, timeout);
            getWindow().setAttributes(attrs);
            Log.d(Binders.TAG, "screen timeout for " + page + ": " + timeout);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(Binders.TAG, "can't set the screen timeout", e);
        }
    }

    /**
     * The screen went off with a page up, most often its own timeout: lock now. Android would
     * otherwise wait for its "lock after screen timeout" (5 minutes, say), as it does after its own
     * timeout, leaving the closed phone unlocked in the meantime.
     */
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ringing(context) && CoverScreen.isPhoneClosed(context) && !Lock.locked(context)) {
                Log.d(Binders.TAG, "lock: screen off on the cover, locking");
                ButtonService.lockScreen();
            }
        }
    };

    /** Puts the page's own timeout back once the ringing stops. */
    private final Runnable ringWatch = this::applyTimeout;

    static boolean isShowing() {
        return showing;
    }

    static boolean isOnFace() {
        return PAGE_FACE.equals(shownPage);
    }

    static boolean ringing(Context context) {
        return TimerController.state(context) == TimerController.State.RINGING
                || AlarmController.state(context) == AlarmController.State.RINGING;
    }

    @Override
    protected void onStart() {
        super.onStart();
        String ringingPage = ringingPage(this);
        if (asleep) {
            asleep = false;
            String home = homePage(this);
            open(home != null ? home : PAGE_FACE, 0);
        } else if (ringingPage != null && !ringingPage.equals(page)) {
            open(ringingPage, 0); // woken while ringing: back to the ringing page
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Display display = getDisplay();
        if (display != null && display.getDisplayId() != CoverScreen.DISPLAY_ID) {
            finish(); // never on the inner screen, e.g. if the phone opened while we were stopped
            return;
        }
        if (PAGE_FACE.equals(page) && !hasFace(this)) {
            finish(); // another clock face was chosen while we waited
            return;
        }
        showing = true;
        // Covering the lock screen stops Android listening to the fingerprint sensor; asking to
        // unlock turns it back on (the prompt goes to the inside screen), so a touch unlocks as on
        // Motorola's own face. Asked again on each wake, once the page is up.
        getWindow().getDecorView().postDelayed(() -> {
            if (showing && Lock.locked(this)) {
                Lock.requestUnlock(this, null);
            }
        }, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        showing = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing() || ringing(this)) {
            return; // leaving anyway, or staying until the ringing is stopped
        }
        String home = CoverScreen.isPhoneClosed(this) ? homePage(this) : null;
        if (home != null && hasFace(this)) {
            // Screen off with the phone closed: stay, so the face (or MUSIC) is there as soon as it
            // wakes, but drop the page meanwhile so nothing on it keeps ticking in the dark.
            asleep = true;
            frame.removeAllViews();
        } else {
            // Back to Motorola's clock face next time the screen wakes.
            finish();
        }
    }
}
