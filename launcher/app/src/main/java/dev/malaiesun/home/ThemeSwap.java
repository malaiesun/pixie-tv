package dev.malaiesun.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Theme change, the same for every theme: Pixie hops and spins like a coin with sparkles around it,
 * the outfit swaps when it's edge-on (with a flash), and the new theme opens up in a glowing circle that
 * grows out from Pixie over a snapshot of the old screen. Removes itself when done.
 */
final class ThemeSwap extends View {
    static final long DUR = 1000;

    private final float d, px, sw, sh;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bp = new Paint();
    private final Path path = new Path();
    private final RectF dst = new RectF();
    private Bitmap old;
    private Sprites.Set from, to;
    private Theme th;
    private float fx, fy, maxD;
    private boolean pixie;
    private long t0;
    private Runnable done;

    ThemeSwap(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        px = 3 * d;
        sw = 18 * px;
        sh = 18 * px;
        bp.setFilterBitmap(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** old: snapshot of the screen before; (feetX, feetY): where Pixie stands; to == null: no Pixie in the new theme. */
    void start(Bitmap old, Sprites.Set from, Sprites.Set to, Theme newTheme, float feetX, float feetY, boolean pixie, Runnable done) {
        this.old = old;
        this.from = from;
        this.to = to;
        th = newTheme;
        fx = feetX;
        fy = feetY;
        this.pixie = pixie;
        this.done = done;
        t0 = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    /** Jump to the end (another rebuild came in mid-way). */
    void finish() {
        Runnable r = done;
        done = null;
        if (r != null) r.run();
    }

    @Override protected void onDraw(Canvas c) {
        if (old == null) return;
        int w = getWidth(), h = getHeight();
        float t = Math.min(1, (SystemClock.uptimeMillis() - t0) / (float) DUR);
        float cx = fx, cy = fy - sh / 2;
        if (maxD == 0) for (float[] k : new float[][]{{0, 0}, {w, 0}, {0, h}, {w, h}}) maxD = Math.max(maxD, (float) Math.hypot(k[0] - cx, k[1] - cy));

        // old screen, with the new theme showing through a growing circle
        float rt = clamp((t - 0.32f) / 0.6f);
        float R = maxD * 1.05f * (rt < 0.5f ? 4 * rt * rt * rt : 1 - (float) Math.pow(-2 * rt + 2, 3) / 2);
        if (R < maxD * 1.04f) {
            c.save();
            if (R > 0) {
                path.reset();
                path.setFillType(Path.FillType.EVEN_ODD);
                path.addRect(0, 0, w, h, Path.Direction.CW);
                path.addCircle(cx, cy, R, Path.Direction.CW);
                c.clipPath(path);
            }
            dst.set(0, 0, w, h);
            c.drawBitmap(old, null, dst, null);
            c.restore();
        }
        if (R > 0 && R < maxD * 1.04f) {                     // glowing edge with sparkles riding it
            p.setStyle(Paint.Style.STROKE);
            p.setColor((th.accent & 0x00FFFFFF) | 0x55000000);
            p.setStrokeWidth(18 * d);
            c.drawCircle(cx, cy, R, p);
            p.setColor(th.accent2);
            p.setStrokeWidth(4 * d);
            c.drawCircle(cx, cy, R, p);
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(1.5f * d);
            c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8 + t * 3;
                plus(c, cx + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R, (2 + (i % 3)) * d, i % 2 == 0 ? 0xFFFFFFFF : th.accent2);
            }
        }

        if (pixie) {
            float hop = -46 * d * (float) Math.sin(Math.PI * clamp(t / 0.8f));
            float spin = (float) Math.cos(t * Math.PI * 4);    // edge-on at t = .125, .375, .625, .875
            boolean swapped = t >= 0.375f;
            Bitmap f = swapped ? (to != null ? (t > 0.8f ? to.idle : to.jump) : null) : (t < 0.12f ? from.idle : from.jump);
            float bx = fx, by = fy + hop;
            for (int i = 0; i < 6; i++) {                    // orbiting sparkles
                double a = t * Math.PI * 4 + i * Math.PI / 3;
                float r = (26 + 10 * (float) Math.sin(t * Math.PI)) * d;
                plus(c, bx + (float) Math.cos(a) * r, by - sh / 2 + (float) Math.sin(a) * r * 0.5f, 2.5f * d, i % 2 == 0 ? 0xFFFFFFFF : th.accent2);
            }
            if (f != null) {
                c.save();
                c.scale(Math.max(0.06f, Math.abs(spin)), 1, bx, 0);
                dst.set(bx - sw / 2, by - sh, bx + sw / 2, by);
                c.drawBitmap(f, null, dst, bp);
                c.restore();
            }
            float fl = (t - 0.33f) / 0.14f;                  // flash at the swap
            if (fl > 0 && fl < 1) {
                p.setColor(0xFFFFFFFF);
                p.setAlpha((int) (230 * (1 - fl)));
                c.drawCircle(bx, by - sh / 2, (14 + 40 * fl) * d, p);
                p.setAlpha(255);
            }
        }
        if (t < 1) postInvalidateOnAnimation();
        else post(this::finish);
    }

    private void plus(Canvas c, float x, float y, float s, int color) {
        p.setColor(color);
        c.drawRect(x - s / 2, y - s * 1.6f, x + s / 2, y + s * 1.6f, p);
        c.drawRect(x - s * 1.6f, y - s / 2, x + s * 1.6f, y + s / 2, p);
    }

    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
}
