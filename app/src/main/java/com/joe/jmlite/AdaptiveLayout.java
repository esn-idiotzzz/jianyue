package com.joe.jmlite;

import android.app.Activity;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

/**
 * Window-driven layout math shared by the phone and tablet screens.
 *
 * <p>Every decision is based on the current application window width in dp, never on the device
 * model, the physical resolution or a DPI threshold. Windows narrower than
 * {@link #TABLET_MIN_WIDTH_DP} keep the original phone rules exactly; wider windows derive the
 * column count from a target cover width and keep every cover inside a minimum/maximum range.
 *
 * <p>The arithmetic methods deliberately contain no Android calls so the JVM regression runner
 * can verify the breakpoints and cover bounds of every window size.
 */
public final class AdaptiveLayout {
    /** Android's medium window-size-class breakpoint: the phone layout is kept below it. */
    public static final int TABLET_MIN_WIDTH_DP = 600;

    /** Original phone shelf rule: 3 to 6 columns, one column per 120dp of width. */
    public static final int PHONE_SHELF_MIN_COLUMNS = 3;
    public static final int PHONE_SHELF_MAX_COLUMNS = 6;
    public static final int PHONE_SHELF_STEP_DP = 120;

    /** Shelf grid metrics: 18dp page margins, 10dp horizontal gaps, 16dp vertical gaps. */
    public static final int SHELF_PAGE_PADDING_DP = 18;
    public static final int SHELF_SPACING_DP = 10;
    public static final int SHELF_VERTICAL_SPACING_DP = 16;
    public static final int SHELF_TARGET_COVER_DP = 132;
    public static final int SHELF_MIN_COVER_DP = 104;
    public static final int SHELF_MAX_COVER_DP = 168;
    /** Cover height divided by cover width: the ratio the phone shelf has always used. */
    public static final float SHELF_COVER_RATIO = 1.40f;

    /** Search results keep their 3 columns and 138dp covers on phones. */
    public static final int PHONE_SEARCH_COLUMNS = 3;
    public static final int PHONE_SEARCH_COVER_DP = 138;
    public static final int SEARCH_PAGE_PADDING_DP = 18;
    public static final int SEARCH_SPACING_DP = 10;
    public static final int SEARCH_TARGET_COVER_DP = 132;
    public static final int SEARCH_MIN_COVER_DP = 104;
    public static final int SEARCH_MAX_COVER_DP = 176;
    /** Phone search covers are roughly 1.36 times taller than a 101dp column. */
    public static final float SEARCH_COVER_RATIO = 1.36f;

    /** Maximum content widths for single-column pages on very wide windows. */
    public static final int DOWNLOAD_LIST_MAX_WIDTH_DP = 720;
    public static final int RECORDS_LIST_MAX_WIDTH_DP = 720;
    public static final int REPOSITORY_LIST_MAX_WIDTH_DP = 760;
    public static final int MANGA_DETAIL_MAX_WIDTH_DP = 720;
    public static final int BOOK_DETAIL_MAX_WIDTH_DP = 560;

    private AdaptiveLayout() {
    }

    /** True when the current window is wide enough for the tablet layout. */
    public static boolean isTabletWidth(int widthDp) {
        return widthDp >= TABLET_MIN_WIDTH_DP;
    }

    /**
     * True for a tablet-class device: the smallest available width is at least 600dp. A phone in
     * landscape can be wider than 600dp but keeps its original phone layout; a tablet in
     * split-screen is still a tablet but follows the narrower window width.
     */
    public static boolean isTabletDevice(android.content.Context context) {
        return context.getResources().getConfiguration().smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP;
    }

    /** The exact phone shelf rule, used for every window below the tablet breakpoint. */
    public static int phoneShelfColumns(int widthDp) {
        if (widthDp <= 0) return PHONE_SHELF_MIN_COLUMNS;
        return Math.max(PHONE_SHELF_MIN_COLUMNS,
                Math.min(PHONE_SHELF_MAX_COLUMNS, widthDp / PHONE_SHELF_STEP_DP));
    }

    /**
     * Column count for the bookshelf and history grids. Phone-class devices keep the original
     * phone rule at every window width (the rule uses the window width, exactly like the old
     * {@code screenWidthDp / 120} formula); tablet windows below 600dp do the same, and wider
     * tablet windows derive the column count from their real content width.
     */
    public static int shelfColumns(int windowWidthDp, int contentWidthDp, boolean tabletDevice) {
        if (!tabletDevice || !isTabletWidth(windowWidthDp)) return phoneShelfColumns(windowWidthDp);
        int content = contentWidthDp > 0 ? contentWidthDp : windowWidthDp - SHELF_PAGE_PADDING_DP * 2;
        return columnsFor(content, SHELF_SPACING_DP,
                SHELF_TARGET_COVER_DP, SHELF_MIN_COVER_DP, SHELF_MAX_COVER_DP);
    }

    /** Column count for the online search result grid, with the same device/window rules. */
    public static int searchColumns(int windowWidthDp, int contentWidthDp, boolean tabletDevice) {
        if (!tabletDevice || !isTabletWidth(windowWidthDp)) return PHONE_SEARCH_COLUMNS;
        int content = contentWidthDp > 0 ? contentWidthDp : windowWidthDp - SEARCH_PAGE_PADDING_DP * 2;
        return columnsFor(content, SEARCH_SPACING_DP,
                SEARCH_TARGET_COVER_DP, SEARCH_MIN_COVER_DP, SEARCH_MAX_COVER_DP);
    }

    /**
     * Chooses the column count closest to {@code targetCoverDp}, then clamps it so that the real
     * cover width stays between {@code minCoverDp} and {@code maxCoverDp}. The bounds also keep
     * the result positive for tiny or unexpected window widths.
     */
    public static int columnsFor(int availableDp, int spacingDp, int targetCoverDp,
                                 int minCoverDp, int maxCoverDp) {
        if (availableDp <= 0) return 1;
        int spacing = Math.max(0, spacingDp);
        int minCover = Math.max(1, minCoverDp);
        int maxCover = Math.max(minCover, maxCoverDp);
        int target = Math.max(minCover, Math.min(maxCover, targetCoverDp));
        int span = availableDp + spacing;
        int lower = (int) Math.ceil(span / (double) (maxCover + spacing));
        int upper = (int) Math.floor(span / (double) (minCover + spacing));
        lower = Math.max(1, lower);
        upper = Math.max(lower, upper);
        int wanted = (int) Math.round(Math.max(1, span) / (double) (target + spacing));
        return Math.max(lower, Math.min(upper, Math.max(1, wanted)));
    }

    /** Cover width produced by a column count, using integer pixels like GridView does. */
    public static int coverWidthDp(int availableDp, int spacingDp, int columns) {
        int count = Math.max(1, columns);
        int width = (availableDp - spacingDp * (count - 1)) / count;
        return Math.max(0, width);
    }

    /** Cover height for a shelf tile: the phone's 1.40 height/width ratio, never zero. */
    public static int shelfCoverHeightPx(int coverWidthPx) {
        return Math.max(1, (int) (Math.max(1, coverWidthPx) * SHELF_COVER_RATIO));
    }

    /** Cover height for a search result tile: the phone's 1.36 height/width ratio, never zero. */
    public static int searchCoverHeightPx(int coverWidthPx) {
        return Math.max(1, (int) (Math.max(1, coverWidthPx) * SEARCH_COVER_RATIO));
    }

    /** Extra horizontal inset per side that centres content inside a wider window. */
    public static int centeredInsetPx(int windowWidthPx, int basePaddingPx, int maxContentWidthPx) {
        int extra = windowWidthPx - basePaddingPx * 2 - maxContentWidthPx;
        return Math.max(0, extra / 2);
    }

    /**
     * Keeps a single-column page readable on wide windows by growing its left/right padding once
     * the window is wider than {@code maxContentWidthDp}. Phone widths keep the original padding.
     */
    public static void capContentWidth(View view, int basePaddingDp, int maxContentWidthDp) {
        if (view == null) return;
        // Phone-class devices keep their original edge-to-edge rows at every window width; only
        // tablet windows (including split-screen) narrow the content on very wide layouts.
        if (!isTabletDevice(view.getContext())) return;
        view.addOnLayoutChangeListener((v, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            if (width <= 0) return;
            float density = v.getResources().getDisplayMetrics().density;
            int base = Math.round(basePaddingDp * density);
            int max = Math.round(maxContentWidthDp * density);
            int inset = centeredInsetPx(width, base, max);
            int horizontal = base + inset;
            if (v.getPaddingLeft() != horizontal || v.getPaddingRight() != horizontal) {
                v.setPadding(horizontal, v.getPaddingTop(), horizontal, v.getPaddingBottom());
            }
        });
    }

    /** Exact current window width in pixels; display metrics can lag in split-screen windows. */
    public static int windowWidthPx(Activity activity) {
        WindowManager manager = activity.getWindowManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return manager.getCurrentWindowMetrics().getBounds().width();
        }
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getMetrics(metrics);
        return metrics.widthPixels;
    }

    /** Current window width in dp, used only for the first layout before a real measure. */
    public static int windowWidthDp(Activity activity) {
        float density = activity.getResources().getDisplayMetrics().density;
        if (density <= 0f) return 0;
        return Math.round(windowWidthPx(activity) / density);
    }
}
