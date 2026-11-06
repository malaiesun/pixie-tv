package dev.malaiesun.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import dev.malaiesun.home.rules.KeyGestures;
import dev.malaiesun.home.rules.Rule;
import dev.malaiesun.home.rules.RuleEngine;

/**
 * The PIXIE agent. An accessibility service, so it is bound by the system at boot and kept alive
 * without a foreground notification. It filters remote keys (rules: single / double / long press),
 * owns the overlays, tracks the foreground app for rules, and runs the LAN API.
 */
public class PixieService extends AccessibilityService implements KeyGestures.Sink {
    static volatile PixieService instance;

    final Handler ui = new Handler(Looper.getMainLooper());
    final Ha ha = new Ha();
    final EventBus events = new EventBus();
    Config cfg;
    Theme theme;
    Overlay overlay;
    RuleEngine engine;
    Actions actions;
    Panels panels;
    Voice voice;
    Server server;
    volatile String fg = "";

    private KeyGestures gestures;
    private AudioManager audio;
    private FileObserver watcher;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;
    private int lastMinute = -1;
    private boolean learning;
    private final List<String> learned = new ArrayList<>();
    private long sleepAt;

    private final Runnable gestureTick = () -> { gestures.tick(SystemClock.uptimeMillis()); scheduleTick(); };
    private final Runnable learnEnd = this::stopLearn;
    private final Runnable reloadSoon = this::reload;
    private final Runnable sleepWarn = this::sleepWarning;
    private final Runnable sleepNow = this::sleepNow;
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            int m = minuteNow();
            if (m != lastMinute) {
                lastMinute = m;
                for (Rule r : engine.matchTime(m, fg)) actions.run(r.actions, null);
                Theme t = Theme.resolve(cfg);            // scheduled / rotating themes
                if (!t.id.equals(theme.id) || !t.background.equals(theme.background)) { theme = t; overlay.setTheme(t); }
            }
            ui.postDelayed(this, 60_000 - (System.currentTimeMillis() % 60_000) + 200);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onServiceConnected() {
        instance = this;
        Config.listen(reloadSoon);
        audio = getSystemService(AudioManager.class);
        engine = new RuleEngine(getPackageName());
        gestures = new KeyGestures(this);
        overlay = new Overlay(this);
        actions = new Actions(this);
        panels = new Panels(this);
        voice = new Voice(this);
        reload();
        server = new Server(this);
        server.start(cfg.agentPort);
        registerNsd();
        startWatching();
        ui.post(minuteTick);
        events.emit("agent_started", null);
    }

    void reload() {
        cfg = Config.load(this);
        theme = Theme.resolve(cfg);
        overlay.setTheme(theme);
        engine.set(RulesIO.load(this));
    }

    @Override public boolean onUnbind(Intent intent) {
        Config.unlisten(reloadSoon);
        shutdown();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (instance != this) return;
        instance = null;
        ui.removeCallbacksAndMessages(null);
        if (watcher != null) watcher.stopWatching();
        if (server != null) server.stop();
        try { if (nsd != null && nsdListener != null) nsd.unregisterService(nsdListener); } catch (Exception ignored) { }
        if (overlay != null) overlay.destroy();
        if (voice != null) voice.destroy();
    }

    @Override public void onInterrupt() { }

    private void startWatching() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        watcher = new FileObserver(dir, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int ev, String path) {
                if (Config.FILE.equals(path) || RulesIO.FILE.equals(path)) { ui.removeCallbacks(reloadSoon); ui.postDelayed(reloadSoon, 300); }
            }
        };
        watcher.startWatching();
    }

    private void registerNsd() {
        try {
            nsd = getSystemService(NsdManager.class);
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName("PIXIE");
            si.setServiceType("_hearth._tcp");
            si.setPort(cfg.agentPort);
            nsdListener = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo s, int e) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
                @Override public void onServiceRegistered(NsdServiceInfo s) { }
                @Override public void onServiceUnregistered(NsdServiceInfo s) { }
            };
            nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- keys

    @Override protected boolean onKeyEvent(KeyEvent e) {
        try {
            int code = e.getKeyCode();
            if (learning) return learnKey(e);
            if (overlay.panelOpen() && overlay.key(e)) return true;
            if (cfg.volumeOverlay && isVolume(code) && !audio.isVolumeFixed()) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) volumeKey(code);
                return true;
            }
            String name = keyName(code);
            long t = e.getEventTime();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() > 0) {
                    if (!gestures.tracking(name)) return false;
                    gestures.tick(t);
                    scheduleTick();
                    return true;
                }
                Set<String> ps = engine.presses(name, code, fg, minuteNow());
                if (ps.isEmpty()) return false;
                gestures.down(name, code, t, ps.contains("double"), ps.contains("long"));
                scheduleTick();
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP && gestures.tracking(name)) {
                gestures.up(name, t);
                scheduleTick();
                return true;
            }
        } catch (Exception ignored) {
            // never break the remote
        }
        return false;
    }

    private void scheduleTick() {
        ui.removeCallbacks(gestureTick);
        long dl = gestures.deadline();
        if (dl > 0) ui.postAtTime(gestureTick, dl);
    }

    /** A finished gesture. Rules first; with no rule, replay the key so nothing feels broken. */
    @Override public void gesture(String key, int code, String press) {
        List<Rule> rs = engine.matchKey(key, code, press, fg, minuteNow());
        if (rs.isEmpty()) {
            int times = "double".equals(press) ? 2 : 1;
            for (int i = 0; i < times; i++) replay(code);
        } else {
            for (Rule r : rs) actions.run(r.actions, null);
        }
        try { events.emit("key", new JSONObject().put("key", key).put("press", press)); } catch (Exception ignored) { }
    }

    private void replay(int code) {
        if (!globalKey(keyName(code))) {
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                    || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        }
    }

    /** Keys an accessibility service can press: navigation via global actions, media via AudioManager. */
    boolean globalKey(String key) {
        String k = key == null ? "" : key.trim().toUpperCase(Locale.ROOT).replace("KEYCODE_", "");
        int ga;
        switch (k) {
            case "BACK": ga = GLOBAL_ACTION_BACK; break;
            case "HOME": ga = GLOBAL_ACTION_HOME; break;
            case "RECENTS": case "APP_SWITCH": ga = GLOBAL_ACTION_RECENTS; break;
            case "NOTIFICATIONS": case "NOTIFICATION": ga = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "UP": case "DPAD_UP": ga = GLOBAL_ACTION_DPAD_UP; break;
            case "DOWN": case "DPAD_DOWN": ga = GLOBAL_ACTION_DPAD_DOWN; break;
            case "LEFT": case "DPAD_LEFT": ga = GLOBAL_ACTION_DPAD_LEFT; break;
            case "RIGHT": case "DPAD_RIGHT": ga = GLOBAL_ACTION_DPAD_RIGHT; break;
            case "OK": case "CENTER": case "DPAD_CENTER": case "ENTER": ga = GLOBAL_ACTION_DPAD_CENTER; break;
            case "PLAY_PAUSE": case "MEDIA_PLAY_PAUSE": media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return true;
            case "NEXT": case "MEDIA_NEXT": media(KeyEvent.KEYCODE_MEDIA_NEXT); return true;
            case "PREVIOUS": case "MEDIA_PREVIOUS": media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return true;
            case "VOLUME_UP": changeVolume(cfg.volumeStep); return true;
            case "VOLUME_DOWN": changeVolume(-cfg.volumeStep); return true;
            case "MUTE": case "VOLUME_MUTE": toggleMute(); return true;
            default: return false;
        }
        return performGlobalAction(ga);
    }

    /** A key from the API/PC while a panel is open goes to the panel, like a real remote press. */
    boolean panelKey(String key) {
        String k = key == null ? "" : key.trim().toUpperCase(Locale.ROOT).replace("KEYCODE_", "");
        switch (k) {
            case "UP": k = "DPAD_UP"; break;
            case "DOWN": k = "DPAD_DOWN"; break;
            case "LEFT": k = "DPAD_LEFT"; break;
            case "RIGHT": k = "DPAD_RIGHT"; break;
            case "OK": case "CENTER": k = "DPAD_CENTER"; break;
            default: break;
        }
        int code = KeyEvent.keyCodeFromString("KEYCODE_" + k);
        if (code == KeyEvent.KEYCODE_UNKNOWN) return false;
        long t = SystemClock.uptimeMillis();
        boolean handled = overlay.key(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        overlay.key(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        return handled;
    }

    private void media(int code) {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    static String keyName(int code) {
        String n = KeyEvent.keyCodeToString(code);
        return n.startsWith("KEYCODE_") ? n.substring(8) : n;
    }

    private static boolean isVolume(int c) {
        return c == KeyEvent.KEYCODE_VOLUME_UP || c == KeyEvent.KEYCODE_VOLUME_DOWN || c == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    static int minuteNow() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    // ---------------------------------------------------------------- volume

    private void volumeKey(int code) {
        if (code == KeyEvent.KEYCODE_VOLUME_MUTE) toggleMute();
        else changeVolume(code == KeyEvent.KEYCODE_VOLUME_UP ? cfg.volumeStep : -cfg.volumeStep);
    }

    void changeVolume(int delta) {
        if (delta != 0 && audio.isStreamMute(AudioManager.STREAM_MUSIC))
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        for (int i = 0; i < Math.abs(delta); i++)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        showVolume();
    }

    void setVolume(int v) {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, v)), 0);
        showVolume();
    }

    void toggleMute() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
        showVolume();
    }

    int volumeLevel() { return audio.getStreamVolume(AudioManager.STREAM_MUSIC); }

    private void showVolume() {
        overlay.volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                audio.isStreamMute(AudioManager.STREAM_MUSIC));
    }

    // ---------------------------------------------------------------- foreground app

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName(), cls = e.getClassName();
        if (p == null || cls == null) return;
        String pkg = p.toString();
        if (pkg.equals(fg) || !isActivity(pkg, cls.toString())) return;
        String old = fg;
        fg = pkg;
        int m = minuteNow();
        if (!old.isEmpty()) for (Rule r : engine.matchApp(false, old, fg, m)) actions.run(r.actions, null);
        for (Rule r : engine.matchApp(true, pkg, fg, m)) actions.run(r.actions, null);
        try { events.emit("app", new JSONObject().put("from", old).put("to", pkg)); } catch (Exception ignored) { }
    }

    private boolean isActivity(String pkg, String cls) {
        try { getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0); return true; }
        catch (Exception e) { return false; }
    }

    void fireEvent(String name, JSONObject data) {
        events.emit(name, data);
        for (Rule r : engine.matchEvent(name, fg, minuteNow())) actions.run(r.actions, null);
    }

    // ---------------------------------------------------------------- text, screenshot

    boolean inputText(String text, boolean append) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || !f.isEditable()) return false;
        String cur = append && f.getText() != null ? f.getText().toString() : "";
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    void screenshot(Consumer<Bitmap> cb) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                    Bitmap b = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                    r.getHardwareBuffer().close();
                    cb.accept(b);
                }
                @Override public void onFailure(int errorCode) { cb.accept(null); }
            });
        } catch (Exception e) {
            cb.accept(null);
        }
    }

    void saveScreenshot() {
        ui.postDelayed(() -> screenshot(b -> {
            if (b == null) { overlay.toast("Screenshot failed"); return; }
            try {
                String name = "PIXIE_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".png";
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PIXIE");
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                try (OutputStream os = getContentResolver().openOutputStream(u)) { b.compress(Bitmap.CompressFormat.PNG, 100, os); }
                overlay.toast("Saved Pictures/PIXIE/" + name);
            } catch (Exception e) { overlay.toast("Screenshot failed: " + e.getMessage()); }
        }), 400);   // let the panel close first
    }

    // ---------------------------------------------------------------- learn mode

    void startLearn() { startLearn(15); }

    /** Learn mode: every remote press is shown (name, keycode, scancode, device) instead of acting. */
    void startLearn(int seconds) {
        int s = Math.max(10, Math.min(seconds, 300));
        learning = true;
        learned.clear();
        overlay.closePanel();
        overlay.card("Learn a button", "Press any button on the remote…\n(ends in " + s + " s, or 15 s after the last press)", false, 0);
        ui.removeCallbacks(learnEnd);
        ui.postDelayed(learnEnd, s * 1000L);
    }

    private boolean learnKey(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            InputDevice dev = InputDevice.getDevice(e.getDeviceId());
            String devName = dev == null ? "?" : dev.getName();
            String line = keyName(e.getKeyCode()) + "   code " + e.getKeyCode() + " · scan " + e.getScanCode() + " · " + devName;
            learned.add(0, line);
            while (learned.size() > 6) learned.remove(learned.size() - 1);
            overlay.card("Learn a button", TextUtils.join("\n", learned), false, 0);
            saveLearned(e, devName);
            ui.removeCallbacks(learnEnd);
            ui.postDelayed(learnEnd, 15_000);
        }
        return true;
    }

    private void stopLearn() {
        learning = false;
        overlay.card("Learn a button", learned.isEmpty() ? "Nothing pressed." : "Use these names in rules.json:\n" + TextUtils.join("\n", learned), false, 8000);
    }

    private void saveLearned(KeyEvent e, String devName) {
        try {
            File f = new File(getExternalFilesDir(null), "learned.json");
            JSONArray a = f.isFile() ? new JSONArray(Config.read(new FileInputStream(f))) : new JSONArray();
            a.put(new JSONObject().put("key", keyName(e.getKeyCode())).put("code", e.getKeyCode())
                    .put("scan", e.getScanCode()).put("device", devName).put("time", System.currentTimeMillis()));
            while (a.length() > 40) a.remove(0);
            Config.write(f, a.toString(2));
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- theme, sleep, state

    /** A preset id, "next", "rotate", "daynight" or "mix" (see Config.applyTheme). */
    void setTheme(String preset) {
        String next = preset;
        if (next == null || next.isEmpty() || "next".equals(next)) {
            int i = 0;
            for (int k = 0; k < Theme.IDS.length; k++) if (Theme.IDS[k].equals(theme.id)) i = k;
            next = Theme.IDS[(i + 1) % Theme.IDS.length];
        }
        try { Config.applyTheme(this, next); } catch (Exception e) { overlay.toast("Could not save theme"); return; }
        reload();
        String label = "rotate".equals(next) ? "rotating daily" : "daynight".equals(next) ? "Aurora by day, Neon Grid at night"
                : "mix".equals(next) ? "Retro + Comic logo" : Theme.name(next);
        // On Home the transition plays and Pixie says it afterwards; elsewhere, say it now.
        if (HomeActivity.current == null) overlay.toast("Ta-da! New look: " + label);
    }

    /** Mascot / opening-animation mode; "next" cycles. Home picks the change up from config.json. */
    String setMode(String key, String value) {
        String[] modes = "mascot".equals(key) ? Config.MASCOT_MODES : Config.LAUNCH_MODES;
        String cur = "mascot".equals(key) ? cfg.mascot : cfg.launchAnimation;
        String v = value == null || value.isEmpty() || "next".equals(value) ? Config.nextOf(modes, cur) : value;
        if (!java.util.Arrays.asList(modes).contains(v)) return null;
        try { Config.put(this, key, v); } catch (Exception e) { overlay.toast("Could not save"); return null; }
        reload();
        return v;
    }

    void sleepTimer(int minutes) {
        ui.removeCallbacks(sleepWarn);
        ui.removeCallbacks(sleepNow);
        if (minutes <= 0) { sleepAt = 0; overlay.toast("Okay, sleep timer cancelled."); return; }
        long ms = minutes * 60_000L;
        sleepAt = SystemClock.uptimeMillis() + ms;
        ui.postDelayed(sleepWarn, Math.max(0, ms - 60_000));
        ui.postDelayed(sleepNow, ms);
        overlay.toast("Okay! I'll turn the TV off in " + minutes + " min.");
    }

    int sleepMinutesLeft() {
        if (sleepAt == 0) return 0;
        long left = sleepAt - SystemClock.uptimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 60_000.0);
    }

    private void sleepWarning() {
        try {
            JSONObject p = new JSONObject().put("title", "Sleep timer").put("text", "The TV turns off in 1 minute.").put("id", "sleep")
                    .put("timeout", 55)
                    .put("buttons", new JSONArray()
                            .put(new JSONObject().put("label", "+30 min").put("do", new JSONArray().put(new JSONObject().put("sleep_timer", 30))))
                            .put(new JSONObject().put("label", "Cancel").put("do", new JSONArray().put(new JSONObject().put("sleep_timer", 0))))
                            .put(new JSONObject().put("label", "OK")));
            actions.exec("popup", p);
        } catch (Exception ignored) { }
    }

    private void sleepNow() {
        sleepAt = 0;
        overlay.closePanel();
        try {   // power off through Home Assistant's Android TV Remote; lock-screen action as a fallback
            ha.service(cfg, "media_player", "turn_off", new JSONObject().put("entity_id", cfg.powerEntity), ui, (b, err) -> {
                if (err != null) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            });
        } catch (Exception e) { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); }
    }

    JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true).put("foreground", fg).put("volume", volumeLevel())
                    .put("max_volume", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    .put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC)).put("theme", theme.id)
                    .put("sleep_minutes_left", sleepMinutesLeft()).put("panel", overlay.panelTitle() == null ? JSONObject.NULL : overlay.panelTitle())
                    .put("rules", engine.all().size()).put("version", HomeActivity.BuildConfigHolder.version(this));
        } catch (Exception ignored) { }
        return o;
    }
}
