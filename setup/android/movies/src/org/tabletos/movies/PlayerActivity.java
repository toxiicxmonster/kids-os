package org.tabletos.movies;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.json.JSONObject;

/**
 * Watching on the tablet: a Plex title, streamed by the host's bridge from the
 * Plex server (as the kid's profile), always from the beginning.
 *
 * Made for a 3-year-old: a "Here it comes!" screen in their theme until the
 * picture is moving; then only the video, the big home button, and one big
 * pause/play button that shows up on a tap and hides again. No seek bar, no
 * menus. When it ends (or fails), it's back to the posters.
 *
 * Not a VideoView: that releases its MediaPlayer on the UI thread when the
 * picture goes away (home, the moon, screen sleep), and Waydroid's software
 * h264 decoder can hang while flushing, so reset() never returned and the
 * whole app froze until Android killed it ("not responding"). Here every
 * MediaPlayer call runs on its playback's own thread.
 *
 * A hung decoder also keeps any later video from showing, so the player
 * watches for it (a MediaPlayer call stuck for CALL_LIMIT_MS, no picture
 * NO_PICTURE_MS after the stream was ready, a video that never finished
 * closing) and then has the host fix Android (tablet-android-restart, via the
 * bridge): first only its decoder services, then the video starts over; if
 * that didn't help, "Oops! A system restart is needed" and a fresh Android.
 */
public class PlayerActivity extends Activity {
    private static final String TAG = "TabletPlayer";
    static final String EXTRA_STREAM = "stream";  // full URL of the bridge's HLS playlist
    static final String EXTRA_THEME = "theme";
    private static final int NAVY = Color.rgb(0x0d, 0x47, 0xa1);
    private static final long CONTROLS_MS = 3500;
    private static final long GIVE_UP_MS = 60_000;  // nothing on screen by then: say so and go back
    private static final long CALL_LIMIT_MS = 10_000;  // a MediaPlayer call this slow: the decoder is stuck
    private static final long NO_PICTURE_MS = 25_000;  // stream ready, still no picture: the same
    private static final long WATCH_EVERY_MS = 2_000;
    private static final long FIX_WAIT_MS = 45_000;  // for the host's answer
    private static final long RESTART_MESSAGE_MS = 5_000;  // "a system restart is needed" before it happens

    /** A video in this app never finished closing: its decoder is stuck (until fixed). */
    private static volatile boolean closeHung;

    /** One MediaPlayer on its own thread. A retry gets a new one: the old thread may be stuck for good. */
    private static final class Playback {
        final HandlerThread thread = new HandlerThread("player");
        final Handler handler;
        MediaPlayer mp;           // its thread only
        boolean prepared;         // its thread only
        volatile long busySince;  // when the MediaPlayer call now running began; 0 when none is

        Playback() {
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        /** A MediaPlayer call, on this playback's thread, timed for the watchdog. */
        void run(Runnable call) {
            handler.post(() -> {
                busySince = SystemClock.uptimeMillis();
                try {
                    call.run();
                } catch (RuntimeException e) {
                    Log.w(TAG, "player call", e);
                } finally {
                    busySince = 0;
                }
            });
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Playback pb;       // the current one (UI thread)
    private volatile Surface surface;
    private boolean playing;   // the UI thread's own idea of it (it never asks the player)
    private long preparedAt;   // when the current stream was ready to play (uptime ms), 0 before
    private boolean started;   // picture on screen (or given up)
    private boolean wasPlaying = true;
    private boolean mediaFixTried;
    private boolean fixing;    // a fix is under way (or we've given up): no more fault checks
    private AudioManager audio;
    private AudioFocusRequest focus;
    private AudioAttributes attrs;
    private FrameLayout root;
    private SurfaceView video;
    private int videoW, videoH;
    private View cover;
    private TextView coverText;
    private TextView pauseButton;
    private String stream;
    private String api;        // the bridge's /api
    private String appName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stream = getIntent().getStringExtra(EXTRA_STREAM);
        api = stream.substring(0, stream.indexOf("/hls/"));
        appName = getApplicationInfo().loadLabel(getPackageManager()).toString();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new SurfaceView(this);
        root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                fitVideo();
            }
        });

        // A tap anywhere on the video shows the pause button for a moment.
        View tapCatcher = new View(this);
        tapCatcher.setOnClickListener(v -> showControls());
        root.addView(tapCatcher, fill());

        pauseButton = roundButton("⏸️", 64, 160);
        pauseButton.setOnClickListener(v -> bounce(v, this::togglePause));
        pauseButton.setVisibility(View.GONE);
        root.addView(pauseButton, new FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER));

        cover = buildCover();
        root.addView(cover, fill());

        TextView home = roundButton("🏠", 44, 96);
        home.setContentDescription("Home");
        home.setOnClickListener(v -> bounce(v, this::finishAffinity));  // past the posters, to the carousel
        FrameLayout.LayoutParams homeLp = new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.TOP | Gravity.START);
        homeLp.setMargins(dp(24), dp(24), 0, 0);
        root.addView(home, homeLp);
        setContentView(root);
        hideSystemBars();

        audio = getSystemService(AudioManager.class);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build();
        audio.requestAudioFocus(focus);
        playing = true;

        // The surface comes and goes (the moon, screen sleep); the player stays.
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Surface s = holder.getSurface();
                surface = s;
                Playback p = pb;
                if (p != null) {
                    p.run(() -> {
                        if (p.mp != null) {
                            p.mp.setSurface(s);
                        }
                    });
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surface = null;
                Playback p = pb;
                if (p != null) {
                    p.run(() -> {
                        if (p.mp != null) {
                            p.mp.setSurface(null);
                        }
                    });
                }
            }
        });

        ui.postDelayed(watchdog, WATCH_EVERY_MS);
        if (closeHung) {
            fault("the last video never finished closing");
        } else {
            open();
        }
    }

    /** Starts the stream (again) in a new Playback. */
    private void open() {
        Playback p = new Playback();
        pb = p;
        started = false;
        preparedAt = 0;
        Uri uri = Uri.parse(stream);
        p.run(() -> {
            try {
                p.mp = new MediaPlayer();  // made on its thread, so its callbacks come there too
                p.mp.setAudioAttributes(attrs);
                p.mp.setOnInfoListener((m, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        ui.post(() -> {
                            if (pb == p) {
                                liftCover();
                            }
                        });
                    }
                    return false;
                });
                p.mp.setOnVideoSizeChangedListener((m, w, h) -> ui.post(() -> {
                    videoW = w;
                    videoH = h;
                    fitVideo();
                }));
                p.mp.setOnCompletionListener(m -> ui.post(() -> {
                    if (pb == p) {
                        finish();
                    }
                }));
                p.mp.setOnErrorListener((m, what, extra) -> {
                    Log.w(TAG, "playback error " + what + "/" + extra);
                    boolean decoder = what == MediaPlayer.MEDIA_ERROR_SERVER_DIED
                            || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT;
                    ui.post(() -> {
                        if (pb != p) {
                            return;
                        }
                        if (decoder) {
                            fault("player error " + what + "/" + extra);
                        } else {
                            failed();
                        }
                    });
                    return true;
                });
                p.mp.setOnPreparedListener(m -> {
                    p.prepared = true;
                    ui.post(() -> {
                        if (pb != p) {
                            return;
                        }
                        preparedAt = SystemClock.uptimeMillis();
                        if (playing) {
                            p.run(() -> startOn(p));
                        }
                    });
                });
                Surface s = surface;
                if (s != null && s.isValid()) {
                    p.mp.setSurface(s);
                }
                p.mp.setDataSource(this, uri);
                p.mp.prepareAsync();
            } catch (Exception e) {
                Log.w(TAG, "opening the stream", e);
                ui.post(() -> {
                    if (pb == p) {
                        failed();
                    }
                });
            }
        });
        ui.removeCallbacks(giveUp);
        ui.postDelayed(giveUp, GIVE_UP_MS);
    }

    /** Releases a Playback on its own thread; if that never finishes, the decoder is stuck. */
    private static void close(Playback p) {
        p.run(() -> {
            if (p.mp != null) {
                p.mp.release();
                p.mp = null;
            }
        });
        p.thread.quitSafely();
        new Thread(() -> {
            SystemClock.sleep(CALL_LIMIT_MS);
            if (p.thread.isAlive()) {
                Log.w(TAG, "a video never finished closing: Android's video decoder is stuck");
                closeHung = true;
            }
        }).start();
    }

    private final Runnable giveUp = () -> {
        if (!started && !fixing) {
            failed();
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            Playback p = pb;
            long now = SystemClock.uptimeMillis();
            if (!fixing && p != null) {
                long busy = p.busySince;
                if (busy != 0 && now - busy > CALL_LIMIT_MS) {
                    fault("a player call hung");
                } else if (!started && preparedAt != 0 && now - preparedAt > NO_PICTURE_MS) {
                    fault("no picture");
                }
            }
            ui.postDelayed(this, WATCH_EVERY_MS);
        }
    };

    /** Android's video decoder is stuck: fix it gently and start over, or else restart Android. */
    private void fault(String why) {
        if (fixing || isFinishing()) {
            return;
        }
        Log.w(TAG, "Android's video decoder is stuck (" + why + ")");
        fixing = true;
        ui.removeCallbacks(giveUp);
        Playback p = pb;
        pb = null;
        if (p != null) {
            close(p);
        }
        pauseButton.setVisibility(View.GONE);
        if (mediaFixTried) {
            restartAndroid(why);
            return;
        }
        mediaFixTried = true;
        showCover("Oops! Fixing " + appName + "… 🔧");
        askHost("media", why, result -> {
            if ("media-fixed".equals(result)) {
                Log.i(TAG, "decoder services restarted; starting over");
                closeHung = false;
                fixing = false;
                showCover("Here it comes! 🎬");
                open();
            } else {
                Log.w(TAG, "decoder fix: " + result);
                restartAndroid(why);
            }
        });
    }

    private void restartAndroid(String why) {
        fixing = true;
        showCover("Oops! A system restart is needed\nto fix " + appName + ". 🔧\nBe right back!");
        ui.postDelayed(() -> askHost("restart", why, result -> {
            Log.w(TAG, "Android restart: " + result);
            if (!"restarting".equals(result)) {
                giveUpNow();
                return;
            }
            // Restarting: Android (and this app) go away in a moment. If not, don't leave the kid waiting.
            ui.postDelayed(this::giveUpNow, 60_000);
        }), RESTART_MESSAGE_MS);
    }

    /** Asks the host (bridge → tablet-android-restart) for a fix; its answer, or null, on the UI thread. */
    private void askHost(String level, String why, Consumer<String> then) {
        new Thread(() -> {
            String result = null;
            try {
                String id = new JSONObject(http("POST", api + "/android-fix?level=" + level
                        + "&app=" + URLEncoder.encode(appName, StandardCharsets.UTF_8.name())
                        + "&why=" + URLEncoder.encode(why, StandardCharsets.UTF_8.name()))).getString("id");
                long end = SystemClock.uptimeMillis() + FIX_WAIT_MS;
                while (result == null && SystemClock.uptimeMillis() < end) {
                    SystemClock.sleep(1000);
                    JSONObject answer = new JSONObject(http("GET", api + "/android-fix/" + id));
                    result = answer.isNull("result") ? null : answer.getString("result");
                }
            } catch (Exception e) {
                Log.w(TAG, "asking for a fix", e);
            }
            String r = result;
            ui.post(() -> {
                if (!isDestroyed()) {
                    then.accept(r);
                }
            });
        }).start();
    }

    private static String http(String method, String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            // Not readAllBytes(): that's API 33, and this app runs from 30.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving (home, the sleeping moon, screen sleep): stop the sound.
        wasPlaying = playing;
        pausePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (started && wasPlaying) {
            startPlayer();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        audio.abandonAudioFocusRequest(focus);
        // Off the UI thread: this is the call that can hang (see the top).
        Playback p = pb;
        pb = null;
        if (p != null) {
            close(p);
        }
        // Tell the bridge, so the Plex server stops converting.
        String sid = Uri.parse(stream).getPathSegments().size() > 2 ? Uri.parse(stream).getPathSegments().get(2) : "";
        String stop = api + "/hls-stop/" + sid;
        new Thread(() -> {
            try {
                http("POST", stop);
            } catch (Exception e) {
                Log.w(TAG, "stopping the stream", e);
            }
        }).start();
    }

    private View buildCover() {
        FrameLayout box = new FrameLayout(this);
        ImageView background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int theme = getResources().getIdentifier("theme_" + getIntent().getStringExtra(EXTRA_THEME),
                "drawable", getPackageName());
        background.setImageResource(theme != 0 ? theme : R.drawable.sky);
        box.addView(background, fill());
        coverText = new TextView(this);
        coverText.setText("Here it comes! 🎬");
        coverText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        coverText.setTypeface(Typeface.DEFAULT_BOLD);
        coverText.setTextColor(NAVY);
        coverText.setGravity(Gravity.CENTER);
        coverText.setPadding(dp(36), dp(36), dp(36), dp(36));
        GradientDrawable bubble = new GradientDrawable();
        bubble.setColor(Color.argb(235, 255, 255, 255));
        bubble.setCornerRadius(dp(40));
        coverText.setBackground(bubble);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        textLp.setMargins(dp(24), 0, dp(24), 0);
        box.addView(coverText, textLp);
        box.setClickable(true);  // nothing underneath gets a tap while it loads
        return box;
    }

    private void showCover(String text) {
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
        coverText.setText(text);
    }

    private void liftCover() {
        if (started) {
            return;
        }
        started = true;
        cover.animate().alpha(0f).setDuration(400).withEndAction(() -> cover.setVisibility(View.GONE)).start();
    }

    private void failed() {
        if (isFinishing()) {
            return;
        }
        if (fixing) {  // a fix is on its way; it decides
            return;
        }
        giveUpNow();
    }

    private void giveUpNow() {
        fixing = true;  // no more fixing either
        started = true;  // no more waiting
        showCover("Oops! That didn't work.\nAsk a grown-up.");
        ui.postDelayed(this::finish, 6000);
    }

    private void showControls() {
        if (!started || cover.getVisibility() == View.VISIBLE) {
            return;
        }
        // Emoji forms, so both draw in colour (a plain ▶ comes out a small grey triangle).
        pauseButton.setText(playing ? "⏸️" : "▶️");
        pauseButton.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        if (playing) {
            ui.postDelayed(hideControls, CONTROLS_MS);
        }
    }

    private final Runnable hideControls = () -> pauseButton.setVisibility(View.GONE);

    private void togglePause() {
        if (playing) {
            pausePlayer();
        } else {
            startPlayer();
        }
        showControls();  // paused: the ▶ stays until it's tapped
    }

    private void startPlayer() {
        playing = true;
        Playback p = pb;
        if (p != null) {
            p.run(() -> startOn(p));
        }
    }

    private void pausePlayer() {
        playing = false;
        Playback p = pb;
        if (p != null) {
            p.run(() -> {
                if (p.mp != null && p.prepared && p.mp.isPlaying()) {
                    p.mp.pause();
                }
            });
        }
    }

    /** Its thread. */
    private static void startOn(Playback p) {
        if (p.mp != null && p.prepared && !p.mp.isPlaying()) {
            p.mp.start();
        }
    }

    /** The whole picture on screen, never stretched (black bars where it doesn't fill). */
    private void fitVideo() {
        int w = root.getWidth(), h = root.getHeight();
        if (videoW <= 0 || videoH <= 0 || w <= 0 || h <= 0) {
            return;
        }
        int fitW = w, fitH = (int) ((long) w * videoH / videoW);
        if (fitH > h) {
            fitH = h;
            fitW = (int) ((long) h * videoW / videoH);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) video.getLayoutParams();
        if (lp.width != fitW || lp.height != fitH) {
            lp.width = fitW;
            lp.height = fitH;
            video.setLayoutParams(lp);
        }
    }

    private TextView roundButton(String label, int sp, int sizeDp) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(5), NAVY);
        b.setBackground(circle);
        b.setElevation(dp(10));
        return b;
    }

    private static void bounce(View v, Runnable then) {
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new OvershootInterpolator(3f))
                        .withEndAction(then).start()).start();
    }

    private void hideSystemBars() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            insets.hide(WindowInsets.Type.systemBars());
        }
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
