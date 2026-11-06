package dev.malaiesun.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/** Pixie's speech bubble, styled per theme (pixel box for Retro, inked balloon for Comic, soft card otherwise). */
final class Bubble extends Drawable {
    static final int TAIL_LEFT = 0, TAIL_BOTTOM = 1;

    private final Theme th;
    private final int tail;
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Bubble(Context c, Theme th, int tail) {
        this.th = th;
        this.tail = tail;
        d = c.getResources().getDisplayMetrics().density;
    }

    static int fill(Theme th) {
        switch (th.id) {
            case "retro": case "comic": return 0xFFFFFFFF;
            case "hero": return 0xF21A1F2B;
            case "neon": return 0xF2160A2B;
            default: return (th.surface & 0x00FFFFFF) | 0xF2000000;
        }
    }

    static int textColor(Theme th) { return Theme.on(fill(th) | 0xFF000000); }

    /** Extra room the tail takes on its side, in px. */
    static int tailSize(Context c) { return Math.round(12 * c.getResources().getDisplayMetrics().density); }

    /** A TextView dressed as a speech bubble. */
    static TextView text(Context c, Theme th, float sp, int tail) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(textColor(th));
        t.setTypeface(th.bold());
        t.setBackground(new Bubble(c, th, tail));
        float d = c.getResources().getDisplayMetrics().density;
        int ts = tailSize(c), h = Math.round(18 * d), v = Math.round(10 * d);
        if (tail == TAIL_LEFT) t.setPadding(h + ts, v, h + Math.round(4 * d), v + Math.round(4 * d));
        else t.setPadding(h, v, h, v + ts);
        t.setMaxLines(4);
        return t;
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float ts = 12 * d, sh = ("retro".equals(th.id) || "comic".equals(th.id)) ? 4 * d : 0;
        r.set(b.left, b.top, b.right - sh, b.bottom - sh);
        if (tail == TAIL_LEFT) r.left += ts; else r.bottom -= ts;
        float rad = "retro".equals(th.id) ? 0 : "comic".equals(th.id) ? 18 * d : 16 * d;
        path.reset();
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
        Path t = new Path();
        if (tail == TAIL_LEFT) {
            float cy = Math.min(r.top + 26 * d, r.centerY());
            t.moveTo(r.left + 2, cy - 8 * d); t.lineTo(b.left, cy + 2 * d); t.lineTo(r.left + 2, cy + 8 * d);
        } else {
            float cx = r.centerX();
            t.moveTo(cx - 9 * d, r.bottom - 2); t.lineTo(cx, b.bottom - sh); t.lineTo(cx + 9 * d, r.bottom - 2);
        }
        t.close();
        path.op(t, Path.Op.UNION);
        if (sh > 0) {                                   // hard offset shadow
            c.save();
            c.translate(sh, sh);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF000000);
            c.drawPath(path, p);
            c.restore();
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill(th));
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        switch (th.id) {
            case "retro": p.setStrokeWidth(3 * d); p.setColor(0xFF000000); c.drawPath(path, p); break;
            case "comic": p.setStrokeWidth(4 * d); p.setColor(0xFF111111); c.drawPath(path, p); break;
            case "neon": p.setStrokeWidth(2 * d); p.setColor(th.accent2); c.drawPath(path, p); break;
            case "hero": p.setStrokeWidth(2 * d); p.setColor(th.accent2); c.drawPath(path, p); break;
            default: break;
        }
        p.setStyle(Paint.Style.FILL);
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }

    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
