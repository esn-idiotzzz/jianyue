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
 * A direct snapshot of the page being left. Back gestures reveal it in the gap around the
 * shrinking page, so the destination is visible before the finger is released.
 *
 * <p>The snapshot is kept at half resolution: it is sharp enough to read the destination and is
 * scaled back up with bilinear filtering. The preview itself never scales with the gesture; only
 * the current page above it does.
 */
public final class BackPreview {
    private static final ArrayDeque<Bitmap> stack = new ArrayDeque<>();
    private static final int MAX_DEPTH = 4;
    private static ColorDrawable scrim;

    private BackPreview() {
    }

    /** Snapshot the page before opening a child page or entering a folder. */
    public static void capture(View root) {
        if (root == null) return;
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        try {
            int sampleWidth = Math.max(32, width / 2);
            int sampleHeight = Math.max(32, height / 2);
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
        Window window = activity.getWindow();
        if (window == null) return false;
        BitmapDrawable image = new BitmapDrawable(activity.getResources(), preview);
        image.setGravity(Gravity.FILL);
        image.setFilterBitmap(true);
        // The destination is shown directly; only a gray scrim is layered over it.
        scrim = new ColorDrawable(0x00000000);
        LayerDrawable layers = new LayerDrawable(new Drawable[] { image, scrim });
        window.setBackgroundDrawable(layers);
        return true;
    }

    /** Dim the previous page while the current page is being dragged away. */
    public static void setScrimAlpha(float progress){
        if(scrim==null)return;
        int alpha=(int)(0x55f*Math.max(0f,Math.min(1f,progress)));
        scrim.setColor(alpha<<24);
    }
    /** Drop the snapshot for the page that just finished. */
    public static void pop() {
        stack.poll();
    }

    public static void clear() {
        stack.clear();
    }
}
