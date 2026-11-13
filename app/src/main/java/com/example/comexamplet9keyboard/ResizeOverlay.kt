package com.example.comexamplet9keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Klavyenin "Yeniden Boyutlandır" katmanı: yükseklik, yan boşluk ve yukarı kaldırma ayarı.
 * T9Service'ten ayrıldı; servis durumuna değil, yalnızca aşağıdaki parametrelere bağlıdır.
 */
class ResizeOverlay(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val frame: FrameLayout,
    private val content: View,
    private val keyColor: Int,
    private val textColor: Int,
    private val accentColor: Int,
    private val navBottomPx: Int,
    private val applyRootPadding: () -> Unit
) {
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun show() {
        frame.findViewWithTag<View>("resize_overlay")?.let { frame.removeView(it) }

        val screenW = context.resources.displayMetrics.widthPixels
        val minH = dp(170)
        val maxH = dp(520)
        val maxSide = (screenW * 0.35f).toInt()
        val maxLift = dp(200)
        val blue = Color.parseColor("#2F8CFF")

        val overlay = FrameLayout(context).apply {
            tag = "resize_overlay"
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                setStroke(dp(1), Color.WHITE, dp(6).toFloat(), dp(4).toFloat())
            }
        }

        fun setHeight(h: Int) {
            val nh = h.coerceIn(minH, maxH)
            content.layoutParams = content.layoutParams.apply { height = nh }
            content.requestLayout()
            prefs.edit().putInt("kb_height", nh).apply()
        }

        fun setSide(key: String, m: Int) {
            prefs.edit().putInt(key, m.coerceIn(0, maxSide)).apply()
            applyRootPadding()
        }

        fun setLift(v: Int) {
            val nv = v.coerceIn(0, maxLift)
            prefs.edit().putInt("kb_lift", nv).apply()
            (frame.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = nv
                frame.layoutParams = it
            }
        }

        fun makeDrag(
            target: View,
            horizontal: Boolean,
            startValue: () -> Int,
            onDelta: (Int, Int) -> Unit
        ) {
            var down = 0f
            var start = 0
            target.setOnTouchListener { v, e ->
                val pos = if (horizontal) e.rawX else e.rawY
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        down = pos
                        start = startValue()
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    MotionEvent.ACTION_MOVE -> onDelta(start, (pos - down).toInt())
                }
                true
            }
        }

        val stroke = dp(3).toFloat()

        val joy = JoystickView(context)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun btn(label: String, bg: Int, fg: Int, f: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(6).toFloat() }
            setOnClickListener { f() }
        }
        buttons.addView(btn("Sıfırla", keyColor, textColor) {
            prefs.edit().remove("kb_height").remove("kb_ml").remove("kb_mr").remove("kb_lift")
                .apply()
            content.layoutParams = content.layoutParams.apply { height = dp(4 * 58) }
            content.requestLayout()
            (frame.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.bottomMargin = 0
                frame.layoutParams = it
            }
            applyRootPadding()
            android.widget.Toast.makeText(
                context,
                "Boyut sıfırlandı",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        buttons.addView(btn("✓ Tamam", accentColor, Color.parseColor("#10201A")) {
            frame.removeView(overlay)
            android.widget.Toast.makeText(
                context,
                "Klavye boyutu kaydedildi",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        })
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        center.addView(joy, LinearLayout.LayoutParams(dp(76), dp(76)))
        center.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        overlay.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        makeDrag(joy, false, { prefs.getInt("kb_lift", 0) }) { s0, d -> setLift(s0 - d) }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        top.addView(
            IconView(context, IconView.UP, blue, stroke),
            LinearLayout.LayoutParams(dp(64), dp(30))
        )
        top.addView(
            IconView(context, IconView.DOWN, blue, stroke),
            LinearLayout.LayoutParams(dp(64), dp(30))
        )
        overlay.addView(
            top,
            FrameLayout.LayoutParams(dp(150), dp(64), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )
        makeDrag(
            top,
            false,
            { if (content.height > 0) content.height else dp(4 * 58) }) { s0, d -> setHeight(s0 - d) }

        val left = IconView(context, IconView.RIGHT, blue, stroke)
        overlay.addView(
            left,
            FrameLayout.LayoutParams(dp(56), dp(140), Gravity.START or Gravity.CENTER_VERTICAL)
        )
        makeDrag(left, true, { prefs.getInt("kb_ml", 0) }) { s0, d -> setSide("kb_ml", s0 + d) }
        val right = IconView(context, IconView.LEFT, blue, stroke)
        overlay.addView(
            right,
            FrameLayout.LayoutParams(dp(56), dp(140), Gravity.END or Gravity.CENTER_VERTICAL)
        )
        makeDrag(right, true, { prefs.getInt("kb_mr", 0) }) { s0, d -> setSide("kb_mr", s0 - d) }

        frame.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            leftMargin = dp(3) + prefs.getInt("kb_ml", 0)
            rightMargin = dp(3) + prefs.getInt("kb_mr", 0)
            bottomMargin = dp(4) + navBottomPx
        })
    }
}
