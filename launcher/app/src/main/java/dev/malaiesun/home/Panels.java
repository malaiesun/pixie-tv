package dev.malaiesun.home;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The overlay panels: Home Assistant, quick panel, app switcher, voice chooser. */
final class Panels {
    static final String HA = "Home Assistant", QUICK = "PIXIE", APPS = "Apps", VOICE = "Talk to", THEMES = "Themes";

    private final PixieService s;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, JSONObject> haStates = new HashMap<>();
    private final List<Overlay.Entry> haEntries = new ArrayList<>();
    private final Runnable haRefreshLoop = new Runnable() {
        @Override public void run() {
            if (!HA.equals(s.overlay.panelTitle())) return;
            refreshHa();
            s.ui.postDelayed(this, 5000);
        }
    };

    Panels(PixieService s) { this.s = s; }

    void open(String name) {
        switch (name == null ? "" : name.toLowerCase()) {
            case "ha": case "home_assistant": ha(); break;
            case "apps": apps(); break;
            case "voice": voice(); break;
            case "theme": case "themes": themes(); break;
            default: quick(); break;
        }
    }

    // ---------------------------------------------------------------- Home Assistant

    void ha() {
        Config cfg = s.cfg;
        Overlay.Panel p = new Overlay.Panel();
        p.title = HA;
        p.subtitle = "OK toggles · Back closes";
        p.tileWdp = 196;
        p.tileHdp = 104;
        haEntries.clear();
        List<Overlay.Entry> row = new ArrayList<>();
        for (Config.Chip ch : cfg.panel) {
            Overlay.Entry e = Overlay.Entry.of(ch.label.isEmpty() ? ch.entity : ch.label, "…", null);
            e.id = ch.entity;
            e.keepOpen = true;
            if (!"none".equals(ch.tap)) e.action = () -> haToggle(e);
            haEntries.add(e);
            row.add(e);
            if (row.size() == 5) { p.row("", row); row = new ArrayList<>(); }
        }
        p.row("", row);
        if (haEntries.isEmpty()) { s.overlay.toast("No Home Assistant entities in config.json (home_assistant.panel)"); return; }
        s.overlay.openPanel(p);
        s.ui.removeCallbacks(haRefreshLoop);
        s.ui.post(haRefreshLoop);
    }

    private void refreshHa() {
        List<String> ids = new ArrayList<>();
        for (Overlay.Entry e : haEntries) ids.add(e.id);
        s.ha.states(s.cfg, ids, s.ui, (st, err) -> {
            haStates.putAll(st);
            for (Overlay.Entry e : haEntries) {
                JSONObject j = st.get(e.id);
                if (j == null) continue;
                if (e.label.equals(e.id)) e.label = Ha.friendly(j, e.id);
                e.sub = e.id.startsWith("weather.") ? Ha.format(chipFor(e.id), j) : Ha.prettyState(j);
                e.active = Ha.active(j);
                s.overlay.updateEntry(e);
            }
            if (err != null && st.isEmpty()) s.overlay.toast("Home Assistant: " + err);
        });
    }

    private Config.Chip chipFor(String entity) {
        for (Config.Chip c : s.cfg.panel) if (c.entity.equals(entity)) return c;
        Config.Chip c = new Config.Chip();
        c.entity = entity;
        return c;
    }

    private void haToggle(Overlay.Entry e) {
        String[] sv = Ha.toggleService(e.id, haStates.get(e.id));
        e.active = !e.active;                      // optimistic
        s.overlay.updateEntry(e);
        try {
            s.ha.service(s.cfg, sv[0], sv[1], new JSONObject().put("entity_id", e.id), s.ui, (b, err) -> {
                if (err != null) s.overlay.toast("Home Assistant: " + err);
                s.ui.postDelayed(this::refreshHa, 500);
            });
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- quick panel

    void quick() {
        Overlay.Panel p = new Overlay.Panel();
        p.title = QUICK;
        p.tileWdp = 180;
        p.tileHdp = 96;
        List<Overlay.Entry> inputs = new ArrayList<>();
        for (Apps.Input in : Apps.inputs(s)) {
            Overlay.Entry e = Overlay.Entry.of(in.label, in.renamed() ? in.port : (in.connected() ? "Connected" : ""),
                    () -> s.startActivity(Apps.inputIntent(in)));
            e.active = false;
            inputs.add(e);
        }
        if (Apps.launch(s, s.cfg.liveTvPackage) != null)
            inputs.add(Overlay.Entry.of("Live TV", "", () -> s.actions.setInput("livetv")));
        p.row("Inputs", inputs);

        List<Overlay.Entry> acts = new ArrayList<>();
        acts.add(Overlay.Entry.of("Home Assistant", "Lights & more", this::ha));
        acts.add(Overlay.Entry.of("All apps", "", this::apps));
        acts.add(Overlay.Entry.of("Themes", Theme.name(s.theme.id), this::themes));
        acts.add(Overlay.Entry.of(s.sleepMinutesLeft() > 0 ? "Sleep timer" : "Sleep 30 min",
                s.sleepMinutesLeft() > 0 ? s.sleepMinutesLeft() + " min left · off" : "Then TV off",
                () -> s.sleepTimer(s.sleepMinutesLeft() > 0 ? 0 : 30)));
        acts.add(Overlay.Entry.of("Talk", "Gemini / HA Assist", this::voice));
        acts.add(Overlay.Entry.of("Screenshot", "Saved to Pictures", s::saveScreenshot));
        acts.add(Overlay.Entry.of("Learn a button", "Show remote codes", s::startLearn));
        acts.add(Overlay.Entry.of("Pair device", "PC / phone", () -> s.server.startPairing("menu")));
        acts.add(Overlay.Entry.of("Settings", "Android", () ->
                s.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        p.row("PIXIE", new ArrayList<>(acts.subList(0, 5)));
        p.row("", new ArrayList<>(acts.subList(5, acts.size())));

        LinkedHashMap<String, Apps.App> apps = Apps.query(s);
        List<Overlay.Entry> favs = new ArrayList<>();
        for (String pkg : s.cfg.favorites) {
            Apps.App a = apps.get(pkg);
            if (a != null) favs.add(appEntry(a));
        }
        p.row("Favorites", favs);
        s.overlay.openPanel(p);
        loadArt(favs, apps);
    }

    // ---------------------------------------------------------------- themes

    void themes() {
        Overlay.Panel p = new Overlay.Panel();
        p.title = THEMES;
        p.subtitle = "OK picks a theme · options change in place";
        p.say = "Pick my next outfit!";
        p.tileWdp = 200;
        p.tileHdp = 96;
        List<Overlay.Entry> ts = new ArrayList<>();
        for (int i = 0; i < Theme.IDS.length; i++) {
            String id = Theme.IDS[i];
            boolean cur = id.equals(s.theme.id);
            Overlay.Entry e = Overlay.Entry.of(Theme.NAMES[i], cur ? "Active" : themeHint(id), () -> s.setTheme(id));
            e.active = cur;
            ts.add(e);
        }
        p.row("Themes", new ArrayList<>(ts.subList(0, 3)));
        p.row("", new ArrayList<>(ts.subList(3, ts.size())));

        List<Overlay.Entry> opts = new ArrayList<>();
        Overlay.Entry mascot = Overlay.Entry.of("Mascot", Config.mascotLabel(s.cfg.mascot), null);
        mascot.keepOpen = true;
        mascot.action = () -> { String v = s.setMode("mascot", "next"); if (v != null) { mascot.sub = Config.mascotLabel(v); s.overlay.updateEntry(mascot); } };
        opts.add(mascot);
        Overlay.Entry anim = Overlay.Entry.of("Opening animation", Config.launchLabel(s.cfg.launchAnimation), null);
        anim.keepOpen = true;
        anim.action = () -> { String v = s.setMode("launch_animation", "next"); if (v != null) { anim.sub = Config.launchLabel(v); s.overlay.updateEntry(anim); } };
        opts.add(anim);
        opts.add(Overlay.Entry.of("Rotate daily", "A new theme each day", () -> s.setTheme("rotate")));
        opts.add(Overlay.Entry.of("Day / night", "Aurora · Neon after 19:00", () -> s.setTheme("daynight")));
        p.row("Options", opts);
        s.overlay.openPanel(p);
    }

    private static String themeHint(String id) {
        switch (id) {
            case "comic": return "POW! Pixie in a cape";
            case "hero": return "Pixie flies";
            case "retro": return "Pixie runs & jumps";
            case "neon": return "Pixie's jetpack";
            case "minimal": return "Calm Pixie";
            default: return "Pixie floats";
        }
    }

    // ---------------------------------------------------------------- apps

    void apps() {
        Overlay.Panel p = new Overlay.Panel();
        p.title = APPS;
        p.tileWdp = 176;
        LinkedHashMap<String, Apps.App> apps = Apps.query(s);
        List<Apps.App> ordered = new ArrayList<>();
        for (String pkg : s.cfg.favorites) if (apps.containsKey(pkg)) ordered.add(apps.get(pkg));
        for (Apps.App a : apps.values())
            if (!s.cfg.favorites.contains(a.pkg) && !s.cfg.hidden.contains(a.pkg)) ordered.add(a);
        List<Overlay.Entry> all = new ArrayList<>(), row = new ArrayList<>();
        for (Apps.App a : ordered) {
            Overlay.Entry e = appEntry(a);
            all.add(e);
            row.add(e);
            if (row.size() == 5) { p.row("", row); row = new ArrayList<>(); }
        }
        p.row("", row);
        s.overlay.openPanel(p);
        loadArt(all, apps);
    }

    private Overlay.Entry appEntry(Apps.App a) {
        Overlay.Entry e = Overlay.Entry.of(a.label, "", () -> {
            Intent i = Apps.launch(s, a.pkg);
            if (i != null) s.startActivity(i);
        });
        e.id = a.pkg;
        return e;
    }

    /** Banners load off the main thread, then pop into the open panel. */
    private void loadArt(List<Overlay.Entry> es, Map<String, Apps.App> apps) {
        io.execute(() -> {
            for (Overlay.Entry e : es) {
                Apps.App a = apps.get(e.id);
                if (a == null) continue;
                Drawable b = Apps.banner(s, a);
                Drawable i = b == null ? Apps.icon(s, a) : null;
                s.ui.post(() -> {
                    if (e.view == null) return;
                    if (b != null) { e.art = b; e.view.banner(b).caption(e.label); }
                    else if (i != null) { e.art = i; e.view.icon(i).text("").caption(e.label); }
                });
            }
        });
    }

    // ---------------------------------------------------------------- voice

    void voice() {
        Overlay.Panel p = new Overlay.Panel();
        p.title = VOICE;
        p.subtitle = "Left / right to choose · OK to talk";
        p.tileWdp = 300;
        p.tileHdp = 150;
        p.autoCloseMs = 9000;
        List<Overlay.Entry> row = new ArrayList<>();
        row.add(Overlay.Entry.of("Gemini", "Google Assistant", s.voice::gemini));
        row.add(Overlay.Entry.of("Home Assistant", "HA Assist", s.voice::haAssist));
        p.row("", row);
        s.overlay.openPanel(p);
    }
}
