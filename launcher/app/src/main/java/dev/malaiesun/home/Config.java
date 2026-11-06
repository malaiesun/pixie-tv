package dev.malaiesun.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the user can change without rebuilding. Lives at
 * /sdcard/Android/data/dev.malaiesun.home/files/config.json; push a new one with adb and Home reloads.
 */
final class Config {
    static final String FILE = "config.json";

    static final class Chip {
        String entity = "", label = "", format = "", tap = "toggle";
    }

    static final class ScheduleEntry {
        int from; String preset;
    }

    // Theme (see Theme.resolve): preset + optional mix, schedule, rotation and color overrides
    String themePreset = "aurora", themeBackground = "", themeLogo = "", themeRotationMode = "off";
    List<String> themeRotation = new ArrayList<>();
    final List<ScheduleEntry> themeSchedule = new ArrayList<>();
    JSONObject themeOverrides;
    String wallpaper = "";
    float wallpaperDim = 0.45f;
    String mascot = "on";               // on (every theme) | auto (Retro background only) | off
    String launchAnimation = "theme";   // theme (per-theme effect) | system (zoom from tile) | off

    // Layout
    boolean clock24h = false;
    String greeting = "";
    int favTileDp = 196;
    int columns = 6;
    List<String> rows = Arrays.asList("favorites", "inputs", "recent", "apps");
    List<String> favorites = new ArrayList<>();
    Set<String> hidden = new LinkedHashSet<>();
    String liveTvPackage = "com.smartdevice.livetv";
    int recentCount = 8;

    // Home Assistant
    String haUrl = "", haToken = "";
    int haPollSeconds = 30;
    final List<Chip> chips = new ArrayList<>();
    final List<Chip> panel = new ArrayList<>();     // Home Assistant control panel (Settings button hold)

    // Agent (accessibility service + LAN API)
    int agentPort = 8765;
    boolean volumeOverlay = true;
    int volumeStep = 1;
    String powerEntity = "media_player.pixie_2", assistLanguage = "en";

    long mtime = -1;
    String error;

    static File file(Context c) {
        return new File(c.getExternalFilesDir(null), FILE);
    }

    boolean haReady() {
        return !haUrl.isEmpty() && !haToken.isEmpty() && !chips.isEmpty();
    }

    static Config load(Context c) {
        Config cfg = new Config();
        File f = file(c);
        try {
            if (!f.exists()) writeDefault(c, f);
            cfg.mtime = f.lastModified();
            cfg.parse(new JSONObject(read(new FileInputStream(f))));
        } catch (Exception e) {
            cfg.error = "config.json: " + e.getMessage();
            try { cfg.parse(new JSONObject(read(c.getResources().openRawResource(R.raw.default_config)))); }
            catch (Exception ignored) { }
        }
        return cfg;
    }

    private void parse(JSONObject j) {
        themePreset = j.optString("theme_preset", themePreset);
        themeBackground = j.optString("theme_background", "");
        themeLogo = j.optString("theme_logo", "");
        themeOverrides = j.optJSONObject("theme_overrides");
        JSONObject rot = j.optJSONObject("theme_rotation");
        if (rot != null) {
            themeRotationMode = rot.optString("mode", "off");
            themeRotation = strings(rot.optJSONArray("presets"), themeRotation);
        }
        JSONArray sch = j.optJSONArray("theme_schedule");
        if (sch != null) for (int i = 0; i < sch.length(); i++) {
            JSONObject o = sch.optJSONObject(i);
            if (o == null) continue;
            ScheduleEntry e = new ScheduleEntry();
            e.from = dev.malaiesun.home.rules.RuleEngine.parse(o.optString("from"));
            e.preset = o.optString("preset", "");
            if (e.from >= 0 && !e.preset.isEmpty()) themeSchedule.add(e);
        }
        JSONObject legacy = j.optJSONObject("theme");   // v0.1 key: only the wallpaper part still applies
        if (legacy != null) {
            wallpaper = legacy.optString("wallpaper", wallpaper);
            wallpaperDim = (float) legacy.optDouble("wallpaper_dim", wallpaperDim);
        }
        wallpaper = j.optString("wallpaper", wallpaper);
        mascot = j.optString("mascot", mascot);
        launchAnimation = j.optString("launch_animation", launchAnimation);
        wallpaperDim = (float) j.optDouble("wallpaper_dim", wallpaperDim);
        clock24h = j.optBoolean("clock_24h", clock24h);
        greeting = j.optString("greeting", greeting);
        favTileDp = clamp(j.optInt("favorite_tile_width_dp", favTileDp), 120, 400);
        columns = clamp(j.optInt("columns", columns), 3, 10);
        rows = strings(j.optJSONArray("rows"), rows);
        favorites = new ArrayList<>(strings(j.optJSONArray("favorites"), favorites));
        hidden = new LinkedHashSet<>(strings(j.optJSONArray("hidden"), new ArrayList<>(hidden)));
        liveTvPackage = j.optString("live_tv_package", liveTvPackage);
        recentCount = clamp(j.optInt("recent_count", recentCount), 0, 20);

        JSONObject h = j.optJSONObject("home_assistant");
        if (h != null) {
            haUrl = h.optString("url", "").replaceAll("/+$", "");
            haToken = h.optString("token", "");
            haPollSeconds = clamp(h.optInt("poll_seconds", haPollSeconds), 5, 3600);
            chips(h.optJSONArray("chips"), chips);
            chips(h.optJSONArray("panel"), panel);
        }
        if (panel.isEmpty()) panel.addAll(chips);
        JSONObject ag = j.optJSONObject("agent");
        if (ag != null) {
            agentPort = clamp(ag.optInt("port", agentPort), 1024, 65535);
            volumeOverlay = ag.optBoolean("volume_overlay", volumeOverlay);
            volumeStep = clamp(ag.optInt("volume_step", volumeStep), 1, 20);
            powerEntity = ag.optString("power_entity", powerEntity);
            assistLanguage = ag.optString("assist_language", assistLanguage);
        }
    }

    private static void chips(JSONArray cs, List<Chip> out) {
        if (cs == null) return;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject o = cs.optJSONObject(i);
            if (o == null || o.optString("entity").isEmpty()) continue;
            Chip ch = new Chip();
            ch.entity = o.optString("entity");
            ch.label = o.optString("label", "");
            ch.format = o.optString("format", "");
            boolean readOnly = ch.entity.startsWith("weather.") || ch.entity.startsWith("sensor.")
                    || ch.entity.startsWith("binary_sensor.") || ch.entity.startsWith("person.");
            ch.tap = o.optString("tap", readOnly ? "none" : "toggle");
            out.add(ch);
        }
    }

    /** Sets one top-level key in config.json (null removes it), keeping everything else. */
    static void put(Context c, String key, Object value) throws Exception {
        File f = file(c);
        JSONObject j = f.exists() ? new JSONObject(read(new FileInputStream(f))) : new JSONObject();
        if (value == null) j.remove(key); else j.put(key, value);
        write(f, j.toString(2).replace("\\/", "/"));
    }

    static final String[] MASCOT_MODES = {"on", "auto", "off"};
    static final String[] LAUNCH_MODES = {"theme", "system", "off"};

    static String mascotLabel(String m) {
        return "auto".equals(m) ? "Retro only" : "off".equals(m) ? "Off" : "Every theme";
    }

    static String launchLabel(String m) {
        return "system".equals(m) ? "Zoom from tile" : "off".equals(m) ? "Off" : "Theme effect";
    }

    static String nextOf(String[] modes, String cur) {
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(cur)) return modes[(i + 1) % modes.length];
        return modes[0];
    }

    /**
     * One theme choice, from any menu or the API: a preset id, "rotate" (all themes, daily),
     * "daynight" (Aurora by day, Neon Grid after 19:00) or "mix" (Retro background + Comic logo).
     */
    static void applyTheme(Context c, String choice) throws Exception {
        put(c, "theme_rotation", null);
        put(c, "theme_schedule", null);
        put(c, "theme_background", null);
        put(c, "theme_logo", null);
        switch (choice) {
            case "rotate": {
                JSONArray all = new JSONArray();
                for (String id : Theme.IDS) if (!id.equals("minimal")) all.put(id);
                put(c, "theme_rotation", new JSONObject().put("mode", "daily").put("presets", all));
                break;
            }
            case "daynight":
                put(c, "theme_schedule", new JSONArray()
                        .put(new JSONObject().put("from", "06:00").put("preset", "aurora"))
                        .put(new JSONObject().put("from", "19:00").put("preset", "neon")));
                break;
            case "mix":
                put(c, "theme_preset", "retro");
                put(c, "theme_logo", "comic");
                break;
            default:
                put(c, "theme_preset", choice);
                break;
        }
    }

    /** Writes favorites + hidden back into config.json, leaving every other key as the user wrote it. */
    void saveLists(Context c) throws Exception {
        File f = file(c);
        JSONObject j = f.exists() ? new JSONObject(read(new FileInputStream(f))) : new JSONObject();
        j.put("favorites", new JSONArray(favorites));
        j.put("hidden", new JSONArray(new ArrayList<>(hidden)));
        write(f, j.toString(2).replace("\\/", "/"));
    }

    Bitmap loadWallpaper(Context c, int maxW) {
        if (wallpaper.isEmpty()) return null;
        File f = new File(c.getExternalFilesDir(null), wallpaper);
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (o.outWidth / (s * 2) >= maxW) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    // ---- helpers ----

    private static void writeDefault(Context c, File f) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        write(f, read(c.getResources().openRawResource(R.raw.default_config)));
    }

    static String read(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = is.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) { os.write(s.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
        changed();
    }

    /**
     * Home and the agent share this process, and FileObserver on app-specific external storage doesn't
     * reliably fire on this firmware, so every config write tells the listeners directly (on the main thread).
     */
    private static final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    static void listen(Runnable r) { if (!listeners.contains(r)) listeners.add(r); }

    static void unlisten(Runnable r) { listeners.remove(r); }

    static void changed() { for (Runnable r : listeners) { main.removeCallbacks(r); main.postDelayed(r, 150); } }

    private static int color(JSONObject o, String k, int def) {
        String s = o.optString(k, "");
        if (s.isEmpty()) return def;
        try { return Color.parseColor(s); } catch (IllegalArgumentException e) { return def; }
    }

    private static List<String> strings(JSONArray a, List<String> def) {
        if (a == null) return def;
        List<String> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) { String s = a.optString(i, ""); if (!s.isEmpty()) l.add(s); }
        return l;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
