package com.strike.vehicle

import android.content.Context
import android.content.pm.PackageManager
import com.strike.core.Logs
import com.strike.daemon.Shell

private const val TAG = "VehicleApi"
private const val PREFS = "vehicle_api_access"
private const val KEY_GRANTED = "dilink5_grants_v1"
private const val KEY_CONSENT = "hidden_api_consent"
private const val KEY_PROMPTED = "hidden_api_prompted"

private val EXEMPTION_TOKENS = listOf("Lcom/ts/", "Ldalvik/system/")

/**
 * DiLink 5 needs runtime `pm grant` of BYDAUTO permissions and (usually) a
 * hidden-API exemption so `com.ts.*` / bydauto SDK binds succeed. Pattern from
 * [Open-DiKey AdbPermissionManager](https://github.com/sp-hy/Open-DiKey/blob/main/app/src/main/java/com/sphy/airconcontroller/adb/AdbPermissionManager.kt),
 * using Strike's existing local ADB [Shell].
 *
 * Grants go to [com.strike] only (app UID). Camera / dashboard run as shell
 * uid 2000 and cannot hold BYDAUTO permissions — vehicle reads stay in the app
 * process ([Triggers]), matching Open-DiKey. Hidden-API exemption waits for an
 * explicit first-run consent dialog.
 */
object VehicleApiAccess {

    fun needsConsentPrompt(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_PROMPTED, false)

    fun hasHiddenApiConsent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONSENT, false)

    fun acceptConsent(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_PROMPTED, true)
            .putBoolean(KEY_CONSENT, true)
            .apply()
    }

    fun declineConsent(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_PROMPTED, true)
            .putBoolean(KEY_CONSENT, false)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val CORE_PERMISSIONS = listOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.READ_LOGS",
    )

    private val BYDAUTO_PERMISSIONS = listOf(
        // Homepage / Triggers: SOC, range, fuel, kWh
        "android.permission.BYDAUTO_STATISTIC_COMMON",
        "android.permission.BYDAUTO_STATISTIC_GET",
        // ACC / ignition
        "android.permission.BYDAUTO_BODYWORK_COMMON",
        "android.permission.BYDAUTO_BODYWORK_GET",
        // Gear
        "android.permission.BYDAUTO_GEARBOX_COMMON",
        "android.permission.BYDAUTO_GEARBOX_GET",
        // Door lock (surveillance arm)
        "android.permission.BYDAUTO_OTA_COMMON",
        "android.permission.BYDAUTO_OTA_GET",
        // Optional battery fallback
        "android.permission.BYDAUTO_POWER_COMMON",
        "android.permission.BYDAUTO_POWER_GET",
    )

    // SYSTEM_ALERT_WINDOW also lets the app start activities from the background (Android 10+).
    private val BACKGROUND_GRANTS = listOf(
        "pm grant \$pkg android.permission.SYSTEM_ALERT_WINDOW",
        "appops set \$pkg SYSTEM_ALERT_WINDOW allow",
        "dumpsys deviceidle whitelist +\$pkg",
        "appops set \$pkg RUN_IN_BACKGROUND allow",
        "appops set \$pkg RUN_ANY_IN_BACKGROUND allow",
    )

    @Volatile private var ensuring = false

    fun ensure(context: Context, shell: Shell): Boolean {
        if (!shell.isAuthorised()) return false
        synchronized(this) {
            if (ensuring) return false
            ensuring = true
        }
        return try {
            apply(context, shell)
        } finally {
            ensuring = false
        }
    }

    private fun apply(context: Context, shell: Shell): Boolean {
        val pkg = context.packageName
        var ok = true
        for (perm in CORE_PERMISSIONS + BYDAUTO_PERMISSIONS) {
            // DiLink 5 makes BYDAUTO permissions install-time; pm grant rejects those it already gave.
            if (held(context, perm)) continue
            if (!grant(shell, pkg, perm)) ok = false
        }
        applyHiddenApi(context, shell)
        for (template in BACKGROUND_GRANTS) {
            val cmd = template.replace("\$pkg", pkg)
            if (!shell.check(cmd)) Logs.d(TAG, "$cmd failed")
        }
        if (ok || coreGranted(context)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_GRANTED, true).apply()
            Logs.d(TAG, "DiLink 5 vehicle API access ensured for $pkg")
            return true
        }
        Logs.w(TAG, "DiLink 5 vehicle API grants incomplete")
        return false
    }

    private fun grant(shell: Shell, pkg: String, perm: String): Boolean {
        // Keep exit 0 so Shell.read does not drop the output on a failed pm grant.
        val result = shell.read(
            "out=\$(pm grant $pkg $perm 2>&1); code=\$?; " +
                "printf '%s\\n' \"\$out\"; echo EXIT:\$code"
        ) ?: return false
        val exit = result.substringAfterLast("EXIT:", "1").trim().toIntOrNull() ?: 1
        val body = result.substringBeforeLast("EXIT:").trim()
        val ok = exit == 0 ||
            body.contains("Success", ignoreCase = true) ||
            body.contains("already granted", ignoreCase = true) ||
            body.isEmpty()
        // Unknown / undeclared on this firmware is not fatal — do not spam the log.
        if (!ok && (body.contains("Unknown permission", ignoreCase = true) ||
                body.contains("has not requested permission", ignoreCase = true) ||
                body.contains("not a changeable permission type", ignoreCase = true))) {
            return true
        }
        if (!ok && body.isNotBlank()) Logs.d(TAG, "grant $pkg $perm: $body")
        return ok
    }

    private fun applyHiddenApi(context: Context, shell: Shell) {
        if (!hasHiddenApiConsent(context)) {
            Logs.d(TAG, "skipping hidden-api exemption until user consents")
            return
        }
        val current = shell.read("settings get global hidden_api_blacklist_exemptions")?.trim()
        if (current != null && EXEMPTION_TOKENS.all { current.contains(it) }) {
            Logs.d(TAG, "hidden-api exemption already set")
            return
        }
        shell.check("settings put global hidden_api_policy 1")
        shell.check(
            "settings put global hidden_api_blacklist_exemptions 'Lcom/ts/,Ldalvik/system/'"
        )
        Logs.d(TAG, "applied DiLink 5 hidden-api exemption")
    }

    private fun held(context: Context, perm: String): Boolean =
        context.packageManager.checkPermission(perm, context.packageName) ==
            PackageManager.PERMISSION_GRANTED

    private fun coreGranted(context: Context): Boolean =
        CORE_PERMISSIONS.all { held(context, it) }
}
