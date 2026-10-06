package dev.lcdclock;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.app.AlertDialog;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Launcher screen: the face as it looks on the outside screen, a setup checklist (clock style,
 * cover button service, notification access, permissions) with a way to fix each item, a card
 * per cover page (show, order, screen timeout) and the Home Assistant buttons for HOME.
 */
public class PreviewActivity extends Activity {
    private static final int REQUEST_BIND = 1;
    private static final int REQUEST_CONFIGURE = 2;

    private LcdClockView face;
    private AppWidgetHost widgetHost;
    private int pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private int pendingSlot = -1;
    private final LinearLayout[] slotActions = new LinearLayout[HomeAssistant.SLOTS];
    private final TextView[] slotEntities = new TextView[HomeAssistant.SLOTS];
    private LinearLayout setupCard;
    private LinearLayout pageCards;
    private LinearLayout quickRows;
    /** Choices for a page's screen timeout, in ms; the phone's minimum is 8 s. */
    private static final long[] TIMEOUTS = {10_000, 15_000, 20_000, 30_000, 45_000, 60_000, 120_000,
            Prefs.PHONE_TIMEOUT};
    private static final int CARD = 0xFF1C1C1E;
    private static final int OK = 0xFF5BD35B;
    private static final int TO_DO = 0xFFFFB000;
    private float dp;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            face.onTimeChanged();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Notifications (timer, recording), then the RECORDER, CALENDAR and WEATHER pages' access.
        if (!missingPermissions().isEmpty()) {
            askPermissions();
        }
        widgetHost = new AppWidgetHost(this, HomeAssistant.HOST_ID);
        if (savedInstanceState != null) {
            // Recreated while Home Assistant's setup was open.
            pendingSlot = savedInstanceState.getInt("pendingSlot", -1);
            pendingWidgetId = savedInstanceState.getInt("pendingWidgetId", AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * dp);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad * 3, pad, pad * 2);

        TextView title = text(getString(R.string.app_name), 28);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, Math.round(4 * dp));
        column.addView(title);
        column.addView(text(getString(R.string.app_intro), 15));

        // The face as it looks on the outside screen, in its colour, and where that's chosen.
        LinearLayout faceCard = card(column);
        face = new LcdClockView(this, true);
        face.setColor(Prefs.color(this));
        int faceW = getResources().getDisplayMetrics().widthPixels - 2 * pad - 2 * Math.round(14 * dp);
        faceCard.addView(face, new LinearLayout.LayoutParams(faceW, faceW * 194 / 368));
        TextView colourNote = text(getString(R.string.colour_note), 14);
        colourNote.setPadding(0, Math.round(10 * dp), 0, 0);
        faceCard.addView(colourNote);
        faceCard.addView(flatButton(getString(R.string.open_clock_styles), v -> openClockStyles()));
        Button saverColour = flatButton("", null);
        saverColour.setText(saverColourLabel());
        saverColour.setOnClickListener(v -> chooseSaverColour(saverColour));
        faceCard.addView(saverColour);

        // What the outside screen needs, each with a way to fix it (filled in onResume).
        column.addView(header(R.string.setup_title));
        setupCard = card(column);

        // The pages, in swiping order, each with its own settings.
        column.addView(header(R.string.pages_title));
        column.addView(text(getString(R.string.pages_help), 14));
        pageCards = new LinearLayout(this);
        pageCards.setOrientation(LinearLayout.VERTICAL);
        column.addView(pageCards);

        // The face's two panels.
        column.addView(header(R.string.panels_title));
        LinearLayout notices = card(column);
        panelTop(notices, CoverActivity.PAGE_NOTICES, getString(R.string.panel_notices), getString(R.string.panel_notices_help));
        LinearLayout controls = card(column);
        panelTop(controls, CoverActivity.PAGE_CONTROLS, getString(R.string.panel_controls), getString(R.string.panel_controls_help));
        quickRows = new LinearLayout(this);
        quickRows.setOrientation(LinearLayout.VERTICAL);
        controls.addView(quickRows);
        refreshQuick();
        if (!QuickControls.canWriteSecure(this)) {
            TextView secureHelp = text(getString(R.string.quick_secure_help), 13);
            secureHelp.setTextIsSelectable(true);
            secureHelp.setPadding(0, Math.round(8 * dp), 0, 0);
            controls.addView(secureHelp);
        }
        TextView wgTitle = text(getString(R.string.wg_title), 15);
        wgTitle.setTextColor(Color.WHITE);
        wgTitle.setPadding(0, Math.round(12 * dp), 0, Math.round(2 * dp));
        controls.addView(wgTitle);
        controls.addView(text(getString(R.string.wg_help), 13));
        EditText tunnel = input(Prefs.get(this).getString(Prefs.WG_TUNNEL, ""), getString(R.string.wg_tunnel_hint),
                InputType.TYPE_CLASS_TEXT);
        EditText key = input(Prefs.get(this).getString(Prefs.WG_KEY, ""), getString(R.string.wg_key_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        controls.addView(tunnel);
        controls.addView(key);
        controls.addView(flatButton(getString(R.string.wg_save), v -> {
            Prefs.get(this).edit().putString(Prefs.WG_TUNNEL, tunnel.getText().toString().trim())
                    .putString(Prefs.WG_KEY, key.getText().toString().trim()).apply();
            Prefs.setQuickShown(this, QuickControls.WIREGUARD, true);
            refreshQuick();
            Toast.makeText(this, R.string.wg_saved, Toast.LENGTH_SHORT).show();
        }));

        // What holding a volume button does.
        column.addView(header(R.string.holds_title));
        LinearLayout holds = card(column);
        holds.addView(text(getString(R.string.holds_help), 14));
        for (boolean up : new boolean[] {true, false}) {
            Button choice = flatButton(holdLabel(up), null);
            choice.setOnClickListener(v -> chooseHold(up, choice));
            holds.addView(choice);
        }

        // Picture-in-picture through closing the phone (PipGuard).
        column.addView(header(R.string.pip_title));
        LinearLayout pip = card(column);
        Switch keepPip = new Switch(this);
        keepPip.setText(R.string.pip_keep);
        keepPip.setTextColor(Color.WHITE);
        keepPip.setTextSize(16);
        keepPip.setChecked(Prefs.keepPip(this));
        keepPip.setOnCheckedChangeListener((button, on) -> Prefs.get(this).edit().putBoolean(Prefs.KEEP_PIP, on).apply());
        pip.addView(keepPip);
        TextView pipHelp = text(getString(R.string.pip_help), 14);
        pipHelp.setPadding(0, Math.round(8 * dp), 0, 0);
        pip.addView(pipHelp);

        // HOME's three buttons.
        column.addView(header(R.string.ha_title));
        LinearLayout ha = card(column);
        ha.addView(text(getString(R.string.home_help), 14));
        ha.addView(connectionSection());
        for (int slot = 0; slot < HomeAssistant.SLOTS; slot++) {
            ha.addView(slotSection(slot));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(column);
        setContentView(scroll);
        for (int slot = 0; slot < HomeAssistant.SLOTS; slot++) {
            refreshSlot(slot);
        }
    }

    // ---- setup checklist ----

    private void refreshSetup() {
        setupCard.removeAllViews();
        setupRow(LcdSession.isFaceInUse(), R.string.setup_face, R.string.setup_face_fix, R.string.open,
                this::openClockStyles);
        setupRow(coverButtonOn(), R.string.setup_button, R.string.setup_button_fix, R.string.turn_on,
                this::openAccessibility);
        setupRow(Media.allowed(this), R.string.setup_music, R.string.setup_music_fix, R.string.allow,
                this::openNotificationAccess);
        setupRow(missingPermissions().isEmpty(), R.string.setup_permissions, R.string.setup_permissions_fix,
                R.string.allow, this::askPermissions);
    }

    /** ✓ and what's set up, or ! with what it's for and a button to set it up. */
    private void setupRow(boolean ok, int title, int fix, int action, Runnable run) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(6 * dp), 0, Math.round(6 * dp));
        TextView mark = new TextView(this);
        mark.setText(ok ? "✓" : "!");
        mark.setTextSize(20);
        mark.setTextColor(ok ? OK : TO_DO);
        mark.setGravity(Gravity.CENTER);
        row.addView(mark, new LinearLayout.LayoutParams(Math.round(28 * dp), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(getString(title), 16);
        name.setTextColor(Color.WHITE);
        name.setPadding(0, 0, 0, 0);
        words.addView(name);
        if (!ok) {
            TextView why = text(getString(fix), 13);
            why.setPadding(0, Math.round(2 * dp), 0, 0);
            words.addView(why);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!ok) {
            row.addView(flatButton(getString(action), v -> run.run()));
        }
        setupCard.addView(row);
    }

    private boolean coverButtonOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName service = new ComponentName(this, ButtonService.class);
        return enabled != null && (enabled.contains(service.flattenToString()) || enabled.contains(service.flattenToShortString()));
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(this, ButtonService.class).flattenToString()));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private void openClockStyles() {
        try {
            startActivity(new Intent("com.motorola.intent.action.SelectAndConfigClockFace"));
        } catch (ActivityNotFoundException | SecurityException e) {
            startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        }
    }

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : new String[] {android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.READ_MEDIA_AUDIO}) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return missing;
    }

    /** Asks again; once Android stops asking (denied twice), opens the app's settings instead. */
    private void askPermissions() {
        List<String> missing = missingPermissions();
        boolean canAsk = false;
        for (String permission : missing) {
            canAsk |= shouldShowRequestPermissionRationale(permission)
                    || !Prefs.get(this).getBoolean("asked_" + permission, false);
        }
        if (canAsk) {
            for (String permission : missing) {
                Prefs.get(this).edit().putBoolean("asked_" + permission, true).apply();
            }
            requestPermissions(missing.toArray(new String[0]), 0);
        } else {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", getPackageName(), null)));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshSetup();
    }

    // ---- pages ----

    /** The face card, then one card per tab in order. */
    private void refreshPages() {
        pageCards.removeAllViews();
        pageCard(CoverActivity.PAGE_FACE, getString(R.string.page_face), getString(R.string.page_face_help),
                Prefs.faceHome(this), on -> Prefs.get(this).edit().putBoolean(Prefs.FACE_HOME, on).apply(), -1, null);
        List<String> order = Prefs.tabOrder(this);
        String[] names = getResources().getStringArray(R.array.tab_names);
        String[] help = getResources().getStringArray(R.array.tab_help);
        for (int i = 0; i < order.size(); i++) {
            String tab = order.get(i);
            int index = Prefs.TABS.indexOf(tab);
            pageCard(tab, names[index], help[index], Prefs.tabShown(this, tab),
                    on -> Prefs.setTabShown(this, tab, on), i, order);
        }
    }

    /**
     * A page: its tab icon, name and show switch; what it does; its screen timeout; and, for the
     * tabs (`at` >= 0), moving it up or down the order.
     */
    private void pageCard(String page, String name, String help, boolean shown, java.util.function.Consumer<Boolean> onShown,
            int at, List<String> order) {
        LinearLayout card = card(pageCards);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int iconSize = Math.round(40 * dp);
        top.addView(new TabIconView(this, page), new LinearLayout.LayoutParams(iconSize, iconSize));
        TextView title = text(name, 17);
        title.setTextColor(Color.WHITE);
        title.setPadding(Math.round(12 * dp), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch show = new Switch(this);
        show.setChecked(shown);
        show.setContentDescription(getString(R.string.page_show, name));
        show.setOnCheckedChangeListener((button, on) -> onShown.accept(on));
        top.addView(show);
        card.addView(top);

        TextView what = text(help, 13);
        what.setPadding(0, Math.round(8 * dp), 0, 0);
        card.addView(what);
        if (CoverActivity.PAGE_RECORDER.equals(page)) {
            card.addView(flatButton(getString(R.string.recordings_open),
                    v -> startActivity(new Intent(this, RecordingsActivity.class))));
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        Button timeout = flatButton(getString(R.string.cover_timeout_button, timeoutLabel(Prefs.blackout(this, page))), null);
        timeout.setOnClickListener(v -> chooseTimeout(page, name, timeout));
        bottom.addView(timeout, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (at >= 0) {
            for (int step : new int[] {-1, 1}) {
                Button move = flatButton(step < 0 ? "↑" : "↓", null);
                move.setTextSize(20);
                move.setContentDescription(getString(step < 0 ? R.string.tab_up : R.string.tab_down, name));
                move.setEnabled(at + step >= 0 && at + step < order.size());
                move.setOnClickListener(v -> {
                    List<String> moved = new ArrayList<>(order);
                    moved.add(at + step, moved.remove(at));
                    Prefs.setTabOrder(this, moved);
                    refreshPages();
                });
                bottom.addView(move, new LinearLayout.LayoutParams(Math.round(52 * dp), LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
        card.addView(bottom);
    }

    /** A panel card's top: icon, name, what it is, and its screen timeout. */
    private void panelTop(LinearLayout card, String page, String name, String help) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int iconSize = Math.round(40 * dp);
        top.addView(new TabIconView(this, page), new LinearLayout.LayoutParams(iconSize, iconSize));
        TextView title = text(name, 17);
        title.setTextColor(Color.WHITE);
        title.setPadding(Math.round(12 * dp), 0, 0, 0);
        top.addView(title);
        card.addView(top);
        TextView what = text(help, 13);
        what.setPadding(0, Math.round(8 * dp), 0, 0);
        card.addView(what);
        Button timeout = flatButton(getString(R.string.cover_timeout_button, timeoutLabel(Prefs.blackout(this, page))), null);
        timeout.setOnClickListener(v -> chooseTimeout(page, name, timeout));
        card.addView(timeout);
    }

    /** One row per quick control: show switch with its name, then move up and down. */
    private void refreshQuick() {
        quickRows.removeAllViews();
        List<String> order = Prefs.quickOrder(this);
        String[] slots = getResources().getStringArray(R.array.home_slots);
        for (int i = 0; i < order.size(); i++) {
            String control = order.get(i);
            int slot = QuickControls.homeSlot(control);
            String name = slot >= 0 ? getString(R.string.quick_home, slots[slot])
                    : getResources().getStringArray(R.array.quick_names)[QuickControls.ALL.indexOf(control)];
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Switch show = new Switch(this);
            boolean secure = QuickControls.SAVER.equals(control) || QuickControls.NIGHT.equals(control);
            show.setText(QuickControls.usable(this, control) ? name
                    : getString(secure ? R.string.quick_needs_permission : R.string.quick_not_set_up, name));
            show.setTextColor(QuickControls.usable(this, control) ? Color.WHITE : 0xFF9A9A9A);
            show.setTextSize(15);
            show.setChecked(Prefs.quickShown(this, control));
            show.setOnCheckedChangeListener((button, on) -> Prefs.setQuickShown(this, control, on));
            row.addView(show, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            int at = i;
            for (int step : new int[] {-1, 1}) {
                Button move = flatButton(step < 0 ? "↑" : "↓", null);
                move.setTextSize(18);
                move.setContentDescription(getString(step < 0 ? R.string.tab_up : R.string.tab_down, name));
                move.setEnabled(at + step >= 0 && at + step < order.size());
                move.setOnClickListener(v -> {
                    List<String> moved = new ArrayList<>(order);
                    moved.add(at + step, moved.remove(at));
                    Prefs.setQuickOrder(this, moved);
                    refreshQuick();
                });
                row.addView(move, new LinearLayout.LayoutParams(Math.round(48 * dp), LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            quickRows.addView(row);
        }
    }

    private void chooseTimeout(String page, String name, Button button) {
        String[] labels = new String[TIMEOUTS.length];
        int checked = -1;
        for (int i = 0; i < TIMEOUTS.length; i++) {
            labels[i] = timeoutLabel(TIMEOUTS[i]);
            if (TIMEOUTS[i] == Prefs.blackout(this, page)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.timeout_dialog_title, name))
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Prefs.setBlackout(this, page, TIMEOUTS[which]);
                    button.setText(getString(R.string.cover_timeout_button, timeoutLabel(TIMEOUTS[which])));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "Battery saver colour: ● Yellow", the dot in that colour. */
    private CharSequence saverColourLabel() {
        String[] names = getResources().getStringArray(R.array.saver_colour_names);
        int at = 0;
        for (int i = 0; i < Prefs.SAVER_COLORS.length; i++) {
            if (Prefs.SAVER_COLORS[i] == Prefs.saverColor(this)) {
                at = i;
            }
        }
        return TextUtils.concat(getString(R.string.saver_colour_button), "  ", colourDot(Prefs.SAVER_COLORS[at], names[at]));
    }

    private static CharSequence colourDot(int color, String name) {
        SpannableString s = new SpannableString("●  " + name);
        s.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private void chooseSaverColour(Button button) {
        String[] names = getResources().getStringArray(R.array.saver_colour_names);
        CharSequence[] items = new CharSequence[names.length];
        int checked = 0;
        for (int i = 0; i < names.length; i++) {
            items[i] = colourDot(Prefs.SAVER_COLORS[i], names[i]);
            if (Prefs.SAVER_COLORS[i] == Prefs.saverColor(this)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.saver_colour_title)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    Prefs.setSaverColor(this, Prefs.SAVER_COLORS[which]);
                    button.setText(saverColourLabel());
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String holdLabel(boolean up) {
        String[] names = getResources().getStringArray(R.array.hold_names);
        return getString(up ? R.string.hold_up : R.string.hold_down,
                names[Prefs.HOLD_ACTIONS.indexOf(Prefs.holdAction(this, up))]);
    }

    private void chooseHold(boolean up, Button button) {
        String[] names = getResources().getStringArray(R.array.hold_names);
        new AlertDialog.Builder(this)
                .setTitle(up ? R.string.hold_up_title : R.string.hold_down_title)
                .setSingleChoiceItems(names, Prefs.HOLD_ACTIONS.indexOf(Prefs.holdAction(this, up)), (dialog, which) -> {
                    Prefs.setHoldAction(this, up, Prefs.HOLD_ACTIONS.get(which));
                    button.setText(holdLabel(up));
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A cover tab's icon on a black tile, as it looks on the outside screen. */
    private static final class TabIconView extends View {
        private final TabIcons icons;
        private final String page;
        private final RectF box = new RectF();

        TabIconView(Context context, String page) {
            super(context);
            icons = new TabIcons(context);
            this.page = page;
            GradientDrawable tile = new GradientDrawable();
            tile.setColor(Color.BLACK);
            tile.setCornerRadius(10 * context.getResources().getDisplayMetrics().density);
            setBackground(tile);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = getWidth() * 0.18f;
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            icons.draw(canvas, page, box, true);
        }
    }

    // ---- shared pieces ----

    /** A rounded dark card added to `parent`, for one group of settings. */
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(16 * dp);
        card.setBackground(background);
        int inner = Math.round(14 * dp);
        card.setPadding(inner, inner, inner, inner);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(10 * dp);
        parent.addView(card, lp);
        return card;
    }

    private TextView header(int title) {
        TextView header = text(getString(title).toUpperCase(java.util.Locale.getDefault()), 13);
        header.setLetterSpacing(0.08f);
        header.setTextColor(0xFF9A9A9A);
        header.setPadding(Math.round(4 * dp), Math.round(22 * dp), 0, Math.round(8 * dp));
        return header;
    }

    private Button flatButton(String label, View.OnClickListener onClick) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        if (onClick != null) {
            button.setOnClickListener(onClick);
        }
        return button;
    }

    private String timeoutLabel(long ms) {
        if (ms == Prefs.PHONE_TIMEOUT) {
            int phone = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 30_000);
            return getString(R.string.cover_timeout_phone, timeoutLabel(phone));
        }
        return ms < 60_000 ? getString(R.string.seconds, (int) (ms / 1000)) : getString(R.string.minutes, (int) (ms / 60_000));
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            Media.component(this).flattenToString()));
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    /** One HOME button: its icon's name, its Home Assistant actions, and Add action / Test. */
    private View slotSection(int slot) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, Math.round(16 * dp), 0, 0);

        TextView heading = text(getResources().getStringArray(R.array.home_slots)[slot], 16);
        heading.setTextColor(Color.WHITE);
        heading.setPadding(0, 0, 0, Math.round(4 * dp));
        section.addView(heading);

        slotEntities[slot] = text("", 14);
        section.addView(slotEntities[slot]);
        Button choose = new Button(this);
        choose.setText(R.string.ha_choose_entities);
        choose.setOnClickListener(v -> chooseEntities(slot));
        section.addView(choose);
        refreshEntities(slot);

        slotActions[slot] = new LinearLayout(this);
        slotActions[slot].setOrientation(LinearLayout.VERTICAL);
        section.addView(slotActions[slot]);

        LinearLayout buttons = new LinearLayout(this);
        Button add = new Button(this);
        add.setText(R.string.home_add);
        add.setOnClickListener(v -> startHomeAssistantSetup(slot));
        Button test = new Button(this);
        test.setText(R.string.home_test);
        test.setOnClickListener(v -> {
            if (HomeAssistantApi.usesEntities(this, slot)) {
                HomeAssistantApi.toggle(this, slot, () -> { });
            } else if (!HomeAssistant.press(this, slot)) {
                Toast.makeText(this, R.string.home_none, Toast.LENGTH_SHORT).show();
            }
        });
        for (Button b : new Button[] {add, test}) {
            buttons.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        section.addView(buttons);
        return section;
    }

    // ---- Home Assistant connection: server address and long-lived token, for on/off state ----

    private View connectionSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, Math.round(8 * dp), 0, 0);
        TextView heading = text(getString(R.string.ha_connection_title), 16);
        heading.setTextColor(Color.WHITE);
        section.addView(heading);
        section.addView(text(getString(R.string.ha_connection_help), 14));

        EditText server = input(HomeAssistantApi.server(this), getString(R.string.ha_server_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        section.addView(server);
        EditText token = input("", getString(HomeAssistantApi.hasToken(this) ? R.string.ha_token_saved_hint
                : R.string.ha_token_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        section.addView(token);
        TextView status = text(getString(HomeAssistantApi.hasToken(this) ? R.string.ha_token_saved
                : R.string.ha_no_token), 14);
        Button save = new Button(this);
        save.setText(R.string.ha_save_check);
        save.setOnClickListener(v -> {
            HomeAssistantApi.setConnection(this, server.getText().toString(), token.getText().toString());
            token.setText("");
            token.setHint(R.string.ha_token_saved_hint);
            status.setText(R.string.ha_checking);
            HomeAssistantApi.check(this, error -> {
                status.setText(error == null ? getString(R.string.ha_connected)
                        : getString(R.string.ha_check_failed, error));
                for (int slot = 0; slot < HomeAssistant.SLOTS; slot++) {
                    refreshEntities(slot);
                }
            });
        });
        section.addView(save);
        section.addView(status);
        return section;
    }

    private EditText input(String value, String hint, int type) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setHint(hint);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(0xFF777777);
        edit.setSingleLine(true);
        edit.setInputType(type);
        return edit;
    }

    private void refreshEntities(int slot) {
        List<String> ids = HomeAssistantApi.entities(this, slot);
        slotEntities[slot].setText(ids.isEmpty() ? getString(R.string.ha_no_entities)
                : getString(R.string.ha_entities, String.join(", ", ids)));
    }

    /** Picks the entities a button switches and shows the state of, from Home Assistant's list. */
    private void chooseEntities(int slot) {
        if (!HomeAssistantApi.hasToken(this)) {
            Toast.makeText(this, R.string.ha_token_first, Toast.LENGTH_LONG).show();
            return;
        }
        HomeAssistantApi.listEntities(this, found -> {
            if (found == null) {
                Toast.makeText(this, R.string.ha_list_failed, Toast.LENGTH_LONG).show();
                return;
            }
            List<String> current = HomeAssistantApi.entities(this, slot);
            String[] labels = new String[found.size()];
            boolean[] checked = new boolean[found.size()];
            for (int i = 0; i < found.size(); i++) {
                labels[i] = found.get(i)[1] + "  (" + found.get(i)[0] + ")";
                checked[i] = current.contains(found.get(i)[0]);
            }
            new AlertDialog.Builder(this)
                    .setTitle(getResources().getStringArray(R.array.home_slots)[slot])
                    .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        List<String> chosen = new ArrayList<>();
                        for (int i = 0; i < found.size(); i++) {
                            if (checked[i]) {
                                chosen.add(found.get(i)[0]);
                            }
                        }
                        HomeAssistantApi.setEntities(this, slot, chosen);
                        refreshEntities(slot);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    /** Lists a button's actions the way Home Assistant draws them, each with Remove. */
    private void refreshSlot(int slot) {
        LinearLayout list = slotActions[slot];
        list.removeAllViews();
        AppWidgetManager widgets = AppWidgetManager.getInstance(this);
        for (int id : HomeAssistant.widgets(this, slot)) {
            AppWidgetProviderInfo info = widgets.getAppWidgetInfo(id);
            if (info == null) { // gone, e.g. Home Assistant was reinstalled
                HomeAssistant.removeWidget(this, slot, id);
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            AppWidgetHostView view = widgetHost.createView(this, id, info);
            row.addView(view, new LinearLayout.LayoutParams(0, Math.round(90 * dp), 1));
            Button remove = new Button(this);
            remove.setText(R.string.home_remove);
            remove.setOnClickListener(v -> {
                widgetHost.deleteAppWidgetId(id);
                HomeAssistant.removeWidget(this, slot, id);
                refreshSlot(slot);
            });
            row.addView(remove);
            list.addView(row);
        }
        if (list.getChildCount() == 0) {
            list.addView(text(getString(R.string.home_none), 14));
        }
    }

    // ---- adding an action: a Home Assistant Button widget hosted here but never placed anywhere ----

    private void startHomeAssistantSetup(int slot) {
        try {
            getPackageManager().getPackageInfo(HomeAssistant.PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(this, R.string.ha_not_installed, Toast.LENGTH_LONG).show();
            return;
        }
        pendingSlot = slot;
        pendingWidgetId = widgetHost.allocateAppWidgetId();
        if (AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(pendingWidgetId, HomeAssistant.BUTTON_WIDGET)) {
            configurePendingWidget();
        } else {
            // Android asks once whether LCD Clock may create widgets.
            startActivityForResult(new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, HomeAssistant.BUTTON_WIDGET), REQUEST_BIND);
        }
    }

    private void configurePendingWidget() {
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(pendingWidgetId);
        if (info != null && info.configure != null) {
            // Home Assistant's own setup: pick the action and entity for the button.
            widgetHost.startAppWidgetConfigureActivityForResult(this, pendingWidgetId, 0, REQUEST_CONFIGURE, null);
        } else {
            finishHomeAssistantSetup(info != null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BIND) {
            if (resultCode == RESULT_OK) {
                configurePendingWidget();
            } else {
                finishHomeAssistantSetup(false);
            }
        } else if (requestCode == REQUEST_CONFIGURE) {
            finishHomeAssistantSetup(resultCode == RESULT_OK);
        }
    }

    private void finishHomeAssistantSetup(boolean ok) {
        if (pendingWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return;
        }
        if (ok) {
            HomeAssistant.addWidget(this, pendingSlot, pendingWidgetId);
        } else {
            widgetHost.deleteAppWidgetId(pendingWidgetId);
        }
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        refreshSlot(pendingSlot);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("pendingSlot", pendingSlot);
        outState.putInt("pendingWidgetId", pendingWidgetId);
    }

    private TextView text(String s, int sp) {
        TextView view = new TextView(this);
        view.setText(s);
        view.setTextSize(sp);
        view.setTextColor(0xFFCCCCCC);
        view.setPadding(0, 0, 0, Math.round(12 * getResources().getDisplayMetrics().density));
        return view;
    }

    @Override
    protected void onStart() {
        super.onStart();
        widgetHost.startListening();
    }

    @Override
    protected void onStop() {
        super.onStop();
        widgetHost.stopListening();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(tick, filter, Context.RECEIVER_NOT_EXPORTED);
        tick.onReceive(this, null);
        face.setColor(Prefs.color(this));
        refreshSetup();
        refreshPages();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(tick);
    }
}
