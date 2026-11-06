package dev.malaiesun.home;

import android.graphics.Color;
import android.graphics.Typeface;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Look and feel. A preset sets colors, tile shape, fonts, the animated background and the logo.
 * Presets can be mixed (one theme's background with another's logo), scheduled by time of day,
 * or rotated daily/hourly.
 */
final class Theme {
    static final String[] IDS = {"aurora", "comic", "hero", "retro", "neon", "minimal"};
    static final String[] NAMES = {"Aurora", "Comic Pop", "Hero", "Retro 8-bit", "Neon Grid", "Minimal"};

    String id = "aurora", background = "aurora", logo = "aurora";
    int bg, bg2, surface, text, textDim, accent, accent2, ring, border, shadow, onAccent = 0xFFFFFFFF;
    float radiusDp = 14, borderDp = 0, shadowDp = 0, ringDp = 3;
    String font = "sans-serif-medium";
    int fontStyle = Typeface.NORMAL;
    boolean upper;

    static Theme preset(String id) {
        Theme t = new Theme();
        t.id = id;
        switch (id) {
            case "comic":
                t.colors(0xFFFFD60A, 0xFFFFB703, 0xFFFFFFFF, 0xFF111111, 0xDD111111, 0xFFE63946, 0xFF1D4ED8);
                t.ring = 0xFF111111; t.border = 0xFF111111; t.borderDp = 4; t.shadow = 0xFF111111; t.shadowDp = 7;
                t.radiusDp = 6; t.ringDp = 5; t.font = "sans-serif-black"; t.upper = true;
                break;
            case "hero":
                t.colors(0xFF05070D, 0xFF2A0A10, 0xE61A1F2B, 0xFFF5F5F5, 0x99F5F5F5, 0xFFE23636, 0xFFF2C14E);
                t.ring = 0xFFF2C14E; t.radiusDp = 10; t.font = "sans-serif-condensed"; t.fontStyle = Typeface.BOLD; t.upper = true;
                break;
            case "retro":
                t.colors(0xFF5C94FC, 0xFF9CC0FF, 0xF0222034, 0xFFFFFFFF, 0xDDFFFFFF, 0xFFE04040, 0xFF40C040);
                t.ring = 0xFFFFE14D; t.border = 0xFF000000; t.borderDp = 4; t.radiusDp = 0; t.shadow = 0xFF000000; t.shadowDp = 6;
                t.ringDp = 5; t.font = "monospace"; t.fontStyle = Typeface.BOLD; t.upper = true;
                break;
            case "neon":
                t.colors(0xFF0B0221, 0xFF2B0B3F, 0xEE160A2B, 0xFFF8F7FF, 0xAAF8F7FF, 0xFFFF2BD6, 0xFF00E5FF);
                t.ring = 0xFF00E5FF; t.radiusDp = 14; t.font = "sans-serif-light";
                break;
            case "minimal":
                t.colors(0xFF0B0D12, 0xFF141821, 0xFF1B1F27, 0xFFFFFFFF, 0x99FFFFFF, 0xFF5B8CFF, 0xFF8AB4FF);
                t.ring = 0xFFFFFFFF; t.radiusDp = 12;
                break;
            default:
                t.id = "aurora";
                t.colors(0xFF070A12, 0xFF0D1324, 0xCC1B2130, 0xFFFFFFFF, 0x99FFFFFF, 0xFF5B8CFF, 0xFFB266FF);
                t.ring = 0xFFFFFFFF; t.radiusDp = 14;
                break;
        }
        t.background = t.id;
        t.logo = t.id;
        return t;
    }

    private void colors(int bg, int bg2, int surface, int text, int dim, int accent, int accent2) {
        this.bg = bg; this.bg2 = bg2; this.surface = surface; this.text = text; this.textDim = dim;
        this.accent = accent; this.accent2 = accent2;
    }

    /** Schedule beats rotation beats the fixed preset; then background/logo mixing and color overrides. */
    static Theme resolve(Config c) {
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        String id = c.themePreset;
        if (!c.themeSchedule.isEmpty()) {
            int bestFrom = -1;
            String pick = null, latest = null;
            int latestFrom = -1;
            for (Config.ScheduleEntry e : c.themeSchedule) {
                if (e.from <= minute && e.from > bestFrom) { bestFrom = e.from; pick = e.preset; }
                if (e.from > latestFrom) { latestFrom = e.from; latest = e.preset; }
            }
            id = pick != null ? pick : latest;   // before the first entry: yesterday's last one
        } else if (!c.themeRotation.isEmpty() && !"off".equals(c.themeRotationMode)) {
            int n = c.themeRotation.size();
            int slot = "hourly".equals(c.themeRotationMode) ? now.get(Calendar.HOUR_OF_DAY) : now.get(Calendar.DAY_OF_YEAR);
            id = c.themeRotation.get(slot % n);
        }
        Theme t = preset(id == null ? "aurora" : id);
        if (!c.themeBackground.isEmpty()) t.background = c.themeBackground;
        if (!c.themeLogo.isEmpty()) t.logo = c.themeLogo;
        JSONObject o = c.themeOverrides;
        if (o != null) {
            t.bg = col(o, "background", t.bg);
            t.bg2 = col(o, "background2", t.bg2);
            t.surface = col(o, "surface", t.surface);
            t.text = col(o, "text", t.text);
            t.textDim = col(o, "text_dim", t.textDim);
            t.accent = col(o, "accent", t.accent);
            t.accent2 = col(o, "accent2", t.accent2);
            t.ring = col(o, "ring", t.ring);
            t.radiusDp = (float) o.optDouble("radius_dp", t.radiusDp);
            if (o.has("font")) t.font = o.optString("font");
        }
        return t;
    }

    /** Same theme with a different tile surface (panels use a lighter shade than their card). */
    Theme withSurface(int s) {
        Theme t = new Theme();
        t.id = id; t.background = background; t.logo = logo;
        t.bg = bg; t.bg2 = bg2; t.surface = s; t.text = text; t.textDim = textDim; t.accent = accent; t.accent2 = accent2;
        t.ring = ring; t.border = border; t.shadow = shadow; t.onAccent = onAccent;
        t.radiusDp = radiusDp; t.borderDp = borderDp; t.shadowDp = shadowDp; t.ringDp = ringDp;
        t.font = font; t.fontStyle = fontStyle; t.upper = upper;
        return t;
    }

    /** Blend toward white (f > 0) or black (f < 0), keeping alpha opaque. */
    static int shade(int c, float f) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255, to = f > 0 ? 255 : 0;
        float a = Math.abs(f);
        return 0xFF000000 | ((int) (r + (to - r) * a) << 16) | ((int) (g + (to - g) * a) << 8) | (int) (b + (to - b) * a);
    }

    Typeface typeface() { return Typeface.create(font, fontStyle); }

    Typeface bold() { return Typeface.create(font, Typeface.BOLD); }

    String cap(String s) { return upper && s != null ? s.toUpperCase(Locale.ROOT) : s; }

    static String name(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
        return id;
    }

    /** Readable text color for something drawn on `c`. */
    static int on(int c) {
        double l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255;
        return l > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
    }

    private static int col(JSONObject o, String k, int def) {
        String s = o.optString(k, "");
        if (s.isEmpty()) return def;
        try { return Color.parseColor(s); } catch (IllegalArgumentException e) { return def; }
    }
}
