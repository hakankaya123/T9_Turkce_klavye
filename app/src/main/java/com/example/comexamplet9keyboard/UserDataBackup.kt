package com.example.comexamplet9keyboard

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Kullanıcı verisinin (uc = eklenen kelimeler, lc = öğrenilen kelimeler, ub = kelime çiftleri)
 * dışa/içe aktarılması. Dosya biçimi tek bir JSON nesnesidir; üç alan SharedPreferences'taki
 * ham metinlerle birebir aynıdır, bu yüzden veri kaybı olmaz.
 */
object UserDataBackup {
    private const val FORMAT = "t9keyboard-user-data"
    private const val VERSION = 1
    private const val MAX_BYTES = 20 * 1024 * 1024

    /** İçe aktarma özeti: dosyadaki kelime / öğrenilen kelime / kelime çifti sayısı. */
    data class Summary(val words: Int, val learned: Int, val pairs: Int)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("t9", Context.MODE_PRIVATE)

    fun exportData(ctx: Context): String {
        val p = prefs(ctx)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("uc", p.getString("uc", "") ?: "")
            .put("lc", p.getString("lc", "") ?: "")
            .put("ub", p.getString("ub", "") ?: "")
            .toString(2)
    }

    /**
     * Dosyadaki veriyi mevcut verinin ÜSTÜNE birleştirir (hiçbir kelime silinmez; aynı kelime
     * iki yerde varsa büyük olan sayı korunur). Dosya geçersizse IllegalArgumentException atar.
     */
    fun importData(ctx: Context, text: String): Summary {
        if (text.length > MAX_BYTES) throw IllegalArgumentException("Dosya çok büyük")
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Dosya okunamadı (geçerli bir yedek değil)")
        }
        if (obj.optString("format") != FORMAT) {
            throw IllegalArgumentException("Bu dosya T9 klavye yedeği değil")
        }
        val p = prefs(ctx)
        val uc = mergeCounts(p.getString("uc", "") ?: "", obj.optString("uc"))
        val lc = mergeCounts(p.getString("lc", "") ?: "", obj.optString("lc"))
        val ub = mergePairs(p.getString("ub", "") ?: "", obj.optString("ub"))
        p.edit(commit = true) {
            putString("uc", uc)
            putString("lc", lc)
            putString("ub", ub)
            // Dosyadaki veri zaten güncel biçimde; ilk açılıştaki eski-sürüm taşıması çalışmasın.
            putBoolean("uc_v2", true)
            // Klavye hizmeti bu sayaç değişince sözlüğünü diskten yeniden yükler.
            putLong("dict_rev", System.currentTimeMillis())
        }
        return Summary(
            words = countLines(parseCounts(obj.optString("uc"))),
            learned = countLines(parseCounts(obj.optString("lc"))),
            pairs = parsePairs(obj.optString("ub")).values.sumOf { it.size }
        )
    }

    private fun countLines(m: Map<String, Int>) = m.size

    // "kelime<TAB>sayı" satırları
    internal fun parseCounts(s: String): LinkedHashMap<String, Int> {
        val m = LinkedHashMap<String, Int>()
        s.lines().forEach { l ->
            val parts = l.split('\t')
            if (parts.size == 2) {
                val w = parts[0].trim()
                val c = parts[1].trim().toIntOrNull()
                if (w.isNotEmpty() && c != null) m[w] = maxOf(m[w] ?: c, c)
            }
        }
        return m
    }

    // "önceki<TAB>kelime<TAB>sayı" satırları
    internal fun parsePairs(s: String): LinkedHashMap<String, LinkedHashMap<String, Int>> {
        val m = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        s.lines().forEach { l ->
            val parts = l.split('\t')
            if (parts.size == 3) {
                val a = parts[0].trim()
                val b = parts[1].trim()
                val c = parts[2].trim().toIntOrNull()
                if (a.isNotEmpty() && b.isNotEmpty() && c != null) {
                    val inner = m.getOrPut(a) { LinkedHashMap() }
                    inner[b] = maxOf(inner[b] ?: c, c)
                }
            }
        }
        return m
    }

    internal fun mergeCounts(current: String, incoming: String): String {
        val m = parseCounts(current)
        parseCounts(incoming).forEach { (w, c) -> m[w] = maxOf(m[w] ?: c, c) }
        val sb = StringBuilder()
        m.forEach { (w, c) -> sb.append(w).append('\t').append(c).append('\n') }
        return sb.toString()
    }

    internal fun mergePairs(current: String, incoming: String): String {
        val m = parsePairs(current)
        parsePairs(incoming).forEach { (a, inner) ->
            val dst = m.getOrPut(a) { LinkedHashMap() }
            inner.forEach { (b, c) -> dst[b] = maxOf(dst[b] ?: c, c) }
        }
        val sb = StringBuilder()
        m.forEach { (a, inner) ->
            inner.forEach { (b, c) ->
                sb.append(a).append('\t').append(b).append('\t').append(c).append('\n')
            }
        }
        return sb.toString()
    }
}
