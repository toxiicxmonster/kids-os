package org.tabletos.dance;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A big scroll bar for small fingers, down the right side of a scrolling list:
 * a ▲ button, a track with a handle, and a ▼ button. Scrolling with it never
 * lands on a list item, so there's nothing to pick by accident. The arrows
 * move about a screenful (smoothly, so the kid sees where things went); the
 * track jumps or drags to a spot. It hides itself when everything fits.
 * (The same file is in the movies and dance apps, each in its own package.)
 */
final class ScrollRail extends LinearLayout {
    interface Feedback {
        void tap(View v, Runnable then);
    }

    static final int WIDTH_DP = 88;

    private final ScrollView scroller;
    private final TextView up;
    private final TextView down;
    private final FrameLayout track;
    private final View handle;

    ScrollRail(Context context, ScrollView scroller, int color, Feedback feedback) {
        super(context);
        this.scroller = scroller;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        up = arrow("▲", color);
        down = arrow("▼", color);
        up.setContentDescription("Scroll up");
        down.setContentDescription("Scroll down");
        up.setOnClickListener(v -> feedback.tap(v, () -> page(-1)));
        down.setOnClickListener(v -> feedback.tap(v, () -> page(1)));

        track = new FrameLayout(context);
        GradientDrawable groove = new GradientDrawable();
        groove.setColor(Color.argb(170, 255, 255, 255));
        groove.setCornerRadius(dp(22));
        groove.setStroke(dp(3), color);
        track.setBackground(groove);
        handle = new View(context);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(color);
        thumb.setCornerRadius(dp(18));
        handle.setBackground(thumb);
        track.addView(handle, new FrameLayout.LayoutParams(dp(36), dp(80), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        track.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                jumpTo(ev.getY());
                return true;
            }
            return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        });

        int size = dp(WIDTH_DP);
        addView(up, new LayoutParams(size, size));
        LayoutParams trackLp = new LayoutParams(dp(48), 0, 1f);
        trackLp.topMargin = dp(14);
        trackLp.bottomMargin = dp(14);
        addView(track, trackLp);
        addView(down, new LayoutParams(size, size));

        scroller.setOnScrollChangeListener((v, x, y, oldX, oldY) -> refresh());
        scroller.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> post(this::refresh));
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> post(this::refresh));
        setVisibility(INVISIBLE);  // until the list has been measured
    }

    /** How far the list can scroll, in pixels (0: it all fits). */
    private int range() {
        if (scroller.getChildCount() == 0) {
            return 0;
        }
        return Math.max(0, scroller.getChildAt(0).getHeight() - scroller.getHeight());
    }

    private void page(int direction) {
        scroller.smoothScrollBy(0, direction * scroller.getHeight() * 3 / 4);
    }

    private void jumpTo(float y) {
        int free = track.getHeight() - handle.getHeight();
        if (free <= 0) {
            return;
        }
        float fraction = (y - handle.getHeight() / 2f) / free;
        scroller.scrollTo(0, Math.round(Math.max(0f, Math.min(1f, fraction)) * range()));
    }

    private void refresh() {
        int range = range();
        setVisibility(range > 0 ? VISIBLE : GONE);
        if (range == 0) {
            return;
        }
        int y = scroller.getScrollY();
        int trackH = track.getHeight();
        // The handle's size shows how much of the list is on screen.
        int total = scroller.getChildAt(0).getHeight();
        int handleH = Math.max(dp(80), trackH * scroller.getHeight() / Math.max(1, total));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) handle.getLayoutParams();
        if (lp.height != handleH) {
            lp.height = handleH;
            handle.setLayoutParams(lp);
        }
        handle.setTranslationY((trackH - handleH) * (float) y / range);
        // A dimmed arrow means "nothing more that way".
        up.setAlpha(y > 0 ? 1f : 0.35f);
        down.setAlpha(y < range ? 1f : 0.35f);
    }

    private TextView arrow(String glyph, int color) {
        TextView v = new TextView(getContext());
        v.setText(glyph);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        circle.setStroke(dp(5), color);
        v.setBackground(circle);
        v.setElevation(dp(10));
        return v;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
