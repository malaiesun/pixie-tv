package dev.malaiesun.home;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.tv.TvInputInfo;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Executes actions from rules, panels and the LAN API. One action is a JSON object, either
 * {"open_app": "pkg"}, {"open_app": {"package": "pkg"}} or {"action": "open_app", "package": "pkg"}.
 * Everything here runs on the main thread.
 */
final class Actions {
    private final PixieService s;

    Actions(PixieService s) { this.s = s; }

    /** Runs actions in order; {"delay": ms} pauses the chain. */
    void run(List<Object> list, Runnable done) {
        runFrom(list == null ? Collections.emptyList() : list, 0, done);
    }

    private void runFrom(List<Object> list, int i, Runnable done) {
        if (i >= list.size()) { if (done != null) done.run(); return; }
        Object o = list.get(i);
        if (!(o instanceof JSONObject)) { runFrom(list, i + 1, done); return; }
        JSONObject a = (JSONObject) o;
        String name = nameOf(a);
        if (name.equals("delay")) {
            long ms = paramsOf(a, name).optLong("value", paramsOf(a, name).optLong("ms", 500));
            s.ui.postDelayed(() -> runFrom(list, i + 1, done), Math.max(0, Math.min(ms, 600_000)));
            return;
        }
        exec(name, paramsOf(a, name));
        runFrom(list, i + 1, done);
    }

    static List<Object> list(JSONArray a) {
        List<Object> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.opt(i));
        return l;
    }

    static String nameOf(JSONObject o) {
        if (o.has("action")) return o.optString("action");
        Iterator<String> it = o.keys();
        return it.hasNext() ? it.next() : "";
    }

    static JSONObject paramsOf(JSONObject o, String name) {
        if (o.has("action")) return o;
        Object v = o.opt(name);
        if (v instanceof JSONObject) return (JSONObject) v;
        JSONObject p = new JSONObject();
        try { p.put("value", v); } catch (JSONException ignored) { }
        return p;
    }

    private static String str(JSONObject p, String key) {
        String v = p.optString(key, "");
        return v.isEmpty() ? p.optString("value", "") : v;
    }

    /** Runs one action and returns a result for API callers. */
    JSONObject exec(String name, JSONObject p) {
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            switch (name.toLowerCase(Locale.ROOT)) {
                case "open_url": {
                    String url = str(p, "url");
                    s.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                }
                case "open_app": case "launch_app": {
                    Intent i = Apps.launch(s, str(p, "package"));
                    if (i == null) return fail(res, "app not found");
                    s.startActivity(i);
                    break;
                }
                case "input_text": case "text":
                    if (!s.inputText(str(p, "text"), p.optBoolean("append", false))) return fail(res, "no focused text field");
                    break;
                case "key": case "press": {
                    String k = str(p, "key");
                    if (s.overlay.panelOpen() && s.panelKey(k)) break;   // drive an open PIXIE panel
                    if (!s.globalKey(k)) return fail(res, "key not supported");
                    break;
                }
                case "home": s.globalKey("HOME"); break;
                case "back": s.globalKey("BACK"); break;
                case "clipboard_set": case "clipboard": {
                    ClipboardManager cm = s.getSystemService(ClipboardManager.class);
                    cm.setPrimaryClip(ClipData.newPlainText("PIXIE", str(p, "text")));
                    s.overlay.toast("Clipboard updated");
                    break;
                }
                case "toast": s.overlay.toast(str(p, "text")); break;
                case "notify":
                    s.overlay.card(p.optString("title", "PIXIE"), str(p, "text"), false, p.optLong("seconds", 6) * 1000);
                    break;
                case "popup": popup(p); break;
                case "volume": {
                    if (p.has("set")) s.setVolume(p.optInt("set"));
                    else if (p.optBoolean("mute", false) || "mute".equals(p.optString("value"))) s.toggleMute();
                    else s.changeVolume(p.has("delta") ? p.optInt("delta") : p.optInt("value", 0));
                    res.put("volume", s.volumeLevel());
                    break;
                }
                case "set_input": case "input":
                    if (!setInput(str(p, "input"))) return fail(res, "input not found");
                    break;
                case "ha_service": {
                    String svc = p.optString("service", "");
                    if (!svc.contains(".")) return fail(res, "service must be domain.service");
                    JSONObject body = p.optJSONObject("data") != null ? new JSONObject(p.optJSONObject("data").toString()) : new JSONObject();
                    if (p.has("entity_id")) body.put("entity_id", p.get("entity_id"));
                    s.ha.service(s.cfg, svc.substring(0, svc.indexOf('.')), svc.substring(svc.indexOf('.') + 1), body, s.ui,
                            (b, err) -> { if (err != null) s.overlay.toast("Home Assistant: " + err); });
                    break;
                }
                case "ha_webhook":
                    s.ha.webhook(s.cfg, p.optString("id", str(p, "id")), p.optJSONObject("data"), s.ui, null);
                    break;
                case "assist": s.voice.ask(str(p, "text")); break;
                case "panel": s.panels.open(str(p, "name")); break;
                case "voice": if ("gemini".equals(str(p, "target"))) s.voice.gemini(); else s.voice.haAssist(); break;
                case "theme": s.setTheme(str(p, "preset")); break;
                case "mascot": case "launch_animation": {
                    String key = name.toLowerCase(Locale.ROOT);
                    String mode = str(p, "mode");
                    if ("mascot".equals(key) && "demo".equals(mode)) {
                        if (!MascotView.demo()) return fail(res, "Pixie isn't on Home right now");
                        break;
                    }
                    if ("mascot".equals(key) && "hello".equals(mode)) {
                        HomeActivity h = HomeActivity.current;
                        if (h == null) return fail(res, "Home isn't on screen");
                        h.greet();
                        break;
                    }
                    if ("launch_animation".equals(key) && "demo".equals(mode)) {
                        HomeActivity h = HomeActivity.current;
                        if (h == null || !h.demoOpen()) return fail(res, "Home isn't on screen (or no tile focused)");
                        break;
                    }
                    String v = s.setMode(key, str(p, "mode"));
                    if (v == null) return fail(res, "mode must be one of " + String.join(", ", "mascot".equals(key) ? Config.MASCOT_MODES : Config.LAUNCH_MODES) + " or next");
                    res.put("mode", v);
                    break;
                }
                case "sleep_timer": s.sleepTimer(p.optInt("minutes", p.optInt("value", 30))); break;
                case "screenshot": s.saveScreenshot(); break;
                case "learn": s.startLearn(p.optInt("seconds", 15)); break;
                case "pair": res.put("expires_in", s.server.startPairing("remote")); break;
                case "run_rule": {
                    dev.malaiesun.home.rules.Rule r = s.engine.byName(str(p, "name"));
                    if (r == null) return fail(res, "no such rule");
                    run(r.actions, null);
                    break;
                }
                case "event": s.fireEvent(str(p, "name"), p.optJSONObject("data")); break;
                default: return fail(res, "unknown action: " + name);
            }
        } catch (Exception e) {
            return fail(res, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return res;
    }

    private static JSONObject fail(JSONObject res, String err) {
        try { res.put("ok", false); res.put("error", err); } catch (JSONException ignored) { }
        return res;
    }

    /** {"title","text","id","buttons":[{"label","do":[...]}]}. Each press also fires event popup:<id>:<label>. */
    private void popup(JSONObject p) {
        Overlay.Panel panel = new Overlay.Panel();
        panel.title = p.optString("title", "PIXIE");
        panel.say = p.optString("text", "");
        panel.tileWdp = 190;
        panel.tileHdp = 72;
        panel.autoCloseMs = p.optLong("timeout", 30) * 1000;
        String id = p.optString("id", "popup");
        JSONArray bs = p.optJSONArray("buttons");
        List<Overlay.Entry> row = new ArrayList<>();
        if (bs == null || bs.length() == 0) bs = new JSONArray().put(new JSONObject());
        for (int i = 0; i < bs.length(); i++) {
            JSONObject b = bs.optJSONObject(i);
            if (b == null) continue;
            String label = b.optString("label", "OK");
            JSONArray todo = b.optJSONArray("do");
            row.add(Overlay.Entry.of(label, "", () -> {
                run(list(todo), null);
                try { s.fireEvent("popup:" + id + ":" + label, new JSONObject().put("id", id).put("button", label)); }
                catch (JSONException ignored) { }
            }));
        }
        panel.row("", row);
        s.overlay.openPanel(panel);
    }

    /** "hdmi1".."hdmi4", "av", "livetv", a port label ("HDMI 2") or a raw TV input id. */
    boolean setInput(String want) {
        String w = want.toLowerCase(Locale.ROOT).replace(" ", "");
        if (w.equals("livetv") || w.equals("tv")) {
            Intent i = Apps.launch(s, s.cfg.liveTvPackage);
            if (i == null) return false;
            s.startActivity(i);
            return true;
        }
        for (Apps.Input in : Apps.inputs(s)) {
            String port = in.port.toLowerCase(Locale.ROOT).replace(" ", "");
            boolean match = port.equals(w) || in.id.equals(want) || in.label.equalsIgnoreCase(want)
                    || (w.equals("av") && (in.type == TvInputInfo.TYPE_COMPOSITE || in.type == TvInputInfo.TYPE_COMPONENT));
            if (match) { s.startActivity(Apps.inputIntent(in)); return true; }
        }
        return false;
    }
}
