package com.strike

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import com.strike.core.Logs
import com.strike.vehicle.VehicleApiAccess
import com.strike.web.WebUi

private val NEEDED = arrayOf(
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
    Manifest.permission.RECORD_AUDIO
)

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (!askForPermissions()) maybePromptVehicleApi()
        WebUi.mount(findViewById<WebView>(R.id.webRoot))
    }

    override fun onResume() {
        super.onResume()
        WebUi.resume()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        (application as StrikeApp).dashboard.resume { pinSet ->
            if (pinSet) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        (application as StrikeApp).setup.foreground(hasFocus)
    }

    override fun onPause() {
        (application as StrikeApp).setup.foreground(false)
        WebUi.pause()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            (application as StrikeApp).setup.permissionsFinished()
            maybePromptVehicleApi()
        }
    }

    /** @return true if a system permission sheet was shown. */
    private fun askForPermissions(): Boolean {
        val missing = NEEDED.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return false
        (application as StrikeApp).setup.permissionsRequested()
        requestPermissions(missing.toTypedArray(), 1)
        return true
    }

    /** Same first-run consent UX as Open-DiKey before applying hidden-API exemption. */
    private fun maybePromptVehicleApi() {
        if (!VehicleApiAccess.needsConsentPrompt(this)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.vehicle_api_title)
            .setMessage(R.string.vehicle_api_message)
            .setCancelable(false)
            .setPositiveButton(R.string.vehicle_api_allow) { _, _ ->
                VehicleApiAccess.acceptConsent(this)
                applyVehicleApiAndRestart()
            }
            .setNegativeButton(R.string.vehicle_api_not_now) { _, _ ->
                VehicleApiAccess.declineConsent(this)
            }
            .show()
    }

    private fun applyVehicleApiAndRestart() {
        val app = application as StrikeApp
        Thread({
            val shell = app.shell
            if (shell.isAuthorised()) {
                VehicleApiAccess.ensure(this, shell)
                Logs.d("VehicleApi", "Restarting Strike after vehicle API consent")
                val started = shell.check(
                    "nohup sh -c 'am force-stop com.strike; " +
                        "am start -n com.strike/.MainActivity' </dev/null >/dev/null 2>&1 &"
                )
                if (!started) Logs.w("VehicleApi", "Close and reopen Strike after Allow")
            } else {
                // USB debugging approval will run ensure (with consent) then setup restart.
                shell.retry()
            }
        }, "vehicle-api-consent").start()
    }
}
