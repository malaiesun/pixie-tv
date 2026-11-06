package dev.malaiesun.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Pixie on the Home screen: follows the focus, wanders, dozes off, says hello after boot, speaks for the TV,
 * and opens apps in a different way per theme.
 *
 *   theme     gets around                               opens an app
 *   retro     runs and jumps on the ground              runs under the tile, jumps, bumps it (coin)
 *   minimal   calm walk on the ground                   throws a paper plane into it
 *   comic     teleports through portals, perches on     dives into a portal, shoots out of another one
 *             top of the focused tile                   under the tile and punches it (POW!)
 *   neon      jetpack, flies anywhere beside the focus  ignition, flies into position, fires a laser
 *   hero      flies anywhere (superman pose), loops     wind-up, curved dive, fist first
 *   aurora    floats around like a fairy, sparkles      spirals up, throws a magic star
 *
 * Also draws the floor in front of the tiles. Hides while an overlay shows its own Pixie (panels, cards),
 * so there's never two. Full-screen overlay, never focusable; runs on the shared Pulse clock.
 */
final class MascotView extends View implements Pulse.Listener {
    private static final float PX_DP = 3f;          // one art pixel
    private static final float G_DP = 2600f;        // gravity, dp/s^2
    private static final long SLEEP_AFTER_MS = 45_000, TP_OUT = 260, TP_IN = 280;
    private static final int M_GROUND = 0, M_PORTAL = 1, M_FLY = 2;
    private static final int K_JUMP = 0, K_PLANE = 1, K_PORTAL = 2, K_LASER = 3, K_SWOOP = 4, K_STAR = 5;
    private static final int P_NONE = 0, P_PLANE = 1, P_STAR = 2;

    // ---------------------------------------------------------------- one Pixie on screen at a time

    private static final Set<View> overlayPixies = new HashSet<>();
    private static MascotView home;

    /** An overlay (panel, card, toast) is showing its own Pixie: Home's hides meanwhile. */
    static void overlayShown(View v) {
        overlayPixies.add(v);
        if (home != null) home.invalidate();
    }

    static void overlayHidden(View v) {
        boolean was = !overlayPixies.isEmpty();
        overlayPixies.remove(v);
        if (was && overlayPixies.isEmpty() && home != null) {
            home.shownAt = SystemClock.uptimeMillis();
            home.schedule();
            home.invalidate();
        }
    }

    /** Show off this theme's app-opening move on the focused tile, without opening anything. */
    static boolean demo() {
        MascotView m = home;
        return m != null && m.follow != null && m.follow.isAttachedToWindow() && overlayPixies.isEmpty() && m.bump(m.follow, null);
    }

    /** If Home is on screen with Pixie, Pixie says it there (instead of a second Pixie in a toast). */
    static boolean speak(String text) {
        MascotView m = home;
        if (m == null || !m.enabled || !m.running || !m.isShown() || !overlayPixies.isEmpty() || text == null) return false;
        m.say(text, Math.max(3000, Math.min(5500, 1400 + text.length() * 60L)), false);
        return true;
    }

    // ---------------------------------------------------------------- state

    private final float d, px, sw, sh;               // density, art pixel, sprite bitmap size in px
    private final Paint bp = new Paint();            // bitmaps, no filtering: crisp pixels
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF(), tmp = new RectF();
    private final Path path = new Path();
    private final Random rnd = new Random();
    private final float[] a2 = new float[2];

    private Theme th = Theme.preset("retro");
    private Sprites.Set set = Sprites.forTheme("retro");
    private int move = M_GROUND, kind = K_JUMP;
    private boolean enabled, running, listening;
    private long lastKey, shownAt;
    private float groundDp = 40;
    private String floorKind = "none";               // retro | fade | none
    private Bitmap floor;
    private int floorW, floorH;

    // body
    private float x = -1, y, vx, vy, angle, perchHop, perchHopV;
    private boolean faceRight = true, air;
    private View follow;
    private boolean wander;
    private float wanderX, wanderY;
    private long last, nextIdle, wanderEnd, nextBlink, blinkUntil, squashUntil, runFlipAt, lastPoke, boostUntil, loopT0, loopUntil, settleAt;
    private boolean runPhase, moving;

    // depth (fake 3D): 1 = normal, bigger = closer to the viewer
    private float zs = 1, zFrom = 1, zTo = 1, zMax;
    private long zT0, zDur;
    // free roaming (flyers) and random perches (portal): they don't chase the focus
    private float roamX = -1, roamY, perchOff = 0.5f, perchFloorX = -1;
    private long roamUntil, nextPerchAt;
    private View perchView;
    private boolean popBig;
    private java.util.function.Supplier<java.util.List<View>> tiles;

    // portals (comic): 1 sinking at the source, 2 rising at the destination, 3 shooting up under a tile
    private int tp;
    private long tpT0, teleportInAt, portalT0, portalDur;
    private float tpSrcX, tpSrcY, portalX, portalY;
    private boolean hiddenInPortal, comicFall;

    // opening an app
    private boolean bumping, hitDone, shakeTile;
    private int phase;
    private long phaseT0, phaseDur;
    private float tcx, tcy, by1, sx0, sy0, mx1, my1, cx1, cy1, aimAngle, side;
    private final RectF bumpRect = new RectF();
    private Runnable onHit;
    private View bumpTile;
    private long tileT0 = -1, prizeT0 = -1;
    private float prizeX, prizeY;
    private String powWord = "POW!";
    private int projKind;
    private long projT0, projDur;
    private float pjSX, pjSY, pjCX, pjCY, pjEX, pjEY;

    // speech
    private final ArrayDeque<Object[]> lines = new ArrayDeque<>();   // {text, ms, wave}
    private String sayText;
    private long sayUntil, sayT0;
    private boolean sayWave;
    private Bubble bubble;

    // particles: x, y, vx, vy, (unused), lifeMs, size, color, kind
    // kinds: 0 puff (rises, grows), 1 spark (falls), 2 streak (speed line), 3 twinkle (plus, shrinks)
    private final float[][] parts = new float[48][];
    private final long[] partT0 = new long[48];

    MascotView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        px = PX_DP * d;
        sw = 18 * px;
        sh = 18 * px;
        bp.setFilterBitmap(false);
        bp.setAntiAlias(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** On/off, the theme (outfit, way of moving, way of opening apps), and the floor in front of the tiles. */
    void setup(boolean on, Theme t, String floorKind) {
        boolean newTheme = !t.id.equals(th.id);
        if (newTheme || !floorKind.equals(this.floorKind) || t.bg != th.bg) floor = null;
        th = t;
        set = Sprites.forTheme(t.id);
        bubble = new Bubble(getContext(), t, Bubble.TAIL_BOTTOM);
        switch (t.id) {
            case "minimal": move = M_GROUND; kind = K_PLANE; break;
            case "comic": move = M_PORTAL; kind = K_PORTAL; break;
            case "neon": move = M_FLY; kind = K_LASER; break;
            case "hero": move = M_FLY; kind = K_SWOOP; break;
            case "aurora": move = M_FLY; kind = K_STAR; break;
            default: move = M_GROUND; kind = K_JUMP; break;
        }
        this.floorKind = floorKind;
        groundDp = "retro".equals(floorKind) ? 40 : 22;
        if (newTheme) {
            long now = SystemClock.uptimeMillis();
            vx = vy = angle = perchHop = perchHopV = 0;
            air = comicFall = bumping = false;
            tp = 0;
            projKind = P_NONE;
            zs = 1; zDur = 0; roamUntil = 0; perchView = null; nextPerchAt = now + 5000;
            if (move == M_PORTAL) { hiddenInPortal = true; teleportInAt = now + 250; perchFloorX = -1; }
            else { hiddenInPortal = false; shownAt = now; }
        }
        enabled = on;
        setVisibility(on ? VISIBLE : GONE);
        if (!on) { bumping = false; onHit = null; resetTile(); }
        schedule();
    }

    boolean isOn() { return enabled; }

    /** Home's tiles, so the portal Pixie can perch on random ones. */
    void setTiles(java.util.function.Supplier<java.util.List<View>> t) { tiles = t; }

    void setRunning(boolean r) {
        running = r;
        if (r) { last = SystemClock.uptimeMillis(); lastPoke = last; clearEffects(); schedule(); }
        else resetTile();
    }

    /**
     * Back on Home after an app opened mid-animation: drop whatever was in flight (coin, POW, laser,
     * a half-finished portal) so nothing stale flashes up; the portal Pixie pops back in somewhere.
     */
    private void clearEffects() {
        prizeT0 = -1;
        projKind = P_NONE;
        resetTile();
        for (int i = 0; i < parts.length; i++) parts[i] = null;
        if (bumping) { bumping = false; onHit = null; }
        zs = 1; zDur = 0; angle = 0;
        if (move == M_PORTAL && (tp != 0 || comicFall)) {
            comicFall = false; tp = 0; hiddenInPortal = true; teleportInAt = SystemClock.uptimeMillis() + 400;
        }
        if (move == M_GROUND && air) { air = false; vy = 0; y = ground(); }
    }

    /** Focus moved: go there. */
    void follow(View v) {
        if (v != follow) { wander = false; nextIdle = SystemClock.uptimeMillis() + 3500; }
        follow = v;
        poke();
    }

    /** Any key: wake up if dozing (with a little hop). */
    void poke() {
        long now = SystemClock.uptimeMillis();
        if (enabled && now - lastPoke > SLEEP_AFTER_MS && !bumping) hop(30 * d);
        lastPoke = now;
        schedule();
    }

    /** A hop (ground), a perch hop (portal) or a quick lift (flying). */
    void hop(float height) {
        if (!enabled || bumping) return;
        if (move == M_FLY) { boostUntil = SystemClock.uptimeMillis() + 450; schedule(); return; }
        if (move == M_PORTAL) { if (perchHop == 0 && tp == 0) perchHopV = -(float) Math.sqrt(2 * G_DP * d * height); schedule(); return; }
        if (air) return;
        air = true;
        vy = -(float) Math.sqrt(2 * G_DP * d * height);
        puff(x, y, 3, 0xFFFFFFFF);
        schedule();
    }

    /** Say something in a speech bubble (queued after whatever is being said). */
    void say(String text, long ms, boolean wave) {
        if (text == null || text.isEmpty()) return;
        lines.add(new Object[]{text, ms, wave});
        if (sayText == null) nextLine(SystemClock.uptimeMillis());
        lastPoke = SystemClock.uptimeMillis();
        schedule();
    }

    private void nextLine(long now) {
        Object[] l = lines.poll();
        if (l == null) { sayText = null; return; }
        sayText = (String) l[0];
        sayT0 = now;
        sayUntil = now + (Long) l[1];
        sayWave = (Boolean) l[2];
        lastPoke = now;
    }

    boolean bumping() { return bumping; }

    // ---------------------------------------------------------------- theme transition

    private boolean transition;

    /** Feet position now (for the theme transition to start from). */
    float[] feet() {
        if (x < 0 || getWidth() == 0) return new float[]{getWidth() / 2f, getHeight() * 0.8f};
        return new float[]{x, y + perchHop};
    }

    /** While the theme transition plays its own Pixie, this one is hidden; speech waits until after. */
    void setTransition(boolean on) {
        transition = on;
        if (on && sayText != null) sayUntil += ThemeSwap.DUR;
        invalidate();
    }

    /** Continue from where the transition's Pixie landed. */
    void placeAt(float fx, float fy) {
        long now = SystemClock.uptimeMillis();
        x = clampX(fx);
        y = Math.min(ground(), fy);
        vx = vy = 0;
        hiddenInPortal = false;
        tp = 0;
        shownAt = now - 1000;
        if (move == M_GROUND && y < ground()) { air = true; vy = 0; }      // drop to the floor
        if (move == M_PORTAL) { perchView = null; perchFloorX = x; nextPerchAt = now + 1500; }
        invalidate();
    }

    /**
     * Open `tile` the theme's way; `hit` runs at the moment of impact (that's when the launch effect starts).
     * Returns false (and does nothing) when the mascot is off, so the caller launches directly.
     */
    boolean bump(View tile, Runnable hit) {
        if (!enabled || !running || bumping || getWidth() == 0) return false;
        long now = SystemClock.uptimeMillis();
        rectOf(tile, bumpRect);
        float ground = ground();
        if (x < 0) { x = getWidth() / 2f; y = ground; }
        tcx = bumpRect.centerX();
        tcy = bumpRect.centerY();
        by1 = bumpRect.bottom + sh - px;                  // fist just reaches the tile's bottom edge
        if (by1 > ground - 14 * d) by1 = ground - 34 * d;  // tile sits at the very bottom
        bumpTile = tile;
        onHit = hit;
        bumping = true;
        hitDone = false;
        shakeTile = kind == K_LASER;
        wander = false;
        sayText = null;
        lines.clear();
        side = x <= tcx ? -1 : 1;
        powWord = new String[]{"POW!", "BAM!", "ZAP!", "WHAM!"}[rnd.nextInt(4)];
        lastPoke = now;
        switch (kind) {
            case K_PLANE: setPhase(10, 170, now); faceRight = tcx >= x; break;
            case K_PORTAL:
                if (tp != 0 || hiddenInPortal || comicFall) { comicFall = false; startEmerge(now); }
                else { tp = 1; tpT0 = now; tpSrcX = x; tpSrcY = y + perchHop; perchHop = perchHopV = 0; openPortal(tpSrcX, tpSrcY, TP_OUT, now); setPhase(20, TP_OUT, now); }
                break;
            case K_LASER: setPhase(30, 190, now); break;
            case K_SWOOP: setPhase(40, 260, now); sx0 = x; sy0 = y; break;
            case K_STAR: setPhase(50, 380, now); sx0 = x; sy0 = y; break;
            default: setPhase(air ? 0 : 1, 0, now); break;
        }
        schedule();
        return true;
    }

    private void setPhase(int ph, long dur, long now) { phase = ph; phaseDur = dur; phaseT0 = now; }

    private float phaseT(long now) { return phaseDur <= 0 ? 1 : Math.min(1, (now - phaseT0) / (float) phaseDur); }

    /** The impact: tile reacts, something pops out, and the launch effect starts. */
    private void doHit(long now, float atX, float atY) {
        if (hitDone) return;
        hitDone = true;
        tileT0 = now;
        prizeT0 = now;
        prizeX = tcx;
        prizeY = (kind == K_LASER || kind == K_STAR) ? tcy : bumpRect.top;
        sparks(atX, atY);
        Runnable h = onHit;
        onHit = null;
        if (h != null) h.run();
    }

    // ---------------------------------------------------------------- frame loop

    /** Shared 30 fps clock: move, then redraw only if something visible changed. */
    @Override public void onPulse(long now) {
        if (!enabled || !running || !isAttachedToWindow()) { Pulse.remove(this); listening = false; return; }
        step(now);
        long key = visualKey(now);
        if (key != lastKey || moving || busy() || dozing(now) || sayText != null || move != M_GROUND || now - shownAt < 400) {
            lastKey = key;
            invalidate();
        }
    }

    private void schedule() {
        if (enabled && running && isAttachedToWindow()) {
            if (!listening) { listening = true; last = SystemClock.uptimeMillis(); }
            Pulse.add(this);
        } else {
            Pulse.remove(this);
            listening = false;
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); home = this; schedule(); }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (home == this) home = null;
        Pulse.remove(this);
        listening = false;
    }

    private boolean dozing(long now) { return now - lastPoke > SLEEP_AFTER_MS && !air && !moving && sayText == null && !bumping; }

    /** Everything an idle ground redraw depends on: position, facing, blink, bob, antenna light, squash. */
    private long visualKey(long now) {
        long k = Math.round(x) * 4099L + Math.round(y);
        k = k * 16 + ((now / 700) % 2) * 8 + (((now / 600) % 3 == 2) ? 4 : 0) + (now < blinkUntil ? 2 : 0) + (now < squashUntil ? 1 : 0);
        return k * 2 + (faceRight ? 1 : 0);
    }

    private boolean busy() {
        if (bumping || air || tileT0 >= 0 || prizeT0 >= 0 || tp != 0 || comicFall || projKind != P_NONE) return true;
        for (float[] q : parts) if (q != null) return true;
        return false;
    }

    private float ground() { return getHeight() - groundDp * d; }

    private float clampX(float v) { return Math.max(sw / 2 + 8 * d, Math.min(getWidth() - sw / 2 - 8 * d, v)); }

    private float clampY(float v) { return Math.max(sh + 6 * d, Math.min(ground(), v)); }

    private void step(long now) {
        float dt = Math.min(0.05f, Math.max(0, (now - last) / 1000f));
        last = now;
        int w = getWidth();
        if (w == 0) return;
        if (x < 0) { x = w / 2f; y = ground(); nextIdle = now + 3000; nextBlink = now + 2000; }

        stepTile(now);
        if (prizeT0 >= 0 && now - prizeT0 > 650) prizeT0 = -1;
        if (projKind != P_NONE && now - projT0 > projDur + 50) projKind = P_NONE;
        for (int i = 0; i < parts.length; i++) if (parts[i] != null && now - partT0[i] > parts[i][5]) parts[i] = null;
        if (sayText != null && now > sayUntil) nextLine(now);
        if (now > nextBlink) { blinkUntil = now + 150; nextBlink = now + 2200 + rnd.nextInt(3200); }

        if (bumping) { stepBump(now, dt); return; }
        switch (move) {
            case M_PORTAL: portalStep(now, dt); break;
            case M_FLY: flyStep(now, dt); break;
            default: groundStep(now, dt); break;
        }
        idleAntics(now);
    }

    // ---------------------------------------------------------------- getting around

    /** Where Pixie wants to be right now (feet position). Only the walkers follow the focus. */
    private void anchor(long now, float[] out) {
        float g = ground(), tx = x, ty = y;
        if (move == M_FLY) {
            if (roamX < 0 || now > roamUntil) pickRoam(now, false);
            tx = roamX;
            ty = roamY;
            if (dozing(now) && kind != K_STAR) ty = g;                 // land for a nap (the fairy keeps floating)
            ty += (float) Math.sin(now / (kind == K_STAR ? 520.0 : 380.0)) * (kind == K_STAR ? 9 : 4) * d;
            if (kind == K_STAR) { tx += (float) Math.sin(now / 900.0) * 30 * d; ty += (float) Math.sin(now / 1300.0) * 18 * d; }
            if (now < loopUntil) {                                      // loop-the-loop
                double a = (now - loopT0) / (double) (loopUntil - loopT0) * Math.PI * 2;
                tx += (float) Math.sin(a) * 60 * d * (faceRight ? 1 : -1);
                ty -= (float) (1 - Math.cos(a)) * 60 * d;
            }
            if (now < boostUntil) ty -= 40 * d;
        } else if (move == M_PORTAL) {
            if (perchView != null && onScreen(perchView)) {
                rectOf(perchView, tmp);
                tx = tmp.left + tmp.width() * perchOff;
                ty = tmp.top + px;
            } else {
                if (perchView != null) { perchView = null; nextPerchAt = 0; }   // its row scrolled away: go elsewhere
                if (perchFloorX < 0) perchFloorX = sw + rnd.nextFloat() * (getWidth() - 2 * sw);
                tx = perchFloorX;
                ty = g;
            }
        } else {
            if (wander) tx = wanderX;
            else if (follow != null && follow.isAttachedToWindow()) { rectOf(follow, tmp); tx = tmp.centerX(); }
            ty = g;
        }
        out[0] = clampX(tx);
        out[1] = clampY(ty);
    }

    /** A new random spot anywhere on screen; `far` makes it cross the screen (fly-bys). */
    private void pickRoam(long now, boolean far) {
        int w = getWidth();
        float top = getHeight() * 0.16f + sh, bottom = ground() - 10 * d;
        for (int i = 0; i < 6; i++) {
            roamX = sw + rnd.nextFloat() * (w - 2 * sw);
            roamY = top + rnd.nextFloat() * Math.max(1, bottom - top);
            if (!far || Math.abs(roamX - x) > w * 0.4f) break;
        }
        roamUntil = now + 2500 + rnd.nextInt(4000);
    }

    private boolean onScreen(View v) {
        if (!v.isAttachedToWindow() || !v.isShown()) return false;
        rectOf(v, tmp);
        return tmp.top > sh && tmp.bottom < ground() - 20 * d && tmp.right > 0 && tmp.left < getWidth();
    }

    /** Portal Pixie: next random perch, on top of a visible tile or on the floor. */
    private void choosePerch() {
        java.util.List<View> vis = new java.util.ArrayList<>();
        if (tiles != null) for (View v : tiles.get()) if (v instanceof Tile && v != perchView && onScreen(v)) vis.add(v);
        if (!vis.isEmpty() && rnd.nextInt(100) < 65) {
            perchView = vis.get(rnd.nextInt(vis.size()));
            perchOff = 0.25f + rnd.nextFloat() * 0.5f;
        } else {
            perchView = null;
            perchFloorX = sw + rnd.nextFloat() * (getWidth() - 2 * sw);
        }
    }

    private void groundStep(long now, float dt) {
        float g = ground();
        anchor(now, a2);
        float dx = a2[0] - x;
        moving = Math.abs(dx) > 2 * d;
        if (moving) {
            float speed = (Math.abs(dx) > 220 * d ? 560 : 300) * d * (kind == K_PLANE ? 0.8f : 1);
            x += Math.signum(dx) * Math.min(Math.abs(dx), speed * dt);
            faceRight = dx > 0;
            if (!air && now >= runFlipAt) {
                runPhase = !runPhase;
                runFlipAt = now + 85;
                if (runPhase && rnd.nextInt(3) == 0) puff(x - (faceRight ? 1 : -1) * 5 * px, g, 1, 0xFFFFFFFF);
            }
        } else if (wander && now > wanderEnd) wander = false;
        if (air) {
            vy += G_DP * d * dt;
            y += vy * dt;
            if (y >= g) { y = g; vy = 0; air = false; squashUntil = now + 110; puff(x, g, 3, 0xFFFFFFFF); }
        } else y = g;
    }

    private void flyStep(long now, float dt) {
        anchor(now, a2);
        float k = kind == K_STAR ? 5 : 12, c = 2 * (float) Math.sqrt(k) * (kind == K_STAR ? 1.1f : 0.9f);
        vx += ((a2[0] - x) * k - vx * c) * dt;
        vy += ((a2[1] - y) * k - vy * c) * dt;
        float sp = (float) Math.hypot(vx, vy), max = 900 * d;
        if (sp > max) { vx *= max / sp; vy *= max / sp; sp = max; }
        x += vx * dt;
        y += vy * dt;
        if (y > ground()) { y = ground(); vy = 0; }
        moving = sp > 40 * d;
        if (Math.abs(vx) > 20 * d) faceRight = vx > 0;
        float target;
        if (kind == K_SWOOP && sp > 220 * d) target = flightAngle(vx, vy);
        else if (kind == K_STAR) target = (float) Math.sin(now / 700.0) * 6;
        else target = Math.min(22, Math.abs(vx) / (400 * d) * 22);
        angle += (target - angle) * Math.min(1, dt * 8);
        // depth: a fly-by toward the viewer, or the fairy drifting nearer and farther
        if (zDur > 0) {
            float t = (now - zT0) / (float) zDur;
            if (t >= 1) zDur = 0;
            else zs = 1 + zMax * (float) Math.pow(Math.sin(Math.PI * t), 1.2);
        }
        if (zDur == 0) {
            float zt = kind == K_STAR ? 1 + 0.18f * (float) Math.sin(now / 1800.0) : 1;
            zs += (zt - zs) * Math.min(1, dt * 3);
        }
        trail(now, sp / max);
    }

    private void portalStep(long now, float dt) {
        if (comicFall) {                                   // after a punch: drop back into the portal
            vy += G_DP * d * dt;
            y += vy * dt;
            if (y - sh > portalY || now - portalT0 > portalDur) { comicFall = false; tp = 0; hiddenInPortal = true; teleportInAt = now + 300; }
            moving = true;
            return;
        }
        if (tp == 0 && !hiddenInPortal && now >= nextPerchAt) {   // every few seconds: portal somewhere random
            choosePerch();
            nextPerchAt = now + 4500 + rnd.nextInt(5000);
        }
        anchor(now, a2);
        if (hiddenInPortal) { if (now >= teleportInAt) startTeleportIn(now, a2); return; }
        if (tp == 1) { if (now - tpT0 >= TP_OUT) startTeleportIn(now, a2); return; }
        if (tp == 2) {
            float k = Math.min(1, (now - tpT0) / (float) TP_IN);
            zs = popBig ? 1 + 1.1f * (1 - k) * (1 - k) : 1;      // sometimes pops out of the screen at you
            if (k >= 1) { tp = 0; zs = 1; perchHopV = -(float) Math.sqrt(2 * G_DP * d * 14 * d); }
            return;
        }
        float dist = (float) Math.hypot(a2[0] - x, a2[1] - y);
        if (dist > 36 * d) {
            tp = 1; tpT0 = now; tpSrcX = x; tpSrcY = y; openPortal(x, y, TP_OUT, now);
        } else {
            x += (a2[0] - x) * Math.min(1, dt * 12);       // ride along when rows scroll
            y = a2[1];
        }
        if (perchHop != 0 || perchHopV != 0) {
            perchHopV += G_DP * d * dt;
            perchHop += perchHopV * dt;
            if (perchHop >= 0) { perchHop = 0; perchHopV = 0; squashUntil = now + 90; }
        }
        moving = false;
    }

    private void startTeleportIn(long now, float[] at) {
        hiddenInPortal = false;
        tp = 2;
        tpT0 = now;
        x = at[0];
        y = at[1];
        popBig = rnd.nextInt(100) < 35;
        openPortal(x, y, TP_IN, now);
        for (int i = 0; i < 4; i++) twinkle(x + (rnd.nextFloat() - 0.5f) * sw, y - rnd.nextFloat() * sh * 0.6f);
    }

    private void openPortal(float atX, float atY, long dur, long now) {
        portalX = atX;
        portalY = atY;
        portalT0 = now;
        portalDur = dur;
    }

    private void idleAntics(long now) {
        if (air || tp != 0 || hiddenInPortal || dozing(now) || sayText != null || now <= nextIdle) return;
        if (move == M_GROUND && moving) return;
        nextIdle = now + 2600 + rnd.nextInt(4500);
        int r = rnd.nextInt(100), w = getWidth();
        if (move == M_FLY) {
            if (r < 28 && zDur == 0) {                          // fly-by: swoops close to the viewer across the screen
                zT0 = now; zDur = 1500; zMax = 1.0f + rnd.nextFloat() * 0.7f;
                pickRoam(now, true);
                roamUntil = now + 1500;
            } else if (r < 50) {
                if (kind == K_SWOOP) { loopT0 = now; loopUntil = now + 1100; }
                else if (kind == K_STAR) { for (int i = 0; i < 8; i++) twinkle(x + (rnd.nextFloat() - 0.5f) * 2 * sw, y - rnd.nextFloat() * sh * 1.4f); }
                else boostUntil = now + 450;
            } else if (r < 65) faceRight = !faceRight;
            return;
        }
        if (move == M_PORTAL) {
            if (r < 45) hop(18 * d + rnd.nextInt(14) * d);
            else if (r < 70) faceRight = !faceRight;
            return;
        }
        if (r < 38) {
            wander = true;
            wanderEnd = now + 1800 + rnd.nextInt(1800);
            wanderX = clampX(x + (60 + rnd.nextInt(180)) * d * (rnd.nextBoolean() ? 1 : -1));
        } else if (r < 62) hop(22 * d + rnd.nextInt(14) * d);
        else if (r < 80) faceRight = !faceRight;
    }

    // ---------------------------------------------------------------- opening an app

    private void stepBump(long now, float dt) {
        moving = true;
        float t = phaseT(now), g = ground();
        switch (phase) {
            // ---- retro: run under it, crouch, jump straight up
            case 0:
                vy += G_DP * d * dt; y += vy * dt;
                if (y >= g) { y = g; vy = 0; air = false; setPhase(1, 0, now); }
                return;
            case 1: {
                float bx = clampX(tcx), dx = bx - x, speed = Math.max(650 * d, Math.abs(dx) / 0.32f);
                if (Math.abs(dx) > 1) {
                    faceRight = dx > 0;
                    x += Math.signum(dx) * Math.min(Math.abs(dx), speed * dt);
                    if (now >= runFlipAt) { runPhase = !runPhase; runFlipAt = now + 55; }
                    return;
                }
                x = bx;
                squashUntil = now + 70;
                setPhase(2, 70, now);
                return;
            }
            case 2:
                if (t < 1) return;
                air = true; vy = 0; puff(x, g, 4, 0xFFFFFFFF);
                sy0 = y;
                setPhase(3, (long) Math.max(220, Math.min(380, 180 + (g - by1) / d * 0.65f)), now);
                return;
            case 3:
                y = sy0 + (by1 - sy0) * (1 - (1 - t) * (1 - t));
                if (t >= 1) { vy = 0; bumping = false; doHit(now, x, bumpRect.bottom); }
                return;

            // ---- minimal: wind up, throw a paper plane
            case 10:
                if (t >= 1) {
                    float hx = x + (faceRight ? 6 : -6) * px, hy = y - sh + 3 * px;
                    launchProjectile(P_PLANE, hx, hy, tcx, tcy + bumpRect.height() * 0.15f, -70 * d, 720, now);
                    setPhase(11, 720, now);
                }
                return;
            case 11:
                if (t >= 1) { bumping = false; doHit(now, pjEX, pjEY); }
                return;

            // ---- comic: into a portal, out of one under the tile, punch
            case 20:
                if (t >= 1) startEmerge(now);
                return;
            case 21:
                y = (portalY + sh) + (by1 - (portalY + sh)) * (1 - (1 - t) * (1 - t));
                if (t >= 1) {
                    vy = 0;
                    bumping = false;
                    comicFall = true;
                    portalT0 = now - 200;
                    portalDur = 900;
                    doHit(now, x, bumpRect.bottom);
                }
                return;

            // ---- neon: ignition, fly up close to the viewer, charge, perspective laser into the tile
            case 30:
                exhaust(now, 3);
                if (rnd.nextBoolean()) puff(x - (faceRight ? 1 : -1) * 5 * px, y, 1, 0xFF8890A0);
                if (t >= 1) {
                    sx0 = x; sy0 = y;
                    mx1 = tcx < getWidth() / 2f ? getWidth() * 0.80f : getWidth() * 0.20f;   // foreground, opposite side
                    my1 = clampY(ground() - 70 * d);
                    cx1 = (sx0 + mx1) / 2;
                    cy1 = Math.min(ground(), Math.max(sy0, my1) + 30 * d);
                    zFrom = zs; zTo = 1.9f;
                    setPhase(31, 360, now);
                }
                return;
            case 31: {
                float e = t * t * (3 - 2 * t);
                float nx = bez(sx0, cx1, mx1, e), ny = bez(sy0, cy1, my1, e);
                vx = (nx - x) / Math.max(dt, 0.001f);
                if (Math.abs(nx - x) > 0.5f) faceRight = nx > x;
                x = nx; y = ny;
                zs = zFrom + (zTo - zFrom) * e;
                angle = Math.min(25, Math.abs(vx) / (500 * d) * 25);
                exhaust(now, 3);
                if (t >= 1) { faceRight = tcx >= x; setPhase(32, 200, now); }
                return;
            }
            case 32:
                aimAngle = flightAngle(Math.abs(tcx - x), tcy - (y - sh / 2)) - 45;
                if (t >= 1) setPhase(33, 260, now);
                return;
            case 33: {
                if (t >= 0.2f && !hitDone) doHit(now, tcx, tcy);
                if (t >= 1) { bumping = false; vx = (faceRight ? -1 : 1) * 140 * d; angle = 0; roamUntil = 0; }
                return;
            }

            // ---- hero: swing out toward the viewer, then dive back into the screen, fist first
            case 40: {
                float e = t * t * (3 - 2 * t);
                float wx = sx0 + (getWidth() / 2f - sx0) * 0.35f, wy = clampY(sy0 + 50 * d);
                x = sx0 + (wx - sx0) * e;
                y = sy0 + (wy - sy0) * e;
                zs = 1 + 1.0f * e;
                faceRight = tcx >= x;
                angle = -15 * e;
                if (t >= 1) {
                    sx0 = x; sy0 = y;
                    mx1 = clampX(tcx); my1 = by1;
                    cx1 = (sx0 + mx1) / 2;
                    cy1 = Math.min(ground(), Math.max(sy0, my1) + 90 * d);
                    float dist = (float) Math.hypot(mx1 - sx0, my1 - sy0) / d;
                    zFrom = zs; zTo = 1;
                    setPhase(41, (long) Math.max(340, Math.min(500, 300 + dist * 0.3f)), now);
                }
                return;
            }
            case 41: {
                float e = (float) Math.pow(t, 1.35);
                x = bez(sx0, cx1, mx1, e);
                y = bez(sy0, cy1, my1, e);
                zs = zFrom + (zTo - zFrom) * e;
                float tx = bezD(sx0, cx1, mx1, e), ty = bezD(sy0, cy1, my1, e);
                if (Math.abs(tx) > 1) faceRight = tx > 0;
                angle = flightAngle(tx, ty);
                trail(now, 1);
                if (t >= 1) { bumping = false; zs = 1; vx = 0; vy = 260 * d; angle = 0; roamUntil = 0; doHit(now, x, bumpRect.bottom); }
                return;
            }

            // ---- aurora: spiral up beside it, throw a magic star
            case 50: {
                float sxT = clampX(tcx + side * 100 * d), syT = clampY(Math.min(bumpRect.bottom + 80 * d, ground() - 10 * d));
                float e = t * t * (3 - 2 * t), r = 34 * d * (1 - t);
                double a = t * Math.PI * 3;
                x = sx0 + (sxT - sx0) * e + (float) Math.cos(a) * r;
                y = sy0 + (syT - sy0) * e + (float) Math.sin(a) * r * 0.6f;
                angle = (float) Math.sin(now / 120.0) * 8;
                zs = 1 + 0.35f * (float) Math.sin(Math.PI * t);
                if (rnd.nextBoolean()) twinkle(x + (rnd.nextFloat() - 0.5f) * sw, y - rnd.nextFloat() * sh);
                if (t >= 1) {
                    faceRight = tcx >= x;
                    angle = 0;
                    float hx = x + (faceRight ? 6 : -6) * px, hy = y - sh + 2 * px;
                    zs = 1;
                    launchProjectile(P_STAR, hx, hy, tcx, tcy, -60 * d, 620, now);
                    setPhase(51, 620, now);
                }
                return;
            }
            case 51:
                if (projKind == P_STAR) {
                    float pt = Math.min(1, (now - projT0) / (float) projDur);
                    twinkle(bez(pjSX, pjCX, pjEX, pt) + (rnd.nextFloat() - 0.5f) * 20 * d * projScale(pt), bez(pjSY, pjCY, pjEY, pt));
                }
                if (t >= 1) { bumping = false; roamUntil = 0; doHit(now, tcx, tcy); for (int i = 0; i < 10; i++) twinkle(tcx + (rnd.nextFloat() - 0.5f) * bumpRect.width(), tcy + (rnd.nextFloat() - 0.5f) * bumpRect.height()); }
                return;
            default:
                bumping = false;
        }
    }

    /** Comic: a portal opens under the tile and Pixie shoots up out of it. */
    private void startEmerge(long now) {
        hiddenInPortal = false;
        tp = 3;
        x = clampX(tcx);
        openPortal(x, Math.min(ground(), bumpRect.bottom + sh + 14 * d), 1200, now);
        y = portalY + sh;
        setPhase(21, 230, now);
    }

    private void launchProjectile(int kindP, float fromX, float fromY, float toX, float toY, float arc, long dur, long now) {
        projKind = kindP;
        projT0 = now;
        projDur = dur;
        pjSX = fromX; pjSY = fromY; pjEX = toX; pjEY = toY;
        float midX = (fromX + toX) / 2, midY = Math.min(fromY, toY) + arc;
        pjCX = midX + (getWidth() / 2f - midX) * 0.6f;        // swing through the middle of the screen,
        pjCY = midY + (getHeight() * 0.45f - midY) * 0.5f;    // where it's biggest: "out of the TV"
    }

    /** Projectile size along its flight: zooms toward the viewer mid-way, then shrinks into the tile. */
    private float projScale(float t) {
        float peak = projKind == P_PLANE ? 2.8f : 2.3f;
        return (1 - 0.5f * t) + peak * (float) Math.pow(Math.sin(Math.PI * t), 1.4);
    }

    /** Tile reaction: bounces up like a struck block, or glitch-shakes after a laser. */
    private void stepTile(long now) {
        if (tileT0 < 0 || bumpTile == null) return;
        float k = (now - tileT0) / (shakeTile ? 320f : 200f);
        if (k >= 1) { resetTile(); return; }
        if (shakeTile) bumpTile.setTranslationX((float) Math.sin(k * Math.PI * 10) * 7 * d * (1 - k));
        else bumpTile.setTranslationY(-12 * d * (float) Math.sin(Math.PI * k));
    }

    private void resetTile() {
        if (bumpTile != null) { bumpTile.setTranslationY(0); bumpTile.setTranslationX(0); }
        tileT0 = -1;
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        if (!enabled) return;
        drawFloor(c);
        if (x < 0) return;
        long now = SystemClock.uptimeMillis();
        boolean hidden = !overlayPixies.isEmpty();
        drawParticles(c, now);
        drawPrize(c, now);
        drawProjectile(c, now);
        drawPortal(c, now);
        if (hidden || hiddenInPortal || transition) return;
        float ground = ground();
        boolean dozing = dozing(now);

        float shakeX = bumping && phase == 30 ? (float) Math.sin(now * 0.9) * 1.5f * px : 0;
        float sink = 0;
        if (tp == 1) sink = sh * clamp((now - tpT0 - 50) / (float) (TP_OUT - 80));
        else if (tp == 2) sink = sh * (1 - clamp((now - tpT0 - 60) / (float) (TP_IN - 100)));
        float dx = x + shakeX, dy = y + perchHop + sink;

        // shadow: on the floor (smaller when high up), or on the perch
        float shY = move == M_PORTAL && !comicFall && tp != 3 ? y : ground;
        float lift = Math.max(0, Math.min(1, (shY - dy) / (200 * d)));
        if (tp == 0 || tp == 2) {
            p.setColor(0x000000);
            p.setAlpha((int) (70 * (1 - lift * 0.7f)));
            float shw = 6 * px * (1 - lift * 0.5f) * zs;
            c.drawOval(dx - shw, shY - px * 0.8f * zs, dx + shw, shY + px * 0.8f * zs, p);
            p.setAlpha(255);
        }

        Bitmap f = frame(now, dozing);
        float bob = (!bumping && move == M_GROUND && !moving && !air && sayText == null) ? (((now / 700) % 2 == 0) ? 0 : -px * 0.5f)
                : (move == M_GROUND && moving && !air && !bumping && !runPhase ? -px : 0);
        float rot = rotation();
        float pop = now - shownAt < 260 ? overshoot((now - shownAt) / 260f) : 1;

        c.save();
        if (tp != 0 || comicFall) c.clipRect(0, 0, getWidth(), tp == 1 ? tpSrcY : portalY);
        if (!faceRight) c.scale(-1, 1, dx, 0);
        if (zs != 1) c.scale(zs, zs, dx, dy - sh / 2);           // depth: closer = bigger
        if (rot != 0) c.rotate(rot, dx, dy - sh / 2);
        float sqx = pop, sqy = pop;
        if (now < squashUntil) { sqx *= 1.15f; sqy *= 0.86f; }
        c.scale(sqx, sqy, dx, dy);
        float left = dx - sw / 2, top = dy - sh + bob;
        if (kind == K_STAR) wings(c, left, top, now);
        if (kind == K_LASER) flame(c, left, top, now, flamePower(now, dozing));
        dst.set(left, top, left + sw, top + sh);
        c.drawBitmap(f, null, dst, bp);
        if ((now / 600) % 3 == 2 || dozing) {               // antenna light blinks
            p.setColor(Theme.shade(set.outfit.antenna, -0.55f));
            c.drawRect(left + Sprites.ANT_X * px, top + Sprites.ANT_Y * px,
                    left + (Sprites.ANT_X + Sprites.ANT_W) * px, top + (Sprites.ANT_Y + 1) * px, p);
        }
        c.restore();

        if (bumping && kind == K_LASER && phase >= 32) drawLaser(c, now);

        if (dozing) {                                       // z z z
            p.setColor(Theme.on(th.bg) == 0xFFFFFFFF ? 0xFFFFFFFF : 0xFF111111);
            p.setTypeface(Typeface.create("monospace", Typeface.BOLD));
            for (int i = 0; i < 3; i++) {
                float k = ((now / 1000f) + i * 0.66f) % 2f / 2f;
                p.setTextSize((10 + 6 * k) * d);
                p.setAlpha((int) (255 * (1 - k)));
                c.drawText("z", dx + (faceRight ? 4 : -9) * px + k * 6 * d, dy - sh - k * 26 * d, p);
            }
            p.setAlpha(255);
        }
        if (sayText != null && !bumping && tp == 0) drawSpeech(c, now, dx, dy - (zs - 1) * sh / 2);
    }

    private Bitmap frame(long now, boolean dozing) {
        if (bumping) {
            switch (kind) {
                case K_PLANE: return phase == 10 ? set.wave1 : (now - phaseT0 < 160 ? set.wave2 : set.idle);
                case K_PORTAL: return set.jump;
                case K_LASER: return phase >= 32 ? set.jump : set.idle;
                case K_SWOOP: return phase == 41 ? set.jump : set.idle;
                case K_STAR: return phase == 51 ? (now - phaseT0 < 140 ? set.wave1 : set.wave2) : set.idle;
                default:
                    if (phase == 3 || air) return set.jump;
                    if (phase == 2) return set.idle;
                    return runPhase ? set.run1 : set.run2;
            }
        }
        if (comicFall) return set.jump;
        if (sayText != null && sayWave) return (now / 220) % 2 == 0 ? set.wave1 : set.wave2;
        switch (move) {
            case M_GROUND:
                if (air) return set.jump;
                if (moving) return runPhase ? set.run1 : set.run2;
                break;
            case M_PORTAL:
                if (perchHop < -2 * d) return set.jump;
                break;
            default:
                if (kind == K_SWOOP && angle > 40) return set.jump;   // superman pose
                break;
        }
        return dozing || now < blinkUntil ? set.blink : set.idle;
    }

    private float rotation() {
        if (bumping) {
            if (kind == K_LASER) return phase >= 32 ? aimAngle : angle;
            if (kind == K_SWOOP || kind == K_STAR) return angle;
            return 0;
        }
        return move == M_FLY ? angle : 0;
    }

    private float flamePower(long now, boolean dozing) {
        if (bumping && (phase == 30 || phase == 31)) return 1f;
        if (dozing && y >= ground() - 2 * d) return 0f;
        if (moving) return 0.6f;
        return 0.25f;
    }

    /** Little fairy wings behind the body, flapping. */
    private void wings(Canvas c, float left, float top, long now) {
        float flap = 0.55f + 0.45f * Math.abs((float) Math.sin(now / 90.0));
        float cx = left + 4 * px, cy = top + 11 * px;
        p.setColor(0x99FFFFFF);
        c.save();
        c.rotate(-35, cx, cy);
        c.drawOval(cx - 4 * px, cy - 6 * px * flap, cx + 1 * px, cy + px, p);
        p.setColor((th.accent2 & 0x00FFFFFF) | 0x77000000);
        c.drawOval(cx - 3 * px, cy - 4 * px * flap, cx + 2 * px, cy + 2 * px, p);
        c.restore();
    }

    /** Jetpack flame under the nozzle; power 0..1 sets its length. */
    private void flame(Canvas c, float left, float top, long now, float power) {
        if (power <= 0) return;
        float nx = left + Sprites.NOZZLE_X * px, ny = top + Sprites.NOZZLE_Y * px;
        int len = 1 + Math.round(power * 5) + (int) ((now / 60) % 2);
        int[] cols = {0xFFFFFFFF, 0xFFFFE14D, 0xFFFF9A3D, th.accent, th.accent};
        for (int i = 0; i < len; i++) {
            p.setColor(cols[Math.min(i, cols.length - 1)]);
            p.setAlpha(Math.max(40, 255 - i * 36));
            float wdt = i == 0 ? px : px * (i % 2 == 0 ? 1.2f : 0.8f);
            c.drawRect(nx - wdt / 2 + px / 2, ny + i * px, nx + wdt / 2 + px / 2, ny + (i + 1) * px, p);
        }
        p.setAlpha(255);
    }

    /** Fist position (blaster) for the current aim, in screen coordinates. */
    private float[] fist() {
        float lx = 7 * px * zs, ly = -7 * px * zs;
        double t = Math.toRadians(aimAngle);
        float rx = (float) (lx * Math.cos(t) - ly * Math.sin(t)), ry = (float) (lx * Math.sin(t) + ly * Math.cos(t));
        if (!faceRight) rx = -rx;
        return new float[]{x + rx, y - sh / 2 + ry};
    }

    private void drawLaser(Canvas c, long now) {
        float[] f = fist();
        if (phase == 32) {                                  // charging
            float k = phaseT(now);
            p.setColor((th.accent2 & 0x00FFFFFF) | 0x55000000);
            c.drawCircle(f[0], f[1], (4 + 10 * k) * d * zs, p);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(f[0], f[1], (2 + 4 * k) * d * zs, p);
            return;
        }
        float k = phaseT(now), flick = 0.8f + 0.2f * (float) Math.sin(now * 0.8);
        float fade = k > 0.75f ? (1 - k) / 0.25f : 1;
        // perspective beam: wide at Pixie (close to you), thin where it hits the tile (deep in the screen)
        beam(c, f[0], f[1], 26 * d * zs * flick, 9 * d, th.accent, (int) (90 * fade));
        beam(c, f[0], f[1], 11 * d * zs * flick, 3.5f * d, th.accent2, (int) (255 * fade));
        beam(c, f[0], f[1], 4 * d * zs, 1.2f * d, 0xFFFFFFFF, (int) (255 * fade));
        p.setColor(0xFFFFFFFF); p.setAlpha((int) (230 * fade));             // muzzle flash
        c.drawCircle(f[0], f[1], (8 + 6 * flick) * d * zs, p);
        p.setColor(0xFFFFFFFF); p.setAlpha((int) (220 * fade));             // impact
        c.drawCircle(tcx, tcy, (8 + 6 * flick) * d, p);
        p.setColor(th.accent2); p.setAlpha((int) (120 * fade));
        c.drawCircle(tcx, tcy, (20 + 12 * k) * d, p);
        p.setAlpha(255);
    }

    /** A tapered beam from (x0, y0) (half-width w0) to the tile centre (half-width w1). */
    private void beam(Canvas c, float x0, float y0, float w0, float w1, int color, int alpha) {
        float dx = tcx - x0, dy = tcy - y0, len = (float) Math.max(1, Math.hypot(dx, dy));
        float nx = -dy / len, ny = dx / len;
        path.reset();
        path.moveTo(x0 + nx * w0, y0 + ny * w0);
        path.lineTo(tcx + nx * w1, tcy + ny * w1);
        path.lineTo(tcx - nx * w1, tcy - ny * w1);
        path.lineTo(x0 - nx * w0, y0 - ny * w0);
        path.close();
        p.setColor(color);
        p.setAlpha(alpha);
        c.drawPath(path, p);
    }

    private void drawProjectile(Canvas c, long now) {
        if (projKind == P_NONE) return;
        float t = clamp((now - projT0) / (float) projDur);
        for (int g = 3; g >= 0; g--) {                       // ghost trail behind it (motion blur)
            float tg = t - g * 0.035f;
            if (tg < 0) continue;
            drawProjectileAt(c, tg, g == 0 ? 255 : 70 - g * 18);
        }
    }

    private void drawProjectileAt(Canvas c, float t, int alpha) {
        float qx = bez(pjSX, pjCX, pjEX, t), qy = bez(pjSY, pjCY, pjEY, t);
        float ang = (float) Math.toDegrees(Math.atan2(bezD(pjSY, pjCY, pjEY, t), bezD(pjSX, pjCX, pjEX, t)));
        float sc = projScale(t);
        c.save();
        c.translate(qx, qy);
        c.scale(sc, sc);
        if (projKind == P_PLANE) {
            c.rotate(ang);
            c.scale(1, 0.55f + 0.45f * Math.abs((float) Math.cos(t * Math.PI * 1.5)));   // rolls as it flies
            path.reset();
            path.moveTo(10 * d, 0); path.lineTo(-8 * d, -6 * d); path.lineTo(-4 * d, 0); path.lineTo(-8 * d, 6 * d); path.close();
            p.setColor(0xFFFFFFFF); p.setAlpha(alpha);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * d); p.setColor(0xFF2A2F3A); p.setAlpha(alpha);
            c.drawPath(path, p);
            c.drawLine(10 * d, 0, -4 * d, 0, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            c.rotate(t * 720);
            p.setColor((th.accent2 & 0x00FFFFFF) | 0x66000000); p.setAlpha(Math.min(alpha, 0x66));
            c.drawCircle(0, 0, 14 * d, p);
            path.reset();
            for (int i = 0; i < 10; i++) {
                float r = (i % 2 == 0 ? 10 : 4.5f) * d;
                double a = -Math.PI / 2 + i * Math.PI / 5;
                if (i == 0) path.moveTo((float) Math.cos(a) * r, (float) Math.sin(a) * r);
                else path.lineTo((float) Math.cos(a) * r, (float) Math.sin(a) * r);
            }
            path.close();
            p.setColor(0xFFFFFFFF); p.setAlpha(alpha);
            c.drawPath(path, p);
        }
        p.setAlpha(255);
        c.restore();
    }

    /** Comic portal: a swirling hole that opens and closes. */
    private void drawPortal(Canvas c, long now) {
        if (tp == 0 && !comicFall) return;
        float k = clamp((now - portalT0) / (float) portalDur);
        float open = Math.min(1, k / 0.25f) * Math.min(1, (1 - k) / 0.25f);
        if (open <= 0) return;
        float cxp = tp == 1 ? tpSrcX : portalX, cyp = tp == 1 ? tpSrcY : portalY;
        float rx = 12 * px * open, ry = rx * 0.34f;
        p.setColor(0xFF111111);
        c.drawOval(cxp - rx, cyp - ry, cxp + rx, cyp + ry, p);
        p.setStyle(Paint.Style.STROKE);
        float spin = (now % 600) / 600f;
        p.setStrokeWidth(3 * d);
        p.setColor(th.accent2);
        c.drawOval(cxp - rx, cyp - ry, cxp + rx, cyp + ry, p);
        p.setColor(th.accent);
        float in = 0.55f + 0.25f * (float) Math.sin(spin * Math.PI * 2);
        c.drawOval(cxp - rx * in, cyp - ry * in, cxp + rx * in, cyp + ry * in, p);
        p.setStrokeWidth(4 * d);
        p.setColor(0xFF111111);
        c.drawOval(cxp - rx - 2 * d, cyp - ry - 2 * d, cxp + rx + 2 * d, cyp + ry + 2 * d, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawParticles(Canvas c, long now) {
        for (int i = 0; i < parts.length; i++) {
            float[] q = parts[i];
            if (q == null) continue;
            float k = Math.min(1, (now - partT0[i]) / q[5]), t = (now - partT0[i]) / 1000f;
            int kp = (int) q[8];
            float qx = q[0] + q[2] * t, qy = q[1] + q[3] * t + (kp == 1 ? 0.5f * 900 * d * t * t : 0);
            p.setColor((int) q[7]);
            p.setAlpha((int) (255 * (1 - k)));
            switch (kp) {
                case 2: c.drawRect(qx - q[6] * 3, qy - px * 0.3f, qx + q[6] * 3, qy + px * 0.3f, p); break;
                case 3: {
                    float s = q[6] * (1 - k);
                    c.drawRect(qx - s / 2, qy - s * 1.5f, qx + s / 2, qy + s * 1.5f, p);
                    c.drawRect(qx - s * 1.5f, qy - s / 2, qx + s * 1.5f, qy + s / 2, p);
                    break;
                }
                default: {
                    float s = q[6] * (kp == 1 ? (1 - k * 0.6f) : (1 + k));
                    c.drawRect(qx - s / 2, qy - s / 2, qx + s / 2, qy + s / 2, p);
                }
            }
        }
        p.setAlpha(255);
    }

    /** What pops out of the tile: a coin (Retro, Minimal), a POW! (Comic), or a ring of light. */
    private void drawPrize(Canvas c, long now) {
        if (prizeT0 < 0) return;
        float k = (now - prizeT0) / 650f;
        if (k >= 1 || k < 0) return;                         // finished (or stale): never draw past the end
        float rise = 70 * d * (float) (1 - Math.pow(1 - Math.min(1, k / 0.55f), 2)) - (k > 0.55f ? 20 * d * (k - 0.55f) : 0);
        int alpha = Math.max(0, Math.min(255, k > 0.7f ? (int) (255 * (1 - (k - 0.7f) / 0.3f)) : 255));
        switch (kind) {
            case K_PORTAL: {
                float sc = 0.3f + 2.3f * (1 - (1 - Math.min(1, k)) * (1 - Math.min(1, k)));   // flies out at you
                c.save();
                c.translate(prizeX, prizeY - 16 * d - rise * 0.5f);
                c.rotate(-10);
                c.scale(sc, sc);
                p.setAlpha(alpha);
                star(c, 46 * d, 30 * d, 12, 0xFFFFFFFF);
                p.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(22 * d);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setColor(0xFF111111); p.setAlpha(alpha);
                c.drawText(powWord, 0, 8 * d, p);
                p.setStyle(Paint.Style.FILL); p.setColor(th.accent); p.setAlpha(alpha);
                c.drawText(powWord, 0, 8 * d, p);
                p.setTextAlign(Paint.Align.LEFT);
                p.setAlpha(255);
                c.restore();
                break;
            }
            case K_JUMP: case K_PLANE: {
                float spin = Math.abs((float) Math.cos(k * Math.PI * 3));
                float cw = 8 * px, ch = 10 * px;
                c.save();
                c.translate(prizeX, prizeY - rise - ch / 2 - 4 * d);
                float zc = 1 + 1.4f * Math.min(1, k);                               // comes out of the screen
                c.scale(Math.max(0.15f, spin) * zc, zc);
                bp.setAlpha(alpha);
                dst.set(-cw / 2, -ch / 2, cw / 2, ch / 2);
                c.drawBitmap(set.coin, null, dst, bp);
                bp.setAlpha(255);
                c.restore();
                break;
            }
            default: {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3 * d * (1 - k));
                p.setColor(th.accent2);
                p.setAlpha(alpha);
                c.drawCircle(prizeX, kind == K_SWOOP ? bumpRect.bottom : prizeY, 10 * d + k * 70 * d, p);
                p.setStyle(Paint.Style.FILL);
                p.setAlpha(255);
            }
        }
    }

    private void star(Canvas c, float ro, float ri, int spikes, int fill) {
        path.reset();
        double step = Math.PI / spikes;
        for (int i = 0; i < spikes * 2; i++) {
            float r = (i % 2 == 0) ? ro : ri;
            float sx = (float) (Math.cos(i * step) * r), sy = (float) (Math.sin(i * step) * r);
            if (i == 0) path.moveTo(sx, sy); else path.lineTo(sx, sy);
        }
        path.close();
        int a = p.getAlpha();
        p.setStyle(Paint.Style.FILL); p.setColor(fill); p.setAlpha(a);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setColor(0xFF111111); p.setAlpha(a);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Speech bubble above Pixie's head, kept on screen. */
    private void drawSpeech(Canvas c, long now, float atX, float atY) {
        float k = Math.min(1, (now - sayT0) / 160f);
        p.setTypeface(th.bold());
        p.setTextSize(16 * d);
        float tw = p.measureText(sayText);
        float pad = 16 * d, ts = Bubble.tailSize(getContext());
        float bw = tw + 2 * pad, bh = 22 * d + 2 * 10 * d + ts;
        float bx = Math.max(10 * d, Math.min(getWidth() - bw - 10 * d, atX - bw / 2));
        float by = Math.max(8 * d, atY - sh - bh - 4 * d);
        c.save();
        c.scale(0.6f + 0.4f * k, 0.6f + 0.4f * k, atX, atY - sh);
        bubble.setBounds(Math.round(bx), Math.round(by), Math.round(bx + bw), Math.round(by + bh));
        bubble.draw(c);
        p.setColor(Bubble.textColor(th));
        c.drawText(sayText, bx + pad - (("retro".equals(th.id) || "comic".equals(th.id)) ? 2 * d : 0), by + 10 * d + 17 * d, p);
        c.restore();
    }

    // ---------------------------------------------------------------- floor

    private void drawFloor(Canvas c) {
        if ("none".equals(floorKind)) return;
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        if (floor == null || floorW != w) buildFloor(w);
        if (floor != null) c.drawBitmap(floor, 0, h - floorH, null);
    }

    /** Pre-rendered once per theme/size: one bitmap draw per frame. */
    private void buildFloor(int w) {
        floorW = w;
        float ground = groundDp * d;
        if ("retro".equals(floorKind)) {
            float shade = 8 * px, grass = 9 * px;
            floorH = Math.round(ground + grass + shade);
            floor = Bitmap.createBitmap(w, floorH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(floor);
            Paint q = new Paint();
            float gTop = floorH - ground;                    // top of the bricks = where feet stand
            // stepped shadow (solid 8-bit steps, no speckle over text), darkening toward the grass
            int[] steps = {0x26, 0x4A, 0x70, 0x98};
            float s0 = gTop - 4 * px - shade;
            for (int i = 0; i < steps.length; i++) {
                q.setColor((steps[i] << 24) | 0x0B3318);
                c.drawRect(0, s0 + i * 2 * px, w, s0 + (i + 1) * 2 * px, q);
            }
            q.setColor(0xB80B3318);
            c.drawRect(0, s0 + shade, w, gTop, q);
            // grass in front of the tiles: a solid base, then tufts of mixed height, light tips
            q.setColor(0xFF1F6B33);
            c.drawRect(0, gTop - 3 * px, w, gTop, q);
            int[] tuft = {4, 6, 9, 6, 4, 5, 8, 5, 3, 5, 7, 4, 6, 9, 7, 4, 3, 6, 8, 5};
            for (int cx = 0; cx * px < w; cx++) {
                int hh = tuft[(cx * 7 + cx / 3) % tuft.length];
                float x0 = cx * px;
                q.setColor(cx % 3 == 0 ? 0xFF2E8B3E : 0xFF36A046);
                c.drawRect(x0, gTop - hh * px, x0 + px, gTop - 2 * px, q);
                q.setColor(0xFF7CDB5A);
                c.drawRect(x0, gTop - hh * px, x0 + px, gTop - (hh - 1) * px, q);   // light tip
            }
            q.setColor(0xFF143D22);
            c.drawRect(0, gTop - px * 0.5f, w, gTop, q);
            // bricks
            float bw = 32 * d, bh = ground / 2;
            q.setColor(0xFFB5651D);
            c.drawRect(0, gTop, w, floorH, q);
            q.setColor(0xFF7A3E12);
            for (int row = 0; row < 2; row++) {
                float yy = gTop + row * bh;
                c.drawRect(0, yy, w, yy + 2 * d, q);
                for (float xx = (row % 2) * bw / 2; xx < w; xx += bw) c.drawRect(xx, yy, xx + 2 * d, yy + bh, q);
            }
        } else {
            floorH = Math.round(ground + 96 * d);
            floor = Bitmap.createBitmap(w, floorH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(floor);
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            int base = th.bg & 0x00FFFFFF;
            q.setShader(new LinearGradient(0, 0, 0, floorH, new int[]{base, base | 0xB0000000, base | 0xF0000000},
                    new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, floorH, q);
            q.setShader(null);
            float line = floorH - ground;
            switch (th.id) {
                case "comic":
                    q.setColor(th.bg2);
                    c.drawRect(0, line, w, floorH, q);
                    q.setColor(0xFF111111);
                    c.drawRect(0, line - 2 * d, w, line + 2 * d, q);
                    break;
                case "neon":
                    q.setColor((th.accent2 & 0x00FFFFFF) | 0x40000000);
                    c.drawRect(0, line - 4 * d, w, line + 4 * d, q);
                    q.setColor(th.accent2);
                    c.drawRect(0, line - d, w, line + d, q);
                    break;
                case "hero":
                    q.setColor((th.accent2 & 0x00FFFFFF) | 0x80000000);
                    c.drawRect(0, line - d * 0.5f, w, line + d * 0.5f, q);
                    break;
                default: break;
            }
        }
    }

    // ---------------------------------------------------------------- particles

    /** Motion trail for the flying styles: exhaust, speed lines, sparkles. */
    private void trail(long now, float speed) {
        if (kind == K_LASER) exhaust(now, speed > 0.3f ? 2 : (rnd.nextInt(3) == 0 ? 1 : 0));
        else if (kind == K_SWOOP && speed > 0.4f) {
            add(x - (faceRight ? 1 : -1) * sw * 0.6f, y - sh * (0.3f + rnd.nextFloat() * 0.4f), (faceRight ? -1 : 1) * 60 * d, 0,
                    now, 260, px, 0xFFFFFFFF, 2);
        } else if (kind == K_STAR && rnd.nextInt(speed > 0.2f ? 2 : 5) == 0) {
            twinkle(x - (faceRight ? 1 : -1) * sw * 0.3f + (rnd.nextFloat() - 0.5f) * 10 * d, y - sh * rnd.nextFloat() * 0.8f);
        }
    }

    private void exhaust(long now, int n) {
        float nx = x - (faceRight ? 1 : -1) * 5 * px;
        for (int i = 0; i < n; i++)
            add(nx + (rnd.nextFloat() - 0.5f) * px, y - 2 * px, (rnd.nextFloat() - 0.5f) * 30 * d, 60 * d + rnd.nextFloat() * 40 * d,
                    now, 300, px * 1.2f, rnd.nextBoolean() ? th.accent : 0xFFFFE14D, 0);
    }

    private void twinkle(float ax, float ay) {
        add(ax, ay, 0, -10 * d, SystemClock.uptimeMillis(), 600, px * 0.9f, rnd.nextBoolean() ? 0xFFFFFFFF : th.accent2, 3);
    }

    private void puff(float atX, float atY, int n, int color) {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < n; i++)
            add(atX + (rnd.nextFloat() - 0.5f) * 6 * px, atY - px, (rnd.nextFloat() - 0.5f) * 80 * d, -20 * d - rnd.nextFloat() * 30 * d,
                    now, 320, 2 * px, color, 0);
    }

    private void sparks(float atX, float atY) {
        long now = SystemClock.uptimeMillis();
        int[] cols = {0xFFFFD84D, 0xFFFFFFFF, th.accent2};
        for (int i = 0; i < 8; i++) {
            double a = Math.PI + Math.PI * (i + 0.5) / 8;     // fan upward-out from the hit point
            float sp = (160 + rnd.nextInt(120)) * d;
            add(atX, atY, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp * 0.6f, now, 420, px * 1.2f, cols[i % 3], 1);
        }
    }

    private void add(float ax, float ay, float vx0, float vy0, long t0, float life, float size, int color, int kp) {
        for (int i = 0; i < parts.length; i++)
            if (parts[i] == null) { parts[i] = new float[]{ax, ay, vx0, vy0, 0, life, size, color, kp}; partT0[i] = t0; return; }
    }

    // ---------------------------------------------------------------- helpers

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }

    private static float bez(float a, float b, float c, float t) { float u = 1 - t; return u * u * a + 2 * u * t * b + t * t * c; }

    private static float bezD(float a, float b, float c, float t) { return 2 * (1 - t) * (b - a) + 2 * t * (c - b); }

    /** Degrees clockwise from "up" for a direction, mirrored to the facing side. */
    private static float flightAngle(float dx, float dy) { return (float) Math.toDegrees(Math.atan2(Math.abs(dx), -dy)); }

    private static float overshoot(float t) {
        float c1 = 2.2f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    /** On-screen rect of a tile's card (or any view), in this view's coordinates, including focus scale. */
    private void rectOf(View v, RectF out) {
        View target = v instanceof Tile ? ((Tile) v).card : v;
        int[] a = new int[2], me = new int[2];
        target.getLocationOnScreen(a);
        getLocationOnScreen(me);
        float sx = v.getScaleX(), sy = v.getScaleY();
        out.set(a[0] - me[0], a[1] - me[1], a[0] - me[0] + target.getWidth() * sx, a[1] - me[1] + target.getHeight() * sy);
    }
}
