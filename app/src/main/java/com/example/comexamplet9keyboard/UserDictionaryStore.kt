package com.example.comexamplet9keyboard

import android.content.Context
import java.util.Locale

data class UserWord(val word: String, val count: Int)

object UserDictionaryStore {
    private val trLocale = Locale.forLanguageTag("tr-TR")

    fun load(context: Context): MutableList<UserWord> {
        return UserDataStore.parseCounts(UserDataStore.readAddedWords(context)).map { (word, count) ->
            UserWord(word, count)
        }.toMutableList()
    }

    fun save(context: Context, list: List<UserWord>) {
        val counts = LinkedHashMap<String, Int>()
        list.forEach { (word, count) ->
            if (word.isNotBlank()) {
                counts[word.trim()] = count
            }
        }
        UserDataStore.writeAddedWords(context, UserDataStore.serializeCounts(counts))
    }

    // Sistem sözlüğünden (best-effort) siler. WRITE_USER_DICTIONARY izni olmayan cihazlarda
    // SecurityException fırlatılır; bu durum sessizce yok sayılır ve klavyenin kendi sözlüğünü etkilemez.
    private fun removeFromSystemDictionary(context: Context, word: String) {
        try {
            context.contentResolver.delete(
                android.provider.UserDictionary.Words.CONTENT_URI,
                "${android.provider.UserDictionary.Words.WORD}=?",
                arrayOf(word)
            )
        } catch (_: Exception) {
        }
    }

    fun add(context: Context, word: String, count: Int = 1): Boolean {
        val clean = word.trim().lowercase(trLocale)
        if (clean.length < 2) return false

        val current = load(context)
        val index = current.indexOfFirst { (w) -> w.lowercase(trLocale) == clean }
        if (index >= 0) {
            current[index] = UserWord(clean, current[index].count + count)
        } else {
            current.add(UserWord(clean, count))
        }
        save(context, current)
        try {
            android.provider.UserDictionary.Words.addWord(context, clean, 250, null, null)
        } catch (_: Exception) { }
        return true
    }

    fun update(context: Context, oldWord: String, newWord: String): Boolean {
        val oldClean = oldWord.trim().lowercase(trLocale)
        val newClean = newWord.trim().lowercase(trLocale)
        if (newClean.length < 2) return false

        val current = load(context)
        val index = current.indexOfFirst { (w) -> w.lowercase(trLocale) == oldClean }
        if (index >= 0) {
            val existingCount = current[index].count
            current.removeAt(index)
            val newIndex = current.indexOfFirst { (w) -> w.lowercase(trLocale) == newClean }
            if (newIndex >= 0) {
                current[newIndex] = UserWord(newClean, current[newIndex].count + existingCount)
            } else {
                current.add(UserWord(newClean, existingCount))
            }
            save(context, current)
            removeFromSystemDictionary(context, oldClean)
            return true
        }
        return false
    }

    fun remove(context: Context, word: String) {
        val clean = word.trim().lowercase(trLocale)
        val current = load(context).filter { (w) -> w.lowercase(trLocale) != clean }
        save(context, current)
        removeFromSystemDictionary(context, clean)
    }
}
