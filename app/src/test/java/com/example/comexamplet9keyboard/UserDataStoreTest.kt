package com.example.comexamplet9keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class UserDataStoreTest {
    @Test
    fun parsesAndSerializesCountsWithoutLosingEntries() {
        val parsed = UserDataStore.parseCounts("merhaba\t2\nselam\t4\n")

        assertEquals(linkedMapOf("merhaba" to 2, "selam" to 4), parsed)
        assertEquals("merhaba\t2\nselam\t4\n", UserDataStore.serializeCounts(parsed))
    }

    @Test
    fun duplicateCountsKeepTheLargerValueAndMalformedRowsAreIgnored() {
        val parsed = UserDataStore.parseCounts("kelime\t2\nkelime\t5\nhatalı\nbozuk\tx\n")

        assertEquals(linkedMapOf("kelime" to 5), parsed)
    }

    @Test
    fun mergesWordPairsWithoutReducingExistingCounts() {
        val merged = UserDataStore.mergePairs(
            "önce\tsonra\t7\n",
            "önce\tsonra\t3\nönce\tyeni\t2\n"
        )

        assertEquals("önce\tsonra\t7\nönce\tyeni\t2\n", merged)
    }
}
