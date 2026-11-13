package com.example.comexamplet9keyboard

// ==========================================
// OPTİMİZE EDİLMİŞ RAM VE CPU DOSTU T9 TRIE
// ==========================================
@JvmInline
value class T9Code(val value: String)

@JvmInline
value class WordIndex(val id: Int)

class T9Trie {
    companion object {
        // Her düğüm için ayrı boş dizi üretmemek için paylaşılan sabit.
        private val EMPTY = IntArray(0)

        // İlk TOP_DEPTH tuşluk önekler için, alt ağaçtaki en küçük (= en sık kullanılan) TOP_K indeks
        // önceden tutulur. Böylece "2" ya da "46" gibi kısa öneklerde tüm ağacı gezmeye gerek kalmaz
        // ve sonuç, ağaç gezme sırasına değil sözlük (frekans) sırasına göre gelir.
        const val TOP_DEPTH = 4
        const val TOP_K = 32
    }

    class Node {
        // HashMap nesne maliyetini önlemek için sabit 10 elemanlı dizi (0-9 tuşları)
        val children = arrayOfNulls<Node>(10)

        // Boxing maliyetini önlemek için IntArray (bu düğümde BİTEN kelimeler)
        var candidateIndices = EMPTY

        // Alt ağacın en küçük TOP_K indeksi (artan sırada); yalnızca 1..TOP_DEPTH derinliğindeki düğümlerde dolu.
        var top: IntArray? = null
        var topCount = 0

        /** Yeni eklendiyse true döner. */
        fun addIndex(index: Int): Boolean {
            if (candidateIndices.contains(index)) return false
            candidateIndices += index
            return true
        }

        fun offerTop(index: Int) {
            val t = top ?: IntArray(TOP_K).also { top = it }
            if (topCount == TOP_K && index >= t[TOP_K - 1]) return
            var pos = if (topCount < TOP_K) topCount else TOP_K - 1
            while (pos > 0 && t[pos - 1] > index) {
                t[pos] = t[pos - 1]
                pos--
            }
            t[pos] = index
            if (topCount < TOP_K) topCount++
        }
    }

    val root = Node()

    fun insert(code: T9Code, wordIndex: WordIndex) {
        val path = arrayOfNulls<Node>(TOP_DEPTH)
        var depth = 0
        var curr = root
        for (ch in code.value) {
            val d = ch.digitToIntOrNull() ?: continue
            if (d !in 0..9) continue

            var next = curr.children[d]
            if (next == null) {
                next = Node()
                curr.children[d] = next
            }
            curr = next
            if (depth < TOP_DEPTH) path[depth] = curr
            depth++
        }
        if (!curr.addIndex(wordIndex.id)) return
        for (n in path) n?.offerTop(wordIndex.id)
    }

    /**
     * Önce önekle birebir biten kelimeler (ekleme sırasıyla), ardından daha uzun kodlu kelimeler
     * (küçük indeks = daha sık kullanılan önce) döner.
     */
    fun search(codePrefix: String, maxResults: Int = TOP_K): IntArray {
        var curr = root
        var depth = 0
        for (ch in codePrefix) {
            val d = ch.digitToIntOrNull() ?: return IntArray(0)
            if (d !in 0..9) return IntArray(0)

            curr = curr.children[d] ?: return IntArray(0)
            depth++
        }

        val own = curr.candidateIndices
        val result = IntArray(maxResults)
        var count = 0
        for (idx in own) {
            if (count >= maxResults) break
            result[count++] = idx
        }
        if (count >= maxResults) return result.copyOf(count)

        val t = curr.top
        if (depth in 1..TOP_DEPTH && t != null && maxResults <= TOP_K) {
            for (i in 0 until curr.topCount) {
                if (count >= maxResults) break
                val idx = t[i]
                if (!own.contains(idx)) result[count++] = idx
            }
            return result.copyOf(count)
        }

        // Derin öneklerde alt ağaç küçüktür; hepsini toplayıp en küçük indeksleri al.
        val rest = ArrayList<Int>()
        fun collect(node: Node, isStart: Boolean) {
            if (!isStart) for (idx in node.candidateIndices) rest.add(idx)
            for (child in node.children) if (child != null) collect(child, false)
        }
        collect(curr, true)
        rest.sort()
        for (idx in rest) {
            if (count >= maxResults) break
            result[count++] = idx
        }
        return result.copyOf(count)
    }
}
