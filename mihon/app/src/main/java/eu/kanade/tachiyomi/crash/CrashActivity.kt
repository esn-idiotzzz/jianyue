package eu.kanade.tachiyomi.crash

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small in-app crash screen; never starts Mihon's library UI. */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exception = GlobalExceptionHandler.getThrowableFromIntent(intent)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfff5f6fa.toInt())
            setPadding(56, 110, 56, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(
            TextView(this).apply {
                text = "简阅遇到了一点问题"
                textSize = 22f
                setTextColor(0xff18202c.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        root.addView(
            TextView(this).apply {
                text = "可以返回首页后重试；如果问题反复出现，请记录下方信息。"
                textSize = 14f
                setTextColor(0xff818895.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 18, 0, 18)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        root.addView(
            ScrollView(this).apply {
                addView(
                    TextView(this@CrashActivity).apply {
                        text = buildString {
                            append(exception?.javaClass?.name ?: "未知错误")
                            exception?.message?.let { append("\n").append(it) }
                            append("\n\n")
                            append(stackTrace(exception))
                        }
                        textSize = 11f
                        setTextColor(0xff818895.toInt())
                        setTextIsSelectable(true)
                    },
                )
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        root.addView(
            Button(this).apply {
                text = "重新启动简阅"
                setOnClickListener {
                    startActivity(
                        Intent(this@CrashActivity, com.joe.jmlite.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    finish()
                }
            },
            LinearLayout.LayoutParams(-1, 54),
        )
        root.addView(
            Button(this).apply {
                text = "关闭"
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(-1, 48),
        )
        setContentView(root)
    }

    private fun stackTrace(exception: Throwable?): String {
        if (exception == null) return ""
        return exception.stackTraceToString().take(8000)
    }
}
