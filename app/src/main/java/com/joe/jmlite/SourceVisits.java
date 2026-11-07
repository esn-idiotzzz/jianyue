package com.joe.jmlite;

import android.content.Context;

/**
 * Shared "visited source" state behind the globe icons and the grey visited rows.
 *
 * <p>The visited marks are intentionally session-only: they help while browsing inside one
 * opening of the app, and are cleared when the app starts or when it is left for the background,
 * so every fresh entry begins with bright globe icons.
 */
public final class SourceVisits {
    /** Preferences file shared by the repository rows, detect failures and search failures. */
    public static final String PREFS = "source_visits";

    private SourceVisits() {
    }

    /** Forget every visited source so the globes and failure rows lose their grey state. */
    public static void reset(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
