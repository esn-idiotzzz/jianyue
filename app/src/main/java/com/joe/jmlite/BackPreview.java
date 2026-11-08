package com.joe.jmlite;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.animation.DecelerateInterpolator;
import android.animation.ValueAnimator;
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
    private static final int MAX_DEPTH = 3;
    private static ImageView previewView;
    private static View scrimView;
    private static Activity host;private static ValueAnimator scrimFade;

    private BackPreview() {
    }

    /** Snapshot the page before opening a child page or entering a folder. */
    public static void capture(View root) {
        capture(root,null);
    }

    /** The capsule belongs to the live destination animation, not its frozen preview. */
    public static void capture(View root,View excluded) {
        if (root == null) return;
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        float alpha=excluded==null?1f:excluded.getAlpha();
        try {
            int sampleWidth = Math.max(32, width);
            int sampleHeight = Math.max(32, height);
            Bitmap sample = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sample);
            canvas.scale(sampleWidth / (float) width, sampleHeight / (float) height);
            if(excluded!=null)excluded.setAlpha(0f);
            try{root.draw(canvas);}finally{if(excluded!=null)excluded.setAlpha(alpha);}
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
        if(decor.getChildAt(0)!=previewView){decor.removeView(previewView);decor.addView(previewView,0);}
        previewView.clearAnimation();previewView.animate().cancel();previewView.setImageBitmap(preview);previewView.setTranslationX(0f);
        previewView.setVisibility(View.GONE);
        scrimView.setVisibility(View.GONE);
        scrimView.setAlpha(0f);
        return true;
    }

    /** Show the preview only for the duration of an active back gesture. */
    public static void show(){if(previewView==null)return;cancelScrimFade();previewView.setVisibility(View.VISIBLE);if(scrimView!=null){scrimView.setVisibility(View.VISIBLE);scrimView.setAlpha(0f);}}
    static View buttonTarget(Activity activity){
        if(host!=activity||previewView==null)throw new IllegalStateException("Install the destination preview first");
        show();previewView.clearAnimation();
        return previewView;
    }
    public static void hide(){cancelScrimFade();if(previewView!=null){previewView.animate().cancel();previewView.clearAnimation();previewView.setVisibility(View.GONE);}if(scrimView!=null)scrimView.setVisibility(View.GONE);}
    /** Parallax the destination slightly away from the edge during the gesture. */
    public static void setPreviewShift(float dx){if(previewView!=null)previewView.setTranslationX(dx);}
    /** Elegantly return the lightly shifted destination to its resting position. */
    public static void settlePreview(long duration){
        if(previewView==null)return;
        previewView.animate().translationX(0f).setDuration(duration).setInterpolator(new DecelerateInterpolator()).start();
    }
    /** Dim the previous page while the current page is being dragged away. */
    public static void setScrimAlpha(float progress) {
        if (scrimView == null) return;
        cancelScrimFade();
        scrimView.setAlpha(0.33f * Math.max(0f, Math.min(1f, progress)));
    }
    /** Fade the dim layer instead of dropping it in one frame at release. */
    public static void fadeScrim(long duration){
        if(scrimView==null||scrimView.getVisibility()!=View.VISIBLE)return;
        cancelScrimFade();
        float from=scrimView.getAlpha();
        ValueAnimator animation=ValueAnimator.ofFloat(from,0f);
        scrimFade=animation;animation.setDuration(duration);
        animation.addUpdateListener(a->{if(scrimFade==animation)scrimView.setAlpha((float)a.getAnimatedValue());});
        animation.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){if(scrimFade==animation)scrimFade=null;}});
        animation.start();
    }
    private static void cancelScrimFade(){if(scrimFade!=null){scrimFade.cancel();scrimFade=null;}}

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
