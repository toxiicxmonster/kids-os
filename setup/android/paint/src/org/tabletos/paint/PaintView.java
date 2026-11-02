package org.tabletos.paint;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;

/**
 * The paper: a coloring page (or blank paper) the kid paints on.
 *
 * Two layers: the kid's paint underneath, and the page's black lines on top,
 * so neither filling nor brushing can ever cover a line. "Fill" colors the
 * whole area a finger lands in (stopping at the lines); "brush" paints with a
 * fat round brush. Every change can be undone, a few steps back.
 */
final class PaintView extends View {
    enum Tool { FILL, BRUSH }

    private static final int UNDO_STEPS = 8;

    private Bitmap paint;       // the kid's colors (opaque)
    private Bitmap lines;       // the page's lines, transparent elsewhere
    private boolean[] wall;     // where the lines are: fills stop there
    private int w, h;
    private final Matrix toView = new Matrix();
    private final Matrix toPage = new Matrix();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path stroke = new Path();
    private final ArrayDeque<Bitmap> undo = new ArrayDeque<>();
    private Tool tool = Tool.FILL;
    private int color = Color.RED;
    private float lastX, lastY;
    private boolean changed;
    private Runnable onChange = () -> { };

    PaintView(Context context) {
        super(context);
        brush.setStyle(Paint.Style.STROKE);
        brush.setStrokeCap(Paint.Cap.ROUND);
        brush.setStrokeJoin(Paint.Join.ROUND);
    }

    /** A new page: its line art (black on white; null for blank paper), and the kid's earlier paint, if any. */
    void setPage(Bitmap art, int blankW, int blankH, Bitmap saved) {
        w = art != null ? art.getWidth() : blankW;
        h = art != null ? art.getHeight() : blankH;
        wall = new boolean[w * h];
        int[] px = new int[w * h];
        if (art != null) {
            art.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                // Dark and opaque = a line; everything else is paper.
                boolean line = Color.alpha(c) > 128
                        && (Color.red(c) + Color.green(c) + Color.blue(c)) < 3 * 128;
                wall[i] = line;
                px[i] = line ? Color.BLACK : Color.TRANSPARENT;
            }
        }
        lines = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        paint = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (saved != null && saved.getWidth() == w && saved.getHeight() == h) {
            new Canvas(paint).drawBitmap(saved, 0, 0, null);
        } else {
            paint.eraseColor(Color.WHITE);
        }
        undo.clear();
        changed = false;
        fit();
        invalidate();
        onChange.run();
    }

    boolean hasPage() {
        return paint != null;
    }

    Bitmap paintLayer() {
        return paint;
    }

    boolean isChanged() {
        return changed;
    }

    void setTool(Tool t) {
        tool = t;
    }

    void setColor(int c) {
        color = c;
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    void setOnChange(Runnable r) {
        onChange = r;
    }

    void undo() {
        Bitmap last = undo.pollLast();
        if (last == null) {
            return;
        }
        paint = last;
        changed = true;
        invalidate();
        onChange.run();
    }

    /** Back to clean paper (can be undone). */
    void wipe() {
        if (paint == null) {
            return;
        }
        remember();
        paint.eraseColor(Color.WHITE);
        changed = true;
        invalidate();
        onChange.run();
    }

    private void remember() {
        undo.addLast(paint.copy(Bitmap.Config.ARGB_8888, true));
        while (undo.size() > UNDO_STEPS) {
            undo.pollFirst().recycle();
        }
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        fit();
    }

    /** The page as big as it fits, centered. */
    private void fit() {
        if (w == 0 || getWidth() == 0) {
            return;
        }
        float scale = Math.min(getWidth() / (float) w, getHeight() / (float) h);
        toView.reset();
        toView.setScale(scale, scale);
        toView.postTranslate((getWidth() - w * scale) / 2f, (getHeight() - h * scale) / 2f);
        toView.invert(toPage);
        // About a fingertip wide on the screen, whatever the page's size.
        brush.setStrokeWidth(28f * getResources().getDisplayMetrics().density / scale);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (paint == null) {
            return;
        }
        RectF page = new RectF(0, 0, w, h);
        toView.mapRect(page);
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        canvas.drawRect(page, white);
        canvas.drawBitmap(paint, toView, bitmapPaint);
        canvas.drawBitmap(lines, toView, bitmapPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (paint == null) {
            return false;
        }
        float[] p = {ev.getX(), ev.getY()};
        toPage.mapPoints(p);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                if (tool == Tool.FILL) {
                    fill(Math.round(p[0]), Math.round(p[1]));
                } else {
                    remember();
                    stroke.reset();
                    stroke.moveTo(p[0], p[1]);
                    stroke.lineTo(p[0] + 0.1f, p[1]);  // a dot for a tap
                    lastX = p[0];
                    lastY = p[1];
                    drawStroke();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tool == Tool.BRUSH) {
                    stroke.reset();
                    stroke.moveTo(lastX, lastY);
                    for (int i = 0; i < ev.getHistorySize(); i++) {
                        float[] hp = {ev.getHistoricalX(i), ev.getHistoricalY(i)};
                        toPage.mapPoints(hp);
                        stroke.lineTo(hp[0], hp[1]);
                    }
                    stroke.lineTo(p[0], p[1]);
                    lastX = p[0];
                    lastY = p[1];
                    drawStroke();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    private void drawStroke() {
        brush.setColor(color);
        new Canvas(paint).drawPath(stroke, brush);
        changed = true;
        invalidate();
        onChange.run();
    }

    /** Color the area at (x, y) up to the lines (and up to other colors already there). */
    private void fill(int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return;
        }
        int[] px = new int[w * h];
        paint.getPixels(px, 0, w, 0, 0, w, h);
        int seed = y * w + x;
        if (wall[seed]) {
            // On a line: use the nearest paper within a few pixels instead.
            seed = nearestPaper(x, y);
            if (seed < 0) {
                return;
            }
        }
        int target = px[seed];
        int with = color | 0xff000000;
        if (target == with) {
            return;
        }
        remember();
        // Scanline flood fill.
        int[] stack = new int[w * 4 + 64];
        int top = 0;
        stack[top++] = seed;
        while (top > 0) {
            int i = stack[--top];
            int sy = i / w;
            int sx = i % w;
            int left = sx;
            while (left > 0 && open(px, sy * w + left - 1, target)) {
                left--;
            }
            int right = sx;
            while (right < w - 1 && open(px, sy * w + right + 1, target)) {
                right++;
            }
            for (int cx = left; cx <= right; cx++) {
                px[sy * w + cx] = with;
            }
            for (int ny = sy - 1; ny <= sy + 1; ny += 2) {
                if (ny < 0 || ny >= h) {
                    continue;
                }
                boolean inRun = false;
                for (int cx = left; cx <= right; cx++) {
                    boolean o = open(px, ny * w + cx, target);
                    if (o && !inRun) {
                        if (top == stack.length) {
                            stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                        }
                        stack[top++] = ny * w + cx;
                    }
                    inRun = o;
                }
            }
        }
        paint.setPixels(px, 0, w, 0, 0, w, h);
        changed = true;
        invalidate();
        onChange.run();
    }

    private boolean open(int[] px, int i, int target) {
        return !wall[i] && px[i] == target;
    }

    private int nearestPaper(int x, int y) {
        for (int r = 1; r <= 12; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx >= 0 && ny >= 0 && nx < w && ny < h && !wall[ny * w + nx]) {
                        return ny * w + nx;
                    }
                }
            }
        }
        return -1;
    }
}
