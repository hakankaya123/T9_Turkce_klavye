package com.example.comexamplet9keyboard

import android.content.Context
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

    fun exportData(ctx: Context): String {
        val data = UserDataStore.readRaw(ctx)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("uc", data.addedWords)
            .put("lc", data.learnedWords)
            .put("ub", data.wordPairs)
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
        val current = UserDataStore.readRaw(ctx)
        val merged = UserDataRaw(
            addedWords = mergeCounts(current.addedWords, obj.optString("uc")),
            learnedWords = mergeCounts(current.learnedWords, obj.optString("lc")),
            wordPairs = mergePairs(current.wordPairs, obj.optString("ub"))
        )
        UserDataStore.writeRaw(ctx, merged, commit = true) {
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
    internal fun parseCounts(s: String): LinkedHashMap<String, Int> = UserDataStore.parseCounts(s)

    // "önceki<TAB>kelime<TAB>sayı" satırları
    internal fun parsePairs(s: String): LinkedHashMap<String, LinkedHashMap<String, Int>> {
        return UserDataStore.parsePairs(s)
    }

    internal fun mergeCounts(current: String, incoming: String): String =
        UserDataStore.mergeCounts(current, incoming)

    internal fun mergePairs(current: String, incoming: String): String =
        UserDataStore.mergePairs(current, incoming)
}
