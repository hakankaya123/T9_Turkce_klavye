package com.example.comexamplet9keyboard

import java.util.Locale

/**
 * Türkçe T9 tuş eşlemesi. Saf (Android'e bağımlı olmayan) olduğundan JVM testleriyle denenebilir.
 */
object T9Keymap {
    val tr: Locale = Locale.forLanguageTag("tr-TR")

    val groups: Map<Char, String> = mapOf(
        '2' to "abcçâ", '3' to "def", '4' to "ghiığî", '5' to "jkl",
        '6' to "mnoö", '7' to "pqrsş", '8' to "tuvüû", '9' to "wxyz"
    )

    val charToDigit: Map<Char, Char> =
        groups.flatMap { (d, s) -> s.map { it to d } }.toMap()

    /**
     * Öneriyi, kullanıcının o ana kadar yazdığı harflerin büyük/küçük durumuna uydurur (Türkçe kurallarıyla).
     * - [caps] açıksa her şey büyük,
     * - yazılanların hepsi büyük harfse (en az 2 harf) öneri tamamen büyük,
     * - aksi halde yazılan her büyük harf, önerideki aynı sıradaki harfi büyük yapar ("Al" + "ali" → "Ali").
     */
    fun matchCase(typed: String, suggestion: String, caps: Boolean = false): String {
        if (suggestion.isEmpty()) return suggestion
        if (caps) return suggestion.uppercase(tr)
        val letters = typed.filter { it.isLetter() }
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return suggestion.uppercase(tr)
        val sb = StringBuilder(suggestion)
        for (i in 0 until minOf(typed.length, suggestion.length)) {
            if (typed[i].isUpperCase()) sb.setCharAt(i, suggestion[i].toString().uppercase(tr)[0])
        }
        return sb.toString()
    }

    /** Kelimenin tuş kodu; haritada olmayan karakterler '?' olur. Her çağrıda yeni StringBuilder kullanır (iş parçacığı güvenli). */
    fun codeOf(w: String): String {
        val sb = StringBuilder(w.length)
        for (c in w) sb.append(charToDigit[c] ?: '?')
        return sb.toString()
    }
}
