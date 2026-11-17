package com.example.comexamplet9keyboard

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class T9Service : InputMethodService() {

    internal val tr = T9Keymap.tr
    internal val handler = Handler(Looper.getMainLooper())

    internal var backgroundColor = "#212121".toColorInt()
    internal var keyColor = "#2E3236".toColorInt()
    internal var accentColor = "#5FD3C5".toColorInt()
    internal var dimColor = "#262829".toColorInt()
    internal var textColor = Color.WHITE
    internal var secondaryTextColor = "#8A8E92".toColorInt()
    internal var iconColor = Color.LTGRAY
    internal var selectionBackgroundColor = "#3F6B66".toColorInt()
    internal var shiftOffColor = "#7FA8A3".toColorInt()
    internal var lightTheme = false
    internal lateinit var themePage: View
    private lateinit var numPage: View
    private var numActionLabel: TextView? = null

    internal val groups = T9Keymap.groups
    private val charToDigit = T9Keymap.charToDigit

    private fun codeOf(w: String) = T9Keymap.codeOf(w)

    internal val words: ArrayList<String> get() = dictionaryManager.data.words
    internal val codes: ArrayList<String> get() = dictionaryManager.data.codes
    private val wordSet: HashSet<String> get() = dictionaryManager.data.wordSet
    internal val builtinBigrams: HashMap<String, ArrayList<String>>
        get() = dictionaryManager.data.builtinBigrams
    internal val userCount: HashMap<String, Int> get() = dictionaryManager.data.userCount
    internal val suffixCount: HashMap<String, Int> get() = dictionaryManager.data.suffixCount
    internal val wordIdx: HashMap<String, Int> get() = dictionaryManager.data.wordIdx
    internal val bigram: HashMap<Int, Int> get() = dictionaryManager.data.bigram
    internal val charTotal: HashMap<Char, Int> get() = dictionaryManager.data.charTotal
    internal val userBigram: HashMap<String, HashMap<String, Int>>
        get() = dictionaryManager.data.userBigram
    private val userWords: HashSet<String> get() = dictionaryManager.data.userWords
    internal val suffixIndex: Map<String, Int> by lazy {
        KeyboardData.SUFFIXES.withIndex().associate { it.value to it.index }
    }
    internal val prefs by lazy { getSharedPreferences("t9", MODE_PRIVATE) }
    private lateinit var dictionaryManager: T9DictionaryManager
    internal var voiceInputEnabled: Boolean
        get() = prefs.getBoolean("voice_input_enabled", true)
        set(v) = prefs.edit { putBoolean("voice_input_enabled", v) }

    private var vibrateEnabled: Boolean
        get() = prefs.getBoolean("vibrate_enabled", true)
        set(v) = prefs.edit { putBoolean("vibrate_enabled", v) }

    internal var autoAddWords: Boolean
        get() = prefs.getBoolean("addword_auto", false)
        set(v) = prefs.edit { putBoolean("addword_auto", v) }

    private fun playClick() {
        if (vibrateEnabled && keyboardRootView != null) {
            try {
                keyboardRootView?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (_: Exception) {
            }
        }
    }

    internal var predictive = true
    internal val word = StringBuilder()
    private var pendKey: String? = null
    private var pendChars = ""
    private var pendIdx = 0

    // Çoklu dokunuşta her harfin büyük/küçük durumu BASILDIĞI ANDA belirlenir (shift / caps).
    // Böylece 2., 4. harf gibi kelime ortasındaki harfler de büyük yazılabilir.
    private var pendUp = false

    // "Doğrudan yazım" (şifre alanları): composing (altı çizili geçici metin) kullanılmaz; her harf
    // doğrudan yazılır, aynı tuşa tekrar basınca önceki harf DEL tuşuyla silinip yenisi yazılır.
    private var dirId: String? = null
    private var dirChars = ""
    private var dirIdx = 0
    private var dirUp = false
    private val dirRunnable = Runnable { dirId = null }

    // Bazı uygulamalar (ör. Compose tabanlı alanlar, otomatik biçimlendiren MAC/IBAN alanları) harf yazılır
    // yazılmaz girişi yeniden başlatır ya da metni yeniden yazar; bu durumda bekleyen çoklu dokunuş durumu
    // (pendKey / dirId) sıfırlanır ve "abc" tuşuna tekrar basınca hep "a" yazılırdı. Son dokunuşu ayrıca
    // hatırlıyoruz: aynı tuşa 900 ms içinde tekrar basılırsa ve harf hâlâ imlecin hemen solundaysa
    // harfi silip sıradakini yazarız.
    // Bazı uygulamalar (ör. Ziraat Mobil) düz metin alanlarını da "görünür şifre" türüyle bildirir
    // (inputType 0x80091 = metin + TYPE_TEXT_VARIATION_VISIBLE_PASSWORD + NO_SUGGESTIONS). Bu alanlarda tahmin
    // varsayılan olarak kapalıdır. Kullanıcı, ayarlardan uygulama bazında "Zorla Tahmin" açabilir.
    // Gerçek şifre (gizli) ve sayısal şifre alanlarında tahmin her zaman kapalı kalır.
    private var forcedPkgs: Set<String> = emptySet()
    internal var forcedHere = false

    private fun visiblePasswordField(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return false
        return (t and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                (t and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    // "a✓" tuşu. Normal alanlarda tahmini açar/kapatır. Uygulama düz metin alanını "görünür şifre" olarak
    // bildiriyorsa (ör. Ziraat Mobil) tahmin başta kapalıdır; tuşa basınca bu uygulama için açılır,
    // tahmin açıkken tekrar basınca bu uygulamada yine kapanır. Seçim uygulama bazında hatırlanır.
    private fun onPredictionKey() {
        val pkg = currentInputEditorInfo?.packageName
        if (visiblePasswordField() && pkg != null) {
            when {
                !forcedHere -> {
                    toggleForcedPrediction(pkg); predictive = true
                }

                !predictive -> predictive = true
                else -> toggleForcedPrediction(pkg)
            }
        } else {
            predictive = !predictive
        }
    }

    internal fun toggleForcedPrediction(pkg: String) {
        val n = HashSet(forcedPkgs)
        if (!n.add(pkg)) n.remove(pkg)
        forcedPkgs = n
        prefs.edit { putStringSet("force_pred_pkgs", n) }
        forcedHere = pkg in n
    }

    // Parola benzeri alanlarda (gizli, görünür, web, sayısal) yazılanlardan hiçbir şey öğrenilmez.
    private fun passwordLikeField(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return false
        return when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> when (t and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true

                else -> false
            }

            InputType.TYPE_CLASS_NUMBER ->
                (t and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            else -> false
        }
    }

    private var lastTapId: String? = null
    private var lastTapIdx = 0
    private var lastTapChar = ""
    private var lastTapUp = false
    private var lastTapAt = 0L
    private var lastFieldNumeric = false
    internal val seq = StringBuilder()
    private var cands: List<String> = emptyList()
    private var symComposing = false
    private var symIdx = 0
    internal val forced = ArrayList<Char?>()
    private var colActive = false
    private lateinit var leftCol: LinearLayout
    private var upper = false
    private var shift = false
    private var caps = false
    private var lastShiftTap = 0L
    private val letterLabels = ArrayList<Pair<TextView, String>>()
    internal var prevWord: String? = null
    internal var attach: String? = null
    private var chipWord: String? = null
    internal var recentUnknownWord: String? = null
    private var stashedChip: String? = null
    private var stashedChipTime = 0L
    private var keyboardRootView: LinearLayout? = null
    private var navBottomPx = 0
    private var keyboardFrameView: FrameLayout? = null
    private var contentLayoutView: FrameLayout? = null
    private var letterMode = false
    internal var letterPos = -1
    private var suggestions: List<String> = emptyList()

    private lateinit var iconBar: LinearLayout
    private lateinit var suggestScroll: HorizontalScrollView
    private lateinit var suggestBox: LinearLayout
    private lateinit var closeBtn: TextView
    private lateinit var suggestLeftSpacer: View
    private lateinit var suggestRow: LinearLayout
    private lateinit var pages: List<View>
    internal lateinit var t9Page: View
    private lateinit var sym1: View
    private lateinit var sym2: View
    private lateinit var emojiPage: View
    private lateinit var cursorPage: View
    internal lateinit var clipPage: View
    private lateinit var settingsPage: View
    internal val clipReady: Boolean get() = ::clipLeft.isInitialized
    internal lateinit var clipLeft: LinearLayout
    internal lateinit var clipRight: LinearLayout
    private lateinit var selKey: FrameLayout
    private lateinit var hideOrReturnTool: TextView
    private var selectMode = false
    private var autoSpaced = false
    private val spacePunct = setOf(".", ",", "?", "!", ":", ";", "…")
    internal val clipManager by lazy { ClipManager(this) { currentInputEditorInfo } }
    private var predView: TextView? = null
    private var shiftIcon: IconView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var predictiveSearchJob: Job? = null
    private var predictiveSearchGeneration = 0L
    private var prefixSearchJob: Job? = null
    private var prefixSearchGeneration = 0L
    private val confirmRunnable = Runnable { confirmPending(); updateSuggestions() }
    private val symRunnable = Runnable {
        currentInputConnection?.finishComposingText()
        symComposing = false
        updateSuggestions()
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (keyboardRootView != null) {
            setInputView(onCreateInputView())
            updateSuggestions()
        }
    }

    // Ekran döndüğünde veya klavye yeniden oluşturulduğunda tuş takımı kaybolmasın
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    // Tam ekran (extract) modunu tamamen devre dışı bırak: her zaman kendi klavyemizi göster
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreate() {
        super.onCreate()
        dictionaryManager = T9DictionaryManager(applicationContext, handler) { updateSuggestions() }
        AutoBackup.ensureScheduled(this)
        forcedPkgs = prefs.getStringSet("force_pred_pkgs", null)?.toSet() ?: emptySet()
        lightTheme = prefs.getBoolean("theme_light", false)
        applyTheme()
        dictionaryManager.loadInitial()

        clipManager.start()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        dictionaryManager.onUnbind()
        handler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        dictionaryManager.onDestroy()
        handler.removeCallbacksAndMessages(null)
        clipManager.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        dictionaryManager.syncUserDictionary()
        forcedHere = info?.packageName?.let { it in forcedPkgs } == true
        // Alarm çalışmadıysa (bazı üreticiler arka plan alarmlarını engeller) yedeği klavye açılırken al.
        AutoBackup.runIfMissed(applicationContext, "klavye")
        // Yalnızca hata ayıklama (debug) derlemelerinde: alan bilgisini logla (metin içeriği değil).
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d(
                TAG,
                "alan: pkg=${info?.packageName} inputType=0x${Integer.toHexString(info?.inputType ?: 0)} " +
                        "imeOptions=0x${Integer.toHexString(info?.imeOptions ?: 0)} restarting=$restarting " +
                        "tahmin=${predictiveEffective()} direkt=${directInput()} zorla=$forcedHere selStart=${info?.initialSelStart} " +
                        "yazımOrtası=${composingActive()}"
            )
        }
        // Bazı uygulamalar (Compose, React Native, hibrit bankacılık alanları) her tuştan sonra girişi yeniden
        // başlatır. Yazım ortasındaysak durumu silmeyiz; yazdığımız metin hâlâ imlecin solundaysa
        // birleştirme (composing) bölgesini yeniden kurup kaldığımız yerden devam ederiz.
        if (restarting && info != null && tryKeepComposing(info)) {
            updatePredLook()
            return
        }
        val keep = if (restarting && stashedChip != null &&
            SystemClock.uptimeMillis() - stashedChipTime < 2500
        ) stashedChip else null
        stashedChip = null
        resetState()
        if (keep != null) {
            recentUnknownWord = keep
            updateSuggestions()
        }
        updatePredLook()
        if (::pages.isInitialized) {
            updateNumActionLabel(info)
            val numeric = isNumericField(info)
            // Tarayıcılar aynı sayfa içinde alan değiştirince girişi "yeniden başlatır" (restarting=true).
            // Önceden bu durumda sayfa hiç güncellenmiyordu: sayısal alandan (ör. TC kimlik no) şifre
            // alanına geçince rakam sayfası açık kalıyordu. Artık alan türü değiştiyse sayfa da değişir.
            if (!restarting || numeric != lastFieldNumeric) {
                showPage(if (numeric) numPage else t9Page, toggle = false)
            }
            lastFieldNumeric = numeric
        }
    }

    private fun tryKeepComposing(info: EditorInfo): Boolean {
        if (!composingActive() || attach != null || symComposing) return false
        val c = composingString() ?: return false
        if (c.isEmpty()) return false
        val ic = currentInputConnection ?: return false
        val end = info.initialSelStart
        if (end < c.length || info.initialSelEnd != end) return false
        val before = ic.getTextBeforeCursor(c.length, 0)?.toString() ?: return false
        if (!before.equals(c, ignoreCase = true)) return false
        val ok = ic.setComposingRegion(end - c.length, end)
        if (ok) Log.i(TAG, "uygulama girişi yeniden başlattı; yazım durumu korundu")
        return ok
    }

    override fun onFinishInput() {
        stashedChip = recentUnknownWord
        stashedChipTime = SystemClock.uptimeMillis()
        commitWord(false)
        dictionaryManager.onFinishInput()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        if (autoSpaced && currentInputConnection?.getTextBeforeCursor(1, 0)
                ?.toString() != " "
        ) autoSpaced = false
        if (composingActive()) checkCursorMoved()
        val a = attach
        if (a != null && !composingActive()) {
            val b = currentInputConnection?.getTextBeforeCursor(a.length, 0)?.toString()
            if (b != a) {
                attach = null; updateSuggestions()
            }
        }
    }

    private fun resetState() {
        handler.removeCallbacks(confirmRunnable)
        handler.removeCallbacks(symRunnable)
        endDirect()
        pendUp = false
        word.setLength(0); seq.setLength(0); forced.clear(); colActive = false
        pendKey = null; symComposing = false
        cands = emptyList(); upper = false; prevWord = null
        attach = null; chipWord = null; recentUnknownWord = null
        shift = false; caps = false; updateShiftLook()
        updateSuggestions()
    }

    internal fun saveUserData() = dictionaryManager.saveUserData()

    private fun gluedToLetter(composingLen: Int): Boolean {
        if (composingLen <= 0) return false
        val ic = currentInputConnection ?: return false
        val t = ic.getTextBeforeCursor(composingLen + 2, 0)?.toString() ?: return false
        val idx = t.length - composingLen - 1
        if (idx < 0) return false
        val c = t[idx]
        return c.isLetter() || ((c == '\'' || c == '’') && idx >= 1 && t[idx - 1].isLetter())
    }

    private fun composingGlued(): Boolean = gluedToLetter(composingString()?.length ?: 0)

    private fun learnWord(w: String) {
        prevWord = dictionaryManager.learn(
            word = w,
            previousWord = prevWord,
            passwordLikeField = passwordLikeField(),
            autoAddWords = autoAddWords,
            addUnknownWord = { addToDictionary(it, silent = true) },
            markUnknownWord = ::markUnknown
        )
    }

    // Yalnızca tahminli (T9) yazımda kullanılır: tüm kelime tek seferde gösterildiğinden shift ilk harfe uygulanır.
    private fun shown(s: String) = when {
        caps -> s.uppercase(tr)
        upper && s.isNotEmpty() -> s.substring(0, 1).uppercase(tr) + s.substring(1)
        else -> s
    }

    // Çoklu dokunuşta öneriyi, o ana kadar yazılan harflerin büyük/küçük durumuna uydurur.
    private fun shownSuggestion(s: String): String = when {
        seq.isNotEmpty() -> shown(s)
        word.isNotEmpty() || pendKey != null -> T9Keymap.matchCase(pendingText(), s, caps)
        else -> shown(s)
    }

    private fun composingActive() =
        seq.isNotEmpty() || word.isNotEmpty() || pendKey != null || symComposing

    private fun composingString(): String? = when {
        seq.isNotEmpty() -> shown(predText())
        word.isNotEmpty() || pendKey != null -> pendingText()
        symComposing -> "@.-_/"[symIdx].toString()
        else -> null
    }

    private fun abandonComposing() {
        handler.removeCallbacks(confirmRunnable)
        handler.removeCallbacks(symRunnable)
        currentInputConnection?.finishComposingText()
        word.setLength(0); seq.setLength(0); forced.clear(); colActive = false
        pendKey = null; symComposing = false
        cands = emptyList(); upper = false
        attach = null; prevWord = null; autoSpaced = false
        updateSuggestions()
    }

    private fun checkCursorMoved() {
        if (!composingActive()) return
        val ic = currentInputConnection ?: return
        val c = composingString() ?: return
        val before = ic.getTextBeforeCursor(c.length, 0)?.toString() ?: return
        if (!before.equals(c, ignoreCase = true)) abandonComposing()
    }

    private fun ensureSpaceAfterPunct() {
        if (composingActive() || attach != null) return
        val ic = currentInputConnection ?: return
        val b = ic.getTextBeforeCursor(2, 0)?.toString() ?: return
        if (b.isEmpty()) return
        val last = b.last().toString()
        if (last in spacePunct && shouldAutoSpace(ic, last)) ic.commitText(" ", 1)
    }

    // Bekleyen harf, basıldığı andaki shift/caps durumuna göre büyük ya da küçük döner.
    private fun pendLetter(): String {
        val c = pendChars[pendIdx].toString()
        return if (pendUp) c.uppercase(tr) else c
    }

    private fun pendingText() =
        word.toString() + (if (pendKey != null) pendLetter() else "")

    private fun predText(): String {
        val exact = cands.firstOrNull { it.length == seq.length }
        return exact?.take(seq.length) ?: guessSpelling()
    }

    private fun confirmPending() {
        if (pendKey != null) {
            word.append(pendLetter()); pendKey = null
        }
    }

    private fun rememberTap(id: String, idx: Int, ch: String, up: Boolean) {
        lastTapId = id; lastTapIdx = idx; lastTapChar = ch; lastTapUp = up
        lastTapAt = SystemClock.uptimeMillis()
    }

    // Durum kaybolmuşsa (bkz. lastTapId): aynı tuşa kısa sürede basıldı ve önceki harf hâlâ imlecin
    // solundaysa o harfi siler ve sıradaki harfin indeksini döner; değilse null.
    private fun recoverMultiTap(ic: InputConnection, id: String, chars: String, useDelKey: Boolean): Int? {
        if (chars.isEmpty() || lastTapId != id || lastTapChar.isEmpty()) return null
        if (SystemClock.uptimeMillis() - lastTapAt > 900) return null
        val before = ic.getTextBeforeCursor(lastTapChar.length, 0)?.toString() ?: return null
        if (!before.equals(lastTapChar, ignoreCase = true)) return null
        if (useDelKey) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        else ic.deleteSurroundingText(lastTapChar.length, 0)
        Log.i(TAG, "multi-tap durumu uygulama tarafından sıfırlanmıştı; geri kazanıldı")
        return (lastTapIdx + 1) % chars.length
    }

    private fun tapMulti(id: String, chars: String) {
        if (directInput()) {
            directTap(id, chars); return
        }
        val ic = currentInputConnection ?: return
        autoSpaced = false
        checkCursorMoved()
        ensureSpaceAfterPunct()
        if (seq.isNotEmpty() || symComposing) commitWord(false)
        if (pendKey == id) {
            pendIdx = (pendIdx + 1) % chars.length
        } else {
            confirmPending()
            val recovered = recoverMultiTap(ic, id, chars, useDelKey = false)
            if (recovered != null) {
                pendUp = lastTapUp
                pendKey = id; pendChars = chars; pendIdx = recovered
            } else {
                // Her yeni harf kendi shift/caps durumunu alır (sadece kelimenin ilk harfi değil).
                pendUp = caps || shift
                if (shift) {
                    shift = false; updateShiftLook()
                }
                pendKey = id; pendChars = chars; pendIdx = 0
            }
        }
        colActive = true
        rememberTap(id, pendIdx, pendLetter(), pendUp)
        ic.setComposingText(pendingText(), 1)
        handler.removeCallbacks(confirmRunnable)
        handler.postDelayed(confirmRunnable, 900)
        updateSuggestions()
    }

    private fun tapPredictive(digit: Char) {
        lastTapId = null
        val ic = currentInputConnection ?: return
        autoSpaced = false
        checkCursorMoved()
        ensureSpaceAfterPunct()
        if (symComposing || word.isNotEmpty() || pendKey != null) commitWord(false)
        if (seq.isEmpty()) {
            upper = shift; shift = false; updateShiftLook()
        }
        seq.append(digit); forced.add(null); colActive = true
        refreshPredictive(ic)
    }

    internal fun refreshPredictive(ic: InputConnection) {
        val query = seq.toString()
        val dictionary = dictionaryManager.data
        val forcedSnapshot = forced.toList()
        val generation = ++predictiveSearchGeneration
        predictiveSearchJob?.cancel()
        cands = if (attach != null) fragmentCandidates() else emptyList()
        ic.setComposingText(shown(predText()), 1)
        updateSuggestions()
        if (attach != null) return

        predictiveSearchJob = serviceScope.launch {
            val matches = withContext(Dispatchers.Default) {
                synchronized(dictionary) { candidates(dictionary, query, forcedSnapshot) }
            }
            if (
                generation != predictiveSearchGeneration ||
                query != seq.toString() ||
                dictionary !== dictionaryManager.data ||
                ic !== currentInputConnection
            ) return@launch

            cands = matches
            ic.setComposingText(shown(predText()), 1)
            updateSuggestions()
        }
    }

    private fun tapSymbolKey() {
        if (directInput()) {
            directTap("1", "@.-_/"); return
        }
        val ic = currentInputConnection ?: return
        val chars = "@.-_/"
        if (symComposing) symIdx = (symIdx + 1) % chars.length
        else {
            commitWord(false); attach = null; symIdx = 0; symComposing = true
        }
        ic.setComposingText(chars[symIdx].toString(), 1)
        handler.removeCallbacks(symRunnable)
        handler.postDelayed(symRunnable, 900)
        updateSuggestions()
    }

    internal fun commitWord(
        space: Boolean,
        chosen: String? = null,
        learnIt: Boolean = true,
        finalizeWord: Boolean = false
    ) {
        val ic = currentInputConnection ?: return
        if (seq.isNotEmpty() && chosen == null && cands.isEmpty()) {
            predictiveSearchJob?.cancel()
            predictiveSearchGeneration++
            cands = candidates(dictionaryManager.data, seq.toString(), forced.toList())
        }
        lastTapId = null
        endDirect()
        checkCursorMoved()
        handler.removeCallbacks(confirmRunnable)
        handler.removeCallbacks(symRunnable)
        val before = attach
        autoSpaced = false
        val text: String? = when {
            seq.isNotEmpty() -> {
                if (chosen != null) chosen
                else {
                    val exact = cands.firstOrNull { it.length == seq.length }
                    exact ?: guessSpelling()
                }
            }

            word.isNotEmpty() || pendKey != null -> chosen ?: pendingText()
            else -> null
        }
        var newAttach: String? = null
        var committedFull: String? = null
        if (text != null) {
            val out = when {
                seq.isNotEmpty() -> shown(text)
                // Çoklu dokunuş: harfler zaten basıldıkları andaki büyük/küçük durumuyla geldi.
                chosen != null -> T9Keymap.matchCase(pendingText(), chosen, caps)
                else -> text
            }
            ic.commitText(out + (if (space) " " else ""), 1)
            committedFull = (before ?: "") + out
            if (learnIt) learnWord((before ?: "") + text)
            if (!space && chosen != null) newAttach = (before ?: "") + out
        } else {
            ic.finishComposingText()
            if (space) ic.commitText(" ", 1) else newAttach = before
        }
        val fullWord = committedFull
        recentUnknownWord = null
        if (autoAddWords) {
            // Otomatik mod: commit sonrası da kontrol et (learnWord() çağrılmadığı durumlar için)
            if (fullWord != null) autoAddIfNew(fullWord)
            if (space) detectUnknownWord()?.let { autoAddIfNew(it) }
        } else {
            // Manuel mod: commit sonrası boşlukla biten bilinmeyen kelimeyi chip olarak işaretle
            if (space) detectUnknownWord()?.let { recentUnknownWord = it }
        }
        word.setLength(0); seq.setLength(0); forced.clear(); colActive = false
        pendKey = null; symComposing = false
        cands = emptyList(); upper = false
        attach = newAttach
        updateSuggestions()
    }

    private fun chipAllowedField(): Boolean {
        if (directInput()) return false
        val t = currentInputEditorInfo?.inputType ?: 0
        if (t != 0 && (t and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> false

            else -> true
        }
    }

    private fun autoAddIfNew(typed: String) {
        if (!chipAllowedField()) return
        val w = typed.lowercase(tr)
        if (w.length < 2 || !w.all { charToDigit.containsKey(it) } || wordSet.contains(w)) return
        addToDictionary(w, silent = true)
    }

    private fun markUnknown(typed: String) {
        recentUnknownWord = null
        if (!chipAllowedField()) return
        val w = typed.lowercase(tr)
        if (w.length < 2 || !w.all { charToDigit.containsKey(it) } || wordSet.contains(w)) return
        recentUnknownWord = typed
    }

    private fun detectUnknownWord(): String? {
        recentUnknownWord = null
        val ic = currentInputConnection ?: return null
        if (!chipAllowedField()) return null
        val before = ic.getTextBeforeCursor(80, 0)?.toString() ?: return null
        if (!before.endsWith(" ")) return null
        val trimmed = before.trimEnd(' ')
        val token = trimmed.takeLastWhile { it.isLetter() }
        if (token.length < 2) return null
        val prevCh = trimmed.getOrNull(trimmed.length - token.length - 1)
        if (prevCh != null && (prevCh.isDigit() || prevCh in "@/.:#_-'’")) return null
        val w = token.lowercase(tr)
        if (!w.all { charToDigit.containsKey(it) }) return null
        return token
    }

    internal fun typeText(s: String) {
        lastTapId = null
        val wasAuto = autoSpaced
        commitWord(false, finalizeWord = true)
        endDirect()
        attach = null
        val ic = currentInputConnection
        if (ic != null) {
            if (wasAuto && s in spacePunct) {
                val b = ic.getTextBeforeCursor(2, 0)?.toString()
                if (b != null && b.length == 2 && b[1] == ' ' && b[0].toString() in spacePunct) {
                    ic.deleteSurroundingText(1, 0)
                }
            }
            ic.commitText(s, 1)
            autoSpaced = false
            if (s in spacePunct && shouldAutoSpace(ic, s)) {
                ic.commitText(" ", 1)
                autoSpaced = true
            }
        }
        prevWord = null
        updateSuggestions()
    }

    private fun startVoiceInput() {
        if (!voiceInputEnabled) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.voice_input_disabled),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        commitWord(false)
        attach = null
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        try {
            val imes =
                imm.enabledInputMethodList.sortedBy { if (it.packageName.contains("google")) 0 else 1 }
            for (imi in imes) {
                if (imi.packageName == packageName) continue
                val voice = imm.getEnabledInputMethodSubtypeList(imi, true)
                    .firstOrNull { it.mode == "voice" }
                    ?: (0 until imi.subtypeCount).map { imi.getSubtypeAt(it) }
                        .firstOrNull { it.mode == "voice" }
                if (voice != null) {
                    if (Build.VERSION.SDK_INT >= 28) {
                        switchInputMethod(imi.id, voice)
                    } else {
                        @Suppress("DEPRECATION")
                        imm.setInputMethodAndSubtype(
                            window.window!!.attributes.token,
                            imi.id,
                            voice
                        )
                    }
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sesle yazma yöntemi aranırken hata", e)
        }
        android.widget.Toast.makeText(
            this,
            getString(R.string.voice_input_not_found),
            android.widget.Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Giriş yöntemi ayarları açılamadı", e)
        }
    }

    private fun shouldAutoSpace(ic: InputConnection, s: String): Boolean {
        if (directInput()) return false
        val t = currentInputEditorInfo?.inputType ?: 0
        // Sayı, telefon ve tarih/saat alanlarında boşluk eklenmez. Sınıfı belirtmeyen (TYPE_NULL) özel
        // sohbet kutuları (ör. Gemini) metin alanı gibi davranır.
        when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME -> return false
        }
        if ((t and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            when (t and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return false
            }
        }
        // Not: TYPE_TEXT_FLAG_NO_SUGGESTIONS bilerek denetlenmez. Bu bayrak yalnızca öneri/düzeltme
        // istemediğini söyler; sohbet uygulamaları (ör. Gemini) kullanır ama noktalamadan sonra boşluk beklenir.
        val recent = ic.getTextBeforeCursor(40, 0)?.toString() ?: ""
        if (currentTokenContainsAddressMarker(recent)) return false

        if (s == "." || s == "," || s == ":") {
            val b = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (b.length >= 2 && b[b.length - 2].isDigit()) return false
        }
        return true
    }

    internal fun backspace() {
        lastTapId = null
        recentUnknownWord = null
        val ic = currentInputConnection ?: return
        autoSpaced = false
        if (directInput()) {
            // Şifre alanı: composing/deleteSurroundingText yerine gerçek DEL tuşu. Sayfa ilk açıldığında
            // bağlantı tam hazır olmasa bile tarayıcı DEL tuş olayını her zaman işler.
            endDirect()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        checkCursorMoved()
        when {
            seq.isNotEmpty() -> {
                seq.setLength(seq.length - 1)
                if (forced.isNotEmpty()) forced.removeAt(forced.lastIndex)
                colActive = false
                if (seq.isEmpty()) {
                    ic.setComposingText("", 1); ic.finishComposingText()
                    cands = emptyList(); upper = false; updateSuggestions()
                } else refreshPredictive(ic)
            }

            pendKey != null || word.isNotEmpty() -> {
                handler.removeCallbacks(confirmRunnable)
                colActive = false
                if (pendKey != null) pendKey = null else word.setLength(word.length - 1)
                if (word.isEmpty()) {
                    ic.setComposingText("", 1); ic.finishComposingText(); upper = false
                } else ic.setComposingText(word.toString(), 1)
                updateSuggestions()
            }

            symComposing -> {
                handler.removeCallbacks(symRunnable)
                ic.setComposingText("", 1); ic.finishComposingText()
                symComposing = false; updateSuggestions()
            }

            else -> {
                val sel = ic.getSelectedText(0)
                if (!sel.isNullOrEmpty()) {
                    ic.commitText("", 1); attach = null
                } else {
                    ic.deleteSurroundingTextInCodePoints(1, 0)
                    attach = attach?.dropLast(1)?.takeIf { it.isNotEmpty() }
                }
                updateSuggestions()
            }
        }
    }

    private fun sendEnterKey(ic: InputConnection) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    // Enter tuşu: Gboard gibi davranır.
    //  - Alanın bir IME eylemi varsa (Ara / Git / Gönder / Bitti / İleri) ham ENTER tuşu yerine
    //    performEditorAction() çağrılır. Birçok uygulama (Trendyol mağaza araması, n11 vb.) aramayı
    //    sadece onEditorAction ile tetikler ve klavyeyi de o akışta kapatır.
    //  - Çok satırlı alanlarda veya IME_FLAG_NO_ENTER_ACTION varsa satır sonu (ENTER) gönderilir.
    private fun enter() {
        commitWord(false, finalizeWord = true)
        attach = null
        prevWord = null
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val opts = ei?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val multiLine = ((ei?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val hasAction = action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED
        val customLabel = !ei?.actionLabel.isNullOrEmpty()
        if (!noEnterAction && !multiLine && (hasAction || customLabel)) {
            val id = if (customLabel) ei.actionId else action
            if (!ic.performEditorAction(id)) sendEnterKey(ic)
        } else {
            sendEnterKey(ic)
        }
        updateSuggestions()
    }

    private fun editorAction() {
        commitWord(false, finalizeWord = true)
        attach = null
        prevWord = null
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        }
        updateSuggestions()
    }

    private fun enterKey(): View {
        val k = key("", accent = true, onLong = { editorAction() }) { enter() }
        k.addView(
            IconView(this, IconView.ENTER, accentColor, dp(3).toFloat()),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        )
        return k
    }

    private fun pickSuggestion(s: String) {
        if (composingActive()) {
            commitWord(false, s)
        } else {
            currentInputConnection?.commitText(s, 1)
            learnWord(s)
        }
        seq.setLength(0)
        word.setLength(0)
        cands = emptyList()
        attach = null
        prevWord = s
        updateSuggestions()
    }

    private fun computeChip(): String? {
        if (autoAddWords) return null
        if (recentUnknownWord != null && !composingActive() && attach == null) {
            return recentUnknownWord
        }
        val shownFrag: String
        when {
            seq.isNotEmpty() -> {
                if (attach == null) {
                    if (cands.any { it.length == seq.length }) return null
                } else if (!forced.all { it != null }) return null
                shownFrag = shown(predText())
            }

            word.isNotEmpty() || pendKey != null -> {
                if (attach != null && pendKey != null) return null
                shownFrag = pendingText()
            }

            attach != null -> shownFrag = ""
            else -> return null
        }
        val whole = (attach ?: "") + shownFrag
        val w = whole.lowercase(tr)
        if (w.length < 2 || wordSet.contains(w) || !w.all { charToDigit.containsKey(it) }) return null
        if (attach == null && composingActive() && composingGlued()) return null
        return whole
    }

    private fun addToDictionary(w0: String, silent: Boolean = false) {
        dictionaryManager.syncUserDictionary()
        val w = w0.lowercase(tr)
        if (w.length < 2 || !w.all { charToDigit.containsKey(it) }) return
        synchronized(dictionaryManager.data) {
            dictionaryManager.addWord(w)
            dictionaryManager.data.userWords.add(w)
            dictionaryManager.data.userCount[w] =
                (dictionaryManager.data.userCount[w] ?: 0) + 1
        }
        if (dictionaryManager.addedWordsChangedOrRebuilding()) {
            UserDictionaryStore.add(this, w)
            dictionaryManager.rebuildDictionary()
        } else dictionaryManager.saveUserData()
        try {
            android.provider.UserDictionary.Words.addWord(this, w, 250, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Sistem sözlüğüne kelime eklenemedi", e)
        }
        recentUnknownWord = null
        if (silent) return
        android.widget.Toast.makeText(
            this,
            "\"$w\" sözlüğe eklendi",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        val ic = currentInputConnection
        if (seq.isNotEmpty() && ic != null && attach == null) refreshPredictive(ic) else updateSuggestions()
    }

    private fun dismissBar() {
        recentUnknownWord = null
        commitWord(false, learnIt = false)
        attach = null
        prevWord = null
        seq.setLength(0)
        word.setLength(0)
        cands = emptyList()
        letterMode = false
        updateSuggestions()
    }

    internal fun updateSuggestions() {
        val prefixGeneration = ++prefixSearchGeneration
        prefixSearchJob?.cancel()
        if (composingActive() || attach != null) recentUnknownWord = null
        if (recentUnknownWord != null) {
            suggestions = emptyList()
        } else {
            letterMode = seq.isNotEmpty() && attach == null && cands.isEmpty()
            suggestions = when {
                letterMode -> letterCandidates()
                seq.length == 1 -> {
                    val letters = letterCandidates()
                    (letters + cands).distinct()
                }

                seq.isNotEmpty() -> cands
                word.isNotEmpty() || pendKey != null -> {
                    if (attach != null) {
                        suffixCompletions(pendingText())
                    } else {
                        val prefix = pendingText()
                        val dictionary = dictionaryManager.data
                        suggestions = emptyList()
                        prefixSearchJob = serviceScope.launch {
                            val matches = withContext(Dispatchers.Default) {
                                synchronized(dictionary) { prefixCandidates(dictionary, prefix) }
                            }
                            if (
                                prefixGeneration == prefixSearchGeneration &&
                                dictionary === dictionaryManager.data &&
                                seq.isEmpty() &&
                                attach == null &&
                                (word.isNotEmpty() || pendKey != null) &&
                                pendingText() == prefix &&
                                recentUnknownWord == null
                            ) {
                                suggestions = matches
                                chipWord = computeChip()
                                renderSuggestions()
                            }
                        }
                        emptyList()
                    }
                }

                symComposing -> emptyList()
                attach != null -> emptyList()
                else -> nextWords(prevWord)
            }
        }
        chipWord = computeChip()
        autoCap()
        updateLeftColumn()
        renderSuggestions()
    }

    private fun renderSuggestions() {
        if (!::suggestRow.isInitialized) return
        suggestRow.removeAllViews()
        val chip = chipWord
        if (suggestions.isEmpty() && chip == null) {
            iconBar.visibility = View.VISIBLE
            suggestBox.visibility = View.GONE
            return
        }
        iconBar.visibility = View.GONE
        suggestBox.visibility = View.VISIBLE
        val isChipOnly = chip != null && suggestions.isEmpty()
        closeBtn.visibility =
            if (chip != null || suggestions.isNotEmpty()) View.VISIBLE else View.GONE
        suggestLeftSpacer.visibility = if (isChipOnly) View.VISIBLE else View.GONE
        suggestRow.gravity =
            if (isChipOnly) Gravity.CENTER else (Gravity.START or Gravity.CENTER_VERTICAL)

        fun addChip() {
            if (chip == null) return
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { addToDictionary(chip) }
            }
            c.addView(TextView(this).apply { text = "⊕"; textSize = 28f; setTextColor(accentColor) })
            c.addView(TextView(this).apply {
                text = chip; textSize = 19f; setTextColor(textColor); setPadding(dp(8), 0, 0, 0)
            })
            suggestRow.addView(c, LinearLayout.LayoutParams(-2, MATCH_PARENT))
        }

        val chipFirst = suggestions.isEmpty() || letterMode
        if (chipFirst) addChip()
        suggestions.forEachIndexed { i, s ->
            val tv = TextView(this).apply {
                text = shownSuggestion(s); textSize = if (letterMode) 24f else 19f; gravity = Gravity.CENTER
                setTextColor(if (i == 0 && composingActive() && !(chipFirst && chip != null && !letterMode)) accentColor else textColor)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener { if (letterMode) pickCandidateLetter(s[0]) else pickSuggestion(s) }
            }
            suggestRow.addView(tv, LinearLayout.LayoutParams(-2, MATCH_PARENT))
        }
        if (!chipFirst) addChip()
        suggestScroll.scrollTo(0, 0)
    }

    private fun updateShiftLook() {
        shiftIcon?.setLook(
            when {
                caps -> IconView.SHIFT_CAPS; shift -> IconView.SHIFT_ON; else -> IconView.SHIFT_OFF
            },
            if (caps || shift) accentColor else shiftOffColor
        )
        updateKeyLabels()
    }

    private fun updateKeyLabels() {
        val up = shift || caps
        letterLabels.forEach { (tv, base) ->
            tv.text = if (up) base.uppercase(tr) else base.lowercase(tr)
        }
    }

    private fun onShiftTap() {
        // Shift'e basılınca bekleyen harf onaylanır; sonraki tuş yeni bir harf başlatır ve shift'i alır.
        if (directInput()) endDirect()
        else if (pendKey != null) {
            handler.removeCallbacks(confirmRunnable)
            confirmPending()
        }
        val now = System.currentTimeMillis()
        when {
            caps -> {
                caps = false; shift = false
            }

            shift && now - lastShiftTap < 450 -> {
                caps = true; shift = false
            }

            else -> shift = !shift
        }
        lastShiftTap = now
        updateShiftLook()
    }

    private fun autoCap() {
        if (caps || composingActive()) return
        if (directInput()) return
        val ic = currentInputConnection ?: return
        val t = currentInputEditorInfo?.inputType ?: return
        if ((t and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return
        // Alan otomatik büyük harf istemiyorsa (e-posta, URL, şifre...) kullanıcının bastığı shift'e dokunma.
        val capFlags = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if ((t and capFlags) == 0) return
        val want = ic.getCursorCapsMode(t) != 0
        if (want != shift) {
            shift = want; updateShiftLook()
        }
    }

    // Yalnızca şifre ve e-posta alanlarında tahmin (T9) kullanılmaz; tuşlar otomatik olarak çoklu dokunuşla
    // (multi-tap) çalışır. Kullanıcının tahmin seçimi (predictive) korunur.
    // Not: URI (tarayıcı adres çubuğu) ve TYPE_TEXT_FLAG_NO_SUGGESTIONS bilerek engellenmez; Chrome, Opera ve
    // sistem arama kutuları bu bayrakları kullandığı halde kullanıcı orada T9 ile yazmak ister.
    private fun predictionBlockedField(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return false
        if ((t and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> !forcedHere

            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true

            else -> false
        }
    }

    private fun predictiveEffective(): Boolean = predictive && !predictionBlockedField()

    // Şifre alanlarında (ve sayfası henüz hazır olmayan tarayıcı alanlarında) composing kullanılmaz.
    // - Metin şifresi / web şifresi / görünür şifre / sayısal şifre
    // - inputType == 0 olan tarayıcı alanı: sayfa ilk açıldığında Chromium tabanlı tarayıcılar (Opera, Chrome...)
    //   henüz hazır olmayan bir bağlantı bildirebilir; bu durumda composing ve deleteSurroundingText
    //   çalışmaz, yalnızca tek karakterlik yazım ve tuş olayları iş görür.
    private fun directInput(): Boolean {
        val ei = currentInputEditorInfo ?: return false
        val t = ei.inputType
        return when (t and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> when (t and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> !forcedHere

                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true

                else -> false
            }

            InputType.TYPE_CLASS_NUMBER ->
                (t and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            0 -> t == 0 && isBrowserPackage(ei.packageName)
            else -> false
        }
    }

    private fun isBrowserPackage(pkg: String?): Boolean =
        pkg != null && KeyboardData.BROWSER_PACKAGES.any { pkg.startsWith(it) }

    private fun endDirect() {
        handler.removeCallbacks(dirRunnable)
        dirId = null
    }

    // Doğrudan çoklu dokunuş: harfi hemen yazar; 900 ms içinde aynı tuşa basılırsa önceki harfi silip
    // sıradakini yazar. Büyük/küçük durum her harf için basıldığı anda belirlenir.
    private fun directTap(id: String, chars: String) {
        val ic = currentInputConnection ?: return
        autoSpaced = false
        if (dirId == id && chars.isNotEmpty()) {
            dirIdx = (dirIdx + 1) % chars.length
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        } else {
            val recovered = recoverMultiTap(ic, id, chars, useDelKey = true)
            if (recovered != null) {
                dirId = id; dirChars = chars; dirIdx = recovered; dirUp = lastTapUp
            } else {
                dirId = id; dirChars = chars; dirIdx = 0
                dirUp = caps || shift
                if (shift) {
                    shift = false; updateShiftLook()
                }
            }
        }
        val ch = chars[dirIdx].toString()
        val out = if (dirUp) ch.uppercase(tr) else ch
        ic.commitText(out, 1)
        rememberTap(id, dirIdx, out, dirUp)
        handler.removeCallbacks(dirRunnable)
        handler.postDelayed(dirRunnable, 900)
    }

    internal fun updatePredLook() {
        predView?.setTextColor(if (predictiveEffective()) accentColor else secondaryTextColor)
    }

    private fun pickCandidateLetter(ch: Char) {
        val ic = currentInputConnection ?: return
        checkCursorMoved()
        if (!composingActive()) return
        if (letterPos in seq.indices && forced.size >= seq.length) {
            forced[letterPos] = ch
            refreshPredictive(ic)
        }
    }

    private fun updateLeftColumn() {
        if (!::leftCol.isInitialized) return
        // Sembol tuşları sabit; her tuş basışında 4 tuşu baştan üretmek gereksizdi.
        if (leftCol.childCount > 0) return
        listOf("!", "?", "'", "~").forEach { sym -> add(leftCol, key(sym) { typeText(sym) }, 1f) }
    }

    internal fun showPage(p: View, toggle: Boolean = true) {
        commitWord(false)
        attach = null
        if (p !== cursorPage && selectMode) {
            selectMode = false; updateSelLook()
        }
        val target = if (toggle && p !== t9Page && p.isVisible) t9Page else p
        ensurePageBuilt(target)
        if (target === emojiPage) {
            loadRecentEmojis()
            populateRecentRow()
        }
        pages.forEach { it.visibility = if (it === target) View.VISIBLE else View.GONE }
        if (::hideOrReturnTool.isInitialized) {
            val isReturn = (target !== t9Page && target !== numPage)
            hideOrReturnTool.text = if (isReturn) "↩" else "⌄"
            hideOrReturnTool.setTextColor(if (isReturn) accentColor else iconColor)
        }
        if (target === clipPage) refreshClips()
    }

    // Emoji, pano, ayarlar ve tema sayfaları ilk açılışta değil, ilk kez gösterildiklerinde kurulur;
    // klavyenin ilk görünme süresini kısaltır (özellikle ~130 görünümlük emoji sayfası).
    private val lazyPageBuilders = HashMap<View, () -> View>()

    private fun lazyPage(builder: () -> View): View {
        val holder = FrameLayout(this)
        lazyPageBuilders[holder] = builder
        return holder
    }

    private fun ensurePageBuilt(page: View) {
        val builder = lazyPageBuilders.remove(page) ?: return
        (page as FrameLayout).addView(builder(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun menuAction(id: Int) {
        commitWord(false)
        attach = null
        currentInputConnection?.performContextMenuAction(id)
    }

    internal fun add(parent: LinearLayout, v: View, w: Float) {
        val lp = if (parent.orientation == LinearLayout.HORIZONTAL)
            LinearLayout.LayoutParams(0, MATCH_PARENT, w)
        else LinearLayout.LayoutParams(MATCH_PARENT, 0, w)
        val m = if (isLandscape) dp(1) else dp(2)
        lp.setMargins(m, m, m, m)
        parent.addView(v, lp)
    }

    internal fun key(
        label: String,
        sub: String? = null,
        topSub: String? = null,
        accent: Boolean = false,
        size: Float = 22f,
        repeat: Boolean = false,
        accel: Boolean = false,
        bg: Int = keyColor,
        onLong: (() -> Unit)? = null,
        onClick: () -> Unit
    ): FrameLayout {
        val f = FrameLayout(this)
        f.background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(6).toFloat() }
        val scaledSize = if (isLandscape) (size * 0.82f) else size
        val main = TextView(this).apply {
            text = label; textSize = scaledSize; gravity = Gravity.CENTER
            setTextColor(if (accent) accentColor else textColor)
        }
        f.tag = main
        f.addView(main, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        if (sub != null) {
            val subSize = if (isLandscape) 9f else 11f
            val s = TextView(this).apply { text = sub; textSize = subSize; setTextColor(secondaryTextColor) }
            f.addView(s, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(4), dp(2))
            })
        }
        if (topSub != null) {
            val topSubSize = if (isLandscape) 10f else 12f
            val ts =
                TextView(this).apply { text = topSub; textSize = topSubSize; setTextColor(secondaryTextColor) }
            f.addView(ts, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(2), dp(4), 0)
            })
        }
        bindTouch(f, repeat, onLong, accel, onClick)
        return f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTouch(
        f: View,
        repeat: Boolean,
        onLong: (() -> Unit)?,
        accel: Boolean,
        onClick: () -> Unit
    ) {
        if (repeat) {
            var delay = 35L
            val r = object : Runnable {
                override fun run() {
                    playClick(); onClick()
                    if (accel) delay = maxOf(25L, (delay * 0.6).toLong())
                    handler.postDelayed(this, delay)
                }
            }
            f.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.6f
                        delay = if (accel) 90L else 35L
                        playClick(); onClick(); handler.postDelayed(r, 400)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1f; handler.removeCallbacks(r)
                    }
                }
                true
            }
        } else {
            f.setOnClickListener { playClick(); onClick() }
            if (onLong != null) f.setOnLongClickListener { playClick(); onLong(); true }
        }
    }

    private fun iconKey(kind: Int, repeat: Boolean = false, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.background = GradientDrawable().apply { setColor(dimColor); cornerRadius = dp(6).toFloat() }
        f.addView(
            IconView(this, kind, accentColor, dp(2).toFloat() * 1.2f),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        )
        bindTouch(f, repeat, null, false, onClick)
        return f
    }

    private fun t9Key(label: String, chars: String, digit: String): View {
        val k = key(
            if (digit == "1") label else label.lowercase(tr),
            digit,
            onLong = { typeText(digit) }) {
            when {
                digit == "1" -> tapSymbolKey()
                predictiveEffective() -> tapPredictive(digit[0])
                else -> tapMulti(label, chars)
            }
        }
        if (digit != "1") letterLabels.add((k.tag as TextView) to label)
        return k
    }

    internal fun spaceKey(label: String, w: Float = 1f): View {
        val k = key(label, size = 14f, onLong = { startVoiceInput() }) {
            if (autoSpaced && !composingActive()) autoSpaced = false
            else commitWord(true)
        }
        val gray = secondaryTextColor
        (k.tag as? TextView)?.apply {
            setTextColor(gray)
            if (label.isNotEmpty()) {
                val sp = android.text.SpannableString(label)
                label.forEachIndexed { i, c ->
                    if (c == '◂' || c == '▸') {
                        sp.setSpan(
                            android.text.style.RelativeSizeSpan(0.7f), i, i + 1,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                text = sp
            }
        }
        k.addView(
            IconView(
                this,
                IconView.MIC,
                if (lightTheme) Color.BLACK else Color.WHITE,
                dp(2).toFloat()
            ),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6)
                rightMargin = dp(8)
            }
        )
        return k
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun navInset(): Int {
        if (Build.VERSION.SDK_INT < 35) return 0
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun qwertyKey(baseLetter: String, sub: String? = null, weight: Float = 1f): View {
        val k = key(
            baseLetter.lowercase(tr),
            sub = sub,
            size = 18f,
            onLong = sub?.let { s -> { typeText(s) } }) {
            val isUp = shift || caps || upper
            val textToType = if (isUp) baseLetter.uppercase(tr) else baseLetter.lowercase(tr)
            typeText(textToType)
            if (shift) {
                shift = false
                updateShiftLook()
            }
        }
        letterLabels.add((k.tag as TextView) to baseLetter)
        return k
    }

    private fun buildQwertyPage(): View {
        letterLabels.clear()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Row 1: Q W E R T Y U I O P Ğ Ü
        val row1Items = listOf(
            "Q" to "1", "W" to "2", "E" to "3", "R" to "4",
            "T" to "5", "Y" to "6", "U" to "7", "I" to "8",
            "O" to "9", "P" to "0", "Ğ" to "₺", "Ü" to "»"
        )
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1Items.forEach { (b, s) -> add(row1, qwertyKey(b, s), 1f) }

        // Row 2: A S D F G H J K L Ş İ
        val row2Items = listOf(
            "A" to "\\", "S" to "_", "D" to "(", "F" to ":",
            "G" to ")", "H" to "&", "J" to "#", "K" to "*",
            "L" to "\"", "Ş" to "|", "İ" to "€"
        )
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2Items.forEach { (b, s) -> add(row2, qwertyKey(b, s), 1f) }

        // Row 3: Shift + Z X C V B N M Ö Ç + Backspace
        val row3Items = listOf(
            "Z" to "@", "X" to "/", "C" to "-", "V" to "'",
            "B" to "!", "N" to "?", "M" to ";", "Ö" to "`", "Ç" to "÷"
        )
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sk = key("", accent = true) { onShiftTap() }
        val shiftIc = IconView(this, IconView.SHIFT_OFF, shiftOffColor, dp(3).toFloat())
        sk.addView(shiftIc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        shiftIcon = shiftIc
        add(row3, sk, 1.3f)

        row3Items.forEach { (b, s) -> add(row3, qwertyKey(b, s), 1f) }

        val bk = key("⌫", accent = true, bg = dimColor, repeat = true, accel = true) { backspace() }
        add(row3, bk, 1.3f)

        // Row 4: 12# , Space . Enter
        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val b12 = key("12#", accent = true) { showPage(sym1) }
        val commaKey = key(",", sub = "`", onLong = { typeText("`") }) { typeText(",") }
        val space = spaceKey("◂ Türkçe ▸")
        val dotKey = key(".", onLong = { typeText("…") }) { typeText(".") }
        val ent = enterKey()

        add(row4, b12, 1.3f)
        add(row4, commaKey, 1f)
        add(row4, space, 4.5f)
        add(row4, dotKey, 1f)
        add(row4, ent, 1.3f)

        add(page, row1, 1f)
        add(page, row2, 1f)
        add(page, row3, 1f)
        add(page, row4, 1f)

        updateShiftLook()
        return page
    }

    private fun buildT9Page(): View {
        if (isLandscape) return buildQwertyPage()
        letterLabels.clear()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        leftCol = left
        updateLeftColumn()
        add(main, left, 0.65f)

        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            listOf(Triple("@", "", "1"), Triple("ABC", "abcçâ", "2"), Triple("DEF", "def", "3")),
            listOf(
                Triple("GHİ", "ghiığî", "4"),
                Triple("JKL", "jkl", "5"),
                Triple("MNO", "mnoö", "6")
            ),
            listOf(
                Triple("PQRS", "pqrsş", "7"),
                Triple("TUV", "tuvüû", "8"),
                Triple("WXYZ", "wxyz", "9")
            )
        )
        rows.forEach { r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEach { (l, c, d) -> add(row, t9Key(l, c, d), 1f) }
            add(mid, row, 1f)
        }
        add(main, mid, 3f)

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(right, key("⌫", accent = true, repeat = true, accel = true) { backspace() }, 1f)
        val pk = key("a✓", sub = "0", accent = true, onLong = { typeText("0") }) {
            commitWord(false)
            onPredictionKey()
            updatePredLook()
            updateSuggestions()
        }
        predView = pk.tag as TextView
        updatePredLook()
        add(right, pk, 1f)
        val sk = key("", accent = true) { onShiftTap() }
        val shiftIc = IconView(this, IconView.SHIFT_OFF, shiftOffColor, dp(3).toFloat())
        sk.addView(shiftIc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        shiftIcon = shiftIc
        updateShiftLook()
        add(right, sk, 1f)
        add(main, right, 0.65f)

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        add(bottom, key("12#", accent = true) { showPage(sym1) }, 0.65f)
        add(bottom, key(",") { typeText(",") }, 0.45f)
        add(bottom, spaceKey("◂ Türkçe ▸"), 1.95f)
        add(bottom, key(".") { typeText(".") }, 0.45f)
        add(bottom, enterKey(), 0.65f)

        add(page, main, 3f)
        add(page, bottom, 1f)
        return page
    }

    private class SK(val l: String, val sub: String? = null, val w: Float = 1f)

    private fun symRow(items: List<SK>, backspaceW: Float = 0f): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { k ->
            val long: (() -> Unit)? = k.sub?.let { s -> { typeText(s) } }
            add(row, key(k.l, k.sub, size = 20f, onLong = long) { typeText(k.l) }, k.w)
        }
        if (backspaceW > 0f) add(
            row,
            key("⌫", accent = true, repeat = true, accel = true) { backspace() },
            backspaceW
        )
        return row
    }

    private fun buildSym1(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val d = 1.4f
        add(
            page, symRow(
                listOf(
                    SK("1", null, d), SK("2", null, d), SK("3", null, d),
                    SK("+"), SK("-", "_"), SK("@", "&"), SK("₺", "€"), SK("(", "<"), SK(")", ">")
                )
            ), 1f
        )
        add(
            page, symRow(
                listOf(
                    SK("4", null, d), SK("5", null, d), SK("6", null, d),
                    SK("*"), SK("/"), SK("'", "‹"), SK("\"", "«"), SK(":", ";"), SK("#", "%")
                )
            ), 1f
        )
        add(
            page, symRow(
                listOf(
                    SK("7", null, d), SK("8", null, d), SK("9", null, d),
                    SK(","), SK("="), SK("!", "¡"), SK("?", "¿")
                ), 2f
            ), 1f
        )
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        add(b, key("abc", accent = true) { showPage(t9Page) }, 1.4f)
        add(b, key("0", size = 20f) { typeText("0") }, 1.4f)
        add(b, key(".", size = 20f) { typeText(".") }, 1.4f)
        add(b, spaceKey(""), 3.6f)
        add(b, key("1/2", accent = true) { showPage(sym2) }, 1.2f)
        add(b, enterKey(), 1.2f)
        add(page, b, 1f)
        return page
    }

    private fun buildSym2(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(
            page,
            symRow(listOf("[", "]", "{", "}", "<", ">", "&", "_", "%", "√").map { SK(it) }),
            1f
        )
        add(
            page, symRow(
                listOf(
                    SK("|", "¦"), SK("\\", "§"), SK("~"), SK("•", "°"), SK("`"),
                    SK("…"), SK("€", "$"), SK("¥"), SK("£"), SK("¢")
                )
            ), 1f
        )
        add(
            page, symRow(
                listOf(
                    SK("α"), SK("β"), SK("^", "Δ"), SK("®"), SK("©"),
                    SK("™"), SK("π", "Π"), SK("¤", "Φ")
                ), 2f
            ), 1f
        )
        val b = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        add(b, key("abc", accent = true) { showPage(t9Page) }, 1.4f)
        add(b, key("☺") { showPage(emojiPage) }, 1.4f)
        add(b, spaceKey(""), 4.2f)
        add(b, key("2/2", accent = true) { showPage(sym1) }, 1.5f)
        add(b, enterKey(), 1.5f)
        add(page, b, 1f)
        return page
    }

    internal val recentEmojis = mutableListOf<String>()

    internal val emojiReady: Boolean get() = ::recentRowContainer.isInitialized
    internal lateinit var recentRowContainer: LinearLayout

    private fun sendNav(code: Int) {
        commitWord(false)
        attach = null
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        val meta = if (selectMode) KeyEvent.META_SHIFT_ON else 0
        if (selectMode) ic.sendKeyEvent(
            KeyEvent(
                t,
                t,
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SHIFT_LEFT,
                0,
                KeyEvent.META_SHIFT_ON
            )
        )
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
        if (selectMode) ic.sendKeyEvent(
            KeyEvent(
                t,
                t,
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SHIFT_LEFT,
                0,
                0
            )
        )
    }

    private fun updateSelLook() {
        if (!::selKey.isInitialized) return
        (selKey.background as? GradientDrawable)?.setColor(if (selectMode) selectionBackgroundColor else keyColor)
        (selKey.tag as? TextView)?.setTextColor(if (selectMode) accentColor else textColor)
    }

    private fun buildCursorPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(vararg k: View): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            k.forEach { add(r, it, 1f) }
            return r
        }

        fun txt(label: String, f: () -> Unit) = key(label, size = 16f) { f() }
        selKey = key("Seç", size = 16f) { selectMode = !selectMode; updateSelLook() }
        updateSelLook()
        add(
            page, row(
            txt("Pano") { showPage(clipPage) },
            iconKey(IconView.UP, repeat = true) { sendNav(KeyEvent.KEYCODE_DPAD_UP) },
            txt("Tümü") { menuAction(android.R.id.selectAll) },
            txt("Kopyala") { menuAction(android.R.id.copy) },
            key("⌫", accent = true, bg = dimColor, repeat = true, accel = true) { backspace() }), 1f
        )
        add(
            page, row(
            iconKey(IconView.LEFT, repeat = true) { sendNav(KeyEvent.KEYCODE_DPAD_LEFT) },
            selKey,
            iconKey(IconView.RIGHT, repeat = true) { sendNav(KeyEvent.KEYCODE_DPAD_RIGHT) },
            txt("Kes") { menuAction(android.R.id.cut) },
            iconKey(IconView.SPACE) { commitWord(true) }), 1f
        )
        add(
            page, row(
            txt("Giriş") { sendNav(KeyEvent.KEYCODE_MOVE_HOME) },
            iconKey(IconView.DOWN, repeat = true) { sendNav(KeyEvent.KEYCODE_DPAD_DOWN) },
            txt("Son") { sendNav(KeyEvent.KEYCODE_MOVE_END) },
            txt("Yapıştır") { menuAction(android.R.id.paste) },
            key("↩", accent = true, bg = dimColor) { showPage(t9Page) }), 1f
        )
        return page
    }

    private fun isNumericField(info: EditorInfo?): Boolean {
        val cls = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER ||
                cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME
    }

    private fun updateNumActionLabel(info: EditorInfo?) {
        val lbl: String = run {
            if (info == null) return@run "↵"
            val custom = info.actionLabel
            if (!custom.isNullOrEmpty()) return@run custom.toString()
            when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_DONE -> "Bitti"
                EditorInfo.IME_ACTION_GO -> "Git"
                EditorInfo.IME_ACTION_NEXT -> "İleri"
                EditorInfo.IME_ACTION_SEARCH -> "Ara"
                EditorInfo.IME_ACTION_SEND -> "Gönder"
                EditorInfo.IME_ACTION_PREVIOUS -> "Geri"
                else -> "↵"
            }
        }
        numActionLabel?.apply {
            text = lbl
            textSize = if (lbl == "↵") 26f else 16f
        }
    }

    private fun typeNum(ch: String) {
        val t = currentInputEditorInfo?.inputType ?: 0
        if (t == 0 && ch.length == 1 && ch[0] in '0'..'9') {
            commitWord(false)
            sendDownUpKeyEvents(KeyEvent.KEYCODE_0 + (ch[0] - '0'))
        } else {
            typeText(ch)
        }
    }

    private fun numBackspace() {
        endDirect()
        val inputType = currentInputEditorInfo?.inputType ?: 0
        if (inputType == 0 || isNumericField(currentInputEditorInfo)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        } else {
            backspace()
        }
    }

    private fun numAction() {
        val ei = currentInputEditorInfo
        val a = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (a != EditorInfo.IME_ACTION_NONE && a != EditorInfo.IME_ACTION_UNSPECIFIED) editorAction() else enter()
    }

    private fun buildNumPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun digit(d: String) = key(d, size = 28f) { typeNum(d) }
        fun sym(s: String) = key(s, accent = true, bg = dimColor, size = 24f) { typeNum(s) }
        fun row(vararg v: View): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            v.forEachIndexed { i, view ->
                add(
                    r,
                    view,
                    if (i == 0 || i == v.size - 1) 0.85f else 1f
                )
            }
            return r
        }

        val back = key("↩", accent = true, bg = dimColor, size = 26f) { showPage(t9Page) }
        val space = iconKey(IconView.SPACE) {
            endDirect(); currentInputConnection?.commitText(" ", 1)
        }
        val action = key("↵", accent = true, bg = dimColor, size = 26f) { numAction() }
        numActionLabel = action.tag as? TextView
        add(
            page, row(
            sym("+"), digit("1"), digit("2"), digit("3"),
            key("⌫", accent = true, bg = dimColor, repeat = true, accel = true) { numBackspace() }), 1f
        )
        add(page, row(sym("-"), digit("4"), digit("5"), digit("6"), back), 1f)
        add(page, row(sym("."), digit("7"), digit("8"), digit("9"), space), 1f)
        add(page, row(sym("/"), sym("*"), digit("0"), sym("#"), action), 1f)
        return page
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        keyboardRootView = root
        navBottomPx = navInset()
        applyRootPadding()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            navBottomPx = if (nav > 0) nav else navInset()
            applyRootPadding()
            insets
        }

        val topFrame = FrameLayout(this)
        iconBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val toolSize = if (isLandscape) 18f else 22f
        fun tool(txt: String, f: () -> Unit) = TextView(this).apply {
            text = txt; textSize = toolSize; gravity = Gravity.CENTER
            setTextColor(iconColor); setOnClickListener { f() }
        }

        val hideBtn = tool("⌄") {
            if (::t9Page.isInitialized && ::numPage.isInitialized &&
                t9Page.visibility != View.VISIBLE && numPage.visibility != View.VISIBLE
            ) {
                showPage(t9Page)
            } else {
                requestHideSelf(0)
            }
        }

        fun iconTool(kind: Int, f: () -> Unit): FrameLayout {
            val fv = FrameLayout(this)
            fv.addView(
                IconView(this, kind, iconColor, dp(2).toFloat()),
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            )
            fv.setOnClickListener { f() }
            return fv
        }
        hideOrReturnTool = hideBtn
        listOf(
            tool("☰") { showPage(settingsPage) },
            iconTool(IconView.CURSOR) { showPage(cursorPage) },
            tool("📋") { showPage(clipPage) },
            tool("☺") { showPage(emojiPage) },
            hideBtn
        ).forEach { iconBar.addView(it, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
        suggestLeftSpacer = View(this)
        suggestScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        suggestRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        suggestScroll.addView(suggestRow, FrameLayout.LayoutParams(-2, MATCH_PARENT))
        closeBtn = TextView(this).apply {
            text = "✕"; textSize = if (isLandscape) 16f else 20f; gravity = Gravity.CENTER
            setTextColor(iconColor); setOnClickListener { dismissBar() }
        }
        suggestBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        suggestBox.addView(suggestLeftSpacer, LinearLayout.LayoutParams(dp(48), MATCH_PARENT))
        suggestBox.addView(suggestScroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        suggestBox.addView(closeBtn, LinearLayout.LayoutParams(dp(48), MATCH_PARENT))
        topFrame.addView(iconBar, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        topFrame.addView(suggestBox, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val topBarH = if (isLandscape) dp(32) else dp(44)
        root.addView(topFrame, LinearLayout.LayoutParams(MATCH_PARENT, topBarH))

        t9Page = buildT9Page()
        sym1 = buildSym1()
        sym2 = buildSym2()
        lazyPageBuilders.clear()
        emojiPage = lazyPage { buildEmojiPage() }
        cursorPage = buildCursorPage()
        clipPage = lazyPage { buildClipPage() }
        settingsPage = lazyPage { buildSettingsPage() }
        themePage = lazyPage { buildThemePage() }
        numPage = buildNumPage()
        pages = listOf(
            t9Page,
            sym1,
            sym2,
            emojiPage,
            cursorPage,
            clipPage,
            settingsPage,
            themePage,
            numPage
        )
        val content = FrameLayout(this)
        contentLayoutView = content
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        pages.forEach { it.visibility = if (it === t9Page) View.VISIBLE else View.GONE }
        val savedHeight = prefs.getInt("kb_height", 0)
        val contentHeight = if (isLandscape) {
            val maxH =
                (resources.displayMetrics.heightPixels * 0.38f).toInt().coerceAtLeast(dp(100))
            minOf(dp(135), maxH)
        } else {
            if (savedHeight > 0) savedHeight.coerceIn(dp(160), dp(520)) else dp(4 * 58)
        }
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, contentHeight))

        renderSuggestions()

        val kbFrame = FrameLayout(this).apply { setBackgroundColor(backgroundColor) }
        kbFrame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, -2))
        keyboardFrameView = kbFrame
        val outer = FrameLayout(this)
        val lift = if (isLandscape) 0 else prefs.getInt("kb_lift", 0).coerceIn(0, dp(200))
        outer.addView(kbFrame, FrameLayout.LayoutParams(MATCH_PARENT, -2, Gravity.BOTTOM).apply {
            bottomMargin = lift
        })
        applyNavBarAppearance()
        return outer
    }

    private fun applyRootPadding() {
        val bottomPx = if (isLandscape) 0 else navBottomPx
        keyboardRootView?.setPadding(
            dp(3) + prefs.getInt("kb_ml", 0), 0,
            dp(3) + prefs.getInt("kb_mr", 0), dp(4) + bottomPx
        )
        syncResizeOverlay()
    }

    private fun syncResizeOverlay() {
        val frame = keyboardFrameView ?: return
        val ov = frame.findViewWithTag<View>("resize_overlay") ?: return
        val lp = ov.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = dp(3) + prefs.getInt("kb_ml", 0)
        lp.rightMargin = dp(3) + prefs.getInt("kb_mr", 0)
        lp.bottomMargin = dp(4) + navBottomPx
        ov.layoutParams = lp
    }

    internal fun showResizeOverlay() {
        showPage(t9Page)
        val frame = keyboardFrameView ?: return
        val content = contentLayoutView ?: return
        ResizeOverlay(this, prefs, frame, content, keyColor, textColor, accentColor, navBottomPx) {
            applyRootPadding()
        }.show()
    }

    private companion object {
        const val TAG = "T9Service"
    }
}

internal fun currentTokenContainsAddressMarker(text: String): Boolean {
    val token = text.takeLastWhile { !it.isWhitespace() }
    return token.contains("@") ||
            token.contains("www.") ||
            token.contains(".com") ||
            token.contains(".net") ||
            token.contains(".org")
}
