package com.example.comexamplet9keyboard

import android.content.Context
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * Data class representing a single dictionary entry.
 */
data class WordEntry(
    val word: String,
    val frequency: Int,
)

/**
 * Zero-Copy Memory-Mapped Binary Dictionary Reader for Android.
 *
 * Reads 36-byte records from words_tr.bin directly via FileChannel and MappedByteBuffer:
 * - 32 bytes: UTF-8 word padded with 0x00 null bytes
 * - 4 bytes: Int32 Big-Endian frequency
 */
class BinaryDictionaryReader(
    private val context: Context,
    private val assetFileName: String = "words_tr.bin"
) {

    companion object {
        const val WORD_SIZE = 32
        const val FREQ_SIZE = 4
        const val RECORD_SIZE = WORD_SIZE + FREQ_SIZE
    }

    private var buffer: ByteBuffer? = null
    private var totalRecords = 0

    /**
     * Initializes and memory-maps the binary dictionary file from assets.
     */
    fun open(): Boolean {
        return try {
            context.assets.openFd(assetFileName).use { fileDescriptor ->
                FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                    val startOffset = fileDescriptor.startOffset
                    val declaredLength = fileDescriptor.declaredLength
                    // Eşleme, kanal kapandıktan sonra da geçerli kalır.
                    buffer = inputStream.channel
                        .map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                        .apply { order(ByteOrder.BIG_ENDIAN) }
                    totalRecords = (declaredLength / RECORD_SIZE).toInt()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Reads all records from the memory-mapped buffer into a list of WordEntry items.
     * High-performance sequence stream processing minimizes GC overhead.
     */
    fun readAllEntries(): List<WordEntry> {
        val buf = buffer ?: return emptyList()
        val count = totalRecords
        if (count == 0) return emptyList()

        val list = ArrayList<WordEntry>(count)
        val wordBytes = ByteArray(WORD_SIZE)

        synchronized(buf) {
            buf.position(0)
            repeat(count) {
                buf.get(wordBytes, 0, WORD_SIZE)
                val freq = buf.int
                val wordStr = parseNullTerminatedUtf8(wordBytes)
                if (wordStr.isNotEmpty()) {
                    list.add(WordEntry(wordStr, freq))
                }
            }
        }
        return list
    }

    /**
     * Converts a null-padded byte array into a UTF-8 String without including trailing 0x00 bytes.
     */
    private fun parseNullTerminatedUtf8(bytes: ByteArray): String {
        var length = 0
        while (length < bytes.size && bytes[length] != 0.toByte()) {
            length++
        }
        if (length == 0) return ""
        return String(bytes, 0, length, StandardCharsets.UTF_8)
    }
}
