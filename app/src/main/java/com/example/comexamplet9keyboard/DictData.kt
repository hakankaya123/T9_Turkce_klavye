package com.example.comexamplet9keyboard

import android.content.Context
import android.os.Handler
import android.util.Log

/**
 * Sözlük verisi: temel kelime listesi (words_tr.bin), kullanıcı kelimeleri,
 * bigram ve harf istatistikleri ile T9 ağacı. Servisten bağımsızdır; Context, SharedPreferences
 * (tembel sağlayıcı olarak) ve ana iş parçacığı Handler'ı dışarıdan alır.
 */
class DictData(
    private val context: Context,
    private val mainHandler: Handler
) {
    private companion object {
        const val TAG = "DictData"
    }

    val words = ArrayList<String>()
    val codes = ArrayList<String>()
    val frequencies = ArrayList<Int?>()
    val wordSet = HashSet<String>()
    val builtinBigrams = HashMap<String, ArrayList<String>>()
    val userCount = HashMap<String, Int>()
    val suffixCount = HashMap<String, Int>()
    val wordIdx = HashMap<String, Int>()
    // Anahtar: (önceki harf shl 16) or harf. String üretmemek için Int.
    val bigram = HashMap<Int, Int>()
    val charTotal = HashMap<Char, Int>()
    val userBigram = HashMap<String, HashMap<String, Int>>()
    val userWords = HashSet<String>()

    val t9Trie = T9Trie()
    var uc = ""

    @Synchronized
    fun addWord(w: String, frequency: Int? = null) {
        if (w.isEmpty() || !w.all { T9Keymap.charToDigit.containsKey(it) }) return
        if (!wordSet.add(w)) return
        val index = words.size
        wordIdx[w] = index
        words.add(w)
        frequencies.add(frequency)

        val codeStr = T9Keymap.codeOf(w)
        codes.add(codeStr)

        t9Trie.insert(T9Code(codeStr), WordIndex(index), frequency)

        addSuffixes(w)
        addLetterStats(w)
    }

    private fun addSuffixes(w: String) {
        for (len in 1..4) if (w.length > len) {
            val k = w.takeLast(len)
            suffixCount[k] = (suffixCount[k] ?: 0) + 1
        }
    }

    private fun addLetterStats(w: String) {
        var prev = '^'
        for (c in w) {
            val k = (prev.code shl 16) or c.code
            bigram[k] = (bigram[k] ?: 0) + 1
            charTotal[prev] = (charTotal[prev] ?: 0) + 1
            prev = c
        }
    }

    fun loadBase() {
        var loaded = false
        try {
            val binaryReader = BinaryDictionaryReader(context, "words_tr.bin")
            if (binaryReader.open()) {
                val entries = binaryReader.readAllEntries()
                if (entries.isNotEmpty()) {
                    // .bin dosyası frekansa göre azalan sıralıdır (T9KeymapTest bunu doğrular); yeniden sıralamaya gerek yok.
                    entries.forEach { addWord(it.word, it.frequency) }
                    loaded = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sözlük okunamadı", e)
        }

        if (!loaded) {
            Log.e(TAG, "words_tr.bin okunamadı, yedek sözlük kullanılıyor")
            mainHandler.post {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.dictionary_load_failed),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            DefaultDictionary.WORDS.split(" ").forEach { addWord(it) }
        }

        DefaultDictionary.BIGRAMS.split(";").forEach { e ->
            val p = e.split(":")
            if (p.size == 2) builtinBigrams.getOrPut(p[0]) { ArrayList() }
                .addAll(p[1].split(","))
        }
    }

    fun loadUser(migrate: Boolean) {
        try {
            val data = UserDataStore.read(context)
            uc = data.raw.addedWords
            data.addedWordCounts.forEach { (word, count) ->
                val builtin = migrate && wordSet.contains(word)
                userCount[word] = count
                if (!builtin) {
                    userWords.add(word); addWord(word)
                }
            }
            data.learnedWordCounts.forEach { (word, count) ->
                if (!userCount.containsKey(word)) userCount[word] = count
            }
            data.wordPairCounts.forEach { (previous, words) ->
                userBigram[previous] = HashMap(words)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kullanıcı sözlüğü yüklenemedi", e)
        }
    }
}
