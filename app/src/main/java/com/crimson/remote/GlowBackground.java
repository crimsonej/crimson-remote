package com.crimson.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * The screen background. In the Glow style, three soft pools of accent-coloured
 * light drift slowly behind the frosted cards; other styles draw a plain fill.
 */
public class GlowBackground extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t;
    private ValueAnimator drift;

    public GlowBackground(Context c) {
        super(c);
        if (Ui.style == 0) {
            drift = ValueAnimator.ofFloat(0f, 1f);
            drift.setDuration(9000);
            drift.setRepeatMode(ValueAnimator.REVERSE);
            drift.setRepeatCount(ValueAnimator.INFINITE);
            drift.setInterpolator(new AccelerateDecelerateInterpolator());
            drift.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (drift != null) drift.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (drift != null) drift.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(Ui.BG_BOTTOM);
        if (Ui.style == 1) {
            p.setShader(new android.graphics.LinearGradient(0, 0, 0, h * 0.5f,
                    (Ui.CRIMSON & 0x00FFFFFF) | 0x1E000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            return;
        }
        if (Ui.style != 0) return;
        int a1 = Ui.dark ? 0x59 : 0x40, a2 = Ui.dark ? 0x38 : 0x2E, a3 = Ui.dark ? 0x40 : 0x30;
        blob(c, w * (0.18f - 0.12f * t), h * (0.10f - 0.06f * t), w * 0.75f, Ui.CRIMSON, a1);
        blob(c, w * (0.92f + 0.06f * t), h * (0.30f - 0.08f * t), w * 0.65f, Ui.PARTNER, a2);
        blob(c, w * (0.55f - 0.10f * t), h * (0.92f + 0.05f * t), w * 0.80f, Ui.CRIMSON_DARK, a3);
    }

    private void blob(Canvas c, float x, float y, float r, int color, int alpha) {
        int rgb = color & 0x00FFFFFF;
        p.setShader(new RadialGradient(x, y, r, new int[]{(alpha << 24) | rgb, rgb}, null, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
    }
}
