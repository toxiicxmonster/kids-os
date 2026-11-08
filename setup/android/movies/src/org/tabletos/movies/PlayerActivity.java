package org.tabletos.movies;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

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
 */
public class PlayerActivity extends Activity {
    private static final String TAG = "TabletPlayer";
    static final String EXTRA_STREAM = "stream";  // full URL of the bridge's HLS playlist
    static final String EXTRA_THEME = "theme";
    private static final int NAVY = Color.rgb(0x0d, 0x47, 0xa1);
    private static final long CONTROLS_MS = 3500;
    private static final long GIVE_UP_MS = 60_000;  // nothing on screen by then: say so and go back

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VideoView video;
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

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // A tap anywhere on the video shows the pause button for a moment.
        View tapCatcher = new View(this);
        tapCatcher.setOnClickListener(v -> showControls());
        root.addView(tapCatcher, fill());

        pauseButton = roundButton("⏸", 64, 160);
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

        video.setOnPreparedListener(mp -> {
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    liftCover();
                }
                return false;
            });
            video.start();
        });
        video.setOnCompletionListener(mp -> finish());
        video.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "playback error " + what + "/" + extra);
            failed();
            return true;
        });
        video.setVideoURI(Uri.parse(stream));
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
        wasPlaying = video.isPlaying();
        video.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (started && wasPlaying) {
            video.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        video.stopPlayback();
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
        pauseButton.setText(video.isPlaying() ? "⏸" : "▶");
        pauseButton.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideControls);
        if (video.isPlaying()) {
            ui.postDelayed(hideControls, CONTROLS_MS);
        }
    }

    private final Runnable hideControls = () -> pauseButton.setVisibility(View.GONE);

    private void togglePause() {
        if (video.isPlaying()) {
            video.pause();
        } else {
            video.start();
        }
        showControls();  // paused: the ▶ stays until it's tapped
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
