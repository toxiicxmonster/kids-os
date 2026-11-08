package org.tabletos.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Kid mode: a round home button floating over another app (a system overlay,
 * so it works over any app). Tapping it comes back to the carousel; Tablet
 * Home takes it away when it's back on screen. One at a time.
 */
final class HomeBubble {
    private static View bubble;

    private HomeBubble() {
    }

    static void show(Context context, View.OnClickListener onTap) {
        Context app = context.getApplicationContext();
        if (bubble != null || !Settings.canDrawOverlays(app)) {
            return;
        }
        TextView home = new TextView(app);
        home.setText("🏠");
        home.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        home.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(app, 4), Color.rgb(0x0d, 0x47, 0xa1));
        home.setBackground(circle);
        home.setAlpha(0.85f);
        home.setContentDescription("Home");
        home.setOnClickListener(onTap);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(app, 64), dp(app, 64),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // Left edge, halfway down: games tend to use the corners for their own buttons.
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lp.x = dp(app, 12);
        try {
            app.getSystemService(WindowManager.class).addView(home, lp);
        } catch (RuntimeException e) {
            return;  // no overlay permission after all
        }
        bubble = home;
    }

    static void hide(Context context) {
        if (bubble != null) {
            try {
                context.getApplicationContext().getSystemService(WindowManager.class).removeView(bubble);
            } catch (RuntimeException ignored) {
                // already gone
            }
            bubble = null;
        }
    }

    /** Back to the carousel, from anywhere. */
    static void goHome(Context context) {
        context.startActivity(new Intent(context.getApplicationContext(), HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
