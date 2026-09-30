package com.strike.cloud

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.GeneralSecurityException

private const val SESSION_MS = 30 * 60_000L
private const val CALL_MS = 10_000
private const val POLL_AFTER_MS = 1_500L
private const val USER_AGENT = "okhttp/4.12.0"
private const val APP_INNER_VERSION = "323"
private const val APP_VERSION = "3.2.3"

/** A BYD answer with a non-zero code; [code] is BYD's own. 1009 means busy, not refused. */
internal class CloudRefused(val code: String, message: String, val signIn: Boolean = false) : IOException(message) {
    val busy: Boolean get() = code == "1009"
}

internal fun interface CloudTransport {
    fun post(url: String, body: String, timeoutMs: Int): String
}

/**
 * The BYD overseas app's cloud API, ported from Overdrive's BydCloudClient (itself pyBYD and
 * Niek/BYD-re). Only sign-in, the vehicle list and the real-time request that wakes the T-Box.
 */
internal class CloudClient(
    private val account: CloudAccount,
    private val codec: Bangcle,
    private val transport: CloudTransport = HttpTransport(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    private class Session(val userId: String, signToken: String, encryToken: String, val atMs: Long) {
        val contentKey = md5Hex(encryToken)
        val signKey = md5Hex(signToken)
    }

    private var session: Session? = null

    @Synchronized
    fun login() {
        val now = nowMs()
        val stamp = now.toString()
        val inner = linkedMapOf(
            "appInnerVersion" to APP_INNER_VERSION,
            "appVersion" to APP_VERSION,
            "deviceName" to "XIAOMIPOCO F1",
            "deviceType" to "0",
            "imeiMD5" to account.imeiMd5,
            "isAuto" to "1",
            "mobileBrand" to "XIAOMI",
            "mobileModel" to "POCO F1",
            "networkType" to "wifi",
            "osType" to "15",
            "osVersion" to "35",
            "random" to randomHex16(),
            "softType" to "0",
            "timeStamp" to stamp,
            "timeZone" to "Asia/Kolkata"
        )
        val signed = LinkedHashMap(inner).apply {
            put("countryCode", account.country)
            put("functionType", "pwdLogin")
            put("identifier", account.email)
            put("identifierType", "0")
            put("language", account.language)
            put("reqTimestamp", stamp)
        }
        val outer = linkedMapOf(
            "countryCode" to account.country,
            "encryData" to aesEncryptHex(flatJson(inner), account.loginKey),
            "functionType" to "pwdLogin",
            "identifier" to account.email,
            "identifierType" to "0",
            "imeiMD5" to account.imeiMd5,
            "isAuto" to "1",
            "language" to account.language,
            "reqTimestamp" to stamp,
            "sign" to sha1Mixed(signString(signed, account.signPassword)),
            "signKey" to account.password
        )
        val reply = try {
            post("/app/account/login", finish(outer), CALL_MS)
        } catch (e: CloudRefused) {
            throw CloudRefused(e.code, e.message.orEmpty(), signIn = true)
        }
        val token = parse(open(reply.optString("respondData"), account.loginKey))?.optJSONObject("token")
            ?: throw IOException("BYD sign-in reply had no token")
        val userId = token.optString("userId")
        val signToken = token.optString("signToken")
        val encryToken = token.optString("encryToken").ifEmpty { token.optString("encryptToken") }
        if (userId.isEmpty() || signToken.isEmpty() || encryToken.isEmpty()) {
            throw IOException("BYD sign-in reply was missing its tokens")
        }
        session = Session(userId, signToken, encryToken, now)
    }

    @Synchronized
    fun forget() {
        session = null
    }

    @Synchronized
    fun firstVin(): String {
        val (reply, key) = signedPost("/app/account/getAllListByUserId", emptyMap(), CALL_MS)
        val plain = open(reply.optString("respondData"), key).trim()
        val list = try {
            JSONArray(plain)
        } catch (e: JSONException) {
            parse(plain)?.optJSONArray("diLinkAutoInfoList")
        }
        for (i in 0 until (list?.length() ?: 0)) {
            val vin = list!!.optJSONObject(i)?.optString("vin").orEmpty()
            if (vin.isNotEmpty()) return vin
        }
        throw IOException("No car is linked to this BYD account")
    }

    /** Asks BYD's servers for live data, which makes them wake the car's T-Box. */
    @Synchronized
    fun wake(vin: String, timeoutMs: Int) {
        val extra = mapOf("energyType" to "0", "tboxVersion" to "3", "vin" to vin)
        val (reply, key) = signedPost("/vehicleInfo/vehicle/vehicleRealTimeRequest", extra, timeoutMs)
        val data = reply.optString("respondData")
        val serial = if (data.isEmpty()) "" else parse(open(data, key))?.optString("requestSerial").orEmpty()
        if (serial.isEmpty()) return
        try {
            Thread.sleep(POLL_AFTER_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        try {
            signedPost("/vehicleInfo/vehicle/vehicleRealTimeResult", extra + ("requestSerial" to serial), timeoutMs)
        } catch (e: IOException) {
            // The request above already reached the car; the result poll is best effort.
        }
    }

    private fun signedPost(endpoint: String, extra: Map<String, String>, timeoutMs: Int): Pair<JSONObject, String> {
        val now = nowMs()
        val current = session?.takeIf { now - it.atMs < SESSION_MS } ?: run { login(); session!! }
        val stamp = now.toString()
        val inner = linkedMapOf(
            "deviceType" to "0",
            "imeiMD5" to account.imeiMd5,
            "networkType" to "wifi",
            "random" to randomHex16(),
            "timeStamp" to stamp,
            "version" to APP_INNER_VERSION
        )
        inner.putAll(extra)
        val signed = LinkedHashMap(inner).apply {
            put("countryCode", account.country)
            put("identifier", current.userId)
            put("imeiMD5", account.imeiMd5)
            put("language", account.language)
            put("reqTimestamp", stamp)
        }
        val outer = linkedMapOf(
            "countryCode" to account.country,
            "encryData" to aesEncryptHex(flatJson(inner), current.contentKey),
            "identifier" to current.userId,
            "imeiMD5" to account.imeiMd5,
            "language" to account.language,
            "reqTimestamp" to stamp,
            "sign" to sha1Mixed(signString(signed, current.signKey))
        )
        return post(endpoint, finish(outer), timeoutMs) to current.contentKey
    }

    private fun finish(outer: LinkedHashMap<String, String>): String {
        outer["ostype"] = "and"
        outer["imei"] = "BANGCLE01234"
        outer["mac"] = "00:00:00:00:00:00"
        outer["model"] = "POCO F1"
        outer["sdk"] = "35"
        outer["mod"] = "Xiaomi"
        outer["serviceTime"] = nowMs().toString()
        outer["checkcode"] = checkcode(flatJson(outer))
        return flatJson(outer)
    }

    private fun post(endpoint: String, outer: String, timeoutMs: Int): JSONObject {
        val body = JSONObject().put("request", codec.encode(outer)).toString()
        val envelope = parse(transport.post(account.baseUrl + endpoint, body, timeoutMs))?.optString("response")
        if (envelope.isNullOrEmpty()) throw IOException("BYD sent no response to $endpoint")
        val decoded = try {
            codec.decode(envelope)
        } catch (e: IllegalArgumentException) {
            throw IOException("BYD's response to $endpoint could not be decoded")
        }
        val reply = parse(if (decoded.startsWith("F{")) decoded.substring(1) else decoded)
            ?: throw IOException("BYD's response to $endpoint was not JSON")
        val code = reply.optString("code")
        if (code != "0") throw CloudRefused(code, reply.optString("message").ifEmpty { "BYD refused $endpoint" })
        return reply
    }

    private fun open(hex: String, key: String): String = try {
        aesDecrypt(hex, key)
    } catch (e: GeneralSecurityException) {
        throw IOException("BYD's reply could not be decrypted")
    } catch (e: IllegalArgumentException) {
        throw IOException("BYD's reply could not be decrypted")
    }

    private fun parse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (e: JSONException) {
        null
    }
}

private class HttpTransport : CloudTransport {

    private val cookies = CookieManager()

    override fun post(url: String, body: String, timeoutMs: Int): String {
        val uri = URI(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("accept-encoding", "identity")
            connection.setRequestProperty("content-type", "application/json; charset=UTF-8")
            connection.setRequestProperty("user-agent", USER_AGENT)
            for ((name, values) in cookies.get(uri, emptyMap())) {
                for (value in values) connection.addRequestProperty(name, value)
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            cookies.put(uri, connection.headerFields.filterKeys { it != null })
            if (status !in 200..299) throw IOException("BYD answered HTTP $status")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
