package dev.lcdclock;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The WEATHER page's forecast, from Open-Meteo (free, no account) for the phone's approximate
 * location: now, today's range and chance of rain, and the coming hours. Kept for 15 minutes; the
 * place name comes from the phone's geocoder when it has one.
 */
final class Weather {
    private static final long FRESH_MS = 15 * 60 * 1000;
    private static final String CACHE = "weather_json";
    private static final String CACHE_AT = "weather_at";
    private static final String PLACE = "weather_place";
    private static final ExecutorService fetcher = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean fetching;

    /** One hour ahead. */
    static final class Hour {
        long time;
        int temp;
        int code;
        int rain;
    }

    // Now.
    int temp;
    int code;
    boolean day;
    int wind;
    // Today.
    int high;
    int low;
    int rain;
    final List<Hour> hours = new ArrayList<>();
    String place;
    long fetchedAt;

    static boolean allowed(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The forecast kept from last time, or null; fetches a new one in the background when it's stale. */
    static Weather get(Context context, Consumer<Weather> updated) {
        SharedPreferences prefs = Prefs.get(context);
        Weather kept = parse(prefs.getString(CACHE, null), prefs.getLong(CACHE_AT, 0), prefs.getString(PLACE, null));
        if ((kept == null || System.currentTimeMillis() - kept.fetchedAt > FRESH_MS) && allowed(context) && !fetching) {
            fetching = true;
            Context app = context.getApplicationContext();
            fetcher.execute(() -> {
                Weather fresh = fetch(app);
                main.post(() -> {
                    fetching = false;
                    if (fresh != null) {
                        updated.accept(fresh);
                    }
                });
            });
        }
        return kept;
    }

    private static Weather fetch(Context context) {
        Location where = location(context);
        if (where == null) {
            Log.w(Binders.TAG, "weather: no location");
            return null;
        }
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                + "&current=temperature_2m,weather_code,is_day,wind_speed_10m"
                + "&hourly=temperature_2m,weather_code,precipitation_probability"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=2&timeformat=unixtime", where.getLatitude(), where.getLongitude());
        try {
            HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(10_000);
            http.setReadTimeout(10_000);
            String json;
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                }
                json = out.toString("UTF-8");
            } finally {
                http.disconnect();
            }
            String place = placeName(context, where);
            long now = System.currentTimeMillis();
            Weather weather = parse(json, now, place);
            if (weather != null) {
                Prefs.get(context).edit().putString(CACHE, json).putLong(CACHE_AT, now).putString(PLACE, place).apply();
            }
            return weather;
        } catch (IOException | RuntimeException e) {
            Log.w(Binders.TAG, "weather: fetch failed", e);
            return null;
        }
    }

    /** The newest location the phone already has, or a fresh coarse fix. */
    private static Location location(Context context) {
        LocationManager lm = context.getSystemService(LocationManager.class);
        Location best = null;
        try {
            for (String provider : new String[] {LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
                    LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            if (best == null || System.currentTimeMillis() - best.getTime() > 6 * 60 * 60 * 1000) {
                CompletableFuture<Location> fix = new CompletableFuture<>();
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, Runnable::run, fix::complete);
                Location fresh = fix.get(15, TimeUnit.SECONDS);
                if (fresh != null) {
                    best = fresh;
                }
            }
        } catch (SecurityException | IllegalArgumentException | ExecutionException | TimeoutException
                | InterruptedException e) {
            Log.w(Binders.TAG, "weather: location", e);
        }
        return best;
    }

    private static String placeName(Context context, Location where) {
        if (!Geocoder.isPresent()) {
            return null;
        }
        try {
            @SuppressWarnings("deprecation")
            List<Address> found = new Geocoder(context).getFromLocation(where.getLatitude(), where.getLongitude(), 1);
            if (found != null && !found.isEmpty()) {
                Address a = found.get(0);
                return a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
            }
        } catch (IOException | RuntimeException e) {
            Log.w(Binders.TAG, "weather: place name", e);
        }
        return null;
    }

    private static Weather parse(String json, long fetchedAt, String place) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            Weather w = new Weather();
            JSONObject current = root.getJSONObject("current");
            w.temp = (int) Math.round(current.getDouble("temperature_2m"));
            w.code = current.getInt("weather_code");
            w.day = current.getInt("is_day") != 0;
            w.wind = (int) Math.round(current.getDouble("wind_speed_10m"));
            JSONObject daily = root.getJSONObject("daily");
            w.high = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
            w.low = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
            w.rain = daily.getJSONArray("precipitation_probability_max").optInt(0, 0);
            JSONObject hourly = root.getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray rains = hourly.getJSONArray("precipitation_probability");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length(); i++) {
                long t = times.getLong(i) * 1000;
                if (t + 3_600_000 <= now) {
                    continue;
                }
                Hour h = new Hour();
                h.time = t;
                h.temp = (int) Math.round(temps.getDouble(i));
                h.code = codes.getInt(i);
                h.rain = rains.optInt(i, 0);
                w.hours.add(h);
            }
            w.place = place;
            w.fetchedAt = fetchedAt;
            return w;
        } catch (JSONException e) {
            Log.w(Binders.TAG, "weather: bad data", e);
            return null;
        }
    }
}
