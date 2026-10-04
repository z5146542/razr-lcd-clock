package dev.lcdclock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;

import com.motorola.clockface.sdk.options.ClockFaceConfigOptions;
import com.motorola.clockface.sdk.options.ClockFaceTemplateAttributesDefinition;

import java.util.ArrayList;

/**
 * Lists our clock faces to MotoClockFace, which queries every package for a provider
 * with action com.motorola.clockface.action.CLOCK_FACE_OPTIONS and this read permission.
 */
public class LcdOptionsProvider extends ContentProvider {
    private static final String METHOD_LIST_OPTIONS = "list_options";

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_LIST_OPTIONS.equals(method)) {
            return null;
        }
        Context context = getContext();
        ArrayList<Bundle> faces = new ArrayList<>();
        faces.add(face(context, context.getString(R.string.face_digits)));
        Bundle result = new Bundle();
        result.putParcelableArrayList("data", faces);
        return result;
    }

    private static Bundle face(Context context, String name) {
        // Motorola's small-cover-screen thumbnails are 146x77dp.
        float density = context.getResources().getDisplayMetrics().density;
        Icon thumbnail = Icon.createWithBitmap(
                LcdClockView.thumbnail(context, Math.round(146 * density), Math.round(77 * density)));

        ClockFaceConfigOptions.ColorGroup[] presets = new ClockFaceConfigOptions.ColorGroup[Palette.PRESETS.length];
        for (int i = 0; i < presets.length; i++) {
            presets[i] = new ClockFaceConfigOptions.ColorGroup(Palette.PRESETS[i]);
        }
        ClockFaceConfigOptions colors = new ClockFaceConfigOptions.DynamicColorConfigOptions(LcdClockView.TEMPLATE_ID, presets);
        colors.supportCustomizationColor = true;

        Bundle face = new Bundle();
        face.putString("template_id", LcdClockView.TEMPLATE_ID);
        face.putString("name", name);
        face.putParcelable("thumbnail", thumbnail);
        face.putBoolean("ui_type", false);
        face.putParcelable("attributes", new ClockFaceTemplateAttributesDefinition(
                ClockFaceTemplateAttributesDefinition.STYLE_LOCKED, false, false, false, false));
        face.putParcelable("config_options", colors);
        face.putBoolean("render_in_process", false);
        return face;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
