package com.example.comexamplet9keyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

// Ayarlar ve tema sayfaları, tema uygulama.
// T9Service'in uzantı fonksiyonlarıdır; durum (state) T9Service içinde tutulur.

private const val TAG = "T9Service"

internal fun T9Service.buildSettingsPage(): View {
    val page = FrameLayout(this)
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(12), dp(8), dp(12))
    }

    var predLabel: TextView? = null

    fun makeTile(
        iconText: String?,
        iconKind: Int?,
        titleText: String,
        onClick: () -> Unit,
        onLabelCreated: ((TextView) -> Unit)? = null
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(4), dp(8), dp(4), dp(8))
            val iconView: View = if (iconKind != null) {
                FrameLayout(context).apply {
                    addView(
                        IconView(context, iconKind, textColor, dp(2).toFloat()),
                        FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
                    )
                }
            } else {
                TextView(context).apply {
                    text = iconText ?: ""
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(textColor)
                }
            }
            val label = TextView(context).apply {
                text = titleText
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(iconColor)
                setPadding(0, dp(6), 0, 0)
            }
            onLabelCreated?.invoke(label)
            addView(iconView, LinearLayout.LayoutParams(MATCH_PARENT, -2))
            addView(label, LinearLayout.LayoutParams(MATCH_PARENT, -2))
            setOnClickListener { onClick() }
        }
    }

    val row1 = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val tTemalar = makeTile("👕", null, "Temalar", { showPage(themePage) })
    var sesLabel: TextView? = null
    val tSes = makeTile(
        null,
        IconView.MIC,
        if (voiceInputEnabled) "Sesle Yazma AÇIK" else "Sesle Yazma KAPALI",
        {
            voiceInputEnabled = !voiceInputEnabled
            sesLabel?.text = if (voiceInputEnabled) "Sesle Yazma AÇIK" else "Sesle Yazma KAPALI"
            sesLabel?.setTextColor(if (voiceInputEnabled) accentColor else iconColor)
        },
        onLabelCreated = { lbl ->
            sesLabel = lbl
            lbl.setTextColor(if (voiceInputEnabled) accentColor else iconColor)
        })
    val tBoyut = makeTile("⤢", null, "Yeniden Boyutlandır", { showResizeOverlay() })
    val tSozluk = makeTile("📖", null, "Sözlüğüm", { openUserDictionaryActivity() })
    listOf(tTemalar, tSes, tBoyut, tSozluk).forEach {
        row1.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
    }

    val row2 = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, 0)
    }
    val tTahmin = makeTile("🔤", null, if (predictive) "Tahmin AÇIK" else "Tahmin KAPALI", {
        predictive = !predictive
        updatePredLook()
        predLabel?.text = if (predictive) "Tahmin AÇIK" else "Tahmin KAPALI"
        predLabel?.setTextColor(if (predictive) accentColor else iconColor)
        val ic = currentInputConnection
        if (seq.isNotEmpty() && ic != null && attach == null) refreshPredictive(ic) else updateSuggestions()
    }, onLabelCreated = { lbl ->
        predLabel = lbl
        lbl.setTextColor(if (predictive) accentColor else iconColor)
    })
    val tDil = makeTile("🌐", null, "Dil", {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    })
    val tPano = makeTile("📋", null, "Pano", { showPage(clipPage) })
    var kelimeLabel: TextView? = null
    fun kelimeText() = if (autoAddWords) "Kelime Ekleme\nOtomatik" else "Kelime Ekleme\nManuel"
    val tAyarlar = makeTile("⊕", null, kelimeText(), {
        autoAddWords = !autoAddWords
        if (autoAddWords) recentUnknownWord = null
        kelimeLabel?.text = kelimeText()
        kelimeLabel?.setTextColor(if (autoAddWords) accentColor else iconColor)
        updateSuggestions()
    }, onLabelCreated = { lbl ->
        kelimeLabel = lbl
        lbl.setTextColor(if (autoAddWords) accentColor else iconColor)
    })
    listOf(tTahmin, tDil, tPano, tAyarlar).forEach {
        row2.addView(it, LinearLayout.LayoutParams(0, -2, 1f))
    }

    container.addView(row1, LinearLayout.LayoutParams(MATCH_PARENT, -2))
    container.addView(row2, LinearLayout.LayoutParams(MATCH_PARENT, -2))
    page.addView(container, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    return page
}

internal fun T9Service.openUserDictionaryActivity() {
    saveUserData()
    val i =
        Intent(this, UserDictionaryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(i)
}

internal fun T9Service.applyTheme() {
    if (lightTheme) {
        backgroundColor = "#E4E7EB".toColorInt()
        keyColor = "#FFFFFF".toColorInt()
        dimColor = "#D3D8DD".toColorInt()
        accentColor = "#00897B".toColorInt()
        textColor = "#202124".toColorInt()
        secondaryTextColor = Color.parseColor("#6F757A")
        iconColor = Color.parseColor("#5F6368")
        selectionBackgroundColor = Color.parseColor("#B2DFDB")
        shiftOffColor = Color.parseColor("#5FA79D")
    } else {
        backgroundColor = Color.parseColor("#212121")
        keyColor = Color.parseColor("#2E3236")
        dimColor = Color.parseColor("#262829")
        accentColor = Color.parseColor("#5FD3C5")
        textColor = Color.WHITE
        secondaryTextColor = Color.parseColor("#8A8E92")
        iconColor = Color.LTGRAY
        selectionBackgroundColor = Color.parseColor("#3F6B66")
        shiftOffColor = Color.parseColor("#7FA8A3")
    }
}

internal fun T9Service.applyNavBarAppearance() {
    try {
        val w = window?.window ?: return
        androidx.core.view.WindowCompat.getInsetsController(
            w,
            w.decorView
        ).isAppearanceLightNavigationBars = lightTheme
    } catch (e: Exception) {
        Log.w(TAG, "Gezinti çubuğu görünümü ayarlanamadı", e)
    }
}

internal fun T9Service.setLightTheme(light: Boolean) {
    if (lightTheme == light) return
    lightTheme = light
    prefs.edit().putBoolean("theme_light", light).apply()
    applyTheme()
    setInputView(onCreateInputView())
    showPage(themePage)
    android.widget.Toast.makeText(
        this,
        if (light) "Beyaz tema" else "Koyu tema",
        android.widget.Toast.LENGTH_SHORT
    ).show()
}

internal fun T9Service.buildThemePage(): View {
    val page = FrameLayout(this)
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }

    fun card(name: String, light: Boolean): View {
        val selected = lightTheme == light
        val pBg = Color.parseColor(if (light) "#E4E7EB" else "#212121")
        val pKey = Color.parseColor(if (light) "#FFFFFF" else "#2E3236")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(10).toFloat()
                if (selected) setStroke(dp(2), accentColor)
            }
            setOnClickListener { setLightTheme(light) }
        }
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(pBg)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.GRAY)
            }
        }
        for (r in 0 until 3) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 4) {
                val k = View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(pKey); cornerRadius = dp(3).toFloat()
                    }
                }
                line.addView(
                    k,
                    LinearLayout.LayoutParams(0, dp(16), 1f)
                        .apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            preview.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, -2))
        }
        box.addView(preview, LinearLayout.LayoutParams(dp(130), -2))
        box.addView(TextView(this).apply {
            text = if (selected) "✓ $name" else name
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (selected) accentColor else textColor)
            setPadding(0, dp(8), 0, 0)
        })
        return box
    }
    row.addView(card("Koyu", false), LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(card("Beyaz", true), LinearLayout.LayoutParams(0, -2, 1f))
    page.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    return page
}
