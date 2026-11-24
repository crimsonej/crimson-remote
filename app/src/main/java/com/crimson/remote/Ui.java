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
    // The live palette. applyTheme() fills these in from the saved appearance and accent.
    static int BG_TOP = 0xFF1A0B11, BG_BOTTOM = 0xFF0B0B10;
    static int SURFACE = 0xFF17171E, SURFACE2 = 0xFF20202A, STROKE = 0xFF2C2C38, RAISED = 0xFF22222C, TRACK = 0xFF3A3A48;
    static int TEXT = 0xFFF2F2F5, SUB = 0xFF9A9AAB, FAINT = 0xFF5E5E6E, RIPPLE = 0x30FFFFFF, SHEET = 0xFF15151C;
    static int CRIMSON = 0xFFE11D48, CRIMSON_DARK = 0xFF9F1239, CRIMSON_SOFT = 0x33E11D48;
    static final int GREEN = 0xFF22C55E, AMBER = 0xFFF59E0B, RED = 0xFFEF4444;
    static boolean dark = true;
    static boolean haptics = true;
    /** 0 = Glow (moving light behind frosted cards), 1 = Solid, 2 = OLED black. */
    static int style = 0;
    static int PARTNER = 0xFFF97316;
    static final String[] STYLE_NAMES = {"Glow", "Solid", "OLED"};
    static final String[] STYLE_BLURBS = {"Soft moving light behind frosted cards", "Calm, flat colours", "Pure black, easy on the battery"};
    static final int[] ACCENT_PARTNER = {0xFFF97316, 0xFF6366F1, 0xFF06B6D4, 0xFFEC4899, 0xFFEF4444, 0xFF94A3B8};

    /** Accent families: name, main, deep. Crimson is the brand default. */
    static final String[] ACCENT_NAMES = {"Crimson", "Ocean", "Emerald", "Violet", "Amber", "Mono"};
    static final int[] ACCENT_MAIN = {0xFFE11D48, 0xFF0EA5E9, 0xFF10B981, 0xFF8B5CF6, 0xFFF59E0B, 0xFF6B7280};
    static final int[] ACCENT_DEEP = {0xFF9F1239, 0xFF0369A1, 0xFF047857, 0xFF5B21B6, 0xFFB45309, 0xFF374151};

    /** mode: 0 = follow system, 1 = light, 2 = dark. */
    static void applyTheme(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("crimson", Context.MODE_PRIVATE);
        int mode = p.getInt("appearance", 0);
        int accent = Math.max(0, Math.min(ACCENT_MAIN.length - 1, p.getInt("accent", 0)));
        haptics = p.getBoolean("haptics", true);
        boolean systemDark = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        dark = mode == 2 || (mode == 0 && systemDark);
        style = Math.max(0, Math.min(2, p.getInt("style", 0)));
        PARTNER = ACCENT_PARTNER[accent];
        CRIMSON = ACCENT_MAIN[accent];
        CRIMSON_DARK = ACCENT_DEEP[accent];
        CRIMSON_SOFT = (CRIMSON & 0x00FFFFFF) | 0x33000000;
        if (dark) {
            BG_BOTTOM = 0xFF0B0B10; SURFACE = 0xFF17171E; SURFACE2 = 0xFF20202A; STROKE = 0xFF2C2C38;
            RAISED = 0xFF22222C; TRACK = 0xFF3A3A48; TEXT = 0xFFF2F2F5; SUB = 0xFF9A9AAB; FAINT = 0xFF5E5E6E;
            RIPPLE = 0x30FFFFFF; SHEET = 0xFF15151C;
            BG_TOP = blend(BG_BOTTOM, CRIMSON, 0.10f);
            if (style == 2) { BG_BOTTOM = 0xFF000000; BG_TOP = 0xFF000000; SURFACE = 0xFF0E0E12; SURFACE2 = 0xFF16161C; STROKE = 0xFF1E1E26; SHEET = 0xFF0A0A0D; }
            if (style == 0) { SURFACE = 0xC417171E; SURFACE2 = 0xB3262632; STROKE = 0x26FFFFFF; }
        } else {
            BG_BOTTOM = 0xFFF5F5F8; SURFACE = 0xFFFFFFFF; SURFACE2 = 0xFFF0F0F5; STROKE = 0xFFE3E3EA;
            RAISED = 0xFFFFFFFF; TRACK = 0xFFD9D9E2; TEXT = 0xFF14141A; SUB = 0xFF5C5C6C; FAINT = 0xFF9A9AAB;
            RIPPLE = 0x22000000; SHEET = 0xFFF7F7FA;
            BG_TOP = blend(BG_BOTTOM, CRIMSON, 0.12f);
            if (style == 2) { BG_BOTTOM = 0xFFFFFFFF; BG_TOP = 0xFFFFFFFF; SURFACE = 0xFFF6F6F8; SURFACE2 = 0xFFEEEEF2; }
            if (style == 0) { SURFACE = 0xD9FFFFFF; SURFACE2 = 0xBFF2F2F7; STROKE = 0x1A000000; }
        }
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static int dialogTheme() {
        return dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

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
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, mask);
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
        if (!haptics) return;
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
