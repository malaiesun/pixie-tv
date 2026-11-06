package dev.malaiesun.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/** The animated "PIXIE" wordmark, drawn in the active theme's logo style. */
final class LogoView extends View implements Pulse.Listener {
    private static final String WORD = "PIXIE";
    private Theme th = Theme.preset("aurora");
    private boolean running;
    private final long t0 = SystemClock.uptimeMillis();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mx = new Matrix();
    private final Path path = new Path();
    private final float d;
    private LinearGradient shimmer;

    // 5x7 pixel glyphs (only the letters we need)
    private static final String[][] GLYPH = {
            {"11110", "10001", "10001", "11110", "10000", "10000", "10000"},  // P
            {"11111", "00100", "00100", "00100", "00100", "00100", "11111"},  // I
            {"10001", "10001", "01010", "00100", "01010", "10001", "10001"},  // X
            {"11111", "00100", "00100", "00100", "00100", "00100", "11111"},  // I
            {"11111", "10000", "10000", "11110", "10000", "10000", "11111"},  // E
    };

    LogoView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    void setTheme(Theme t) {
        th = t;
        shimmer = null;
        requestLayout();
        invalidate();
    }

    void setRunning(boolean r) {
        running = r;
        if (r && !"minimal".equals(th.logo)) Pulse.add(this); else Pulse.remove(this);
        invalidate();
    }

    @Override public void onPulse(long now) {
        if (!running || "minimal".equals(th.logo) || !isAttachedToWindow()) { Pulse.remove(this); return; }
        invalidate();
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); Pulse.remove(this); }

    @Override protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(Math.round(250 * d), Math.round(84 * d));
    }

    @Override protected void onDraw(Canvas c) {
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        float cy = getHeight() * 0.68f;
        p.reset();
        p.setAntiAlias(true);
        switch (th.logo) {
            case "comic": comic(c, t, cy); break;
            case "hero": hero(c, t, cy); break;
            case "retro": retro(c, t); break;
            case "neon": neon(c, t, cy); break;
            case "minimal": plain(c, cy); break;
            default: aurora(c, t, cy); break;
        }
    }

    private void text(float sizeDp, Typeface tf) {
        p.setTypeface(tf);
        p.setTextSize(sizeDp * d);
        p.setLetterSpacing(0.06f);
    }

    private void aurora(Canvas c, float t, float cy) {
        text(54, Typeface.create("sans-serif-black", Typeface.NORMAL));
        if (shimmer == null)
            shimmer = new LinearGradient(0, 0, 220 * d, 0, new int[]{th.accent, th.accent2, 0xFFFFFFFF, th.accent},
                    new float[]{0f, 0.45f, 0.55f, 1f}, Shader.TileMode.MIRROR);
        mx.setTranslate((t * 60 * d) % (440 * d), 0);
        shimmer.setLocalMatrix(mx);
        p.setShader(shimmer);
        p.setShadowLayer(14 * d, 0, 0, (th.accent & 0x00FFFFFF) | 0x88000000);
        c.drawText(WORD, 4 * d, cy, p);
    }

    private void comic(Canvas c, float t, float cy) {
        float bounce = 1f + 0.03f * (float) Math.sin(t * 3.2f);
        c.save();
        c.rotate(-6, getWidth() / 2f, getHeight() / 2f);
        c.scale(bounce, bounce, getWidth() / 2f, getHeight() / 2f);
        // burst behind
        path.reset();
        float bx = getWidth() * 0.5f, by = getHeight() * 0.5f, ro = getWidth() * 0.48f, ri = ro * 0.78f;
        for (int i = 0; i < 28; i++) {
            double a = i * Math.PI / 14 + t * 0.15;
            float r = i % 2 == 0 ? ro : ri;
            float x = bx + (float) Math.cos(a) * r, y = by + (float) Math.sin(a) * r * 0.42f;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        p.setColor(0xFFFFFFFF);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setColor(0xFF111111);
        c.drawPath(path, p);
        // letters: hard shadow, thick outline, yellow fill
        text(50, Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        p.setTextAlign(Paint.Align.CENTER);
        float x = getWidth() / 2f, y = cy - 4 * d;
        p.setStyle(Paint.Style.FILL); p.setColor(th.accent);
        c.drawText(WORD, x + 5 * d, y + 5 * d, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(9 * d); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(0xFF111111);
        c.drawText(WORD, x, y, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFE600);
        c.drawText(WORD, x, y, p);
        c.restore();
    }

    private void hero(Canvas c, float t, float cy) {
        text(56, Typeface.create("sans-serif-condensed", Typeface.BOLD));
        float band = (t * 0.35f) % 1.6f - 0.3f;
        LinearGradient metal = new LinearGradient(0, cy - 48 * d, 0, cy + 6 * d,
                new int[]{0xFFFFF3C4, 0xFFF2C14E, 0xFF9A6B12, 0xFFF2C14E}, new float[]{0f, 0.45f, 0.75f, 1f}, Shader.TileMode.CLAMP);
        float glow = 0.5f + 0.5f * (float) Math.sin(t * 1.6f);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5 * d); p.setColor(th.accent);
        p.setShadowLayer((8 + glow * 10) * d, 0, 0, (th.accent & 0x00FFFFFF) | 0xAA000000);
        c.drawText(WORD, 6 * d, cy, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL); p.setShader(metal);
        c.drawText(WORD, 6 * d, cy, p);
        // moving highlight
        p.setShader(new LinearGradient(band * 240 * d, 0, (band + 0.25f) * 240 * d, 0,
                new int[]{0x00FFFFFF, 0xAAFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawText(WORD, 6 * d, cy, p);
    }

    private void retro(Canvas c, float t) {
        float cell = 6.5f * d;
        float bob = ((int) (t * 2) % 2 == 0) ? 0 : cell * 0.6f;   // stepwise bob, pixel feel
        float x0 = 4 * d, y0 = 12 * d + bob;
        int[] cols = {th.accent, 0xFFFFE14D, th.accent2, 0xFFFFE14D, th.accent};
        for (int g = 0; g < GLYPH.length; g++) {
            float gx = x0 + g * cell * 6.2f;
            for (int pass = 0; pass < 2; pass++) {
                p.setColor(pass == 0 ? 0xFF000000 : cols[g]);
                float off = pass == 0 ? cell * 0.7f : 0;
                for (int r = 0; r < 7; r++)
                    for (int k = 0; k < 5; k++)
                        if (GLYPH[g][r].charAt(k) == '1')
                            c.drawRect(gx + k * cell + off, y0 + r * cell + off, gx + (k + 1) * cell + off, y0 + (r + 1) * cell + off, p);
            }
        }
    }

    private void neon(Canvas c, float t, float cy) {
        text(54, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        boolean flicker = (t % 6f) > 5.6f && ((int) (t * 30) % 3 == 0);
        int a = flicker ? 0x55 : 0xFF;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3 * d);
        p.setColor((th.accent & 0x00FFFFFF) | (a << 24));
        p.setShadowLayer(18 * d, 0, 0, (th.accent & 0x00FFFFFF) | ((flicker ? 0x30 : 0xCC) << 24));
        c.drawText(WORD, 6 * d, cy, p);
        p.setShadowLayer(6 * d, 0, 0, (th.accent2 & 0x00FFFFFF) | 0x99000000);
        p.setStrokeWidth(1.2f * d);
        p.setColor(0xFFFFFFFF & ((a << 24) | 0x00FFFFFF));
        c.drawText(WORD, 6 * d, cy, p);
    }

    private void plain(Canvas c, float cy) {
        text(50, Typeface.create("sans-serif-black", Typeface.NORMAL));
        p.setColor(th.accent);
        c.drawText(WORD, 4 * d, cy, p);
    }
}
