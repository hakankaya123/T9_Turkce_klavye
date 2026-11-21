package com.example.comexamplet9keyboard

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import androidx.core.view.WindowInsetsControllerCompat
class UserDictionaryActivity : AppCompatActivity() {

    private val BG = "#141C22".toColorInt()
    private val CARD_BG = "#1C2830".toColorInt()
    private val ITEM_BG = "#263642".toColorInt()
    private val ACCENT = "#7EEEEF".toColorInt()
    private val ACCENT_DARK = "#0E7C86".toColorInt()
    private val TXT = "#F8FAFC".toColorInt()
    private val SUBTXT = "#94A3B8".toColorInt()
    private val RED = "#EF4444".toColorInt()
    private val GREEN = "#10B981".toColorInt()

    private val trLocale = Locale.forLanguageTag("tr-TR")

    private lateinit var wordsContainer: LinearLayout
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var inputSmart: EditText
    private lateinit var wordsScroll: ScrollView
    private lateinit var scrollbarTrack: View
    private lateinit var scrollbarThumb: View
    private lateinit var btnAdd: TextView      // "Ekle" butonu (kelime sözlükte yoksa)
    private lateinit var btnEdit: TextView     // "Düzenle" butonu (kelime sözlükte varsa)
    private lateinit var btnDelete: TextView   // "Sil" butonu (kelime sözlükte varsa)

    private var allWords = mutableListOf<UserWord>()
    private var filterQuery = ""
    private var visibleLimit =
        50   // her seferinde en fazla bu kadar kart çizilir (hızlı silme/yazma için)
    private var scrollbarTouchStartY = 0f
    private var scrollbarTouchStartScroll = 0

    // ── Yedekleme: dosya seçiciler (ek izin gerekmez) ──
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) doImport(uri)
        }

    // ── Otomatik yedek (her gün seçilen saatte, İndirilenler) ──
    private lateinit var autoText: TextView
    private lateinit var autoSwitch: androidx.appcompat.widget.SwitchCompat
    private var autoRefreshing = false

    // Android 9 ve öncesinde depolama izni verilince yapılacak iş
    private var permAction: (() -> Unit)? = null

    private val autoPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = permAction
            permAction = null
            if (granted) action?.invoke()
            else Toast.makeText(this, "Depolama izni verilmedi", Toast.LENGTH_LONG).show()
            refreshAutoRow()
        }

    // Android 9 ve öncesinde İndirilenler'e yazmak için izin gerekir; gerekirse ister, yoksa işi yapar.
    private fun withStoragePermission(action: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permAction = action
            autoPermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        action()
    }

    private fun onAutoSwitchChanged(checked: Boolean) {
        if (autoRefreshing) return
        if (!checked) {
            AutoBackup.setEnabled(this, false)
            refreshAutoRow()
            return
        }
        withStoragePermission {
            AutoBackup.setEnabled(this, true)
            refreshAutoRow()
            Toast.makeText(
                this,
                "Otomatik yedek açıldı: her gün ${AutoBackup.timeText(this)}",
                Toast.LENGTH_LONG
            ).show()
        }
        // İzin istendiyse, verilene kadar anahtar eski konumunda görünsün
        if (permAction != null) refreshAutoRow()
    }

    private fun backupNow() {
        withStoragePermission {
            val app = applicationContext
            Thread {
                val err = AutoBackup.runBackup(app, "elle")
                runOnUiThread {
                    refreshAutoRow()
                    Toast.makeText(
                        this,
                        if (err == null) "Yedek İndirilenler'e kaydedildi" else "Yedek alınamadı: $err",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.start()
        }
    }

    private fun refreshAutoRow() {
        if (!::autoText.isInitialized) return
        val on = AutoBackup.isEnabled(this)
        val status = AutoBackup.statusText(this)
        val text = android.text.SpannableStringBuilder("Otomatik yedek: her gün ")
        // Saat ayrı tıklanabilir: dokununca saat seçici açılır.
        val start = text.length
        text.append(AutoBackup.timeText(this))
        text.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) = pickBackupTime()
            override fun updateDrawState(ds: android.text.TextPaint) {
                ds.color = ACCENT
                ds.isUnderlineText = true
            }
        }, start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append("\nİndirilenler/${AutoBackup.FILE_NAME} (eskisinin üzerine yazılır)")
        if (status != null) text.append("\n").append(status)
        autoText.text = text
        autoText.setTextColor(if (on) TXT else SUBTXT)
        autoRefreshing = true
        autoSwitch.isChecked = on
        autoRefreshing = false
    }

    private fun pickBackupTime() {
        android.app.TimePickerDialog(
            this,
            { _, h, m ->
                AutoBackup.setTime(this, h, m)
                refreshAutoRow()
                val note = if (AutoBackup.isEnabled(this)) "" else " (otomatik yedek kapalı; açmak için anahtarı kullanın)"
                Toast.makeText(
                    this,
                    "Yedek saati ${AutoBackup.timeText(this)} olarak ayarlandı$note",
                    Toast.LENGTH_LONG
                ).show()
            },
            AutoBackup.hour(this),
            AutoBackup.minute(this),
            true
        ).show()
    }

    private fun doExport(uri: android.net.Uri) {
        try {
            val text = UserDataBackup.exportData(this)
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("Dosya açılamadı")
            Toast.makeText(this, "Sözlük yedeği kaydedildi", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Dışa aktarılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun doImport(uri: android.net.Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw java.io.IOException("Dosya açılamadı")
            val r = UserDataBackup.importData(this, String(bytes, Charsets.UTF_8))
            refreshList()
            Toast.makeText(
                this,
                "Yüklendi: ${r.words} kelime, ${r.learned} öğrenilen kelime, ${r.pairs} kelime çifti",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "İçe aktarılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun backupButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(ACCENT_DARK)
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false // Açık renk simgeler için false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
// true: Koyu renk simgeler (açık arka plan için)
// false: Açık renk simgeler (koyu arka plan için)

        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        wordsScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, _, _, _ -> updateScrollbar() }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, ime.bottom)
            )
            wordsScroll.post { updateScrollbar() }
            insets
        }

        // Header (Geri Butonu ve Başlık)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val btnBack = TextView(this).apply {
            text = "← Geri"
            textSize = 16f
            setTextColor(ACCENT)
            setPadding(0, dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        }

        val title = TextView(this).apply {
            text = "Kullanıcı Sözlüğü"
            textSize = 22f
            setTextColor(TXT)
        }

        header.addView(btnBack)
        header.addView(title)
        container.addView(header)

        countText = TextView(this).apply {
            text = "Yükleniyor..."
            textSize = 14f
            setTextColor(SUBTXT)
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(countText)

        // ── Yedekleme düğmeleri ──
        val backupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        backupRow.addView(
            backupButton("⬆ Dışa aktar") { exportLauncher.launch("t9-sozluk-yedek-$stamp.json") },
            LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) }
        )
        backupRow.addView(
            backupButton("⬇ İçe aktar") { importLauncher.launch(arrayOf("*/*")) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) }
        )
        container.addView(backupRow)

        autoText = TextView(this).apply {
            textSize = 13f
            // Saat kısmındaki bağlantıya (ClickableSpan) dokunmayı etkinleştirir
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        autoSwitch = androidx.appcompat.widget.SwitchCompat(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(ACCENT, SUBTXT))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(ACCENT_DARK, ITEM_BG))
            setOnCheckedChangeListener { _, checked -> onAutoSwitchChanged(checked) }
        }
        val autoTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(autoText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(autoSwitch, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        val backupNowBtn = TextView(this).apply {
            text = "⟳  Şimdi yedekle"
            textSize = 13f
            setTextColor(ACCENT)
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { backupNow() }
        }
        val autoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(6))
            addView(autoTop)
            addView(backupNowBtn)
        }
        container.addView(
            autoCard,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        )
        refreshAutoRow()

        // ── Birleşik Arama Kartı ──
        val smartCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val searchIcon = TextView(this).apply {
            text = "🔍"
            textSize = 16f
            setPadding(0, 0, dp(8), 0)
        }

        inputSmart = EditText(this).apply {
            hint = "Ara..."
            setHintTextColor(SUBTXT)
            setTextColor(TXT)
            textSize = 15f
            background = null
            isSingleLine = true
            setImeOptions(imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterQuery = s?.toString()?.trim()?.lowercase(trLocale) ?: ""
                    visibleLimit = 50
                    renderList()
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }

        // "Ekle" butonu – kelime sözlükte yokken görünür
        btnAdd = TextView(this).apply {
            text = "+ Ekle"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(ACCENT_DARK)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
            setOnClickListener {
                val clean = filterQuery.trim()
                if (clean.length < 2) {
                    Toast.makeText(
                        this@UserDictionaryActivity,
                        "Kelime en az 2 harfli olmalıdır",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                UserDictionaryStore.add(this@UserDictionaryActivity, clean)
                Toast.makeText(
                    this@UserDictionaryActivity,
                    "\"$clean\" sözlüğe eklendi",
                    Toast.LENGTH_SHORT
                ).show()
                refreshList()
            }
        }

        // "Düzenle" butonu – kelime sözlükte varken görünür
        btnEdit = TextView(this).apply {
            text = "✏️"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(6), dp(6))
            visibility = View.GONE
            setOnClickListener {
                val match = allWords.firstOrNull { it.word.lowercase(trLocale) == filterQuery }
                if (match != null) showEditDialog(match)
            }
        }

        // "Sil" butonu – kelime sözlükte varken görünür
        btnDelete = TextView(this).apply {
            text = "🗑️"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(4), dp(6))
            visibility = View.GONE
            setOnClickListener {
                val match = allWords.firstOrNull { it.word.lowercase(trLocale) == filterQuery }
                if (match != null) showDeleteDialog(match)
            }
        }

        smartCard.addView(searchIcon)
        smartCard.addView(inputSmart, LinearLayout.LayoutParams(0, -2, 1f))
        smartCard.addView(btnAdd, LinearLayout.LayoutParams(-2, -2))
        smartCard.addView(btnEdit, LinearLayout.LayoutParams(-2, -2))
        smartCard.addView(btnDelete, LinearLayout.LayoutParams(-2, -2))
        container.addView(smartCard)

        // Durum Bilgilendirme Metni
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(SUBTXT)
            visibility = View.GONE
            setPadding(dp(4), dp(8), dp(4), dp(12))
        }
        container.addView(statusText)

        // Kelime Listesi Konteyneri
        wordsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(wordsContainer)

        wordsScroll.addView(container)
        root.addView(wordsScroll)

        scrollbarTrack = View(this).apply {
            background = GradientDrawable().apply {
                setColor(ITEM_BG)
                cornerRadius = dp(3).toFloat()
            }
            visibility = View.GONE
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_MOVE
                ) {
                    val contentHeight = wordsScroll.getChildAt(0)?.height ?: 0
                    val scrollRange = (contentHeight - wordsScroll.height).coerceAtLeast(0)
                    if (scrollRange > 0) {
                        val thumbHeight = dp(64)
                        val usableHeight = (height - thumbHeight).coerceAtLeast(1)
                        val thumbTop = (event.y - thumbHeight / 2f).coerceIn(0f, usableHeight.toFloat())
                        val progress = thumbTop / usableHeight
                        wordsScroll.scrollTo(0, (progress * scrollRange).toInt())
                    }
                    true
                } else {
                    true
                }
            }
        }
        scrollbarThumb = View(this).apply {
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(5).toFloat()
            }
            visibility = View.GONE
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        scrollbarTouchStartY = event.rawY
                        scrollbarTouchStartScroll = wordsScroll.scrollY
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val viewportHeight = wordsScroll.height
                        val thumbHeight = view.height
                        val thumbTravel = (viewportHeight - thumbHeight).coerceAtLeast(1)
                        val content = wordsScroll.getChildAt(0)?.height ?: 0
                        val scrollRange = (content - viewportHeight).coerceAtLeast(0)
                        val delta = event.rawY - scrollbarTouchStartY
                        val target = scrollbarTouchStartScroll +
                            (delta * scrollRange / thumbTravel).toInt()
                        wordsScroll.scrollTo(0, target.coerceIn(0, scrollRange))
                        true
                    }
                    else -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        true
                    }
                }
            }
        }
        val trackParams = FrameLayout.LayoutParams(dp(12), -1).apply {
            gravity = Gravity.END
            marginEnd = dp(6)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        val thumbParams = FrameLayout.LayoutParams(dp(16), dp(64)).apply {
            gravity = Gravity.END
            marginEnd = dp(4)
        }
        root.addView(scrollbarTrack, trackParams)
        root.addView(scrollbarThumb, thumbParams)
        wordsScroll.viewTreeObserver.addOnGlobalLayoutListener { updateScrollbar() }
        setContentView(root)

        refreshList()
    }

    // Klavyedeki ⊕ ile (veya başka yerden) sözlük değişince liste hemen yenilensin
    private val prefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "uc" || key == "dict_rev") runOnUiThread { refreshList() }
        }

    override fun onStart() {
        super.onStart()
        val prefs = getSharedPreferences("t9", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshList()
    }

    override fun onStop() {
        getSharedPreferences("t9", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(
            prefListener
        )
        super.onStop()
    }

    // Türkçe alfabe sırası (a b c ç d … ı i … o ö p … s ş t u ü …); düz String karşılaştırması
    // ç, ğ, ı, ö, ş, ü harflerini listenin sonuna atardı.
    private val collator = java.text.Collator.getInstance(trLocale)

    private fun refreshList() {
        allWords = UserDictionaryStore.load(this)
        allWords.sortWith { a, b -> collator.compare(a.word, b.word) }
        renderList()
    }

    private fun updateScrollbar() {
        if (!::wordsScroll.isInitialized ||
            !::scrollbarTrack.isInitialized ||
            !::scrollbarThumb.isInitialized
        ) {
            return
        }

        val viewportHeight = wordsScroll.height
        val contentHeight = wordsScroll.getChildAt(0)?.height ?: 0
        val scrollRange = (contentHeight - viewportHeight).coerceAtLeast(0)
        if (viewportHeight <= 0 || scrollRange <= 0) {
            scrollbarTrack.visibility = View.GONE
            scrollbarThumb.visibility = View.GONE
            return
        }

        val thumbHeight = dp(64)
        val thumbParams = scrollbarThumb.layoutParams as FrameLayout.LayoutParams
        thumbParams.height = thumbHeight
        val trackParams = scrollbarTrack.layoutParams as FrameLayout.LayoutParams
        trackParams.height = (viewportHeight - dp(8)).coerceAtLeast(dp(24))
        trackParams.topMargin = dp(4)
        trackParams.bottomMargin = dp(4)

        val thumbTravel = (viewportHeight - thumbHeight - dp(8)).coerceAtLeast(1)
        val progress = wordsScroll.scrollY.toFloat() / scrollRange
        thumbParams.topMargin = dp(4) + (progress * thumbTravel).toInt()
        thumbParams.bottomMargin = 0

        scrollbarTrack.visibility = View.VISIBLE
        scrollbarThumb.visibility = View.VISIBLE
        scrollbarTrack.layoutParams = trackParams
        scrollbarThumb.layoutParams = thumbParams
    }

    private fun renderList() {
        wordsContainer.removeAllViews()
        val query = filterQuery.trim()

        val filtered = if (query.isBlank()) {
            allWords
        } else {
            allWords.filter { it.word.lowercase(trLocale).contains(query) }
        }

        countText.text = "Toplam ${allWords.size} özel kelime kaydedildi"

        // ── Akıllı Buton & Durum Güncelleme ──
        if (query.length >= 2) {
            val exactExists = allWords.any { it.word.lowercase(trLocale) == query }

            if (exactExists) {
                // Kelime sözlükte VAR → "Düzenle" ve "Sil" butonları göster
                btnAdd.visibility = View.GONE
                btnEdit.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE

                statusText.visibility = View.VISIBLE
                statusText.text = "✓ \"$query\" sözlüğünüzde kayıtlı."
                statusText.setTextColor(GREEN)
            } else {
                // Kelime sözlükte YOK → "Ekle" butonu göster
                btnAdd.visibility = View.VISIBLE
                btnEdit.visibility = View.GONE
                btnDelete.visibility = View.GONE
                btnAdd.text = "+ Ekle"
                btnAdd.background = GradientDrawable().apply {
                    setColor(ACCENT_DARK)
                    cornerRadius = dp(8).toFloat()
                }

                statusText.visibility = View.VISIBLE
                statusText.text = "➕ \"$query\" sözlükte yok. Eklemek için + Ekle'ye basın."
                statusText.setTextColor(ACCENT)
            }
        } else {
            btnAdd.visibility = View.GONE
            btnEdit.visibility = View.GONE
            btnDelete.visibility = View.GONE
            statusText.visibility = View.GONE
        }

        if (filtered.isEmpty()) {
            val emptyCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(CARD_BG)
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }

            val msgText = TextView(this).apply {
                text = if (allWords.isEmpty()) {
                    "Henüz sözlüğe eklenmiş özel bir kelime yok."
                } else {
                    "\"$query\" kelimesi sözlükte bulunamadı."
                }
                textSize = 14f
                setTextColor(SUBTXT)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(12))
            }

            emptyCard.addView(msgText)

            if (query.length >= 2 && !allWords.any { it.word.lowercase(trLocale) == query }) {
                val btnDirectAdd = TextView(this).apply {
                    text = "+ \"$query\" kelimesini sözlüğe ekle"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(ACCENT_DARK)
                        cornerRadius = dp(8).toFloat()
                    }
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setOnClickListener {
                        UserDictionaryStore.add(this@UserDictionaryActivity, query)
                        Toast.makeText(
                            this@UserDictionaryActivity,
                            "\"$query\" sözlüğe eklendi",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshList()
                    }
                }
                emptyCard.addView(btnDirectAdd, LinearLayout.LayoutParams(-2, -2))
            }

            wordsContainer.addView(emptyCard)
            return
        }

        filtered.take(visibleLimit).forEach { userWord ->
            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(ITEM_BG)
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }

            val textInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val wordTitle = TextView(this).apply {
                text = userWord.word
                textSize = 16f
                setTextColor(TXT)
            }

            val wordBadge = TextView(this).apply {
                text = "Kullanım: ${userWord.count}x"
                textSize = 12f
                setTextColor(SUBTXT)
            }

            textInfo.addView(wordTitle)
            textInfo.addView(wordBadge)

            // Butonlar (Düzenle ve Sil)
            val itemBtnEdit = TextView(this).apply {
                text = "✏️ Düzenle"
                textSize = 13f
                setTextColor(ACCENT)
                setPadding(dp(8), dp(6), dp(12), dp(6))
                setOnClickListener { showEditDialog(userWord) }
            }

            val itemBtnDelete = TextView(this).apply {
                text = "🗑️ Sil"
                textSize = 13f
                setTextColor(RED)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener { showDeleteDialog(userWord) }
            }

            itemCard.addView(textInfo, LinearLayout.LayoutParams(0, -2, 1f))
            itemCard.addView(itemBtnEdit)
            itemCard.addView(itemBtnDelete)

            val lpItem = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lpItem.setMargins(0, 0, 0, dp(8))
            wordsContainer.addView(itemCard, lpItem)
        }

        if (filtered.size > visibleLimit) {
            val more = TextView(this).apply {
                text = "Daha fazla göster (${filtered.size - visibleLimit})"
                textSize = 14f
                setTextColor(ACCENT)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { visibleLimit += 50; renderList() }
            }
            wordsContainer.addView(more, LinearLayout.LayoutParams(-1, -2))
        }

        wordsScroll.post { updateScrollbar() }
    }

    private fun showEditDialog(userWord: UserWord) {
        val input = EditText(this).apply {
            setText(userWord.word)
            setSelection(userWord.word.length)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle("Kelimeyi Düzenle")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val newW = input.text.toString().trim()
                if (newW.length < 2) {
                    Toast.makeText(this, "Kelime en az 2 harfli olmalıdır", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                UserDictionaryStore.update(this, userWord.word, newW)
                Toast.makeText(this, "Kelime güncellendi", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showDeleteDialog(userWord: UserWord) {
        AlertDialog.Builder(this)
            .setTitle("Kelimeyi Sil")
            .setMessage("\"${userWord.word}\" kelimesini sözlükten silmek istediğinize emin misiniz?")
            .setPositiveButton("Sil") { _, _ ->
                UserDictionaryStore.remove(this, userWord.word)
                Toast.makeText(this, "\"${userWord.word}\" silindi", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
