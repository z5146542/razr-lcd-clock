package dev.lcdclock;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The recordings in Recordings, newest first, for the RECORDER page's list and the app's
 * Recordings screen: LCD Clock's own (RecorderService) and any other app's saved there (e.g.
 * Fossify Voice Recorder's). LCD Clock's own it may rename and delete freely;
 * Fossify's it sees with the "music and audio" permission, and changes only after Android asks
 * the user (MediaStore write and delete requests, made by RecordingsActivity).
 */
final class Recordings {
    /** Where LCD Clock's recordings go: the phone's Recordings folder. */
    static final String FOLDER = Environment.DIRECTORY_RECORDINGS;
    /** How LCD Clock and other recorders (Fossify's two) name new recordings: by when they were made. */
    private static final String[] DATED_NAMES = {"'LCD Clock 'yyyy-MM-dd HH-mm-ss", "yyyyMMdd_HHmmss",
            "yyyy_MM_dd_HH_mm_ss"};

    static final class Item {
        Uri uri;
        String fileName;
        long date;
        long length;
        long size;
        /** LCD Clock's own: renamed and deleted without asking. */
        boolean owned;
    }

    private Recordings() {
    }

    static List<Item> list(Context context) {
        List<Item> items = new ArrayList<>();
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] columns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.OWNER_PACKAGE_NAME};
        try (Cursor c = context.getContentResolver().query(collection, columns,
                // Not hidden folders such as Fossify's Recordings/.trash, where deleted recordings wait.
                MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Audio.Media.RELATIVE_PATH + " NOT LIKE ?",
                new String[] {"Recordings/%", "Recordings/.%"},
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext()) {
                Item item = new Item();
                item.uri = ContentUris.withAppendedId(collection, c.getLong(0));
                item.fileName = c.getString(1);
                item.date = c.getLong(2) * 1000;
                item.length = c.getLong(3);
                item.size = c.getLong(4);
                item.owned = context.getPackageName().equals(c.getString(5));
                if (item.length <= 0) {
                    item.length = measure(context, item.uri);
                }
                items.add(item);
            }
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "can't list recordings", e);
        }
        return items;
    }

    /** The length from the file itself, for recordings MediaStore hasn't measured yet. */
    private static long measure(Context context, Uri uri) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(context, uri);
            String ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return ms == null ? 0 : Long.parseLong(ms);
        } catch (RuntimeException | java.io.IOException e) {
            return 0;
        }
    }

    /** A recording's name: its date and time ("Sun 4 Oct, 18:07") until it's renamed. */
    static String title(Context context, Item item) {
        String name = item.fileName == null ? "" : item.fileName.replaceFirst("\\.[A-Za-z0-9]+$", "");
        for (String dated : DATED_NAMES) {
            SimpleDateFormat format = new SimpleDateFormat(dated, Locale.ROOT);
            format.setLenient(false);
            try {
                Date when = format.parse(name);
                if (when != null && format.format(when).equals(name)) {
                    String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(),
                            DateFormat.is24HourFormat(context) ? "EEEdMMMHHmm" : "EEEdMMMhmma");
                    return new SimpleDateFormat(pattern, Locale.getDefault()).format(when);
                }
            } catch (ParseException ignored) {
                // not this naming
            }
        }
        return name; // renamed: its name
    }

    /** "2:05", or "1:02:05". */
    static String length(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    static boolean rename(Context context, Item item, String name) {
        String clean = name.trim().replaceAll("[/\\\\:*?\"<>|]", "-");
        if (clean.isEmpty()) {
            return false;
        }
        String extension = item.fileName != null && item.fileName.contains(".")
                ? item.fileName.substring(item.fileName.lastIndexOf('.')) : ".m4a";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, clean + extension);
        try {
            return context.getContentResolver().update(item.uri, values, null, null) > 0;
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "can't rename " + item.uri, e);
            return false;
        }
    }

    static boolean delete(Context context, Item item) {
        try {
            boolean gone = context.getContentResolver().delete(item.uri, null, null) > 0;
            if (gone && item.uri.toString().equals(Prefs.get(context).getString(Prefs.LAST_RECORDING, null))) {
                Prefs.get(context).edit().remove(Prefs.LAST_RECORDING).remove(Prefs.LAST_RECORDING_MS).apply();
            }
            return gone;
        } catch (RuntimeException e) {
            Log.w(Binders.TAG, "can't delete " + item.uri, e);
            return false;
        }
    }
}
