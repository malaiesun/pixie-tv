package dev.malaiesun.home;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A 16:9 card with a caption, styled by the theme (radius, outline, hard shadow, ring, fonts).
 * Works with real focus (Home screen) and with highlight() (overlay panels, which don't take focus).
 */
final class Tile extends LinearLayout {
    final FrameLayout card;
    final ImageView art;
    final TextView inner, caption;
    private final View dot;
    private final GradientDrawable ring, cardBg;
    private final Theme th;
    private final int w, h;

    Tile(Context c, Theme th, int widthPx) { this(c, th, widthPx, widthPx * 9 / 16); }

    Tile(Context c, Theme th, int widthPx, int heightPx) {
        super(c);
        this.th = th;
        w = widthPx;
        h = heightPx;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        float r = Ui.dp(c, th.radiusDp);
        int sh = Ui.dp(c, th.shadowDp);

        FrameLayout holder = new FrameLayout(c);
        holder.setClipChildren(false);
        if (sh > 0) {                       // hard offset shadow (comic / retro)
            View shadow = new View(c);
            GradientDrawable sd = new GradientDrawable();
            sd.setColor(th.shadow);
            sd.setCornerRadius(r);
            shadow.setBackground(sd);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(w, h);
            slp.setMargins(sh, sh, 0, 0);
            holder.addView(shadow, slp);
        }

        card = new FrameLayout(c);
        cardBg = new GradientDrawable();
        cardBg.setColor(th.surface);
        cardBg.setCornerRadius(r);
        if (th.borderDp > 0) cardBg.setStroke(Ui.dp(c, th.borderDp), th.border);
        card.setBackground(cardBg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));

        inner = new TextView(c);
        inner.setTextColor(Theme.on(th.surface | 0xFF000000));
        inner.setTextSize(18);
        inner.setTypeface(th.bold());
        inner.setGravity(Gravity.CENTER);
        inner.setMaxLines(2);
        inner.setEllipsize(TextUtils.TruncateAt.END);
        int p = Ui.dp(c, 10);
        inner.setPadding(p, p, p, p);
        card.addView(inner, new FrameLayout.LayoutParams(-1, -1));

        dot = new View(c);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(0xFF3DDC84);
        dd.setStroke(Ui.dp(c, 2), 0x66000000);
        dot.setBackground(dd);
        dot.setVisibility(GONE);
        int ds = Ui.dp(c, 12);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ds, ds, Gravity.TOP | Gravity.END);
        dlp.setMargins(0, Ui.dp(c, 10), Ui.dp(c, 10), 0);
        card.addView(dot, dlp);

        ring = new GradientDrawable();
        ring.setColor(0x00000000);
        ring.setCornerRadius(r);
        ring.setStroke(Ui.dp(c, th.ringDp), th.ring);

        holder.addView(card, new FrameLayout.LayoutParams(w, h));
        addView(holder, new LayoutParams(w + sh, h + sh));

        caption = new TextView(c);
        caption.setTextColor(th.textDim);
        caption.setTextSize(13);
        caption.setTypeface(th.typeface());
        caption.setSingleLine();
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setPadding(0, Ui.dp(c, 8), 0, 0);
        if (Theme.on(th.bg) == 0xFFFFFFFF) caption.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), 0xCC000000);  // readable over animated backgrounds
        addView(caption, new LayoutParams(w, -2));

        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setRevealOnFocusHint(false);   // HomeActivity scrolls rows itself
        setPivotX(w / 2f);
        setPivotY(h / 2f);
        setOnFocusChangeListener((v, has) -> highlight(has));
    }

    void highlight(boolean on) {
        animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).setDuration(120).start();
        card.setForeground(on ? ring : null);
        caption.setTextColor(on ? th.text : th.textDim);
        setTranslationZ(on ? Ui.dp(getContext(), 8) : 0);
    }

    /** Colored card (HA entity on, active action). */
    Tile active(boolean on) {
        cardBg.setColor(on ? th.accent : th.surface);
        inner.setTextColor(on ? Theme.on(th.accent) : Theme.on(th.surface | 0xFF000000));
        return this;
    }

    Tile banner(Drawable b) { art.setImageDrawable(b); inner.setText(null); return this; }

    Tile icon(Drawable i) {
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(getContext(), 22);
        art.setPadding(p, p, p, p);
        art.setImageDrawable(i);
        return this;
    }

    Tile text(String s) { inner.setText(th.cap(s)); return this; }

    Tile caption(String s) { caption.setText(s); return this; }

    Tile dot(boolean on) { dot.setVisibility(on ? VISIBLE : GONE); return this; }
}
