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

import java.net.HttpURLConnection;
import java.net.URL;

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
 * MediaPlayer call runs on its own thread; if one hangs, only that thread does.
 */
public class PlayerActivity extends Activity {
    private static final String TAG = "TabletPlayer";
    static final String EXTRA_STREAM = "stream";  // full URL of the bridge's HLS playlist
    static final String EXTRA_THEME = "theme";
    private static final int NAVY = Color.rgb(0x0d, 0x47, 0xa1);
    private static final long CONTROLS_MS = 3500;
    private static final long GIVE_UP_MS = 60_000;  // nothing on screen by then: say so and go back

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HandlerThread playerThread = new HandlerThread("player");
    private Handler player;    // every MediaPlayer call goes through here
    private MediaPlayer mp;    // player thread only
    private boolean prepared;  // player thread only
    private boolean playing;   // the UI thread's own idea of it (it never asks the player)
    private AudioManager audio;
    private AudioFocusRequest focus;
    private FrameLayout root;
    private SurfaceView video;
    private int videoW, videoH;
    private View cover;
    private TextView coverText;
    private TextView pauseButton;
    private boolean started;
    private boolean wasPlaying = true;
    private String stream;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        stream = getIntent().getStringExtra(EXTRA_STREAM);

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
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build();
        audio.requestAudioFocus(focus);
        playing = true;

        playerThread.start();
        player = new Handler(playerThread.getLooper());
        // The surface comes and goes (the moon, screen sleep); the player stays.
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Surface s = holder.getSurface();
                player.post(() -> {
                    if (mp != null) {
                        mp.setSurface(s);
                    }
                });
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                player.post(() -> {
                    if (mp != null) {
                        mp.setSurface(null);
                    }
                });
            }
        });
        Uri uri = Uri.parse(stream);
        player.post(() -> {
            try {
                mp = new MediaPlayer();  // made on the player thread, so its callbacks come here too
                mp.setAudioAttributes(attrs);
                mp.setOnInfoListener((p, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        ui.post(this::liftCover);
                    }
                    return false;
                });
                mp.setOnVideoSizeChangedListener((p, w, h) -> ui.post(() -> {
                    videoW = w;
                    videoH = h;
                    fitVideo();
                }));
                mp.setOnCompletionListener(p -> ui.post(this::finish));
                mp.setOnErrorListener((p, what, extra) -> {
                    Log.w(TAG, "playback error " + what + "/" + extra);
                    ui.post(this::failed);
                    return true;
                });
                mp.setOnPreparedListener(p -> {
                    prepared = true;
                    ui.post(() -> {
                        if (playing) {
                            player.post(this::playerStart);
                        }
                    });
                });
                Surface s = video.getHolder().getSurface();
                if (s != null && s.isValid()) {
                    mp.setSurface(s);
                }
                mp.setDataSource(this, uri);
                mp.prepareAsync();
            } catch (Exception e) {
                Log.w(TAG, "opening the stream", e);
                ui.post(this::failed);
            }
        });
        ui.postDelayed(() -> {
            if (!started) {
                failed();
            }
        }, GIVE_UP_MS);
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
        player.post(() -> {
            if (mp != null) {
                mp.release();
                mp = null;
            }
        });
        playerThread.quitSafely();
        // Tell the bridge, so the Plex server stops converting.
        String sid = Uri.parse(stream).getPathSegments().size() > 2 ? Uri.parse(stream).getPathSegments().get(2) : "";
        String stop = stream.substring(0, stream.indexOf("/hls/")) + "/hls-stop/" + sid;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(stop).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(3000);
                c.getResponseCode();
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
        box.addView(coverText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        box.setClickable(true);  // nothing underneath gets a tap while it loads
        return box;
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
        started = true;  // no more waiting
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
        coverText.setText("Oops! That didn't work.\nAsk a grown-up.");
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
        player.post(this::playerStart);
    }

    private void pausePlayer() {
        playing = false;
        player.post(() -> {
            if (mp != null && prepared && mp.isPlaying()) {
                mp.pause();
            }
        });
    }

    /** Player thread. */
    private void playerStart() {
        if (mp != null && prepared && !mp.isPlaying()) {
            mp.start();
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
