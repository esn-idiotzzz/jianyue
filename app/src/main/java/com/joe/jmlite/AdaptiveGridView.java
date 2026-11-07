package com.joe.jmlite;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.GridView;

/**
 * A {@link GridView} that re-derives its column count from the width actually measured for the
 * window, so split-screen, freeform, display-size and rotation changes reflow without a fixed
 * column count. Columns only change when the width crosses a cover-width boundary, so scrolling
 * and rotation do not repeat layout work unnecessarily.
 */
public final class AdaptiveGridView extends GridView {
    /** Maps the measured content width and the window width, both in dp, to a column count. */
    public interface ColumnPolicy {
        int columnsFor(int contentWidthDp, int windowWidthDp);
    }

    /** Notified after the column count changes because the window width changed. */
    public interface OnColumnsChanged {
        void onColumnsChanged(int columns, int contentWidthPx);
    }

    private final ColumnPolicy policy;
    private OnColumnsChanged listener;
    private int lastContentWidthPx = -1;
    private int lastWindowWidthDp = -1;

    public AdaptiveGridView(Context context, ColumnPolicy policy) {
        super(context);
        this.policy = policy;
    }

    public AdaptiveGridView(Context context, AttributeSet attrs, ColumnPolicy policy) {
        super(context, attrs);
        this.policy = policy;
    }

    public void setOnColumnsChanged(OnColumnsChanged listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthPx = MeasureSpec.getSize(widthMeasureSpec);
        int contentWidthPx = widthPx - getPaddingLeft() - getPaddingRight();
        int windowWidthDp = getResources().getConfiguration().screenWidthDp;
        // Re-run the policy only when the real content width (or window class) changed; scrolling
        // and repeated measure passes must not repeat column math.
        if (contentWidthPx > 0 && policy != null
                && (contentWidthPx != lastContentWidthPx || windowWidthDp != lastWindowWidthDp)) {
            lastContentWidthPx = contentWidthPx;
            lastWindowWidthDp = windowWidthDp;
            float density = getResources().getDisplayMetrics().density;
            if (density > 0f) {
                int contentWidthDp = Math.round(contentWidthPx / density);
                int columns = Math.max(1, policy.columnsFor(contentWidthDp, windowWidthDp));
                if (columns != getNumColumns()) {
                    setNumColumns(columns);
                    if (listener != null) listener.onColumnsChanged(columns, contentWidthPx);
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
