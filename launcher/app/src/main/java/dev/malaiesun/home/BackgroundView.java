package dev.malaiesun.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Animated, procedurally drawn backgrounds (no image assets). ~30 fps while Home is visible,
 * stopped otherwise. Styles: aurora, comic (pop-art halftone + bursts), hero (light rays + embers),
 * retro (8-bit sky, clouds, hills, bricks), neon (synthwave sun + grid), minimal (static).
 */
final class BackgroundView extends View implements Pulse.Listener {
    private Theme th = Theme.preset("aurora");
    private String style = "aurora";
    private boolean running, groundStill;
    private final long t0 = SystemClock.uptimeMillis();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix mx = new Matrix();
    private float d;   // density

    // prepared per size/theme
    private RadialGradient[] blobs;
    private float blobR;
    private BitmapShader dots;
    private LinearGradient sky, vignetteTop;
    private RadialGradient vignette;
    private float[][] embers;

    BackgroundView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
    }

    void setTheme(Theme t) {
        th = t;
        style = t.background;
        prepare();
        invalidate();
    }

    /** The mascot stands on the Retro ground: keep it from sliding like a conveyor belt. */
    void setGroundStill(boolean still) { groundStill = still; }

    void setRunning(boolean r) {
        running = r;
        if (r && !"minimal".equals(style)) Pulse.add(this); else Pulse.remove(this);
        invalidate();
    }

    @Override public void onPulse(long now) {
        if (!running || "minimal".equals(style) || !isAttachedToWindow()) { Pulse.remove(this); return; }
        invalidate();
    }

    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); Pulse.remove(this); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { prepare(); }

    private void prepare() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        blobR = Math.max(w, h) * 0.55f;
        int[] cs = {th.accent, th.accent2, mix(th.accent, th.accent2), th.bg2};
        blobs = new RadialGradient[cs.length];
        for (int i = 0; i < cs.length; i++)
            blobs[i] = new RadialGradient(0, 0, blobR, (cs[i] & 0x00FFFFFF) | 0x66000000, cs[i] & 0x00FFFFFF, Shader.TileMode.CLAMP);
        int tile = Math.round(26 * d);
        Bitmap b = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(b);
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor((th.accent & 0x00FFFFFF) | 0x55000000);
        bc.drawCircle(tile / 2f, tile / 2f, tile * 0.2f, dp);
        dots = new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        sky = new LinearGradient(0, 0, 0, h, th.bg2, th.bg, Shader.TileMode.CLAMP);
        vignette = new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f, 0x00000000, 0xAA000000, Shader.TileMode.CLAMP);
        java.util.Random rnd = new java.util.Random(7);
        embers = new float[46][];
        for (int i = 0; i < embers.length; i++)
            embers[i] = new float[]{rnd.nextFloat() * w, rnd.nextFloat() * (h + 200), 20 + rnd.nextFloat() * 60, 1.5f + rnd.nextFloat() * 3f, rnd.nextFloat() * 6.28f};
    }

    @Override protected void onDraw(Canvas c) {
        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        int w = getWidth(), h = getHeight();
        switch (style) {
            case "comic": comic(c, t, w, h); break;
            case "hero": hero(c, t, w, h); break;
            case "retro": retro(c, t, w, h); break;
            case "neon": neon(c, t, w, h); break;
            case "minimal": p.setShader(sky); c.drawRect(0, 0, w, h, p); p.setShader(null); return;
            default: aurora(c, t, w, h); break;
        }
    }

    // ---------------------------------------------------------------- aurora

    private void aurora(Canvas c, float t, int w, int h) {
        c.drawColor(th.bg);
        for (int i = 0; i < blobs.length; i++) {
            float x = w * (0.5f + 0.42f * (float) Math.sin(t * 0.06f * (i + 1) + i * 1.7f));
            float y = h * (0.5f + 0.38f * (float) Math.cos(t * 0.045f * (i + 1.3f) + i * 2.3f));
            c.save();
            c.translate(x, y);
            p.setShader(blobs[i]);
            c.drawCircle(0, 0, blobR, p);
            c.restore();
        }
        p.setShader(null);
        // a few slow sparkles
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < 24; i++) {
            float a = (float) (0.5 + 0.5 * Math.sin(t * (0.6 + i % 5 * 0.2) + i));
            p.setAlpha((int) (a * 120));
            c.drawCircle((i * 397) % w, (i * 233) % h, 1.6f * d, p);
        }
        p.setAlpha(255);
    }

    // ---------------------------------------------------------------- comic / pop-art

    private static final String[] BURSTS = {"POW!", "ZAP!", "BAM!", "WOW!", "BOOM!", "KAPOW!"};

    private void comic(Canvas c, float t, int w, int h) {
        c.drawColor(th.bg);
        // diagonal color panels
        p.setColor((th.bg2 & 0x00FFFFFF) | 0xFF000000);
        path.reset(); path.moveTo(w * 0.62f, 0); path.lineTo(w, 0); path.lineTo(w, h); path.lineTo(w * 0.42f, h); path.close();
        c.drawPath(path, p);
        // halftone dots drifting
        mx.setTranslate((t * 14 * d) % (26 * d), (t * 7 * d) % (26 * d));
        dots.setLocalMatrix(mx);
        p.setShader(dots);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // slow rotating starburst (bottom right)
        float cx = w * 0.86f, cy = h * 0.8f, r = h * 0.55f;
        star(c, cx, cy, r, r * 0.62f, 18, t * 4, (th.accent2 & 0x00FFFFFF) | 0x40000000, 0);
        // pop bubble every 7 s
        float ph = t % 7f;
        if (ph < 1.8f) {
            int k = (int) (t / 7f) % BURSTS.length;
            float[][] spots = {{0.84f, 0.78f}, {0.9f, 0.34f}, {0.72f, 0.9f}};
            float[] s = spots[(int) (t / 7f) % spots.length];
            float sc = spring(Math.min(1f, ph / 0.35f)) * (ph > 1.5f ? Math.max(0, (1.8f - ph) / 0.3f) : 1f);
            if (sc > 0.01f) {
                c.save();
                c.translate(w * s[0], h * s[1]);
                c.rotate(-8);
                c.scale(sc, sc);
                star(c, 0, 0, 120 * d, 78 * d, 14, 0, 0xFFFFFFFF, 0xFF111111);
                q.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
                q.setTextAlign(Paint.Align.CENTER);
                q.setTextSize(44 * d);
                q.setStyle(Paint.Style.STROKE); q.setStrokeWidth(7 * d); q.setColor(0xFF111111);
                c.drawText(BURSTS[k], 0, 16 * d, q);
                q.setStyle(Paint.Style.FILL); q.setColor(th.accent);
                c.drawText(BURSTS[k], 0, 16 * d, q);
                c.restore();
            }
        }
    }

    private void star(Canvas c, float cx, float cy, float ro, float ri, int spikes, float rotDeg, int fill, int stroke) {
        path.reset();
        double step = Math.PI / spikes, a0 = Math.toRadians(rotDeg);
        for (int i = 0; i < spikes * 2; i++) {
            float r = (i % 2 == 0) ? ro : ri;
            double a = a0 + i * step;
            float x = cx + (float) (Math.cos(a) * r), y = cy + (float) (Math.sin(a) * r);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        p.setStyle(Paint.Style.FILL); p.setColor(fill);
        c.drawPath(path, p);
        if (stroke != 0) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6 * d); p.setColor(stroke); c.drawPath(path, p); p.setStyle(Paint.Style.FILL); }
    }

    // ---------------------------------------------------------------- hero

    private void hero(Canvas c, float t, int w, int h) {
        p.setShader(sky);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        // light rays from behind the logo, slowly turning
        float cx = w * 0.12f, cy = h * 0.1f, R = w * 1.6f;
        p.setColor((th.accent2 & 0x00FFFFFF) | 0x16000000);
        for (int i = 0; i < 16; i++) {
            double a = Math.toRadians(t * 3 + i * 22.5), b = a + Math.toRadians(7);
            path.reset();
            path.moveTo(cx, cy);
            path.lineTo(cx + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R);
            path.lineTo(cx + (float) Math.cos(b) * R, cy + (float) Math.sin(b) * R);
            path.close();
            c.drawPath(path, p);
        }
        // rising embers
        for (float[] e : embers) {
            float y = h + 100 - ((t * e[2] * d * 0.6f + e[1]) % (h + 200));
            float x = e[0] + (float) Math.sin(t * 0.8f + e[4]) * 18 * d;
            float life = 1f - Math.max(0, Math.min(1, y / h));
            p.setColor(((e[4] > 3.1f ? th.accent2 : th.accent) & 0x00FFFFFF) | ((int) (60 + 150 * (1 - life)) << 24));
            c.drawCircle(x, y, e[3] * d * 0.6f, p);
        }
        p.setShader(vignette);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    // ---------------------------------------------------------------- retro 8-bit

    private static final String[] CLOUD = {
            "....XXXX........",
            "..XXXXXXXX..XX..",
            ".XXXXXXXXXXXXXX.",
            "XXXXXXXXXXXXXXXX",
            "XXXXXXXXXXXXXXXX",
            ".XXXXXXXXXXXXXX.",
    };

    private void retro(Canvas c, float t, int w, int h) {
        // banded sky
        int bands = 7;
        for (int i = 0; i < bands; i++) {
            p.setColor(mixf(th.bg, th.bg2, i / (float) (bands - 1)));
            c.drawRect(0, h * i / (float) bands, w, h * (i + 1) / (float) bands + 1, p);
        }
        float px = 6 * d;
        // clouds (parallax)
        for (int i = 0; i < 5; i++) {
            float speed = (8 + i * 5) * d;
            float x = w - ((t * speed + i * 520 * d) % (w + 200 * d)) + 100 * d;
            float y = (60 + (i * 97) % 300) * d;
            pixels(c, CLOUD, x, y, px * (i % 2 == 0 ? 1.2f : 0.9f), 0xFFFFFFFF, 0xFFDDE8FF);
        }
        // twinkling pixel stars
        for (int i = 0; i < 12; i++) {
            if (((int) (t * 2) + i) % 5 != 0) continue;
            float x = (i * 311 * d) % w, y = (i * 53 * d) % (h * 0.4f);
            p.setColor(0xFFFFFFFF);
            c.drawRect(x, y - px, x + px, y + 2 * px, p);
            c.drawRect(x - px, y, x + 2 * px, y + px, p);
        }
        // pixel hills
        float base = h - 40 * d;
        hills(c, t * 10 * d, base, 120 * d, 0xFF2E8B57, px * 2);
        hills(c, t * 18 * d + 300 * d, base, 70 * d, 0xFF3CB043, px * 2);
        // brick ground
        float gh = 40 * d, bw = 32 * d, bh = gh / 2;
        p.setColor(0xFFB5651D);
        c.drawRect(0, h - gh, w, h, p);
        p.setColor(0xFF7A3E12);
        float off = groundStill ? 0 : (t * 30 * d) % bw;
        for (int row = 0; row < 2; row++) {
            float y = h - gh + row * bh;
            c.drawRect(0, y, w, y + 2 * d, p);
            for (float x = -off + (row % 2) * bw / 2; x < w; x += bw) c.drawRect(x, y, x + 2 * d, y + bh, p);
        }
    }

    private void hills(Canvas c, float scroll, float base, float height, int color, float step) {
        p.setColor(color);
        int w = getWidth();
        float period = 520 * d;
        for (float x = 0; x < w + step; x += step) {
            double ph = ((x + scroll) % period) / period * Math.PI * 2;
            float hh = (float) (Math.max(0, Math.sin(ph)) * height);
            hh = Math.round(hh / step) * step;   // stepped, pixel look
            c.drawRect(x, base - hh, x + step + 1, base, p);
        }
    }

    private void pixels(Canvas c, String[] art, float x, float y, float px, int col, int shade) {
        for (int r = 0; r < art.length; r++)
            for (int k = 0; k < art[r].length(); k++)
                if (art[r].charAt(k) == 'X') {
                    p.setColor(r >= art.length - 2 ? shade : col);
                    c.drawRect(x + k * px, y + r * px, x + (k + 1) * px + 0.5f, y + (r + 1) * px + 0.5f, p);
                }
    }

    // ---------------------------------------------------------------- neon synthwave

    private void neon(Canvas c, float t, int w, int h) {
        p.setShader(sky);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        float hor = h * 0.64f;
        // stars
        p.setColor(0xFFFFFFFF);
        for (int i = 0; i < 40; i++) {
            float a = (float) (0.5 + 0.5 * Math.sin(t * 1.3 + i * 1.7));
            p.setAlpha((int) (40 + a * 160));
            c.drawCircle((i * 487) % w, (i * 151) % (int) (hor * 0.8f), 1.4f * d, p);
        }
        p.setAlpha(255);
        // striped sun
        float sr = h * 0.2f, sx = w * 0.5f, sy = hor - sr * 0.35f;
        p.setShader(new LinearGradient(0, sy - sr, 0, sy + sr, 0xFFFFE066, th.accent, Shader.TileMode.CLAMP));
        c.save();
        c.clipRect(0, 0, w, hor);
        c.drawCircle(sx, sy, sr, p);
        p.setShader(null);
        p.setColor(th.bg2);
        for (int i = 0; i < 6; i++) {
            float y = sy + sr * (0.1f + i * 0.15f) + (t * 6 * d) % (sr * 0.15f);
            c.drawRect(sx - sr, y, sx + sr, y + (2 + i) * d, p);
        }
        c.restore();
        // floor
        p.setColor(th.bg);
        c.drawRect(0, hor, w, h, p);
        q.setStyle(Paint.Style.STROKE);
        float scroll = (t * 0.35f) % 1f;
        for (int pass = 0; pass < 2; pass++) {
            q.setStrokeWidth(pass == 0 ? 7 * d : 2 * d);
            q.setColor(pass == 0 ? (th.accent & 0x00FFFFFF) | 0x33000000 : (th.accent & 0x00FFFFFF) | 0xDD000000);
            for (int i = 0; i < 14; i++) {         // horizontal lines, perspective spacing
                float z = (i + scroll) / 14f;
                float y = hor + (h - hor) * z * z;
                c.drawLine(0, y, w, y, q);
            }
            for (int i = -14; i <= 14; i++) {       // lines to the vanishing point
                float xb = w / 2f + i * w / 9f;
                c.drawLine(w / 2f + i * w / 60f, hor, xb, h, q);
            }
        }
        q.setStyle(Paint.Style.FILL);
        q.setColor(th.accent2);
        c.drawRect(0, hor - d, w, hor + d, q);
    }

    // ---------------------------------------------------------------- helpers

    private static float spring(float x) {
        return (float) (1 - Math.exp(-6 * x) * Math.cos(9 * x));
    }

    private static int mix(int a, int b) { return mixf(a, b, 0.5f); }

    private static int mixf(int a, int b, float f) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * f) << 16) | ((int) (ag + (bg - ag) * f) << 8) | (int) (ab + (bb - ab) * f);
    }
}
