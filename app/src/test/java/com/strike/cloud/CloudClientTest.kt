package com.strike.cloud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private const val SIGN_TOKEN = "SIGNTOKEN"
private const val ENCRY_TOKEN = "ENCRYTOKEN"

/** Checks requests the way BYD's server must: envelope, checkcode, signature and payload key. */
private class FakeByd(private val account: CloudAccount, var loginCode: String = "0") : CloudTransport {

    val calls = ArrayList<String>()
    var logins = 0

    override fun post(url: String, body: String, timeoutMs: Int): String {
        assertTrue(url.startsWith(account.baseUrl))
        val endpoint = url.removePrefix(account.baseUrl)
        calls.add(endpoint)
        val raw = shippedCodec.decode(JSONObject(body).getString("request"))
        val outer = JSONObject(raw)
        val checked = raw.substringBeforeLast(",\"checkcode\":") + "}"
        assertEquals(checkcode(checked), outer.getString("checkcode"))
        val reply = if (endpoint == "/app/account/login") login(outer) else signed(endpoint, outer)
        return JSONObject().put("response", shippedCodec.encode(reply.toString())).toString()
    }

    private fun login(outer: JSONObject): JSONObject {
        logins++
        assertEquals(account.password, outer.getString("signKey"))
        val inner = JSONObject(aesDecrypt(outer.getString("encryData"), account.loginKey))
        assertEquals(account.imeiMd5, inner.getString("imeiMD5"))
        val fields = fields(inner)
        for (key in listOf("countryCode", "functionType", "identifier", "identifierType", "language", "reqTimestamp")) {
            fields[key] = outer.getString(key)
        }
        assertEquals(sha1Mixed(signString(fields, account.signPassword)), outer.getString("sign"))
        if (loginCode != "0") return JSONObject().put("code", loginCode).put("message", "Wrong password")
        val token = JSONObject().put("token", JSONObject()
            .put("userId", "42").put("signToken", SIGN_TOKEN).put("encryToken", ENCRY_TOKEN))
        return JSONObject().put("code", "0").put("respondData", aesEncryptHex(token.toString(), account.loginKey))
    }

    private fun signed(endpoint: String, outer: JSONObject): JSONObject {
        val contentKey = md5Hex(ENCRY_TOKEN)
        val inner = JSONObject(aesDecrypt(outer.getString("encryData"), contentKey))
        val fields = fields(inner)
        for (key in listOf("countryCode", "identifier", "imeiMD5", "language", "reqTimestamp")) {
            fields[key] = outer.getString(key)
        }
        assertEquals("42", outer.getString("identifier"))
        assertEquals(sha1Mixed(signString(fields, md5Hex(SIGN_TOKEN))), outer.getString("sign"))
        val data = when (endpoint) {
            "/app/account/getAllListByUserId" -> JSONArray().put(JSONObject().put("vin", "LGXC1234567890123")).toString()
            "/vehicleInfo/vehicle/vehicleRealTimeRequest" -> {
                assertEquals("LGXC1234567890123", inner.getString("vin"))
                assertEquals("3", inner.getString("tboxVersion"))
                JSONObject().put("onlineState", 1).toString()
            }
            else -> throw AssertionError("unexpected $endpoint")
        }
        return JSONObject().put("code", "0").put("respondData", aesEncryptHex(data, contentKey))
    }

    private fun fields(inner: JSONObject): MutableMap<String, String> {
        val fields = HashMap<String, String>()
        for (key in inner.keys()) fields[key] = inner.getString(key)
        return fields
    }
}

class CloudClientTest {

    private val account = CloudAccount("driver@example.com", "hunter2", "AU")

    @Test fun signInFindsTheCarAndWakesIt() {
        val byd = FakeByd(account)
        var now = 1_700_000_000_000L
        val client = CloudClient(account, shippedCodec, byd) { now }
        client.login()
        assertEquals("LGXC1234567890123", client.firstVin())
        client.wake("LGXC1234567890123", 6_000)
        assertEquals(listOf("/app/account/login", "/app/account/getAllListByUserId",
            "/vehicleInfo/vehicle/vehicleRealTimeRequest"), byd.calls)
        now += 31 * 60_000L
        client.wake("LGXC1234567890123", 6_000)
        assertEquals(2, byd.logins)
    }

    @Test fun aRefusedSignInIsReportedAsSuch() {
        val byd = FakeByd(account, loginCode = "1005")
        try {
            CloudClient(account, shippedCodec, byd).wake("LGXC1234567890123", 6_000)
            fail("Expected BYD to refuse the sign-in")
        } catch (refused: CloudRefused) {
            assertTrue(refused.signIn)
            assertFalse(refused.busy)
            assertEquals("1005", refused.code)
        }
        byd.loginCode = "1009"
        try {
            CloudClient(account, shippedCodec, byd).login()
            fail("Expected BYD to be busy")
        } catch (refused: CloudRefused) {
            assertTrue(refused.busy)
        }
    }

    @Test fun heartbeatsKeepAFifteenSecondCadenceAndRetryQuickly() {
        assertEquals(13_000L, heartbeatDelayMs(0, 2_000L))
        assertEquals(0L, heartbeatDelayMs(0, 20_000L))
        assertEquals(1_000L, heartbeatDelayMs(1, 2_000L))
        assertEquals(3_000L, heartbeatDelayMs(2, 2_000L))
        assertEquals(5_000L, heartbeatDelayMs(7, 2_000L))
        assertEquals(500L, heartbeatDelayMs(3, 14_500L))
    }
}
