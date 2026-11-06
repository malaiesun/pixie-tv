package dev.malaiesun.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import dev.malaiesun.home.rules.Rule;

/** rules.json <-> Rule objects. Lives next to config.json; push it the same way (ConfigProvider / API). */
final class RulesIO {
    static final String FILE = "rules.json";

    private RulesIO() { }

    static File file(Context c) { return new File(c.getExternalFilesDir(null), FILE); }

    static List<Rule> load(Context c) {
        File f = file(c);
        try {
            if (!f.exists()) Config.write(f, Config.read(c.getResources().openRawResource(R.raw.default_rules)));
            return parse(Config.read(new FileInputStream(f)));
        } catch (Exception e) {
            try { return parse(Config.read(c.getResources().openRawResource(R.raw.default_rules))); }
            catch (Exception e2) { return new ArrayList<>(); }
        }
    }

    /** Accepts a bare array or {"rules": [...]}. Throws on invalid JSON so the API can reject it. */
    static List<Rule> parse(String json) throws Exception {
        Object root = new JSONTokener(json).nextValue();
        JSONArray arr = root instanceof JSONArray ? (JSONArray) root
                : root instanceof JSONObject ? ((JSONObject) root).optJSONArray("rules") : null;
        if (arr == null) throw new IllegalArgumentException("expected an array of rules");
        List<Rule> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Rule r = new Rule();
            r.name = o.optString("name", "rule " + (i + 1));
            r.enabled = o.optBoolean("enabled", true);
            r.key = opt(o, "key");
            r.press = o.optString("press", "single");
            r.appOpened = opt(o, "app_opened");
            r.appClosed = opt(o, "app_closed");
            r.time = opt(o, "time");
            r.event = opt(o, "event");
            r.inApp = opt(o, "in_app");
            r.after = opt(o, "after");
            r.before = opt(o, "before");
            Object d = o.opt("do");
            if (d instanceof JSONArray) r.actions.addAll(Actions.list((JSONArray) d));
            else if (d instanceof JSONObject) r.actions.add(d);
            out.add(r);
        }
        return out;
    }

    private static String opt(JSONObject o, String k) {
        String v = o.optString(k, "");
        return v.isEmpty() ? null : v;
    }
}
