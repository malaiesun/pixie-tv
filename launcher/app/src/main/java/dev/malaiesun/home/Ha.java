package dev.malaiesun.home;

import android.os.Handler;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal Home Assistant REST client: read entity states, call a service. One background thread. */
final class Ha {
    interface Listener { void onStates(Map<String, JSONObject> states, String error); }

    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    void fetch(Config cfg, Handler ui, Listener l) {
        if (!cfg.haReady()) return;
        final String url = cfg.haUrl, token = cfg.haToken;
        final List<String> ents = new ArrayList<>();
        for (Config.Chip c : cfg.chips) ents.add(c.entity);
        ex.execute(() -> {
            Map<String, JSONObject> out = new HashMap<>();
            String err = null;
            for (String e : ents) {
                try { out.put(e, new JSONObject(request("GET", url + "/api/states/" + e, token, null))); }
                catch (Exception x) { err = x.getClass().getSimpleName() + (x.getMessage() == null ? "" : ": " + x.getMessage()); }
            }
            final String fe = err;
            ui.post(() -> l.onStates(out, fe));
        });
    }

    interface Result { void done(String body, String error); }

    /** States for any list of entities (control panel). */
    void states(Config cfg, List<String> entities, Handler ui, Listener l) {
        if (cfg.haUrl.isEmpty() || cfg.haToken.isEmpty()) { ui.post(() -> l.onStates(new HashMap<>(), "Home Assistant not configured")); return; }
        final String url = cfg.haUrl, token = cfg.haToken;
        final List<String> ents = new ArrayList<>(entities);
        ex.execute(() -> {
            Map<String, JSONObject> out = new HashMap<>();
            String err = null;
            for (String e : ents) {
                try { out.put(e, new JSONObject(request("GET", url + "/api/states/" + e, token, null))); }
                catch (Exception x) { err = x.getClass().getSimpleName() + (x.getMessage() == null ? "" : ": " + x.getMessage()); }
            }
            final String fe = err;
            ui.post(() -> l.onStates(out, fe));
        });
    }

    /** POST /api/services/domain/service with a JSON body (entity_id and/or data). */
    void service(Config cfg, String domain, String service, JSONObject body, Handler ui, Result r) {
        post(cfg, "/api/services/" + domain + "/" + service, body == null ? "{}" : body.toString(), true, ui, r);
    }

    void webhook(Config cfg, String id, JSONObject data, Handler ui, Result r) {
        post(cfg, "/api/webhook/" + id, data == null ? "{}" : data.toString(), false, ui, r);
    }

    /** HA Assist: send text to the conversation agent, get the spoken reply. */
    void conversation(Config cfg, String text, Handler ui, Result r) {
        try {
            JSONObject b = new JSONObject().put("text", text).put("language", cfg.assistLanguage);
            post(cfg, "/api/conversation/process", b.toString(), true, ui, (body, err) -> {
                if (err != null) { r.done(null, err); return; }
                try {
                    JSONObject speech = new JSONObject(body).optJSONObject("response").optJSONObject("speech");
                    r.done(speech == null ? "Done." : speech.optJSONObject("plain").optString("speech", "Done."), null);
                } catch (Exception e) { r.done("Done.", null); }
            });
        } catch (Exception e) { r.done(null, e.getMessage()); }
    }

    private void post(Config cfg, String path, String body, boolean auth, Handler ui, Result r) {
        if (cfg.haUrl.isEmpty() || (auth && cfg.haToken.isEmpty())) { if (r != null) ui.post(() -> r.done(null, "Home Assistant not configured")); return; }
        final String url = cfg.haUrl + path, token = auth ? cfg.haToken : null;
        ex.execute(() -> {
            String res = null, err = null;
            try { res = request("POST", url, token, body); } catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String fr = res, fe = err;
            if (r != null) ui.post(() -> r.done(fr, fe));
        });
    }

    void call(Config cfg, String domain, String service, String entity, Handler ui, Runnable done) {
        if (cfg.haUrl.isEmpty() || cfg.haToken.isEmpty()) return;
        final String url = cfg.haUrl, token = cfg.haToken;
        ex.execute(() -> {
            try { request("POST", url + "/api/services/" + domain + "/" + service, token, "{\"entity_id\":\"" + entity + "\"}"); }
            catch (Exception ignored) { }
            ui.post(done);
        });
    }

    private static String request(String method, String u, String token, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setRequestMethod(method);
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String s = in == null ? "" : Config.read(in);
            if (code >= 400) throw new IOException("HTTP " + code);
            return s;
        } finally {
            c.disconnect();
        }
    }

    // ---- chip text ----

    private static final Pattern VAR = Pattern.compile("\\{([A-Za-z0-9_]+)\\}");

    /** Formats a chip: "{temperature}{temperature_unit} · {state}", or "Label · On" by default. */
    static String format(Config.Chip ch, JSONObject s) {
        if (s == null) return ch.label.isEmpty() ? ch.entity : ch.label + " · …";
        JSONObject a = s.optJSONObject("attributes");
        String fmt = ch.format;
        if (fmt.isEmpty()) {
            if (ch.entity.startsWith("weather.")) fmt = "{temperature}{temperature_unit} · {state}";
            else fmt = (ch.label.isEmpty() ? "{friendly_name}" : ch.label) + " · {state}";
        }
        Matcher m = VAR.matcher(fmt);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String k = m.group(1), v;
            if (k.equals("state")) v = pretty(s.optString("state"));
            else {
                Object o = a == null ? null : a.opt(k);
                v = o == null ? "" : o instanceof Double && ((Double) o) % 1 == 0 ? String.valueOf(((Double) o).intValue()) : String.valueOf(o);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean active(JSONObject s) {
        if (s == null) return false;
        String st = s.optString("state");
        return st.equals("on") || st.equals("open") || st.equals("playing") || st.equals("home") || st.equals("heat")
                || st.equals("cool") || st.equals("unlocked");
    }

    /** The service a panel press should call for this entity's domain: {domain, service}. */
    static String[] toggleService(String entity, JSONObject state) {
        String domain = entity.contains(".") ? entity.substring(0, entity.indexOf('.')) : "";
        String st = state == null ? "" : state.optString("state");
        switch (domain) {
            case "scene": return new String[]{"scene", "turn_on"};
            case "script": return new String[]{"script", "turn_on"};
            case "button": case "input_button": return new String[]{domain, "press"};
            case "automation": return new String[]{"automation", "trigger"};
            case "lock": return new String[]{"lock", "locked".equals(st) ? "unlock" : "lock"};
            case "media_player": return new String[]{"media_player", "media_play_pause"};
            case "cover": return new String[]{"cover", "toggle"};
            default: return new String[]{"homeassistant", "toggle"};
        }
    }

    static String friendly(JSONObject s, String fallback) {
        JSONObject a = s == null ? null : s.optJSONObject("attributes");
        String n = a == null ? "" : a.optString("friendly_name", "");
        return n.isEmpty() ? fallback : n;
    }

    static String prettyState(JSONObject s) { return s == null ? "…" : pretty(s.optString("state")); }

    private static String pretty(String st) {
        switch (st) {
            case "unavailable": case "unknown": return "—";
            case "partlycloudy": return "Partly cloudy";
            case "clear-night": return "Clear";
            default:
                String t = st.replace('-', ' ').replace('_', ' ');
                return t.isEmpty() ? t : t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
        }
    }
}
