package com.example.comexamplet9keyboard

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import kotlin.math.abs
import kotlin.math.min

// Pano sayfası: kartlar, kaydırarak silme, uzun basınca düzenleme.
// T9Service'in uzantı fonksiyonlarıdır; durum (state) T9Service içinde tutulur.

internal fun T9Service.openClipEditor(text: String?, pinned: Boolean) {
    val i = Intent(this, ClipEditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (text != null) {
        i.putExtra("text", text); i.putExtra("pinned", pinned)
    }
    startActivity(i)
}

internal fun T9Service.pasteClip(text: String) {
    commitWord(false)
    attach = null
    currentInputConnection?.commitText(text, 1)
    prevWord = null
    showPage(t9Page)
    updateSuggestions()
}

internal fun T9Service.makeCard(
    text: String, bg: Int, pinned: Boolean, swipeable: Boolean,
    onTap: () -> Unit, onLong: () -> Unit, onDelete: () -> Unit
): View {
    val card = FrameLayout(this)
    card.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(6).toFloat() }
    card.addView(TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor("#222222".toColorInt())
        maxLines = 8
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }, FrameLayout.LayoutParams(MATCH_PARENT, -2))
    if (pinned) {
        card.addView(
            TextView(this).apply { this.text = "📌"; textSize = 12f },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        )
    }
    var downX = 0f
    var downY = 0f
    var dragging = false
    var longFired = false
    val slop = ViewConfiguration.get(this).scaledTouchSlop
    val longRunnable = Runnable { longFired = true; onLong() }
    card.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false; longFired = false
                handler.postDelayed(longRunnable, 500)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (swipeable && !dragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                    dragging = true
                    handler.removeCallbacks(longRunnable)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                } else if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    handler.removeCallbacks(longRunnable)
                }
                if (dragging) {
                    v.translationX = dx
                    v.alpha = 1f - min(1f, abs(dx) / v.width)
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longRunnable)
                val dx = e.rawX - downX
                if (dragging) {
                    if (abs(dx) > v.width / 3) onDelete()
                    else v.animate().translationX(0f).alpha(1f).setDuration(120).start()
                } else if (!longFired && abs(dx) < slop && abs(e.rawY - downY) < slop) {
                    onTap()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                v.translationX = 0f
                v.alpha = 1f
            }
        }
        true
    }
    return card
}

internal fun T9Service.refreshClips() {
    if (!clipReady) return
    clipManager.capture(false)
    clipLeft.removeAllViews()
    clipRight.removeAllViews()
    var hl = 0
    var hr = 0
    fun put(v: View, lines: Int) {
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, -2)
            .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        if (hl <= hr) {
            clipLeft.addView(v, lp); hl += lines
        } else {
            clipRight.addView(v, lp); hr += lines
        }
    }

    val hintBg = "#F8EDC8".toColorInt()
    if (!prefs.getBoolean("hint_del", false)) {
        put(
            makeCard(
            "Kartı silmek için sola ya da sağa kaydırın", hintBg, pinned = false,
            swipeable = true,
            onTap = {},
            onLong = {}) {
            prefs.edit { putBoolean("hint_del", true) }; refreshClips()
        }, 3
        )
    }
    if (!prefs.getBoolean("hint_edit", false)) {
        put(
            makeCard(
            "Düzenlemek ve kilitlemek için uzun basın", hintBg, pinned = false,
            swipeable = true,
            onTap = {},
            onLong = {}) {
            prefs.edit { putBoolean("hint_edit", true) }; refreshClips()
        }, 3
        )
    }
    val all = ClipStore.load(this)
    val sorted = all.filter { it.pinned } + all.filter { !it.pinned }
    if (sorted.isEmpty()) {
        put(
            makeCard(
            "Henüz kopyalanan bir şey yok.", hintBg, pinned = false,
            swipeable = false,
            onTap = {},
            onLong = {},
            onDelete = {}), 3
        )
    }
    sorted.forEach { c ->
        put(
            makeCard(
                c.text, "#FAFAFA".toColorInt(), c.pinned, !c.pinned,
                { pasteClip(c.text) },
                { openClipEditor(c.text, c.pinned) },
                { ClipStore.remove(this, c.text); refreshClips() }),
            minOf(8, c.text.length / 16 + 1)
        )
    }
}

internal fun T9Service.buildClipPage(): View {
    val page = FrameLayout(this)
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val header = TextView(this).apply {
        text = "+   YENİ OLUŞTUR"
        textSize = 17f
        setTextColor(textColor)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(16), 0)
        setOnClickListener { openClipEditor(null, false) }
    }
    box.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
    val scroll = ScrollView(this)
    val cols = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), 0, dp(4), dp(8))
    }
    clipLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    clipRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    cols.addView(clipLeft, LinearLayout.LayoutParams(0, -2, 1f))
    cols.addView(clipRight, LinearLayout.LayoutParams(0, -2, 1f))
    scroll.addView(cols)
    box.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    page.addView(box, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    return page
}
