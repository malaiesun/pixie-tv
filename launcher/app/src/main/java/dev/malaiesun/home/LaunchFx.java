package dev.malaiesun.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * App-opening transitions, one per theme, played from the tile that was clicked:
 *   retro    8-bit block wipe that closes in on the tile
 *   comic    "POW!" burst, then a halftone circle wipe
 *   hero     light rays and a flash
 *   neon     the tile's glowing outline grows to the screen edges (scanlines inside)
 *   aurora   soft glowing circle
 *   minimal  the tile grows to fill the screen
 * cover() ends with the screen covered, then the app is started; reveal() plays it backwards when
 * Home comes back. Uses its own clock, so the system animation scale doesn't change it.
 */
final class LaunchFx extends View {
    private static final int IDLE = 0, COVERING = 1, COVERED = 2, REVEALING = 3;
    private static final String[] WORDS = {"POW!", "ZAP!", "BAM!", "WHAM!", "BOOM!", "KAPOW!", "ZOOM!"};

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random rnd = new Random();
    private final RectF from = new RectF();

    private int state = IDLE, dur = 400;
    private long t0;
    private Theme th = Theme.preset("aurora");
    private String style = "aurora";
    private float cx, cy, maxD;
    private Runnable done;

    // per-style prepared data
    private float cell;
    private int cols, rows;
    private float[] delay;
    private String word = WORDS[0];
    private BitmapShader dots;

    LaunchFx(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    boolean busy() { return state == COVERING; }

    boolean covered() { return state == COVERED; }

    /** Plays the theme's opening effect from `r` (in this view's coordinates); `whenCovered` starts the app. */
    void cover(Theme t, RectF r, Runnable whenCovered) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) { whenCovered.run(); return; }
        th = t;
        style = t.id;
        from.set(r);
        cx = r.centerX();
        cy = r.centerY();
        maxD = 0;
        for (float[] k : new float[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) maxD = Math.max(maxD, (float) Math.hypot(k[0] - cx, k[1] - cy));
        switch (style) {
            case "retro": {
                dur = 440;
                cell = 24 * d;
                cols = (int) Math.ceil(w / cell);
                rows = (int) Math.ceil(h / cell);
                delay = new float[cols * rows];
                for (int y = 0; y < rows; y++)
                    for (int x = 0; x < cols; x++) {
                        float dist = (float) Math.hypot((x + 0.5f) * cell - cx, (y + 0.5f) * cell - cy) / maxD;
                        delay[y * cols + x] = (1 - dist) * 0.72f + rnd.nextFloat() * 0.08f;   // far blocks first
                    }
                break;
            }
            case "comic": {
                dur = 540;
                word = WORDS[rnd.nextInt(WORDS.length)];
                int tile = Math.round(18 * d);
                Bitmap b = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888);
                Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
                dp.setColor(Theme.shade(th.accent, -0.28f));
                new Canvas(b).drawCircle(tile / 2f, tile / 2f, tile * 0.26f, dp);
                dots = new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                break;
            }
            case "hero": dur = 500; break;
            case "neon": dur = 420; break;
            case "minimal": dur = 300; break;
            default: style = "aurora"; dur = 420; break;
        }
        done = whenCovered;
        state = COVERING;
        t0 = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    /** Back on Home: play the effect in reverse (or just clear it if nothing is showing). */
    void reveal() {
        if (state == IDLE || state == REVEALING) return;
        done = null;
        state = REVEALING;
        t0 = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    void reset() {
        state = IDLE;
        done = null;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (state == IDLE) return;
        long now = SystemClock.uptimeMillis();
        float pr;
        if (state == COVERING) {
            pr = (now - t0) / (float) dur;
            if (pr >= 1) {
                pr = 1;
                state = COVERED;
                Runnable r = done;
                done = null;
                if (r != null) post(r);
            }
        } else if (state == COVERED) {
            pr = 1;
        } else {
            pr = 1 - (now - t0) / (dur * 0.75f);
            if (pr <= 0) { state = IDLE; return; }
        }
        int w = getWidth(), h = getHeight();
        switch (style) {
            case "retro": retro(c, pr); break;
            case "comic": comic(c, pr); break;
            case "hero": hero(c, pr, w, h); break;
            case "neon": neon(c, pr, w, h); break;
            case "minimal": minimal(c, pr, w, h); break;
            default: aurora(c, pr); break;
        }
        if (state == COVERING || state == REVEALING) postInvalidateOnAnimation();
    }

    // ---------------------------------------------------------------- styles

    private void retro(Canvas c, float pr) {
        p.setColor(0xFF000000);
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++) {
                float g = (pr - delay[y * cols + x]) / 0.2f;
                if (g <= 0) continue;
                g = Math.min(1f, (float) Math.ceil(g * 4) / 4f);   // grows in steps: pixel feel
                float s = cell * g, x0 = x * cell + (cell - s) / 2, y0 = y * cell + (cell - s) / 2;
                c.drawRect(x0, y0, x0 + s + 0.5f, y0 + s + 0.5f, p);
            }
    }

    private void comic(Canvas c, float pr) {
        float wipe = clamp((pr - 0.28f) / 0.72f);
        if (wipe > 0) {
            float R = maxD * 1.04f * wipe * wipe + 1;
            p.setColor(th.accent);
            c.drawCircle(cx, cy, R, p);
            c.save();
            path.reset();
            path.addCircle(cx, cy, R, Path.Direction.CW);
            c.clipPath(path);
            p.setShader(dots);
            c.drawRect(cx - R, cy - R, cx + R, cy + R, p);
            p.setShader(null);
            c.restore();
            q.setStyle(Paint.Style.STROKE);
            q.setStrokeWidth(9 * d);
            q.setColor(0xFF111111);
            c.drawCircle(cx, cy, R, q);
            q.setStyle(Paint.Style.FILL);
        }
        float b = clamp(pr / 0.42f);
        float ro = Math.max(from.width(), from.height()) * 0.62f * backOut(b) + 8 * d;
        c.save();
        c.translate(cx, cy);
        c.rotate(-8);
        star(c, ro, ro * 0.66f, 14);
        q.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        q.setTextAlign(Paint.Align.CENTER);
        q.setTextSize(ro * 0.5f);
        float tw = q.measureText(word);
        if (tw > ro * 1.25f) q.setTextSize(ro * 0.5f * ro * 1.25f / tw);
        float base = q.getTextSize() * 0.36f;
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(Math.max(2 * d, q.getTextSize() * 0.12f));
        q.setColor(0xFF111111);
        c.drawText(word, 0, base, q);
        q.setStyle(Paint.Style.FILL);
        q.setColor(th.accent);
        c.drawText(word, 0, base, q);
        c.restore();
    }

    private void star(Canvas c, float ro, float ri, int spikes) {
        path.reset();
        double step = Math.PI / spikes;
        for (int i = 0; i < spikes * 2; i++) {
            float r = (i % 2 == 0) ? ro : ri;
            float x = (float) (Math.cos(i * step) * r), y = (float) (Math.sin(i * step) * r);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        p.setColor(0xFFFFFFFF);
        c.drawPath(path, p);
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(5 * d);
        q.setColor(0xFF111111);
        c.drawPath(path, q);
        q.setStyle(Paint.Style.FILL);
    }

    private void hero(Canvas c, float pr, int w, int h) {
        float a = clamp(pr / 0.65f);
        float L = maxD * 1.3f * easeOut(a);
        p.setColor((th.accent2 & 0x00FFFFFF) | 0x70000000);
        for (int i = 0; i < 14; i++) {
            double ang = Math.toRadians(i * (360.0 / 14) + pr * 40), half = Math.toRadians(4.5);
            path.reset();
            path.moveTo(cx, cy);
            path.lineTo(cx + (float) Math.cos(ang - half) * L, cy + (float) Math.sin(ang - half) * L);
            path.lineTo(cx + (float) Math.cos(ang + half) * L, cy + (float) Math.sin(ang + half) * L);
            path.close();
            c.drawPath(path, p);
        }
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(4 * d);
        q.setColor(th.accent2);
        q.setAlpha((int) (255 * (1 - a)));
        float g = 6 * d + a * 30 * d;
        c.drawRect(from.left - g, from.top - g, from.right + g, from.bottom + g, q);
        q.setAlpha(255);
        q.setStyle(Paint.Style.FILL);
        float f = clamp((pr - 0.25f) / 0.45f);
        if (f > 0) {
            float R = Math.max(1, maxD * 1.1f * f * f);
            p.setShader(new RadialGradient(cx, cy, R, new int[]{0xFFFFFFFF, 0xFFFFF1C9, th.accent2},
                    new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, R, p);
            p.setShader(null);
        }
        float k = clamp((pr - 0.72f) / 0.28f);
        if (k > 0) {
            p.setColor(th.bg);
            p.setAlpha((int) (255 * k));
            c.drawRect(0, 0, w, h, p);
            p.setAlpha(255);
        }
    }

    private void neon(Canvas c, float pr, int w, int h) {
        float e = easeInOut(pr), m = 14 * d;
        float l = lerp(from.left, -m, e), t = lerp(from.top, -m, e), r = lerp(from.right, w + m, e), b = lerp(from.bottom, h + m, e);
        float rad = lerp(th.radiusDp * d, 0, e);
        p.setColor(th.bg);
        p.setAlpha((int) (255 * Math.min(1, 0.6f + e)));
        c.drawRoundRect(l, t, r, b, rad, rad, p);
        p.setAlpha(255);
        c.save();
        c.clipRect(l, t, r, b);
        p.setColor((th.accent2 & 0x00FFFFFF) | 0x1C000000);
        for (float y = t + (pr * 40 * d) % (6 * d); y < b; y += 6 * d) c.drawRect(l, y, r, y + 1.5f * d, p);
        c.restore();
        q.setStyle(Paint.Style.STROKE);
        q.setStrokeWidth(12 * d);
        q.setColor((th.accent2 & 0x00FFFFFF) | 0x30000000);
        c.drawRoundRect(l, t, r, b, rad, rad, q);
        q.setStrokeWidth(3 * d);
        q.setColor(th.accent);
        c.drawRoundRect(l + 3 * d, t + 3 * d, r + 3 * d, b + 3 * d, rad, rad, q);
        q.setColor(th.accent2);
        c.drawRoundRect(l, t, r, b, rad, rad, q);
        q.setStyle(Paint.Style.FILL);
    }

    private void aurora(Canvas c, float pr) {
        float R = Math.max(1, maxD * 1.35f * easeInOut(pr));
        p.setShader(new RadialGradient(cx, cy, R, new int[]{th.bg, th.bg, th.accent2, (th.accent & 0x00FFFFFF) | 0x80000000, th.accent & 0x00FFFFFF},
                new float[]{0, 0.7f, 0.86f, 0.95f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R, p);
        p.setShader(null);
    }

    private void minimal(Canvas c, float pr, int w, int h) {
        float e = easeInOut(pr);
        float rad = lerp(th.radiusDp * d, 0, e);
        p.setColor(th.surface | 0xFF000000);
        c.drawRoundRect(lerp(from.left, 0, e), lerp(from.top, 0, e), lerp(from.right, w, e), lerp(from.bottom, h, e), rad, rad, p);
    }

    // ---------------------------------------------------------------- easing

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float easeOut(float t) { return 1 - (1 - t) * (1 - t); }

    private static float easeInOut(float t) { return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2; }

    private static float backOut(float t) {
        float c1 = 1.9f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }
}
