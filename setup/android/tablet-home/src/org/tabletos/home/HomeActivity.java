package org.tabletos.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.graphics.PixelFormat;
import android.view.WindowManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.UserManager;
import android.view.animation.OvershootInterpolator;
import android.widget.SeekBar;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Home screen with two modes, chosen by the host through the
 * persist.waydroid.tablet_mode property (`waydroid prop set ...`):
 *
 *  - kid (default, and whenever the property is anything but "parent"):
 *    a swipeable carousel of the approved apps, locked in kiosk mode so
 *    nothing else can be opened.
 *  - parent: kiosk off, Play Store and Settings buttons, and a switch per
 *    installed app deciding whether it appears in the kid's carousel.
 */
public class HomeActivity extends Activity {
    private static final String TAG = "TabletHome";
    private static final String MODE_PROP = "persist.waydroid.tablet_mode";
    static final String PREFS = "apps";
    private static final String KEY_APPROVED = "approved";
    private static final String KEY_THEME = "theme";  // last known, so the right one shows at once
    private static final long MODE_POLL_MS = 1500;

    private DevicePolicyManager dpm;
    private ComponentName admin;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Boolean shownParentMode;
    private boolean ownerSetupDone;
    private ImageView kidBackground;
    private boolean sleeping;  // outside the kid's tablet time (bedtime, daily limit)
    private String sleepMessage = "";
    private boolean resumed;
    private static final long SLEEP_POLL_MS = 20000;

    /** Bedtime: checked all the time, even while the kid is in another app. */
    private final Runnable sleepPoll = new Runnable() {
        @Override
        public void run() {
            if (!Boolean.TRUE.equals(shownParentMode)) {
                Themes.allowed((allowed, message) -> {
                    if (!allowed && (!sleeping || !resumed)) {
                        sleeping = true;
                        sleepMessage = message;
                        if (resumed) {
                            render();
                        } else {
                            // Back to the home screen over whatever app is open.
                            startActivity(new Intent(HomeActivity.this, HomeActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                        }
                    } else if (allowed && sleeping) {
                        sleeping = false;
                        if (resumed) {
                            render();
                        }
                    }
                });
            }
            handler.postDelayed(this, SLEEP_POLL_MS);
        }
    };

    private final Runnable modePoll = new Runnable() {
        @Override
        public void run() {
            if (shownParentMode == null || readParentMode() != shownParentMode) {
                render();
            }
            handler.postDelayed(this, MODE_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dpm = getSystemService(DevicePolicyManager.class);
        admin = new ComponentName(this, AdminReceiver.class);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadSounds();  // loads asynchronously; start early so the first tap has sound
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (handler.hasCallbacks(sleepPoll)) {
            handler.removeCallbacks(sleepPoll);
        }
        handler.post(sleepPoll);
        hideHomeBubble();
        launchedPkg = null;
        render();
        handler.removeCallbacks(modePoll);
        handler.postDelayed(modePoll, MODE_POLL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(modePoll);
        // Leaving for an app the kid tapped: give them a way back.
        if (Boolean.FALSE.equals(shownParentMode)) {
            showHomeBubble();
        }
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to.
    }

    private void render() {
        boolean parent = readParentMode();
        shownParentMode = parent;
        if (isOwner()) {
            setUpOwner();
        }
        if (parent) {
            leaveKiosk();
            showSystemBars(true);
            setContentView(buildParentView());
        } else {
            showSystemBars(false);
            setContentView(sleeping ? buildSleepView() : buildKidView());
            enterKiosk();
            applyKidVolume();
        }
    }

    /** Kid mode: the sky fills the whole screen with no bars (gesture nav, so nothing is lost). */
    private void showSystemBars(boolean show) {
        Window window = getWindow();
        window.setDecorFitsSystemWindows(show);
        window.setNavigationBarColor(show ? Color.BLACK : Color.TRANSPARENT);
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

    // ---- mode and device-owner policy -------------------------------------

    static boolean readParentMode() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getMethod("get", String.class);
            return "parent".equals(get.invoke(null, MODE_PROP));
        } catch (Exception e) {
            Log.w(TAG, "can't read " + MODE_PROP + ", staying in kid mode", e);
            return false;
        }
    }

    private boolean isOwner() {
        return dpm.isDeviceOwnerApp(getPackageName());
    }

    private void setUpOwner() {
        if (ownerSetupDone) {
            return;
        }
        ownerSetupDone = true;
        IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addCategory(Intent.CATEGORY_DEFAULT);
        dpm.addPersistentPreferredActivity(admin, home, new ComponentName(this, HomeActivity.class));
    }

    private void enterKiosk() {
        if (!isOwner()) {
            Log.w(TAG, "not device owner; carousel shown without kiosk lock");
            return;
        }
        List<String> allowed = new ArrayList<>(approved());
        allowed.add(getPackageName());
        dpm.setLockTaskPackages(admin, allowed.toArray(new String[0]));
        // Keep only the Home button: no recents, notifications, status bar or power menu.
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME);
        dpm.setStatusBarDisabled(admin, true);
        if (lockTaskState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            startLockTask();
        }
    }

    private void leaveKiosk() {
        if (!isOwner()) {
            return;
        }
        dpm.setStatusBarDisabled(admin, false);
        if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
            stopLockTask();
        }
    }

    private int lockTaskState() {
        return getSystemService(ActivityManager.class).getLockTaskModeState();
    }

    // ---- apps -------------------------------------------------------------

    /** In the carousel until the parent first changes the list: PBS KIDS (the main app) and our apps. */
    private static final Set<String> DEFAULT_APPROVED =
            Set.of("org.pbskids.gamesapp", "org.tabletos.movies", "org.tabletos.dance");
    /** Our own apps: each goes into the carousel the first time it's seen (the parent can switch it off). */
    private static final Set<String> BUILT_IN = Set.of("org.tabletos.movies", "org.tabletos.dance");
    private static final String KEY_SEEN_BUILT_IN = "seen_built_in";

    private Set<String> approved() {
        Set<String> apps = new HashSet<>(prefs.getStringSet(KEY_APPROVED, DEFAULT_APPROVED));
        Set<String> seen = new HashSet<>(prefs.getStringSet(KEY_SEEN_BUILT_IN, Set.of()));
        if (!seen.containsAll(BUILT_IN)) {
            for (String pkg : BUILT_IN) {
                if (seen.add(pkg)) {
                    apps.add(pkg);
                }
            }
            prefs.edit().putStringSet(KEY_APPROVED, apps).putStringSet(KEY_SEEN_BUILT_IN, seen).commit();
        }
        return apps;
    }

    private void setApproved(String pkg, boolean on) {
        Set<String> apps = approved();
        if (on) {
            apps.add(pkg);
        } else {
            apps.remove(pkg);
        }
        // commit(), not apply(): written before the switch returns, so a power cut
        // right after the parent's change can't lose it.
        prefs.edit().putStringSet(KEY_APPROVED, apps).commit();
    }

    /** Launchable apps other than this one, sorted by name. */
    private List<ResolveInfo> launchableApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.equals(getPackageName()) && seen.add(pkg)) {
                apps.add(ri);
            }
        }
        apps.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        return apps;
    }

    /**
     * The app's icon from its highest-density resources. loadIcon() picks the
     * density of this screen, which looks soft scaled up to a carousel tile.
     */
    private Drawable sharpIcon(ResolveInfo ri) {
        PackageManager pm = getPackageManager();
        int res = ri.getIconResource();
        if (res != 0) {
            try {
                Resources appRes = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                Drawable d = appRes.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) {
                    return d;
                }
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                Log.w(TAG, "no hi-res icon for " + ri.activityInfo.packageName, e);
            }
        }
        return ri.loadIcon(pm);
    }

    // ---- home bubble: a way out of any app ---------------------------------------

    /** Apps with their own home button; the bubble would just duplicate it. */
    private static final Set<String> HAS_OWN_HOME_BUTTON = Set.of("org.tabletos.movies", "org.tabletos.dance");
    private View bubble;
    private String launchedPkg;

    /**
     * Kid mode: a round home button floating over the app that was just opened
     * (a system overlay, so it works over any app). Tapping it comes back here.
     */
    private void showHomeBubble() {
        if (bubble != null || launchedPkg == null || HAS_OWN_HOME_BUTTON.contains(launchedPkg)
                || !Settings.canDrawOverlays(this)) {
            return;
        }
        TextView home = new TextView(getApplicationContext());
        home.setText("🏠");
        home.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        home.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(4), Color.rgb(0x0d, 0x47, 0xa1));
        home.setBackground(circle);
        home.setAlpha(0.85f);
        home.setContentDescription("Home");
        home.setOnClickListener(v -> tapFeedback(v, () -> {
            Intent back = new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(back);
        }));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(64), dp(64),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // Left edge, halfway down: games tend to use the corners for their own buttons.
        lp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        lp.x = dp(12);
        getSystemService(WindowManager.class).addView(home, lp);
        bubble = home;
    }

    private void hideHomeBubble() {
        if (bubble != null) {
            getSystemService(WindowManager.class).removeView(bubble);
            bubble = null;
        }
    }

    private void launch(String pkg) {
        if (sleeping) {
            return;
        }
        launchedPkg = pkg;
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) {
            return;
        }
        try {
            Themes.report(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString());
        } catch (PackageManager.NameNotFoundException e) {
            Themes.report(pkg);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "can't launch " + pkg, e);
        }
    }

    // ---- kid view: sleeping (outside tablet time) ---------------------------

    private View buildSleepView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(0x14, 0x10, 0x4a));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView moon = text("🌙", 120, Color.WHITE);
        moon.setGravity(Gravity.CENTER);
        box.addView(moon);
        TextView title = text("The tablet is sleeping", 44, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView when = text(sleepMessage, 30, Color.rgb(0xff, 0xe0, 0x8a));
        when.setGravity(Gravity.CENTER);
        when.setPadding(0, dp(16), 0, 0);
        box.addView(when);
        root.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    // ---- kid view: carousel -----------------------------------------------

    private View buildKidView() {
        FrameLayout root = new FrameLayout(this);
        kidBackground = new ImageView(this);
        kidBackground.setImageResource(Themes.drawable(this, prefs.getString(KEY_THEME, Themes.DEFAULT)));
        kidBackground.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(kidBackground, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // This kid's theme (several kids can share the tablet, each with their own).
        Themes.fetch(theme -> {
            if (theme != null && !theme.equals(prefs.getString(KEY_THEME, null))) {
                showTheme(theme);
            }
        });

        List<ResolveInfo> apps = new ArrayList<>();
        Set<String> approved = approved();
        for (ResolveInfo ri : launchableApps()) {
            if (approved.contains(ri.activityInfo.packageName)) {
                apps.add(ri);
            }
        }
        if (apps.isEmpty()) {
            TextView empty = text("Ask a grown-up to add some apps", 36, Color.rgb(0x0d, 0x47, 0xa1));
            empty.setGravity(Gravity.CENTER);
            root.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(themeButton(root));
            return root;
        }

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int tileW = Math.min((int) (screenW * 0.34), (int) (screenH * 0.55));
        int margin = dp(24);
        int step = tileW + 2 * margin;
        int sidePad = Math.max(0, (screenW - step) / 2);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(sidePad, 0, sidePad, 0);

        PackageManager pm = getPackageManager();
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(16), dp(24), dp(16), dp(24));
            GradientDrawable card = new GradientDrawable();
            card.setColor(Color.WHITE);
            card.setCornerRadius(dp(40));
            tile.setBackground(card);
            tile.setElevation(dp(8));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(sharpIcon(ri));
            int iconSize = (int) (tileW * 0.62);
            tile.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView label = text(ri.loadLabel(pm).toString(), 26, Color.rgb(0x33, 0x33, 0x33));
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = dp(16);
            tile.addView(label, labelLp);

            tile.setOnClickListener(v -> tapFeedback(v, () -> launch(pkg)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(margin, 0, margin, 0);
            row.addView(tile, lp);
        }
        scroller.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        snapToTiles(scroller, step, apps.size());

        root.addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(themeButton(root));
        return root;
    }

    // ---- themes: the kid picks their own background ------------------------------

    /** A round paint-palette button, bottom middle. */
    private View themeButton(FrameLayout root) {
        TextView button = new TextView(this);
        button.setText("🎨");
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        button.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(5), Color.rgb(0x0d, 0x47, 0xa1));
        button.setBackground(circle);
        button.setElevation(dp(10));
        button.setContentDescription("Theme");
        button.setOnClickListener(v -> tapFeedback(v, () -> showThemePicker(root)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(88), dp(88),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(20);
        button.setLayoutParams(lp);
        return button;
    }

    /** Big pictures of every theme over the home screen; tap one to use it, or outside to close. */
    private void showThemePicker(FrameLayout root) {
        FrameLayout shade = new FrameLayout(this);
        shade.setBackgroundColor(Color.argb(150, 0, 0, 0));
        shade.setClickable(true);
        shade.setOnClickListener(v -> root.removeView(shade));

        int columns = 5;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int gap = dp(18);
        int thumbW = (screenW - dp(64) - (columns - 1) * gap) / columns;
        int thumbH = thumbW * 9 / 16;
        String current = prefs.getString(KEY_THEME, Themes.DEFAULT);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < Themes.NAMES.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = gap;
                grid.addView(row, rowLp);
            }
            String name = Themes.NAMES[i];
            ImageView thumb = new ImageView(this);
            thumb.setImageBitmap(Themes.thumbnail(this, name));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setClipToOutline(true);
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(dp(22));
            frame.setColor(Color.WHITE);
            if (name.equals(current)) {
                frame.setStroke(dp(8), Color.rgb(0xff, 0xcf, 0x3f));  // the one in use
            }
            thumb.setBackground(frame);
            thumb.setPadding(dp(6), dp(6), dp(6), dp(6));
            thumb.setElevation(dp(8));
            thumb.setContentDescription(name);
            thumb.setOnClickListener(v -> tapFeedback(v, () -> {
                root.removeView(shade);
                showTheme(name);
                Themes.save(name);
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(thumbW, thumbH);
            lp.leftMargin = i % columns == 0 ? 0 : gap;
            row.addView(thumb, lp);
        }
        shade.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(shade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showTheme(String name) {
        prefs.edit().putString(KEY_THEME, name).commit();
        if (kidBackground != null) {
            kidBackground.setImageResource(Themes.drawable(this, name));
        }
    }

    /** After each swipe, settle so one tile sits in the middle (a swipe moves one tile). */
    private void snapToTiles(HorizontalScrollView scroller, int step, int count) {
        final int[] startX = new int[1];
        scroller.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startX[0] = scroller.getScrollX();
            } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                int start = Math.round((float) startX[0] / step);
                int moved = scroller.getScrollX() - startX[0];
                int target = start;
                if (moved > step * 0.12f) {
                    target = start + 1;
                } else if (moved < -step * 0.12f) {
                    target = start - 1;
                }
                int index = Math.max(0, Math.min(count - 1, target));
                scroller.post(() -> scroller.smoothScrollTo(index * step, 0));
            }
            return false;
        });
    }

    // ---- parent view: app picker --------------------------------------------

    private View buildParentView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(0xf5, 0xf5, 0xf5));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(32), dp(32), dp(32), dp(32));
        scroll.addView(list);

        TextView title = text("Parent mode", 32, Color.BLACK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);
        list.addView(text("Switch on the apps that should appear in the kid's carousel. "
                + "Install new apps from the Play Store. The kid's session always starts locked, "
                + "so just close this window or log out when you're done.", 18, Color.DKGRAY));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(16), 0, dp(16));
        buttons.addView(button("Play Store", v -> launch("com.android.vending")));
        buttons.addView(button("Android settings", v -> {
            Intent settings = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settings);
        }));
        list.addView(buttons);
        list.addView(volumeControl());

        PackageManager pm = getPackageManager();
        Set<String> approved = approved();
        for (ResolveInfo ri : launchableApps()) {
            String pkg = ri.activityInfo.packageName;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(sharpIcon(ri));
            row.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView label = text(ri.loadLabel(pm).toString(), 20, Color.BLACK);
            label.setPadding(dp(16), 0, dp(16), 0);
            label.setOnClickListener(v -> launch(pkg));
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            Switch toggle = new Switch(this);
            toggle.setText("In carousel  ");
            toggle.setChecked(approved.contains(pkg));
            toggle.setOnCheckedChangeListener((b, on) -> setApproved(pkg, on));
            row.addView(toggle);
            list.addView(row);
        }
        return scroll;
    }

    // ---- tap feedback and volume ------------------------------------------------

    private SoundPool sounds;
    private int popSound;

    /** A toddler needs to see and hear that a tap worked: squash-and-bounce plus a pop. */
    private void loadSounds() {
        // Media usage, so the pop follows the kid volume the parent set.
        sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build()).build();
        popSound = sounds.load(this, R.raw.pop, 1);
    }

    private void tapFeedback(View v, Runnable then) {
        sounds.play(popSound, 1f, 1f, 1, 0, 1f);
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new OvershootInterpolator(3f))
                        .withEndAction(then).start()).start();
    }

    static final String KEY_VOLUME = "kid_volume";  // percent of the media stream's max
    /** Until the parent moves the slider. Full: the Dell's desktop speaker is quiet. */
    static final int DEFAULT_KID_VOLUME = 100;

    /** Kid mode: media volume back at the parent's level each time home is shown. */
    private void applyKidVolume() {
        applyVolume(this, prefs.getInt(KEY_VOLUME, DEFAULT_KID_VOLUME));
    }

    /**
     * Sets the media (and system-sound) volume to percent of its max. Also used
     * by VolumeReceiver (web portal). The kid has no volume keys or volume panel
     * to change it with (touch only, system bars hidden).
     *
     * Not DISALLOW_ADJUST_VOLUME: Android enforces that restriction by muting
     * all sound (AudioFlinger master mute), which silenced the whole tablet.
     */
    static void applyVolume(Context context, int percent) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm.isDeviceOwnerApp(context.getPackageName())) {
            // Older Tablet Home versions set it: lift it (and the mute with it).
            dpm.clearUserRestriction(new ComponentName(context, AdminReceiver.class),
                    UserManager.DISALLOW_ADJUST_VOLUME);
        }
        AudioManager audio = context.getSystemService(AudioManager.class);
        for (int stream : new int[] {AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM}) {
            int streamMax = audio.getStreamMaxVolume(stream);
            audio.setStreamVolume(stream, Math.round(streamMax * percent / 100f), 0);
        }
        Log.i(TAG, "kid volume " + percent + "%");
    }

    private View volumeControl() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(16));
        int percent = prefs.getInt(KEY_VOLUME, DEFAULT_KID_VOLUME);
        TextView label = text("Kid volume: " + percent + "%  (the kid can't change it)", 18, Color.BLACK);
        box.addView(label);
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(percent);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                label.setText("Kid volume: " + value + "%  (the kid can't change it)");
                AudioManager audio = getSystemService(AudioManager.class);
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * value / 100f),
                        AudioManager.FLAG_PLAY_SOUND);  // a preview beep at the new level
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(KEY_VOLUME, s.getProgress()).commit();
            }
        });
        box.addView(slider);
        return box;
    }

    // ---- small view helpers ---------------------------------------------------

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
        b.setOnClickListener(onClick);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
