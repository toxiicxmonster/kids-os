package org.tabletos.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Screen sleep: the host's parent-switch daemon (which sees every touch) sends
 * this after a while without one, unless Android is playing sound:
 *
 *   am broadcast -n org.tabletos.home/.ScreenReceiver
 *
 * The screen fades to black over everything. The next touch only takes the
 * black away, so a tap on a dark screen can't open an app. (Cage, the kiosk
 * compositor, can't switch the monitor itself off.) Shell/system only: the
 * manifest requires the DUMP permission.
 */
public class ScreenReceiver extends BroadcastReceiver {
    private static View dark;

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> show(app));
    }

    private static void show(Context context) {
        if (dark != null) {
            return;
        }
        WindowManager wm = context.getSystemService(WindowManager.class);
        View view = new View(context);
        view.setBackgroundColor(Color.BLACK);
        view.setAlpha(0f);
        view.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                hide(wm);
            }
            return true;  // the waking touch goes no further
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setFitInsetsTypes(0);  // over the gesture bar strip at the bottom too
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            return;  // no overlay permission
        }
        dark = view;
        view.animate().alpha(1f).setDuration(3000).start();
    }

    private static void hide(WindowManager wm) {
        if (dark != null) {
            try {
                wm.removeView(dark);
            } catch (RuntimeException ignored) {
                // already gone
            }
            dark = null;
        }
    }
}
