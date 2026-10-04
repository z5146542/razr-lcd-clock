package dev.lcdclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * The app's Recordings screen, on the inside screen: every recording in Recordings (Fossify Voice
 * Recorder's and LCD Clock's), newest first, each with play/pause, rename, share and delete, and a
 * way to open the folder in Files. Android confirms changes to Fossify's files itself. Opened from
 * the Recorder card, or the launcher icon's "Recordings" shortcut.
 */
public class RecordingsActivity extends Activity {
    private static final int CARD = 0xFF1C1C1E;
    private static final int REQUEST_DELETE = 1;
    private static final int REQUEST_RENAME = 2;

    private LinearLayout list;
    private float dp;
    private MediaPlayer player;
    private Uri playing;
    /** A rename of another app's recording, waiting for Android's go-ahead. */
    private Recordings.Item renaming;
    private String newName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * dp);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad * 3, pad, pad * 2);

        TextView title = text(getString(R.string.recordings_title), 28, Color.WHITE);
        title.setPadding(0, 0, 0, Math.round(4 * dp));
        column.addView(title);
        column.addView(text(getString(R.string.recordings_help), 15, 0xFFCCCCCC));
        column.addView(button(getString(R.string.recordings_open_folder), v -> openFolder()));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Math.round(12 * dp), 0, 0);
        column.addView(list);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(column);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stop();
    }

    private void refresh() {
        list.removeAllViews();
        List<Recordings.Item> items = Recordings.list(this);
        if (items.isEmpty()) {
            list.addView(text(getString(R.string.recordings_none), 15, 0xFF9A9A9A));
            return;
        }
        for (Recordings.Item item : items) {
            list.addView(card(item));
        }
    }

    /** One recording: its name, length and size, then play, rename, share and delete. */
    private View card(Recordings.Item item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(16 * dp);
        card.setBackground(background);
        int inner = Math.round(14 * dp);
        card.setPadding(inner, inner, inner, Math.round(4 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(10 * dp);
        card.setLayoutParams(lp);

        card.addView(text(Recordings.title(this, item), 17, Color.WHITE));
        card.addView(text(Recordings.length(item.length) + "  ·  " + Formatter.formatShortFileSize(this, item.size),
                13, 0xFF9A9A9A));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        boolean isPlaying = item.uri.equals(playing) && player != null && player.isPlaying();
        row.addView(button(getString(isPlaying ? R.string.recordings_pause : R.string.recordings_play), v -> toggle(item)));
        row.addView(button(getString(R.string.recordings_rename), v -> rename(item)));
        row.addView(button(getString(R.string.recordings_share), v -> share(item)));
        row.addView(button(getString(R.string.recordings_delete), v -> delete(item)));
        card.addView(row);
        return card;
    }

    private void toggle(Recordings.Item item) {
        if (item.uri.equals(playing) && player != null) {
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.start();
            }
        } else {
            stop();
            MediaPlayer p = new MediaPlayer();
            try {
                p.setDataSource(this, item.uri);
                p.prepare();
                p.setOnCompletionListener(done -> {
                    stop();
                    refresh();
                });
                p.start();
                player = p;
                playing = item.uri;
            } catch (IOException | RuntimeException e) {
                Log.w(Binders.TAG, "can't play " + item.uri, e);
                p.release();
                Toast.makeText(this, R.string.recordings_cant_play, Toast.LENGTH_SHORT).show();
            }
        }
        refresh();
    }

    private void stop() {
        if (player != null) {
            player.release();
            player = null;
        }
        playing = null;
    }

    private void rename(Recordings.Item item) {
        EditText name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(item.fileName == null ? "" : item.fileName.replaceFirst("\\.m4a$", ""));
        name.selectAll();
        new AlertDialog.Builder(this)
                .setTitle(R.string.recordings_rename)
                .setView(name)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (!item.owned) {
                        // Another app's file: Android asks first, then onActivityResult renames it.
                        renaming = item;
                        newName = name.getText().toString();
                        ask(MediaStore.createWriteRequest(getContentResolver(), Collections.singletonList(item.uri)),
                                REQUEST_RENAME);
                        return;
                    }
                    if (!Recordings.rename(this, item, name.getText().toString())) {
                        Toast.makeText(this, R.string.recordings_cant_rename, Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void share(Recordings.Item item) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("audio/*")
                .putExtra(Intent.EXTRA_STREAM, item.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.recordings_share)));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.recordings_cant_share, Toast.LENGTH_SHORT).show();
        }
    }

    private void delete(Recordings.Item item) {
        if (!item.owned) {
            // Another app's file: Android's own confirmation deletes it.
            if (item.uri.equals(playing)) {
                stop();
            }
            ask(MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(item.uri)), REQUEST_DELETE);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.recordings_delete_title, Recordings.title(this, item)))
                .setMessage(R.string.recordings_delete_message)
                .setPositiveButton(R.string.recordings_delete, (dialog, which) -> {
                    if (item.uri.equals(playing)) {
                        stop();
                    }
                    if (!Recordings.delete(this, item)) {
                        Toast.makeText(this, R.string.recordings_cant_delete, Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void ask(PendingIntent request, int code) {
        try {
            startIntentSenderForResult(request.getIntentSender(), code, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException e) {
            Log.w(Binders.TAG, "can't ask about a recording", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_RENAME && resultCode == RESULT_OK && renaming != null) {
            if (!Recordings.rename(this, renaming, newName)) {
                Toast.makeText(this, R.string.recordings_cant_rename, Toast.LENGTH_SHORT).show();
            }
        }
        renaming = null;
        refresh();
    }

    /** The folder in the Files app, where it can take a folder link; otherwise says where it is. */
    private void openFolder() {
        Uri folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Recordings");
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.recordings_folder_is, "Recordings"), Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(s);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, 0, 0, Math.round(4 * dp));
        return view;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setOnClickListener(onClick);
        return button;
    }
}
