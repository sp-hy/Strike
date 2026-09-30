package com.strike.server

import android.content.Context
import com.strike.core.ScratchPaths
import com.strike.daemon.Shell
import com.strike.daemon.STRIKE_DIR
import java.io.File

internal fun dashboardDirectory(context: Context): String {
    val installed = context.packageManager.getPackageInfo(context.packageName, 0)
    return "$STRIKE_DIR/dashboard-${context.applicationInfo.uid}-${installed.firstInstallTime}"
}

internal fun launchDashboard(context: Context, shell: Shell): Boolean {
    ScratchPaths.ensureStrikeTree()
    val directory = File(dashboardDirectory(context))
    // Older builds left this dir owned by shell with mode 700 → app EACCES on start.sh.
    if (!shell.check(
            ScratchPaths.prepareShellCommand(
                "rm -rf '${directory.absolutePath}'; " +
                    "chmod 777 '${ScratchPaths.getDir()}' '$STRIKE_DIR' 2>/dev/null || true"
            )
        )
    ) {
        return false
    }
    if (!directory.mkdirs()) return false
    openShared(directory)
    val start = File(directory, "start.sh")
    start.writeText(dashboardScript(directory.absolutePath))
    openShared(start)
    return shell.check(
        ScratchPaths.prepareShellCommand(
            "chmod 755 '${directory.absolutePath}' '${start.absolutePath}' 2>/dev/null || true; " +
                "nohup sh '${start.absolutePath}' </dev/null >/dev/null 2>&1 &"
        )
    )
}

private fun openShared(file: File) {
    try {
        file.setReadable(true, false)
        file.setWritable(true, false)
        if (file.isDirectory || file.name.endsWith(".sh")) {
            file.setExecutable(true, false)
        }
    } catch (_: Exception) {
    }
}

internal fun dashboardScript(directory: String): String {
    val scratch = ScratchPaths.getDir()
    val strike = "$scratch/strike".trimEnd('/')
    return """
    #!/system/bin/sh
    umask 022
    export STRIKE_SCRATCH='$scratch'
    export OVERDRIVE_SCRATCH='$scratch'
    export TMPDIR='$scratch'
    mkdir -p '$scratch' '$strike' '$directory' || true
    chmod 777 '$scratch' '$strike' 2>/dev/null || true
    chmod 755 '$directory' 2>/dev/null || true
    failures=0
    while true; do
        apk=${'$'}(pm path com.strike 2>/dev/null | grep '/base.apk${'$'}' | head -n 1 | sed 's/^package://')
        [ -n "${'$'}apk" ] || exit 0
        started=${'$'}(date +%s)
        CLASSPATH="${'$'}apk" app_process /system/bin --nice-name=strike_dashboard com.strike.server.DashboardDaemon '$directory' >> '$directory/daemon.log' 2>&1
        code=${'$'}?
        [ "${'$'}code" -eq 0 ] && exit 0
        [ "${'$'}code" -eq 3 ] && exit 0
        [ "${'$'}code" -eq 42 ] && continue
        elapsed=${'$'}(( ${'$'}(date +%s) - started ))
        if [ "${'$'}elapsed" -ge 300 ]; then failures=0; fi
        failures=${'$'}((failures + 1))
        [ "${'$'}failures" -ge 5 ] && exit 1
        sleep ${'$'}((failures * 3))
    done
""".trimIndent() + "\n"
}
