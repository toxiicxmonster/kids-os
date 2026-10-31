package org.tabletos.dance;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dance: Danny Go! songs on the TV.
 *
 *  - kid: a grid of the songs the parents picked (the web page's Dance tab).
 *    "One song": a tap plays it. "Dance party": taps line songs up (numbered),
 *    then the big "Let's dance!" button plays them one after another.
 *  - parent mode: just says where the songs are chosen.
 *
 * The host's bridge (192.168.240.1:8766) starts YouTube on the TV and follows
 * each song to its end; picks share Movies & TV's pause and 90 s lockout.
 */
public class DanceActivity extends Activity {
    private static final String TAG = "TabletDance";
    private static final String BRIDGE = "http://192.168.240.1:8766/api";
    private static final String MODE_PROP = "persist.waydroid.tablet_mode";
    private static final int NAVY = Color.rgb(0x0d, 0x47, 0xa1);
    private static final int PINK = Color.rgb(0xff, 0x5c, 0xa8);
    private static final int GREEN = Color.rgb(0x2e, 0xb8, 0x5c);

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView message;
    private View grid;
    private TextView oneButton;
    private TextView partyButton;
    private TextView goButton;
    private boolean partyMode;
    private boolean playing;
    private int partyMax = 6;
    private final List<String> party = new ArrayList<>();
    private final List<TextView> badges = new ArrayList<>();
    private final List<String> badgeIds = new ArrayList<>();

    private SoundPool sounds;
    private int popSound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Media usage, so the pop follows the kid volume the parent set.
        sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build()).build();
        popSound = sounds.load(this, R.raw.pop, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showSystemBars(readParentMode());
        if (readParentMode()) {
            showParent();
        } else {
            showKid();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /** A toddler needs to see and hear that a tap worked: squash-and-bounce plus a pop. */
    private void tapFeedback(View v, Runnable then) {
        sounds.play(popSound, 1f, 1f, 1, 0, 1f);
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new OvershootInterpolator(3f))
                        .withEndAction(then).start()).start();
    }

    // ---- bridge calls -----------------------------------------------------------

    private interface Done<T> {
        void on(T result);
    }

    private static byte[] request(String method, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BRIDGE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(120000);  // opening YouTube on the TV and getting past its profile screen
        int code = c.getResponseCode();
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; in != null && (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            if (code >= 400) {
                throw new IOException("HTTP " + code + ": " + out);
            }
            return out.toByteArray();
        }
    }

    private void call(String method, String path, Done<JSONObject> done) {
        io.execute(() -> {
            JSONObject result = null;
            try {
                result = new JSONObject(new String(request(method, path), "UTF-8"));
            } catch (Exception e) {
                Log.w(TAG, method + " " + path + " failed", e);
            }
            JSONObject r = result;
            ui.post(() -> done.on(r));
        });
    }

    private void loadThumb(String id, ImageView into) {
        io.execute(() -> {
            try {
                byte[] data = request("GET", "/dance-thumb/" + id);
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                ui.post(() -> into.setImageBitmap(bmp));
            } catch (Exception e) {
                Log.w(TAG, "thumbnail " + id, e);
            }
        });
    }

    // ---- kid screen -----------------------------------------------------------------

    private void showKid() {
        playing = false;
        partyMode = false;
        party.clear();
        root = new FrameLayout(this);
        ImageView background = new ImageView(this);
        background.setImageResource(R.drawable.sky);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(background, fill());
        message = text("", 40, NAVY);
        message.setGravity(Gravity.CENTER);
        message.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(message, fill());
        setContentView(root);

        call("GET", "/dance", result -> {
            if (result == null) {
                message.setText("Dance isn't ready yet");
                root.addView(homeButton());
                return;
            }
            int theme = getResources().getIdentifier("theme_" + result.optString("theme", "sky"),
                    "drawable", getPackageName());
            if (theme != 0) {
                background.setImageResource(theme);
            }
            partyMax = result.optInt("party_max", 6);
            JSONArray songs = result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) {
                message.setText("Ask a grown-up to pick some songs");
                root.addView(homeButton());
                return;
            }
            message.setText("");
            grid = buildGrid(songs);
            root.addView(grid, 1, fill());
            root.addView(modeButtons());
            root.addView(goButton());
            root.addView(homeButton());
            int wait = result.optInt("cooldown");
            if (result.optBoolean("paused")) {
                grid.setAlpha(0.35f);
                flash("Dance time is taking a break 💤", 0, false);
            } else if (wait > 0) {
                grid.setAlpha(0.35f);
                flash("The TV is busy 📺\nTry again in a minute!", wait * 1000, false);
                ui.postDelayed(() -> grid.setAlpha(1f), wait * 1000L);
            }
        });
    }

    /** Songs 3 across (bigger pictures for small fingers), scrolling up and down. */
    private View buildGrid(JSONArray songs) {
        final int columns = 3;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int side = dp(32);
        int gap = dp(24);
        int tileW = (screenW - 2 * side - (columns - 1) * gap) / columns;
        int thumbW = tileW - dp(20);
        int thumbH = thumbW * 9 / 16;

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // Clear of the home and mode buttons at the top and "Let's dance!" at the bottom.
        list.setPadding(side, dp(136), side, dp(150));
        scroller.addView(list);

        badges.clear();
        badgeIds.clear();
        LinearLayout row = null;
        for (int i = 0; i < songs.length(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = gap;
                list.addView(row, rowLp);
            }
            JSONObject song = songs.optJSONObject(i);
            String id = song.optString("id");

            FrameLayout tile = new FrameLayout(this);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(28));
            card.setBackground(bg);
            card.setPadding(dp(10), dp(10), dp(10), dp(14));
            card.setElevation(dp(8));

            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setClipToOutline(true);
            GradientDrawable round = new GradientDrawable();
            round.setCornerRadius(dp(20));
            round.setColor(Color.rgb(0xff, 0xe0, 0xf0));
            thumb.setBackground(round);
            card.addView(thumb, new LinearLayout.LayoutParams(thumbW, thumbH));
            loadThumb(id, thumb);

            TextView title = text(song.optString("title"), 22, Color.rgb(0x33, 0x33, 0x33));
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setPadding(0, dp(8), 0, 0);
            card.addView(title, new LinearLayout.LayoutParams(thumbW, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.addView(card);

            // Dance party: this song's place in the line-up.
            TextView badge = text("", 34, Color.WHITE);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setGravity(Gravity.CENTER);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(PINK);
            dot.setStroke(dp(4), Color.WHITE);
            badge.setBackground(dot);
            badge.setElevation(dp(12));
            badge.setVisibility(View.GONE);
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP | Gravity.END);
            tile.addView(badge, badgeLp);
            badges.add(badge);
            badgeIds.add(id);

            card.setOnClickListener(v -> {
                if (playing) {
                    return;
                }
                if (partyMode) {
                    tapFeedback(v, () -> toggleParty(id));
                } else {
                    playing = true;  // one pick per visit; further taps are ignored
                    tapFeedback(v, () -> play(id));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = i % columns == 0 ? 0 : gap;
            row.addView(tile, lp);
        }
        return scroller;
    }

    /** "One song" / "Dance party", top middle. */
    private View modeButtons() {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        oneButton = pill("💃 One song");
        partyButton = pill("🎉 Dance party");
        oneButton.setOnClickListener(v -> tapFeedback(v, () -> setPartyMode(false)));
        partyButton.setOnClickListener(v -> tapFeedback(v, () -> setPartyMode(true)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(260), dp(84));
        lp.setMargins(dp(10), 0, dp(10), 0);
        box.addView(oneButton, lp);
        box.addView(partyButton, new LinearLayout.LayoutParams(lp));
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        boxLp.topMargin = dp(30);
        box.setLayoutParams(boxLp);
        setPartyMode(false);
        return box;
    }

    private TextView pill(String label) {
        TextView t = text(label, 28, NAVY);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setElevation(dp(8));
        return t;
    }

    private void setPartyMode(boolean on) {
        partyMode = on;
        party.clear();
        styleMode(oneButton, !on);
        styleMode(partyButton, on);
        refreshParty();
    }

    private void styleMode(TextView button, boolean selected) {
        if (button == null) {
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(42));
        bg.setColor(selected ? PINK : Color.WHITE);
        bg.setStroke(dp(4), selected ? Color.WHITE : NAVY);
        button.setBackground(bg);
        button.setTextColor(selected ? Color.WHITE : NAVY);
    }

    private void toggleParty(String id) {
        if (party.contains(id)) {
            party.remove(id);
        } else if (party.size() < partyMax) {
            party.add(id);
        } else {
            flash("That's a big party! 🎉\nTap \"Let's dance!\"", 2500, false);
        }
        refreshParty();
    }

    /** Numbers on the picked songs, and "Let's dance!" once there's at least one. */
    private void refreshParty() {
        for (int i = 0; i < badges.size(); i++) {
            int place = party.indexOf(badgeIds.get(i));
            badges.get(i).setVisibility(place < 0 ? View.GONE : View.VISIBLE);
            badges.get(i).setText(String.valueOf(place + 1));
        }
        if (goButton != null) {
            goButton.setVisibility(partyMode && !party.isEmpty() ? View.VISIBLE : View.GONE);
            goButton.setText("▶  Let's dance!  (" + party.size() + ")");
        }
    }

    private View goButton() {
        goButton = text("", 34, Color.WHITE);
        goButton.setTypeface(Typeface.DEFAULT_BOLD);
        goButton.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(50));
        bg.setColor(GREEN);
        bg.setStroke(dp(5), Color.WHITE);
        goButton.setBackground(bg);
        goButton.setElevation(dp(14));
        goButton.setOnClickListener(v -> {
            if (playing || party.isEmpty()) {
                return;
            }
            playing = true;
            tapFeedback(v, () -> play(String.join(",", party)));
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(460), dp(100),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(28);
        goButton.setLayoutParams(lp);
        goButton.setVisibility(View.GONE);
        return goButton;
    }

    private void play(String ids) {
        flash("Starting it on the TV… 💃", 0, true);
        call("POST", "/dance-play/" + ids, result -> {
            String status = result == null ? "error" : result.optString("result");
            if (!"playing".equals(status)) {
                playing = false;  // didn't start: the kid may try again
            }
            switch (status) {
                case "playing":
                    flash("Let's dance! 🎶", 0, true);
                    ui.postDelayed(this::finish, 2500);
                    break;
                case "sleeping":
                    flash("The tablet is sleeping 🌙\nSee you later!", 0, false);
                    break;
                case "tv_off":
                    flash("The TV is off.\nAsk a grown-up to turn it on.", 6000, false);
                    break;
                case "busy":
                    flash("The TV is catching up 📺\nTry again in a minute!", 4000, false);
                    break;
                case "paused":
                    flash("Dance time is taking a break 💤\nTry again later!", 0, false);
                    break;
                default:
                    flash("Oops! That didn't work.\nAsk a grown-up.", 6000, false);
            }
        });
    }

    /**
     * A big message over everything; cleared after ms (0 = stays). A blocking
     * one also swallows every tap (songs and buttons).
     */
    private void flash(String text, int ms, boolean blocking) {
        message.setText(text);
        message.setClickable(blocking);
        GradientDrawable bubble = new GradientDrawable();
        bubble.setColor(Color.argb(235, 255, 255, 255));
        bubble.setCornerRadius(dp(40));
        message.setBackground(text.isEmpty() ? null : bubble);
        message.bringToFront();
        if (ms > 0) {
            ui.postDelayed(() -> {
                if (text.contentEquals(message.getText())) {
                    flash("", 0, false);
                }
            }, ms);
        }
    }

    /** A big round "home" button, top-left: back to the app carousel. */
    private View homeButton() {
        TextView home = new TextView(this);
        home.setText("🏠");
        home.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        home.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(5), NAVY);
        home.setBackground(circle);
        home.setElevation(dp(10));
        home.setContentDescription("Home");
        home.setOnClickListener(v -> tapFeedback(v, this::finish));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.TOP | Gravity.START);
        lp.setMargins(dp(24), dp(24), 0, 0);
        home.setLayoutParams(lp);
        return home;
    }

    // ---- parent mode ------------------------------------------------------------------

    private void showParent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        box.setBackgroundColor(Color.rgb(0xf5, 0xf5, 0xf5));
        TextView title = text("Dance", 32, Color.BLACK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);
        box.addView(text("Pick the songs from a phone or computer on the home Wi-Fi: open the Movies & TV "
                + "web page (its address is in Movies & TV setup) and go to the Dance tab. The songs play on the Movies & TV TV, "
                + "through YouTube.", 20, Color.DKGRAY));
        setContentView(box);
    }

    // ---- helpers ------------------------------------------------------------------------

    private static boolean readParentMode() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getMethod("get", String.class);
            return "parent".equals(get.invoke(null, MODE_PROP));
        } catch (Exception e) {
            return false;
        }
    }

    private void showSystemBars(boolean show) {
        Window window = getWindow();
        window.setDecorFitsSystemWindows(show);
        WindowInsetsController insets = window.getInsetsController();
        if (insets == null) {
            return;
        }
        if (show) {
            insets.show(WindowInsets.Type.systemBars());
        } else {
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            insets.hide(WindowInsets.Type.systemBars());
        }
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
