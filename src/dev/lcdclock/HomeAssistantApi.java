package dev.lcdclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to Home Assistant's REST API with a long-lived access token, so the HOME buttons can
 * show whether their entities are on and switch them together. The token lives in
 * credential-encrypted app storage, is never logged, and only goes to an https:// address.
 */
final class HomeAssistantApi {
    /** Entity domains offered in the picker; all have an on/off state. */
    private static final List<String> DOMAINS = Arrays.asList("switch", "light", "fan", "input_boolean");

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Last known state per entity: true = on. Missing = not known. */
    private static final Map<String, Boolean> STATES = new ConcurrentHashMap<>();

    private HomeAssistantApi() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("home_assistant", Context.MODE_PRIVATE);
    }

    /** The server's address as entered in the app, or "" before it's set. */
    static String server(Context context) {
        return prefs(context).getString("server", "");
    }

    static boolean hasToken(Context context) {
        return !prefs(context).getString("token", "").isEmpty();
    }

    static void setConnection(Context context, String server, String token) {
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString("server", server.trim().replaceAll("/+$", ""));
        if (!token.trim().isEmpty()) {
            editor.putString("token", token.trim());
        }
        editor.apply();
    }

    static List<String> entities(Context context, int slot) {
        String saved = prefs(context).getString("entities_" + slot, "");
        return saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(",")));
    }

    static void setEntities(Context context, int slot, List<String> ids) {
        prefs(context).edit().putString("entities_" + slot, String.join(",", ids)).apply();
    }

    /** True when this button switches entities directly (otherwise it taps widgets). */
    static boolean usesEntities(Context context, int slot) {
        return hasToken(context) && !entities(context, slot).isEmpty();
    }

    /** On if any of the button's entities is on; null when not known. */
    static Boolean slotState(Context context, int slot) {
        if (!usesEntities(context, slot)) {
            return null;
        }
        boolean anyOn = false;
        for (String id : entities(context, slot)) {
            Boolean on = STATES.get(id);
            if (on == null) {
                return null;
            }
            anyOn |= on;
        }
        return anyOn;
    }

    /** Fetches every button's entity states, then runs `done` on the main thread. */
    static void refresh(Context context, Runnable done) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            for (int slot = 0; slot < HomeAssistant.SLOTS; slot++) {
                if (!usesEntities(app, slot)) {
                    continue;
                }
                for (String id : entities(app, slot)) {
                    try {
                        JSONObject state = new JSONObject(request(app, "GET",
                                "/api/states/" + URLEncoder.encode(id, "UTF-8"), null));
                        STATES.put(id, "on".equals(state.optString("state")));
                    } catch (Exception e) {
                        STATES.remove(id);
                        Log.w(Binders.TAG, "state of " + id + ": " + e.getMessage());
                    }
                }
            }
            MAIN.post(done);
        });
    }

    /**
     * Switches a button's entities together: all off if any is on, otherwise all on. Shows the
     * new state straight away, then confirms it from Home Assistant. Does nothing while the phone
     * is locked.
     */
    static void toggle(Context context, int slot, Runnable done) {
        Context app = context.getApplicationContext();
        if (Lock.locked(app)) {
            Log.d(Binders.TAG, "home button " + slot + ": locked, not switching");
            return;
        }
        List<String> ids = entities(app, slot);
        boolean turnOn = !Boolean.TRUE.equals(slotState(app, slot));
        for (String id : ids) {
            STATES.put(id, turnOn);
        }
        IO.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("entity_id", new JSONArray(ids));
                request(app, "POST", "/api/services/homeassistant/" + (turnOn ? "turn_on" : "turn_off"),
                        body.toString());
                Log.d(Binders.TAG, "home button " + slot + ": turned " + (turnOn ? "on" : "off"));
            } catch (Exception e) {
                Log.w(Binders.TAG, "home button " + slot + ": " + e.getMessage());
            }
        });
        MAIN.post(done);
        MAIN.postDelayed(() -> refresh(app, done), 800);
    }

    /** Checks the server and token; returns null if fine, otherwise what went wrong. Runs on IO. */
    static void check(Context context, java.util.function.Consumer<String> result) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            String error;
            try {
                request(app, "GET", "/api/", null);
                error = null;
            } catch (Exception e) {
                error = e.getMessage();
            }
            String finalError = error;
            MAIN.post(() -> result.accept(finalError));
        });
    }

    /** Lists switchable entities as {id, friendly name}, sorted by name; null on failure. */
    static void listEntities(Context context, java.util.function.Consumer<List<String[]>> result) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            List<String[]> found = new ArrayList<>();
            try {
                JSONArray states = new JSONArray(request(app, "GET", "/api/states", null));
                for (int i = 0; i < states.length(); i++) {
                    JSONObject s = states.getJSONObject(i);
                    String id = s.getString("entity_id");
                    if (DOMAINS.contains(id.substring(0, id.indexOf('.')))) {
                        String name = s.optJSONObject("attributes") == null ? id
                                : s.getJSONObject("attributes").optString("friendly_name", id);
                        found.add(new String[] {id, name});
                    }
                }
                found.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
            } catch (Exception e) {
                Log.w(Binders.TAG, "listing entities: " + e.getMessage());
                found = null;
            }
            List<String[]> finalFound = found;
            MAIN.post(() -> result.accept(finalFound));
        });
    }

    private static String request(Context context, String method, String path, String body) throws IOException {
        if (server(context).isEmpty()) {
            throw new IOException("no server address");
        }
        // The token gives full control of Home Assistant: never send it unencrypted.
        if (!server(context).regionMatches(true, 0, "https://", 0, 8)) {
            throw new IOException("the address must start with https://");
        }
        HttpURLConnection http = (HttpURLConnection) new URL(server(context) + path).openConnection();
        try {
            http.setRequestMethod(method);
            http.setConnectTimeout(5000);
            http.setReadTimeout(5000);
            http.setRequestProperty("Authorization", "Bearer " + prefs(context).getString("token", ""));
            http.setRequestProperty("Content-Type", "application/json");
            if (body != null) {
                http.setDoOutput(true);
                try (OutputStream out = http.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = http.getResponseCode();
            if (code == 401) {
                throw new IOException("the token was rejected (401)");
            }
            if (code >= 300) {
                throw new IOException("Home Assistant answered " + code);
            }
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    bytes.write(buffer, 0, n);
                }
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            http.disconnect();
        }
    }
}
