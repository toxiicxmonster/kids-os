package org.tabletos.paint;

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
import java.io.File;
import java.io.FileOutputStream;
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
 * Paint: finger painting and coloring pages.
 *
 *  - kid: the paper in the middle; tools down the left (pages, fill, brush,
 *    undo, wipe) and big color dots along the bottom. Starts on the page
 *    picker. Each page remembers the kid's painting (in this kid's Android),
 *    so they can come back to it. Wiping and everything else can be undone.
 *  - parent mode: just says where the pages are chosen.
 *
 * The coloring pages come from the host's bridge (192.168.240.1:8766), which
 * downloads them once from Openclipart and keeps them; the parents add more
 * on the Movies & TV web page's Coloring tab.
 */
public class PaintActivity extends Activity {
    private static final String TAG = "TabletPaint";
    private static final String BRIDGE = "http://192.168.240.1:8766/api";
    private static final String MODE_PROP = "persist.waydroid.tablet_mode";
    private static final int NAVY = Color.rgb(0x0d, 0x47, 0xa1);
    private static final int ORANGE = Color.rgb(0xff, 0x8a, 0x00);
    private static final String BLANK = "blank";
    private static final int[] COLORS = {
            Color.rgb(0xe5, 0x39, 0x35), Color.rgb(0xff, 0x8f, 0x00), Color.rgb(0xfd, 0xd8, 0x35),
            Color.rgb(0x43, 0xa0, 0x47), Color.rgb(0x29, 0xb6, 0xf6), Color.rgb(0x1e, 0x40, 0xd8),
            Color.rgb(0x8e, 0x24, 0xaa), Color.rgb(0xff, 0x6f, 0xb5), Color.rgb(0x79, 0x55, 0x48),
            Color.rgb(0x21, 0x21, 0x21), Color.WHITE,
    };

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private ImageView background;
    private PaintView paper;
    private FrameLayout picker;
    private TextView fillButton;
    private TextView brushButton;
    private TextView undoButton;
    private final List<TextView> dots = new ArrayList<>();
    private String pageId;
    private boolean loading;

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
        } else if (root == null) {
            showKid();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveArt();
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

    // ---- kid screen ---------------------------------------------------------------------

    private void showKid() {
        root = new FrameLayout(this);
        background = new ImageView(this);
        background.setImageResource(R.drawable.sky);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(background, fill());

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int margin = dp(20);
        // Six round buttons down the left (home + 5 tools), as big as fit.
        int tool = Math.min(dp(92), (screenH - 2 * margin) / 6 - dp(10));
        // Eleven color dots along the bottom.
        int left = margin + tool + margin;
        int dot = Math.min(dp(84), (screenW - left - margin) / COLORS.length - dp(10));

        // The paper: a white card between the tools and the colors.
        FrameLayout card = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(dp(28));
        card.setBackground(cardBg);
        card.setElevation(dp(8));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        paper = new PaintView(this);
        paper.setOnChange(this::refreshUndo);
        card.addView(paper, fill());
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cardLp.setMargins(left, margin, margin, margin + dot + dp(16));
        root.addView(card, cardLp);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.addView(roundButton("🏠", tool, NAVY, "Home", this::finish));
        tools.addView(roundButton("🖼️", tool, NAVY, "Pages", this::showPicker));
        fillButton = roundButton("🪣", tool, NAVY, "Fill", () -> pickTool(PaintView.Tool.FILL));
        brushButton = roundButton("🖌️", tool, NAVY, "Brush", () -> pickTool(PaintView.Tool.BRUSH));
        undoButton = roundButton("↩️", tool, NAVY, "Undo", () -> paper.undo());
        tools.addView(fillButton);
        tools.addView(brushButton);
        tools.addView(undoButton);
        tools.addView(roundButton("🧽", tool, NAVY, "Wipe", () -> paper.wipe()));
        FrameLayout.LayoutParams toolsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        toolsLp.setMargins(margin, margin, 0, 0);
        root.addView(tools, toolsLp);

        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        colors.setGravity(Gravity.CENTER);
        dots.clear();
        for (int c : COLORS) {
            TextView d = new TextView(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(c);
            circle.setStroke(dp(4), Color.WHITE);
            d.setBackground(circle);
            d.setElevation(dp(6));
            d.setContentDescription("Color");
            d.setOnClickListener(v -> tapFeedback(v, () -> pickColor(c)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dot, dot);
            lp.setMargins(dp(5), 0, dp(5), 0);
            colors.addView(d, lp);
            dots.add(d);
        }
        FrameLayout.LayoutParams colorsLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dot + dp(12), Gravity.BOTTOM);
        colorsLp.setMargins(left, 0, margin, margin);
        root.addView(colors, colorsLp);

        setContentView(root);
        pickTool(PaintView.Tool.FILL);
        pickColor(COLORS[0]);
        refreshUndo();
        call("GET", "/coloring", result -> {
            if (result != null) {
                int theme = getResources().getIdentifier("theme_" + result.optString("theme", "sky"),
                        "drawable", getPackageName());
                if (theme != 0) {
                    background.setImageResource(theme);
                }
            }
        });
        showPicker();
    }

    private void pickTool(PaintView.Tool t) {
        paper.setTool(t);
        highlight(fillButton, t == PaintView.Tool.FILL);
        highlight(brushButton, t == PaintView.Tool.BRUSH);
    }

    private void pickColor(int c) {
        paper.setColor(c);
        for (int i = 0; i < dots.size(); i++) {
            boolean on = COLORS[i] == c;
            TextView d = dots.get(i);
            GradientDrawable circle = (GradientDrawable) d.getBackground();
            circle.setStroke(dp(on ? 8 : 4), on ? NAVY : Color.WHITE);
            d.setScaleX(on ? 1.12f : 1f);
            d.setScaleY(on ? 1.12f : 1f);
        }
    }

    private void highlight(TextView button, boolean on) {
        GradientDrawable circle = (GradientDrawable) button.getBackground();
        circle.setColor(on ? Color.rgb(0xff, 0xe0, 0x8a) : Color.WHITE);
        circle.setStroke(dp(on ? 7 : 5), on ? ORANGE : NAVY);
    }

    private void refreshUndo() {
        if (undoButton != null) {
            undoButton.setAlpha(paper.canUndo() ? 1f : 0.4f);
        }
    }

    // ---- page picker --------------------------------------------------------------------

    /** Every page as a big picture over the paper (blank paper first); tap one to paint it. */
    private void showPicker() {
        if (picker != null) {
            return;
        }
        saveArt();
        picker = new FrameLayout(this);
        picker.setBackgroundColor(Color.argb(170, 0, 0, 0));
        picker.setClickable(true);  // nothing underneath gets the taps
        // Above the paper, tools and colors: raised views (elevation) draw over
        // flat ones whatever order they were added in.
        picker.setElevation(dp(40));
        root.addView(picker, fill());
        TextView wait = text("Getting the pages…", 36, Color.WHITE);
        wait.setGravity(Gravity.CENTER);
        picker.addView(wait, fill());

        call("GET", "/coloring", result -> {
            if (picker == null) {
                return;
            }
            picker.removeAllViews();
            JSONArray pages = result == null ? new JSONArray() : result.optJSONArray("pages");
            List<String[]> items = new ArrayList<>();
            items.add(new String[]{BLANK, "Blank paper"});
            for (int i = 0; pages != null && i < pages.length(); i++) {
                JSONObject p = pages.optJSONObject(i);
                items.add(new String[]{p.optString("id"), p.optString("title")});
            }
            buildPickerGrid(items);
        });
    }

    private void buildPickerGrid(List<String[]> items) {
        final int columns = 4;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int side = dp(32);
        int gap = dp(24);
        int right = side + dp(ScrollRail.WIDTH_DP + 24);
        int tileW = (screenW - side - right - (columns - 1) * gap) / columns;
        int picW = tileW - dp(20);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(side, dp(136), right, dp(48));
        scroller.addView(grid);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = gap;
                grid.addView(row, rowLp);
            }
            String id = items.get(i)[0];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            GradientDrawable card = new GradientDrawable();
            card.setColor(Color.WHITE);
            card.setCornerRadius(dp(28));
            tile.setBackground(card);
            tile.setPadding(dp(10), dp(10), dp(10), dp(14));
            tile.setElevation(dp(8));
            ImageView pic = new ImageView(this);
            pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pic.setBackgroundColor(Color.WHITE);
            tile.addView(pic, new LinearLayout.LayoutParams(picW, picW * 3 / 4));
            if (BLANK.equals(id)) {
                pic.setImageBitmap(savedArt(BLANK));
            } else {
                loadThumb(id, pic, picW);
            }
            TextView title = text(items.get(i)[1], 22, Color.rgb(0x33, 0x33, 0x33));
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(1);
            title.setPadding(0, dp(8), 0, 0);
            tile.addView(title, new LinearLayout.LayoutParams(picW, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.setOnClickListener(v -> {
                if (loading) {
                    return;  // one page at a time
                }
                loading = true;
                tapFeedback(v, () -> openPage(id));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = i % columns == 0 ? 0 : gap;
            row.addView(tile, lp);
        }

        FrameLayout box = new FrameLayout(this);
        box.addView(scroller, fill());
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(
                dp(ScrollRail.WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        railLp.setMargins(0, dp(24), dp(24), dp(24));
        box.addView(new ScrollRail(this, scroller, NAVY, this::tapFeedback), railLp);
        picker.addView(box, fill());

        // Close the picker without choosing (only once a page is open).
        if (paper.hasPage()) {
            TextView close = roundButton("✖️", dp(96), NAVY, "Close", this::closePicker);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.TOP | Gravity.START);
            lp.setMargins(dp(24), dp(24), 0, 0);
            picker.addView(close, lp);
        } else {
            TextView home = roundButton("🏠", dp(96), NAVY, "Home", this::finish);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(96), dp(96), Gravity.TOP | Gravity.START);
            lp.setMargins(dp(24), dp(24), 0, 0);
            picker.addView(home, lp);
        }
    }

    private void closePicker() {
        if (picker != null) {
            root.removeView(picker);
            picker = null;
        }
    }

    private void openPage(String id) {
        if (BLANK.equals(id)) {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            pageId = id;
            paper.setPage(null, Math.min(1600, screenW), Math.min(1100, screenH), savedArt(id));
            loading = false;
            closePicker();
            return;
        }
        io.execute(() -> {
            Bitmap art = null;
            try {
                byte[] data = request("GET", "/coloring-page/" + id);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                art = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (Exception e) {
                Log.w(TAG, "page " + id, e);
            }
            Bitmap saved = savedArt(id);
            Bitmap page = art;
            ui.post(() -> {
                loading = false;
                if (page == null) {
                    return;  // stays on the picker; another tap tries again
                }
                pageId = id;
                paper.setPage(page, 0, 0, saved);
                closePicker();
            });
        });
    }

    // ---- remembering the kid's paintings -------------------------------------------------

    private File artFile(String id) {
        return new File(getFilesDir(), "art-" + id.replaceAll("[^0-9a-z]", "") + ".png");
    }

    private Bitmap savedArt(String id) {
        File f = artFile(id);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    private void saveArt() {
        if (pageId == null || paper == null || !paper.isChanged()) {
            return;
        }
        Bitmap art = paper.paintLayer().copy(Bitmap.Config.ARGB_8888, false);
        File f = artFile(pageId);
        io.execute(() -> {
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                art.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException e) {
                Log.w(TAG, "saving " + f, e);
                return;
            }
            if (!tmp.renameTo(f)) {
                Log.w(TAG, "couldn't save " + f);
            }
        });
    }

    // ---- bridge calls -------------------------------------------------------------------

    private interface Done<T> {
        void on(T result);
    }

    private static byte[] request(String method, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BRIDGE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(60000);  // a page is downloaded from Openclipart the first time
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

    private void loadThumb(String id, ImageView into, int width) {
        io.execute(() -> {
            try {
                byte[] data = request("GET", "/coloring-page/" + id);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = Math.max(1, bounds.outWidth / Math.max(1, width));
                Bitmap page = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                Bitmap saved = savedArt(id);
                Bitmap shown = page;
                if (saved != null && page != null) {
                    // Show the kid's painting under the lines, as they left it.
                    shown = Bitmap.createScaledBitmap(saved, page.getWidth(), page.getHeight(), true)
                            .copy(Bitmap.Config.ARGB_8888, true);
                    Bitmap lines = page.copy(Bitmap.Config.ARGB_8888, true);
                    int[] px = new int[lines.getWidth() * lines.getHeight()];
                    lines.getPixels(px, 0, lines.getWidth(), 0, 0, lines.getWidth(), lines.getHeight());
                    for (int i = 0; i < px.length; i++) {
                        px[i] = Color.red(px[i]) < 128 ? Color.BLACK : Color.TRANSPARENT;
                    }
                    lines.setPixels(px, 0, lines.getWidth(), 0, 0, lines.getWidth(), lines.getHeight());
                    new android.graphics.Canvas(shown).drawBitmap(lines, 0, 0, null);
                }
                Bitmap result = shown;
                ui.post(() -> into.setImageBitmap(result));
            } catch (Exception e) {
                Log.w(TAG, "thumbnail " + id, e);
            }
        });
    }

    // ---- parent mode ------------------------------------------------------------------

    private void showParent() {
        root = null;
        picker = null;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        box.setBackgroundColor(Color.rgb(0xf5, 0xf5, 0xf5));
        TextView title = text("Paint", 32, Color.BLACK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);
        box.addView(text("Choose the coloring pages from a phone or computer on the home Wi-Fi: open the "
                + "Movies & TV web page (its address is in Movies & TV setup) and go to the Coloring tab. "
                + "The pages are kept on the tablet, so they work without the internet.", 20, Color.DKGRAY));
        setContentView(box);
    }

    // ---- helpers ------------------------------------------------------------------------

    private TextView roundButton(String glyph, int size, int color, String name, Runnable action) {
        TextView v = new TextView(this);
        v.setText(glyph);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.45f);
        v.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(5), color);
        v.setBackground(circle);
        v.setElevation(dp(10));
        v.setContentDescription(name);
        v.setOnClickListener(b -> tapFeedback(b, action));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.bottomMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

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

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
