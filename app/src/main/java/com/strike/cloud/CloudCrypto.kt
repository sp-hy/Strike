package com.strike.cloud

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val ZERO_IV = ByteArray(16)
private val random = SecureRandom()

internal fun md5Hex(value: String): String = hex(digest("MD5", value)).uppercase()

internal fun loginKey(password: String): String = md5Hex(md5Hex(password))

/** SHA-1 hex with alternating-case bytes, dropping '0' at even positions. */
internal fun sha1Mixed(value: String): String {
    val mixed = StringBuilder()
    digest("SHA-1", value).forEachIndexed { i, byte ->
        val pair = "%02x".format(byte.toInt() and 0xFF)
        mixed.append(if (i % 2 == 0) pair.uppercase() else pair)
    }
    return mixed.toString().filterIndexed { j, ch -> !(ch == '0' && j % 2 == 0) }
}

/** BYD hashes the exact JSON text it receives, so [json] must be the serialized request. */
internal fun checkcode(json: String): String {
    val md5 = hex(digest("MD5", json))
    return md5.substring(24, 32) + md5.substring(8, 16) + md5.substring(16, 24) + md5.substring(0, 8)
}

internal fun signString(fields: Map<String, String>, password: String): String =
    fields.keys.sorted().joinToString("&") { "$it=${fields[it]}" } + "&password=$password"

/** Compact JSON in insertion order; request hashes depend on the exact text. */
internal fun flatJson(fields: Map<String, String>): String =
    fields.entries.joinToString(",", "{", "}") { JSONObject.quote(it.key) + ":" + JSONObject.quote(it.value) }

internal fun aesEncryptHex(plaintext: String, keyHex: String): String =
    hex(aes(Cipher.ENCRYPT_MODE, keyHex).doFinal(plaintext.toByteArray(Charsets.UTF_8))).uppercase()

internal fun aesDecrypt(cipherHex: String, keyHex: String): String =
    String(aes(Cipher.DECRYPT_MODE, keyHex).doFinal(unhex(cipherHex)), Charsets.UTF_8)

internal fun randomHex16(): String = hex(ByteArray(16).also { random.nextBytes(it) }).uppercase()

private fun aes(mode: Int, keyHex: String): Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
    init(mode, SecretKeySpec(unhex(keyHex), "AES"), IvParameterSpec(ZERO_IV))
}

private fun digest(algorithm: String, value: String): ByteArray =
    MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8))

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private fun unhex(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
    ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
}
