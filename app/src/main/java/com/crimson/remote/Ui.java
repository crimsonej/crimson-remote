package com.crimson.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/** Colours, shapes and the shared press behaviour for every control. */
final class Ui {
    static final int BG_TOP = 0xFF1A0B11, BG_BOTTOM = 0xFF0B0B10;
    static final int SURFACE = 0xFF17171E, SURFACE2 = 0xFF20202A, STROKE = 0xFF2C2C38;
    static final int TEXT = 0xFFF2F2F5, SUB = 0xFF9A9AAB, FAINT = 0xFF5E5E6E;
    static final int CRIMSON = 0xFFE11D48, CRIMSON_DARK = 0xFF9F1239, CRIMSON_SOFT = 0x33E11D48;
    static final int GREEN = 0xFF22C55E, AMBER = 0xFFF59E0B, RED = 0xFFEF4444;

    static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(int color, float radius, int stroke, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static GradientDrawable gradient(int a, int b, float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        g.setCornerRadius(radius);
        return g;
    }

    static RippleDrawable ripple(GradientDrawable content) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(content.getCornerRadius());
        return new RippleDrawable(ColorStateList.valueOf(0x30FFFFFF), content, mask);
    }

    static TextView text(Context c, String s, float sp, int color, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        String family = weight >= 700 ? "sans-serif-black" : weight >= 500 ? "sans-serif-medium" : "sans-serif";
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    static void buzz(Context c, int ms) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, 90));
            else v.vibrate(ms);
        } catch (Exception ignored) {}
    }

    /**
     * Springy press feedback for any control. With repeat=true, holding the
     * control keeps firing the action (volume, arrows).
     */
    static void press(final View v, final Runnable action, final boolean repeat) {
        v.setClickable(true);
        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            @Override public void run() {
                action.run();
                MAIN.postDelayed(loop[0], 130);
            }
        };
        v.setOnTouchListener(new View.OnTouchListener() {
            boolean inside;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        inside = true;
                        view.setPressed(true);
                        if (Build.VERSION.SDK_INT >= 21) view.getBackground().setHotspot(e.getX(), e.getY());
                        view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        if (repeat) {
                            action.run();
                            MAIN.postDelayed(loop[0], 420);
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        boolean now = e.getX() >= 0 && e.getY() >= 0
                                && e.getX() <= view.getWidth() && e.getY() <= view.getHeight();
                        if (!now && inside) {
                            inside = false;
                            view.setPressed(false);
                            MAIN.removeCallbacks(loop[0]);
                            release(view);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setPressed(false);
                        MAIN.removeCallbacks(loop[0]);
                        release(view);
                        if (!repeat && inside) action.run();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.setPressed(false);
                        MAIN.removeCallbacks(loop[0]);
                        release(view);
                        return true;
                }
                return false;
            }
        });
    }

    static void release(View v) {
        v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new OvershootInterpolator(3f)).start();
    }

    /** Gentle breathing animation for the status dot while connecting. */
    static ValueAnimator pulse(final View v) {
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0.25f);
        a.setDuration(700);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                v.setAlpha((float) va.getAnimatedValue());
            }
        });
        a.start();
        return a;
    }

    /** Fade a text change instead of snapping. */
    static void swapText(final TextView t, final CharSequence s) {
        if (s.toString().contentEquals(t.getText())) return;
        t.animate().alpha(0f).translationY(-6).setDuration(120).withEndAction(new Runnable() {
            @Override public void run() {
                t.setText(s);
                t.setTranslationY(6);
                t.animate().alpha(1f).translationY(0).setDuration(200)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        }).start();
    }
}
