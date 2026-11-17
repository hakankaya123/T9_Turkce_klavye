package com.example.comexamplet9keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T9ServiceInputRulesTest {

    @Test
    fun addressMarkersOnlySuppressSpacingInCurrentToken() {
        assertTrue(currentTokenContainsAddressMarker("iletişim: info@example.com"))
        assertTrue(currentTokenContainsAddressMarker("adres: www.example.org"))
        assertFalse(currentTokenContainsAddressMarker("www.example.com "))
        assertFalse(currentTokenContainsAddressMarker("E-posta: info@example.com sonra"))
    }
}
