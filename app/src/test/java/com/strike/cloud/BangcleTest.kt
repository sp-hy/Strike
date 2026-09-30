package com.strike.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

internal val shippedCodec: Bangcle by lazy { Bangcle(Bangcle.parse(File("src/main/assets/byd/bangcle_tables.bin").readBytes())) }

class BangcleTest {

    // Produced by pyBYD's BangcleCodec with the same tables.
    private val plain = """{"countryCode":"AU","identifier":"driver@example.com","reqTimestamp":"1700000000000"}"""
    private val envelope = "FaRsr4XZ+lRY61Cgabp5n1RsaQ0Ri30nMEyhfPWpVfAFUSXUMpzoKJ5jQFbuipIPy0Pylr5/WZ83seufp63xWWQ3Z9VBoIiUgYbr9i/zInHXxJ5UIYh3nMTGGUKKYzBfd"

    @Test fun encodingMatchesPyByd() {
        assertEquals(envelope, shippedCodec.encode(plain))
    }

    @Test fun decodingMatchesPyByd() {
        assertEquals(plain, shippedCodec.decode(envelope))
        assertEquals(plain, shippedCodec.decode(envelope.replace('+', '-').replace('/', '_').trimEnd('=')))
    }

    @Test fun everyPaddingLengthRoundTrips() {
        for (length in 0..40) {
            val text = "x".repeat(length) + "\u00e9"
            assertEquals(text, shippedCodec.decode(shippedCodec.encode(text)))
        }
    }

    @Test fun damagedTablesAndEnvelopesAreRejected() {
        val tables = File("src/main/assets/byd/bangcle_tables.bin").readBytes()
        tables[0] = 'X'.code.toByte()
        try {
            Bangcle.parse(tables)
            fail("Expected the bad header to be rejected")
        } catch (expected: IOException) {}
        try {
            shippedCodec.decode("G" + envelope.substring(1))
            fail("Expected a non-Bangcle envelope to be rejected")
        } catch (expected: IllegalArgumentException) {}
    }
}
