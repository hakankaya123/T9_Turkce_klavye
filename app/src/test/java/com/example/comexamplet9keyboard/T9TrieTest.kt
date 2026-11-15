package com.example.comexamplet9keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9TrieTest {

    private fun trie(vararg entries: Pair<String, Int>) = T9Trie().apply {
        entries.forEach { (code, idx) -> insert(T9Code(code), WordIndex(idx)) }
    }

    @Test
    fun exactCodeReturnsInsertedWord() {
        val t = trie("4663" to 0)
        assertArrayEquals(intArrayOf(0), t.search("4663"))
    }

    @Test
    fun prefixReturnsLongerWords() {
        val t = trie("46" to 0, "466" to 1, "4663" to 2)
        // Önce tam eşleşme, sonra daha uzun kodlar gelir
        assertArrayEquals(intArrayOf(0, 1, 2), t.search("46"))
    }

    @Test
    fun sameCodeKeepsInsertionOrder() {
        // Aynı tuş dizisi: ekleme sırası (= sözlükteki frekans sırası) korunmalı
        val t = trie("4663" to 5, "4663" to 2, "4663" to 9)
        assertArrayEquals(intArrayOf(5, 2, 9), t.search("4663"))
    }

    @Test
    fun explicitFrequenciesRankExactMatchesAndPrefixes() {
        val t = T9Trie()
        t.insert(T9Code("222"), WordIndex(0), frequency = 10)
        t.insert(T9Code("2"), WordIndex(1), frequency = 20)
        t.insert(T9Code("299"), WordIndex(2), frequency = 90)
        t.insert(T9Code("2"), WordIndex(3), frequency = 70)

        assertArrayEquals(intArrayOf(3, 1, 2, 0), t.search("2"))
        assertArrayEquals(intArrayOf(2), t.search("29"))
    }

    @Test
    fun equalFrequenciesKeepInsertionOrder() {
        val t = T9Trie()
        t.insert(T9Code("22"), WordIndex(8), frequency = 5)
        t.insert(T9Code("22"), WordIndex(3), frequency = 5)

        assertArrayEquals(intArrayOf(8, 3), t.search("22"))
    }

    @Test
    fun duplicateIndexIsNotAddedTwice() {
        val t = trie("22" to 1, "22" to 1)
        assertArrayEquals(intArrayOf(1), t.search("22"))
    }

    @Test
    fun unknownPrefixReturnsEmpty() {
        val t = trie("22" to 1)
        assertEquals(0, t.search("99").size)
    }

    @Test
    fun nonDigitQueryReturnsEmpty() {
        val t = trie("22" to 1)
        assertEquals(0, t.search("2a").size)
    }

    @Test
    fun maxResultsIsRespected() {
        val t = T9Trie()
        for (i in 0 until 50) t.insert(T9Code("2"), WordIndex(i))
        assertEquals(10, t.search("2", maxResults = 10).size)
    }

    @Test
    fun emptyPrefixSearchesWholeTrie() {
        val t = trie("2" to 0, "3" to 1)
        val r = t.search("")
        assertTrue(r.contains(0) && r.contains(1))
    }

    @Test
    fun shortPrefixReturnsMostFrequentWordsFirst() {
        // Sözlük sırası = frekans sırası: küçük indeks daha sık. Ağaç gezme sırası sonucu belirlememeli.
        val t = T9Trie()
        t.insert(T9Code("9999"), WordIndex(0))   // en sık ama son dalda (9)
        for (i in 1..60) t.insert(T9Code("2" + "2".repeat(i % 5 + 1)), WordIndex(i))
        val r = t.search("")
        assertTrue(r.isNotEmpty())
        val r2 = t.search("9")
        assertArrayEquals(intArrayOf(0), r2)
        val r3 = t.search("2", maxResults = 5)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5), r3)
    }
}
