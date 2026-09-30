package com.strike

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.strike.camera.FastCamNative
import com.strike.core.Crashes
import com.strike.core.Logs
import com.strike.core.ScratchPaths
import com.strike.daemon.Shell
import com.strike.recording.Triggers
import com.strike.server.DashboardClient
import com.strike.vehicle.BydSdk
import com.strike.vehicle.VehicleApiAccess
import java.io.File

class StrikeApp : Application() {
    internal lateinit var dashboard: DashboardClient
        private set
    internal lateinit var setup: SetupRestart
        private set
    internal lateinit var shell: Shell
        private set

    override fun onCreate() {
        super.onCreate()
        ScratchPaths.init(this)
        FastCamNative.warm()
        Crashes.watch(filesDir)
        val main = Handler(Looper.getMainLooper())
        shell = Shell(this) {
            Thread({
                // Grants before setup restart so the relaunch inherits DiLink 5 API access.
                VehicleApiAccess.ensure(this@StrikeApp, shell)
                BydSdk.ensure(this@StrikeApp)
                main.post {
                    dashboard.authorised()
                    setup.shellAuthorised()
                }
            }, "vehicle-api").start()
        }
        setup = SetupRestart(
            File(filesDir, "setup.pending"),
            !File(filesDir, "adbkey").isFile || !File(filesDir, "adbkey.pub").isFile,
            { shell.retry() }
        ) {
            Thread({
                Logs.d("Shell", "Restarting Strike after USB debugging approval")
                val started = shell.check("nohup sh -c 'am force-stop com.strike; " +
                    "am start -n com.strike/.MainActivity' </dev/null >/dev/null 2>&1 &")
                if (!started) Logs.w("Shell", "Setup is complete. Close and reopen Strike")
            }, "setup-restart").start()
        }
        dashboard = DashboardClient(this, shell)
        Triggers(this, shell).start()
    }
}
