package dev.malaiesun.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launchable apps and TV inputs. */
final class Apps {
    private Apps() { }

    static final class App {
        final String pkg;
        final ResolveInfo ri;
        final String label;
        App(String pkg, ResolveInfo ri, String label) { this.pkg = pkg; this.ri = ri; this.label = label; }
    }

    /** All launchable apps (Leanback first, then phone-style launchers), sorted by label. Excludes this app. */
    static LinkedHashMap<String, App> query(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> m = new HashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = ri.activityInfo.packageName;
                if (!m.containsKey(p)) m.put(p, new App(p, ri, String.valueOf(ri.loadLabel(pm))));
            }
        }
        m.remove(c.getPackageName());
        List<App> l = new ArrayList<>(m.values());
        l.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        LinkedHashMap<String, App> out = new LinkedHashMap<>();
        for (App a : l) out.put(a.pkg, a);
        return out;
    }

    static Intent launch(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    /** Banner if the app has one (16:9 art), else null. Slow-ish: call off the UI thread. */
    static Drawable banner(Context c, App a) {
        try { return a.ri.activityInfo.loadBanner(c.getPackageManager()); } catch (Exception e) { return null; }
    }

    static Drawable icon(Context c, App a) {
        try { return a.ri.loadIcon(c.getPackageManager()); } catch (Exception e) { return null; }
    }

    // ---- TV inputs ----

    static final class Input {
        final String id, label, port;   // label: custom/CEC name if set ("Snowbyte"), port: "HDMI 1"
        final int state, type;
        Input(String id, String label, String port, int state, int type) {
            this.id = id; this.label = label; this.port = port; this.state = state; this.type = type;
        }
        boolean connected() { return state != TvInputManager.INPUT_STATE_DISCONNECTED; }
        boolean renamed() { return !label.equals(port); }
    }

    /** Physical inputs (HDMI, AV, ...) from the TV input framework, HDMI first. */
    static List<Input> inputs(Context c) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (!info.isPassthroughInput() || info.getParentId() != null || info.isHidden(c)) continue;
            int t = info.getType();
            if (t == TvInputInfo.TYPE_TUNER || t == TvInputInfo.TYPE_OTHER) continue;
            String port = String.valueOf(info.loadLabel(c));
            CharSequence custom = info.loadCustomLabel(c);
            String label = custom != null && custom.length() > 0 ? String.valueOf(custom) : port;
            out.add(new Input(info.getId(), label, port, tim.getInputState(info.getId()), t));
        }
        // Port order (HDMI 1, 2, 3, then AV...), never by the device name plugged into it.
        out.sort((a, b) -> {
            boolean ah = a.type == TvInputInfo.TYPE_HDMI, bh = b.type == TvInputInfo.TYPE_HDMI;
            if (ah != bh) return ah ? -1 : 1;
            return a.port.compareToIgnoreCase(b.port);
        });
        return out;
    }

    static Intent inputIntent(Input in) {
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
