package dev.malaiesun.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Ha ha = new Ha();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Config cfg;
    private Theme th;
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout content;
    private TextView clock, date, status;
    private BackgroundView bgView;
    private LogoView logo;
    private MascotView mascot;     // pixel robot that runs under the focus and bumps tiles open
    private LaunchFx fx;           // per-theme app-opening effect
    private ThemeSwap swap;        // theme-change transition in progress
    static HomeActivity current;   // resumed instance (demo hooks for the API)

    // Focus grid: rows of focusable views. section.get(r) is the view row r scrolls to.
    private final List<List<View>> grid = new ArrayList<>();
    private final List<View> section = new ArrayList<>();
    private final Map<View, int[]> pos = new IdentityHashMap<>();
    private final Map<String, TextView> chipViews = new HashMap<>();
    private final Map<String, JSONObject> haStates = new HashMap<>();
    private List<String> lastRecents = new ArrayList<>();
    private int firstContentRow, curRow = -1, curCol, relRow;   // relRow: focus row counted from the first content row

    private boolean resumed, rebuildQueued;
    private FileObserver watcher;
    private BroadcastReceiver pkgReceiver;
    private SharedPreferences prefs;

    private final Runnable rebuild = this::build;
    /** config.json was written (by the agent, the API or adb): rebuild if it really changed. */
    private final Runnable configChanged = () -> {
        if (cfg != null && Config.file(this).lastModified() != cfg.mtime) queueRebuild();
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            if (clock != null) clock.setText(new SimpleDateFormat(cfg.clock24h ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
            if (date != null) date.setText(th.cap(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)));
            Theme t = Theme.resolve(cfg);   // scheduled / rotating themes switch on their own
            if (th != null && (!t.id.equals(th.id) || !t.background.equals(th.background) || !t.logo.equals(th.logo))) { queueRebuild(); return; }
            ui.removeCallbacks(this);
            if (resumed) ui.postDelayed(this, 60_000 - (System.currentTimeMillis() % 60_000) + 50);
        }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            ha.fetch(cfg, ui, HomeActivity.this::onStates);
            ui.removeCallbacks(this);
            if (resumed && cfg.haReady()) ui.postDelayed(this, cfg.haPollSeconds * 1000L);
        }
    };
    private final Runnable pollOnce = () -> ha.fetch(cfg, ui, this::onStates);
    /** The launched app never showed up (Home still in front): uncover. */
    private final Runnable launchWatchdog = () -> { if (resumed && fx.covered()) fx.reveal(); };

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        relRow = prefs.getInt("rel_row", 0);
        curCol = prefs.getInt("col", 0);
        root = new FrameLayout(this);
        setContentView(root);
        mascot = new MascotView(this);
        mascot.setTiles(() -> { List<View> all = new ArrayList<>(); for (List<View> r : grid) all.addAll(r); return all; });
        fx = new LaunchFx(this);
        build();
        Config.listen(configChanged);

        pkgReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { queueRebuild(); }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(pkgReceiver, f);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        current = this;
        boolean changed = Config.file(this).lastModified() != cfg.mtime;
        if (changed || rebuildQueued || !recents(Apps.query(this)).equals(lastRecents)) build();
        animate(true);
        startWatching();
        tick.run();
        poll.run();
        if (fx.covered()) fx.reveal();   // back from an app: play the opening effect in reverse
        greetAfterBoot();
    }

    /** Once per boot, Pixie says hello (waving), then a line for the time of day. */
    private void greetAfterBoot() {
        long boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        if (Math.abs(prefs.getLong("greeted_boot", 0) - boot) < 120_000) return;
        prefs.edit().putLong("greeted_boot", boot).apply();
        greet();
    }

    /** "Hi, I'm Pixie!" (waving) and a line for the time of day. */
    void greet() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String second = h < 5 ? "Late night? I'll keep it cozy." : h < 12 ? "Good morning! What shall we watch?"
                : h < 17 ? "Good afternoon! Ready when you are." : h < 22 ? "Good evening! Gear button = my menu."
                : "Late night? I'll keep it cozy.";
        if (mascot.isOn()) {
            ui.postDelayed(() -> {
                mascot.say("Hi, I'm Pixie!", 2600, true);
                mascot.say(second, 3200, false);
            }, 900);
        } else {
            PixieService agent = PixieService.instance;
            if (agent != null) agent.overlay.toast("Hi, I'm Pixie! " + second);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        if (current == this) current = null;
        animate(false);
        if (watcher != null) watcher.stopWatching();
        ui.removeCallbacks(tick);
        ui.removeCallbacks(poll);
        ui.removeCallbacks(launchWatchdog);
        prefs.edit().putInt("rel_row", curRow - firstContentRow).putInt("col", curCol).apply();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        Config.unlisten(configChanged);
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
    }

    /** Home pressed while already on Home: jump to the top. Coming back from an app keeps the last tile. */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (resumed) goTop();
    }

    private void animate(boolean on) {
        if (bgView != null) bgView.setRunning(on);
        if (logo != null) logo.setRunning(on);
        mascot.setRunning(on);
    }

    private void queueRebuild() {
        if (!resumed) { rebuildQueued = true; return; }
        ui.removeCallbacks(rebuild);
        ui.postDelayed(rebuild, 300);
    }

    private void startWatching() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        if (watcher != null) watcher.stopWatching();
        watcher = new FileObserver(dir, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int ev, String path) {
                ui.post(() -> {
                    boolean cfgChanged = Config.file(HomeActivity.this).lastModified() != cfg.mtime;
                    boolean wpChanged = path != null && !cfg.wallpaper.isEmpty() && path.equals(cfg.wallpaper);
                    if (cfgChanged || wpChanged) queueRebuild();
                });
            }
        };
        watcher.startWatching();
    }

    // ---------------------------------------------------------------- build

    /** Rebuild Home. If the theme changed while Home is on screen, snapshot it first and play the transition. */
    private void build() {
        Theme next = Theme.resolve(Config.load(this));
        boolean changed = th != null && (!next.id.equals(th.id) || !next.background.equals(th.background) || next.bg != th.bg);
        if (!changed || !resumed || root.getWidth() == 0) { buildNow(); return; }
        // PixelCopy never calls back on this firmware, so draw Home into a bitmap directly (once, ~0.1 s)
        Sprites.Set from = Sprites.forTheme(th.id);
        boolean hadPixie = mascot.isOn() && mascot.isShown();
        float[] feet = mascot.feet();
        Bitmap shot = null;
        try {
            shot = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            mascot.setTransition(true);                    // snapshot without Pixie: the transition draws its own
            root.draw(new android.graphics.Canvas(shot));
        } catch (Throwable e) {
            if (shot != null) shot.recycle();
            shot = null;
        }
        mascot.setTransition(false);
        buildNow();
        if (shot != null) startSwap(shot, from, hadPixie, feet);
    }

    private void startSwap(Bitmap shot, Sprites.Set from, boolean hadPixie, float[] feet) {
        if (swap != null) swap.finish();
        ThemeSwap s = new ThemeSwap(this);
        swap = s;
        root.addView(s, root.indexOfChild(fx), new FrameLayout.LayoutParams(-1, -1));   // above Pixie, below the launch effect
        boolean pixieNow = mascot.isOn();
        mascot.setTransition(true);
        s.start(shot, from, pixieNow ? Sprites.forTheme(th.id) : null, th, feet[0], feet[1], hadPixie, () -> {
            root.removeView(s);
            shot.recycle();
            if (swap == s) swap = null;
            mascot.setTransition(false);
            mascot.placeAt(feet[0], feet[1]);
            if (mascot.isOn()) mascot.say("Ta-da! New look: " + Theme.name(th.id), 3000, false);
        });
    }

    private void buildNow() {
        if (swap != null) swap.finish();
        rebuildQueued = false;
        if (curRow >= 0) relRow = curRow - firstContentRow;   // survive the chips row appearing/disappearing
        cfg = Config.load(this);
        th = Theme.resolve(cfg);
        root.removeAllViews();
        grid.clear();
        section.clear();
        pos.clear();
        chipViews.clear();
        bgView = null;

        root.setBackgroundColor(th.bg);
        int sw = getResources().getDisplayMetrics().widthPixels;
        Bitmap wp = cfg.loadWallpaper(this, sw);
        if (wp != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(wp);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            View dim = new View(this);
            dim.setBackgroundColor(Math.round(Math.max(0, Math.min(1, cfg.wallpaperDim)) * 255) << 24);
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        } else {
            bgView = new BackgroundView(this);
            bgView.setTheme(th);
            root.addView(bgView, new FrameLayout.LayoutParams(-1, -1));
        }

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setFocusable(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        final int side = dp(56);
        content.setPadding(side, dp(26), side, dp(160));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        boolean retroGround = bgView != null && "retro".equals(th.background);
        boolean mascotOn = "on".equals(cfg.mascot) || ("auto".equals(cfg.mascot) && "retro".equals(th.background));
        root.addView(mascot, new FrameLayout.LayoutParams(-1, -1));
        mascot.setup(mascotOn, th, retroGround ? "retro" : "fade");
        if (bgView != null) bgView.setGroundStill(mascotOn && retroGround);
        root.addView(fx, new FrameLayout.LayoutParams(-1, -1));

        // Top: [logo  clock/date] on one line, Home Assistant chips on the line below (right-aligned).
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setClipChildren(false);
        top.setClipToPadding(false);
        top.setPadding(0, 0, 0, dp(4));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);
        logo = new LogoView(this);
        logo.setTheme(th);
        header.addView(logo);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(18), 0, 0, 0);
        clock = text(46, th.text);
        boolean light = "aurora".equals(th.id) || "minimal".equals(th.id) || "neon".equals(th.id);
        clock.setTypeface(light ? Typeface.create("sans-serif-light", Typeface.NORMAL) : th.bold());
        if (!"comic".equals(th.id)) clock.setShadowLayer(dp(6), 0, 0, 0x66000000);
        date = text(16, th.textDim);
        date.setTypeface(th.typeface());
        left.addView(clock);
        left.addView(date);
        if (!cfg.greeting.isEmpty()) { TextView g = text(15, th.textDim); g.setText(cfg.greeting); left.addView(g); }
        header.addView(left, new LinearLayout.LayoutParams(-2, -2));
        top.addView(header, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        chips.setClipChildren(false);
        chips.setPadding(0, dp(6), 0, 0);
        top.addView(chips, new LinearLayout.LayoutParams(-1, -2));
        content.addView(top, new LinearLayout.LayoutParams(-1, -2));

        status = text(14, "comic".equals(th.id) ? 0xFFB00020 : 0xFFFF8A80);
        status.setVisibility(View.GONE);
        content.addView(status);
        if (cfg.error != null) showStatus(cfg.error);

        List<View> chipRow = new ArrayList<>();
        for (Config.Chip ch : cfg.chips) {
            TextView v = chip(ch);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(10));
            chips.addView(v, lp);
            chipRow.add(v);
            chipViews.put(ch.entity, v);
        }
        if (!chipRow.isEmpty()) addRow(chipRow, top);
        firstContentRow = grid.size();

        // Content rows, in the order config.json lists them.
        LinkedHashMap<String, Apps.App> apps = Apps.query(this);
        int avail = sw - 2 * side;
        int gap = dp(20);
        int gridW = (avail - (cfg.columns - 1) * gap) / cfg.columns;
        int favW = Math.min(dp(cfg.favTileDp), avail);
        for (String r : cfg.rows) {
            switch (r) {
                case "favorites": {
                    List<Tile> ts = new ArrayList<>();
                    for (String p : cfg.favorites) { Apps.App a = apps.get(p); if (a != null) ts.add(appTile(a, favW)); }
                    hRow("Favorites", ts, gap, side);
                    break;
                }
                case "inputs": {
                    List<Tile> ts = new ArrayList<>();
                    for (Apps.Input in : Apps.inputs(this)) {
                        String cap = in.renamed() ? in.port + (in.connected() ? " · Connected" : "") : (in.connected() ? "Connected" : "");
                        Tile t = new Tile(this, th, gridW).text(in.label).caption(cap).dot(in.connected());
                        t.setOnClickListener(v -> launch(t, Apps.inputIntent(in)));
                        ts.add(t);
                    }
                    if (Apps.launch(this, cfg.liveTvPackage) != null) {
                        Tile t = new Tile(this, th, gridW).text("Live TV").caption("Antenna / cable");
                        t.setOnClickListener(v -> launch(t, Apps.launch(this, cfg.liveTvPackage)));
                        ts.add(t);
                    }
                    hRow("Inputs", ts, gap, side);
                    break;
                }
                case "recent": {
                    lastRecents = recents(apps);
                    List<Tile> ts = new ArrayList<>();
                    for (String p : lastRecents) ts.add(appTile(apps.get(p), gridW));
                    hRow("Recent", ts, gap, side);
                    break;
                }
                case "apps": {
                    List<Tile> ts = new ArrayList<>();
                    for (Apps.App a : apps.values())
                        if (!cfg.hidden.contains(a.pkg) && !cfg.favorites.contains(a.pkg)) ts.add(appTile(a, gridW));
                    gridRows("Apps", ts, gap);
                    break;
                }
                default: break;
            }
        }

        animate(resumed);
        tick.run();
        applyChipStates();
        if (grid.isEmpty()) return;
        int r = firstContentRow + relRow, c = curCol;
        if (r < 0 || r >= grid.size()) { r = Math.min(firstContentRow, grid.size() - 1); c = 0; }
        final int fr = r, fc = c;
        root.post(() -> root.post(() -> focusAt(fr, fc, false)));
    }

    private void hRow(String title, List<Tile> ts, int gap, int side) {
        if (ts.isEmpty()) return;
        LinearLayout sec = section(title);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setFocusable(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int vp = dp(14);
        row.setPadding(side, vp, side, vp);   // row runs edge to edge, tiles start at the content margin
        List<View> vs = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.setMarginStart(gap);
            row.addView(ts.get(i), lp);
            vs.add(ts.get(i));
        }
        hs.addView(row);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.setMargins(-side, 0, -side, 0);
        sec.addView(hs, hlp);
        addRow(vs, sec);
    }

    private void gridRows(String title, List<Tile> ts, int gap) {
        if (ts.isEmpty()) return;
        LinearLayout sec = section(title);
        int vp = dp(14);
        for (int i = 0; i < ts.size(); i += cfg.columns) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            line.setClipToPadding(false);
            line.setPadding(0, vp, 0, vp);
            List<View> vs = new ArrayList<>();
            for (int j = i; j < Math.min(ts.size(), i + cfg.columns); j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (j > i) lp.setMarginStart(gap);
                line.addView(ts.get(j), lp);
                vs.add(ts.get(j));
            }
            sec.addView(line);
            addRow(vs, i == 0 ? sec : line);
        }
    }

    private LinearLayout section(String title) {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setClipChildren(false);
        sec.setClipToPadding(false);
        TextView h = text(20, th.textDim);
        h.setTypeface(th.bold());
        h.setText(th.cap(title));
        h.setPadding(0, dp(20), 0, 0);
        if ("comic".equals(th.id) || "retro".equals(th.id)) h.setTextColor(th.text);
        if ("retro".equals(th.id)) h.setShadowLayer(1, dp(3), dp(3), 0xFF000000);
        sec.addView(h);
        content.addView(sec, new LinearLayout.LayoutParams(-1, -2));
        return sec;
    }

    private void addRow(List<View> vs, View sectionView) {
        int r = grid.size();
        grid.add(vs);
        section.add(sectionView);
        for (int i = 0; i < vs.size(); i++) pos.put(vs.get(i), new int[]{r, i});
    }

    private Tile appTile(Apps.App a, int w) {
        Tile t = new Tile(this, th, w).caption(a.label);
        t.setOnClickListener(v -> launch(t, Apps.launch(this, a.pkg)));
        t.setOnLongClickListener(v -> { appMenu(a); return true; });
        io.execute(() -> {
            Drawable b = Apps.banner(this, a);
            Drawable i = b == null ? Apps.icon(this, a) : null;
            ui.post(() -> { if (b != null) t.banner(b); else t.icon(i); });
        });
        return t;
    }

    // ---------------------------------------------------------------- focus

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (fx.busy() || mascot.bumping()) return true;            // mid-jump / mid-effect: hold keys
        if (fx.covered()) {                                         // app still starting: Back cancels
            if (k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) fx.reveal();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) mascot.poke();
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) goTop();
            return true; // Home has nowhere to go back to
        }
        boolean arrow = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (arrow) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) move(k);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Deterministic: left/right stay in the row, up/down go exactly one row to the tile nearest in x. */
    private void move(int k) {
        if (grid.isEmpty()) return;
        int[] p = pos.get(getCurrentFocus());
        if (p == null) { focusAt(Math.max(0, Math.min(curRow, grid.size() - 1)), curCol, true); return; }
        int r = p[0], c = p[1];
        View from = grid.get(r).get(c);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:  if (c > 0) focusAt(r, c - 1, true); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (c < grid.get(r).size() - 1) focusAt(r, c + 1, true); break;
            case KeyEvent.KEYCODE_DPAD_UP:    if (r > 0) focusAt(r - 1, nearest(r - 1, from), true); break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  if (r < grid.size() - 1) focusAt(r + 1, nearest(r + 1, from), true); break;
            default: break;
        }
    }

    private int nearest(int row, View from) {
        int[] a = new int[2];
        from.getLocationOnScreen(a);
        float cx = a[0] + from.getWidth() / 2f;
        List<View> vs = grid.get(row);
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < vs.size(); i++) {
            View v = vs.get(i);
            v.getLocationOnScreen(a);
            float d = Math.abs(a[0] + v.getWidth() / 2f - cx);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private void focusAt(int r, int c, boolean smooth) {
        if (r < 0 || r >= grid.size() || grid.get(r).isEmpty()) return;
        List<View> row = grid.get(r);
        c = Math.max(0, Math.min(c, row.size() - 1));
        View v = row.get(c);
        v.requestFocus();
        mascot.follow(v);
        curRow = r;
        curCol = c;
        int y = r <= firstContentRow ? 0 : Math.max(0, topIn(section.get(r)) - dp(16));
        if (smooth) scroll.smoothScrollTo(0, y); else scroll.scrollTo(0, y);
        if (v.getParent() instanceof View && ((View) v.getParent()).getParent() instanceof HorizontalScrollView) {
            View rowView = (View) v.getParent();
            HorizontalScrollView hs = (HorizontalScrollView) rowView.getParent();
            int pad = rowView.getPaddingStart(), x = hs.getScrollX(), w = hs.getWidth();
            int target = x;
            if (v.getLeft() - pad < x) target = v.getLeft() - pad;
            else if (v.getRight() + pad > x + w) target = v.getRight() + pad - w;
            if (target != x) { if (smooth) hs.smoothScrollTo(target, 0); else hs.scrollTo(target, 0); }
        }
    }

    private int topIn(View v) {
        int y = 0;
        for (View p = v; p != null && p != content; p = p.getParent() instanceof View ? (View) p.getParent() : null) y += p.getTop();
        return y;
    }

    private void goTop() {
        if (grid.isEmpty()) return;
        focusAt(Math.min(firstContentRow, grid.size() - 1), 0, true);
    }

    // ---------------------------------------------------------------- Home Assistant chips

    private TextView chip(Config.Chip ch) {
        TextView v = text(16, th.text);
        v.setTypeface(th.bold());
        v.setText(Ha.format(ch, null));
        int ph = dp(16), pv = dp(9);
        v.setPadding(ph, pv, ph, pv);
        v.setFocusable(true);
        v.setClickable(true);
        v.setRevealOnFocusHint(false);
        styleChip(v, false, false);
        v.setOnFocusChangeListener((x, has) -> styleChip(v, has, Ha.active(haStates.get(ch.entity))));
        v.setOnClickListener(x -> {
            mascot.hop(dp(26));
            if ("none".equals(ch.tap)) return;
            String domain = "homeassistant", service = "toggle";
            if (ch.tap.contains(".")) { String[] s = ch.tap.split("\\.", 2); domain = s[0]; service = s[1]; }
            ha.call(cfg, domain, service, ch.entity, ui, () -> ui.postDelayed(pollOnce, 700));
        });
        return v;
    }

    private void styleChip(TextView v, boolean focused, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(th.radiusDp == 0 ? 0 : dp(999));
        int bg = active ? th.accent : (th.surface & 0x00FFFFFF) | 0xDD000000;
        g.setColor(bg);
        if (focused) g.setStroke(dp(th.ringDp), th.ring);
        else if (th.borderDp > 0) g.setStroke(dp(th.borderDp), th.border);
        v.setBackground(g);
        v.setTextColor(Theme.on(bg | 0xFF000000));
        v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(120).start();
    }

    private void onStates(Map<String, JSONObject> states, String err) {
        haStates.putAll(states);
        applyChipStates();
        if (err != null && states.isEmpty()) showStatus("Home Assistant: " + err);
        else if (cfg.error == null && status != null) status.setVisibility(View.GONE);
    }

    private void applyChipStates() {
        for (Config.Chip ch : cfg.chips) {
            TextView v = chipViews.get(ch.entity);
            if (v == null) continue;
            JSONObject s = haStates.get(ch.entity);
            v.setText(Ha.format(ch, s));
            styleChip(v, v.isFocused(), Ha.active(s));
        }
    }

    // ---------------------------------------------------------------- menus

    private void appMenu(Apps.App a) {
        boolean fav = cfg.favorites.contains(a.pkg);
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (fav) {
            int i = cfg.favorites.indexOf(a.pkg);
            items.add("Remove from Favorites"); acts.add(() -> cfg.favorites.remove(a.pkg));
            if (i > 0) { items.add("Move left"); acts.add(() -> { Collections.swap(cfg.favorites, i, i - 1); curCol = Math.max(0, curCol - 1); }); }
            if (i < cfg.favorites.size() - 1) { items.add("Move right"); acts.add(() -> { Collections.swap(cfg.favorites, i, i + 1); curCol++; }); }
        } else {
            items.add("Add to Favorites"); acts.add(() -> cfg.favorites.add(a.pkg));
        }
        items.add("Hide from Home"); acts.add(() -> { cfg.hidden.add(a.pkg); cfg.favorites.remove(a.pkg); });
        items.add("App info"); acts.add(null);
        final int homeOptions = items.size();
        items.add("PIXIE Home options..."); acts.add(null);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == homeOptions) { homeMenu(); return; }
                    Runnable act = acts.get(which);
                    if (act == null) {
                        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    }
                    act.run();
                    saveLists();
                })
                .show();
    }

    private void homeMenu() {
        PixieService agent = PixieService.instance;
        String[] items = {
                "Theme: " + Theme.name(th.id) + "...",
                "Mascot: " + Config.mascotLabel(cfg.mascot) + "  (OK to change)",
                "Opening animation: " + Config.launchLabel(cfg.launchAnimation) + "  (OK to change)",
                "Learn a remote button",
                "Pair a phone / PC",
                "Show hidden apps (" + cfg.hidden.size() + ")",
                "Reload config.json",
                "About"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("PIXIE Home")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: themeMenu(); break;
                        case 1: setOption("mascot", Config.nextOf(Config.MASCOT_MODES, cfg.mascot)); homeMenu(); break;
                        case 2: setOption("launch_animation", Config.nextOf(Config.LAUNCH_MODES, cfg.launchAnimation)); homeMenu(); break;
                        case 3: if (agent != null) agent.startLearn(); else toast("Turn on the PIXIE agent (accessibility) first"); break;
                        case 4: if (agent != null) agent.server.startPairing("menu"); else toast("Turn on the PIXIE agent (accessibility) first"); break;
                        case 5: cfg.hidden.clear(); saveLists(); break;
                        case 6: build(); break;
                        default:
                            toast("PIXIE Home " + BuildConfigHolder.version(this) + "\nAgent: " + (agent == null ? "off" : "on, port " + agent.server.port)
                                    + "\n" + Config.file(this));
                    }
                })
                .show();
    }

    private void themeMenu() {
        List<String> items = new ArrayList<>();
        Collections.addAll(items, Theme.NAMES);
        items.add("Rotate all themes daily");
        items.add("Day / night: Aurora by day, Neon Grid after 19:00");
        items.add("Mix: Retro background + Comic logo");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Theme")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    int n = Theme.IDS.length;
                    String choice = which < n ? Theme.IDS[which] : which == n ? "rotate" : which == n + 1 ? "daynight" : "mix";
                    try { Config.applyTheme(this, choice); } catch (Exception e) { toast("Could not save: " + e.getMessage()); }
                    build();
                    PixieService agent = PixieService.instance;
                    if (agent != null) agent.reload();
                })
                .show();
    }

    private void setOption(String key, String value) {
        try { Config.put(this, key, value); } catch (Exception e) { toast("Could not save: " + e.getMessage()); return; }
        build();
        PixieService agent = PixieService.instance;
        if (agent != null) agent.reload();
    }

    private void saveLists() {
        try { cfg.saveLists(this); } catch (Exception e) { toast("Could not save: " + e.getMessage()); }
        build();
    }

    // ---------------------------------------------------------------- recents

    private List<String> recents(Map<String, Apps.App> apps) {
        List<String> out = new ArrayList<>();
        if (cfg == null || cfg.recentCount == 0 || !usageAccess()) return out;
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        List<UsageStats> st = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 14L * 86_400_000L, now);
        if (st == null) return out;
        Map<String, Long> last = new HashMap<>();
        for (UsageStats u : st) if (u.getTotalTimeInForeground() > 0) last.merge(u.getPackageName(), u.getLastTimeUsed(), Math::max);
        List<Map.Entry<String, Long>> es = new ArrayList<>(last.entrySet());
        es.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        for (Map.Entry<String, Long> e : es) {
            String p = e.getKey();
            if (!apps.containsKey(p) || cfg.hidden.contains(p) || p.equals(cfg.liveTvPackage)) continue;
            out.add(p);
            if (out.size() >= cfg.recentCount) break;
        }
        return out;
    }

    private boolean usageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    // ---------------------------------------------------------------- helpers

    private int dp(float v) { return Ui.dp(this, v); }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private void showStatus(String s) {
        if (status == null) return;
        status.setText(s);
        status.setVisibility(View.VISIBLE);
    }

    /** API demo: this theme's whole app-opening sequence on the focused tile, then back (nothing opens). */
    boolean demoOpen() {
        View v = getCurrentFocus();
        if (!(v instanceof Tile) || fx.busy() || fx.covered() || mascot.bumping()) return false;
        Runnable effect = () -> fx.cover(th, rectIn(fx, v), () -> ui.postDelayed(fx::reveal, 700));
        if (!mascot.bump(v, effect)) effect.run();
        return true;
    }

    /** Open from a tile: mascot jump (if on), the theme's opening effect, then the app. */
    private void launch(View tile, Intent i) {
        if (i == null) { toast("Not available"); return; }
        String mode = cfg.launchAnimation;
        if ("off".equals(mode)) { start(i); return; }
        if ("system".equals(mode)) {
            try { startActivity(i, ActivityOptions.makeScaleUpAnimation(tile, 0, 0, tile.getWidth(), tile.getHeight()).toBundle()); }
            catch (Exception e) { toast("Could not open: " + e.getMessage()); }
            return;
        }
        Runnable effect = () -> fx.cover(th, rectIn(fx, tile), () -> startCovered(i));
        if (!mascot.bump(tile, effect)) effect.run();
    }

    private void startCovered(Intent i) {
        try {
            startActivity(i, ActivityOptions.makeCustomAnimation(this, R.anim.pixie_open, R.anim.pixie_hold).toBundle());
            ui.removeCallbacks(launchWatchdog);
            ui.postDelayed(launchWatchdog, 6000);
        } catch (Exception e) {
            fx.reveal();
            toast("Could not open: " + e.getMessage());
        }
    }

    /** A tile's card (or any view) as a rect in `in`'s coordinates, focus scale included. */
    private static RectF rectIn(View in, View v) {
        View target = v instanceof Tile ? ((Tile) v).card : v;
        int[] a = new int[2], me = new int[2];
        target.getLocationOnScreen(a);
        in.getLocationOnScreen(me);
        float x = a[0] - me[0], y = a[1] - me[1];
        return new RectF(x, y, x + target.getWidth() * v.getScaleX(), y + target.getHeight() * v.getScaleY());
    }

    private void start(Intent i) {
        if (i == null) { toast("Not available"); return; }
        try { startActivity(i); } catch (Exception e) { toast("Could not open: " + e.getMessage()); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    /** Version name without depending on generated BuildConfig. */
    static final class BuildConfigHolder {
        static String version(Context c) {
            try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }
    }
}
