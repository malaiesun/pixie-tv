package dev.malaiesun.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * Pixie standing next to what the TV is saying (panels, toasts, cards, popups). Idles, blinks,
 * talks (bobs, antenna flickers), waves, or listens (antenna pulses). Runs on the shared Pulse clock.
 */
final class BuddyView extends View implements Pulse.Listener {
    static final int IDLE = 0, TALK = 1, WAVE = 2, LISTEN = 3;

    private final Sprites.Set set;
    private final float px, size;
    private final Paint bp = new Paint(), p = new Paint();
    private final RectF dst = new RectF();
    private int pose = IDLE;
    private long poseUntil, nextBlink, blinkUntil;

    BuddyView(Context c, Theme th, float pixelDp) {
        super(c);
        set = Sprites.forTheme(th.id);
        px = pixelDp * c.getResources().getDisplayMetrics().density;
        size = 18 * px;
        bp.setFilterBitmap(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Change pose; ms > 0 falls back to idle afterwards. */
    BuddyView pose(int p, long ms) {
        pose = p;
        poseUntil = ms > 0 ? SystemClock.uptimeMillis() + ms : 0;
        invalidate();
        return this;
    }

    /** Pop in with a little bounce. */
    void popIn() {
        setScaleX(0.2f);
        setScaleY(0.2f);
        setPivotX(size / 2);
        setPivotY(size);
        animate().scaleX(1).scaleY(1).setInterpolator(new OvershootInterpolator(2.5f)).setDuration(320).start();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(size), Math.round(size + px));   // headroom for the bob
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Pulse.add(this);
        MascotView.overlayShown(this);          // one Pixie on screen: Home's steps aside
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Pulse.remove(this);
        MascotView.overlayHidden(this);
    }

    @Override public void onPulse(long now) {
        if (!isAttachedToWindow()) { Pulse.remove(this); return; }
        if (poseUntil > 0 && now > poseUntil) { pose = IDLE; poseUntil = 0; }
        if (now > nextBlink) { blinkUntil = now + 150; nextBlink = now + 2000 + (now % 2500); }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        Bitmap f;
        float bob = 0;
        boolean antOff;
        switch (pose) {
            case TALK:
                f = (now / 400) % 4 == 3 ? set.blink : set.idle;
                bob = (now / 140) % 2 == 0 ? 0 : -px;
                antOff = (now / 140) % 2 == 0;
                break;
            case WAVE:
                f = (now / 220) % 2 == 0 ? set.wave1 : set.wave2;
                bob = (now / 220) % 2 == 0 ? 0 : -px * 0.5f;
                antOff = false;
                break;
            case LISTEN:
                f = now < blinkUntil ? set.blink : set.idle;
                antOff = (now / 250) % 2 == 0;
                break;
            default:
                f = now < blinkUntil ? set.blink : set.idle;
                bob = (now / 700) % 2 == 0 ? 0 : -px * 0.5f;
                antOff = (now / 600) % 3 == 2;
                break;
        }
        float top = getHeight() - size + bob;
        dst.set(0, top, size, top + size);
        c.drawBitmap(f, null, dst, bp);
        if (antOff) {
            p.setColor(Theme.shade(set.outfit.antenna, -0.55f));
            c.drawRect(Sprites.ANT_X * px, top + Sprites.ANT_Y * px, (Sprites.ANT_X + Sprites.ANT_W) * px, top + (Sprites.ANT_Y + 1) * px, p);
        }
    }
}
