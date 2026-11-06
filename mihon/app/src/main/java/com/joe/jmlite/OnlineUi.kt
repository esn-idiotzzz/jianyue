package com.joe.jmlite

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/** Small view helper shared by the online screens so they match the rest of the app. */
internal class OnlineUi(private val context: Context) {
    val dark: Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val bg: Int = if (dark) 0xff101114.toInt() else 0xfff5f6fa.toInt()
    val card: Int = if (dark) 0xff202126.toInt() else Color.WHITE
    val ink: Int = if (dark) 0xfff1f2f5.toInt() else 0xff18202c.toInt()
    val muted: Int = if (dark) 0xff989da9.toInt() else 0xff818895.toInt()
    val tint: Int = if (dark) 0xff202f4a.toInt() else 0xffeaf1ff.toInt()
    val line: Int = if (dark) 0xff34363c.toInt() else 0xffe9ecf1.toInt()

    fun dp(value: Number): Int =
        (value.toFloat() * context.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(color: Int, radius: Number): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    fun round(view: View, color: Int, radius: Number) {
        view.background = RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x22ffffff else 0x11000000),
            shape(color, radius),
            null,
        )
    }

    fun text(value: String, size: Number, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }

    fun button(value: String, primary: Boolean): TextView =
        text(value, 15, if (primary) Color.WHITE else BLUE, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(50)
            round(this, if (primary) BLUE else tint, 17)
        }

    fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun backButton(onClick: () -> Unit): TextView =
        text("‹", 34, ink).apply {
            gravity = Gravity.CENTER
            contentDescription = "返回"
            round(this, Color.TRANSPARENT, 22)
            setOnClickListener { onClick() }
        }

    fun header(titleText: String, onBack: () -> Unit): LinearLayout {
        val bar = row()
        bar.setPadding(dp(8), dp(4), dp(12), dp(4))
        bar.addView(backButton(onBack), LinearLayout.LayoutParams(dp(44), dp(48)))
        bar.addView(
            text(titleText, 17, ink, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        return bar
    }

    fun progress(): android.widget.ProgressBar =
        android.widget.ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(BLUE)
        }

    companion object {
        const val BLUE: Int = 0xff3478f6.toInt()
    }
}

internal fun applyOnlineInsets(activity: Activity, root: View, dark: Boolean) {
    EdgeNavigation.install(activity,root,dark)
}
