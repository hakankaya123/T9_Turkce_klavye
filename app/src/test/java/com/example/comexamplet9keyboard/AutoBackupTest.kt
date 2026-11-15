package com.example.comexamplet9keyboard

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoBackupTest {
    private fun at(h: Int, m: Int, dayOffset: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 13, h, m, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, dayOffset)
    }.timeInMillis

    @Test
    fun morningSchedulesTonightAndLastDueWasYesterday() {
        val now = at(10, 0)
        assertEquals(at(23, 45), AutoBackup.nextTrigger(now))
        assertEquals(at(23, 45, -1), AutoBackup.lastDue(now))
    }

    @Test
    fun afterBackupTimeSchedulesTomorrowAndLastDueIsToday() {
        val now = at(23, 50)
        assertEquals(at(23, 45, 1), AutoBackup.nextTrigger(now))
        assertEquals(at(23, 45), AutoBackup.lastDue(now))
    }

    @Test
    fun exactlyAtBackupTimeMovesToNextDay() {
        val now = at(23, 45)
        assertEquals(at(23, 45, 1), AutoBackup.nextTrigger(now))
        assertEquals(at(23, 45), AutoBackup.lastDue(now))
    }

    @Test
    fun customTimeIsRespected() {
        val now = at(10, 0)
        assertEquals(at(14, 30), AutoBackup.nextTrigger(now, 14, 30))
        assertEquals(at(14, 30, -1), AutoBackup.lastDue(now, 14, 30))
        val later = at(15, 0)
        assertEquals(at(14, 30, 1), AutoBackup.nextTrigger(later, 14, 30))
        assertEquals(at(14, 30), AutoBackup.lastDue(later, 14, 30))
    }
}
