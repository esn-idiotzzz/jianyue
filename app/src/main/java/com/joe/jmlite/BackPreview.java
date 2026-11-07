package com.joe.jmlite;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import java.util.ArrayDeque;

/**
 * A small, softly blurred snapshot of the page being left. Back gestures reveal it in the gap
 * around the sliding page, so the destination is visible before the finger is released.
 *
 * <p>The snapshot is intentionally tiny (one sixth of the screen); scaling it back up with
 * filtering gives a light blur without a RenderScript pass or a full-size bitmap in memory.
 */
public final class BackPreview {
    private static final ArrayDeque<Bitmap> stack = new ArrayDeque<>();
    private static final int MAX_DEPTH = 4;

    private BackPreview() {
    }

    /** Snapshot the page before opening a child page or entering a folder. */
    public static void capture(View root) {
        if (root == null) return;
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        try {
            int sampleWidth = Math.max(24, width / 6);
            int sampleHeight = Math.max(24, height / 6);
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

    /** Put the latest cached snapshot behind the page; the sliding page reveals it. */
    public static boolean install(Activity activity) {
        Bitmap preview = stack.peek();
        if (preview == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        Window window = activity.getWindow();
        if (window == null) return false;
        BitmapDrawable image = new BitmapDrawable(activity.getResources(), preview);
        image.setGravity(Gravity.FILL);
        image.setFilterBitmap(true);
        // A light scrim keeps the destination readable but clearly behind the current page.
        LayerDrawable layers = new LayerDrawable(new Drawable[] { image, new ColorDrawable(0x33000000) });
        window.setBackgroundDrawable(layers);
        return true;
    }

    /** Drop the snapshot for the page that just finished. */
    public static void pop() {
        stack.poll();
    }

    public static void clear() {
        stack.clear();
    }
}
