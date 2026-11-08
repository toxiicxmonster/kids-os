package org.tabletos.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Set;

/**
 * Watching on the tablet: Movies & TV and Dance hand a title to the kid's own
 * Plex or YouTube Kids app and tell this receiver, which
 *
 *  - covers the screen ("Here it comes!") and swallows every tap until the
 *    title is really playing, so the kid can't wander into other profiles
 *    or shows while the player loads;
 *  - floats the home button on top, there for as long as the player is open.
 *
 * "Playing" is Android media sound that keeps going for a moment (the tap's
 * own pop is far too short). If nothing plays in time, the cover stays and
 * says to tap home. Tablet Home takes it all away when it's back on screen.
 *
 *   am broadcast -n org.tabletos.home/.PlayerCover --es package com.plexapp.android
 */
public class PlayerCover extends BroadcastReceiver {
    /** The player apps our apps hand titles to (also allowed to run in lock task). */
    static final Set<String> PLAYERS = Set.of("com.plexapp.android", "com.google.android.apps.youtube.kids");

    private static final long GRACE_MS = 2000;        // ignore sound right after the hand-off
    private static final long STEADY_SOUND_MS = 1500;  // this long without a break = playing
    private static final long GIVE_UP_MS = 90_000;     // Plex can take a while on the tablet
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private static View cover;
    private static TextView message;
    private static long shownAt;
    private static long soundSince;
    private static boolean gaveUp;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!PLAYERS.contains(intent.getStringExtra("package"))) {
            return;
        }
        Context app = context.getApplicationContext();
        ui.post(() -> show(app));
    }

    private static void show(Context context) {
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        hide(context);
        FrameLayout root = new FrameLayout(context);
        ImageView background = new ImageView(context);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String theme = context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE)
                .getString(HomeActivity.KEY_THEME, Themes.DEFAULT);
        background.setImageResource(Themes.drawable(context, theme));
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));

        message = new TextView(context);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        message.setTypeface(Typeface.DEFAULT_BOLD);
        message.setTextColor(Color.rgb(0x0d, 0x47, 0xa1));
        message.setGravity(Gravity.CENTER);
        int pad = HomeBubble.dp(context, 36);
        message.setPadding(pad, pad, pad, pad);
        GradientDrawable bubble = new GradientDrawable();
        bubble.setColor(Color.argb(235, 255, 255, 255));
        bubble.setCornerRadius(HomeBubble.dp(context, 40));
        message.setBackground(bubble);
        message.setText("Here it comes! 🎬");
        root.addView(message, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        // A gentle bounce, so it looks alive while the player loads.
        message.animate().scaleX(1.06f).scaleY(1.06f).setDuration(700).withEndAction(() ->
                message.animate().scaleX(1f).scaleY(1f).setDuration(700)
                        .setInterpolator(new OvershootInterpolator()).start()).start();

        root.setOnTouchListener((v, ev) -> true);  // every tap stops here
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setFitInsetsTypes(0);  // over the gesture strip at the bottom too
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try {
            context.getSystemService(WindowManager.class).addView(root, lp);
        } catch (RuntimeException e) {
            return;
        }
        cover = root;
        shownAt = SystemClock.uptimeMillis();
        soundSince = 0;
        gaveUp = false;
        // The home button goes on top of the cover (added after it), and stays after it lifts.
        HomeBubble.hide(context);
        HomeBubble.show(context, v -> v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90)
                .withEndAction(() -> HomeBubble.goHome(context)).start());
        ui.postDelayed(() -> watch(context), 500);
    }

    /** Lifts the cover once the title is playing (steady media sound). */
    private static void watch(Context context) {
        if (cover == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean sound = context.getSystemService(AudioManager.class).isMusicActive();
        if (sound && now - shownAt > GRACE_MS) {
            if (soundSince == 0) {
                soundSince = now;
            } else if (now - soundSince >= STEADY_SOUND_MS) {
                View lifting = cover;
                cover = null;
                lifting.animate().alpha(0f).setDuration(400).withEndAction(() -> remove(context, lifting)).start();
                return;
            }
        } else {
            soundSince = 0;
        }
        if (!gaveUp && now - shownAt > GIVE_UP_MS) {
            gaveUp = true;  // keeps covering (and keeps watching, in case it starts late)
            message.setText("Oops! It didn't start.\nTap 🏠 to go back.");
        }
        ui.postDelayed(() -> watch(context), 500);
    }

    /** Tablet Home is back on screen: cover and home button both go. */
    static void hide(Context context) {
        if (cover != null) {
            remove(context, cover);
            cover = null;
        }
        HomeBubble.hide(context);
    }

    private static void remove(Context context, View view) {
        try {
            context.getApplicationContext().getSystemService(WindowManager.class).removeView(view);
        } catch (RuntimeException ignored) {
            // already gone
        }
    }
}
