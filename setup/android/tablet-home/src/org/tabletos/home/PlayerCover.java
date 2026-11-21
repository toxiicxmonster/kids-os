package org.tabletos.home;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * YouTube Kids: once the song plays, the picture shows through but the cover
 * stays as a clear shield that still swallows every tap (YouTube Kids' own
 * screen has other videos, search and suggestions one tap away). The home
 * button floats above the shield and keeps working; a swallowed tap makes it
 * bounce. When the song is over (it has played about its length and then the
 * sound stops, or a hard limit so YouTube Kids' autoplay can't run on), the
 * shield goes and the app that asked (--es back, i.e. Dance) comes back.
 *
 * The players may only run in the kiosk's lock task while a hand-off is on:
 * this receiver adds them to the allowlist (before the app that asked opens
 * one: it waits for this ordered broadcast) and hide() takes them off again,
 * which makes Android close whatever is left of them. Otherwise a paused
 * YouTube Kids could come back on its own later (e.g. when Dance finished),
 * without the shield or the home button.
 *
 *   am broadcast -n org.tabletos.home/.PlayerCover --es package com.plexapp.android
 *   am broadcast -n org.tabletos.home/.PlayerCover --es package com.google.android.apps.youtube.kids \
 *       --ei seconds 185 --es back org.tabletos.dance
 */
public class PlayerCover extends BroadcastReceiver {
    static final String YOUTUBE_KIDS = "com.google.android.apps.youtube.kids";
    /** The player apps our apps hand titles to (also allowed to run in lock task). */
    static final Set<String> PLAYERS = Set.of("com.plexapp.android", YOUTUBE_KIDS);

    private static final long GRACE_MS = 2000;        // ignore sound right after the hand-off
    private static final long STEADY_SOUND_MS = 1500;  // this long without a break = playing
    private static final long GIVE_UP_MS = 90_000;     // Plex can take a while on the tablet
    // The YouTube Kids shield: when is the song over?
    private static final long END_SILENCE_MS = 2500;      // sound stopped this long, once it has nearly played
    private static final long STUCK_SILENCE_MS = 60_000;  // no sound this long under the shield: give up
    private static final long UNKNOWN_LENGTH_MS = 10 * 60_000;
    private static final long ADS_ROOM_MS = 60_000;       // ads (their own sound) on top of the song
    private static final long TICK_MS = 500;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private static View cover;
    private static View background;
    private static TextView message;
    private static long shownAt;
    private static long soundSince;
    private static boolean gaveUp;
    // The shield (YouTube Kids only).
    private static boolean shield;
    private static boolean shielding;
    private static long lengthMs;
    private static long playedMs;
    private static long silentSince;
    private static String back;
    // Which cover the watch loop belongs to: a new cover (or none) ends the old loop,
    // so two loops never count the same song twice.
    private static int generation;

    private static void tick(Context context) {
        int mine = generation;
        ui.postDelayed(() -> {
            if (mine == generation) {
                watch(context);
            }
        }, TICK_MS);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String pkg = intent.getStringExtra("package");
        if (!PLAYERS.contains(pkg)) {
            return;
        }
        Context app = context.getApplicationContext();
        allowPlayers(app, true);  // before the player opens (the sender waits for us)
        boolean shieldIt = YOUTUBE_KIDS.equals(pkg);
        long length = Math.max(0, intent.getIntExtra("seconds", 0)) * 1000L;
        String backTo = intent.getStringExtra("back");
        ui.post(() -> {
            show(app);
            shield = shieldIt;
            lengthMs = length;
            back = backTo;
        });
    }

    private static void show(Context context) {
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        clear(context);
        FrameLayout root = new FrameLayout(context);
        ImageView picture = new ImageView(context);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String theme = context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE)
                .getString(HomeActivity.KEY_THEME, Themes.DEFAULT);
        picture.setImageResource(Themes.drawable(context, theme));
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        background = picture;

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

        root.setOnTouchListener((v, ev) -> {  // every tap stops here (the home button is above it)
            if (shielding && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                HomeBubble.nudge();  // "this is the way out"
            }
            return true;
        });
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
        shield = false;
        shielding = false;
        playedMs = 0;
        silentSince = 0;
        // The home button goes on top of the cover (added after it), and stays after it lifts.
        HomeBubble.hide(context);
        HomeBubble.show(context, v -> v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90)
                .withEndAction(() -> HomeBubble.goHome(context)).start());
        tick(context);
    }

    /** Lifts the cover (or, for YouTube Kids, clears it to a shield) once the title is playing. */
    private static void watch(Context context) {
        if (cover == null) {
            return;
        }
        if (shielding) {
            guard(context);
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean sound = context.getSystemService(AudioManager.class).isMusicActive();
        if (sound && now - shownAt > GRACE_MS) {
            if (soundSince == 0) {
                soundSince = now;
            } else if (now - soundSince >= STEADY_SOUND_MS) {
                if (shield) {
                    // The picture shows through; the (now clear) cover keeps every tap.
                    shielding = true;
                    playedMs = now - soundSince;
                    silentSince = 0;
                    background.animate().alpha(0f).setDuration(400).start();
                    message.animate().alpha(0f).setDuration(400).start();
                    tick(context);
                    return;
                }
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
        tick(context);
    }

    /**
     * The song is playing under the shield: is it over? Counted in media
     * sound, so ads before it (also sound) only make it end on the silence a
     * little sooner, and a hard limit stops YouTube Kids' autoplay going on.
     */
    private static void guard(Context context) {
        long now = SystemClock.uptimeMillis();
        boolean sound = context.getSystemService(AudioManager.class).isMusicActive();
        if (sound) {
            playedMs += TICK_MS;
            silentSince = 0;
        } else if (silentSince == 0) {
            silentSince = now;
        }
        long silent = silentSince == 0 ? 0 : now - silentSince;
        boolean nearlyPlayed = lengthMs > 0
                ? playedMs >= Math.max(lengthMs - 8000, lengthMs * 3 / 4)
                : playedMs >= 60_000;
        long endSilence = lengthMs > 0 ? END_SILENCE_MS : 2 * END_SILENCE_MS;
        long limit = lengthMs > 0 ? lengthMs + ADS_ROOM_MS : UNKNOWN_LENGTH_MS;
        if ((nearlyPlayed && silent >= endSilence) || playedMs >= limit || silent >= STUCK_SILENCE_MS) {
            finished(context);
            return;
        }
        tick(context);
    }

    /** The song is over: pause YouTube Kids and go back to the app that asked (or home). */
    private static void finished(Context context) {
        shielding = false;
        AudioManager audio = context.getSystemService(AudioManager.class);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        Intent again = back == null ? null : context.getPackageManager().getLaunchIntentForPackage(back);
        try {
            if (again != null) {
                // Started while our overlay is still up (so allowed from the background).
                context.startActivity(again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                HomeBubble.goHome(context);
            }
        } catch (RuntimeException e) {
            HomeBubble.goHome(context);
        }
        // A moment later, so YouTube Kids is never left uncovered on screen.
        ui.postDelayed(() -> hide(context), 700);
    }

    /**
     * Tablet Home is back on screen (or the song is over): cover and home
     * button both go, and so do the players (off the lock-task allowlist).
     */
    static void hide(Context context) {
        clear(context);
        allowPlayers(context, false);
    }

    /** Lets the players run in lock task (on), or takes them off the allowlist (off). */
    static void allowPlayers(Context context, boolean on) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(context, AdminReceiver.class);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }
        try {
            List<String> allowed = new ArrayList<>(Arrays.asList(dpm.getLockTaskPackages(admin)));
            // (A player the parent put in the carousel stays allowed.)
            Set<String> approved = context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE)
                    .getStringSet(HomeActivity.KEY_APPROVED, Set.of());
            boolean changed = false;
            for (String player : PLAYERS) {
                if (on && !allowed.contains(player)) {
                    changed |= allowed.add(player);
                } else if (!on && !approved.contains(player)) {
                    changed |= allowed.remove(player);
                }
            }
            if (changed) {
                dpm.setLockTaskPackages(admin, allowed.toArray(new String[0]));
            }
        } catch (RuntimeException e) {
            android.util.Log.w("PlayerCover", "lock task allowlist", e);
        }
    }

    private static void clear(Context context) {
        generation++;  // ends the old cover's watch loop
        shielding = false;
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
