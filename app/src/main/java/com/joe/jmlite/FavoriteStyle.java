package com.joe.jmlite;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.TextView;

/** Shared favorite-heart look, taken from the JM manga detail screen. */
final class FavoriteStyle {
    static final int RED = 0xffe5484d;

    private FavoriteStyle() {}

    static void paint(Ui ui, Ui.Icon heart, View button, TextView label, boolean favorite) {
        heart.kind(favorite ? "heart-filled" : "heart");
        heart.color(favorite ? RED : ui.muted);
        if (label != null) label.setTextColor(favorite ? RED : ui.muted);
        if (button != null) {
            GradientDrawable background = ui.shape(favorite ? (ui.dark ? 0x33ff5a5f : 0xffffe9ea) : ui.card, 30);
            background.setStroke(ui.dp(1), favorite ? 0x4de5484d : ui.line);
            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(ui.dark ? 0x22ffffff : 0x11000000), background, null));
        }
    }
}
