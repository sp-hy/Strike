package com.strike.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import java.io.File

private const val PREFS = "scratch_paths"
private const val KEY_DIR = "resolved_dir"
private const val ENV_SCRATCH = "STRIKE_SCRATCH"
private const val ENV_OVERDRIVE = "OVERDRIVE_SCRATCH"

/**
 * Shark-only scratch for daemons and shared files (Overdrive-compatible).
 *
 * Always `getExternalFilesDir(null)/daemon` —
 * `/storage/emulated/0/Android/data/<pkg>/files/daemon`. Logs, config, locks,
 * scripts, and TMPDIR / STRIKE_SCRATCH / OVERDRIVE_SCRATCH all go there.
 *
 * Capture executes only from the APK lib dir — never from emulated storage.
 * Prefer app-local writes over adb sync push into this tree.
 */
object ScratchPaths {

    @Volatile private var scratchDir: String = ""
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val external = context.getExternalFilesDir(null)
        scratchDir = join(external?.absolutePath ?: context.filesDir.absolutePath, "daemon")
        // Shell app_process cannot write the app's shared_prefs.
        if (Process.myUid() != 2000) {
            try {
                prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            } catch (_: Exception) {
                prefs = null
            }
        }
        syncFromEnv()
        val cached = try {
            prefs?.getString(KEY_DIR, null)
        } catch (_: Exception) {
            null
        }
        if (cached.isNullOrBlank() || !isExternalFilesDaemon(cached)) {
            persist(scratchDir)
        } else {
            scratchDir = cached
        }
        ensureDir()
        ensureStrikeTree()
        Logs.d("Scratch", "using $scratchDir")
    }

    fun syncFromEnv() {
        val fromEnv = System.getenv(ENV_SCRATCH) ?: System.getenv(ENV_OVERDRIVE)
        if (!fromEnv.isNullOrBlank()) {
            scratchDir = fromEnv
            persist(fromEnv)
        }
    }

    fun getDir(): String {
        System.getenv(ENV_SCRATCH)?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv(ENV_OVERDRIVE)?.takeIf { it.isNotBlank() }?.let { return it }
        if (scratchDir.isNotBlank()) return scratchDir
        return "/storage/emulated/0/Android/data/com.strike/files/daemon"
    }

    fun path(name: String): String = join(getDir(), name)

    fun ensureDir(): File {
        val dir = File(getDir())
        try {
            if (!dir.isDirectory) dir.mkdirs()
            openForShell(dir)
        } catch (_: Exception) {
        }
        return dir
    }

    fun ensureStrikeTree(): File {
        val strike = File(join(getDir(), "strike"))
        try {
            strike.mkdirs()
            openForShell(File(getDir()))
            openForShell(strike)
        } catch (_: Exception) {
        }
        return strike
    }

    fun shellPrefix(): String {
        val dir = getDir()
        val strike = join(dir, "strike")
        return "export STRIKE_SCRATCH='$dir'; export OVERDRIVE_SCRATCH='$dir'; " +
            "export TMPDIR='$dir'; mkdir -p '$dir' '$strike' || true; " +
            "chmod 777 '$dir' '$strike' 2>/dev/null || true; "
    }

    fun prepareShellCommand(command: String): String = shellPrefix() + command

    /** Capture may only run from APK lib — never from emulated storage. */
    fun isExecCapableLocation(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (p.startsWith("/storage/emulated") || p.contains("/mnt/user/") ||
            p.contains("/Android/data/") || p.contains("/Android/obb/")
        ) {
            return false
        }
        return p.startsWith("/data/app/")
    }

    private fun openForShell(dir: File) {
        try {
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        } catch (_: Exception) {
        }
    }

    private fun persist(dir: String) {
        try {
            prefs?.edit()?.putString(KEY_DIR, dir)?.apply()
        } catch (_: Exception) {
        }
    }

    private fun join(base: String, child: String): String =
        base.trimEnd('/') + "/" + child.trimStart('/')

    private fun isExternalFilesDaemon(path: String): Boolean {
        val p = path.replace('\\', '/')
        return p.contains("/Android/data/") && p.endsWith("/files/daemon")
    }
}
