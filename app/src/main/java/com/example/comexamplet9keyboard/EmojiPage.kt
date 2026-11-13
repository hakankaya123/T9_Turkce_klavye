package com.example.comexamplet9keyboard

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

// Emoji sayfası: son kullanılanlar, ten rengi seçici.
// T9Service'in uzantı fonksiyonlarıdır; durum (state) T9Service içinde tutulur.

internal fun T9Service.loadRecentEmojis() {
    recentEmojis.clear()
    val saved = prefs.getString("recent_emojis", "👍 😀 😂 🙏 ❤️ 🔥 ✨ 😊") ?: ""
    recentEmojis.addAll(saved.split(" ").filter { it.isNotEmpty() }.take(8))
}

internal fun T9Service.showSkinTonePopup(anchor: View, baseEmoji: String) {
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            setColor("#2D3748".toColorInt())
            cornerRadius = dp(24).toFloat()
        }
    }
    val variants = listOf(baseEmoji) + listOf(
        "\uD83C\uDFFB",
        "\uD83C\uDFFC",
        "\uD83C\uDFFD",
        "\uD83C\uDFFE",
        "\uD83C\uDFFF"
    ).map { baseEmoji + it }
    val popup = PopupWindow(layout, -2, -2, true).apply {
        isOutsideTouchable = true
        elevation = dp(8).toFloat()
    }
    variants.forEach { v ->
        val tv = TextView(this).apply {
            text = v
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener {
                typeText(v)
                recordRecentEmoji(v)
                popup.dismiss()
            }
        }
        layout.addView(tv)
    }
    layout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val screenWidth = resources.displayMetrics.widthPixels
    val loc = IntArray(2)
    anchor.getLocationOnScreen(loc)
    var screenX = loc[0] + (anchor.width - layout.measuredWidth) / 2
    val rightMargin = dp(16)
    if (screenX + layout.measuredWidth > screenWidth - rightMargin) {
        screenX = screenWidth - layout.measuredWidth - rightMargin
    }
    if (screenX < rightMargin) {
        screenX = rightMargin
    }
    val xOff = screenX - loc[0]
    val yOff = -layout.measuredHeight - anchor.height - dp(8)
    popup.showAsDropDown(anchor, xOff, yOff)
}

internal fun T9Service.recordRecentEmoji(e: String) {
    recentEmojis.remove(e)
    recentEmojis.add(0, e)
    if (recentEmojis.size > 8) recentEmojis.removeAt(recentEmojis.size - 1)
    prefs.edit {putString("recent_emojis", recentEmojis.joinToString(" "))}
    populateRecentRow()
}

internal fun T9Service.populateRecentRow() {
    if (!emojiReady) return
    recentRowContainer.removeAllViews()
    for (i in 0 until 8) {
        val e = recentEmojis.getOrNull(i) ?: ""
        val tv = TextView(this).apply {
            text = e
            textSize = 26f
            gravity = Gravity.CENTER
            if (e.isNotEmpty()) {
                setOnClickListener {
                    typeText(e)
                    recordRecentEmoji(e)
                }
                if (KeyboardData.SKIN_TONE_BASE_EMOJIS.any { e.startsWith(it) }) {
                    setOnLongClickListener {
                        showSkinTonePopup(it, e.substring(0, 2))
                        true
                    }
                }
            }
        }
        recentRowContainer.addView(tv, LinearLayout.LayoutParams(0, dp(46), 1f))
    }
}

internal fun T9Service.buildEmojiPage(): View {
    val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(this).apply { isFillViewport = true }
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    loadRecentEmojis()

    val titleRecent = TextView(this).apply {
        text = "Son kullanılanlar"
        textSize = 13f
        setTextColor(SUBTXT)
        setPadding(dp(16), dp(10), dp(16), dp(2))
    }
    col.addView(titleRecent)

    recentRowContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    populateRecentRow()
    col.addView(recentRowContainer)

    val titleAll = TextView(this).apply {
        text = "Yüz ifadeleri ve insanlar"
        textSize = 13f
        setTextColor(SUBTXT)
        setPadding(dp(16), dp(14), dp(16), dp(2))
    }
    col.addView(titleAll)

    KeyboardData.EMOJIS.split(" ").filter { it.isNotEmpty() }.chunked(8).forEach { chunk ->
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0 until 8) {
            val e = chunk.getOrNull(i)
            val tv = TextView(this).apply {
                text = e ?: ""; textSize = 26f; gravity = Gravity.CENTER
                if (e != null) {
                    setOnClickListener {
                        typeText(e)
                        recordRecentEmoji(e)
                    }
                    if (e in KeyboardData.SKIN_TONE_BASE_EMOJIS) {
                        setOnLongClickListener {
                            showSkinTonePopup(it, e)
                            true
                        }
                    }
                }
            }
            row.addView(tv, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        col.addView(row)
    }
    scroll.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, -2))
    panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    add(bottom, key("ABC", accent = true, size = 18f) { showPage(t9Page) }, 1.5f)
    add(bottom, spaceKey("", 1f), 4f)
    add(bottom, key("⌫", accent = true, repeat = true, accel = true) { backspace() }, 1.5f)
    panel.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)))
    return panel
}
