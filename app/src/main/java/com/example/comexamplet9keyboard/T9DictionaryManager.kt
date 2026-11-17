package com.example.comexamplet9keyboard

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class T9DictionaryManager(
    context: Context,
    private val handler: Handler,
    private val onDictionaryChanged: () -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences("t9", Context.MODE_PRIVATE) }
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val saveRunnable = Runnable { saveUserData() }
    private var learnedSinceSave = 0
    private var lastUc = ""
    private var lastRev = 0L
    private var rebuilding = false

    var data = DictData(appContext, handler)
        private set

    fun loadInitial() {
        managerScope.launch(Dispatchers.IO) {
            val loadedData = DictData(appContext, handler)
            loadedData.loadBase()
            val migrate = !prefs.getBoolean("uc_v2", false)
            loadedData.loadUser(migrate)
            withContext(Dispatchers.Main) {
                data = loadedData
                lastUc = loadedData.uc
                lastRev = prefRev()
                if (migrate) {
                    prefs.edit { putBoolean("uc_v2", true) }
                    saveUserData()
                }
                onDictionaryChanged()
            }
        }
    }

    fun onUnbind() {
        saveUserData()
    }

    fun onFinishInput() {
        saveUserData()
    }

    fun onDestroy() {
        saveUserData()
        handler.removeCallbacks(saveRunnable)
        managerScope.cancel()
    }

    fun addWord(word: String) {
        data.addWord(word)
    }

    fun learn(
        word: String,
        previousWord: String?,
        passwordLikeField: Boolean,
        autoAddWords: Boolean,
        addUnknownWord: (String) -> Unit,
        markUnknownWord: (String) -> Unit
    ): String? {
        if (passwordLikeField) return null
        val normalized = word.lowercase(T9Keymap.tr)
        if (normalized.length < 2 || !normalized.all { T9Keymap.charToDigit.containsKey(it) }) {
            return null
        }

        val knownWord = synchronized(data) { data.wordSet.contains(normalized) }
        if (!knownWord) {
            if (autoAddWords) addUnknownWord(normalized) else markUnknownWord(normalized)
        } else {
            synchronized(data) {
                data.userCount[normalized] = (data.userCount[normalized] ?: 0) + 1
            }
        }
        synchronized(data) {
            previousWord?.let { previous ->
                val counts = data.userBigram.getOrPut(previous) { HashMap() }
                counts[normalized] = (counts[normalized] ?: 0) + 1
            }
        }
        learnedSinceSave++
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
        return normalized
    }

    fun saveUserData() {
        if (UserDataStore.readAddedWords(appContext) != lastUc || prefRev() != lastRev) {
            rebuildDictionary()
            return
        }
        if (rebuilding) return
        learnedSinceSave = 0
        handler.removeCallbacks(saveRunnable)
        pruneUserBigram()
        val addedWords = linkedMapOf<String, Int>()
        val learnedWords = linkedMapOf<String, Int>()
        data.userCount.forEach { (word, count) ->
            if (data.userWords.contains(word)) addedWords[word] = count
            else learnedWords[word] = count
        }
        lastUc = UserDataStore.save(appContext, addedWords, learnedWords, data.userBigram).addedWords
    }

    fun syncUserDictionary() {
        val currentAddedWords = UserDataStore.readAddedWords(appContext)
        if (currentAddedWords != lastUc || prefRev() != lastRev) rebuildDictionary()
    }

    fun addedWordsChangedOrRebuilding(): Boolean =
        UserDataStore.readAddedWords(appContext) != lastUc || rebuilding

    fun rebuildDictionary() {
        if (rebuilding) return
        rebuilding = true
        val revisionAtStart = prefRev()
        managerScope.launch(Dispatchers.IO) {
            val loadedData = DictData(appContext, handler)
            try {
                loadedData.loadBase()
                loadedData.loadUser(false)
            } catch (e: Exception) {
                Log.w(TAG, "Sözlük yeniden oluşturulamadı", e)
            }
            withContext(Dispatchers.Main) {
                rebuilding = false
                val currentAddedWords = UserDataStore.readAddedWords(appContext)
                if (currentAddedWords != loadedData.uc || prefRev() != revisionAtStart) {
                    rebuildDictionary()
                } else {
                    data = loadedData
                    lastUc = loadedData.uc
                    lastRev = revisionAtStart
                    onDictionaryChanged()
                }
            }
        }
    }

    private fun pruneUserBigram() {
        var total = 0
        data.userBigram.values.forEach { total += it.size }
        var threshold = 1
        while (total > MAX_USER_PAIRS && threshold < 50) {
            val outer = data.userBigram.entries.iterator()
            while (outer.hasNext()) {
                val entry = outer.next()
                val inner = entry.value.entries.iterator()
                while (inner.hasNext()) {
                    if (inner.next().value <= threshold) {
                        inner.remove()
                        total--
                    }
                }
                if (entry.value.isEmpty()) outer.remove()
            }
            threshold++
        }
    }

    private fun prefRev() = prefs.getLong("dict_rev", 0L)

    private companion object {
        const val TAG = "T9Service"
        const val MAX_USER_PAIRS = 8000
        const val SAVE_DELAY_MS = 5000L
    }
}
