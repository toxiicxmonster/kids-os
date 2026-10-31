package org.tabletos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The kid's theme: one of the backgrounds built into the app (R.drawable.theme_<name>,
 * from setup/files/themes). Each kid's choice is kept on the Linux side by the
 * Movies & TV bridge, which knows which kid is logged in, so the parent's Users
 * app can see and change it too.
 */
final class Themes {
    static final String[] NAMES = {
        "sky", "space", "sea", "dino", "trucks", "princess", "unicorn", "fairy", "mermaid", "sweets"};
    static final String DEFAULT = "sky";
    private static final String TAG = "TabletHome";
    private static final String BRIDGE = "http://192.168.240.1:8766/api";
    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Themes() {
    }

    static int drawable(Context context, String name) {
        int id = context.getResources().getIdentifier("theme_" + name, "drawable", context.getPackageName());
        return id != 0 ? id : drawable(context, DEFAULT);
    }

    /** A small copy for the picker (the full ones are 1920x1080). */
    static Bitmap thumbnail(Context context, String name) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 4;
        return BitmapFactory.decodeResource(context.getResources(), drawable(context, name), options);
    }

    /** The logged-in kid's theme from the bridge; delivered on the main thread (null if unreachable). */
    static void fetch(Consumer<String> done) {
        io.execute(() -> {
            String theme = null;
            try {
                String body = request("GET", "/theme");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"theme\":\\s*\"(\\w+)\"").matcher(body);
                theme = m.find() ? m.group(1) : null;
            } catch (Exception e) {
                Log.w(TAG, "can't get the theme", e);
            }
            String result = theme;
            ui.post(() -> done.accept(result));
        });
    }

    static void save(String name) {
        io.execute(() -> {
            try {
                request("POST", "/theme/" + name);
            } catch (Exception e) {
                Log.w(TAG, "can't save the theme", e);
            }
        });
    }

    /**
     * May the kid use the tablet now (their bedtime and daily limit, set by the
     * parents)? Delivers [allowed, message] on the main thread; allowed if the
     * bridge can't be reached, so a hiccup never locks the kid out.
     */
    static void allowed(java.util.function.BiConsumer<Boolean, String> done) {
        io.execute(() -> {
            boolean allowed = true;
            String message = "";
            try {
                org.json.JSONObject r = new org.json.JSONObject(request("GET", "/allowed"));
                allowed = r.optBoolean("allowed", true);
                message = r.optString("message", "");
            } catch (Exception e) {
                Log.w(TAG, "can't check tablet time", e);
            }
            boolean a = allowed;
            String m = message;
            ui.post(() -> done.accept(a, m));
        });
    }

    /** For the parents' history: the kid opened this app. */
    static void report(String title) {
        io.execute(() -> {
            try {
                request("POST", "/activity?title=" + java.net.URLEncoder.encode(title, "UTF-8"));
            } catch (Exception e) {
                Log.w(TAG, "can't report " + title, e);
            }
        });
    }

    private static String request(String method, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BRIDGE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; in != null && (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            if (c.getResponseCode() >= 400) {
                throw new java.io.IOException("HTTP " + c.getResponseCode() + ": " + out);
            }
            return out.toString("UTF-8");
        }
    }
}
