package com.joe.jmlite;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.util.ArrayDeque;

/**
 * A direct snapshot of the page being left. Back gestures reveal it in the gap around the
 * shrinking page, so the destination is visible before the finger is released.
 *
 * <p>The snapshot is kept at three quarter resolution: it is sharp enough to read the destination
 * and is scaled with bilinear filtering. It is installed as a view behind the page content (not
 * as the window background) so the preview is composited stably on every frame; the preview
 * itself never scales with the gesture.
 */
public final class BackPreview {
    private static final ArrayDeque<Bitmap> stack = new ArrayDeque<>();
    private static final int MAX_DEPTH = 4;
    private static ImageView previewView;
    private static View scrimView;
    private static Activity host;

    private BackPreview() {
    }

    /** Snapshot the page before opening a child page or entering a folder. */
    public static void capture(View root) {
        if (root == null) return;
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        try {
            int sampleWidth = Math.max(32, width * 3 / 4);
            int sampleHeight = Math.max(32, height * 3 / 4);
            Bitmap sample = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sample);
            canvas.scale(sampleWidth / (float) width, sampleHeight / (float) height);
            root.draw(canvas);
            while (stack.size() >= MAX_DEPTH) stack.removeLast();
            stack.push(sample);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // The back gesture still works; only the optional preview is lost.
        }
    }

    /** Put the latest cached snapshot behind the page; the shrinking page reveals it. */
    public static boolean install(Activity activity) {
        Bitmap preview = stack.peek();
        if (preview == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (decor == null) return false;
        if (host != activity || previewView == null || previewView.getParent() != decor) {
            removeViews();
            host = activity;
            previewView = new ImageView(activity);
            previewView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            scrimView = new View(activity);
            scrimView.setBackgroundColor(Color.BLACK);
            scrimView.setAlpha(0f);
            FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            decor.addView(previewView, 0, match);
            decor.addView(scrimView, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        previewView.setImageBitmap(preview);
        previewView.setVisibility(View.VISIBLE);
        scrimView.setVisibility(View.VISIBLE);
        scrimView.setAlpha(0f);
        return true;
    }

    /** Dim the previous page while the current page is being dragged away. */
    public static void setScrimAlpha(float progress) {
        if (scrimView == null) return;
        scrimView.setAlpha(0.33f * Math.max(0f, Math.min(1f, progress)));
    }

    /** Drop the snapshot for the page that just finished. */
    public static void pop() {
        stack.poll();
    }

    public static void clear() {
        stack.clear();
    }

    private static void removeViews() {
        if (previewView != null && previewView.getParent() instanceof ViewGroup) {
            ((ViewGroup) previewView.getParent()).removeView(previewView);
        }
        if (scrimView != null && scrimView.getParent() instanceof ViewGroup) {
            ((ViewGroup) scrimView.getParent()).removeView(scrimView);
        }
        previewView = null;
        scrimView = null;
        host = null;
    }
}
