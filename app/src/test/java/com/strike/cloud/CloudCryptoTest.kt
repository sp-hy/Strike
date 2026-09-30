package com.strike.cloud

import org.junit.Assert.*
import org.junit.Test

class CloudCryptoTest {

    // Expected values come from pyBYD's hashing module.
    @Test fun hashesMatchPyByd() {
        assertEquals("DCb59C51C6eaEC5312edF9823379EE91322eCD55", sha1Mixed("a=1&b=2&password=ABC"))
        assertEquals("29c9527cf0942cebf72878cf2c3bbb56", checkcode("""{"b":"2","a":"1"}"""))
        assertEquals("38075EADB88F8484DDC00C4D272D9F4C", loginKey("hunter2"))
    }

    @Test fun signStringsAreSortedWithThePasswordLast() {
        assertEquals("a=1&b=2&password=ABC", signString(linkedMapOf("b" to "2", "a" to "1"), "ABC"))
    }

    @Test fun flatJsonKeepsInsertionOrderAndEscapes() {
        assertEquals("""{"b":"2","a":"say \"hi\""}""", flatJson(linkedMapOf("b" to "2", "a" to "say \"hi\"")))
    }

    @Test fun payloadEncryptionRoundTrips() {
        val key = md5Hex("token")
        val sealed = aesEncryptHex("""{"x":"1"}""", key)
        assertEquals(sealed.uppercase(), sealed)
        assertEquals("""{"x":"1"}""", aesDecrypt(sealed, key))
        assertEquals(32, randomHex16().length)
    }

    @Test fun accountsMapToTheirBydServer() {
        val account = CloudAccount("driver@example.com", "pw", "AU")
        assertEquals("https://dilinkappoversea-au.byd.auto", account.baseUrl)
        assertEquals("en", account.language)
        assertEquals("eu", CloudRegions.region("NO"))
        assertEquals("no", CloudRegions.region("AE"))
        assertFalse(CloudRegions.supports("CN"))
    }

    @Test fun storedAccountsRoundTripAndAreMasked() {
        val account = CloudAccount("driver@example.com", "p\"w", "NZ", "LGXC1234567890123")
        val back = CloudAccount.fromJson(account.toJson())!!
        assertEquals(account.toJson(), back.toJson())
        assertNull(CloudAccount.fromJson("""{"email":"a@b.c","password":"x","country":"CN"}"""))
        assertNull(CloudAccount.fromJson("not json"))
        assertEquals("d\u2022\u2022\u2022@example.com", CloudStore.maskEmail(account.email))
        assertEquals("\u2022\u2022\u20220123", CloudStore.maskVin(account.vin))
    }
}
