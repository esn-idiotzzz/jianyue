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
    /** Maps the content width in dp to a column count. */
    public interface ColumnPolicy {
        int columnsFor(int contentWidthDp);
    }

    /** Notified after the column count changes because the window width changed. */
    public interface OnColumnsChanged {
        void onColumnsChanged(int columns, int contentWidthPx);
    }

    private final ColumnPolicy policy;
    private OnColumnsChanged listener;

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
        int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        if (available > 0 && policy != null) {
            float density = getResources().getDisplayMetrics().density;
            int widthDp = density > 0f ? Math.round(available / density) : 0;
            int columns = Math.max(1, policy.columnsFor(widthDp));
            if (columns != getNumColumns()) {
                setNumColumns(columns);
                if (listener != null) listener.onColumnsChanged(columns, available);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
