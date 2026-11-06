package dev.malaiesun.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything PIXIE draws over other apps: interactive panels (D-pad driven by the service's key
 * filter, so the windows never steal focus), the volume slider, toasts and message cards.
 * Windows are TYPE_ACCESSIBILITY_OVERLAY: no "draw over apps" permission needed.
 */
final class Overlay {

    static final class Entry {
        String label = "", sub = "", id = "";
        boolean active, keepOpen;
        Drawable art;
        Runnable action;
        Tile view;

        static Entry of(String label, String sub, Runnable action) {
            Entry e = new Entry(); e.label = label; e.sub = sub == null ? "" : sub; e.action = action; return e;
        }
    }

    static final class Panel {
        String title = "", subtitle = "";
        String say = "";                    // what Pixie says at the top of the panel
        final List<List<Entry>> rows = new ArrayList<>();
        final List<String> rowTitles = new ArrayList<>();
        int tileWdp = 200, tileHdp = 0;     // 0 = 16:9
        long autoCloseMs;
        Runnable onClose;
        int r, c;

        Panel row(String title, List<Entry> es) {
            if (es != null && !es.isEmpty()) { rows.add(es); rowTitles.add(title); }
            return this;
        }

        Entry selected() { return rows.isEmpty() ? null : rows.get(r).get(c); }
    }

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float d;
    private Theme th = Theme.preset("aurora");

    private Panel panel;
    private FrameLayout panelRoot;
    private ScrollView panelScroll;
    private final List<View> rowViews = new ArrayList<>();
    private final Runnable autoClose = this::closePanel;

    private FrameLayout volRoot;
    private VolumeView vol;
    private final Runnable volHide = () -> { remove(volRoot); volRoot = null; };

    private LinearLayout toast;
    private TextView toastText;
    private BuddyView toastPixie;
    private final Runnable toastHide = () -> { remove(toast); toast = null; };

    private FrameLayout cardRoot;
    private TextView cardTitle, cardBody;
    private BuddyView cardPixie;
    // the open panel's Pixie and speech bubble (a toast while a panel is open is said there)
    private BuddyView panelPixie;
    private TextView panelBubble;
    private String panelSay = "";
    private final Runnable panelSayRestore = () -> {
        if (panelBubble == null) return;
        panelBubble.setText(panelSay);
        panelBubble.setVisibility(panelSay.isEmpty() ? View.GONE : View.VISIBLE);
    };
    private BarsView bars;
    private final Runnable cardHide = this::dismissCard;

    Overlay(AccessibilityService s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        d = s.getResources().getDisplayMetrics().density;
    }

    void setTheme(Theme t) { th = t; }

    private int dp(float v) { return Math.round(v * d); }

    // ---------------------------------------------------------------- windows

    private WindowManager.LayoutParams params(int w, int h, int gravity) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        return p;
    }

    private void add(View v, WindowManager.LayoutParams p) {
        try { wm.addView(v, p); } catch (Exception ignored) { }
    }

    /** Immediate, so a closing panel's Pixie is gone before anything else speaks (Home's Pixie takes over). */
    private void remove(View v) {
        if (v != null && v.isAttachedToWindow()) try { wm.removeViewImmediate(v); } catch (Exception ignored) { }
    }

    private GradientDrawable cardBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(th.radiusDp + 8));
        if (th.borderDp > 0) g.setStroke(dp(th.borderDp), th.border);
        return g;
    }

    private int panelColor() { return (th.surface & 0x00FFFFFF) | 0xF2000000; }

    private TextView tv(float sp, int color, Typeface tf) {
        TextView t = new TextView(svc);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private int onPanel() { return Theme.on(panelColor()); }

    // ---------------------------------------------------------------- panels

    boolean panelOpen() { return panel != null; }

    String panelTitle() { return panel == null ? null : panel.title; }

    void openPanel(Panel p) {
        closePanel();
        panel = p;
        rowViews.clear();
        panelRoot = new FrameLayout(svc);
        panelRoot.setBackgroundColor(0xA6000000);

        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(panelColor()));
        int pad = dp(30);
        card.setPadding(pad, dp(24), pad, dp(24));
        // clipChildren clips each child to its OWN bounds: on, so the scroll area can't paint over the header
        card.setClipChildren(true);

        // one line: [Pixie] TITLE (speech bubble)
        LinearLayout head = new LinearLayout(svc);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        head.setBaselineAligned(false);                      // baseline alignment would clip the bubble
        head.setPadding(0, dp(8), 0, 0);                    // room for Pixie's pop-in overshoot
        BuddyView pixie = new BuddyView(svc, th, 3f);
        pixie.pose(BuddyView.WAVE, 900);
        head.addView(pixie);
        TextView title = tv(26, onPanel(), th.bold());
        title.setText(th.cap(p.title));
        title.setSingleLine();
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMarginStart(dp(12));
        head.addView(title, tlp);
        String say = p.say.isEmpty() ? sayFor(p.title) : p.say;
        FrameLayout bubbleBox = new FrameLayout(svc);
        panelPixie = pixie;
        panelSay = say;
        panelBubble = Bubble.text(svc, th, 17, Bubble.TAIL_LEFT);
        panelBubble.setText(say);
        panelBubble.setVisibility(say.isEmpty() ? View.GONE : View.VISIBLE);
        bubbleBox.addView(panelBubble, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams bblp = new LinearLayout.LayoutParams(0, -2, 1);
        bblp.setMarginStart(dp(10));
        head.addView(bubbleBox, bblp);
        card.addView(head);
        pixie.post(pixie::popIn);
        if (!p.subtitle.isEmpty()) {
            TextView sub = tv(16, (onPanel() & 0x00FFFFFF) | 0xBB000000, th.typeface());
            sub.setText(p.subtitle);
            sub.setPadding(0, dp(6), 0, 0);
            sub.setMaxLines(6);
            card.addView(sub);
        }

        final int sh = svc.getResources().getDisplayMetrics().heightPixels;
        final int maxScroll = sh - dp(p.subtitle.isEmpty() ? 170 : 205);
        panelScroll = new ScrollView(svc) {      // never taller than the screen
            @Override protected void onMeasure(int ws, int hs) {
                super.onMeasure(ws, View.MeasureSpec.makeMeasureSpec(maxScroll, View.MeasureSpec.AT_MOST));
            }
        };
        panelScroll.setVerticalScrollBarEnabled(false);
        panelScroll.setVerticalFadingEdgeEnabled(true);
        panelScroll.setFadingEdgeLength(dp(28));
        LinearLayout rowsCol = new LinearLayout(svc);
        rowsCol.setOrientation(LinearLayout.VERTICAL);
        rowsCol.setClipChildren(false);
        rowsCol.setClipToPadding(false);
        int sw = svc.getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min(sw - dp(80), dp(1200));
        // Shrink tiles so the longest row fits the card (no clipped labels).
        int maxN = 1;
        for (List<Entry> r : p.rows) maxN = Math.max(maxN, r.size());
        int inner = cardW - 2 * pad - dp(36);                         // minus each row's side padding
        int fitW = (inner - (maxN - 1) * dp(18)) / maxN;
        int tw = Math.max(Math.min(dp(p.tileWdp), fitW), dp(128));   // past this, rows scroll instead
        float scale = tw / (float) dp(p.tileWdp);
        int thh = p.tileHdp > 0 ? Math.round(dp(p.tileHdp) * Math.max(0.75f, scale)) : tw * 9 / 16;
        Theme tileTheme = th.withSurface("comic".equals(th.id) ? 0xFFFFFFFF : Theme.shade(panelColor(), 0.12f));
        for (int i = 0; i < p.rows.size(); i++) {
            String rt = p.rowTitles.get(i);
            if (rt != null && !rt.isEmpty()) {
                TextView h = tv(16, (onPanel() & 0x00FFFFFF) | 0x99000000, th.bold());
                h.setText(th.cap(rt));
                h.setPadding(dp(4), dp(16), 0, 0);
                rowsCol.addView(h);
            }
            HorizontalScrollView hs = new HorizontalScrollView(svc);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            LinearLayout row = new LinearLayout(svc);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setPadding(dp(18), dp(14), dp(18), dp(14));
            for (int k = 0; k < p.rows.get(i).size(); k++) {
                Entry e = p.rows.get(i).get(k);
                Tile t = new Tile(svc, tileTheme, tw, thh);
                t.setFocusable(false);
                if (e.art != null) t.banner(e.art).caption(e.label);
                else t.text(e.label).caption(e.sub);
                t.active(e.active);
                e.view = t;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (k > 0) lp.setMarginStart(dp(18));
                row.addView(t, lp);
            }
            hs.addView(row);
            rowsCol.addView(hs, new LinearLayout.LayoutParams(-1, -2));
            rowViews.add(hs);
        }
        panelScroll.addView(rowsCol);
        card.addView(panelScroll, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(cardW, -2, Gravity.CENTER);
        panelRoot.addView(card, clp);
        add(panelRoot, params(-1, -1, Gravity.CENTER));
        card.setScaleX(0.94f); card.setScaleY(0.94f); card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start();
        p.r = 0; p.c = 0;
        panelRoot.post(() -> select(0, 0));
        armAutoClose();
    }

    void closePanel() {
        ui.removeCallbacks(autoClose);
        if (panel == null) return;
        Runnable onClose = panel.onClose;
        remove(panelRoot);
        panelRoot = null;
        panel = null;
        panelPixie = null;
        panelBubble = null;
        ui.removeCallbacks(panelSayRestore);
        if (onClose != null) onClose.run();
    }

    /** Refresh one entry's text/state (HA updates). */
    void updateEntry(Entry e) {
        if (e.view == null) return;
        if (e.art == null) e.view.text(e.label).caption(e.sub); else e.view.caption(e.label);
        e.view.active(e.active);
    }

    private void armAutoClose() {
        ui.removeCallbacks(autoClose);
        if (panel != null && panel.autoCloseMs > 0) ui.postDelayed(autoClose, panel.autoCloseMs);
    }

    /** D-pad routing while a panel is open. Returns false for keys the service should handle. */
    boolean key(KeyEvent e) {
        if (panel == null) return false;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        if (k == KeyEvent.KEYCODE_HOME) { if (e.getAction() == KeyEvent.ACTION_DOWN) closePanel(); return false; }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        armAutoClose();
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: select(panel.r, panel.c - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: select(panel.r, panel.c + 1); break;
            case KeyEvent.KEYCODE_DPAD_UP: if (panel.r > 0) select(panel.r - 1, nearest(panel.r - 1)); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (panel.r < panel.rows.size() - 1) select(panel.r + 1, nearest(panel.r + 1)); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                Entry sel = panel.selected();
                if (sel != null && sel.action != null) {
                    if (!sel.keepOpen) closePanel();
                    sel.action.run();
                }
                break;
            }
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE: closePanel(); break;
            default: break;
        }
        return true;
    }

    private int nearest(int row) {
        Entry cur = panel.selected();
        if (cur == null || cur.view == null) return 0;
        int[] a = new int[2];
        cur.view.getLocationOnScreen(a);
        float cx = a[0] + cur.view.getWidth() / 2f, best = Float.MAX_VALUE;
        int bi = 0;
        List<Entry> es = panel.rows.get(row);
        for (int i = 0; i < es.size(); i++) {
            es.get(i).view.getLocationOnScreen(a);
            float dd = Math.abs(a[0] + es.get(i).view.getWidth() / 2f - cx);
            if (dd < best) { best = dd; bi = i; }
        }
        return bi;
    }

    private void select(int r, int c) {
        if (panel == null || panel.rows.isEmpty()) return;
        r = Math.max(0, Math.min(r, panel.rows.size() - 1));
        c = Math.max(0, Math.min(c, panel.rows.get(r).size() - 1));
        Entry old = panel.selected();
        if (old != null && old.view != null) old.view.highlight(false);
        panel.r = r; panel.c = c;
        Entry e = panel.selected();
        if (e == null || e.view == null) return;
        e.view.highlight(true);
        View hsv = rowViews.get(r);
        if (hsv instanceof HorizontalScrollView) {
            HorizontalScrollView hs = (HorizontalScrollView) hsv;
            int x = hs.getScrollX(), w = hs.getWidth(), m = dp(40);
            int l = e.view.getLeft(), rr = e.view.getRight();
            if (l - m < x) hs.smoothScrollTo(Math.max(0, l - m), 0);
            else if (rr + m > x + w) hs.smoothScrollTo(rr + m - w, 0);
        }
        if (panelScroll != null) {
            int top = hsv.getTop() - dp(40), bottom = hsv.getBottom() + dp(20);
            int y = panelScroll.getScrollY(), h = panelScroll.getHeight();
            if (top < y) panelScroll.smoothScrollTo(0, Math.max(0, top));
            else if (bottom > y + h) panelScroll.smoothScrollTo(0, bottom - h);
        }
    }

    // ---------------------------------------------------------------- volume

    void volume(int level, int max, boolean muted) {
        if (volRoot == null) {
            volRoot = new FrameLayout(svc);
            vol = new VolumeView(svc);
            FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(dp(96), dp(380), Gravity.CENTER);
            volRoot.addView(vol, vlp);
            WindowManager.LayoutParams p = params(dp(150), dp(440), Gravity.END | Gravity.CENTER_VERTICAL);
            p.x = dp(36);
            add(volRoot, p);
            vol.setTranslationX(dp(120));
            vol.animate().translationX(0).setDuration(160).start();
        }
        vol.set(level, max, muted);
        ui.removeCallbacks(volHide);
        ui.postDelayed(volHide, 1700);
    }

    private final class VolumeView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private int level, max = 100;
        private boolean muted;
        private long bumpAt;

        VolumeView(Context c) { super(c); }

        void set(int l, int m, boolean mu) {
            if (l != level) bumpAt = SystemClock.uptimeMillis();
            level = l; max = Math.max(1, m); muted = mu;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), f = muted ? 0 : level / (float) max;
            float textH = dp(48), top = textH, bottom = h - dp(8);
            p.reset(); p.setAntiAlias(true);
            p.setTypeface(th.bold());
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(26));
            int textColor = "comic".equals(th.id) ? 0xFF111111 : 0xFFFFFFFF;
            float bump = Math.max(0, 1 - (SystemClock.uptimeMillis() - bumpAt) / 180f);
            switch (th.id) {
                case "retro": {
                    int n = 16;
                    float gap = dp(4), bh = (bottom - top - gap * (n - 1)) / n;
                    for (int i = 0; i < n; i++) {
                        float y = bottom - (i + 1) * bh - i * gap;
                        boolean on = (i + 1) / (float) n <= f + 0.001f;
                        p.setColor(0xFF000000); c.drawRect(dp(14), y + dp(3), w - dp(8), y + bh + dp(3), p);
                        p.setColor(on ? (i > n * 0.75f ? th.accent : th.accent2) : 0xFF3A3A55);
                        c.drawRect(dp(10), y, w - dp(12), y + bh, p);
                    }
                    break;
                }
                case "comic": {
                    rf.set(dp(18), top + dp(6), w - dp(8), bottom + dp(2));
                    p.setColor(0xFF111111); c.drawRoundRect(rf, dp(16), dp(16), p);
                    rf.set(dp(10), top, w - dp(16), bottom - dp(4));
                    p.setColor(0xFFFFFFFF); c.drawRoundRect(rf, dp(16), dp(16), p);
                    float fy = rf.bottom - (rf.height() - dp(8)) * f;
                    p.setColor(th.accent);
                    c.drawRoundRect(new RectF(rf.left + dp(4), fy, rf.right - dp(4), rf.bottom - dp(4)), dp(12), dp(12), p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(5)); p.setColor(0xFF111111);
                    c.drawRoundRect(rf, dp(16), dp(16), p);
                    p.setStyle(Paint.Style.FILL);
                    if (level >= max && !muted) {
                        p.setColor(0xFFFFE600);
                        c.drawCircle(w / 2, top + dp(30), dp(26), p);
                        p.setColor(0xFF111111); p.setTextSize(dp(18));
                        c.drawText("MAX!", w / 2, top + dp(36), p);
                        p.setTextSize(dp(26));
                    }
                    break;
                }
                default: {
                    rf.set(dp(22), top, w - dp(22), bottom);
                    p.setColor((th.surface & 0x00FFFFFF) | 0xE6000000);
                    c.drawRoundRect(rf, rf.width() / 2, rf.width() / 2, p);
                    float fy = rf.bottom - rf.height() * f;
                    p.setShader(new LinearGradient(0, rf.top, 0, rf.bottom, th.accent2, th.accent, Shader.TileMode.CLAMP));
                    if ("neon".equals(th.id) || "hero".equals(th.id)) p.setShadowLayer(dp(14), 0, 0, th.accent);
                    c.save();
                    c.clipRect(rf.left - dp(20), fy, rf.right + dp(20), rf.bottom + dp(20));
                    c.drawRoundRect(rf, rf.width() / 2, rf.width() / 2, p);
                    c.restore();
                    p.setShader(null); p.clearShadowLayer();
                    break;
                }
            }
            p.setColor(textColor);
            p.setTextSize(dp(26 + 6 * bump));
            p.setShadowLayer(dp(4), 0, 0, "comic".equals(th.id) ? 0x00000000 : 0xAA000000);
            c.drawText(muted ? "MUTE" : String.valueOf(level), w / 2, dp(34), p);
            if (bump > 0) postInvalidateOnAnimation();
        }
    }

    // ---------------------------------------------------------------- toast

    /** Pixie says something at the bottom of the screen. */
    void toast(String text) {
        if (panel != null && panelBubble != null && toast == null) {   // a panel is open: its Pixie says it
            panelBubble.setText(text);
            panelBubble.setVisibility(View.VISIBLE);
            if (panelPixie != null) panelPixie.pose(BuddyView.TALK, 1800);
            ui.removeCallbacks(panelSayRestore);
            ui.postDelayed(panelSayRestore, Math.max(2800, Math.min(6000, 1500 + text.length() * 60L)));
            return;
        }
        if (toast == null && MascotView.speak(text)) return;   // Home is up: its Pixie says it (no second Pixie)
        if (toast == null) {
            toast = new LinearLayout(svc);
            toast.setOrientation(LinearLayout.HORIZONTAL);
            toast.setGravity(Gravity.CENTER_VERTICAL);
            toast.setBaselineAligned(false);                 // otherwise the row is too short and cuts the bubble's top
            toast.setClipChildren(false);
            toast.setPadding(dp(4), dp(12), dp(4), dp(4));   // room for Pixie's pop-in overshoot
            toastPixie = new BuddyView(svc, th, 3f);
            toast.addView(toastPixie);
            toastText = Bubble.text(svc, th, 18, Bubble.TAIL_LEFT);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.setMarginStart(dp(6));
            toast.addView(toastText, tlp);
            WindowManager.LayoutParams p = params(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            p.y = dp(40);
            add(toast, p);
            toastPixie.post(toastPixie::popIn);
        }
        toastText.setText(text);
        toastPixie.pose(BuddyView.TALK, Math.min(2400, 600 + text.length() * 45L));
        ui.removeCallbacks(toastHide);
        ui.postDelayed(toastHide, Math.max(2800, Math.min(6000, 1500 + text.length() * 60L)));
    }

    /** Pixie's line for a panel, by its title. */
    private static String sayFor(String title) {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        switch (title == null ? "" : title) {
            case Panels.QUICK: return (h < 12 ? "Morning! " : h < 17 ? "Hey! " : "Evening! ") + "What can I do for you?";
            case Panels.HA: return "Here's your home. Want me to switch something?";
            case Panels.APPS: return "Where shall we go?";
            case Panels.VOICE: return "Who should I ask?";
            case Panels.THEMES: return "Pick my next outfit!";
            default: return "";
        }
    }

    // ---------------------------------------------------------------- message card

    /** One card slot (pairing code, voice, learn mode, notifications). hideMs <= 0 keeps it up. */
    void card(String title, String body, boolean showBars, long hideMs) {
        if (cardRoot == null) {
            cardRoot = new FrameLayout(svc);
            LinearLayout box = new LinearLayout(svc);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setBaselineAligned(false);
            box.setBackground(cardBg(panelColor()));
            box.setPadding(dp(24), dp(22), dp(30), dp(24));
            box.setClipChildren(false);
            cardPixie = new BuddyView(svc, th, 3.5f);
            box.addView(cardPixie);
            LinearLayout col = new LinearLayout(svc);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(18), 0, 0, 0);
            box.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            cardTitle = tv(24, onPanel(), th.bold());
            cardBody = tv(18, (onPanel() & 0x00FFFFFF) | 0xDD000000, th.typeface());
            cardBody.setMaxLines(8);
            cardBody.setEllipsize(TextUtils.TruncateAt.END);
            cardBody.setPadding(0, dp(8), 0, 0);
            bars = new BarsView(svc);
            col.addView(cardTitle);
            col.addView(cardBody);
            col.addView(bars, new LinearLayout.LayoutParams(-1, dp(56)));
            cardRoot.addView(box, new FrameLayout.LayoutParams(dp(680), -2, Gravity.CENTER));
            cardPixie.post(cardPixie::popIn);
            WindowManager.LayoutParams p = params(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            p.y = dp(48);
            add(cardRoot, p);
            box.setTranslationY(-dp(40)); box.setAlpha(0);
            box.animate().translationY(0).alpha(1).setDuration(180).start();
        }
        cardTitle.setText(title);
        cardBody.setText(body == null ? "" : body);
        cardBody.setVisibility(body == null || body.isEmpty() ? View.GONE : View.VISIBLE);
        bars.setVisibility(showBars ? View.VISIBLE : View.GONE);
        bars.running = showBars;
        cardPixie.pose(showBars ? BuddyView.LISTEN : BuddyView.TALK, showBars ? 0 : 2200);
        if (showBars) bars.invalidate();
        ui.removeCallbacks(cardHide);
        if (hideMs > 0) ui.postDelayed(cardHide, hideMs);
    }

    void cardLevel(float rmsDb) { if (bars != null) bars.level = Math.max(0, Math.min(1, (rmsDb + 2) / 12f)); }

    void dismissCard() {
        ui.removeCallbacks(cardHide);
        remove(cardRoot);
        cardRoot = null;
        bars = null;
    }

    private final class BarsView extends View {
        boolean running;
        float level;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarsView(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            float t = SystemClock.uptimeMillis() / 1000f, w = getWidth(), h = getHeight();
            int n = 24;
            float bw = w / (n * 1.6f);
            for (int i = 0; i < n; i++) {
                float a = (float) (0.25 + 0.75 * Math.abs(Math.sin(t * 5 + i * 0.6)) * (0.3 + level));
                float bh = Math.max(dp(4), h * Math.min(1, a));
                p.setColor(i % 2 == 0 ? th.accent : th.accent2);
                float x = i * bw * 1.6f;
                c.drawRoundRect(new RectF(x, (h - bh) / 2, x + bw, (h + bh) / 2), bw / 2, bw / 2, p);
            }
            if (running) postInvalidateDelayed(33);
        }
    }

    void destroy() {
        closePanel();
        dismissCard();
        volHide.run();
        toastHide.run();
    }
}
