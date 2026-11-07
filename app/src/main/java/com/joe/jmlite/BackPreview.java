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
 * A soft, Gaussian-blurred snapshot of the page being left. Back gestures reveal it in the gap
 * around the sliding page, so the destination is visible before the finger is released.
 *
 * <p>The snapshot is captured at a quarter resolution and blurred with three separable box passes
 * (a close approximation of a Gaussian kernel) before it is scaled back up with bilinear
 * filtering. This keeps the cost low and avoids the blocky, mosaic-looking result of simply
 * stretching a tiny bitmap.
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
            int sampleWidth = Math.max(32, width / 4);
            int sampleHeight = Math.max(32, height / 4);
            Bitmap sample = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sample);
            canvas.scale(sampleWidth / (float) width, sampleHeight / (float) height);
            root.draw(canvas);
            Bitmap blurred = gaussian(sample);
            sample.recycle();
            while (stack.size() >= MAX_DEPTH) stack.removeLast();
            stack.push(blurred);
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

    /** Three separable box passes approximate a Gaussian blur closely and run in linear time. */
    private static Bitmap gaussian(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int[] pixels = new int[width * height];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        int radius = Math.max(4, Math.min(width, height) / 24);
        int[] scratch = new int[pixels.length];
        for (int pass = 0; pass < 3; pass++) boxPass(pixels, scratch, width, height, radius);
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    /** One horizontal + vertical box pass; the result is left back in {@code pixels}. */
    private static void boxPass(int[] pixels, int[] scratch, int width, int height, int radius) {
        int divisor = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int alpha = 0, red = 0, green = 0, blue = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = pixels[row + clamp(i, width)];
                alpha += color >>> 24;
                red += (color >> 16) & 0xff;
                green += (color >> 8) & 0xff;
                blue += color & 0xff;
            }
            for (int x = 0; x < width; x++) {
                scratch[row + x] = ((alpha / divisor) << 24)
                        | ((red / divisor) << 16)
                        | ((green / divisor) << 8)
                        | (blue / divisor);
                int outgoing = pixels[row + clamp(x - radius, width)];
                int incoming = pixels[row + clamp(x + radius + 1, width)];
                alpha += (incoming >>> 24) - (outgoing >>> 24);
                red += ((incoming >> 16) & 0xff) - ((outgoing >> 16) & 0xff);
                green += ((incoming >> 8) & 0xff) - ((outgoing >> 8) & 0xff);
                blue += (incoming & 0xff) - (outgoing & 0xff);
            }
        }
        for (int x = 0; x < width; x++) {
            int alpha = 0, red = 0, green = 0, blue = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = scratch[clamp(i, height) * width + x];
                alpha += color >>> 24;
                red += (color >> 16) & 0xff;
                green += (color >> 8) & 0xff;
                blue += color & 0xff;
            }
            for (int y = 0; y < height; y++) {
                pixels[y * width + x] = ((alpha / divisor) << 24)
                        | ((red / divisor) << 16)
                        | ((green / divisor) << 8)
                        | (blue / divisor);
                int outgoing = scratch[clamp(y - radius, height) * width + x];
                int incoming = scratch[clamp(y + radius + 1, height) * width + x];
                alpha += (incoming >>> 24) - (outgoing >>> 24);
                red += ((incoming >> 16) & 0xff) - ((outgoing >> 16) & 0xff);
                green += ((incoming >> 8) & 0xff) - ((outgoing >> 8) & 0xff);
                blue += (incoming & 0xff) - (outgoing & 0xff);
            }
        }
    }

    private static int clamp(int value, int limit) {
        return value < 0 ? 0 : value >= limit ? limit - 1 : value;
    }
}
