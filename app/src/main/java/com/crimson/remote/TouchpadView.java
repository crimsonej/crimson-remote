package com.crimson.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * A trackpad surface. Swiping moves the TV's selection (or its pointer in
 * pointer mode) one step per short distance; a quick tap presses OK.
 */
public class TouchpadView extends View {
    public interface Listener {
        void onStep(String key);
        void onTap();
    }

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float step, slop, radius;
    private Listener listener;
    private float x, y, lastX, lastY, downX, downY, accX, accY, alpha, ripple;
    private long downAt;
    private boolean moved;
    private ValueAnimator fade, tapAnim;

    public TouchpadView(Context c) {
        super(c);
        step = Ui.dp(c, 34);
        slop = Ui.dp(c, 10);
        radius = Ui.dp(c, 70);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2));
        ring.setColor(Ui.CRIMSON);
        grid.setColor(0x14FFFFFF);
        setBackground(Ui.shape(Ui.SURFACE, Ui.dp(c, 28), Ui.STROKE, c));
    }

    public void setListener(Listener l) { listener = l; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                x = lastX = downX = e.getX();
                y = lastY = downY = e.getY();
                accX = accY = 0;
                moved = false;
                downAt = System.currentTimeMillis();
                if (fade != null) fade.cancel();
                alpha = 1f;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                x = e.getX(); y = e.getY();
                accX += x - lastX; accY += y - lastY;
                lastX = x; lastY = y;
                if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) moved = true;
                // Emit along the dominant axis so diagonal swipes feel clean.
                while (Math.abs(accX) >= step || Math.abs(accY) >= step) {
                    if (Math.abs(accX) >= Math.abs(accY)) {
                        emit(accX > 0 ? "KEY_RIGHT" : "KEY_LEFT");
                        accX -= Math.signum(accX) * step;
                        accY *= 0.5f;
                    } else {
                        emit(accY > 0 ? "KEY_DOWN" : "KEY_UP");
                        accY -= Math.signum(accY) * step;
                        accX *= 0.5f;
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved && System.currentTimeMillis() - downAt < 300) {
                    if (listener != null) listener.onTap();
                    playTap();
                }
                fadeOut();
                return true;
            case MotionEvent.ACTION_CANCEL:
                fadeOut();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void emit(String key) {
        if (listener != null) listener.onStep(key);
    }

    private void fadeOut() {
        fade = ValueAnimator.ofFloat(alpha, 0f);
        fade.setDuration(380);
        fade.addUpdateListener(a -> { alpha = (float) a.getAnimatedValue(); invalidate(); });
        fade.start();
    }

    private void playTap() {
        if (tapAnim != null) tapAnim.cancel();
        tapAnim = ValueAnimator.ofFloat(0f, 1f);
        tapAnim.setDuration(420);
        tapAnim.addUpdateListener(a -> { ripple = (float) a.getAnimatedValue(); invalidate(); });
        tapAnim.start();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        // Subtle dot grid so the surface reads as a pad.
        float gap = Ui.dp(getContext(), 22), r = Ui.dp(getContext(), 1.3f);
        for (float gx = gap; gx < getWidth(); gx += gap)
            for (float gy = gap; gy < getHeight(); gy += gap)
                c.drawCircle(gx, gy, r, grid);

        if (alpha > 0.01f) {
            glow.setShader(new RadialGradient(x, y, radius,
                    new int[]{(int) (alpha * 0x66) << 24 | 0xE11D48, 0x00E11D48},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, radius, glow);
        }
        if (ripple > 0f && ripple < 1f) {
            ring.setAlpha((int) ((1f - ripple) * 255));
            c.drawCircle(downX, downY, Ui.dp(getContext(), 12) + ripple * Ui.dp(getContext(), 46), ring);
        }
    }
}
