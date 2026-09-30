package com.strike.server.api

import android.content.Context
import com.strike.cloud.Bangcle
import com.strike.cloud.CloudAccount
import com.strike.cloud.CloudClient
import com.strike.cloud.CloudRefused
import com.strike.cloud.CloudRegions
import com.strike.cloud.CloudStore
import com.strike.core.Config
import com.strike.daemon.DaemonClient
import com.strike.daemon.ParkedPower
import com.strike.daemon.Shell
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.server.formValue
import com.strike.vehicle.VehicleCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

class PowerApi(private val context: Context, private val shell: Shell) {

    private val daemon = DaemonClient()

    fun settings(): Response {
        val values = JSONObject()
        for (key in ParkedPower.switches) values.put(key, ParkedPower.on(key))
        values.put(ParkedPower.CUTOFF_VOLTS, ParkedPower.cutoffSetting())
        val vehicle = VehicleCache.read()
        val payload = JSONObject()
        payload.put("values", values)
        payload.put("volts", vehicle?.batteryVolts ?: JSONObject.NULL)
        payload.put("lowBattery", vehicle?.lowBattery == true)
        payload.put("cloud", cloud())
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun save(body: String): Response {
        val key = formValue(body, "key") ?: return Response(400, TEXT, "No setting named".toByteArray())
        val value = formValue(body, "value") ?: return Response(400, TEXT, "No value given".toByteArray())
        if (!ParkedPower.accepts(key, value)) {
            return Response(400, TEXT, "That setting does not take that value".toByteArray())
        }
        val stored = if (key in ParkedPower.switches) Config.put(shell, key, value == "true")
        else Config.put(shell, key, value)
        if (!stored) {
            return Response(503, TEXT, "Strike has no shell access, so it cannot save that".toByteArray())
        }
        return Response(200, JSON, "{}".toByteArray())
    }

    /** Checks the account against BYD before anything is stored. */
    fun signIn(body: String): Response {
        val email = formValue(body, "email")?.trim().orEmpty()
        val password = formValue(body, "password").orEmpty()
        val country = formValue(body, "country")?.trim()?.uppercase(Locale.US).orEmpty()
        if (email.isEmpty() || password.isEmpty()) {
            return Response(400, TEXT, "Enter the email and password you use in the BYD app".toByteArray())
        }
        if (!CloudRegions.supports(country)) {
            return Response(400, TEXT, "Choose the country your BYD account was made in".toByteArray())
        }
        val codec = try {
            context.assets.open("byd/bangcle_tables.bin").use { Bangcle.load(it) }
        } catch (e: IOException) {
            return Response(500, TEXT, "This build is missing the BYD cipher tables".toByteArray())
        }
        val account = CloudAccount(email, password, country)
        val vin = try {
            CloudClient(account, codec).run {
                login()
                firstVin()
            }
        } catch (e: CloudRefused) {
            val reason = if (e.busy) "BYD is busy; try again in a minute" else "BYD refused that: ${e.message}"
            return Response(400, TEXT, reason.toByteArray())
        } catch (e: IOException) {
            return Response(502, TEXT, "Could not reach BYD: ${e.message}".toByteArray())
        }
        if (!CloudStore.save(shell, account.withVin(vin))) {
            return Response(503, TEXT, "Strike has no shell access, so it cannot save the account".toByteArray())
        }
        return Response(200, JSON, JSONObject().put("vin", CloudStore.maskVin(vin)).toString().toByteArray())
    }

    fun signOut(): Response {
        if (!CloudStore.clear(shell)) {
            return Response(503, TEXT, "Strike has no shell access, so it cannot remove the account".toByteArray())
        }
        return Response(200, JSON, "{}".toByteArray())
    }

    private fun cloud(): JSONObject {
        val countries = JSONArray()
        for (code in CloudRegions.countries) {
            countries.put(JSONObject().put("code", code).put("name", CloudRegions.name(code)))
        }
        val local = Locale.getDefault().country
        return JSONObject()
            .put("account", Config.getString(CloudStore.ACCOUNT, ""))
            .put("vin", Config.getString(CloudStore.VIN, ""))
            .put("country", if (CloudRegions.supports(local)) local else "AU")
            .put("countries", countries)
            .put("heartbeat", daemon.status()?.optJSONObject("cloud") ?: JSONObject.NULL)
    }
}
