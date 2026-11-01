package org.tabletos.movies;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.view.animation.OvershootInterpolator;
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
import android.widget.Button;
import android.widget.EditText;
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
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Movies, in two modes (chosen by the host, like Tablet Home, through the
 * persist.waydroid.tablet_mode property):
 *
 *  - kid: a carousel of the approved movie posters; tapping one plays it on
 *    the TV, from the beginning, as the kid's Plex profile, if the TV is on.
 *  - parent: link the Plex account, pick the profile and the TV, and choose
 *    which movies are in the carousel.
 *
 * Everything goes through the tablet-movies-bridge service on the Linux host,
 * which holds the Plex sign-in and talks to the TV.
 */
public class MoviesActivity extends Activity {
    private static final String TAG = "TabletMovies";
    private static final String BRIDGE = "http://192.168.240.1:8766/api";
    private static final String MODE_PROP = "persist.waydroid.tablet_mode";
    private static final int NAVY = Color.rgb(0x0d, 0x47, 0xa1);

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout kidRoot;
    private TextView kidMessage;
    private LinearLayout parentList;
    private boolean playing;

    private SoundPool sounds;
    private int popSound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Media usage, so the pop follows the kid volume the parent set.
        sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build()).build();
        popSound = sounds.load(this, R.raw.pop, 1);  // async; started early so the first tap has sound
    }

    /** A toddler needs to see and hear that a tap worked: squash-and-bounce plus a pop. */
    private void tapFeedback(View v, Runnable then) {
        sounds.play(popSound, 1f, 1f, 1, 0, 1f);
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new OvershootInterpolator(3f))
                        .withEndAction(then).start()).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (readParentMode()) {
            showSystemBars(true);
            showParent();
        } else {
            showSystemBars(false);
            showKid();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---- bridge calls -----------------------------------------------------------

    private interface Done<T> {
        void on(T result);
    }

    private static byte[] request(String method, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BRIDGE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(90000);  // a play can take a while: opening Plex, then checking it started
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

    /** Runs a bridge call off the main thread; delivers the JSON (or null on failure) on it. */
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

    private void loadPoster(String key, ImageView into) {
        io.execute(() -> {
            try {
                byte[] data = request("GET", "/poster/" + key);
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                ui.post(() -> into.setImageBitmap(bmp));
            } catch (Exception e) {
                Log.w(TAG, "poster " + key, e);
            }
        });
    }

    // ---- kid mode: poster carousel -------------------------------------------------

    private void showKid() {
        playing = false;
        kidRoot = new FrameLayout(this);
        ImageView sky = new ImageView(this);
        sky.setImageResource(R.drawable.sky);
        sky.setScaleType(ImageView.ScaleType.CENTER_CROP);
        kidRoot.addView(sky, fill());
        kidMessage = text("", 40, NAVY);
        kidMessage.setGravity(Gravity.CENTER);
        kidMessage.setTypeface(Typeface.DEFAULT_BOLD);
        kidRoot.addView(kidMessage, fill());
        setContentView(kidRoot);

        call("GET", "/movies", result -> {
            if (result == null) {
                kidMessage.setText("Movies & TV isn't ready yet");
                return;
            }
            // The kid's own theme (picked on the home screen), when it isn't the sky.
            int theme = getResources().getIdentifier("theme_" + result.optString("theme", "sky"),
                    "drawable", getPackageName());
            if (theme != 0) {
                sky.setImageResource(theme);
            }
            JSONArray movies = result.optJSONArray("movies");
            if (movies == null || movies.length() == 0) {
                kidMessage.setText("Ask a grown-up to add some movies");
                return;
            }
            kidMessage.setText("");
            View grid = buildGrid(movies);
            kidRoot.addView(grid, 1, fill());
            int wait = result.optInt("cooldown");
            if (result.optBoolean("paused")) {
                // A parent paused movie time (web portal): dim the posters and say so.
                grid.setAlpha(0.35f);
                flash("Movie time is taking a break 💤", 0);
            } else if (wait > 0) {
                // Something was just picked: give the TV time to catch up.
                waitForTv(grid, wait);
            }
        });
        kidRoot.addView(homeButton());
    }

    /** A big round "home" button, top-left: back to the app carousel. */
    private View homeButton() {
        TextView home = new TextView(this);
        home.setText("🏠"); // house emoji, easy for a toddler to recognise
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

    /** Movies and TV shows as a grid, 4 across, as many rows as needed, scrolling up and down. */
    private View buildGrid(JSONArray movies) {
        final int columns = 4;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int side = dp(32);
        int gap = dp(24);
        int right = side + dp(ScrollRail.WIDTH_DP + 24);  // room for the scroll rail
        int tileW = (screenW - side - right - (columns - 1) * gap) / columns;
        int posterW = tileW - dp(20);  // tile padding: 10dp each side
        int posterH = posterW * 3 / 2;

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        // Top padding keeps the first row clear of the home button.
        grid.setPadding(side, dp(136), right, dp(48));
        scroller.addView(grid);

        LinearLayout row = null;
        for (int i = 0; i < movies.length(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = gap;
                grid.addView(row, rowLp);
            }
            JSONObject m = movies.optJSONObject(i);
            String key = m.optString("key");
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            GradientDrawable card = new GradientDrawable();
            card.setColor(Color.WHITE);
            card.setCornerRadius(dp(28));
            tile.setBackground(card);
            tile.setPadding(dp(10), dp(10), dp(10), dp(14));
            tile.setElevation(dp(8));

            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setClipToOutline(true);
            GradientDrawable round = new GradientDrawable();
            round.setCornerRadius(dp(20));
            round.setColor(Color.rgb(0xe3, 0xf4, 0xfd));
            poster.setBackground(round);
            tile.addView(poster, new LinearLayout.LayoutParams(posterW, posterH));
            loadPoster(key, poster);

            TextView title = text(m.optString("title"), 22, Color.rgb(0x33, 0x33, 0x33));
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setPadding(0, dp(8), 0, 0);
            tile.addView(title, new LinearLayout.LayoutParams(posterW, ViewGroup.LayoutParams.WRAP_CONTENT));

            tile.setOnClickListener(v -> {
                // One title per visit: taps on other posters are ignored from
                // the first tap on, while it starts and after it's playing.
                if (playing) {
                    return;
                }
                playing = true;
                tapFeedback(v, () -> play(key));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = i % columns == 0 ? 0 : gap;
            row.addView(tile, lp);
        }
        return withRail(scroller, dp(24), dp(24));
    }

    /** The list with a big scroll bar (ScrollRail) down its right side. */
    private View withRail(ScrollView scroller, int top, int bottom) {
        FrameLayout box = new FrameLayout(this);
        box.addView(scroller, fill());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(ScrollRail.WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        lp.setMargins(0, top, dp(24), bottom);
        box.addView(new ScrollRail(this, scroller, NAVY, this::tapFeedback), lp);
        return box;
    }

    private void play(String key) {
        flash("Starting it on the TV…", 0, true);
        call("POST", "/play/" + key, result -> {
            String status = result == null ? "error" : result.optString("result");
            if (!"playing".equals(status)) {
                playing = false;  // didn't start: the kid may try again
            }
            switch (status) {
                case "playing":
                    // Show it worked, then back to the app carousel.
                    flash("It's playing on the TV!", 0, true);
                    ui.postDelayed(this::finish, 2500);
                    break;
                case "sleeping":
                    flash("The tablet is sleeping 🌙\nSee you later!", 0);
                    break;
                case "tv_off":
                    flash("The TV is off.\nAsk a grown-up to turn it on.", 6000);
                    break;
                case "plex_closed":
                    flash("Ask a grown-up to open Plex on the TV.", 6000);
                    break;
                case "busy":
                    flash("The TV is catching up 📺\nTry again in a minute!", 4000);
                    break;
                case "paused":
                    flash("Movie time is taking a break 💤\nTry again later!", 0);
                    break;
                default:
                    flash("Oops! That didn't work.\nAsk a grown-up.", 6000);
            }
        });
    }

    /** Dims the posters for the seconds left after a pick, with a friendly note. */
    private void waitForTv(View grid, int seconds) {
        grid.setAlpha(0.35f);
        flash("Your show is on its way to the TV 📺", seconds * 1000);
        ui.postDelayed(() -> grid.setAlpha(1f), seconds * 1000L);
    }

    private void flash(String message, int ms) {
        flash(message, ms, false);
    }

    /**
     * A big message over the grid; cleared after ms (0 = stays until replaced).
     * A blocking one also swallows every tap (posters and the home button).
     */
    private void flash(String message, int ms, boolean blocking) {
        kidMessage.setText(message);
        kidMessage.setClickable(blocking);
        GradientDrawable bubble = new GradientDrawable();
        bubble.setColor(Color.argb(235, 255, 255, 255));
        bubble.setCornerRadius(dp(40));
        kidMessage.setBackground(message.isEmpty() ? null : bubble);
        kidMessage.bringToFront();
        if (ms > 0) {
            ui.postDelayed(() -> {
                if (message.contentEquals(kidMessage.getText())) {
                    flash("", 0);
                }
            }, ms);
        }
    }

    // ---- parent mode: setup ------------------------------------------------------------

    private void showParent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(0xf5, 0xf5, 0xf5));
        parentList = new LinearLayout(this);
        parentList.setOrientation(LinearLayout.VERTICAL);
        parentList.setPadding(dp(32), dp(32), dp(32), dp(32));
        scroll.addView(parentList);
        setContentView(scroll);
        refreshParent();
    }

    private void refreshParent() {
        call("GET", "/setup", setup -> {
            parentList.removeAllViews();
            TextView title = text("Movies & TV setup", 32, Color.BLACK);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            parentList.addView(title);
            if (setup == null) {
                parentList.addView(text("Can't reach the Movies & TV service on this tablet. "
                        + "Setup only works while you're logged in as the parent.", 18, Color.RED));
                return;
            }

            heading("1. Plex account");
            if (setup.optBoolean("linked")) {
                parentList.addView(text("Linked: " + setup.optString("account"), 18, Color.DKGRAY));
            }
            parentList.addView(button(setup.optBoolean("linked") ? "Link a different account" : "Link Plex account",
                    v -> startLink()));

            if (!setup.optBoolean("linked")) {
                return;
            }
            heading("2. Profile the movies play as");
            parentList.addView(text(setup.isNull("profile") ? "Not chosen yet"
                    : setup.optString("profile") + " on " + setup.optString("server"), 18, Color.DKGRAY));
            parentList.addView(button("Choose profile", v -> chooseProfile()));

            heading("3. TV");
            JSONObject tv = setup.optJSONObject("tv");
            parentList.addView(text(tv == null ? "Not chosen yet"
                    : tv.optString("location") + " – " + tv.optString("name"), 18, Color.DKGRAY));
            parentList.addView(button("Find TVs", v -> chooseTv()));

            heading("Phone & web control");
            parentList.addView(text("From a phone or computer on the home Wi-Fi, open "
                    + setup.optString("portal_url") + " to pause movie time or change the list. "
                    + (setup.optBoolean("portal_pin_set") ? "A PIN is set." : "Set a PIN first:"),
                    18, Color.DKGRAY));
            LinearLayout pinRow = new LinearLayout(this);
            EditText pin = new EditText(this);
            pin.setHint("New PIN (4–8 digits)");
            pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pinRow.addView(pin, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            pinRow.addView(button("Save PIN", v -> savePin(pin.getText().toString())));
            parentList.addView(pinRow);

            if (setup.isNull("profile")) {
                return;
            }
            showMoviePicker();
        });
    }

    private void savePin(String pin) {
        if (!pin.matches("\\d{4,8}")) {
            toastLine("The PIN must be 4 to 8 digits.");
            return;
        }
        io.execute(() -> {
            boolean ok;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BRIDGE + "/portal-pin").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(new JSONObject().put("pin", pin).toString().getBytes("UTF-8"));
                ok = c.getResponseCode() == 200;
            } catch (Exception e) {
                Log.w(TAG, "saving the portal PIN", e);
                ok = false;
            }
            boolean saved = ok;
            ui.post(() -> {
                if (saved) {
                    refreshParent();
                } else {
                    toastLine("Couldn't save the PIN.");
                }
            });
        });
    }

    private void startLink() {
        call("POST", "/link", link -> {
            if (link == null) {
                toastLine("Couldn't start linking. Is the internet working?");
                return;
            }
            parentList.removeAllViews();
            parentList.addView(text("Go to plex.tv/link on any device and enter:", 22, Color.BLACK));
            TextView code = text(link.optString("code"), 64, NAVY);
            code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            parentList.addView(code);
            parentList.addView(button("Cancel", v -> refreshParent()));
            pollLink(150);
        });
    }

    private void pollLink(int triesLeft) {
        ui.postDelayed(() -> call("GET", "/link", status -> {
            if (status != null && status.optBoolean("linked")) {
                refreshParent();
            } else if (triesLeft > 0 && !isFinishing()) {
                pollLink(triesLeft - 1);
            }
        }), 3000);
    }

    private void chooseProfile() {
        call("GET", "/profiles", result -> {
            JSONArray list = result == null ? null : result.optJSONArray("profiles");
            if (list == null) {
                toastLine("Couldn't load profiles.");
                return;
            }
            heading("Tap the kid's profile:");
            for (int i = 0; i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                String label = p.optString("title") + (p.optBoolean("restricted") ? "  (restricted)" : "");
                parentList.addView(button(label, v -> call("POST", "/profile/" + p.optString("id"),
                        r -> {
                            if (r == null) {
                                toastLine("Couldn't use that profile.");
                            } else {
                                refreshParent();
                            }
                        })));
            }
        });
    }

    private void chooseTv() {
        toastLine("Looking for TVs…");
        call("GET", "/tvs", result -> {
            JSONArray list = result == null ? null : result.optJSONArray("tvs");
            if (list == null || list.length() == 0) {
                toastLine("No Roku TVs found. Make sure they're on the same Wi-Fi.");
                return;
            }
            heading("Tap the TV to use:");
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                String label = t.optString("location") + " – " + t.optString("name")
                        + ("PowerOn".equals(t.optString("power")) ? "  (on)" : "  (off/standby)");
                parentList.addView(button(label, v -> call("POST", "/tv/" + t.optString("serial"),
                        r -> refreshParent())));
            }
        });
    }

    // The movie picker edits in place (no page rebuild), so a search stays on
    // screen while several of its results are added or removed.
    private final java.util.Set<String> chosenKeys = new java.util.HashSet<>();
    private TextView chosenHeading;
    private LinearLayout chosenBox;
    /** The parent's search survives any redraw of the page; only the parent's typing changes it. */
    private String searchText = "";
    private boolean searched;

    private void showMoviePicker() {
        chosenHeading = new TextView(this);
        parentList.addView(chosenHeading);
        chosenHeading.setTypeface(Typeface.DEFAULT_BOLD);
        chosenHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        chosenHeading.setTextColor(Color.BLACK);
        chosenHeading.setPadding(0, dp(24), 0, dp(4));
        chosenBox = new LinearLayout(this);
        chosenBox.setOrientation(LinearLayout.VERTICAL);
        parentList.addView(chosenBox);

        heading("Add movies");
        LinearLayout search = new LinearLayout(this);
        EditText query = new EditText(this);
        query.setHint("Search the kid's movies and TV shows (blank = all)");
        query.setSingleLine(true);
        query.setText(searchText);
        query.setSelection(searchText.length());
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                searchText = s.toString();
            }
        });
        search.addView(query, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        search.addView(button("Search", v -> {
            searched = true;
            searchLibrary(searchText, results);
        }));
        parentList.addView(search);
        parentList.addView(results);

        heading("Add from Netflix");
        parentList.addView(text("Paste the show's netflix.com/title/… link (or the number at its end). "
                + "It opens in Netflix on the TV, in whichever Netflix profile was used there last.",
                16, Color.DKGRAY));
        LinearLayout netflixRow = new LinearLayout(this);
        EditText netflix = new EditText(this);
        netflix.setHint("netflix.com/title/…");
        netflix.setSingleLine(true);
        netflixRow.addView(netflix, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView netflixMsg = text("", 16, NAVY);
        netflixRow.addView(button("Add", v -> {
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("(\\d{5,10})")
                    .matcher(netflix.getText());
            if (!id.find()) {
                netflixMsg.setText("That doesn't look like a Netflix link.");
                return;
            }
            v.setEnabled(false);
            call("POST", "/netflix/" + id.group(1), r -> {
                v.setEnabled(true);
                netflixMsg.setText(r == null ? "Couldn't find that on Netflix." : "Added.");
                if (r != null) {
                    netflix.setText("");
                    renderChosen();
                }
            });
        }));
        parentList.addView(netflixRow);
        parentList.addView(netflixMsg);
        // Load the chosen list first so the results' Add/Remove buttons are right.
        renderChosen(() -> {
            if (searched) {
                searchLibrary(searchText, results);
            }
        });
    }

    private void renderChosen() {
        renderChosen(null);
    }

    private void renderChosen(Runnable then) {
        call("GET", "/movies", current -> {
            JSONArray chosen = current == null ? null : current.optJSONArray("movies");
            chosenKeys.clear();
            chosenBox.removeAllViews();
            int count = chosen == null ? 0 : chosen.length();
            chosenHeading.setText("4. Movies & TV shows for the kid (" + count + ")");
            for (int i = 0; i < count; i++) {
                JSONObject m = chosen.optJSONObject(i);
                chosenKeys.add(m.optString("key"));
                chosenBox.addView(movieRow(m));
            }
            if (then != null) {
                then.run();
            }
        });
    }

    private void searchLibrary(String q, LinearLayout results) {
        results.removeAllViews();
        results.addView(text("Searching…", 18, Color.DKGRAY));
        String path;
        try {
            path = "/library?q=" + URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            return;
        }
        call("GET", path, result -> {
            results.removeAllViews();
            JSONArray list = result == null ? null : result.optJSONArray("movies");
            if (list == null || list.length() == 0) {
                results.addView(text("No movies found.", 18, Color.DKGRAY));
                return;
            }
            java.util.List<Button> toggles = new java.util.ArrayList<>();
            Button addAll = button("Add all " + list.length(), null);
            results.addView(addAll);
            for (int i = 0; i < list.length(); i++) {
                View row = movieRow(list.optJSONObject(i));
                toggles.add((Button) ((LinearLayout) row).getChildAt(1));
                results.addView(row);
            }
            addAll.setOnClickListener(v -> {
                for (Button b : toggles) {
                    if ("Add".contentEquals(b.getText())) {
                        b.performClick();
                    }
                }
            });
        });
    }

    /** A movie with an Add/Remove button that flips in place. */
    private View movieRow(JSONObject m) {
        String key = m.optString("key");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String year = m.optInt("year") > 0 ? " (" + m.optInt("year") + ")" : "";
        String rating = m.optString("rating", "");
        String kind = "show".equals(m.optString("kind")) ? "  · TV show (random episode)"
                : "netflix".equals(m.optString("kind")) ? "  · Netflix" : "";
        TextView label = text(m.optString("title") + year + (rating.isEmpty() || "null".equals(rating)
                ? "" : "  " + rating) + kind, 18, Color.BLACK);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button toggle = button(chosenKeys.contains(key) ? "Remove" : "Add", null);
        toggle.setOnClickListener(v -> {
            boolean adding = "Add".contentEquals(toggle.getText());
            toggle.setEnabled(false);
            call(adding ? "POST" : "DELETE", "/movies/" + key, r -> {
                toggle.setEnabled(true);
                if (r != null) {
                    toggle.setText(adding ? "Remove" : "Add");
                    renderChosen();
                }
            });
        });
        row.addView(toggle);
        return row;
    }

    private void heading(String s) {
        TextView t = text(s, 22, Color.BLACK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(4));
        parentList.addView(t);
    }

    private void toastLine(String s) {
        parentList.addView(text(s, 18, NAVY));
    }

    // ---- shared helpers ------------------------------------------------------------------

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

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
