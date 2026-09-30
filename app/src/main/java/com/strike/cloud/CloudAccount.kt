package com.strike.cloud

import com.strike.core.Config
import com.strike.core.ScratchPaths
import com.strike.daemon.STRIKE_DIR
import com.strike.daemon.Shell
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64

internal class CloudAccount(val email: String, val password: String, val country: String, val vin: String = "") {

    val region: String get() = CloudRegions.region(country)
    val language: String get() = CloudRegions.language(country)
    val baseUrl: String get() = "https://dilinkappoversea-$region.byd.auto"
    val imeiMd5: String get() = md5Hex(email)
    val loginKey: String get() = loginKey(password)
    val signPassword: String get() = md5Hex(password)

    fun withVin(vin: String) = CloudAccount(email, password, country, vin)

    fun toJson(): String = JSONObject()
        .put("email", email).put("password", password).put("country", country).put("vin", vin).toString()

    companion object {
        fun fromJson(text: String): CloudAccount? = try {
            val json = JSONObject(text)
            CloudAccount(json.getString("email"), json.getString("password"), json.getString("country"),
                json.optString("vin", "")).takeIf { it.email.isNotEmpty() && CloudRegions.supports(it.country) }
        } catch (e: JSONException) {
            null
        }
    }
}

/** The password is sent to BYD at every sign-in, so it is kept in a shell-only file rather than config.json. */
internal object CloudStore {

    const val ACCOUNT = "cloud.account"
    const val VIN = "cloud.vin"

    val path: String get() = "$STRIKE_DIR/byd-cloud.json"

    fun load(file: File = File(path)): CloudAccount? = try {
        if (file.isFile) CloudAccount.fromJson(file.readText()) else null
    } catch (e: IOException) {
        null
    }

    fun save(shell: Shell, account: CloudAccount): Boolean {
        val encoded = Base64.getEncoder().encodeToString(account.toJson().toByteArray())
        val saved = shell.run(ScratchPaths.prepareShellCommand(
            "mkdir -p '$STRIKE_DIR' && umask 077 && echo '$encoded' | base64 -d > '$path.tmp' && " +
                "chmod 600 '$path.tmp' && mv -f '$path.tmp' '$path'"
        )) == 0
        return saved && Config.put(shell, ACCOUNT, maskEmail(account.email)) && Config.put(shell, VIN, maskVin(account.vin))
    }

    fun clear(shell: Shell): Boolean =
        shell.run("rm -f '$path'") == 0 && Config.put(shell, ACCOUNT, "") && Config.put(shell, VIN, "")

    fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        if (at <= 1) return email
        return email[0] + "\u2022\u2022\u2022" + email.substring(at)
    }

    fun maskVin(vin: String): String = if (vin.length > 4) "\u2022\u2022\u2022" + vin.takeLast(4) else vin
}
