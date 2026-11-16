package com.example.comexamplet9keyboard

import kotlin.math.ln

// Tahmin motoru: T9 aday arama, harf tahmini, ek ve sonraki kelime önerileri.
// UI durumuna bağlı işlemler T9Service uzantısı, sözlük aramaları saf yardımcı olarak tutulur.

internal fun candidates(
    dictionary: DictData,
    s: String,
    forced: List<Char?>
): List<String> {
    val matchedIndices = dictionary.t9Trie.search(s)
    val userMatches = dictionary.userCount.keys.asSequence()
        .mapNotNull { w ->
            dictionary.wordIdx[w]?.takeIf { idx ->
                dictionary.codes[idx].startsWith(s) && matchesForced(w, forced)
            }
        }

    return (matchedIndices.asSequence() + userMatches)
        .filter { matchesForced(dictionary.words[it], forced) }
        .distinct()
        .sortedWith(
            compareBy<Int> { if (dictionary.codes[it].length == s.length) 0 else 1 }
                .thenByDescending { dictionary.userCount[dictionary.words[it]] ?: 0 }
                .thenByDescending { dictionary.frequencies[it] ?: Int.MIN_VALUE }
                .thenBy { dictionary.codes[it].length }
                .thenBy { it }
        )
        .take(8)
        .map { dictionary.words[it] }
        .toList()
}

private fun matchesForced(word: String, forced: List<Char?>): Boolean =
    forced.indices.all { index ->
        forced[index] == null || (index < word.length && word[index] == forced[index])
    }

internal fun T9Service.letterScore(prev: Char, c: Char): Double {
    val b = (bigram[(prev.code shl 16) or c.code] ?: 0) + 0.5
    val t = (charTotal[prev] ?: 0) + 15.0
    return ln(b / t)
}

internal fun T9Service.startPrev(): Char =
    attach?.lastOrNull()?.let { it.toString().lowercase(tr)[0] } ?: '^'

internal fun T9Service.chainScore(start: Char, frag: String): Double {
    var prev = start
    var sc = 0.0
    for (c in frag) {
        sc += letterScore(prev, c); prev = c
    }
    return sc
}

internal fun T9Service.guessSpelling(): String {
    var beam: List<Pair<String, Double>> = listOf("" to 0.0)
    for (k in seq.indices) {
        val opts = forced.getOrNull(k)?.toString() ?: groups[seq[k]] ?: "?"
        val next = ArrayList<Pair<String, Double>>()
        for ((word, score) in beam) for (c in opts) {
            val prev = if (word.isEmpty()) startPrev() else word.last()
            next.add((word + c) to (score + letterScore(prev, c)))
        }
        beam = next.sortedByDescending { it.second }.take(10)
    }
    return beam.firstOrNull()?.first ?: ""
}

internal fun T9Service.letterCandidates(): List<String> {
    val u = forced.indexOfLast { it == null }
    letterPos = u
    if (u < 0 || u >= seq.length) return emptyList()
    val guess = guessSpelling()
    val prev = if (u > 0 && u - 1 < guess.length) guess[u - 1] else startPrev()
    val opts = groups[seq[u]] ?: return emptyList()
    return opts.map { it.toString() }.sortedByDescending { letterScore(prev, it[0]) }
}

internal fun T9Service.fragScore(f: String): Int =
    suffixIndex[f]?.let { 1_000_000 - it } ?: (suffixCount[f] ?: 0)

internal fun T9Service.fragmentCandidates(): List<String> {
    val n = seq.length
    if (n == 0 || n > 4) return emptyList()
    var combos = listOf("")
    for (k in 0 until n) {
        val opts = forced.getOrNull(k)?.toString() ?: groups[seq[k]] ?: return emptyList()
        combos = combos.flatMap { c -> opts.map { c + it } }
    }
    val start = startPrev()
    return combos.sortedWith(
        compareByDescending<String> { fragScore(it) }.thenByDescending { chainScore(start, it) }
    ).take(8)
}

internal fun T9Service.suffixCompletions(prefix: String): List<String> {
    val p = prefix.lowercase(tr)
    return KeyboardData.SUFFIXES.asSequence()
        .filter { it.startsWith(p) && it != p }
        .take(6)
        .toList()
}

internal fun prefixCandidates(dictionary: DictData, prefix: String): List<String> {
    val p = prefix.lowercase(T9Keymap.tr)
    val builtInHits = dictionary.words.asSequence()
        .mapIndexedNotNull { idx, word -> if (word.startsWith(p)) idx else null }
        .take(30)
    val userHits = dictionary.userCount.keys.asSequence()
        .mapNotNull { w -> dictionary.wordIdx[w]?.takeIf { w.startsWith(p) } }

    return (builtInHits + userHits)
        .distinct()
        .sortedWith(
            compareByDescending<Int> { dictionary.userCount[dictionary.words[it]] ?: 0 }
                .thenByDescending { dictionary.frequencies[it] ?: Int.MIN_VALUE }
        )
        .take(6)
        .map { dictionary.words[it] }
        .toList()
}

internal fun T9Service.nextWords(prev: String?): List<String> {
    if (prev == null) return emptyList()
    val userNext = userBigram[prev]?.entries?.asSequence()
        ?.sortedByDescending { it.value }
        ?.map { it.key } ?: emptySequence()
    val builtInNext = builtinBigrams[prev]?.asSequence() ?: emptySequence()

    return (userNext + builtInNext)
        .distinct()
        .take(6)
        .toList()
}
