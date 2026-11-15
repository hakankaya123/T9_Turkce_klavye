package com.example.comexamplet9keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

internal data class UserDataRaw(
    val addedWords: String,
    val learnedWords: String,
    val wordPairs: String
)

internal data class UserDataSnapshot(
    val raw: UserDataRaw,
    val addedWordCounts: LinkedHashMap<String, Int>,
    val learnedWordCounts: LinkedHashMap<String, Int>,
    val wordPairCounts: LinkedHashMap<String, LinkedHashMap<String, Int>>
)

/**
 * Single persistence boundary for the keyboard's learned dictionary data.
 * The existing tab-separated SharedPreferences format is kept for compatibility.
 */
internal object UserDataStore {
    private const val PREFERENCES = "t9"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun readAddedWords(context: Context): String =
        preferences(context).getString("uc", "") ?: ""

    fun writeAddedWords(context: Context, addedWords: String) {
        preferences(context).edit { putString("uc", addedWords) }
    }

    fun readRaw(context: Context): UserDataRaw {
        val prefs = preferences(context)
        return UserDataRaw(
            addedWords = prefs.getString("uc", "") ?: "",
            learnedWords = prefs.getString("lc", "") ?: "",
            wordPairs = prefs.getString("ub", "") ?: ""
        )
    }

    fun read(context: Context): UserDataSnapshot {
        val raw = readRaw(context)
        return UserDataSnapshot(
            raw = raw,
            addedWordCounts = parseCounts(raw.addedWords),
            learnedWordCounts = parseCounts(raw.learnedWords),
            wordPairCounts = parsePairs(raw.wordPairs)
        )
    }

    fun writeRaw(
        context: Context,
        raw: UserDataRaw,
        commit: Boolean = false,
        additionalEdits: SharedPreferences.Editor.() -> Unit = {}
    ) {
        preferences(context).edit(commit = commit) {
            putString("uc", raw.addedWords)
            putString("lc", raw.learnedWords)
            putString("ub", raw.wordPairs)
            additionalEdits(this)
        }
    }

    fun save(
        context: Context,
        addedWords: Map<String, Int>,
        learnedWords: Map<String, Int>,
        wordPairs: Map<String, Map<String, Int>>
    ): UserDataRaw {
        val raw = UserDataRaw(
            addedWords = serializeCounts(addedWords),
            learnedWords = serializeCounts(learnedWords),
            wordPairs = serializePairs(wordPairs)
        )
        writeRaw(context, raw)
        return raw
    }

    internal fun parseCounts(text: String): LinkedHashMap<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        text.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2) {
                val word = parts[0].trim()
                val count = parts[1].trim().toIntOrNull()
                if (word.isNotEmpty() && count != null) {
                    counts[word] = maxOf(counts[word] ?: count, count)
                }
            }
        }
        return counts
    }

    internal fun parsePairs(text: String): LinkedHashMap<String, LinkedHashMap<String, Int>> {
        val pairs = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        text.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 3) {
                val previous = parts[0].trim()
                val word = parts[1].trim()
                val count = parts[2].trim().toIntOrNull()
                if (previous.isNotEmpty() && word.isNotEmpty() && count != null) {
                    val counts = pairs.getOrPut(previous) { LinkedHashMap() }
                    counts[word] = maxOf(counts[word] ?: count, count)
                }
            }
        }
        return pairs
    }

    internal fun serializeCounts(counts: Map<String, Int>): String = buildString {
        counts.forEach { (word, count) ->
            append(word).append('\t').append(count).append('\n')
        }
    }

    internal fun serializePairs(pairs: Map<String, Map<String, Int>>): String = buildString {
        pairs.forEach { (previous, words) ->
            words.forEach { (word, count) ->
                append(previous).append('\t').append(word).append('\t').append(count).append('\n')
            }
        }
    }

    internal fun mergeCounts(current: String, incoming: String): String {
        val merged = parseCounts(current)
        parseCounts(incoming).forEach { (word, count) ->
            merged[word] = maxOf(merged[word] ?: count, count)
        }
        return serializeCounts(merged)
    }

    internal fun mergePairs(current: String, incoming: String): String {
        val merged = parsePairs(current)
        parsePairs(incoming).forEach { (previous, words) ->
            val destination = merged.getOrPut(previous) { LinkedHashMap() }
            words.forEach { (word, count) ->
                destination[word] = maxOf(destination[word] ?: count, count)
            }
        }
        return serializePairs(merged)
    }
}
