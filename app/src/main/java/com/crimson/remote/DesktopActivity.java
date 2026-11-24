package com.crimson.remote;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.crimson.remote.Ui.*;

/**
 * Crimson Desktop: while the phone is cast to the TV, the TV shows a desktop
 * (wallpaper, clock, apps, taskbar) and this phone becomes its trackpad.
 */
public class DesktopActivity extends Activity {
    private DesktopScreen screen;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.applyTheme(this);
        getWindow().setStatusBarColor(BG_BOTTOM);
        getWindow().setNavigationBarColor(BG_BOTTOM);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG_BOTTOM);
        root.setPadding(dp(this, 18), dp(this, 18), dp(this, 18), dp(this, 18));

        TextView brand = text(this, "CRIMSON DESKTOP", 12, CRIMSON, 700);
        brand.setLetterSpacing(0.25f);
        root.addView(brand);
        status = text(this, "Looking for the TV screen…", 14, SUB, 400);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(this, 6);
        root.addView(status, slp);

        Trackpad pad = new Trackpad(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, 0, 1);
        plp.topMargin = dp(this, 16);
        root.addView(pad, plp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"Apps", "Home", "Back", "Exit"};
        for (String l : labels) {
            TextView t = text(this, l, 14, TEXT, 500);
            t.setGravity(Gravity.CENTER);
            t.setBackground(ripple(shape(SURFACE2, dp(this, 24), STROKE, this)));
            press(t, () -> onBar(l), false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(this, 50), 1);
            lp.leftMargin = lp.rightMargin = dp(this, 4);
            bar.addView(t, lp);
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(this, 14);
        root.addView(bar, blp);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] ds = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (ds.length == 0) {
            status.setText("No TV screen yet. Start mirroring to the TV, then come back here.");
            return;
        }
        if (screen == null || !screen.isShowing()) {
            screen = new DesktopScreen(this, ds[0]);
            try {
                screen.show();
                status.setText("Showing on " + ds[0].getName() + ". Swipe to move, tap to click.");
            } catch (Exception e) {
                status.setText("The TV screen isn't available: " + e.getMessage());
                screen = null;
            }
        }
    }

    @Override protected void onDestroy() {
        if (screen != null) screen.dismiss();
        super.onDestroy();
    }

    private void onBar(String what) {
        buzz(this, 12);
        if (screen == null) return;
        switch (what) {
            case "Apps": screen.toggleApps(); break;
            case "Home": screen.home(); break;
            case "Back": screen.back(); break;
            case "Exit": finish(); break;
        }
    }

    /** The phone side: moves the desktop's cursor, taps click. */
    class Trackpad extends View {
        float lx, ly, sx, sy;
        long downAt;
        boolean moved;
        final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Trackpad(Context c) {
            super(c);
            setBackground(shape(SURFACE, dp(c, 28), STROKE, c));
            hint.setColor(FAINT);
            hint.setTextAlign(Paint.Align.CENTER);
            hint.setTextSize(dp(c, 14));
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawText("Trackpad: swipe to move · tap to click", getWidth() / 2f, getHeight() / 2f, hint);
            c.drawText("two-finger swipe to scroll", getWidth() / 2f, getHeight() / 2f + dp(getContext(), 22), hint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (screen == null) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lx = sx = e.getX(); ly = sy = e.getY();
                    downAt = System.currentTimeMillis();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - lx, dy = e.getY() - ly;
                    lx = e.getX(); ly = e.getY();
                    if (Math.abs(e.getX() - sx) > 12 || Math.abs(e.getY() - sy) > 12) moved = true;
                    float speed = 1.6f + Math.min(2.4f, (float) Math.hypot(dx, dy) / 18f); // acceleration
                    if (e.getPointerCount() >= 2) screen.scroll(dy * 2.2f);
                    else screen.moveCursor(dx * speed, dy * speed);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved && System.currentTimeMillis() - downAt < 280) { buzz(getContext(), 10); screen.click(); }
                    return true;
            }
            return true;
        }
    }

    /** The TV side. */
    static class DesktopScreen extends Presentation {
        private final Activity owner;
        private FrameLayout root;
        private View appsPanel;
        private android.widget.ScrollView appsScroll;
        private Cursor cursor;
        private TextView clock, date;
        private float cx, cy;
        private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());

        DesktopScreen(Activity owner, Display display) {
            super(owner, display);
            this.owner = owner;
        }

        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            Context c = getContext();
            root = new FrameLayout(c);
            GradientDrawable wall = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{blend(0xFF0B0B10, CRIMSON, 0.55f), 0xFF14070C, 0xFF050507});
            root.setBackground(wall);

            // Top bar: brand, TV name, clock
            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(dp(c, 24), dp(c, 12), dp(c, 24), dp(c, 12));
            top.setBackgroundColor(0x33000000);
            TextView brand = text(c, "CRIMSON", 13, Color.WHITE, 700);
            brand.setLetterSpacing(0.3f);
            top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
            date = text(c, "", 13, 0xCCFFFFFF, 500);
            top.addView(date);
            root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

            // Big clock in the middle
            LinearLayout center = new LinearLayout(c);
            center.setOrientation(LinearLayout.VERTICAL);
            center.setGravity(Gravity.CENTER_HORIZONTAL);
            clock = text(c, "", 96, Color.WHITE, 300);
            center.addView(clock);
            TextView hint = text(c, "Use your phone as the trackpad", 16, 0x99FFFFFF, 400);
            center.addView(hint);
            root.addView(center, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

            // Apps panel
            appsPanel = buildApps(c);
            appsPanel.setVisibility(View.GONE);
            FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-1, -1);
            ap.setMargins(dp(c, 80), dp(c, 70), dp(c, 80), dp(c, 90));
            root.addView(appsPanel, ap);

            // Taskbar
            root.addView(buildTaskbar(c), new FrameLayout.LayoutParams(-2, dp(c, 64), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

            cursor = new Cursor(c);
            root.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
            root.post(() -> { cx = root.getWidth() / 2f; cy = root.getHeight() / 2f; cursor.at(cx, cy); });
            tick();
        }

        private void tick() {
            Date now = new Date();
            if (clock != null) clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
            if (date != null) date.setText(new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now));
            h.postDelayed(this::tick, 10_000);
        }

        private View buildTaskbar(Context c) {
            LinearLayout bar = new LinearLayout(c);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xCC111117);
            g.setCornerRadius(dp(c, 20));
            g.setStroke(dp(c, 1), 0x33FFFFFF);
            bar.setBackground(g);
            FrameLayout.LayoutParams unused = null;

            TextView start = text(c, "C", 20, Color.WHITE, 700);
            start.setGravity(Gravity.CENTER);
            start.setBackground(gradient(CRIMSON, CRIMSON_DARK, dp(c, 14)));
            start.setOnClickListener(v -> toggleApps());
            bar.addView(start, new LinearLayout.LayoutParams(dp(c, 46), dp(c, 46)));

            for (ResolveInfo r : pinned(c)) {
                ImageView iv = new ImageView(c);
                iv.setImageDrawable(r.loadIcon(c.getPackageManager()));
                iv.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
                iv.setOnClickListener(v -> launch(r));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48));
                lp.leftMargin = dp(c, 6);
                bar.addView(iv, lp);
            }
            return bar;
        }

        private View buildApps(Context c) {
            LinearLayout panel = new LinearLayout(c);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(c, 24), dp(c, 20), dp(c, 24), dp(c, 20));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xE6121218);
            g.setCornerRadius(dp(c, 24));
            g.setStroke(dp(c, 1), 0x33FFFFFF);
            panel.setBackground(g);
            TextView t = text(c, "Apps", 22, Color.WHITE, 700);
            panel.addView(t);
            appsScroll = new android.widget.ScrollView(c);
            GridLayout grid = new GridLayout(c);
            grid.setColumnCount(8);
            for (ResolveInfo r : launchable(c)) {
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(c, 8), dp(c, 12), dp(c, 8), dp(c, 12));
                ImageView iv = new ImageView(c);
                iv.setImageDrawable(r.loadIcon(c.getPackageManager()));
                cell.addView(iv, new LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)));
                TextView name = text(c, String.valueOf(r.loadLabel(c.getPackageManager())), 12, 0xDDFFFFFF, 400);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER);
                cell.addView(name, new LinearLayout.LayoutParams(dp(c, 84), -2));
                cell.setOnClickListener(v -> { launch(r); toggleApps(); });
                grid.addView(cell);
            }
            appsScroll.addView(grid);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
            lp.topMargin = dp(c, 12);
            panel.addView(appsScroll, lp);
            return panel;
        }

        static List<ResolveInfo> launchable(Context c) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = new ArrayList<>(c.getPackageManager().queryIntentActivities(i, 0));
            PackageManager pm = c.getPackageManager();
            Collections.sort(list, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
            return list;
        }

        static List<ResolveInfo> pinned(Context c) {
            String[] wanted = {"chrome", "youtube", "whatsapp", "music", "gallery", "photos", "files", "spotify"};
            List<ResolveInfo> out = new ArrayList<>();
            for (ResolveInfo r : launchable(c)) {
                String pkg = r.activityInfo.packageName.toLowerCase(Locale.ROOT);
                for (String w : wanted) if (pkg.contains(w) && out.size() < 7) { out.add(r); break; }
            }
            return out;
        }

        /** Opens an app on the TV screen when the phone allows it, otherwise on the phone (which is mirrored). */
        void launch(ResolveInfo r) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            try {
                ActivityOptions o = ActivityOptions.makeBasic().setLaunchDisplayId(getDisplay().getDisplayId());
                owner.startActivity(i, o.toBundle());
            } catch (Exception e) {
                try { owner.startActivity(i); } catch (Exception ignored) {}
            }
        }

        void toggleApps() {
            boolean show = appsPanel.getVisibility() != View.VISIBLE;
            if (show) {
                appsPanel.setAlpha(0f);
                appsPanel.setTranslationY(dp(getContext(), 30));
                appsPanel.setVisibility(View.VISIBLE);
                appsPanel.animate().alpha(1).translationY(0).setDuration(220).start();
            } else {
                appsPanel.animate().alpha(0).translationY(dp(getContext(), 30)).setDuration(180)
                        .withEndAction(() -> appsPanel.setVisibility(View.GONE)).start();
            }
        }

        void home() { if (appsPanel.getVisibility() == View.VISIBLE) toggleApps(); }

        void back() { home(); }

        void moveCursor(float dx, float dy) {
            if (root == null) return;
            cx = Math.max(0, Math.min(root.getWidth() - 1, cx + dx));
            cy = Math.max(0, Math.min(root.getHeight() - 1, cy + dy));
            cursor.at(cx, cy);
        }

        void scroll(float dy) {
            if (appsPanel.getVisibility() == View.VISIBLE) appsScroll.scrollBy(0, (int) dy);
        }

        /** Clicks whatever clickable view is under the cursor. */
        void click() {
            cursor.pulse();
            View hit = find(root, (int) cx, (int) cy);
            if (hit != null) hit.performClick();
        }

        private View find(View v, int x, int y) {
            if (v == cursor || v.getVisibility() != View.VISIBLE) return null;
            Rect r = new Rect();
            if (!v.getGlobalVisibleRect(r) || !r.contains(x, y)) return null;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = g.getChildCount() - 1; i >= 0; i--) {
                    View f = find(g.getChildAt(i), x, y);
                    if (f != null) return f;
                }
            }
            return v.isClickable() ? v : null;
        }

        @Override public void onDetachedFromWindow() {
            h.removeCallbacksAndMessages(null);
            super.onDetachedFromWindow();
        }
    }

    /** The arrow pointer drawn on the TV. */
    static class Cursor extends View {
        float x, y, ring;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG), r = new Paint(Paint.ANTI_ALIAS_FLAG);

        Cursor(Context c) {
            super(c);
            fill.setColor(Color.WHITE);
            edge.setColor(0xFF000000);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(c, 1.5f));
            edge.setStrokeJoin(Paint.Join.ROUND);
            r.setColor(CRIMSON);
            r.setStyle(Paint.Style.STROKE);
            r.setStrokeWidth(dp(c, 2));
        }

        void at(float nx, float ny) { x = nx; y = ny; invalidate(); }

        void pulse() {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(350);
            a.addUpdateListener(v -> { ring = (float) v.getAnimatedValue(); invalidate(); });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float s = dp(getContext(), 1);
            Path p = new Path();
            p.moveTo(x, y);
            p.lineTo(x, y + 22 * s);
            p.lineTo(x + 6 * s, y + 17 * s);
            p.lineTo(x + 10 * s, y + 26 * s);
            p.lineTo(x + 14 * s, y + 24 * s);
            p.lineTo(x + 10 * s, y + 15 * s);
            p.lineTo(x + 17 * s, y + 15 * s);
            p.close();
            c.drawPath(p, fill);
            c.drawPath(p, edge);
            if (ring > 0 && ring < 1) {
                r.setAlpha((int) ((1 - ring) * 255));
                c.drawCircle(x, y, (6 + ring * 22) * s, r);
            }
        }
    }
}
